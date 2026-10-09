package io.github.hectorvent.floci.services.sqs;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Floci's receipt handle. The fields are readable, and the signature rejects a handle that was
 * edited or built by hand, as AWS does.
 */
record ReceiptHandle(String region, String accountId, String queueName, String messageId, String receipt,
                     String signature) {

    static final String DEFAULT_SECRET = "local-emulator-secret";

    private static final String[] KEYS = {"region", "account", "queue", "messageId", "receipt", "signature"};

    static ReceiptHandle issue(String storageKey, String messageId, String secret) {
        String[] queue = queueParts(storageKey);
        String receipt = UUID.randomUUID().toString();
        String signature = sign(body(queue[0], queue[1], queue[2], messageId, receipt), secret);
        return new ReceiptHandle(queue[0], queue[1], queue[2], messageId, receipt, signature);
    }

    String encode() {
        return body(region, accountId, queueName, messageId, receipt) + ":signature=" + signature;
    }

    /** Returns null when {@code value} is not in this format. */
    static ReceiptHandle parse(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != KEYS.length) {
            return null;
        }
        String[] values = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            String prefix = KEYS[i] + "=";
            if (!parts[i].startsWith(prefix)) {
                return null;
            }
            values[i] = parts[i].substring(prefix.length());
        }
        return new ReceiptHandle(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    boolean isSignedWith(String secret) {
        return signature.equals(sign(body(region, accountId, queueName, messageId, receipt), secret));
    }

    boolean belongsTo(String storageKey) {
        String[] queue = queueParts(storageKey);
        return region.equals(queue[0]) && accountId.equals(queue[1]) && queueName.equals(queue[2]);
    }

    /** Splits a storage key of the form {@code region::/accountId/queueName}. */
    private static String[] queueParts(String storageKey) {
        if (storageKey == null) {
            return new String[] {"", "", ""};
        }
        int separator = storageKey.indexOf("::");
        String region = separator < 0 ? "" : storageKey.substring(0, separator);
        String path = separator < 0 ? storageKey : storageKey.substring(separator + 2);
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        String accountId = slash < 0 ? "" : trimmed.substring(0, slash);
        return new String[] {region, accountId, trimmed.substring(slash + 1)};
    }

    private static String body(String region, String accountId, String queueName, String messageId,
                               String receipt) {
        return "region=" + region + ":account=" + accountId + ":queue=" + queueName
                + ":messageId=" + messageId + ":receipt=" + receipt;
    }

    private static String sign(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
