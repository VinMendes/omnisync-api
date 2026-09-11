ALTER TABLE marketplace_integrations
    ADD COLUMN last_sync_at TIMESTAMPTZ NULL;

DO $$
DECLARE
    duplicate_groups BIGINT;
BEGIN
    SELECT COUNT(*)
      INTO duplicate_groups
      FROM (
          SELECT system_client_id,
                 resource -> 'mercado_livre' ->> 'item_id' AS item_id
            FROM products
           WHERE NULLIF(BTRIM(resource -> 'mercado_livre' ->> 'item_id'), '') IS NOT NULL
           GROUP BY system_client_id, resource -> 'mercado_livre' ->> 'item_id'
          HAVING COUNT(*) > 1
      ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V12 aborted: % tenant/item identity group(s) contain duplicate Mercado Livre products',
            duplicate_groups;
    END IF;
END $$;

CREATE UNIQUE INDEX uk_products_client_ml_item_id
    ON products (system_client_id, ((resource -> 'mercado_livre' ->> 'item_id')))
    WHERE NULLIF(BTRIM(resource -> 'mercado_livre' ->> 'item_id'), '') IS NOT NULL;
