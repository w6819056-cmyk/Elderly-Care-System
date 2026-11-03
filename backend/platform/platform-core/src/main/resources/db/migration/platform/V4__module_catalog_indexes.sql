CREATE INDEX idx_module_sale_menu_order
    ON pf_module_definition(sale_status, menu_order);

CREATE INDEX idx_module_dependency_id
    ON pf_module_dependency(dependency_module_id);

CREATE INDEX idx_module_price_module
    ON pf_module_price(module_id, billing_mode, duration_days);
