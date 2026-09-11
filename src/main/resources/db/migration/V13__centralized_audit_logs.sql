-- Audit snapshots intentionally survive user deletion; actor identity is historical.
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    system_client_id BIGINT NOT NULL REFERENCES system_client(id),
    user_id BIGINT,
    user_name VARCHAR(150) NOT NULL,
    user_email VARCHAR(150),
    user_role VARCHAR(50) NOT NULL,
    action VARCHAR(30) NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    entity_id VARCHAR(150) NOT NULL,
    description TEXT NOT NULL,
    previous_data JSONB,
    new_data JSONB,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    CONSTRAINT audit_previous_object CHECK (previous_data IS NULL OR jsonb_typeof(previous_data) = 'object'),
    CONSTRAINT audit_new_object CHECK (new_data IS NULL OR jsonb_typeof(new_data) = 'object'),
    CONSTRAINT audit_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);
CREATE INDEX idx_audit_tenant_time ON audit_logs(system_client_id, created_at DESC, id DESC);
CREATE INDEX idx_audit_tenant_user_time ON audit_logs(system_client_id, user_id, created_at DESC, id DESC);
CREATE INDEX idx_audit_tenant_role_time ON audit_logs(system_client_id, user_role, created_at DESC, id DESC);
CREATE INDEX idx_audit_tenant_action_time ON audit_logs(system_client_id, action, created_at DESC, id DESC);
CREATE INDEX idx_audit_tenant_entity_time ON audit_logs(system_client_id, entity_type, created_at DESC, id DESC);
