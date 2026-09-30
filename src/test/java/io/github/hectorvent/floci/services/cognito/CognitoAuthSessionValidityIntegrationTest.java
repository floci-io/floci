package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CognitoAuthSessionValidityIntegrationTest {

    private String poolId;

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createPool() throws Exception {
        poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"session-validity-%s"}
                """.formatted(UUID.randomUUID())).path("UserPool").path("Id").asText();
    }

    @AfterEach
    void deletePool() {
        cognitoAction("DeleteUserPool", """
                {"UserPoolId":"%s"}
                """.formatted(poolId)).then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 10, 15})
    void createAndDescribeReturnConfiguredMinutes(int minutes) throws Exception {
        JsonNode client = createClient(Integer.toString(minutes));
        assertEquals(minutes, client.path("AuthSessionValidity").asInt());
        assertEquals(minutes, describeClient(client.path("ClientId").asText())
                .path("AuthSessionValidity").asInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 10, 15})
    void updateAndDescribeReturnConfiguredMinutes(int minutes) throws Exception {
        String clientId = createClient("7").path("ClientId").asText();
        JsonNode updated = cognitoJson("UpdateUserPoolClient", """
                {"UserPoolId":"%s","ClientId":"%s","AuthSessionValidity":%d}
                """.formatted(poolId, clientId, minutes)).path("UserPoolClient");
        assertEquals(minutes, updated.path("AuthSessionValidity").asInt());
        assertEquals(minutes, describeClient(clientId).path("AuthSessionValidity").asInt());
    }

    @Test
    void omittedCreateUsesDefaultAndOmittedUpdatePreservesConfiguredValue() throws Exception {
        JsonNode defaultClient = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"default-client"}
                """.formatted(poolId)).path("UserPoolClient");
        assertEquals(3, defaultClient.path("AuthSessionValidity").asInt());
        assertEquals(3, describeClient(defaultClient.path("ClientId").asText())
                .path("AuthSessionValidity").asInt());

        String clientId = createClient("10").path("ClientId").asText();
        JsonNode updated = cognitoJson("UpdateUserPoolClient", """
                {"UserPoolId":"%s","ClientId":"%s","ClientName":"renamed"}
                """.formatted(poolId, clientId)).path("UserPoolClient");
        assertEquals(10, updated.path("AuthSessionValidity").asInt());
        assertEquals(10, describeClient(clientId).path("AuthSessionValidity").asInt());
    }

    @Test
    void explicitNullHasTheSameMeaningAsAnOmittedValue() throws Exception {
        JsonNode created = createClient("null");
        assertEquals(3, created.path("AuthSessionValidity").asInt());
        assertEquals(3, describeClient(created.path("ClientId").asText())
                .path("AuthSessionValidity").asInt());

        String clientId = createClient("10").path("ClientId").asText();
        JsonNode updated = cognitoJson("UpdateUserPoolClient", """
                {"UserPoolId":"%s","ClientId":"%s","ClientName":"renamed","AuthSessionValidity":null}
                """.formatted(poolId, clientId)).path("UserPoolClient");
        assertEquals(10, updated.path("AuthSessionValidity").asInt());
        JsonNode stored = describeClient(clientId);
        assertEquals(10, stored.path("AuthSessionValidity").asInt());
        assertEquals("renamed", stored.path("ClientName").asText());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 16, -1, 0, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void rangeViolationsUseConstraintErrorsBeforeResourceLookupAndDoNotMutateClients(int value) throws Exception {
        String constraint = value < 3
                ? "Member must have value greater than or equal to 3"
                : "Member must have value less than or equal to 15";
        String message = "1 validation error detected: Value '" + value
                + "' at 'authSessionValidity' failed to satisfy constraint: " + constraint;
        assertRejectedWithoutMutatingClients(Integer.toString(value), "InvalidParameterException", message);
        assertRejectedBeforeResourceLookup(Integer.toString(value), "InvalidParameterException", message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.5", "\"10\"", "true", "{}", "[]", "2147483648", "-2147483649"})
    void nonIntegerValuesUseSerializationErrorsBeforeResourceLookupAndDoNotMutateClients(String value) throws Exception {
        assertRejectedWithoutMutatingClients(value, "SerializationException", "Expected integer or null");
        assertRejectedBeforeResourceLookup(value, "SerializationException", "Expected integer or null");
    }

    private void assertRejectedWithoutMutatingClients(String value, String error, String message) throws Exception {
        cognitoAction("CreateUserPoolClient", createRequest(value))
                .then().statusCode(400).body("__type", containsString(error))
                .body("message", equalTo(message));
        assertEquals(0, cognitoJson("ListUserPoolClients", """
                {"UserPoolId":"%s"}
                """.formatted(poolId)).path("UserPoolClients").size());

        String clientId = createClient("10").path("ClientId").asText();
        cognitoAction("UpdateUserPoolClient", """
                {"UserPoolId":"%s","ClientId":"%s","ClientName":"changed","AuthSessionValidity":%s}
                """.formatted(poolId, clientId, value))
                .then().statusCode(400).body("__type", containsString(error))
                .body("message", equalTo(message));
        JsonNode stored = describeClient(clientId);
        assertEquals(10, stored.path("AuthSessionValidity").asInt());
        assertEquals("session-client", stored.path("ClientName").asText());
    }

    private void assertRejectedBeforeResourceLookup(String value, String error, String message) {
        String missingPool = "us-east-1_missing";
        cognitoAction("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"missing-pool-client","AuthSessionValidity":%s}
                """.formatted(missingPool, value)).then().statusCode(400)
                .body("__type", containsString(error)).body("message", equalTo(message));
        for (String requestedPool : new String[]{missingPool, poolId}) {
            cognitoAction("UpdateUserPoolClient", """
                    {"UserPoolId":"%s","ClientId":"missing-client","AuthSessionValidity":%s}
                    """.formatted(requestedPool, value)).then().statusCode(400)
                    .body("__type", containsString(error)).body("message", equalTo(message));
        }
    }

    private JsonNode createClient(String value) throws Exception {
        return cognitoJson("CreateUserPoolClient", createRequest(value)).path("UserPoolClient");
    }

    private String createRequest(String value) {
        return """
                {"UserPoolId":"%s","ClientName":"session-client","AuthSessionValidity":%s}
                """.formatted(poolId, value);
    }

    private JsonNode describeClient(String clientId) throws Exception {
        return cognitoJson("DescribeUserPoolClient", """
                {"UserPoolId":"%s","ClientId":"%s"}
                """.formatted(poolId, clientId)).path("UserPoolClient");
    }
}
