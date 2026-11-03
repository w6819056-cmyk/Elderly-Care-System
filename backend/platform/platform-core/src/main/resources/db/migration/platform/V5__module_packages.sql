CREATE TABLE pf_module_package (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 module_code VARCHAR(40) NOT NULL,
 package_version VARCHAR(30) NOT NULL,
 sha256 VARCHAR(64) NOT NULL,
 manifest_json LONGTEXT NOT NULL,
 status VARCHAR(20) NOT NULL,
 uploaded_by BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_package_module FOREIGN KEY(module_code) REFERENCES pf_module_definition(code),
 CONSTRAINT uk_package_version UNIQUE(module_code,package_version)
);
