-- Consulta somente leitura. Após V7 e gravações pelo contrato tipado, deve retornar zero linhas.
SELECT id, system_client_id, resource ->> 'role' AS role
FROM users
WHERE jsonb_typeof(resource) IS DISTINCT FROM 'object'
   OR coalesce(resource ->> 'role', '') NOT IN ('ADMIN', 'MANAGER', 'SELLER', 'VIEWER')
   OR jsonb_typeof(resource -> 'permissions') IS DISTINCT FROM 'array'
   OR EXISTS (
       SELECT 1
       FROM jsonb_array_elements(
           CASE WHEN jsonb_typeof(resource -> 'permissions') = 'array'
                THEN resource -> 'permissions' ELSE '[]'::jsonb END
       ) AS entry(value)
       WHERE jsonb_typeof(entry.value) <> 'string'
          OR entry.value #>> '{}' NOT IN (
              'PRODUCT_READ', 'PRODUCT_WRITE', 'LISTING_PUBLISH', 'SALE_READ', 'SALE_WRITE',
              'USER_MANAGE', 'INTEGRATION_MANAGE', 'SETTINGS_MANAGE'
          )
   );
