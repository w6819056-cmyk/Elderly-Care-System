package com.smartelder.core.organization.internal;

import com.smartelder.core.organization.TenantOrganizationRepository;
import com.smartelder.core.organization.TenantOrganizationService.CreateStoreCommand;
import com.smartelder.core.organization.TenantOrganizationService.EnterpriseView;
import com.smartelder.core.organization.TenantOrganizationService.StoreView;
import com.smartelder.core.organization.TenantOrganizationService.UpdateEnterpriseCommand;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTenantOrganizationRepository implements TenantOrganizationRepository {

    private final JdbcClient jdbc;

    public JdbcTenantOrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EnterpriseView findEnterprise() {
        return jdbc.sql("SELECT public_id, org_no, name, legal_name, contact_name, contact_phone, version "
                        + "FROM core_enterprise WHERE id = 1")
                .query((rs, row) -> new EnterpriseView(rs.getString("public_id"), rs.getString("org_no"),
                        rs.getString("name"), rs.getString("legal_name"), rs.getString("contact_name"),
                        rs.getString("contact_phone"), rs.getLong("version")))
                .optional().orElse(null);
    }

    @Override
    public EnterpriseView updateEnterprise(
            UpdateEnterpriseCommand command, long expectedVersion, long actorId, String traceId) {
        int updated = jdbc.sql("UPDATE core_enterprise SET name = :name, legal_name = :legalName, "
                        + "contact_name = :contactName, contact_phone = :contactPhone, "
                        + "version = version + 1, updated_at = :now WHERE id = 1 AND version = :version")
                .param("name", command.name()).param("legalName", command.legalName())
                .param("contactName", command.contactName()).param("contactPhone", command.contactPhone())
                .param("now", Timestamp.from(Instant.now())).param("version", expectedVersion).update();
        if (updated == 0) return null;
        audit(actorId, "ENTERPRISE_PROFILE_UPDATED", "ENTERPRISE", findEnterprise().publicId(), traceId);
        return findEnterprise();
    }

    @Override
    public StoreView insertStore(CreateStoreCommand command, String publicId, long actorId, String traceId) {
        Instant now = Instant.now();
        jdbc.sql("INSERT INTO core_store (public_id, store_no, name, region_code, address, status, version, "
                        + "created_at, updated_at) VALUES (:publicId, :storeNo, :name, :regionCode, :address, "
                        + "'DRAFT', 0, :now, :now)")
                .param("publicId", publicId).param("storeNo", command.storeNo()).param("name", command.name())
                .param("regionCode", command.regionCode()).param("address", command.address())
                .param("now", Timestamp.from(now)).update();
        audit(actorId, "STORE_CREATED", "STORE", publicId, traceId);
        return findStore(publicId);
    }

    @Override
    public StoreView findStore(String publicId) {
        return jdbc.sql("SELECT public_id, store_no, name, status, version FROM core_store WHERE public_id = :publicId")
                .param("publicId", publicId).query(JdbcTenantOrganizationRepository::storeRow).optional().orElse(null);
    }

    @Override
    public StoreView changeStoreStatus(
            String publicId, String status, long expectedVersion, long actorId, String traceId) {
        int updated = jdbc.sql("UPDATE core_store SET status = :status, version = version + 1, updated_at = :now "
                        + "WHERE public_id = :publicId AND version = :version")
                .param("status", status).param("now", Timestamp.from(Instant.now())).param("publicId", publicId)
                .param("version", expectedVersion).update();
        if (updated == 0) return null;
        audit(actorId, "STORE_STATUS_CHANGED", "STORE", publicId, traceId);
        return findStore(publicId);
    }

    @Override
    public List<StoreView> listStores() {
        return jdbc.sql("SELECT public_id, store_no, name, status, version FROM core_store ORDER BY store_no")
                .query(JdbcTenantOrganizationRepository::storeRow).list();
    }

    @Override
    public List<StoreView> listStores(Set<Long> storeIds) {
        if (storeIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT public_id, store_no, name, status, version FROM core_store "
                        + "WHERE id IN (:storeIds) ORDER BY store_no")
                .param("storeIds", storeIds).query(JdbcTenantOrganizationRepository::storeRow).list();
    }

    @Override
    public Long findStoreId(String publicId) {
        return jdbc.sql("SELECT id FROM core_store WHERE public_id = :publicId")
                .param("publicId", publicId).query(Long.class).optional().orElse(null);
    }

    private void audit(long actorId, String action, String targetType, String publicId, String traceId) {
        jdbc.sql("INSERT INTO audit_tenant_log (actor_staff_id, action, target_type, target_public_id, "
                        + "trace_id, details_json, created_at) VALUES (:actor, :action, :targetType, :publicId, "
                        + ":traceId, '{}', :now)")
                .param("actor", actorId).param("action", action).param("targetType", targetType)
                .param("publicId", publicId).param("traceId", traceId)
                .param("now", Timestamp.from(Instant.now())).update();
    }

    private static StoreView storeRow(ResultSet rs, int row) throws SQLException {
        String status = rs.getString("status");
        return new StoreView(rs.getString("public_id"), rs.getString("store_no"), rs.getString("name"),
                status, rs.getLong("version"), "ACTIVE".equals(status));
    }
}
