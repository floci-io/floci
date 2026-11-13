package io.floci.aws.services.cognito;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CognitoPasswordUserExistenceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({
            "USER_PASSWORD_AUTH, ENABLED", "ADMIN_USER_PASSWORD_AUTH, ENABLED", "ADMIN_NO_SRP_AUTH, ENABLED",
            "USER_PASSWORD_AUTH, LEGACY", "ADMIN_USER_PASSWORD_AUTH, LEGACY", "ADMIN_NO_SRP_AUTH, LEGACY",
            "USER_PASSWORD_AUTH,", "ADMIN_USER_PASSWORD_AUTH,", "ADMIN_NO_SRP_AUTH,"
    })
    void passwordAuthHonorsUserExistenceErrorSetting(String flow, String prevention) throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"password-user-existence"}
                """).path("UserPool").path("Id").asText();
        try {
            Map<String, Object> clientRequest = new LinkedHashMap<>(Map.of(
                    "UserPoolId", poolId, "ClientName", "password-client",
                    "ExplicitAuthFlows", List.of("ALLOW_USER_PASSWORD_AUTH", "ALLOW_ADMIN_USER_PASSWORD_AUTH")));
            if (prevention != null) {
                clientRequest.put("PreventUserExistenceErrors", prevention);
            }
            String clientId = cognitoJson("CreateUserPoolClient", MAPPER.writeValueAsString(clientRequest))
                    .path("UserPoolClient").path("ClientId").asText();
            cognitoAction("AdminCreateUser", """
                    {"UserPoolId":"%s","Username":"alice","MessageAction":"SUPPRESS"}
                    """.formatted(poolId)).then().statusCode(200);
            cognitoAction("AdminSetUserPassword", """
                    {"UserPoolId":"%s","Username":"alice","Password":"Perm1234!","Permanent":true}
                    """.formatted(poolId)).then().statusCode(200);

            passwordAuth(poolId, clientId, flow, "alice", "wrong").then()
                    .statusCode(400)
                    .body("__type", equalTo("NotAuthorizedException"))
                    .body("message", equalTo("Incorrect username or password"))
                    .body("AuthenticationResult", nullValue());

            boolean hidden = "ENABLED".equals(prevention);
            passwordAuth(poolId, clientId, flow, "nobody", "wrong").then()
                    .statusCode(400)
                    .body("__type", equalTo(hidden ? "NotAuthorizedException" : "UserNotFoundException"))
                    .body("message", equalTo(hidden ? "Incorrect username or password" : "User not found"))
                    .body("AuthenticationResult", nullValue());

            passwordAuth(poolId, clientId, flow, "alice", "Perm1234!").then()
                    .statusCode(200)
                    .body("AuthenticationResult.AccessToken", notNullValue());
        } finally {
            cognitoAction("DeleteUserPool", """
                    {"UserPoolId":"%s"}
                    """.formatted(poolId)).then().statusCode(200);
        }
    }

    private static Response passwordAuth(String poolId, String clientId, String flow,
                                         String username, String password) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(Map.of(
                "ClientId", clientId, "AuthFlow", flow,
                "AuthParameters", Map.of("USERNAME", username, "PASSWORD", password)));
        String action = "InitiateAuth";
        if (!"USER_PASSWORD_AUTH".equals(flow)) {
            request.put("UserPoolId", poolId);
            action = "AdminInitiateAuth";
        }
        return cognitoAction(action, MAPPER.writeValueAsString(request));
    }
}
