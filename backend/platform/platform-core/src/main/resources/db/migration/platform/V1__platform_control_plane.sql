CREATE TABLE pf_admin_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    failed_login_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE pf_admin_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE pf_admin_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    module_code VARCHAR(40) NOT NULL
);

CREATE TABLE pf_admin_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES pf_admin_role(id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES pf_admin_permission(id)
);

CREATE TABLE pf_admin_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES pf_admin_user(id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES pf_admin_role(id)
);

CREATE TABLE pf_enterprise (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    org_no VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    contact_name VARCHAR(100) NOT NULL,
    contact_phone VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE pf_activation_code (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    enterprise_id BIGINT NOT NULL,
    code_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6) NULL,
    used_instance_id BIGINT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_activation_enterprise FOREIGN KEY (enterprise_id) REFERENCES pf_enterprise(id)
);

CREATE TABLE pf_deployment_instance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    enterprise_id BIGINT NOT NULL,
    installation_id VARCHAR(100) NOT NULL,
    tenant_database_id VARCHAR(100) NOT NULL,
    public_key_pem TEXT NOT NULL,
    fingerprint VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_heartbeat_at TIMESTAMP(6) NULL,
    license_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_instance_enterprise FOREIGN KEY (enterprise_id) REFERENCES pf_enterprise(id),
    CONSTRAINT uk_instance_identity UNIQUE (enterprise_id, installation_id, tenant_database_id)
);

CREATE TABLE pf_module_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    menu_order INT NOT NULL,
    sale_status VARCHAR(20) NOT NULL,
    trial_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE pf_module_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    module_id BIGINT NOT NULL,
    version VARCHAR(30) NOT NULL,
    min_core_version VARCHAR(30) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_module_version_module FOREIGN KEY (module_id) REFERENCES pf_module_definition(id),
    CONSTRAINT uk_module_version UNIQUE (module_id, version)
);

CREATE TABLE pf_module_dependency (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    module_id BIGINT NOT NULL,
    dependency_module_id BIGINT NOT NULL,
    dependency_type VARCHAR(20) NOT NULL,
    minimum_version VARCHAR(30) NOT NULL,
    CONSTRAINT fk_dependency_module FOREIGN KEY (module_id) REFERENCES pf_module_definition(id),
    CONSTRAINT fk_dependency_target FOREIGN KEY (dependency_module_id) REFERENCES pf_module_definition(id),
    CONSTRAINT uk_module_dependency UNIQUE (module_id, dependency_module_id)
);

CREATE TABLE pf_module_price (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    module_id BIGINT NOT NULL,
    billing_mode VARCHAR(20) NOT NULL,
    duration_days INT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    store_quota INT NOT NULL,
    trial_days INT NULL,
    sale_status VARCHAR(20) NOT NULL,
    CONSTRAINT fk_module_price_module FOREIGN KEY (module_id) REFERENCES pf_module_definition(id),
    CONSTRAINT uk_module_price UNIQUE (module_id, billing_mode, duration_days)
);

CREATE TABLE pf_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    minimum_amount DECIMAL(12,2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    sale_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE pf_plan_module (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    module_id BIGINT NOT NULL,
    billing_mode VARCHAR(20) NOT NULL,
    duration_days INT NOT NULL,
    store_quota INT NOT NULL,
    sort_order INT NOT NULL,
    CONSTRAINT fk_plan_module_plan FOREIGN KEY (plan_id) REFERENCES pf_plan(id),
    CONSTRAINT fk_plan_module_module FOREIGN KEY (module_id) REFERENCES pf_module_definition(id),
    CONSTRAINT uk_plan_module UNIQUE (plan_id, module_id)
);

CREATE TABLE pf_subscription_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    order_no VARCHAR(40) NOT NULL UNIQUE,
    enterprise_id BIGINT NOT NULL,
    plan_id BIGINT NULL,
    status VARCHAR(30) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    original_amount DECIMAL(12,2) NOT NULL,
    deduction_amount DECIMAL(12,2) NOT NULL,
    payable_amount DECIMAL(12,2) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    paid_at TIMESTAMP(6) NULL,
    fulfilled_at TIMESTAMP(6) NULL,
    fulfillment_attempts INT NOT NULL DEFAULT 0,
    last_fulfillment_error VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_order_enterprise FOREIGN KEY (enterprise_id) REFERENCES pf_enterprise(id),
    CONSTRAINT fk_order_plan FOREIGN KEY (plan_id) REFERENCES pf_plan(id)
);

