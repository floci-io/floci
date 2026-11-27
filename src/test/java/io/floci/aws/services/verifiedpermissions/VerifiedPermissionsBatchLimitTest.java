package io.floci.aws.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.verifiedpermissions.model.EntityIdentifier;
import io.floci.aws.services.verifiedpermissions.model.PolicyStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerifiedPermissionsBatchLimitTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String STORE_ID = "PSEXAMPLEabcdefg111111";
    private static final int LIMIT = VerifiedPermissionsJsonHandler.MAX_BATCH_REQUESTS;

    @Test
    void batchIsAuthorized_accepts30RequestsAndRejects31() {
        VerifiedPermissionsJsonHandler handler = handler();

        Response accepted = handler.handle("BatchIsAuthorized", batchIsAuthorizedRequest(LIMIT), REGION);
        assertEquals(200, accepted.getStatus());
        assertEquals(LIMIT, ((JsonNode) accepted.getEntity()).get("results").size());

        assertHandlerRejected(handler, "BatchIsAuthorized", batchIsAuthorizedRequest(LIMIT + 1),
                "requests must contain between 1 and 30 items.");
    }

    @Test
    void batchIsAuthorizedWithToken_accepts30RequestsAndRejects31() {
        VerifiedPermissionsJsonHandler handler = handler();

        Response accepted = handler.handle("BatchIsAuthorizedWithToken",
                batchIsAuthorizedWithTokenRequest(LIMIT), REGION);
        assertEquals(200, accepted.getStatus());
        assertEquals(LIMIT, ((JsonNode) accepted.getEntity()).get("results").size());

        assertHandlerRejected(handler, "BatchIsAuthorizedWithToken",
                batchIsAuthorizedWithTokenRequest(LIMIT + 1),
                "requests must contain between 1 and 30 items.");
    }

    @Test
    void rejectsAMissingEmptyOrNonArrayBatch() {
        assertRejected(null);
        assertRejected(MAPPER.createArrayNode());
        assertRejected(MAPPER.createObjectNode());
    }

    private static VerifiedPermissionsJsonHandler handler() {
        VerifiedPermissionsService service = mock(VerifiedPermissionsService.class);
        when(service.getPolicyStore(STORE_ID, REGION)).thenReturn(new PolicyStore(STORE_ID, null, "OFF", "DISABLED",
                null, null, null, null, null, null, null, null, null));

        CedarAuthorizationEvaluator evaluator = mock(CedarAuthorizationEvaluator.class);
        when(evaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(new CedarAuthorizationEvaluator.EvaluationResult("ALLOW", List.of(), List.of()));

        VerifiedPermissionsTokenService tokenService = mock(VerifiedPermissionsTokenService.class);
        when(tokenService.prepare(any(), anyString())).thenReturn(new VerifiedPermissionsTokenService.PreparedTokenRequest(
                MAPPER.createObjectNode(), new EntityIdentifier("Demo::User", "alice")));

        return new VerifiedPermissionsJsonHandler(service, MAPPER, evaluator, tokenService);
    }

    private static ObjectNode batchIsAuthorizedRequest(int size) {
        ObjectNode request = MAPPER.createObjectNode().put("policyStoreId", STORE_ID);
        ArrayNode requests = request.putArray("requests");
        for (int i = 0; i < size; i++) {
            ObjectNode item = requests.addObject();
            item.putObject("principal").put("entityType", "Demo::User").put("entityId", "alice");
            item.putObject("action").put("actionType", "Demo::Action").put("actionId", "read");
            item.putObject("resource").put("entityType", "Demo::Document").put("entityId", "doc" + i);
        }
        return request;
    }

    private static ObjectNode batchIsAuthorizedWithTokenRequest(int size) {
        ObjectNode request = MAPPER.createObjectNode()
                .put("policyStoreId", STORE_ID)
                .put("identityToken", "header.payload.signature");
        ArrayNode requests = request.putArray("requests");
        for (int i = 0; i < size; i++) {
            ObjectNode item = requests.addObject();
            item.putObject("action").put("actionType", "Demo::Action").put("actionId", "read");
            item.putObject("resource").put("entityType", "Demo::Document").put("entityId", "doc" + i);
        }
        return request;
    }

    private static void assertHandlerRejected(VerifiedPermissionsJsonHandler handler, String action,
                                              JsonNode request, String message) {
        AwsException error = assertThrows(AwsException.class, () -> handler.handle(action, request, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(message, error.getMessage());
    }

    private static void assertRejected(JsonNode requests) {
        AwsException error = assertThrows(AwsException.class,
                () -> VerifiedPermissionsJsonHandler.requireBatchSize(requests));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("requests must contain between 1 and 30 items.", error.getMessage());
    }
}
