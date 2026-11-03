package com.smartelder.platform.licensing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class LicenseCanonicalizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private LicenseCanonicalizer() {
    }

    public static String canonicalize(LicenseDocument document) {
        Map<String, Object> root = new TreeMap<>();
        root.put("schemaVersion", document.schemaVersion());
        if (document.schemaVersion() >= 2) {
            root.put("version", document.version());
        }
        root.put("licenseId", document.licenseId());
        root.put("orgNo", document.orgNo());
        root.put("deploymentInstanceId", document.deploymentInstanceId());
        root.put("instanceFingerprint", document.instanceFingerprint());
        root.put("issuedAt", document.issuedAt().toString());
        root.put("notBefore", document.notBefore().toString());
        root.put("expiresAt", document.expiresAt() == null ? null : document.expiresAt().toString());
        root.put("offlineGraceDays", document.offlineGraceDays());
        root.put("heartbeat", heartbeat(document.heartbeat()));
        root.put("modules", modules(document.modules()));
        root.put("signature", signature(document.signature()));
        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize canonical license payload", exception);
        }
    }

    public static String sha256(LicenseDocument document) {
        return sha256(canonicalize(document));
    }

    public static String sha256(String canonicalPayload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<String, Object> heartbeat(LicenseDocument.HeartbeatPolicy heartbeat) {
        Map<String, Object> value = new TreeMap<>();
        value.put("intervalHours", heartbeat.intervalHours());
        value.put("jitterMinutes", heartbeat.jitterMinutes());
        return value;
    }

    private static List<Map<String, Object>> modules(List<LicenseModule> modules) {
        List<Map<String, Object>> result = new ArrayList<>(modules.size());
        for (LicenseModule module : modules) {
            Map<String, Object> value = new TreeMap<>();
            value.put("code", module.code());
            value.put("licenseType", module.licenseType());
            value.put("status", module.status());
            value.put("startsAt", module.startsAt().toString());
            value.put("expiresAt", module.expiresAt() == null ? null : module.expiresAt().toString());
            value.put("perpetual", module.perpetual());
            value.put("storeQuota", module.storeQuota());
            result.add(value);
        }
        return result;
    }

    private static Map<String, Object> signature(LicenseSignature signature) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (signature == null) {
            return value;
        }
        value.put("algorithm", signature.algorithm());
        value.put("keyId", signature.keyId());
        return value;
    }
}
