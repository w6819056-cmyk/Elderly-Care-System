package com.smartelder.api.tenant.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.tenant-bootstrap.enabled", havingValue = "true")
public class TenantBootstrapRunner implements ApplicationRunner {

    private final TenantBootstrapService bootstrapService;
    private final TenantBootstrapService.BootstrapCommand command;

    public TenantBootstrapRunner(
            TenantBootstrapService bootstrapService,
            @Value("${app.tenant-bootstrap.org-no:}") String orgNo,
            @Value("${app.tenant-bootstrap.enterprise-name:}") String enterpriseName,
            @Value("${app.tenant-bootstrap.owner-account:}") String ownerAccount,
            @Value("${app.tenant-bootstrap.owner-display-name:}") String ownerDisplayName,
            @Value("${app.tenant-bootstrap.owner-password:}") String ownerPassword) {
        this.bootstrapService = bootstrapService;
        this.command = new TenantBootstrapService.BootstrapCommand(
                orgNo, enterpriseName, ownerAccount, ownerDisplayName, ownerPassword);
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapService.bootstrap(command);
    }
}
