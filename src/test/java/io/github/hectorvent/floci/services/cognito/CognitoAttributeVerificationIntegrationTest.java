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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoAttributeVerificationIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SIX_DIGIT_CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final String USERNAME = "verify+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "Verify1234!";

    private static String poolId;
    private static String clientId;
    private static String accessToken;
    private static String emailVerificationCode;

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
    void issuesEmailVerificationCode() throws Exception {
        clearInspectionEndpoint("/_aws/ses");

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
                .body("CodeDeliveryDetails.Destination", equalTo("v***@e***"));

        emailVerificationCode = fetchLatestSesVerificationCode(USERNAME);
    }

    @Test
    @Order(3)
    void rejectsWrongVerificationCodeWithoutUpdatingAttribute() throws Exception {
        cognitoAction("VerifyUserAttribute", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "email",
                  "Code": "%s"
                }
                """.formatted(accessToken, differentCode(emailVerificationCode)))
                .then()
                .statusCode(400)
                .body("__type", equalTo("CodeMismatchException"));

        assertNull(userAttribute(USERNAME, "email_verified"));
    }

    @Test
    @Order(4)
    void verifiesEmailAttribute() throws Exception {
        cognitoAction("VerifyUserAttribute", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "email",
                  "Code": "%s"
                }
                """.formatted(accessToken, emailVerificationCode))
                .then()
                .statusCode(200)
                .body(equalTo("{}"));

        assertEquals("true", userAttribute(USERNAME, "email_verified"));
    }

    @Test
    @Order(5)
    void rejectsConsumedVerificationCode() {
        cognitoAction("VerifyUserAttribute", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "email",
                  "Code": "%s"
                }
                """.formatted(accessToken, emailVerificationCode))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ExpiredCodeException"))
                .body("message", equalTo("Invalid code provided, please request a code again."));
    }

    @Test
    @Order(6)
    void rejectsInvalidAccessToken() {
        cognitoAction("GetUserAttributeVerificationCode", """
                {
                  "AccessToken": "not-a-valid-token",
                  "AttributeName": "email"
                }
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Invalid Access Token"));
    }

    @Test
    @Order(7)
    void rejectsUnverifiableAttribute() {
        cognitoAction("GetUserAttributeVerificationCode", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "given_name"
                }
                """.formatted(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "Invalid attribute name. Only phone_number and email can be verified."));
    }

    @Test
    @Order(8)
    void rejectsAttributeWithoutValue() {
        cognitoAction("GetUserAttributeVerificationCode", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "phone_number"
                }
                """.formatted(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo("User does not have a valid registered phone number"));
    }

    @Test
    @Order(9)
    void emailAndPhoneCodesAreIndependent() throws Exception {
        String username = "verify-both+" + UUID.randomUUID() + "@example.com";
        String phoneNumber = "+15551234567";
        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "UserAttributes": [
                    { "Name": "email", "Value": "%s" },
                    { "Name": "phone_number", "Value": "%s" }
                  ]
                }
                """.formatted(poolId, username, username, phoneNumber))
                .then()
                .statusCode(200);

        cognitoAction("AdminSetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "Password": "%s",
                  "Permanent": true
                }
                """.formatted(poolId, username, PASSWORD))
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
                """.formatted(clientId, username, PASSWORD));
        String token = auth.path("AuthenticationResult").path("AccessToken").asText();

        clearInspectionEndpoint("/_aws/ses");
        clearInspectionEndpoint("/_aws/sns");

        cognitoAction("GetUserAttributeVerificationCode", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "email"
                }
                """.formatted(token))
                .then()
                .statusCode(200)
                .body("CodeDeliveryDetails.DeliveryMedium", equalTo("EMAIL"));
        String emailCode = fetchLatestSesVerificationCode(username);

        cognitoAction("GetUserAttributeVerificationCode", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "phone_number"
                }
                """.formatted(token))
                .then()
                .statusCode(200)
                .body("CodeDeliveryDetails.AttributeName", equalTo("phone_number"))
                .body("CodeDeliveryDetails.DeliveryMedium", equalTo("SMS"))
                .body("CodeDeliveryDetails.Destination", equalTo("+*******4567"));
        String phoneCode = fetchLatestSnsVerificationCode(phoneNumber);

        cognitoAction("VerifyUserAttribute", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "phone_number",
                  "Code": "%s"
                }
                """.formatted(token, phoneCode))
                .then()
                .statusCode(200);

        cognitoAction("VerifyUserAttribute", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "email",
                  "Code": "%s"
                }
                """.formatted(token, emailCode))
                .then()
                .statusCode(200);

        assertEquals("true", userAttribute(username, "email_verified"));
        assertEquals("true", userAttribute(username, "phone_number_verified"));
    }

    @Test
    @Order(10)
    void rejectsUnverifiableAttributeDuringVerification() {
        cognitoAction("VerifyUserAttribute", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "given_name",
                  "Code": "123456"
                }
                """.formatted(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "Invalid attribute name. Only phone_number and email can be verified."));
    }

    @Test
    @Order(11)
    void rejectsVerificationForAttributeWithoutValue() {
        cognitoAction("VerifyUserAttribute", """
                {
                  "AccessToken": "%s",
                  "AttributeName": "phone_number",
                  "Code": "123456"
                }
                """.formatted(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "Unable to verify attribute: phone_number no value set to verify"));
    }

    private static void clearInspectionEndpoint(String path) {
        given()
                .delete(path)
                .then()
                .statusCode(200);
    }

    private static String fetchLatestSesVerificationCode(String recipient) throws Exception {
        String response = given()
                .queryParam("email", recipient)
                .get("/_aws/ses")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonNode messages = JSON.readTree(response).path("messages");
        assertTrue(messages.isArray() && !messages.isEmpty());
        return extractVerificationCode(messages.get(0).path("Body").path("text_part").asText());
    }

    private static String fetchLatestSnsVerificationCode(String phoneNumber) throws Exception {
        String response = given()
                .queryParam("phone", phoneNumber)
                .get("/_aws/sns")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonNode messages = JSON.readTree(response).path("messages");
        assertTrue(messages.isArray() && !messages.isEmpty());
        return extractVerificationCode(messages.get(0).path("Message").asText());
    }

    private static String extractVerificationCode(String message) {
        Matcher matcher = SIX_DIGIT_CODE.matcher(message);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private static String differentCode(String code) {
        return "000000".equals(code) ? "000001" : "000000";
    }

    private static String userAttribute(String username, String attributeName) throws Exception {
        JsonNode user = cognitoJson("AdminGetUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s"
                }
                """.formatted(poolId, username));
        for (JsonNode attribute : user.path("UserAttributes")) {
            if (attributeName.equals(attribute.path("Name").asText())) {
                return attribute.path("Value").asText();
            }
        }
        return null;
    }
}
