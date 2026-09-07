CREATE INDEX idx_products_dashboard_metrics
    ON products(system_client_id, active, created_at);

CREATE INDEX idx_sales_dashboard_metrics
    ON sales(system_client_id, status, created_at);

CREATE INDEX idx_sales_logs_dashboard_confirmation
    ON sales_logs(system_client_id, sale_id, new_status, created_at);
