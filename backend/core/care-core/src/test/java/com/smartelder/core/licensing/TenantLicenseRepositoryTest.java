package com.smartelder.core.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartelder.core.licensing.internal.JdbcTenantLicenseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

class TenantLicenseRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    void acceptsNewSnapshotAndReplacesModuleProjectionAtomically() {
        Fixture fixture = fixture();

        fixture.repository().accept(snapshot(1, "a".repeat(64), List.of(grant("institution"))));

        assertThat(fixture.jdbc().sql("SELECT license_version FROM lic_license_snapshot WHERE id = 1")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(fixture.jdbc().sql("SELECT module_code FROM lic_module_entitlement")
                .query(String.class).list()).containsExactly("institution");
        assertThat(fixture.repository().currentStatus())
                .extracting(TenantLicenseRepository.LicenseStatus::version)
                .isEqualTo(1L);
    }

    @Test
    void acceptsSameVersionAndDigestIdempotentlyButRejectsDifferentContent() {
        Fixture fixture = fixture();
        var original = snapshot(1, "a".repeat(64), List.of(grant("institution")));
        fixture.repository().accept(original);

        fixture.repository().accept(original);

        assertThatThrownBy(() -> fixture.repository().accept(snapshot(
                1, "b".repeat(64), List.of(grant("community")))))
                .isInstanceOf(LicenseSnapshotConflictException.class);
        assertThat(fixture.jdbc().sql("SELECT module_code FROM lic_module_entitlement")
                .query(String.class).list()).containsExactly("institution");
    }

    @Test
    void rejectsLowerVersionWithoutChangingStoredSnapshot() {
        Fixture fixture = fixture();
        fixture.repository().accept(snapshot(2, "b".repeat(64), List.of(grant("community"))));

        assertThatThrownBy(() -> fixture.repository().accept(snapshot(
                1, "a".repeat(64), List.of(grant("institution")))))
                .isInstanceOf(LicenseSnapshotConflictException.class);
        assertThat(fixture.jdbc().sql("SELECT license_version FROM lic_license_snapshot WHERE id = 1")
                .query(Long.class).single()).isEqualTo(2L);
        assertThat(fixture.jdbc().sql("SELECT module_code FROM lic_module_entitlement")
                .query(String.class).list()).containsExactly("community");
    }

    @Test
    void recordsExplicitPlatformSuspensionWithoutAdvancingSuccessfulSyncTime() {
        Fixture fixture = fixture();
        fixture.repository().accept(snapshot(1, "a".repeat(64), List.of(grant("institution"))));

        fixture.repository().markPlatformStatus("SUSPENDED");

        assertThat(fixture.repository().currentStatus().instanceStatus()).isEqualTo("SUSPENDED");
        assertThat(fixture.jdbc().sql("SELECT last_successful_sync_at FROM lic_license_snapshot WHERE id = 1")
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(NOW);
    }

    private static Fixture fixture() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:lic_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/tenant").load().migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("INSERT INTO core_enterprise (id, public_id, org_no, name, status, created_at, updated_at) "
                        + "VALUES (1, 'ent_test', 'ORG-TEST', '测试企业', 'ACTIVE', :now, :now)")
                .param("now", java.sql.Timestamp.from(NOW)).update();
        return new Fixture(jdbc, new JdbcTenantLicenseRepository(
                jdbc, new JdbcTransactionManager(dataSource), Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static TenantLicenseRepository.VerifiedLicenseSnapshot snapshot(
            long version, String digest, List<TenantLicenseRepository.ModuleGrant> modules) {
        return new TenantLicenseRepository.VerifiedLicenseSnapshot(
                "ORG-TEST", "ins_test", "sha256:fingerprint", "lic_" + version, 2, version,
                "{signed-license}", "{canonical-payload-" + version + "}", digest,
                "Ed25519", "key-1", "signature", NOW, NOW, null, 7, 6, NOW, "ACTIVE", modules);
    }

    private static TenantLicenseRepository.ModuleGrant grant(String code) {
        return new TenantLicenseRepository.ModuleGrant(
                code, "FORMAL", "ACTIVE", NOW, null, true, 5);
    }

    private record Fixture(JdbcClient jdbc, JdbcTenantLicenseRepository repository) {
    }
}
