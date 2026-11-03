CREATE TABLE pf_instance_nonce (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id BIGINT NOT NULL,
    nonce VARCHAR(100) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_instance_nonce_instance FOREIGN KEY (instance_id)
        REFERENCES pf_deployment_instance(id),
    CONSTRAINT uk_instance_nonce UNIQUE (instance_id, nonce)
);

CREATE INDEX idx_instance_nonce_expires ON pf_instance_nonce(expires_at);