CREATE TABLE site_public_content (
 target_key VARCHAR(80) NOT NULL PRIMARY KEY,
 draft_json LONGTEXT NOT NULL,
 submitted_json LONGTEXT,
 published_json LONGTEXT,
 review_status VARCHAR(24) NOT NULL,
 review_note VARCHAR(500),
 updated_at TIMESTAMP(6) NOT NULL
);
INSERT INTO iam_permission_definition(permission_code,module_code,name,description,active,version,created_at) VALUES
 ('core.site.view','core','查看首页内容','读取品牌或管理范围内门店的公开内容草稿',TRUE,0,CURRENT_TIMESTAMP),
 ('core.site.manage','core','维护首页内容','编辑门店首页并提交审核，总部可发布品牌首页',TRUE,0,CURRENT_TIMESTAMP),
 ('core.site.review','core','审核门店首页','总部审核管理范围内门店提交的公开内容',TRUE,0,CURRENT_TIMESTAMP);
INSERT INTO iam_permission_group_item(permission_group_id,permission_definition_id)
 SELECT g.id,d.id FROM iam_permission_group g CROSS JOIN iam_permission_definition d
 WHERE g.group_code='TENANT_OWNER_ALL' AND d.permission_code IN ('core.site.view','core.site.manage','core.site.review');
