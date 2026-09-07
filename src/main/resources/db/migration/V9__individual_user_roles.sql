-- Converte o catálogo de roles da V8 em uma configuração exclusiva por usuário.
-- Usuários com o mesmo papel podem, a partir desta versão, possuir permissões diferentes.

LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE user_roles IN EXCLUSIVE MODE;
LOCK TABLE roles IN EXCLUSIVE MODE;

CREATE TEMP TABLE migrated_individual_roles ON COMMIT DROP AS
SELECT account.id AS user_id,
       account.system_client_id,
       role.system_client_id AS role_system_client_id,
       role.name AS role_name,
       role.resource AS role_resource
FROM users account
JOIN user_roles link ON link.user_id = account.id
JOIN roles role ON role.id = link.role_id;

DO $$
BEGIN
    IF (SELECT count(*) FROM migrated_individual_roles) <> (SELECT count(*) FROM users) THEN
        RAISE EXCEPTION 'Todo usuário deve possuir exatamente uma role antes da V9.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM migrated_individual_roles access
        WHERE access.system_client_id <> access.role_system_client_id
    ) THEN
        RAISE EXCEPTION 'Existem vínculos de usuário e role entre empresas diferentes antes da V9.';
    END IF;
END
$$;

-- Empresas futuras não recebem mais um catálogo fixo de quatro roles.
DROP TRIGGER IF EXISTS trg_system_client_default_roles ON system_client;
DROP FUNCTION IF EXISTS seed_default_tenant_roles();

DELETE FROM user_roles;
DELETE FROM roles;

ALTER TABLE roles DROP CONSTRAINT IF EXISTS uq_roles_system_client_name;
CREATE INDEX idx_roles_system_client_name ON roles(system_client_id, name);

-- O marcador temporário correlaciona o novo id da role ao usuário de origem.
INSERT INTO roles(name, resource, system_client_id)
SELECT access.role_name,
       access.role_resource || jsonb_build_object('_migration_user_id', access.user_id),
       access.system_client_id
FROM migrated_individual_roles access
ORDER BY access.user_id;

INSERT INTO user_roles(user_id, role_id)
SELECT (role.resource ->> '_migration_user_id')::bigint, role.id
FROM roles role
WHERE role.resource ? '_migration_user_id';

UPDATE roles
SET resource = resource - '_migration_user_id'
WHERE resource ? '_migration_user_id';

ALTER TABLE user_roles
    ADD CONSTRAINT uq_user_roles_role UNIQUE (role_id);

DO $$
BEGIN
    IF (SELECT count(*) FROM roles) <> (SELECT count(*) FROM users)
       OR (SELECT count(*) FROM user_roles) <> (SELECT count(*) FROM users) THEN
        RAISE EXCEPTION 'A V9 não conseguiu criar exatamente uma role exclusiva para cada usuário.';
    END IF;
END
$$;
