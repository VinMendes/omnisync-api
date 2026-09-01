-- Move papel/permissões de users.resource para roles + user_roles.
-- O JSON do usuário passa a conter somente características próprias (CPF, preferências etc.).

LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE user_roles IN EXCLUSIVE MODE;
LOCK TABLE roles IN EXCLUSIVE MODE;

CREATE TEMP TABLE migrated_user_access ON COMMIT DROP AS
WITH role_defaults(role_name, permissions) AS (
    VALUES
        ('ADMIN', '["PRODUCT_READ","PRODUCT_WRITE","LISTING_PUBLISH","SALE_READ","SALE_WRITE","USER_MANAGE","INTEGRATION_MANAGE","SETTINGS_MANAGE"]'::jsonb),
        ('MANAGER', '["PRODUCT_READ","PRODUCT_WRITE","LISTING_PUBLISH","SALE_READ","SALE_WRITE"]'::jsonb),
        ('SELLER', '["PRODUCT_READ","LISTING_PUBLISH","SALE_READ","SALE_WRITE"]'::jsonb),
        ('VIEWER', '["PRODUCT_READ","SALE_READ"]'::jsonb)
),
permission_aliases(alias, code) AS (
    VALUES
        ('product_read', 'PRODUCT_READ'),
        ('product_write', 'PRODUCT_WRITE'),
        ('listing_publish', 'LISTING_PUBLISH'),
        ('sale_read', 'SALE_READ'),
        ('sale_write', 'SALE_WRITE'),
        ('user_manage', 'USER_MANAGE'),
        ('integration_manage', 'INTEGRATION_MANAGE'),
        ('settings_manage', 'SETTINGS_MANAGE'),
        ('acesso total', 'PRODUCT_READ'),
        ('acesso total', 'PRODUCT_WRITE'),
        ('acesso total', 'LISTING_PUBLISH'),
        ('acesso total', 'SALE_READ'),
        ('acesso total', 'SALE_WRITE'),
        ('acesso total', 'USER_MANAGE'),
        ('acesso total', 'INTEGRATION_MANAGE'),
        ('acesso total', 'SETTINGS_MANAGE'),
        ('full access', 'PRODUCT_READ'),
        ('full access', 'PRODUCT_WRITE'),
        ('full access', 'LISTING_PUBLISH'),
        ('full access', 'SALE_READ'),
        ('full access', 'SALE_WRITE'),
        ('full access', 'USER_MANAGE'),
        ('full access', 'INTEGRATION_MANAGE'),
        ('full access', 'SETTINGS_MANAGE'),
        ('gestão de estoque', 'PRODUCT_READ'),
        ('gestão de estoque', 'PRODUCT_WRITE'),
        ('stock management', 'PRODUCT_READ'),
        ('stock management', 'PRODUCT_WRITE'),
        ('anúncios', 'PRODUCT_READ'),
        ('anúncios', 'LISTING_PUBLISH'),
        ('listings', 'PRODUCT_READ'),
        ('listings', 'LISTING_PUBLISH'),
        ('vendas', 'SALE_READ'),
        ('vendas', 'SALE_WRITE'),
        ('orders', 'SALE_READ'),
        ('orders', 'SALE_WRITE'),
        ('faturamento', 'SALE_READ'),
        ('billing', 'SALE_READ'),
        ('gestão de usuários', 'USER_MANAGE'),
        ('user management', 'USER_MANAGE'),
        ('marketplaces', 'INTEGRATION_MANAGE'),
        ('atividade', 'PRODUCT_READ'),
        ('atividade', 'SALE_READ'),
        ('activity', 'PRODUCT_READ'),
        ('activity', 'SALE_READ'),
        ('somente leitura', 'PRODUCT_READ'),
        ('somente leitura', 'SALE_READ'),
        ('view only', 'PRODUCT_READ'),
        ('view only', 'SALE_READ')
),
legacy AS (
    SELECT id,
           system_client_id,
           CASE
               WHEN resource IS NULL OR resource = 'null'::jsonb THEN '{}'::jsonb
               WHEN jsonb_typeof(resource) = 'object' THEN resource
               ELSE jsonb_build_object('_legacy_resource', resource)
           END AS metadata,
           row_number() OVER (PARTITION BY system_client_id ORDER BY created_at, id) AS company_position
    FROM users
),
normalized AS (
    SELECT *,
           CASE upper(btrim(metadata ->> 'role'))
               WHEN 'ADMIN' THEN 'ADMIN'
               WHEN 'MANAGER' THEN 'MANAGER'
               WHEN 'SELLER' THEN 'SELLER'
               WHEN 'EDITOR' THEN 'SELLER'
               WHEN 'VIEWER' THEN 'VIEWER'
               ELSE NULL
           END AS previous_role
    FROM legacy
),
assigned AS (
    SELECT *, coalesce(previous_role,
                       CASE WHEN company_position = 1 THEN 'ADMIN' ELSE 'VIEWER' END) AS role_name
    FROM normalized
)
SELECT assigned.id AS user_id,
       assigned.system_client_id,
       assigned.role_name,
       assigned.metadata - 'role' - 'permissions' AS user_resource,
       CASE
           WHEN assigned.previous_role IS NULL
                OR assigned.metadata -> 'permissions' IS NULL
                OR assigned.metadata -> 'permissions' = 'null'::jsonb
               THEN defaults.permissions
           WHEN jsonb_typeof(assigned.metadata -> 'permissions') <> 'array' THEN '[]'::jsonb
           ELSE coalesce((
               SELECT jsonb_agg(parsed.code ORDER BY parsed.code)
               FROM (
                   SELECT DISTINCT aliases.code
                   FROM jsonb_array_elements(assigned.metadata -> 'permissions') AS entry(value)
                   JOIN permission_aliases aliases
                     ON aliases.alias = lower(btrim(entry.value #>> '{}'))
                   WHERE jsonb_typeof(entry.value) = 'string'
               ) parsed
           ), '[]'::jsonb)
       END AS permissions
FROM assigned
JOIN role_defaults defaults ON defaults.role_name = assigned.role_name;

-- Duas permissões diferentes para o mesmo papel/empresa não podem ser achatadas silenciosamente.
-- A migração para com uma mensagem clara para que o conflito seja decidido pelo time.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM migrated_user_access
        GROUP BY system_client_id, role_name
        HAVING count(DISTINCT permissions::text) > 1
    ) THEN
        RAISE EXCEPTION
            'Existem usuários da mesma empresa e role com permissões divergentes. Ajuste os dados antes da V8.';
    END IF;
END
$$;

-- Até a V7 essas tabelas não eram fonte de verdade. Elas são reconstruídas a partir de users.resource.
DELETE FROM user_roles;
DELETE FROM roles;

ALTER TABLE roles DROP CONSTRAINT IF EXISTS roles_name_key;

ALTER TABLE roles
    ALTER COLUMN resource SET NOT NULL,
    ADD CONSTRAINT ck_roles_canonical_name
        CHECK (name IN ('ADMIN', 'MANAGER', 'SELLER', 'VIEWER')),
    ADD CONSTRAINT ck_roles_resource_permissions
        CHECK (jsonb_typeof(resource) = 'object'
               AND jsonb_typeof(resource -> 'permissions') = 'array'),
    ADD CONSTRAINT uq_roles_system_client_name UNIQUE (system_client_id, name);

WITH role_defaults(role_name, permissions) AS (
    VALUES
        ('ADMIN', '["PRODUCT_READ","PRODUCT_WRITE","LISTING_PUBLISH","SALE_READ","SALE_WRITE","USER_MANAGE","INTEGRATION_MANAGE","SETTINGS_MANAGE"]'::jsonb),
        ('MANAGER', '["PRODUCT_READ","PRODUCT_WRITE","LISTING_PUBLISH","SALE_READ","SALE_WRITE"]'::jsonb),
        ('SELLER', '["PRODUCT_READ","LISTING_PUBLISH","SALE_READ","SALE_WRITE"]'::jsonb),
        ('VIEWER', '["PRODUCT_READ","SALE_READ"]'::jsonb)
)
INSERT INTO roles(name, resource, system_client_id)
SELECT defaults.role_name,
       jsonb_build_object(
           'permissions',
           coalesce((
               SELECT access.permissions
               FROM migrated_user_access access
               WHERE access.system_client_id = client.id
                 AND access.role_name = defaults.role_name
               LIMIT 1
           ), defaults.permissions)
       ),
       client.id
FROM system_client client
CROSS JOIN role_defaults defaults;

INSERT INTO user_roles(user_id, role_id)
SELECT access.user_id, role.id
FROM migrated_user_access access
JOIN roles role
  ON role.system_client_id = access.system_client_id
 AND role.name = access.role_name;

UPDATE users account
SET resource = access.user_resource
FROM migrated_user_access access
WHERE account.id = access.user_id;

ALTER TABLE users
    ALTER COLUMN resource SET DEFAULT '{}'::jsonb,
    ALTER COLUMN resource SET NOT NULL;

ALTER TABLE user_roles
    ADD CONSTRAINT uq_user_roles_user UNIQUE (user_id);

CREATE OR REPLACE FUNCTION enforce_user_role_same_tenant()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM users account
        JOIN roles role ON role.id = NEW.role_id
        WHERE account.id = NEW.user_id
          AND account.system_client_id = role.system_client_id
    ) THEN
        RAISE EXCEPTION 'Usuário e role devem pertencer à mesma empresa.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_user_roles_same_tenant
