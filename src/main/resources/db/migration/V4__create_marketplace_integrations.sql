CREATE TABLE marketplace_integrations (
                                          id BIGSERIAL PRIMARY KEY,
                                          system_client_id BIGINT NOT NULL,
                                          marketplace VARCHAR(30) NOT NULL,

                                          access_token TEXT NOT NULL,
                                          refresh_token TEXT,
                                          expires_at TIMESTAMP NOT NULL,

                                          resource JSONB,

                                          active BOOLEAN NOT NULL DEFAULT TRUE,
                                          created_at TIMESTAMP NOT NULL DEFAULT NOW(),

                                          CONSTRAINT fk_marketplace_integrations_client
                                              FOREIGN KEY (system_client_id)
                                                  REFERENCES system_client(id)
                                                  ON DELETE CASCADE
);