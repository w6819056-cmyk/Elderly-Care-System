CREATE TABLE pf_sale_product (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 module_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 description VARCHAR(1000) NOT NULL,
 billing_mode VARCHAR(20) NOT NULL,
 duration_days INT NOT NULL,
 amount DECIMAL(12,2) NOT NULL,
 store_quota INT NOT NULL,
 sale_status VARCHAR(20) NOT NULL DEFAULT 'ON_SALE',
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_sale_product_module FOREIGN KEY(module_id) REFERENCES pf_module_definition(id),
 CONSTRAINT uk_sale_product_period UNIQUE(module_id,billing_mode,duration_days)
);
INSERT INTO pf_sale_product(module_id,name,description,billing_mode,duration_days,amount,store_quota,sale_status)
 SELECT p.module_id,m.name,m.description,p.billing_mode,p.duration_days,p.amount,p.store_quota,p.sale_status
 FROM pf_module_price p JOIN pf_module_definition m ON m.id=p.module_id;
CREATE TABLE pf_sale_card (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 product_id BIGINT NULL,
 plan_id BIGINT NULL,
 image_url VARCHAR(2000) NOT NULL,
 sort_order INT NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 CONSTRAINT fk_sale_card_product FOREIGN KEY(product_id) REFERENCES pf_sale_product(id),
 CONSTRAINT fk_sale_card_plan FOREIGN KEY(plan_id) REFERENCES pf_plan(id),
 CONSTRAINT uk_sale_card_product UNIQUE(product_id),
 CONSTRAINT uk_sale_card_plan UNIQUE(plan_id),
 CONSTRAINT ck_sale_card_target CHECK ((product_id IS NOT NULL AND plan_id IS NULL) OR (product_id IS NULL AND plan_id IS NOT NULL))
);
INSERT INTO pf_sale_card(product_id,image_url,sort_order,enabled)
 SELECT p.id,COALESCE(NULLIF(m.storefront_image,''),'/images/membership-cover.svg'),m.storefront_order,m.storefront_enabled
 FROM pf_sale_product p JOIN pf_module_definition m ON m.id=p.module_id
 WHERE m.storefront_title IS NOT NULL OR m.storefront_enabled=TRUE;
CREATE TABLE pf_sale_coupon (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(64) NOT NULL UNIQUE,
 name VARCHAR(100) NOT NULL,
 type VARCHAR(20) NOT NULL,
 coupon_value DECIMAL(12,2) NOT NULL,
 minimum_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
 expires_at TIMESTAMP(6) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE pf_enterprise_application (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 public_id VARCHAR(64) NOT NULL UNIQUE,
 org_no VARCHAR(100) NOT NULL UNIQUE,
 name VARCHAR(100) NOT NULL,
 contact_name VARCHAR(100) NOT NULL,
 contact_phone VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 reason VARCHAR(500) NULL,
 enterprise_public_id VARCHAR(64) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

ALTER TABLE pf_subscription_order ADD COLUMN coupon_code VARCHAR(64) NULL;
ALTER TABLE pf_sale_coupon ADD COLUMN reserved_order_id BIGINT NULL;
