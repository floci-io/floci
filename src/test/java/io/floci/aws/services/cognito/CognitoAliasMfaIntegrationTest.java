package io.floci.aws.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.latestSms;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * MFA challenges in a pool created with {@code UsernameAttributes = ["email"]}. The session of such a challenge
 * keeps the user's own username (the {@code sub}), while the user signed in with, and answers with, the email:
 * the challenge response has to accept that sign-in alias, and only the alias of the session's own user.
 */
@QuarkusTest
class CognitoAliasMfaIntegrationTest {
    private static final String PASSWORD = "Perm1234!";
    private static final String TEMPORARY_PASSWORD = "Temp1234!";

    @Inject
    Clock clock;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void totpChallengesAcceptTheEmailTheUserSignedInWith() throws Exception {
        String poolId = createAliasPool("""
                {"MfaConfiguration":"ON","SoftwareTokenMfaConfiguration":{"Enabled":true}}""");
        String clientId = createClient(poolId);
        String email = createUser(poolId, null);
        String otherEmail = createUser(poolId, null);

        String loginRequest = login(clientId, email);
        JsonNode challenge = cognitoJson("InitiateAuth", loginRequest);
        assertEquals("MFA_SETUP", challenge.path("ChallengeName").asText());

        JsonNode associated = cognitoJson("AssociateSoftwareToken", """
                {"Session":"%s"}
                """.formatted(challenge.path("Session").asText()));
        String secret = associated.path("SecretCode").asText();
        String code = CognitoTotp.code(secret, clock.instant());
        JsonNode verified = cognitoJson("VerifySoftwareToken", """
                {"Session":"%s","UserCode":"%s"}
                """.formatted(associated.path("Session").asText(), code));
        String setupSession = verified.path("Session").asText();

        // the email of another user does not name the user of this session
        cognitoAction("RespondToAuthChallenge", respond(clientId, "MFA_SETUP", setupSession, otherEmail, ""))
                .then().statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Session does not match user"));

        JsonNode completed = cognitoJson("RespondToAuthChallenge",
                respond(clientId, "MFA_SETUP", setupSession, email, ""));
        assertFalse(completed.path("AuthenticationResult").path("AccessToken").asText().isEmpty());

        JsonNode later = cognitoJson("InitiateAuth", loginRequest);
        assertEquals("SOFTWARE_TOKEN_MFA", later.path("ChallengeName").asText());
        String nextCode = CognitoTotp.code(secret, clock.instant().plusSeconds(30));
        String laterSession = later.path("Session").asText();
        cognitoAction("RespondToAuthChallenge", respond(clientId, "SOFTWARE_TOKEN_MFA", laterSession, otherEmail,
                ",\"SOFTWARE_TOKEN_MFA_CODE\":\"" + nextCode + "\""))
                .then().statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Session does not match user"));
        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", respond(clientId, "SOFTWARE_TOKEN_MFA",
                laterSession, email, ",\"SOFTWARE_TOKEN_MFA_CODE\":\"" + nextCode + "\""));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
    }

    @Test
    void smsChallengeAcceptsTheEmailTheUserSignedInWith() throws Exception {
        String poolId = createAliasPool("""
                {"MfaConfiguration":"ON","SmsMfaConfiguration":{"SmsAuthenticationMessage":"Your MFA code is {####}"}}""");
        String clientId = createClient(poolId);
        String phone = "+1555" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        String email = createUser(poolId, phone);
        String otherEmail = createUser(poolId, null);

        JsonNode challenge = cognitoJson("InitiateAuth", login(clientId, email));
        assertEquals("SMS_MFA", challenge.path("ChallengeName").asText());
        String code = CognitoCodes.sixDigitCode(latestSms(phone));
        String codeResponse = ",\"SMS_MFA_CODE\":\"" + code + "\"";
        String session = challenge.path("Session").asText();

        cognitoAction("RespondToAuthChallenge", respond(clientId, "SMS_MFA", session, otherEmail, codeResponse))
                .then().statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Session does not match user"));
        JsonNode result = cognitoJson("RespondToAuthChallenge",
                respond(clientId, "SMS_MFA", session, email, codeResponse));
        assertFalse(result.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
    }

    @Test
    void newPasswordChallengeAcceptsTheEmailTheUserSignedInWith() throws Exception {
        String poolId = createAliasPool("""
                {"MfaConfiguration":"OFF"}""");
        String clientId = createClient(poolId);
        String email = createUserWithTemporaryPassword(poolId);
        String otherEmail = createUserWithTemporaryPassword(poolId);

        JsonNode challenge = cognitoJson("InitiateAuth", login(clientId, email, TEMPORARY_PASSWORD));
        assertEquals("NEW_PASSWORD_REQUIRED", challenge.path("ChallengeName").asText());
        String session = challenge.path("Session").asText();
        String newPassword = ",\"NEW_PASSWORD\":\"" + PASSWORD + "\"";

        cognitoAction("RespondToAuthChallenge",
                respond(clientId, "NEW_PASSWORD_REQUIRED", session, otherEmail, newPassword))
                .then().statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Invalid session for the user."));
        JsonNode result = cognitoJson("RespondToAuthChallenge",
                respond(clientId, "NEW_PASSWORD_REQUIRED", session, email, newPassword));
        assertFalse(result.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
    }

    @Test
    void newPasswordChallengeCompletesWhenTheResponseChangesTheEmail() throws Exception {
        String poolId = createAliasPool("""
                {"MfaConfiguration":"OFF"}""");
        String clientId = createClient(poolId);
        String email = createUserWithTemporaryPassword(poolId);
        String newEmail = "alias-mfa-new-" + UUID.randomUUID() + "@example.com";

        JsonNode challenge = cognitoJson("InitiateAuth", login(clientId, email, TEMPORARY_PASSWORD));
        assertEquals("NEW_PASSWORD_REQUIRED", challenge.path("ChallengeName").asText());
        String responses = ",\"NEW_PASSWORD\":\"" + PASSWORD + "\",\"userAttributes.email\":\"" + newEmail + "\"";

        JsonNode result = cognitoJson("RespondToAuthChallenge", respond(clientId, "NEW_PASSWORD_REQUIRED",
                challenge.path("Session").asText(), email, responses));
        assertFalse(result.path("AuthenticationResult").path("AccessToken").asText().isEmpty());

        JsonNode signedIn = cognitoJson("InitiateAuth", login(clientId, newEmail));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
    }

    private static String createAliasPool(String mfaConfiguration) throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"AliasMfaPool","UsernameAttributes":["email"]}
                """).path("UserPool").path("Id").asText();
        String configuration = mfaConfiguration.substring(1, mfaConfiguration.length() - 1);
        cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s",%s}
                """.formatted(poolId, configuration));
        return poolId;
    }

    private static String createClient(String poolId) throws Exception {
        return cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"alias-mfa-client",
                 "ExplicitAuthFlows":["ALLOW_USER_PASSWORD_AUTH","ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
    }

    /** Creates a user that signs in with the returned email; {@code phone} is optional. */
    private static String createUser(String poolId, String phone) throws Exception {
        String email = "alias-mfa-" + UUID.randomUUID() + "@example.com";
        String phoneAttribute = phone == null ? ""
                : ",\"UserAttributes\":[{\"Name\":\"phone_number\",\"Value\":\"" + phone + "\"}]";
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","MessageAction":"SUPPRESS"%s}
                """.formatted(poolId, email, phoneAttribute));
        cognitoJson("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, email, PASSWORD));
        return email;
    }

    /** Creates a user that still has to choose a password, and signs in with the returned email. */
    private static String createUserWithTemporaryPassword(String poolId) throws Exception {
        String email = "alias-mfa-" + UUID.randomUUID() + "@example.com";
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","TemporaryPassword":"%s","MessageAction":"SUPPRESS"}
                """.formatted(poolId, email, TEMPORARY_PASSWORD));
        return email;
    }

    private static String login(String clientId, String email) {
        return login(clientId, email, PASSWORD);
    }

    private static String login(String clientId, String email, String password) {
        return """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(clientId, email, password);
    }

    /** {@code extraResponses} is appended inside {@code ChallengeResponses}, so it starts with a comma. */
    private static String respond(String clientId, String challengeName, String session, String username,
                                  String extraResponses) {
        return """
                {"ClientId":"%s","ChallengeName":"%s","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"%s"%s}}
                """.formatted(clientId, challengeName, session, username, extraResponses);
    }
}
