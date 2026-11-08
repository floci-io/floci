package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsPartition;
import io.floci.aws.core.common.AwsPartitions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The members that name a key, checked against the vendored constraints in every published region.
 *
 * <p>The data plane's identifier names a key by its ARN or by an alias, and the identifier of a
 * key on its own names none of them, so a request that carries one is refused.
 */
class PaymentCryptographyDataRequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY_ID = "0123456789abcdef0123456789abcdef";

    /** An encryption request, which the model asks for the identifier, the plaintext and the attributes of. */
    private static ObjectNode request(String identifier) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("KeyIdentifier", identifier);
        request.put("PlainText", "00112233445566778899aabbccddeeff");
        request.putObject("EncryptionAttributes").putObject("Symmetric").put("Mode", "CBC");
        return request;
    }

    @Test
    void everyPublishedRegionIsTaken() {
        assertFalse(AwsPartitions.allRegionIds().isEmpty());
        for (String region : AwsPartitions.allRegionIds()) {
            AwsPartition partition = AwsPartitions.forRegion(region).orElseThrow();
            String prefix = "arn:" + partition.id() + ":payment-cryptography:" + region + ":000000000000:";
            String key = prefix + "key/" + KEY_ID;

            assertDoesNotThrow(() -> PaymentCryptographyDataRequestShapes.check(
                    "EncryptData", request(key)), key);
            assertDoesNotThrow(() -> PaymentCryptographyDataRequestShapes.check(
                    "EncryptData", request(prefix + "alias/team/card-key")), region);
            assertDoesNotThrow(() -> PaymentCryptographyDataRequestShapes.check(
                    "EncryptData", request("alias/team/card-key")), region);
        }
    }

    @Test
    void anIdentifierThatNamesNoKeyIsRefused() {
        // The identifier of a key on its own is not the ARN of a key nor an alias, and the
        // model says so.
        AwsException refused = assertThrows(AwsException.class,
                () -> PaymentCryptographyDataRequestShapes.check(
                        "EncryptData", request(KEY_ID)));

        assertEquals("ValidationException", refused.getErrorCode());
    }
}
