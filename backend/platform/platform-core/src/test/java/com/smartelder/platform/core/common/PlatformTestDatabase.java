package com.smartelder.platform.core.common;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class PlatformTestDatabase implements AutoCloseable {

    private final DriverManagerDataSource dataSource;
    private final JdbcClient jdbcClient;
    private final DataSourceTransactionManager transactionManager;

    private PlatformTestDatabase(String name) {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:" + name
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        );
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform")
                .load()
                .migrate();

        jdbcClient = JdbcClient.create(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    public static PlatformTestDatabase create(String prefix) {
        return new PlatformTestDatabase(prefix + "_" + UUID.randomUUID().toString().replace("-", ""));
    }

    public JdbcClient jdbcClient() {
        return jdbcClient;
    }

    public DataSourceTransactionManager transactionManager() {
        return transactionManager;
    }

    @Override
    public void close() {
        jdbcClient.sql("SHUTDOWN").update();
    }
}
