package com.smartelder.core.organization;

import java.util.List;
import java.util.Set;

public interface TenantOrganizationRepository {

    TenantOrganizationService.EnterpriseView findEnterprise();

    TenantOrganizationService.EnterpriseView updateEnterprise(
            TenantOrganizationService.UpdateEnterpriseCommand command,
            long expectedVersion, long actorId, String traceId);

    TenantOrganizationService.StoreView insertStore(
            TenantOrganizationService.CreateStoreCommand command, String publicId, long actorId, String traceId);

    TenantOrganizationService.StoreView findStore(String publicId);

    TenantOrganizationService.StoreView changeStoreStatus(
            String publicId, String status, long expectedVersion, long actorId, String traceId);

    List<TenantOrganizationService.StoreView> listStores();

    List<TenantOrganizationService.StoreView> listStores(Set<Long> storeIds);

    Long findStoreId(String publicId);
}
