CREATE TABLE lic_license_snapshot (
    id SMALLINT NOT NULL PRIMARY KEY,
    org_no VARCHAR(64) NOT NULL,
    deployment_instance_id VARCHAR(100) NOT NULL,
    instance_fingerprint VARCHAR(160) NOT NULL,
    license_id VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    license_version BIGINT NOT NULL,
    raw_license_json TEXT NOT NULL,
    canonical_payload TEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    signature_algorithm VARCHAR(32) NOT NULL,
    key_id VARCHAR(100) NOT NULL,
    signature_value VARCHAR(256) NOT NULL,
    issued_at TIMESTAMP(6) NOT NULL,
    not_before TIMESTAMP(6) NOT NULL,
    license_expires_at TIMESTAMP(6),
    offline_grace_days INT NOT NULL,
    heartbeat_interval_hours INT NOT NULL,
    last_platform_time TIMESTAMP(6) NOT NULL,
    last_successful_sync_at TIMESTAMP(6) NOT NULL,
    platform_instance_status VARCHAR(24) NOT NULL,
    sync_status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_lic_snapshot_singleton CHECK (id = 1),
    CONSTRAINT chk_lic_snapshot_version CHECK (license_version > 0),
    CONSTRAINT chk_lic_snapshot_offline_grace CHECK (offline_grace_days >= 0),
    CONSTRAINT chk_lic_snapshot_heartbeat CHECK (heartbeat_interval_hours > 0)
);

CREATE TABLE lic_module_entitlement (
    module_code VARCHAR(64) NOT NULL PRIMARY KEY,
    license_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6),
    perpetual BOOLEAN NOT NULL,
    store_quota INT NOT NULL,
    license_version BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT chk_lic_module_quota CHECK (store_quota >= 0),
    CONSTRAINT chk_lic_module_expiry CHECK (perpetual = FALSE OR expires_at IS NULL),
    INDEX idx_lic_module_status (status)
);

CREATE TABLE core_enterprise_module (
    module_code VARCHAR(64) NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by_staff_id BIGINT,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_core_enterprise_module_actor FOREIGN KEY (updated_by_staff_id) REFERENCES iam_staff (id)
);

CREATE TABLE core_store_module_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    module_code VARCHAR(64) NOT NULL,
    store_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(100),
    assigned_by_staff_id BIGINT NOT NULL,
    assigned_at TIMESTAMP(6) NOT NULL,
    revoked_by_staff_id BIGINT,
    revoked_at TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_core_store_module_assignment_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT fk_core_store_module_assignment_store FOREIGN KEY (store_id) REFERENCES core_store (id),
    CONSTRAINT fk_core_store_module_assignment_actor FOREIGN KEY (assigned_by_staff_id) REFERENCES iam_staff (id),
    CONSTRAINT fk_core_store_module_revoke_actor FOREIGN KEY (revoked_by_staff_id) REFERENCES iam_staff (id),
    CONSTRAINT uk_core_store_module_idempotency UNIQUE (module_code, idempotency_key),
    INDEX idx_core_store_module_active (module_code, status),
    INDEX idx_core_store_module_store (store_id, status)
);

CREATE TABLE lic_sync_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    license_version BIGINT,
    result_code VARCHAR(48) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    attempted_at TIMESTAMP(6) NOT NULL,
    INDEX idx_lic_sync_attempted_at (attempted_at)
);

INSERT INTO iam_permission_definition
    (permission_code, module_code, name, description, active, version, created_at)
VALUES
    ('core.module.view', 'core', '查看模块授权', '查看企业模块授权、启用状态和门店名额', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('core.module.manage', 'core', '启停企业模块', '启用或停用本企业已授权模块', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('core.module.assignment.manage', 'core', '分配门店模块', '分配或撤销门店模块名额', TRUE, 0, CURRENT_TIMESTAMP(6));
