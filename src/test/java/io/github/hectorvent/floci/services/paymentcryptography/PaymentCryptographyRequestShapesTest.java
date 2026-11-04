package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsPartition;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The members that name a key or a region, checked against the vendored constraints in every published region. */
class PaymentCryptographyRequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY_ID = "0123456789abcdef0123456789abcdef";

    private static ObjectNode request(String member, String value) {
        return MAPPER.createObjectNode().put(member, value);
    }

    @Test
    void everyPublishedRegionIsTaken() {
        assertFalse(AwsPartitions.allRegionIds().isEmpty());
        for (String region : AwsPartitions.allRegionIds()) {
            AwsPartition partition = AwsPartitions.forRegion(region).orElseThrow();
            String prefix = "arn:" + partition.id() + ":payment-cryptography:" + region + ":000000000000:";
            String key = prefix + "key/" + KEY_ID;

            assertDoesNotThrow(() -> PaymentCryptographyRequestShapes.check("GetKey", request("KeyIdentifier", key)), key);
            assertDoesNotThrow(() -> PaymentCryptographyRequestShapes.check("GetKey",
                    request("KeyIdentifier", prefix + "alias/team/card-key")), region);
            assertDoesNotThrow(() -> PaymentCryptographyRequestShapes.check("ListTagsForResource",
                    request("ResourceArn", key)), region);
            ObjectNode create = MAPPER.createObjectNode();
            create.putObject("KeyAttributes")
                    .put("KeyAlgorithm", "AES_256")
                    .put("KeyClass", "SYMMETRIC_KEY")
                    .put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY")
                    .putObject("KeyModesOfUse").put("Encrypt", true);
            create.put("Exportable", true);
            create.putArray("ReplicationRegions").add(region);
            assertDoesNotThrow(() -> PaymentCryptographyRequestShapes.check("CreateKey", create), region);
        }
    }
}
