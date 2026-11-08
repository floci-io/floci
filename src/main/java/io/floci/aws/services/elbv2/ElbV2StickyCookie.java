package io.floci.aws.services.elbv2;

import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.services.elbv2.model.TargetGroup;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Duration-based ALB cookies, scoped to one load balancer and target group.
 * See https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-target-group-attributes.html#sticky-sessions.
 */
final class ElbV2StickyCookie {

    private static final long COOKIE_LIFETIME_SECONDS = 604800;
    private static final int NONCE_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final Clock clock;

    ElbV2StickyCookie() {
        this(Clock.systemUTC());
    }

    ElbV2StickyCookie(Clock clock) {
        this.clock = clock;
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        key = new SecretKeySpec(secret, "AES");
    }

    static long durationSeconds(TargetGroup group) {
        Map<String, String> attributes = group.getAttributes();
        // ALB-level cross-zone balancing is always on; only the target group can disable it.
        if (!Boolean.parseBoolean(attributes.get("stickiness.enabled"))
                || !"lb_cookie".equals(attributes.getOrDefault("stickiness.type", "lb_cookie"))
                || !("HTTP".equals(group.getProtocol()) || "HTTPS".equals(group.getProtocol()))
                || !("instance".equals(group.getTargetType()) || "ip".equals(group.getTargetType()))
                || "false".equals(attributes.get("load_balancing.cross_zone.enabled"))
                || "weighted_random".equals(attributes.get("load_balancing.algorithm.type"))) {
            return 0;
        }
        try {
            long duration = Long.parseLong(attributes.getOrDefault("stickiness.lb_cookie.duration_seconds", "86400"));
            return duration >= 1 && duration <= COOKIE_LIFETIME_SECONDS ? duration : 0;
        } catch (NumberFormatException ignored) {
            // Invalid stored attributes must not break data-plane requests.
            return 0;
        }
    }

    TargetDescription target(List<String> headers, String scope, TargetGroup group,
                             List<TargetDescription> candidates, long duration) {
        if (duration == 0) {
            return null;
        }
        String value = cookie(headers, "AWSALBCORS");
        if (value == null) {
            value = cookie(headers, "AWSALB");
        }
        if (value == null || value.length() > 2048) {
            return null;
        }
        try {
            byte[] encoded = Base64.getUrlDecoder().decode(value);
            if (encoded.length < NONCE_BYTES + 16) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encoded, 0, NONCE_BYTES));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            String[] binding = new String(cipher.doFinal(encoded, NONCE_BYTES, encoded.length - NONCE_BYTES),
                    StandardCharsets.UTF_8).split("\n", 3);
            if (binding.length != 3) {
                return null;
            }
            long age = clock.instant().getEpochSecond() - Long.parseLong(binding[0]);
            int port = Integer.parseInt(binding[1]);
            if (age < 0 || age >= duration || age >= COOKIE_LIFETIME_SECONDS) {
                return null;
            }
            return candidates.stream().filter(target -> binding[2].equals(target.getId())
                    && port == ElbV2HealthChecker.effectivePort(target, group)).findFirst().orElse(null);
        } catch (GeneralSecurityException | IllegalArgumentException ignored) {
            // A stale, malformed or modified client cookie selects a new target instead.
            return null;
        }
    }

    List<String> headers(String scope, TargetGroup group, TargetDescription target) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        String binding = clock.instant().getEpochSecond() + "\n"
                + ElbV2HealthChecker.effectivePort(target, group) + "\n" + target.getId();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(binding.getBytes(StandardCharsets.UTF_8));
            String value = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
            String attributes = "; Expires=" + DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    clock.instant().plusSeconds(COOKIE_LIFETIME_SECONDS).atZone(ZoneOffset.UTC)) + "; Path=/";
            return List.of("AWSALB=" + value + attributes,
                    "AWSALBCORS=" + value + attributes + "; SameSite=None; Secure");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate ALB stickiness cookie", e);
        }
    }

    private static String cookie(List<String> headers, String name) {
        for (String header : headers) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(name + "=")) {
                    return trimmed.substring(name.length() + 1);
                }
            }
        }
        return null;
    }
}
