package io.floci.aws.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CognitoOptionalTotpMfaIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "Perm1234!";

    @Inject
    Clock clock;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void optionalPoolChallengesOnlyUsersWhoTurnedTotpOn() throws Exception {
        String poolId = createOptionalPool();
        String clientId = createClient(poolId);
        String username = "optional-" + UUID.randomUUID();
        createUser(poolId, username);
        String loginRequest = """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(clientId, username, PASSWORD);

        String accessToken = cognitoJson("InitiateAuth", loginRequest)
                .path("AuthenticationResult").path("AccessToken").asText();
        cognitoAction("SetUserMFAPreference", """
                {"AccessToken":"%s","SoftwareTokenMfaSettings":{"Enabled":true,"PreferredMfa":true}}
                """.formatted(accessToken))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo("User does not have delivery config set to turn on SOFTWARE_TOKEN_MFA"));

        String secret = cognitoJson("AssociateSoftwareToken", """
                {"AccessToken":"%s"}
                """.formatted(accessToken)).path("SecretCode").asText();
        assertEquals("SUCCESS", cognitoJson("VerifySoftwareToken", """
                {"AccessToken":"%s","UserCode":"%s"}
                """.formatted(accessToken, CognitoTotp.code(secret, clock.instant()))).path("Status").asText());
        assertFalse(cognitoJson("InitiateAuth", loginRequest)
                .path("AuthenticationResult").path("AccessToken").asText().isEmpty());
        cognitoAction("GetUser", accessTokenBody(accessToken))
                .then().statusCode(200)
                .body("$", not(hasKey("UserMFASettingList")))
                .body("$", not(hasKey("PreferredMfaSetting")));

        cognitoAction("SetUserMFAPreference", """
                {"AccessToken":"%s","SoftwareTokenMfaSettings":{"Enabled":true,"PreferredMfa":true}}
                """.formatted(accessToken))
                .then().statusCode(200);
        cognitoAction("GetUser", accessTokenBody(accessToken))
                .then().statusCode(200)
                .body("UserMFASettingList", contains("SOFTWARE_TOKEN_MFA"))
                .body("PreferredMfaSetting", equalTo("SOFTWARE_TOKEN_MFA"));
        cognitoAction("AdminGetUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, username))
                .then().statusCode(200)
                .body("UserMFASettingList", contains("SOFTWARE_TOKEN_MFA"))
                .body("PreferredMfaSetting", equalTo("SOFTWARE_TOKEN_MFA"));

        JsonNode challenged = cognitoJson("InitiateAuth", loginRequest);
        assertEquals("SOFTWARE_TOKEN_MFA", challenged.path("ChallengeName").asText());
        assertTrue(challenged.path("AuthenticationResult").isMissingNode());
        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", """
                {"ClientId":"%s","ChallengeName":"SOFTWARE_TOKEN_MFA","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"%s","SOFTWARE_TOKEN_MFA_CODE":"%s"}}
                """.formatted(clientId, challenged.path("Session").asText(), username,
                CognitoTotp.code(secret, clock.instant())));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());

        cognitoAction("AdminSetUserMFAPreference", """
                {"UserPoolId":"%s","Username":"%s","SoftwareTokenMfaSettings":{"Enabled":false}}
                """.formatted(poolId, username))
                .then().statusCode(200);
        assertFalse(cognitoJson("InitiateAuth", loginRequest)
                .path("AuthenticationResult").path("AccessToken").asText().isEmpty());
        cognitoAction("AdminGetUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, username))
                .then().statusCode(200)
                .body("$", not(hasKey("UserMFASettingList")))
                .body("$", not(hasKey("PreferredMfaSetting")));
    }

    @Test
    void choiceBasedSignInOffersOnlyPasswordToAUserWithMfa() throws Exception {
        String poolId = createOptionalPool();
        String clientId = createClient(poolId);
        String username = "choice-" + UUID.randomUUID();
        createUser(poolId, username);
        String accessToken = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(clientId, username, PASSWORD)).path("AuthenticationResult").path("AccessToken").asText();
        String userAuthRequest = """
                {"ClientId":"%s","AuthFlow":"USER_AUTH","AuthParameters":{"USERNAME":"%s"}}
                """.formatted(clientId, username);
        cognitoAction("InitiateAuth", userAuthRequest)
                .then().statusCode(200)
                .body("AvailableChallenges", contains("PASSWORD", "PASSWORD_SRP", "EMAIL_OTP"));

        // The pool offers no email MFA, so email MFA turned on alone keeps the email code choice.
        cognitoJson("SetUserMFAPreference", """
                {"AccessToken":"%s","EmailMfaSettings":{"Enabled":true}}
                """.formatted(accessToken));
        cognitoAction("InitiateAuth", userAuthRequest)
                .then().statusCode(200)
                .body("AvailableChallenges", contains("PASSWORD", "PASSWORD_SRP", "EMAIL_OTP"));

        String secret = cognitoJson("AssociateSoftwareToken", accessTokenBody(accessToken))
                .path("SecretCode").asText();
        cognitoJson("VerifySoftwareToken", """
                {"AccessToken":"%s","UserCode":"%s"}
                """.formatted(accessToken, CognitoTotp.code(secret, clock.instant())));
        cognitoJson("SetUserMFAPreference", """
                {"AccessToken":"%s","SoftwareTokenMfaSettings":{"Enabled":true}}
                """.formatted(accessToken));

        cognitoAction("InitiateAuth", userAuthRequest)
                .then().statusCode(200)
                .body("ChallengeName", equalTo("SELECT_CHALLENGE"))
                .body("AvailableChallenges", contains("PASSWORD", "PASSWORD_SRP"));
        JsonNode challenged = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PREFERRED_CHALLENGE":"PASSWORD","PASSWORD":"%s"}}
                """.formatted(clientId, username, PASSWORD));
        assertEquals("SOFTWARE_TOKEN_MFA", challenged.path("ChallengeName").asText());
        assertTrue(challenged.path("AuthenticationResult").isMissingNode());
    }

    @Test
    void softwareTokenChallengeNamesTheSubAndDeviceOfAnEmailUsernameUser() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"EmailTotpPool","UsernameAttributes":["email"]}
                """).path("UserPool").path("Id").asText();
        cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"OPTIONAL",
                 "SoftwareTokenMfaConfiguration":{"Enabled":true}}
                """.formatted(poolId));
        JsonNode client = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"email-totp-client","GenerateSecret":true,
                 "ExplicitAuthFlows":["ALLOW_ADMIN_USER_PASSWORD_AUTH","ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient");
        String clientId = client.path("ClientId").asText();
        String clientSecret = client.path("ClientSecret").asText();
        String email = "totp-" + UUID.randomUUID() + "@example.com";
        String sub = cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","MessageAction":"SUPPRESS",
                 "UserAttributes":[{"Name":"email","Value":"%s"},{"Name":"email_verified","Value":"true"}]}
                """.formatted(poolId, email, email)).path("User").path("Username").asText();
        assertNotEquals(email, sub);
        cognitoJson("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, email, PASSWORD));
        String loginRequest = """
                {"UserPoolId":"%s","ClientId":"%s","AuthFlow":"ADMIN_USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s","SECRET_HASH":"%s"}}
                """.formatted(poolId, clientId, email, PASSWORD, secretHash(clientId, clientSecret, email));

        String accessToken = cognitoJson("AdminInitiateAuth", loginRequest)
                .path("AuthenticationResult").path("AccessToken").asText();
        String secret = cognitoJson("AssociateSoftwareToken", accessTokenBody(accessToken))
                .path("SecretCode").asText();
        assertEquals("SUCCESS", cognitoJson("VerifySoftwareToken", """
                {"AccessToken":"%s","UserCode":"%s","FriendlyDeviceName":"repro-device"}
                """.formatted(accessToken, CognitoTotp.code(secret, clock.instant()))).path("Status").asText());
        cognitoAction("AdminSetUserMFAPreference", """
                {"UserPoolId":"%s","Username":"%s",
                 "SoftwareTokenMfaSettings":{"Enabled":true,"PreferredMfa":true}}
                """.formatted(poolId, email))
                .then().statusCode(200);

        JsonNode challenged = cognitoJson("AdminInitiateAuth", loginRequest);
        assertEquals("SOFTWARE_TOKEN_MFA", challenged.path("ChallengeName").asText());
        JsonNode parameters = challenged.path("ChallengeParameters");
        assertEquals(sub, parameters.path("USER_ID_FOR_SRP").asText());
        assertEquals("repro-device", parameters.path("FRIENDLY_DEVICE_NAME").asText());
        assertEquals(2, parameters.size());

        String userIdForSrp = parameters.path("USER_ID_FOR_SRP").asText();
        JsonNode signedIn = cognitoJson("AdminRespondToAuthChallenge", """
                {"UserPoolId":"%s","ClientId":"%s","ChallengeName":"SOFTWARE_TOKEN_MFA","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"%s","SECRET_HASH":"%s","SOFTWARE_TOKEN_MFA_CODE":"%s"}}
                """.formatted(poolId, clientId, challenged.path("Session").asText(), userIdForSrp,
                secretHash(clientId, clientSecret, userIdForSrp), CognitoTotp.code(secret, clock.instant())));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
    }

    @Test
    void softwareTokenChallengeOmitsTheDeviceNameWhenNoneWasGiven() throws Exception {
        String poolId = createOptionalPool();
        String clientId = createClient(poolId);
        String username = "unnamed-" + UUID.randomUUID();
        createUser(poolId, username);
        String loginRequest = """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(clientId, username, PASSWORD);
        String accessToken = cognitoJson("InitiateAuth", loginRequest)
                .path("AuthenticationResult").path("AccessToken").asText();
        String secret = cognitoJson("AssociateSoftwareToken", accessTokenBody(accessToken))
                .path("SecretCode").asText();
        cognitoJson("VerifySoftwareToken", """
                {"AccessToken":"%s","UserCode":"%s"}
                """.formatted(accessToken, CognitoTotp.code(secret, clock.instant())));
        cognitoAction("SetUserMFAPreference", """
                {"AccessToken":"%s","SoftwareTokenMfaSettings":{"Enabled":true,"PreferredMfa":true}}
                """.formatted(accessToken))
                .then().statusCode(200);

        JsonNode challenged = cognitoJson("InitiateAuth", loginRequest);
        assertEquals("SOFTWARE_TOKEN_MFA", challenged.path("ChallengeName").asText());
        JsonNode parameters = challenged.path("ChallengeParameters");
        assertEquals(username, parameters.path("USER_ID_FOR_SRP").asText());
        assertEquals(1, parameters.size());

        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", """
                {"ClientId":"%s","ChallengeName":"SOFTWARE_TOKEN_MFA","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"%s","SOFTWARE_TOKEN_MFA_CODE":"%s"}}
                """.formatted(clientId, challenged.path("Session").asText(),
                parameters.path("USER_ID_FOR_SRP").asText(), CognitoTotp.code(secret, clock.instant())));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
    }

    @Test
    void verifySoftwareTokenRejectsAnOverLongFriendlyDeviceName() throws Exception {
        String poolId = createOptionalPool();
        String clientId = createClient(poolId);
        String username = "long-name-" + UUID.randomUUID();
        createUser(poolId, username);
        String accessToken = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(clientId, username, PASSWORD)).path("AuthenticationResult").path("AccessToken").asText();
        String secret = cognitoJson("AssociateSoftwareToken", accessTokenBody(accessToken))
                .path("SecretCode").asText();
        String tooLong = "d".repeat(CognitoAuthFlowHandler.MAX_FRIENDLY_DEVICE_NAME_LENGTH + 1);

        cognitoAction("VerifySoftwareToken", """
                {"AccessToken":"%s","UserCode":"%s","FriendlyDeviceName":"%s"}
                """.formatted(accessToken, CognitoTotp.code(secret, clock.instant()), tooLong))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo("1 validation error detected: Value '" + tooLong
                        + "' at 'friendlyDeviceName' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 131072"));
        cognitoAction("SetUserMFAPreference", """
                {"AccessToken":"%s","SoftwareTokenMfaSettings":{"Enabled":true}}
                """.formatted(accessToken))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void friendlyDeviceNameOfSupplementaryCharactersIsLimitedByCharacterCount() throws Exception {
        String poolId = createOptionalPool();
        String clientId = createClient(poolId);
        String username = "emoji-name-" + UUID.randomUUID();
        createUser(poolId, username);
        String loginRequest = """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(clientId, username, PASSWORD);
        String accessToken = cognitoJson("InitiateAuth", loginRequest)
                .path("AuthenticationResult").path("AccessToken").asText();
        int limit = CognitoAuthFlowHandler.MAX_FRIENDLY_DEVICE_NAME_LENGTH;
        // JSON escapes keep the request ASCII whatever charset the client encodes it with.
        String escapedEmoji = "\\uD83D\\uDE00";

        String rejectedSecret = cognitoJson("AssociateSoftwareToken", accessTokenBody(accessToken))
                .path("SecretCode").asText();
        Response rejected = cognitoAction("VerifySoftwareToken", """
                {"AccessToken":"%s","UserCode":"%s","FriendlyDeviceName":"%s"}
                """.formatted(accessToken, CognitoTotp.code(rejectedSecret, clock.instant()),
                escapedEmoji.repeat(limit + 1)));
        assertEquals(400, rejected.statusCode());
        JsonNode error = JSON.readTree(rejected.asByteArray());
        assertEquals("InvalidParameterException", error.path("__type").asText());
        assertEquals("1 validation error detected: Value '" + "\uD83D\uDE00".repeat(limit + 1)
                + "' at 'friendlyDeviceName' failed to satisfy constraint: "
                + "Member must have length less than or equal to 131072", error.path("message").asText());

        String secret = cognitoJson("AssociateSoftwareToken", accessTokenBody(accessToken))
                .path("SecretCode").asText();
        assertEquals("SUCCESS", cognitoJson("VerifySoftwareToken", """
                {"AccessToken":"%s","UserCode":"%s","FriendlyDeviceName":"%s"}
                """.formatted(accessToken, CognitoTotp.code(secret, clock.instant()), escapedEmoji.repeat(limit)))
                .path("Status").asText());
        cognitoAction("SetUserMFAPreference", """
                {"AccessToken":"%s","SoftwareTokenMfaSettings":{"Enabled":true,"PreferredMfa":true}}
                """.formatted(accessToken))
                .then().statusCode(200);
        JsonNode challenged = JSON.readTree(cognitoAction("InitiateAuth", loginRequest).asByteArray());
        assertEquals("\uD83D\uDE00".repeat(limit),
                challenged.path("ChallengeParameters").path("FRIENDLY_DEVICE_NAME").asText());
    }

    private static String createOptionalPool() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"OptionalTotpPool","UserPoolTier":"ESSENTIALS",
                 "Policies":{"SignInPolicy":{"AllowedFirstAuthFactors":["PASSWORD","EMAIL_OTP"]}}}
                """).path("UserPool").path("Id").asText();
        cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"OPTIONAL",
                 "SoftwareTokenMfaConfiguration":{"Enabled":true}}
                """.formatted(poolId));
        return poolId;
    }

    private static String createClient(String poolId) throws Exception {
        return cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"optional-totp-client",
                 "ExplicitAuthFlows":["ALLOW_USER_PASSWORD_AUTH","ALLOW_USER_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
    }

    private static void createUser(String poolId, String username) throws Exception {
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","MessageAction":"SUPPRESS",
                 "UserAttributes":[{"Name":"email","Value":"%s@example.com"},
                                   {"Name":"email_verified","Value":"true"}]}
                """.formatted(poolId, username, username));
        cognitoJson("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, username, PASSWORD));
    }

    private static String accessTokenBody(String accessToken) {
        return """
                {"AccessToken":"%s"}
                """.formatted(accessToken);
    }

    private static String secretHash(String clientId, String clientSecret, String username) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(
                mac.doFinal((username + clientId).getBytes(StandardCharsets.UTF_8)));
    }
}
