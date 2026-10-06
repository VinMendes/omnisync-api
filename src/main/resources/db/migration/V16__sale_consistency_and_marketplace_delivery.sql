DO $$
DECLARE
    duplicate_keys BIGINT;
    blank_references BIGINT;
BEGIN
    SELECT COUNT(*)
      INTO duplicate_keys
      FROM (
          SELECT system_client_id, channel, BTRIM(external_reference_id)
            FROM sales
           WHERE external_reference_id IS NOT NULL
           GROUP BY system_client_id, channel, BTRIM(external_reference_id)
          HAVING COUNT(*) > 1
      ) duplicates;

    SELECT COUNT(*)
      INTO blank_references
      FROM sales
     WHERE external_reference_id IS NOT NULL
       AND BTRIM(external_reference_id) = '';

    IF duplicate_keys > 0 OR blank_references > 0 THEN
        RAISE EXCEPTION
            'V16 aborted: idempotency preflight found % duplicate key group(s) and % blank external reference(s)',
            duplicate_keys,
            blank_references;
    END IF;
END $$;

DO $$
DECLARE
    invalid_sales BIGINT;
    invalid_products BIGINT;
BEGIN
    SELECT COUNT(*)
      INTO invalid_sales
      FROM sales
     WHERE quantity <= 0
        OR total_value < 0;

    SELECT COUNT(*)
      INTO invalid_products
      FROM products
     WHERE stock < 0
        OR reserved_stock < 0
        OR stock < reserved_stock;

    IF invalid_sales > 0 OR invalid_products > 0 THEN
        RAISE EXCEPTION
            'V16 aborted: invalid history contains % sale row(s) and % product row(s)',
            invalid_sales,
            invalid_products;
    END IF;
END $$;

UPDATE sales
   SET external_reference_id = BTRIM(external_reference_id)
 WHERE external_reference_id IS NOT NULL;

ALTER TABLE sales
    ADD CONSTRAINT ck_sales_quantity_positive CHECK (quantity > 0),
    ADD CONSTRAINT ck_sales_total_value_nonnegative CHECK (total_value >= 0);

ALTER TABLE products
    ADD CONSTRAINT ck_products_stock_nonnegative CHECK (stock >= 0),
    ADD CONSTRAINT ck_products_reserved_stock_nonnegative CHECK (reserved_stock >= 0),
    ADD CONSTRAINT ck_products_stock_covers_reserved CHECK (stock >= reserved_stock);

CREATE UNIQUE INDEX uk_sales_idempotency_key
    ON sales(system_client_id, channel, external_reference_id)
    WHERE external_reference_id IS NOT NULL;

CREATE TABLE marketplace_stock_sync_outbox (
    id BIGSERIAL PRIMARY KEY,
    system_client_id BIGINT NOT NULL,
    sale_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    marketplace VARCHAR(30) NOT NULL,
    operation VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(120),
    last_attempt_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    last_error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_stock_sync_outbox_system_client
        FOREIGN KEY (system_client_id) REFERENCES system_client(id) ON DELETE CASCADE,
    CONSTRAINT fk_stock_sync_outbox_sale
        FOREIGN KEY (sale_id) REFERENCES sales(id) ON DELETE CASCADE,
    CONSTRAINT fk_stock_sync_outbox_product
        FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT uk_stock_sync_outbox_delivery
        UNIQUE (sale_id, marketplace, operation),
    CONSTRAINT ck_stock_sync_outbox_operation
        CHECK (operation IN ('STOCK_UPDATE')),
    CONSTRAINT ck_stock_sync_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'SUCCEEDED', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_stock_sync_outbox_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_stock_sync_outbox_claim
    ON marketplace_stock_sync_outbox(status, next_attempt_at, id);

CREATE INDEX idx_stock_sync_outbox_tenant_product_status
    ON marketplace_stock_sync_outbox(system_client_id, product_id, status);

CREATE TABLE marketplace_webhook_inbox (
    id BIGSERIAL PRIMARY KEY,
    event_key CHAR(64) NOT NULL,
    provider_event_id VARCHAR(120),
    marketplace VARCHAR(30) NOT NULL,
    system_client_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    marketplace_user_id VARCHAR(120) NOT NULL,
    topic VARCHAR(80) NOT NULL,
    resource VARCHAR(500) NOT NULL,
    provider_sent_at TIMESTAMPTZ,
    status VARCHAR(40) NOT NULL DEFAULT 'RECEIVED',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(120),
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    last_error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_webhook_inbox_system_client
        FOREIGN KEY (system_client_id) REFERENCES system_client(id) ON DELETE CASCADE,
    CONSTRAINT uk_webhook_inbox_event_key UNIQUE (event_key),
    CONSTRAINT ck_webhook_inbox_status
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'RETRY_WAIT', 'SUCCEEDED', 'REJECTED', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_webhook_inbox_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_webhook_inbox_claim
    ON marketplace_webhook_inbox(status, next_attempt_at, id);

CREATE INDEX idx_webhook_inbox_tenant_status
    ON marketplace_webhook_inbox(system_client_id, status);
