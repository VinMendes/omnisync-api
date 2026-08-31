-- Contrato canônico do UserResource, sem criar ou utilizar novas tabelas.
-- O backfill ocorre apenas uma vez. Primeiro usuário = menor (created_at, id) de cada empresa.
-- Roles válidas são preservadas; sem role válida: primeiro ADMIN, demais VIEWER.
-- EDITOR é apenas o nome legado de SELLER. Nenhum campo de negócio fora do resource é alterado.
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
    SELECT *, coalesce(previous_role, CASE WHEN company_position = 1 THEN 'ADMIN' ELSE 'VIEWER' END) AS role_name
    FROM normalized
)
UPDATE users account
SET resource = assigned.metadata || jsonb_build_object(
        'role', assigned.role_name,
        'permissions', CASE
            -- Sem papel válido, os textos de permissão antigos não determinam privilégios.
            WHEN assigned.previous_role IS NULL
                 OR assigned.metadata -> 'permissions' IS NULL
                 OR assigned.metadata -> 'permissions' = 'null'::jsonb
                THEN defaults.permissions
            -- Um valor malformado não pode ampliar o acesso; lista vazia explícita permanece vazia.
            WHEN jsonb_typeof(assigned.metadata -> 'permissions') <> 'array' THEN '[]'::jsonb
            ELSE coalesce((
                SELECT jsonb_agg(DISTINCT aliases.code ORDER BY aliases.code)
                FROM jsonb_array_elements(
                    CASE WHEN jsonb_typeof(assigned.metadata -> 'permissions') = 'array'
                         THEN assigned.metadata -> 'permissions' ELSE '[]'::jsonb END
                ) AS entry(value)
                JOIN permission_aliases aliases ON aliases.alias = lower(btrim(entry.value #>> '{}'))
                WHERE jsonb_typeof(entry.value) = 'string'
            ), '[]'::jsonb)
        END
    )
FROM assigned
JOIN role_defaults defaults ON defaults.role_name = assigned.role_name
WHERE account.id = assigned.id;
