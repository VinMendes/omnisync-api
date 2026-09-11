ALTER TABLE products
    ADD COLUMN minimum_stock INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_products_minimum_stock_non_negative CHECK (minimum_stock >= 0);

CREATE INDEX idx_products_low_stock
    ON products(system_client_id, id)
    WHERE active = TRUE;

CREATE INDEX idx_product_logs_dashboard_recent
    ON product_logs(system_client_id, created_at DESC);

CREATE INDEX idx_sales_logs_dashboard_recent
    ON sales_logs(system_client_id, created_at DESC);
