ALTER TABLE pf_module_definition ADD COLUMN storefront_title VARCHAR(100) NULL;
ALTER TABLE pf_module_definition ADD COLUMN storefront_description VARCHAR(1000) NULL;
ALTER TABLE pf_module_definition ADD COLUMN storefront_image VARCHAR(2000) NULL;
ALTER TABLE pf_module_definition ADD COLUMN storefront_order INT NOT NULL DEFAULT 0;
ALTER TABLE pf_module_definition ADD COLUMN storefront_enabled BOOLEAN NOT NULL DEFAULT FALSE;
