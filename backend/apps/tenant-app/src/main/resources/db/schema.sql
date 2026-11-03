-- 综合智慧养老平台 一期核心表结构
-- 兼容 MySQL 8.0 与 H2 2.x（MODE=MySQL），不使用引擎/字符集子句

DROP TABLE IF EXISTS message_record;
DROP TABLE IF EXISTS alert_handle_log;
DROP TABLE IF EXISTS alert_record;
DROP TABLE IF EXISTS service_review;
DROP TABLE IF EXISTS work_order_log;
DROP TABLE IF EXISTS work_order;
DROP TABLE IF EXISTS settlement;
DROP TABLE IF EXISTS service_order;
DROP TABLE IF EXISTS service_item;
DROP TABLE IF EXISTS medication_plan;
DROP TABLE IF EXISTS vital_record;
DROP TABLE IF EXISTS health_archive;
DROP TABLE IF EXISTS family_binding;
DROP TABLE IF EXISTS elder_profile;
DROP TABLE IF EXISTS provider_org;
DROP TABLE IF EXISTS sys_user;

-- 平台账号主体
CREATE TABLE sys_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone         VARCHAR(20)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    nickname      VARCHAR(50)  NOT NULL,
    org_id        BIGINT       NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_user_phone ON sys_user (phone);

