-- Reconcilia alterações legadas salvas em users.resource depois da V8.
-- Após esta migration, papel/permissões existem somente na role exclusiva do usuário.

LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE user_roles IN EXCLUSIVE MODE;
LOCK TABLE roles IN EXCLUSIVE MODE;

CREATE TEMP TABLE reconciled_legacy_access ON COMMIT DROP AS
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
current_access AS (
    SELECT account.id AS user_id,
           role.id AS role_id,
           role.name AS current_role_name,
           role.resource AS current_role_resource,
           CASE
               WHEN jsonb_typeof(account.resource) = 'object' THEN account.resource
               ELSE jsonb_build_object('_legacy_resource', account.resource)
           END AS metadata
    FROM users account
    JOIN user_roles link ON link.user_id = account.id
    JOIN roles role ON role.id = link.role_id
),
selected_access AS (
    SELECT *,
           CASE upper(btrim(metadata ->> 'role'))
               WHEN 'ADMIN' THEN 'ADMIN'
               WHEN 'MANAGER' THEN 'MANAGER'
               WHEN 'SELLER' THEN 'SELLER'
               WHEN 'EDITOR' THEN 'SELLER'
               WHEN 'VIEWER' THEN 'VIEWER'
               ELSE current_role_name
           END AS selected_role_name
    FROM current_access
)
SELECT access.user_id,
       access.role_id,
       access.selected_role_name AS role_name,
       CASE
           WHEN jsonb_typeof(access.metadata -> 'permissions') = 'array' THEN coalesce((
               SELECT jsonb_agg(parsed.code ORDER BY parsed.code)
               FROM (
                   SELECT DISTINCT aliases.code
                   FROM jsonb_array_elements(access.metadata -> 'permissions') AS entry(value)
                   JOIN permission_aliases aliases
                     ON aliases.alias = lower(btrim(entry.value #>> '{}'))
                   WHERE jsonb_typeof(entry.value) = 'string'
               ) parsed
           ), '[]'::jsonb)
           WHEN access.selected_role_name = access.current_role_name
               THEN access.current_role_resource -> 'permissions'
           ELSE defaults.permissions
       END AS permissions,
       access.metadata - 'role' - 'permissions' AS user_resource
FROM selected_access access
JOIN role_defaults defaults ON defaults.role_name = access.selected_role_name;

UPDATE roles role
SET name = access.role_name,
    resource = jsonb_set(role.resource, '{permissions}', access.permissions, true)
FROM reconciled_legacy_access access
WHERE role.id = access.role_id;

UPDATE users account
SET resource = access.user_resource
FROM reconciled_legacy_access access
WHERE account.id = access.user_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM users
        WHERE jsonb_typeof(resource) IS DISTINCT FROM 'object'
           OR resource ? 'role'
           OR resource ? 'permissions'
    ) THEN
        RAISE EXCEPTION 'A V10 não conseguiu remover todos os campos de acesso de users.resource.';
    END IF;
END
$$;
