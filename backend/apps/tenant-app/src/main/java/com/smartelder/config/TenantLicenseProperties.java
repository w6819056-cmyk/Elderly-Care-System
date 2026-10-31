package com.smartelder.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.license")
public class TenantLicenseProperties {

    private String platformBaseUrl = "";
    private String orgNo = "";
    private String instancePublicId = "";
    private String instanceFingerprint = "";
    private String instancePrivateKeyPem = "";
    private String activationCode = "";
    private String installationId = "";
    private String tenantDatabaseId = "";
    private long heartbeatIntervalMs = 21_600_000L;
    private long retryPollMs = 60_000L;
    private Map<String, String> trustedKeys = new LinkedHashMap<>();

    public String getPlatformBaseUrl() { return platformBaseUrl; }
    public void setPlatformBaseUrl(String value) { platformBaseUrl = value; }
    public String getOrgNo() { return orgNo; }
    public void setOrgNo(String value) { orgNo = value; }
    public String getInstancePublicId() { return instancePublicId; }
    public void setInstancePublicId(String value) { instancePublicId = value; }
    public String getInstanceFingerprint() { return instanceFingerprint; }
    public void setInstanceFingerprint(String value) { instanceFingerprint = value; }
    public String getInstancePrivateKeyPem() { return instancePrivateKeyPem; }
    public void setInstancePrivateKeyPem(String value) { instancePrivateKeyPem = value; }
    public String getActivationCode() { return activationCode; }
    public void setActivationCode(String value) { activationCode = value; }
    public String getInstallationId() { return installationId; }
    public void setInstallationId(String value) { installationId = value; }
    public String getTenantDatabaseId() { return tenantDatabaseId; }
    public void setTenantDatabaseId(String value) { tenantDatabaseId = value; }
    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public void setHeartbeatIntervalMs(long value) { heartbeatIntervalMs = value; }
    public long getRetryPollMs() { return retryPollMs; }
    public void setRetryPollMs(long value) { retryPollMs = value; }
    public Map<String, String> getTrustedKeys() { return trustedKeys; }
    public void setTrustedKeys(Map<String, String> value) { trustedKeys = new LinkedHashMap<>(value); }

    public boolean isSyncConfigured() {
        return hasText(platformBaseUrl) && hasText(instancePublicId) && hasText(instancePrivateKeyPem)
                && hasText(orgNo) && hasText(instanceFingerprint);
    }

    public boolean isActivationConfigured() {
        return hasText(platformBaseUrl) && hasText(activationCode) && hasText(installationId)
                && hasText(tenantDatabaseId) && hasText(instancePrivateKeyPem);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
