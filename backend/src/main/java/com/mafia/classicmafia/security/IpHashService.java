package com.mafia.classicmafia.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class IpHashService {
    private static final Logger log = LoggerFactory.getLogger(IpHashService.class);
    private final byte[] key;

    public IpHashService(@Value("${app.security.ip-hash-secret:}") String configuredSecret) {
        String secret = configuredSecret == null ? "" : configuredSecret.trim();
        if (secret.length() < 32) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.key = generated;
            log.warn("APP_SECURITY_IP_HASH_SECRET не задан или короче 32 символов. Используется временный ключ только до перезапуска.");
        } else {
            this.key = secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String hash(String value) {
        String normalized = value == null ? "unknown" : value.trim();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 недоступен.", exception);
        }
    }
}
