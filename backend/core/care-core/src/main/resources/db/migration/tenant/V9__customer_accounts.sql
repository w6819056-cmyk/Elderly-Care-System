CREATE TABLE customer_account (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 public_id VARCHAR(36) NOT NULL UNIQUE,
 username VARCHAR(80) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 display_name VARCHAR(80) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE customer_session (
 token_hash VARCHAR(64) NOT NULL PRIMARY KEY,
 customer_id BIGINT NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_customer_session FOREIGN KEY(customer_id) REFERENCES customer_account(id)
);
ALTER TABLE core_visit_booking ADD COLUMN customer_id BIGINT;
ALTER TABLE core_visit_booking ADD CONSTRAINT fk_booking_customer FOREIGN KEY(customer_id) REFERENCES customer_account(id);
CREATE INDEX idx_booking_customer ON core_visit_booking(customer_id,created_at);
