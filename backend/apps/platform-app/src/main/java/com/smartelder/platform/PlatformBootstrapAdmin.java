package com.smartelder.platform;

import com.smartelder.platform.core.auth.PlatformAdminRepository;
import com.smartelder.platform.core.common.PlatformClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PlatformBootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformBootstrapAdmin.class);
    private static final String DEFAULT_PASSWORD = "ChangeMe-Platform-2026";

    private final PlatformAdminRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformClock clock;
    private final String username;
    private final String password;

    public PlatformBootstrapAdmin(
            PlatformAdminRepository repository,
            PasswordEncoder passwordEncoder,
            PlatformClock clock,
            @Value("${platform.bootstrap.admin-username:admin}") String username,
            @Value("${platform.bootstrap.admin-password:ChangeMe-Platform-2026}") String password
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.hasAnyPlatformAdministrator()) {
            return;
        }
        repository.createBootstrapAdministrator(
                username,
                passwordEncoder.encode(password),
                "平台超级管理员",
                clock.now()
        );
        if (DEFAULT_PASSWORD.equals(password)) {
            log.warn("平台引导管理员使用了默认密码，生产环境必须通过环境变量修改");
        }
        log.info("已创建平台引导管理员账号: {}", username);
    }
}
