CREATE TABLE core_module_install_execution (
 plan_id VARCHAR(36) NOT NULL PRIMARY KEY,
 phase VARCHAR(40) NOT NULL,
 backup_path VARCHAR(1000),
 backup_sha256 VARCHAR(64),
 error_message VARCHAR(1000),
 started_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_install_execution_plan FOREIGN KEY(plan_id) REFERENCES core_module_install_plan(public_id)
);
