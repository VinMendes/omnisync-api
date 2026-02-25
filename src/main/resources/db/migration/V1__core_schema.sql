-- =========================================
-- OmniSync - Core Schema (V1)
-- =========================================

-- ================================
-- SYSTEM CLIENT (TENANT)
-- ================================
CREATE TABLE system_client (
                               id BIGSERIAL PRIMARY KEY,
                               name VARCHAR(150) NOT NULL,
                               document VARCHAR(20) NOT NULL,
                               resource JSONB,
                               created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                               active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_system_client_document
    ON system_client(document);

-- ================================
-- ROLES
-- ================================
CREATE TABLE roles (
                       id BIGSERIAL PRIMARY KEY,
                       name VARCHAR(50) NOT NULL UNIQUE,
                       resource JSONB,
                       system_client_id BIGINT NOT NULL,

                       CONSTRAINT fk_roles_system_client
                           FOREIGN KEY (system_client_id)
                               REFERENCES system_client(id)
                               ON DELETE CASCADE
);

-- ================================
-- USERS
-- ================================
CREATE TABLE users (
                       id BIGSERIAL PRIMARY KEY,
                       system_client_id BIGINT NOT NULL,
                       name VARCHAR(150) NOT NULL,
                       email VARCHAR(150) NOT NULL UNIQUE,
                       resource JSONB,
                       password_hash VARCHAR(255) NOT NULL,
                       active BOOLEAN NOT NULL DEFAULT TRUE,
                       created_at TIMESTAMP NOT NULL DEFAULT NOW(),

                       CONSTRAINT fk_users_system_client
                           FOREIGN KEY (system_client_id)
                               REFERENCES system_client(id)
                               ON DELETE CASCADE
);

CREATE INDEX idx_users_system_client
    ON users(system_client_id);

-- ================================
-- USER ROLES (Many-to-Many)
-- ================================
CREATE TABLE user_roles (
                            user_id BIGINT NOT NULL,
                            role_id BIGINT NOT NULL,

                            PRIMARY KEY (user_id, role_id),

                            CONSTRAINT fk_user_roles_user
                                FOREIGN KEY (user_id)
                                    REFERENCES users(id)
                                    ON DELETE CASCADE,

                            CONSTRAINT fk_user_roles_role
                                FOREIGN KEY (role_id)
                                    REFERENCES roles(id)
                                    ON DELETE CASCADE
);