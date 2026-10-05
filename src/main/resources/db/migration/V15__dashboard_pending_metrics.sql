CREATE TABLE inventory_history (
    id BIGSERIAL PRIMARY KEY,
    system_client_id BIGINT NOT NULL REFERENCES system_client(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(12,2) NOT NULL,
    active BOOLEAN NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
);

CREATE INDEX idx_inventory_history_tenant_time
    ON inventory_history(system_client_id, recorded_at, product_id, id DESC);

CREATE INDEX idx_sales_activity_hour
    ON sales(system_client_id, channel, status, created_at);

INSERT INTO inventory_history(system_client_id, product_id, quantity, unit_price, active, recorded_at)
SELECT system_client_id, id, stock, price, active, CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
FROM products;

CREATE OR REPLACE FUNCTION record_inventory_history()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        INSERT INTO inventory_history(system_client_id, product_id, quantity, unit_price, active)
        VALUES (OLD.system_client_id, OLD.id, 0, OLD.price, FALSE);
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT'
       OR NEW.stock IS DISTINCT FROM OLD.stock
       OR NEW.price IS DISTINCT FROM OLD.price
       OR NEW.active IS DISTINCT FROM OLD.active
       OR NEW.system_client_id IS DISTINCT FROM OLD.system_client_id THEN
        INSERT INTO inventory_history(system_client_id, product_id, quantity, unit_price, active)
        VALUES (NEW.system_client_id, NEW.id, NEW.stock, NEW.price, NEW.active);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_products_inventory_history
AFTER INSERT OR UPDATE OF stock, price, active, system_client_id OR DELETE ON products
FOR EACH ROW EXECUTE FUNCTION record_inventory_history();
