package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.Digests;
import io.floci.aws.services.dynamodb.model.TableDefinition;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** The hash DynamoDB places and orders keys by: MD5 over "Outliers" and the bytes of the key values. */
final class DynamoDbKeyHash {

    private static final byte[] SALT = "Outliers".getBytes(StandardCharsets.US_ASCII);
    private static final int TERMINATED_BELOW_PAIRS = 20;

    private DynamoDbKeyHash() {
    }

    /**
     * Orders items tied on an index key. DynamoDB hashes the table sort key alone on an LSI, whose
     * items share the partition key, and the partition key followed by the sort key on a GSI.
     */
    static Comparator<JsonNode> indexTieOrder(TableDefinition table, DynamoDbAccessPath accessPath) {
        List<String> hashed = new ArrayList<>();
        if (accessPath.isGlobalSecondaryIndex()) {
            hashed.add(table.getPartitionKeyName());
        }
        if (table.getSortKeyName() != null) {
            hashed.add(table.getSortKeyName());
        }
        Map<JsonNode, byte[]> hashes = new IdentityHashMap<>();
        return Comparator.comparing(item -> hashes.computeIfAbsent(item, ignored -> of(item, hashed)),
                Arrays::compareUnsigned);
    }

    /** DynamoDB gives an on-demand table and its indexes 4 partitions, and a table provisioned at 1 unit 1. */
    static int partitionCount(TableDefinition table) {
        return "PAY_PER_REQUEST".equals(table.getBillingMode()) ? 4 : 1;
    }

    /**
     * Finds the partition of an item, each partition holding an equal range of the hash. A key value
     * is hashed once, as a scan meets the same partition key again and again.
     */
    static ToIntFunction<JsonNode> partitionLocator(List<String> keyNames, int partitions) {
        if (partitions == 1) {
            return item -> 0;
        }
        Map<List<JsonNode>, Integer> known = new HashMap<>();
        return item -> known.computeIfAbsent(Arrays.asList(keyNames.stream().map(item::get).toArray(JsonNode[]::new)),
                ignored -> (of(item, keyNames)[0] & 0xFF) * partitions / 256);
    }

    static byte[] of(JsonNode item, List<String> keyNames) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(SALT);
        for (String name : keyNames) {
            JsonNode value = item.get(name);
            if (value != null) {
                bytes.writeBytes(bytesOf(value));
            }
        }
        return Digests.md5(bytes.toByteArray());
    }

    private static byte[] bytesOf(JsonNode value) {
        if (value.has("S")) {
            return value.get("S").asText().getBytes(StandardCharsets.UTF_8);
        }
        if (value.has("B")) {
            return Base64.getDecoder().decode(value.get("B").asText());
        }
        if (value.has("N")) {
            return numberBytes(new BigDecimal(value.get("N").asText()));
        }
        return new byte[0];
    }

    // A number is hashed in the layout of an Oracle NUMBER: an exponent byte, then two decimal digits
    // per byte. A negative number inverts both and ends with 102 when it has fewer than 20 digit bytes.
    static byte[] numberBytes(BigDecimal number) {
        if (number.signum() == 0) {
            return new byte[] {(byte) 0x80};
        }
        boolean negative = number.signum() < 0;
        BigDecimal magnitude = number.abs().stripTrailingZeros();
        String digits = magnitude.unscaledValue().toString();
        int digitsBeforePoint = digits.length() - magnitude.scale();
        if (Math.floorMod(digitsBeforePoint, 2) != 0) {
            digits = "0" + digits;
            digitsBeforePoint++;
        }
        if (digits.length() % 2 != 0) {
            digits = digits + "0";
        }
        int pairs = digits.length() / 2;
        boolean terminated = negative && pairs < TERMINATED_BELOW_PAIRS;
        byte[] bytes = new byte[1 + pairs + (terminated ? 1 : 0)];
        int exponent = digitsBeforePoint / 2 - 64;
        bytes[0] = (byte) (negative ? ~exponent : exponent);
        for (int i = 0; i < pairs; i++) {
            int pair = Integer.parseInt(digits, 2 * i, 2 * i + 2, 10);
            bytes[1 + i] = (byte) (negative ? 101 - pair : pair + 1);
        }
        if (terminated) {
            bytes[bytes.length - 1] = 102;
        }
        return bytes;
    }
}
