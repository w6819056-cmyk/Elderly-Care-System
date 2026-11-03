ALTER TABLE pf_subscription_order MODIFY COLUMN created_by BIGINT NULL;
ALTER TABLE pf_subscription_order ADD COLUMN source_instance_id BIGINT NULL;
ALTER TABLE pf_subscription_order ADD CONSTRAINT fk_order_source_instance FOREIGN KEY(source_instance_id) REFERENCES pf_deployment_instance(id);