BEFORE INSERT OR UPDATE ON user_roles
FOR EACH ROW EXECUTE FUNCTION enforce_user_role_same_tenant();

-- Toda empresa criada depois da migração já nasce com seu catálogo básico de papéis.
CREATE OR REPLACE FUNCTION seed_default_tenant_roles()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO roles(name, resource, system_client_id)
    VALUES
        ('ADMIN', jsonb_build_object('permissions',
            '["PRODUCT_READ","PRODUCT_WRITE","LISTING_PUBLISH","SALE_READ","SALE_WRITE","USER_MANAGE","INTEGRATION_MANAGE","SETTINGS_MANAGE"]'::jsonb), NEW.id),
        ('MANAGER', jsonb_build_object('permissions',
            '["PRODUCT_READ","PRODUCT_WRITE","LISTING_PUBLISH","SALE_READ","SALE_WRITE"]'::jsonb), NEW.id),
        ('SELLER', jsonb_build_object('permissions',
            '["PRODUCT_READ","LISTING_PUBLISH","SALE_READ","SALE_WRITE"]'::jsonb), NEW.id),
        ('VIEWER', jsonb_build_object('permissions',
            '["PRODUCT_READ","SALE_READ"]'::jsonb), NEW.id)
    ON CONFLICT (system_client_id, name) DO NOTHING;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_system_client_default_roles
AFTER INSERT ON system_client
FOR EACH ROW EXECUTE FUNCTION seed_default_tenant_roles();
