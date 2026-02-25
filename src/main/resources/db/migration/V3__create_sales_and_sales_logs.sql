-- =========================================
-- SALES
-- =========================================

CREATE TABLE sales (
                       id BIGSERIAL PRIMARY KEY,
                       system_client_id BIGINT NOT NULL,
                       product_id BIGINT NOT NULL,
                       quantity INTEGER NOT NULL,
                       total_value NUMERIC(12,2) NOT NULL,
                       resource JSONB,
                       channel VARCHAR(30) NOT NULL, -- MERCADO_LIVRE, SHOPEE, AMAZON, PHYSICAL
                       external_reference_id VARCHAR(150),
                       status VARCHAR(30) NOT NULL, -- CONFIRMED, CANCELLED
                       created_at TIMESTAMP NOT NULL DEFAULT NOW(),

                       CONSTRAINT fk_sales_system_client
                           FOREIGN KEY (system_client_id)
                               REFERENCES system_client(id)
                               ON DELETE CASCADE,

                       CONSTRAINT fk_sales_product
                           FOREIGN KEY (product_id)
                               REFERENCES products(id)
                               ON DELETE CASCADE
);

CREATE INDEX idx_sales_system_client
    ON sales(system_client_id);

CREATE INDEX idx_sales_product
    ON sales(product_id);

CREATE INDEX idx_sales_channel
    ON sales(channel);


-- =========================================
-- SALES LOGS (auditoria)
-- =========================================

CREATE TABLE sales_logs (
                            id BIGSERIAL PRIMARY KEY,
                            sale_id BIGINT NOT NULL,
                            system_client_id BIGINT NOT NULL,
                            action VARCHAR(50) NOT NULL, -- CREATED, CANCELLED
                            previous_status VARCHAR(30),
                            resource JSONB,
                            new_status VARCHAR(30),
                            metadata JSONB,
                            created_at TIMESTAMP NOT NULL DEFAULT NOW(),

                            CONSTRAINT fk_sales_logs_sale
                                FOREIGN KEY (sale_id)
                                    REFERENCES sales(id)
                                    ON DELETE CASCADE,

                            CONSTRAINT fk_sales_logs_system_client
                                FOREIGN KEY (system_client_id)
                                    REFERENCES system_client(id)
                                    ON DELETE CASCADE
);

CREATE INDEX idx_sales_logs_sale
    ON sales_logs(sale_id);