CREATE TABLE core_visit_booking (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 public_id VARCHAR(36) NOT NULL UNIQUE,
 store_id BIGINT NOT NULL,
 visitor_name VARCHAR(80) NOT NULL,
 phone VARCHAR(32) NOT NULL,
 visit_type VARCHAR(24) NOT NULL,
 preferred_date DATE NOT NULL,
 remark VARCHAR(500),
 status VARCHAR(24) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_visit_booking_store FOREIGN KEY(store_id) REFERENCES core_store(id),
 INDEX idx_visit_store_date(store_id,preferred_date,status)
);
INSERT INTO iam_permission_definition(permission_code,module_code,name,description,active,version,created_at) VALUES
 ('core.visit.view','core','查看咨询预约','查看管理范围内的咨询与到店预约',TRUE,0,CURRENT_TIMESTAMP),
 ('core.visit.manage','core','处理咨询预约','确认、完成及取消管理范围内的咨询与到店预约',TRUE,0,CURRENT_TIMESTAMP);
INSERT INTO iam_permission_group_item(permission_group_id,permission_definition_id)
 SELECT g.id,d.id FROM iam_permission_group g CROSS JOIN iam_permission_definition d
 WHERE g.group_code='TENANT_OWNER_ALL' AND d.permission_code IN ('core.visit.view','core.visit.manage');
