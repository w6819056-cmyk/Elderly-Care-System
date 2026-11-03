package com.smartelder.api.tenant.licensing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.core.licensing.TenantLicenseRepository.ModuleGrant;
import com.smartelder.core.licensing.TenantLicenseRepository.VerifiedLicenseSnapshot;
import com.smartelder.platform.licensing.Ed25519LicenseVerifier;
import com.smartelder.platform.licensing.LicenseCanonicalizer;
import com.smartelder.platform.licensing.LicenseDocument;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class TenantLicenseVerifier {

    private static final Duration MAX_FUTURE_ISSUED_AT = Duration.ofMinutes(5);

    private final Map<String, PublicKey> trustedKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TenantLicenseVerifier(Map<String, PublicKey> trustedKeys, ObjectMapper objectMapper, Clock clock) {
        this.trustedKeys = Map.copyOf(trustedKeys);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public VerifiedLicenseSnapshot verify(
            String rawJson, ExpectedInstance expected, Instant platformTime) {
        try {
            LicenseDocument document = objectMapper.readValue(rawJson, LicenseDocument.class);
            if (document.schemaVersion() != 2 || document.version() < 1) {
                throw invalid("unsupported license schema or version");
            }
            if (!expected.orgNo().equals(document.orgNo())
                    || !expected.deploymentInstanceId().equals(document.deploymentInstanceId())
                    || !expected.instanceFingerprint().equals(document.instanceFingerprint())) {
                throw invalid("license instance binding does not match this installation");
            }
            if (document.signature() == null || document.signature().value() == null
                    || !"Ed25519".equalsIgnoreCase(document.signature().algorithm())) {
                throw invalid("license signature metadata is invalid");
            }
            PublicKey publicKey = trustedKeys.get(document.signature().keyId());
            if (publicKey == null || !new Ed25519LicenseVerifier(
                    document.signature().keyId(), publicKey).verify(document)) {
                throw invalid("license signature is invalid");
            }

            Instant now = platformTime == null ? clock.instant() : platformTime;
            if (document.notBefore().isAfter(now)
                    || document.issuedAt().isAfter(now.plus(MAX_FUTURE_ISSUED_AT))) {
                throw invalid("license is not yet valid");
            }
            String canonicalPayload = LicenseCanonicalizer.canonicalize(document);
            return new VerifiedLicenseSnapshot(
                    document.orgNo(),
                    document.deploymentInstanceId(),
                    document.instanceFingerprint(),
                    document.licenseId(),
                    document.schemaVersion(),
                    document.version(),
                    rawJson,
                    canonicalPayload,
                    LicenseCanonicalizer.sha256(document),
                    document.signature().algorithm(),
                    document.signature().keyId(),
                    document.signature().value(),
                    document.issuedAt(),
                    document.notBefore(),
                    document.expiresAt(),
                    document.offlineGraceDays(),
                    document.heartbeat().intervalHours(),
                    now,
                    "ACTIVE",
                    document.modules().stream().map(module -> new ModuleGrant(
                            module.code(), module.licenseType(), module.status(), module.startsAt(),
                            module.expiresAt(), module.perpetual(), module.storeQuota())).collect(Collectors.toList())
            );
        } catch (LicenseVerificationException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid("license document is malformed");
        }
    }

    private static LicenseVerificationException invalid(String message) {
        return new LicenseVerificationException(message);
    }

    public record ExpectedInstance(String orgNo, String deploymentInstanceId, String instanceFingerprint) {
    }
}
