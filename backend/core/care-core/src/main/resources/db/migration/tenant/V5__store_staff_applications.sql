CREATE TABLE iam_staff_application (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 public_id VARCHAR(36) NOT NULL UNIQUE,
 store_id BIGINT NOT NULL,
 username VARCHAR(80) NOT NULL,
 password_hash VARCHAR(100),
 display_name VARCHAR(100) NOT NULL,
 phone VARCHAR(32) NOT NULL,
 email VARCHAR(254),
 status VARCHAR(24) NOT NULL,
 review_note VARCHAR(500),
 created_staff_public_id VARCHAR(36),
 created_at TIMESTAMP(6) NOT NULL,
 reviewed_at TIMESTAMP(6),
 CONSTRAINT fk_staff_application_store FOREIGN KEY(store_id) REFERENCES core_store(id),
 INDEX idx_staff_application_store_status(store_id,status)
);
