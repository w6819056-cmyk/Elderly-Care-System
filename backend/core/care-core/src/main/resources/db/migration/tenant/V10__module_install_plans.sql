CREATE TABLE core_module_install_mutex (id BIGINT NOT NULL PRIMARY KEY);
INSERT INTO core_module_install_mutex(id) VALUES(1);
CREATE TABLE core_module_install_plan (
 public_id VARCHAR(36) NOT NULL PRIMARY KEY,
 module_code VARCHAR(40) NOT NULL,
 package_version VARCHAR(30) NOT NULL,
 package_sha256 VARCHAR(64) NOT NULL,
 manifest_json LONGTEXT NOT NULL,
 status VARCHAR(32) NOT NULL,
 created_by BIGINT NOT NULL,
 confirmed_by BIGINT,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL
);
INSERT INTO iam_permission_definition(permission_code,module_code,name,description,active,version,created_at) VALUES
 ('core.module.install','core','安装与更新模块','下载模块并确认或撤销安装计划，仅企业总部可用',TRUE,0,CURRENT_TIMESTAMP);
INSERT INTO iam_permission_group_item(permission_group_id,permission_definition_id)
 SELECT g.id,d.id FROM iam_permission_group g CROSS JOIN iam_permission_definition d WHERE g.group_code='TENANT_OWNER_ALL' AND d.permission_code='core.module.install';
