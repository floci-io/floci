package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoAttributeVerificationIntegrationTest {

    private static final String USERNAME = "verify+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "Verify1234!";

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
                  "PoolName": "AttributeVerificationPool"
                }
                """);
        poolId = poolResponse.path("UserPool").path("Id").asText();

        JsonNode clientResponse = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "attribute-verification-client"
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

        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s"
                  }
                }
                """.formatted(clientId, USERNAME, PASSWORD));
        accessToken = auth.path("AuthenticationResult").path("AccessToken").asText();
    }

    @Test
    @Order(2)
    void issuesEmailVerificationCode() {
        cognitoAction("GetUserAttributeVerificationCode", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "email"
                }
                """.formatted(accessToken))
                .then()
                .statusCode(200)
                .body("CodeDeliveryDetails.AttributeName", equalTo("email"))
                .body("CodeDeliveryDetails.DeliveryMedium", equalTo("EMAIL"))
                .body("CodeDeliveryDetails.Destination", containsString("*"))
                .body("CodeDeliveryDetails.Destination", containsString("@"));
    }

    @Test
    @Order(3)
    void rejectsInvalidAccessToken() {
        cognitoAction("GetUserAttributeVerificationCode", """
                {
                  "AccessToken": "not-a-valid-token",
                  "AttributeName": "email"
                }
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"));
    }

    @Test
    @Order(4)
    void rejectsUnverifiableAttribute() {
        cognitoAction("GetUserAttributeVerificationCode", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "given_name"
                }
                """.formatted(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(5)
    void rejectsAttributeWithoutValue() {
        cognitoAction("GetUserAttributeVerificationCode", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "phone_number"
                }
                """.formatted(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }
}
