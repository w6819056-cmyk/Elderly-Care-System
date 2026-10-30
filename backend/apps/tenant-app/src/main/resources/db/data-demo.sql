-- 演示数据。所有演示账号的初始密码均为 888888
-- 密码哈希为 BCrypt($2b$10)，Spring Security 可直接校验

INSERT INTO provider_org (id, name, contact_name, contact_phone, license_no, service_area, rating, audit_status, audit_note, settle_account, created_at) VALUES
(1, '康颐居家护理服务中心', '王护理', '13900000001', 'LIC-2024-0001', '朝阳区', 4.85, 'APPROVED', '资质齐全', '6222020000000001', '2026-01-05 09:00:00'),
(2, '夕阳红助餐服务部', '李助餐', '13900000002', 'LIC-2024-0002', '朝阳区', 4.60, 'APPROVED', '资质齐全', '6222020000000002', '2026-01-06 09:00:00'),
(3, '安康陪诊服务有限公司', '赵陪诊', '13900000003', 'LIC-2024-0003', '海淀区', 4.70, 'PENDING', NULL, NULL, '2026-02-01 09:00:00');

-- 账号：老人 1 名、家属 1 名、机构管理员 2 名、平台管理员 1 名、护理员 1 名
INSERT INTO sys_user (id, phone, password_hash, role, nickname, org_id, status, created_at) VALUES
(1, '13800000001', '$2b$10$kjqpGKVITbjwjFHiOfydvuXZCfizkVUTFaBtgCwyEw56L4e1pEkGa', 'ELDER', '张桂兰', NULL, 'ACTIVE', '2026-01-10 10:00:00'),
(2, '13800000002', '$2b$10$kjqpGKVITbjwjFHiOfydvuXZCfizkVUTFaBtgCwyEw56L4e1pEkGa', 'FAMILY', '张小明', NULL, 'ACTIVE', '2026-01-10 10:05:00'),
(3, '13900000001', '$2b$10$kjqpGKVITbjwjFHiOfydvuXZCfizkVUTFaBtgCwyEw56L4e1pEkGa', 'ORG_ADMIN', '康颐护理中心管理员', 1, 'ACTIVE', '2026-01-05 09:10:00'),
(4, '13900000002', '$2b$10$kjqpGKVITbjwjFHiOfydvuXZCfizkVUTFaBtgCwyEw56L4e1pEkGa', 'ORG_ADMIN', '夕阳红助餐管理员', 2, 'ACTIVE', '2026-01-06 09:10:00'),
(5, '13700000001', '$2b$10$kjqpGKVITbjwjFHiOfydvuXZCfizkVUTFaBtgCwyEw56L4e1pEkGa', 'PLATFORM_ADMIN', '平台运营管理员', NULL, 'ACTIVE', '2026-01-01 09:00:00'),
(6, '13900000011', '$2b$10$kjqpGKVITbjwjFHiOfydvuXZCfizkVUTFaBtgCwyEw56L4e1pEkGa', 'PROVIDER', '护理员刘姐', 1, 'ACTIVE', '2026-01-05 09:20:00'),
(7, '13800000003', '$2b$10$kjqpGKVITbjwjFHiOfydvuXZCfizkVUTFaBtgCwyEw56L4e1pEkGa', 'ELDER', '李长顺', NULL, 'ACTIVE', '2026-01-12 10:00:00');

INSERT INTO elder_profile (id, user_id, name, gender, birth_date, address, longitude, latitude, emergency_contact, emergency_phone, created_at) VALUES
(1, 1, '张桂兰', 'FEMALE', '1943-05-12', '北京市朝阳区幸福里 3 号楼 502', 116.481000, 39.921000, '张小明', '13800000002', '2026-01-10 10:00:00'),
(2, 7, '李长顺', 'MALE', '1940-08-20', '北京市朝阳区安康小区 8 号楼 1102', 116.470000, 39.915000, '李红', '13800000004', '2026-01-12 10:00:00');

INSERT INTO health_archive (id, elder_id, height_cm, weight_kg, blood_type, medical_history, allergy_history, medication_history, systolic_range, diastolic_range, updated_at) VALUES
(1, 1, 158.0, 56.0, 'A', '高血压 10 年，2 型糖尿病 5 年', '青霉素过敏', '苯磺酸氨氯地平片 5mg 每日一次', '90-140', '60-90', '2026-01-10 10:10:00'),
(2, 2, 170.0, 68.0, 'O', '冠心病，高血脂', '无', '阿托伐他汀钙片 20mg 每晚一次', '90-140', '60-90', '2026-01-12 10:10:00');

INSERT INTO family_binding (id, elder_id, family_id, relation, status, created_at) VALUES
(1, 1, 2, '儿子', 'ACCEPTED', '2026-01-10 10:20:00');

INSERT INTO vital_record (id, elder_id, type, value1, value2, unit, abnormal, source, measured_at, created_at) VALUES
(1, 1, 'BLOOD_PRESSURE', 148.0, 92.0, 'mmHg', 1, 'MANUAL', '2026-02-10 08:00:00', '2026-02-10 08:00:10'),
(2, 1, 'BLOOD_PRESSURE', 132.0, 84.0, 'mmHg', 0, 'MANUAL', '2026-02-11 08:00:00', '2026-02-11 08:00:10'),
(3, 1, 'HEART_RATE', 76.0, NULL, '次/分', 0, 'DEVICE', '2026-02-11 08:01:00', '2026-02-11 08:01:10'),
(4, 1, 'BLOOD_SUGAR', 7.8, NULL, 'mmol/L', 1, 'MANUAL', '2026-02-11 07:30:00', '2026-02-11 07:30:10');

