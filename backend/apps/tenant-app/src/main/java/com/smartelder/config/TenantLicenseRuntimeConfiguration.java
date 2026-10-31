package com.smartelder.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.api.tenant.licensing.InstanceRequestSigner;
import com.smartelder.api.tenant.licensing.PlatformLicenseClient;
import com.smartelder.platform.licensing.LicenseKeyPair;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.license", name = "enabled", havingValue = "true")
public class TenantLicenseRuntimeConfiguration {

    @Bean
    public LicenseKeyPair tenantInstanceKeyPair(TenantLicenseProperties properties) {
        if (!properties.isSyncConfigured() && !properties.isActivationConfigured()) {
            throw new IllegalStateException("license sync is enabled but instance configuration is incomplete");
        }
        return LicenseKeyPair.fromPrivateKeyPem(properties.getInstancePrivateKeyPem());
    }

    @Bean
    public RestClient.Builder platformRestClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    public InstanceRequestSigner instanceRequestSigner(LicenseKeyPair keyPair, Clock clock) {
        return new InstanceRequestSigner(keyPair.privateKey(), clock);
    }

    @Bean
    public PlatformLicenseClient platformLicenseClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            TenantLicenseProperties properties,
            InstanceRequestSigner signer,
            LicenseKeyPair keyPair) {
        return new PlatformLicenseClient(builder, objectMapper, properties, signer, keyPair);
    }
}
