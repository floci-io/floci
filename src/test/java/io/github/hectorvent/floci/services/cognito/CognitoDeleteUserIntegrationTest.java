package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoDeleteUserIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USERNAME = "deleteuser+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "DeleteUser1234!";

    private static String poolId;
    private static String clientId;
    private static String accessToken;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setUpPoolClientUserAndToken() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "DeleteUserPool"
                }
                """);
        poolId = poolResponse.path("UserPool").path("Id").asText();

        JsonNode clientResponse = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "delete-user-client",
                  "ExplicitAuthFlows": ["ALLOW_ADMIN_USER_PASSWORD_AUTH"]
                }
                """.formatted(poolId));
        clientId = clientResponse.path("UserPoolClient").path("ClientId").asText();

        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "UserAttributes": [
                    { "Name": "email", "Value": "%s" }
                  ]
                }
                """.formatted(poolId, USERNAME, USERNAME))
                .then()
                .statusCode(200);

        cognitoAction("AdminSetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "Password": "%s",
                  "Permanent": true
                }
                """.formatted(poolId, USERNAME, PASSWORD))
                .then()
                .statusCode(200);

        JsonNode authResponse = cognitoJson("AdminInitiateAuth", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "AuthFlow": "ADMIN_USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s"
                  }
                }
                """.formatted(poolId, clientId, USERNAME, PASSWORD));
        accessToken = authResponse.path("AuthenticationResult").path("AccessToken").asText();
    }

    @Test
    @Order(2)
    void deleteUserWithMissingOrNullAccessTokenReturnsInvalidParameterException() {
        cognitoAction("DeleteUser", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));

        cognitoAction("DeleteUser", """
                {
                  "AccessToken": null
                }
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(3)
    void deleteUserWithValidAccessTokenDeletesUser() {
        cognitoAction("DeleteUser", """
                {
                  "AccessToken": "%s"
                }
                """.formatted(accessToken))
                .then()
                .statusCode(200);

        // Verify AdminGetUser now returns UserNotFoundException
        cognitoAction("AdminGetUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s"
                }
                """.formatted(poolId, USERNAME))
                .then()
                .statusCode(400)
                .body("__type", equalTo("UserNotFoundException"));
    }

    @Test
    @Order(4)
    void deleteUserWithAlreadyDeletedUserAccessTokenReturnsNotAuthorizedException() {
        cognitoAction("DeleteUser", """
                {
                  "AccessToken": "%s"
                }
                """.formatted(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"));
    }
}
