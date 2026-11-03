INSERT INTO iam_permission_definition(permission_code,module_code,name,description,active,version,created_at)
VALUES ('core.customer.view','core','查看客户信息','按总部数据范围或当前门店查看注册客户及预约记录',TRUE,0,CURRENT_TIMESTAMP(6));

CREATE INDEX idx_booking_store_customer ON core_visit_booking(store_id,customer_id);
