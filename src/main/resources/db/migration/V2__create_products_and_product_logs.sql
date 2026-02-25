-- =========================================
-- PRODUCTS
-- =========================================

CREATE TABLE products (
                          id BIGSERIAL PRIMARY KEY,
                          system_client_id BIGINT NOT NULL,
                          sku VARCHAR(100) NOT NULL,
                          name VARCHAR(255) NOT NULL,
                          description TEXT,
                          stock INTEGER NOT NULL DEFAULT 0,
                          reserved_stock INTEGER NOT NULL DEFAULT 0,
                          price NUMERIC(12,2) NOT NULL,
                          resource JSONB,
                          active BOOLEAN NOT NULL DEFAULT TRUE,
                          created_at TIMESTAMP NOT NULL DEFAULT NOW(),

                          CONSTRAINT fk_products_system_client
                              FOREIGN KEY (system_client_id)
                                  REFERENCES system_client(id)
                                  ON DELETE CASCADE
);

-- SKU único por tenant
CREATE UNIQUE INDEX uk_products_sku_per_client
    ON products(system_client_id, sku);

CREATE INDEX idx_products_system_client
    ON products(system_client_id);


-- =========================================
-- PRODUCT LOGS (auditoria)
-- =========================================

CREATE TABLE product_logs (
                              id BIGSERIAL PRIMARY KEY,
                              product_id BIGINT NOT NULL,
                              system_client_id BIGINT NOT NULL,
                              action VARCHAR(50) NOT NULL, -- CREATED, UPDATED, STOCK_ADJUSTED
                              old_stock INTEGER,
                              new_stock INTEGER,
                              old_price NUMERIC(12,2),
                              new_price NUMERIC(12,2),
                              metadata JSONB,
                              created_at TIMESTAMP NOT NULL DEFAULT NOW(),

                              CONSTRAINT fk_product_logs_product
                                  FOREIGN KEY (product_id)
                                      REFERENCES products(id)
                                      ON DELETE CASCADE,

                              CONSTRAINT fk_product_logs_system_client
                                  FOREIGN KEY (system_client_id)
                                      REFERENCES system_client(id)
                                      ON DELETE CASCADE
);

CREATE INDEX idx_product_logs_product
    ON product_logs(product_id);