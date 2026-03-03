ALTER TABLE marketplace_integrations
    ADD CONSTRAINT uk_client_marketplace
        UNIQUE (system_client_id, marketplace);
