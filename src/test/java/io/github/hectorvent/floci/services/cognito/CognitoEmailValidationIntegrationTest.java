package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class CognitoEmailValidationIntegrationTest {
    private static final ObjectMapper JSON = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    @BeforeAll
    static void setUpJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\r\n", "\u2003"})
    void bothUpdateApisDeleteBlankEmailsButCreationStillRejectsThem(String email) throws Exception {
        String poolId = cognitoJson("CreateUserPool", JSON.writeValueAsString(Map.of(
                "PoolName", "blank-email-" + UUID.randomUUID()))).path("UserPool").path("Id").asText();
        try {
            String clientId = cognitoJson("CreateUserPoolClient", JSON.writeValueAsString(Map.of(
                    "UserPoolId", poolId, "ClientName", "email-client",
                    "ExplicitAuthFlows", List.of("ALLOW_USER_PASSWORD_AUTH"))))
                    .path("UserPoolClient").path("ClientId").asText();
            for (String operation : List.of("AdminUpdateUserAttributes", "UpdateUserAttributes")) {
                cognitoAction("AdminCreateUser", JSON.writeValueAsString(Map.of("UserPoolId", poolId,
                        "Username", operation, "MessageAction", "SUPPRESS", "UserAttributes", List.of(
                                Map.of("Name", "email", "Value", "alice@example.com"),
                                Map.of("Name", "email_verified", "Value", "true")))))
                        .then().statusCode(200);
                cognitoAction("AdminSetUserPassword", JSON.writeValueAsString(Map.of("UserPoolId", poolId,
                        "Username", operation, "Password", "Passw0rd!x", "Permanent", true)))
                        .then().statusCode(200);
                String token = cognitoJson("InitiateAuth", JSON.writeValueAsString(Map.of("ClientId", clientId,
                        "AuthFlow", "USER_PASSWORD_AUTH", "AuthParameters",
                        Map.of("USERNAME", operation, "PASSWORD", "Passw0rd!x"))))
                        .path("AuthenticationResult").path("AccessToken").asText();
                List<Map<String, String>> attributes = List.of(Map.of("Name", "email", "Value", email));
                Map<String, Object> request = operation.equals("AdminUpdateUserAttributes")
                        ? Map.of("UserPoolId", poolId, "Username", operation, "UserAttributes", attributes)
                        : Map.of("AccessToken", token, "UserAttributes", attributes);
                cognitoAction(operation, JSON.writeValueAsString(request)).then().statusCode(200);
                cognitoAction("AdminGetUser", JSON.writeValueAsString(Map.of("UserPoolId", poolId,
                        "Username", operation))).then().statusCode(200)
                        .body("UserAttributes.findAll { it.Name == 'email' || it.Name == 'email_verified' }.size()",
                                equalTo(0));
            }
            List<Map<String, String>> attributes = List.of(Map.of("Name", "email", "Value", email));
            cognitoAction("AdminCreateUser", JSON.writeValueAsString(Map.of("UserPoolId", poolId,
                    "Username", "bad-admin", "MessageAction", "SUPPRESS", "UserAttributes", attributes)))
                    .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
            cognitoAction("SignUp", JSON.writeValueAsString(Map.of("ClientId", clientId,
                    "Username", "bad-signup", "Password", "Passw0rd!x", "UserAttributes", attributes)))
                    .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        } finally {
            cognitoAction("DeleteUserPool", JSON.writeValueAsString(Map.of("UserPoolId", poolId)))
                    .then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice smith@example.com", "alice\\@example.com", "\"alice@example.com\""})
    void allFourApisReturnCognitoValidationErrorsAndPreserveExistingAttributes(String email) throws Exception {
        String poolId = cognitoJson("CreateUserPool", JSON.writeValueAsString(Map.of(
                "PoolName", "email-validation-" + UUID.randomUUID()))).path("UserPool").path("Id").asText();
        try {
            String clientId = cognitoJson("CreateUserPoolClient", JSON.writeValueAsString(Map.of(
                    "UserPoolId", poolId, "ClientName", "email-client",
                    "ExplicitAuthFlows", List.of("ALLOW_USER_PASSWORD_AUTH"))))
                    .path("UserPoolClient").path("ClientId").asText();
            cognitoAction("AdminCreateUser", JSON.writeValueAsString(Map.of("UserPoolId", poolId,
                    "Username", "alice", "MessageAction", "SUPPRESS", "UserAttributes",
                    List.of(Map.of("Name", "email", "Value", "alice@example.com")))))
                    .then().statusCode(200);
            cognitoAction("AdminSetUserPassword", JSON.writeValueAsString(Map.of("UserPoolId", poolId,
                    "Username", "alice", "Password", "Passw0rd!x", "Permanent", true))).then().statusCode(200);
            String token = cognitoJson("InitiateAuth", JSON.writeValueAsString(Map.of("ClientId", clientId,
                    "AuthFlow", "USER_PASSWORD_AUTH", "AuthParameters", Map.of("USERNAME", "alice", "PASSWORD", "Passw0rd!x"))))
                    .path("AuthenticationResult").path("AccessToken").asText();
            List<Map<String, String>> attributes = List.of(Map.of("Name", "name", "Value", "must-not-be-saved"),
                    Map.of("Name", "email", "Value", email));
            Map<String, Map<String, Object>> requests = Map.of(
                    "AdminCreateUser", Map.of("UserPoolId", poolId, "Username", "bad-admin", "MessageAction", "SUPPRESS", "UserAttributes", attributes),
                    "SignUp", Map.of("ClientId", clientId, "Username", "bad-signup", "Password", "Passw0rd!x", "UserAttributes", attributes),
                    "AdminUpdateUserAttributes", Map.of("UserPoolId", poolId, "Username", "alice", "UserAttributes", attributes),
                    "UpdateUserAttributes", Map.of("AccessToken", token, "UserAttributes", attributes));
            for (Map.Entry<String, Map<String, Object>> request : requests.entrySet()) {
                cognitoAction(request.getKey(), JSON.writeValueAsString(request.getValue())).then().statusCode(400)
                        .body("__type", equalTo("InvalidParameterException"));
            }
            cognitoAction("AdminGetUser", JSON.writeValueAsString(Map.of("UserPoolId", poolId, "Username", "alice")))
                    .then().statusCode(200)
                    .body("UserAttributes.find { it.Name == 'email' }.Value", equalTo("alice@example.com"))
                    .body("UserAttributes.findAll { it.Name == 'name' }.size()", equalTo(0));
            cognitoAction("ListUsers", JSON.writeValueAsString(Map.of("UserPoolId", poolId)))
                    .then().statusCode(200).body("Users.size()", equalTo(1));
        } finally {
            cognitoAction("DeleteUserPool", JSON.writeValueAsString(Map.of("UserPoolId", poolId))).then().statusCode(200);
        }
    }
}