-- 机构/服务商主体
CREATE TABLE provider_org (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    contact_name   VARCHAR(50)  NOT NULL,
    contact_phone  VARCHAR(20)  NOT NULL,
    license_no     VARCHAR(64)  NULL,
    service_area   VARCHAR(100) NOT NULL,
    rating         DECIMAL(3,2) NOT NULL DEFAULT 5.00,
    audit_status   VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    audit_note     VARCHAR(255) NULL,
    settle_account VARCHAR(64)  NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 老人档案
CREATE TABLE elder_profile (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    name              VARCHAR(50)  NOT NULL,
    gender            VARCHAR(10)  NOT NULL,
    birth_date        DATE         NULL,
    address           VARCHAR(200) NULL,
    longitude         DECIMAL(10,6) NULL,
    latitude          DECIMAL(10,6) NULL,
    emergency_contact VARCHAR(50)  NULL,
    emergency_phone   VARCHAR(20)  NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_elder_user ON elder_profile (user_id);

-- 健康档案
CREATE TABLE health_archive (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    elder_id         BIGINT       NOT NULL,
    height_cm        DECIMAL(5,1) NULL,
    weight_kg        DECIMAL(5,1) NULL,
    blood_type       VARCHAR(10)  NULL,
    medical_history  VARCHAR(500) NULL,
    allergy_history  VARCHAR(500) NULL,
    medication_history VARCHAR(500) NULL,
    systolic_range   VARCHAR(20)  NULL,
    diastolic_range  VARCHAR(20)  NULL,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_archive_elder ON health_archive (elder_id);

-- 家属绑定
CREATE TABLE family_binding (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    elder_id   BIGINT      NOT NULL,
    family_id  BIGINT      NOT NULL,
    relation   VARCHAR(20) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_binding_pair ON family_binding (elder_id, family_id);

-- 体征记录
CREATE TABLE vital_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    elder_id    BIGINT       NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    value1      DECIMAL(6,1) NOT NULL,
    value2      DECIMAL(6,1) NULL,
    unit        VARCHAR(20)  NOT NULL,
    abnormal    TINYINT      NOT NULL DEFAULT 0,
    source      VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    measured_at DATETIME     NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_vital_elder_time ON vital_record (elder_id, measured_at);

-- 用药计划
CREATE TABLE medication_plan (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    elder_id     BIGINT      NOT NULL,
    drug_name    VARCHAR(100) NOT NULL,
    dosage       VARCHAR(50) NOT NULL,
    frequency    VARCHAR(50) NOT NULL,
    remind_time  VARCHAR(50) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by   BIGINT      NOT NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_med_elder ON medication_plan (elder_id);

-- 服务项目
CREATE TABLE service_item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(100)  NOT NULL,
    category     VARCHAR(30)   NOT NULL,
    price        DECIMAL(10,2) NOT NULL,
    duration_min INT           NOT NULL DEFAULT 60,
    service_area VARCHAR(100)  NOT NULL,
    description  VARCHAR(500)  NULL,
    provider_id  BIGINT        NULL,
    audit_status VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    status       VARCHAR(20)   NOT NULL DEFAULT 'ON_SALE',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_item_category ON service_item (category, status);

-- 服务订单
CREATE TABLE service_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)   NOT NULL,
    elder_id      BIGINT        NOT NULL,
    item_id       BIGINT        NOT NULL,
    provider_id   BIGINT        NULL,
    creator_id    BIGINT        NOT NULL,
    amount        DECIMAL(10,2) NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    appoint_time  DATETIME      NULL,
    address       VARCHAR(200)  NOT NULL,
    remark        VARCHAR(500)  NULL,
    pay_time      DATETIME      NULL,
    cancel_reason VARCHAR(255)  NULL,
    refund_amount DECIMAL(10,2) NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_order_no ON service_order (order_no);
CREATE INDEX idx_order_elder ON service_order (elder_id, created_at);
CREATE INDEX idx_order_provider ON service_order (provider_id, status);

-- 工单（派单执行单元）
CREATE TABLE work_order (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT      NOT NULL,
    provider_id BIGINT      NOT NULL,
    nurse_id    BIGINT      NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'DISPATCHED',
    accept_time DATETIME    NULL,
    start_time  DATETIME    NULL,
    end_time    DATETIME    NULL,
    start_lng   DECIMAL(10,6) NULL,
    start_lat   DECIMAL(10,6) NULL,
    end_lng     DECIMAL(10,6) NULL,
    end_lat     DECIMAL(10,6) NULL,
    service_note VARCHAR(500) NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_wo_order ON work_order (order_id);

-- 工单操作流水
CREATE TABLE work_order_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_order_id BIGINT      NOT NULL,
    action     VARCHAR(30) NOT NULL,
    operator_id BIGINT      NOT NULL,
    note       VARCHAR(255) NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_wolog_wo ON work_order_log (work_order_id);

-- 告警记录
CREATE TABLE alert_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    elder_id     BIGINT       NOT NULL,
    type         VARCHAR(30)  NOT NULL,
    level        VARCHAR(10)  NOT NULL,
    content      VARCHAR(500) NOT NULL,
    trigger_lng  DECIMAL(10,6) NULL,
    trigger_lat  DECIMAL(10,6) NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    handle_by    BIGINT       NULL,
    handle_time  DATETIME     NULL,
    handle_note  VARCHAR(500) NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_alert_elder_time ON alert_record (elder_id, created_at);
CREATE INDEX idx_alert_status ON alert_record (status, level);

-- 告警处置流水
CREATE TABLE alert_handle_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id   BIGINT       NOT NULL,
    action     VARCHAR(30)  NOT NULL,
    operator_id BIGINT      NOT NULL,
    note       VARCHAR(500) NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_alertlog_alert ON alert_handle_log (alert_id);

-- 服务评价
CREATE TABLE service_review (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT       NOT NULL,
    elder_id   BIGINT       NOT NULL,
    reviewer_id BIGINT      NOT NULL,
    score      INT          NOT NULL,
    content    VARCHAR(500) NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_review_order ON service_review (order_id);

-- 结算流水
CREATE TABLE settlement (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_id BIGINT        NOT NULL,
    order_id    BIGINT        NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_settle_provider ON settlement (provider_id, status);

-- 消息记录
CREATE TABLE message_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    receiver_id BIGINT       NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    channel     VARCHAR(20)  NOT NULL,
    title       VARCHAR(100) NOT NULL,
    content     VARCHAR(500) NOT NULL,
    biz_id      BIGINT       NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'SENT',
    read_at     DATETIME     NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_msg_receiver ON message_record (receiver_id, created_at);
