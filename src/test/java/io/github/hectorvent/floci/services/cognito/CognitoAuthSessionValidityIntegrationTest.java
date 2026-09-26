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

    @ParameterizedTest
    @ValueSource(strings = {"2", "16", "-1", "0", "3.5", "\"10\"", "true", "null", "2147483648"})
    void invalidCreateAndUpdateDoNotChangeStoredClients(String value) throws Exception {
        cognitoAction("CreateUserPoolClient", createRequest(value))
                .then().statusCode(400).body("__type", containsString("InvalidParameterException"));
        assertEquals(0, cognitoJson("ListUserPoolClients", """
                {"UserPoolId":"%s"}
                """.formatted(poolId)).path("UserPoolClients").size());

        String clientId = createClient("10").path("ClientId").asText();
        cognitoAction("UpdateUserPoolClient", """
                {"UserPoolId":"%s","ClientId":"%s","ClientName":"changed","AuthSessionValidity":%s}
                """.formatted(poolId, clientId, value))
                .then().statusCode(400).body("__type", containsString("InvalidParameterException"));
        JsonNode stored = describeClient(clientId);
        assertEquals(10, stored.path("AuthSessionValidity").asInt());
        assertEquals("session-client", stored.path("ClientName").asText());
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
