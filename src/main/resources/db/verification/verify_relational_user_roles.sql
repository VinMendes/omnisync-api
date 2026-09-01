-- Deve retornar zero linhas após a V8.
WITH relationship_problems AS (
    SELECT 'USER_ROLE'::text AS entity_type,
           account.id AS entity_id,
           CASE
               WHEN count(link.role_id) = 0 THEN 'Usuário sem role'
               WHEN count(link.role_id) > 1 THEN 'Usuário com mais de uma role'
               WHEN bool_or(role.system_client_id <> account.system_client_id) THEN 'Role de outra empresa'
           END AS problem
    FROM users account
    LEFT JOIN user_roles link ON link.user_id = account.id
    LEFT JOIN roles role ON role.id = link.role_id
    GROUP BY account.id
    HAVING count(link.role_id) <> 1
        OR bool_or(role.system_client_id <> account.system_client_id)
),
role_relationship_problems AS (
    SELECT 'ROLE_LINK'::text AS entity_type,
           role.id AS entity_id,
           CASE
               WHEN count(link.user_id) = 0 THEN 'Role sem usuário'
               WHEN count(link.user_id) > 1 THEN 'Role compartilhada por mais de um usuário'
           END AS problem
    FROM roles role
    LEFT JOIN user_roles link ON link.role_id = role.id
    GROUP BY role.id
    HAVING count(link.user_id) <> 1
),
user_resource_problems AS (
    SELECT 'USER_RESOURCE'::text AS entity_type,
           id AS entity_id,
           'resource inválido ou ainda contém role/permissions'::text AS problem
    FROM users
    WHERE jsonb_typeof(resource) IS DISTINCT FROM 'object'
       OR resource ? 'role'
       OR resource ? 'permissions'
),
role_problems AS (
    SELECT 'ROLE'::text AS entity_type,
           id AS entity_id,
           'role ou permissions inválidas'::text AS problem
    FROM roles
    WHERE name NOT IN ('ADMIN', 'MANAGER', 'SELLER', 'VIEWER')
       OR jsonb_typeof(resource) IS DISTINCT FROM 'object'
       OR jsonb_typeof(resource -> 'permissions') IS DISTINCT FROM 'array'
       OR EXISTS (
           SELECT 1
           FROM jsonb_array_elements(
               CASE WHEN jsonb_typeof(resource -> 'permissions') = 'array'
                    THEN resource -> 'permissions' ELSE '[]'::jsonb END
           ) entry(value)
           WHERE jsonb_typeof(entry.value) <> 'string'
              OR entry.value #>> '{}' NOT IN (
                  'PRODUCT_READ', 'PRODUCT_WRITE', 'LISTING_PUBLISH', 'SALE_READ', 'SALE_WRITE',
                  'USER_MANAGE', 'INTEGRATION_MANAGE', 'SETTINGS_MANAGE'
              )
       )
)
SELECT * FROM relationship_problems
UNION ALL
SELECT * FROM role_relationship_problems
UNION ALL
SELECT * FROM user_resource_problems
UNION ALL
SELECT * FROM role_problems;
