package io.github.hectorvent.floci.services.sqs;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Floci's receipt handle. The fields are readable. The checksum catches a handle edited by
 * mistake. It does not stop a forged handle. The receipts each message remembers do.
 */
record ReceiptHandle(String region, String accountId, String queueName, String messageId, String receipt,
                     String checksum) {

    private static final String[] KEYS = {"region", "account", "queue", "messageId", "receipt", "checksum"};

    /** {@code storageKey} has the form {@code region::/accountId/queueName}. */
    static ReceiptHandle issue(String storageKey, String messageId) {
        String region = "";
        String accountId = "";
        String queueName = "";
        if (storageKey != null) {
            int separator = storageKey.indexOf("::");
            region = separator < 0 ? "" : storageKey.substring(0, separator);
            String path = separator < 0 ? storageKey : storageKey.substring(separator + 2);
            String trimmed = path.startsWith("/") ? path.substring(1) : path;
            int slash = trimmed.indexOf('/');
            accountId = slash < 0 ? "" : trimmed.substring(0, slash);
            queueName = trimmed.substring(slash + 1);
        }
        String receipt = UUID.randomUUID().toString();
        String checksum = crc32(body(region, accountId, queueName, messageId, receipt));
        return new ReceiptHandle(region, accountId, queueName, messageId, receipt, checksum);
    }

    String encode() {
        return body(region, accountId, queueName, messageId, receipt) + ":checksum=" + checksum;
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

    boolean isIntact() {
        return checksum.equals(crc32(body(region, accountId, queueName, messageId, receipt)));
    }

    boolean belongsTo(String storageKey) {
        return (region + "::/" + accountId + "/" + queueName).equals(storageKey);
    }

    private static String body(String region, String accountId, String queueName, String messageId,
                               String receipt) {
        return "region=" + region + ":account=" + accountId + ":queue=" + queueName
                + ":messageId=" + messageId + ":receipt=" + receipt;
    }

    private static String crc32(String value) {
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().toHexDigits((int) crc.getValue());
    }
}
