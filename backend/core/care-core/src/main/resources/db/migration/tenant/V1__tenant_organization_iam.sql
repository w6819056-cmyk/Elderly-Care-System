CREATE TABLE core_enterprise (
    id BIGINT NOT NULL PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    org_no VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    legal_name VARCHAR(200),
    contact_name VARCHAR(80),
    contact_phone VARCHAR(32),
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE core_store (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    store_no VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    region_code VARCHAR(32),
    address VARCHAR(500),
    contact_name VARCHAR(80),
    contact_phone VARCHAR(32),
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    INDEX idx_core_store_status (status)
);

CREATE TABLE iam_staff (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    phone VARCHAR(32) UNIQUE,
    staff_type VARCHAR(16) NOT NULL,
    home_store_id BIGINT,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_iam_staff_home_store FOREIGN KEY (home_store_id) REFERENCES core_store (id),
    INDEX idx_iam_staff_type_status (staff_type, status)
);

CREATE TABLE iam_permission_definition (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    permission_code VARCHAR(120) NOT NULL UNIQUE,
    module_code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE iam_permission_group (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    group_code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE iam_permission_group_item (
    permission_group_id BIGINT NOT NULL,
    permission_definition_id BIGINT NOT NULL,
    PRIMARY KEY (permission_group_id, permission_definition_id),
    CONSTRAINT fk_iam_group_item_group FOREIGN KEY (permission_group_id) REFERENCES iam_permission_group (id),
    CONSTRAINT fk_iam_group_item_permission FOREIGN KEY (permission_definition_id) REFERENCES iam_permission_definition (id)
);

CREATE TABLE iam_role (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    role_code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    role_domain VARCHAR(16) NOT NULL,
    protected BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE iam_role_permission_group (
    role_id BIGINT NOT NULL,
    permission_group_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_group_id),
    CONSTRAINT fk_iam_role_group_role FOREIGN KEY (role_id) REFERENCES iam_role (id),
    CONSTRAINT fk_iam_role_group_group FOREIGN KEY (permission_group_id) REFERENCES iam_permission_group (id)
);

CREATE TABLE iam_role_permission (
    role_id BIGINT NOT NULL,
    permission_definition_id BIGINT NOT NULL,
    effect VARCHAR(8) NOT NULL,
    PRIMARY KEY (role_id, permission_definition_id),
    CONSTRAINT fk_iam_role_permission_role FOREIGN KEY (role_id) REFERENCES iam_role (id),
    CONSTRAINT fk_iam_role_permission_definition FOREIGN KEY (permission_definition_id) REFERENCES iam_permission_definition (id)
);

CREATE TABLE iam_staff_role (
    staff_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (staff_id, role_id),
    CONSTRAINT fk_iam_staff_role_staff FOREIGN KEY (staff_id) REFERENCES iam_staff (id),
    CONSTRAINT fk_iam_staff_role_role FOREIGN KEY (role_id) REFERENCES iam_role (id)
);

CREATE TABLE iam_store_group (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    group_code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE iam_store_group_member (
    store_group_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    PRIMARY KEY (store_group_id, store_id),
    CONSTRAINT fk_iam_store_group_member_group FOREIGN KEY (store_group_id) REFERENCES iam_store_group (id),
    CONSTRAINT fk_iam_store_group_member_store FOREIGN KEY (store_id) REFERENCES core_store (id)
);

CREATE TABLE iam_staff_store_scope (
    staff_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    PRIMARY KEY (staff_id, store_id),
    CONSTRAINT fk_iam_staff_scope_staff FOREIGN KEY (staff_id) REFERENCES iam_staff (id),
    CONSTRAINT fk_iam_staff_scope_store FOREIGN KEY (store_id) REFERENCES core_store (id)
);

CREATE TABLE audit_tenant_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_staff_id BIGINT,
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_public_id VARCHAR(36),
    trace_id VARCHAR(64) NOT NULL,
    details_json TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    INDEX idx_audit_tenant_target (target_type, target_public_id),
    INDEX idx_audit_tenant_created (created_at)
);

INSERT INTO iam_permission_definition
    (permission_code, module_code, name, description, active, version, created_at)
VALUES
    ('core.enterprise.view', 'core', '查看企业资料', '查看当前企业基础资料', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('core.enterprise.manage', 'core', '管理企业资料', '修改当前企业基础资料', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('core.store.view', 'core', '查看门店', '查看企业门店资料', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('core.store.manage', 'core', '管理门店', '创建和维护企业门店', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('iam.staff.view', 'iam', '查看员工', '查看企业员工账号', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('iam.staff.manage', 'iam', '管理员工', '创建、停用员工并分配角色', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('iam.role.view', 'iam', '查看角色', '查看企业角色权限', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('iam.role.manage', 'iam', '管理角色', '创建和维护企业角色权限', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('iam.permission-group.view', 'iam', '查看权限组', '查看企业权限组', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('iam.permission-group.manage', 'iam', '管理权限组', '创建和维护企业权限组', TRUE, 0, CURRENT_TIMESTAMP(6));