CREATE TABLE pf_subscription_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    module_id BIGINT NOT NULL,
    module_code VARCHAR(40) NOT NULL,
    module_name VARCHAR(100) NOT NULL,
    billing_mode VARCHAR(20) NOT NULL,
    duration_days INT NOT NULL,
    store_quota INT NOT NULL,
    standard_amount DECIMAL(12,2) NOT NULL,
    deduction_amount DECIMAL(12,2) NOT NULL,
    actual_amount DECIMAL(12,2) NOT NULL,
    entitlement_result VARCHAR(30) NULL,
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES pf_subscription_order(id),
    CONSTRAINT fk_order_item_module FOREIGN KEY (module_id) REFERENCES pf_module_definition(id)
);

CREATE TABLE pf_payment_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    channel VARCHAR(30) NOT NULL,
    channel_transaction_no VARCHAR(100) NOT NULL UNIQUE,
    amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    paid_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES pf_subscription_order(id)
);

CREATE TABLE pf_refund (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES pf_subscription_order(id)
);

CREATE TABLE pf_entitlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    module_id BIGINT NOT NULL,
    license_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    perpetual BOOLEAN NOT NULL DEFAULT FALSE,
    store_quota INT NOT NULL,
    source_order_id BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_entitlement_enterprise FOREIGN KEY (enterprise_id) REFERENCES pf_enterprise(id),
    CONSTRAINT fk_entitlement_module FOREIGN KEY (module_id) REFERENCES pf_module_definition(id),
    CONSTRAINT fk_entitlement_order FOREIGN KEY (source_order_id) REFERENCES pf_subscription_order(id),
    CONSTRAINT uk_entitlement UNIQUE (enterprise_id, module_id)
);

CREATE TABLE pf_license (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    enterprise_id BIGINT NOT NULL,
    instance_id BIGINT NOT NULL,
    version BIGINT NOT NULL,
    schema_version INT NOT NULL,
    payload_json TEXT NOT NULL,
    signature VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    issued_at TIMESTAMP(6) NOT NULL,
    acknowledged_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_license_enterprise FOREIGN KEY (enterprise_id) REFERENCES pf_enterprise(id),
    CONSTRAINT fk_license_instance FOREIGN KEY (instance_id) REFERENCES pf_deployment_instance(id),
    CONSTRAINT uk_license_version UNIQUE (instance_id, version)
);

CREATE TABLE pf_heartbeat (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id BIGINT NOT NULL,
    license_version BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    platform_time TIMESTAMP(6) NOT NULL,
    client_time TIMESTAMP(6) NOT NULL,
    remote_address VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_heartbeat_instance FOREIGN KEY (instance_id) REFERENCES pf_deployment_instance(id)
);

CREATE TABLE pf_support_grant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    enterprise_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    scope_json TEXT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_support_grant_enterprise FOREIGN KEY (enterprise_id) REFERENCES pf_enterprise(id)
);

CREATE TABLE pf_outbox_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL
);

CREATE TABLE pf_idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(300) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_json TEXT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_idempotency UNIQUE (scope, idempotency_key)
);

CREATE TABLE pf_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_user_id BIGINT NULL,
    actor_public_id VARCHAR(64) NULL,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_public_id VARCHAR(64) NULL,
    detail_json TEXT NULL,
    trace_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_enterprise_status ON pf_enterprise(status);
CREATE INDEX idx_instance_enterprise ON pf_deployment_instance(enterprise_id);
CREATE INDEX idx_heartbeat_instance_time ON pf_heartbeat(instance_id, created_at);
CREATE INDEX idx_audit_created ON pf_audit_log(created_at);
