CREATE TABLE iam_staff_scope_config (
    staff_id BIGINT NOT NULL PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_iam_staff_scope_config_staff FOREIGN KEY (staff_id) REFERENCES iam_staff (id),
    CONSTRAINT chk_iam_staff_scope_type CHECK (scope_type IN ('ALL', 'STORE_GROUP', 'STORES', 'SELF'))
);

CREATE TABLE iam_staff_store_group_scope (
    staff_id BIGINT NOT NULL,
    store_group_id BIGINT NOT NULL,
    PRIMARY KEY (staff_id, store_group_id),
    CONSTRAINT fk_iam_staff_group_scope_staff FOREIGN KEY (staff_id) REFERENCES iam_staff (id),
    CONSTRAINT fk_iam_staff_group_scope_group FOREIGN KEY (store_group_id) REFERENCES iam_store_group (id)
);
