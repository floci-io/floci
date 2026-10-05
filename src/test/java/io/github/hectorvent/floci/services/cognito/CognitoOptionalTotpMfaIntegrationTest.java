package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CognitoOptionalTotpMfaIntegrationTest {
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

        // Floci sends no email MFA code after a password, so email MFA alone keeps the email code choice.
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
}
