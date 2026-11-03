package com.smartelder.api.tenant.installation;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.Clock;
@Service
public class InstallLicensePolicy extends com.smartelder.core.installation.InstallLicensePolicy {
 public InstallLicensePolicy(JdbcClient jdbc,Clock clock){super(jdbc,clock);}
}