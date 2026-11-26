package io.floci.aws.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedPermissionsBatchLimitTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsABatchUpToTheConfiguredSize() {
        assertDoesNotThrow(() -> VerifiedPermissionsJsonHandler.requireBatchSize(batch(1), 30));
        assertDoesNotThrow(() -> VerifiedPermissionsJsonHandler.requireBatchSize(batch(30), 30));
        assertDoesNotThrow(() -> VerifiedPermissionsJsonHandler.requireBatchSize(batch(100), 100));
    }

    @Test
    void rejectsOneOverTheConfiguredSizeWithTheConfiguredNumberInTheMessage() {
        assertRejected(batch(31), 30, "requests must contain between 1 and 30 items.");
        assertRejected(batch(101), 100, "requests must contain between 1 and 100 items.");
    }

    @Test
    void rejectsAMissingEmptyOrNonArrayBatchWhateverTheSize() {
        assertRejected(null, 100, "requests must contain between 1 and 100 items.");
        assertRejected(batch(0), 100, "requests must contain between 1 and 100 items.");
        assertRejected(MAPPER.createObjectNode(), 100, "requests must contain between 1 and 100 items.");
    }

    @Test
    void refusesAMaximumBelowOne() {
        assertEquals(1, VerifiedPermissionsJsonHandler.validateBatchIsAuthorizedMaxRequests(1));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedPermissionsJsonHandler.validateBatchIsAuthorizedMaxRequests(0));
    }

    private static void assertRejected(JsonNode requests, int max, String message) {
        AwsException error = assertThrows(AwsException.class,
                () -> VerifiedPermissionsJsonHandler.requireBatchSize(requests, max));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(message, error.getMessage());
    }

    private static ArrayNode batch(int size) {
        ArrayNode requests = MAPPER.createArrayNode();
        for (int i = 0; i < size; i++) {
            requests.addObject().put("index", i);
        }
        return requests;
    }
}
