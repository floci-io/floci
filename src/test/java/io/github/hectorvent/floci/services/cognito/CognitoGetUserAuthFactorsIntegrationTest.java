package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GetUserAuthFactors over the JSON 1.1 wire protocol. The pools use email as a username
 * attribute, so each user's username is its generated sub, which is what AWS returns as
 * {@code Username} for such a pool.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoGetUserAuthFactorsIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SIX_DIGIT_CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final String PASSWORD = "Factors1234!";

    private static String passwordOnlyPoolId;
    private static String passwordOnlyClientId;
    private static String passwordlessPoolId;
    private static String passwordlessClientId;

    @Inject
    Clock clock;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setUpPoolsAndClients() throws Exception {
        passwordOnlyPoolId = createPool("AuthFactorsPasswordOnlyPool", "[\"PASSWORD\"]");
        passwordOnlyClientId = createClient(passwordOnlyPoolId);
        passwordlessPoolId = createPool("AuthFactorsPasswordlessPool", "[\"PASSWORD\", \"EMAIL_OTP\"]");
        passwordlessClientId = createClient(passwordlessPoolId);
    }

    @Test
    @Order(2)
    void passwordAndVerifiedEmailAreReportedEvenWhenThePoolAllowsOnlyPassword() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" },
                { "Name": "email_verified", "Value": "true" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);
        String username = cognitoJson("GetUser", accessTokenBody(accessToken)).path("Username").asText();

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("Username", equalTo(username))
                .body("ConfiguredUserAuthFactors", contains("PASSWORD", "EMAIL_OTP"))
                .body("$", not(hasKey("PreferredMfaSetting")))
                .body("$", not(hasKey("UserMFASettingList")));
        assertNotEquals(email, username, "an email-username pool keys the user by its sub");
    }

    @Test
    @Order(3)
    void unverifiedEmailIsNotAFactor() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("ConfiguredUserAuthFactors", contains("PASSWORD"));
    }

    @Test
    @Order(4)
    void verifiedPhoneNumberAddsSmsOtpAfterEmailOtp() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" },
                { "Name": "email_verified", "Value": "true" },
                { "Name": "phone_number", "Value": "+15555550123" },
                { "Name": "phone_number_verified", "Value": "true" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("ConfiguredUserAuthFactors", contains("PASSWORD", "EMAIL_OTP", "SMS_OTP"));
    }

    @Test
    @Order(5)
    void passwordlessUserSignedInWithEmailOtpHasNoPasswordFactor() throws Exception {
        String email = uniqueEmail();
        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "MessageAction": "SUPPRESS",
                  "UserAttributes": [
                    { "Name": "email", "Value": "%s" },
                    { "Name": "email_verified", "Value": "true" }
                  ]
                }
                """.formatted(passwordlessPoolId, email, email))
                .then()
                .statusCode(200);

        given().delete("/_aws/ses").then().statusCode(200);
        JsonNode challenge = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PREFERRED_CHALLENGE": "EMAIL_OTP" }
                }
                """.formatted(passwordlessClientId, email));
        assertEquals("EMAIL_OTP", challenge.path("ChallengeName").asText());
        JsonNode auth = cognitoJson("RespondToAuthChallenge", """
                {
                  "ClientId": "%s",
                  "ChallengeName": "EMAIL_OTP",
                  "Session": "%s",
                  "ChallengeResponses": { "USERNAME": "%s", "EMAIL_OTP_CODE": "%s" }
                }
                """.formatted(passwordlessClientId, challenge.path("Session").asText(),
                challenge.path("ChallengeParameters").path("USERNAME").asText(),
                fetchLatestSesCode(email)));
        String accessToken = auth.path("AuthenticationResult").path("AccessToken").asText();

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("ConfiguredUserAuthFactors", contains("EMAIL_OTP"));
    }

    @Test
    @Order(6)
    void emailMfaPreferenceIsReportedAsMfaSettings() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" },
                { "Name": "email_verified", "Value": "true" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);

        cognitoAction("SetUserMFAPreference", """
                { "AccessToken": "%s", "EmailMfaSettings": { "Enabled": true } }
                """.formatted(accessToken))
                .then()
                .statusCode(200);
        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("UserMFASettingList", contains("EMAIL_OTP"))
                .body("$", not(hasKey("PreferredMfaSetting")));

        cognitoAction("SetUserMFAPreference", """
                { "AccessToken": "%s", "EmailMfaSettings": { "Enabled": true, "PreferredMfa": true } }
                """.formatted(accessToken))
                .then()
                .statusCode(200);
        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("ConfiguredUserAuthFactors", contains("PASSWORD", "EMAIL_OTP"))
                .body("PreferredMfaSetting", equalTo("EMAIL_OTP"))
                .body("UserMFASettingList", contains("EMAIL_OTP"));
    }

    @Test
    @Order(7)
    void invalidAccessTokenIsRejected() {
        cognitoAction("GetUserAuthFactors", accessTokenBody("not-a-valid-token"))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Invalid Access Token"));
    }

    @Test
    @Order(8)
    void globallySignedOutTokenIsRejectedLikeGetUser() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);
        cognitoAction("GlobalSignOut", accessTokenBody(accessToken)).then().statusCode(200);

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Access Token has been revoked"));
        cognitoAction("GetUser", accessTokenBody(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Access Token has been revoked"));
    }

    @Test
    @Order(9)
    void deletedUserTokenIsRejectedLikeGetUser() throws Exception {
        String email = uniqueEmail();
        createUserWithPassword(passwordOnlyPoolId, email, """
                { "Name": "email", "Value": "%s" }
                """.formatted(email));
        String accessToken = signInWithPassword(passwordOnlyClientId, email);
        cognitoAction("AdminDeleteUser", """
                { "UserPoolId": "%s", "Username": "%s" }
                """.formatted(passwordOnlyPoolId, email))
                .then()
                .statusCode(200);

        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("UserNotFoundException"));
        cognitoAction("GetUser", accessTokenBody(accessToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("UserNotFoundException"));
    }

    @Test
    @Order(10)
    void verifiedSoftwareTokenIsReportedLastButAnUnverifiedOneIsNot() throws Exception {
        String poolId = createPool("AuthFactorsTotpPool", "[\"PASSWORD\"]");
        cognitoAction("SetUserPoolMfaConfig", """
                {
                  "UserPoolId": "%s",
                  "MfaConfiguration": "OPTIONAL",
                  "SoftwareTokenMfaConfiguration": { "Enabled": true }
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);
        String clientId = createClient(poolId);
        String email = uniqueEmail();
        createUserWithPassword(poolId, email, """
                { "Name": "email", "Value": "%s" },
                { "Name": "email_verified", "Value": "true" },
                { "Name": "phone_number", "Value": "+15555550124" },
                { "Name": "phone_number_verified", "Value": "true" }
                """.formatted(email));
        String accessToken = signInWithPassword(clientId, email);

        String secret = cognitoJson("AssociateSoftwareToken", accessTokenBody(accessToken))
                .path("SecretCode").asText();
        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("ConfiguredUserAuthFactors", contains("PASSWORD", "EMAIL_OTP", "SMS_OTP"));

        JsonNode verified = cognitoJson("VerifySoftwareToken", """
                { "AccessToken": "%s", "UserCode": "%s" }
                """.formatted(accessToken, CognitoTotp.code(secret, clock.instant())));
        assertEquals("SUCCESS", verified.path("Status").asText());
        cognitoAction("GetUserAuthFactors", accessTokenBody(accessToken))
                .then()
                .statusCode(200)
                .body("ConfiguredUserAuthFactors",
                        contains("PASSWORD", "EMAIL_OTP", "SMS_OTP", "SOFTWARE_TOKEN"));
    }

    private static String createPool(String name, String allowedFirstAuthFactors) throws Exception {
        JsonNode response = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "%s",
                  "UsernameAttributes": ["email"],
                  "UserPoolTier": "ESSENTIALS",
                  "Policies": { "SignInPolicy": { "AllowedFirstAuthFactors": %s } }
                }
                """.formatted(name, allowedFirstAuthFactors));
        return response.path("UserPool").path("Id").asText();
    }

    private static String createClient(String poolId) throws Exception {
        JsonNode response = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "auth-factors-client",
                  "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_USER_AUTH"]
                }
                """.formatted(poolId));
        return response.path("UserPoolClient").path("ClientId").asText();
    }

    private static void createUserWithPassword(String poolId, String email, String attributes) {
        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "MessageAction": "SUPPRESS",
                  "UserAttributes": [ %s ]
                }
                """.formatted(poolId, email, attributes))
                .then()
                .statusCode(200);
        cognitoAction("AdminSetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "Password": "%s",
                  "Permanent": true
                }
                """.formatted(poolId, email, PASSWORD))
                .then()
                .statusCode(200);
    }

    private static String signInWithPassword(String clientId, String email) throws Exception {
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "%s" }
                }
                """.formatted(clientId, email, PASSWORD));
        return auth.path("AuthenticationResult").path("AccessToken").asText();
    }

    private static String accessTokenBody(String accessToken) {
        return """
                { "AccessToken": "%s" }
                """.formatted(accessToken);
    }

    private static String uniqueEmail() {
        return "factors+" + UUID.randomUUID() + "@example.com";
    }

    private static String fetchLatestSesCode(String recipient) throws Exception {
        String response = given()
                .queryParam("email", recipient)
                .get("/_aws/ses")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonNode messages = JSON.readTree(response).path("messages");
        assertTrue(messages.isArray() && !messages.isEmpty(), "an EMAIL_OTP code should have been sent");
        Matcher matcher = SIX_DIGIT_CODE.matcher(messages.get(0).path("Body").path("text_part").asText());
        assertTrue(matcher.find(), "the EMAIL_OTP message should carry a six-digit code");
        return matcher.group(1);
    }
}