INSERT INTO medication_plan (id, elder_id, drug_name, dosage, frequency, remind_time, status, created_by, created_at) VALUES
(1, 1, '苯磺酸氨氯地平片', '5mg', '每日一次', '08:00', 'ACTIVE', 2, '2026-01-11 09:00:00'),
(2, 1, '二甲双胍缓释片', '0.5g', '每日两次', '08:00,18:00', 'ACTIVE', 2, '2026-01-11 09:05:00');

INSERT INTO service_item (id, name, category, price, duration_min, service_area, description, provider_id, audit_status, status, created_at) VALUES
(1, '居家保洁服务', 'HOUSEKEEPING', 120.00, 120, '朝阳区', '两小时居家保洁，含厨卫清洁', 1, 'APPROVED', 'ON_SALE', '2026-01-08 09:00:00'),
(2, '上门护理（换药/助浴）', 'NURSING', 200.00, 90, '朝阳区', '持证护理员上门，含基础生命体征测量', 1, 'APPROVED', 'ON_SALE', '2026-01-08 09:10:00'),
(3, '助餐配送（午餐）', 'MEAL', 25.00, 30, '朝阳区', '营养配餐午餐配送上门', 2, 'APPROVED', 'ON_SALE', '2026-01-08 09:20:00'),
(4, '陪同就医', 'ESCORT', 300.00, 240, '海淀区', '陪诊、挂号、取药全程陪同', 3, 'PENDING', 'ON_SALE', '2026-02-01 09:30:00');

INSERT INTO service_order (id, order_no, elder_id, item_id, provider_id, creator_id, amount, status, appoint_time, address, remark, pay_time, cancel_reason, refund_amount, created_at, updated_at) VALUES
(1, 'SO202602100001', 1, 1, 1, 2, 120.00, 'PENDING_ACCEPT', '2026-02-12 09:00:00', '北京市朝阳区幸福里 3 号楼 502', '家属代下单，注意老人听力不好', '2026-02-10 10:00:00', NULL, NULL, '2026-02-10 09:55:00', '2026-02-10 10:00:00'),
(2, 'SO202602110002', 1, 3, 2, 1, 25.00, 'COMPLETED', NULL, '北京市朝阳区幸福里 3 号楼 502', '少盐', '2026-02-11 10:30:00', NULL, NULL, '2026-02-11 10:25:00', '2026-02-11 12:30:00');

INSERT INTO work_order (id, order_id, provider_id, nurse_id, status, accept_time, start_time, end_time, start_lng, start_lat, end_lng, end_lat, service_note, created_at) VALUES
(1, 1, 1, 6, 'DISPATCHED', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '2026-02-10 10:00:05'),
(2, 2, 2, NULL, 'COMPLETED', '2026-02-11 10:35:00', '2026-02-11 11:00:00', '2026-02-11 11:40:00', 116.481000, 39.921000, 116.481000, 39.921000, '已送达并协助用餐', '2026-02-11 10:30:05');

INSERT INTO settlement (id, provider_id, order_id, amount, status, created_at) VALUES
(1, 2, 2, 25.00, 'PENDING', '2026-02-11 11:40:05');

INSERT INTO service_review (id, order_id, elder_id, reviewer_id, score, content, created_at) VALUES
(1, 2, 1, 1, 5, '饭菜可口，送达准时', '2026-02-11 12:35:00');

INSERT INTO alert_record (id, elder_id, type, level, content, trigger_lng, trigger_lat, status, handle_by, handle_time, handle_note, created_at) VALUES
(1, 1, 'SOS', 'RED', '老人触发一键紧急呼叫', 116.481000, 39.921000, 'ACKED', 2, '2026-02-11 03:20:00', '已电话联系，老人表示无大碍', '2026-02-11 03:15:00'),
(2, 1, 'VITAL_ABNORMAL', 'YELLOW', '血压异常：148/92mmHg', NULL, NULL, 'PENDING', NULL, NULL, NULL, '2026-02-10 08:00:10'),
(3, 1, 'GEOFENCE', 'YELLOW', '老人离开设定安全区域', 116.500000, 39.930000, 'ARCHIVED', 5, '2026-02-05 16:00:00', '家属确认陪同外出，无需处理', '2026-02-05 15:30:00');

INSERT INTO alert_handle_log (id, alert_id, action, operator_id, note, created_at) VALUES
(1, 1, 'TRIGGER', 1, '触发紧急告警', '2026-02-11 03:15:00'),
(2, 1, 'ACKED', 2, '已电话联系，老人表示无大碍', '2026-02-11 03:20:00'),
(3, 3, 'TRIGGER', 7, '触发关注告警', '2026-02-05 15:30:00'),
(4, 3, 'ARCHIVED', 5, '家属确认陪同外出，无需处理', '2026-02-05 16:00:00');

INSERT INTO message_record (id, receiver_id, type, channel, title, content, biz_id, status, read_at, created_at) VALUES
(1, 2, 'ALERT', 'SMS', '【紧急告警】张桂兰', '老人触发一键紧急呼叫', 1, 'SENT', NULL, '2026-02-11 03:15:01'),
(2, 2, 'ALERT', 'IN_APP', '【紧急告警】张桂兰', '老人触发一键紧急呼叫', 1, 'READ', '2026-02-11 03:19:00', '2026-02-11 03:15:01'),
(3, 3, 'SERVICE', 'IN_APP', '新工单待接单', '订单号 SO202602100001', 1, 'SENT', NULL, '2026-02-10 10:00:06');
