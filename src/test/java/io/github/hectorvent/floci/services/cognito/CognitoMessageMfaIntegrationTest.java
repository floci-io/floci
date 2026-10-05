package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SMS and email message MFA after a password, over the JSON 1.1 wire protocol. */
@QuarkusTest
class CognitoMessageMfaIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SIX_DIGIT_CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final String PASSWORD = "Perm1234!";
    private static final String SMS_CONFIG = """
            "SmsMfaConfiguration":{"SmsAuthenticationMessage":"Your MFA code is {####}"}""";
    private static final String EMAIL_CONFIG = """
            "EmailMfaConfiguration":{"Message":"Your MFA code is {####}","Subject":"Your MFA code"}""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void optionalSmsMfaChallengesOnceTurnedOnAndVerifiesThePhone() throws Exception {
        String poolId = createPool("OPTIONAL", SMS_CONFIG);
        String clientId = createClient(poolId);
        User user = createUser(poolId);
        String accessToken = passwordLogin(clientId, user).path("AuthenticationResult").path("AccessToken").asText();
        assertFalse(accessToken.isEmpty(), "MFA is optional and not turned on yet");

        cognitoAction("SetUserMFAPreference", """
                {"AccessToken":"%s","SMSMfaSettings":{"Enabled":true}}
                """.formatted(accessToken)).then().statusCode(200);
        cognitoAction("GetUser", """
                {"AccessToken":"%s"}
                """.formatted(accessToken))
                .then().statusCode(200)
                .body("UserMFASettingList", contains("SMS_MFA"))
                .body("$", not(hasKey("PreferredMfaSetting")));

        JsonNode challenge = passwordLogin(clientId, user);
        assertEquals("SMS_MFA", challenge.path("ChallengeName").asText());
        assertEquals("SMS", challenge.path("ChallengeParameters").path("CODE_DELIVERY_DELIVERY_MEDIUM").asText());
        assertTrue(challenge.path("ChallengeParameters").path("CODE_DELIVERY_DESTINATION").asText().startsWith("+"));
        String sms = latestSms(user.phone());
        assertTrue(sms.startsWith("Your MFA code is "), sms);
        String code = sixDigitCode(sms);

        String wrongCode = code.equals("000000") ? "000001" : "000000";
        cognitoAction("RespondToAuthChallenge", respond(clientId, "SMS_MFA", challenge, user,
                "SMS_MFA_CODE", wrongCode))
                .then().statusCode(400).body("__type", equalTo("CodeMismatchException"));
        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", respond(clientId, "SMS_MFA", challenge, user,
                "SMS_MFA_CODE", code));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());

        JsonNode adminUser = cognitoJson("AdminGetUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, user.username()));
        boolean phoneVerified = false;
        for (JsonNode attribute : adminUser.path("UserAttributes")) {
            if ("phone_number_verified".equals(attribute.path("Name").asText())) {
                phoneVerified = "true".equals(attribute.path("Value").asText());
            }
        }
        assertTrue(phoneVerified, "a completed SMS MFA challenge verifies the phone number");
    }

    @Test
    void emailMfaUsesThePoolsEmailMfaMessage() throws Exception {
        String poolId = createPool("OPTIONAL", EMAIL_CONFIG);
        String clientId = createClient(poolId);
        User user = createUser(poolId);
        cognitoAction("AdminSetUserMFAPreference", """
                {"UserPoolId":"%s","Username":"%s","EmailMfaSettings":{"Enabled":true,"PreferredMfa":true}}
                """.formatted(poolId, user.username())).then().statusCode(200);

        JsonNode challenge = cognitoJson("AdminInitiateAuth", """
                {"UserPoolId":"%s","ClientId":"%s","AuthFlow":"ADMIN_USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(poolId, clientId, user.username(), PASSWORD));
        assertEquals("EMAIL_OTP", challenge.path("ChallengeName").asText());
        assertEquals("EMAIL", challenge.path("ChallengeParameters").path("CODE_DELIVERY_DELIVERY_MEDIUM").asText());
        JsonNode email = latestEmail(user.email());
        assertEquals("Your MFA code", email.path("Subject").asText());
        String code = sixDigitCode(email.path("Body").path("text_part").asText());

        JsonNode signedIn = cognitoJson("AdminRespondToAuthChallenge", """
                {"UserPoolId":"%s","ClientId":"%s","ChallengeName":"EMAIL_OTP","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"%s","EMAIL_OTP_CODE":"%s"}}
                """.formatted(poolId, clientId, challenge.path("Session").asText(), user.username(), code));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
    }

    @Test
    void severalFactorsWithoutAPreferenceAskToChoose() throws Exception {
        String poolId = createPool("OPTIONAL", SMS_CONFIG + "," + EMAIL_CONFIG);
        String clientId = createClient(poolId);
        User user = createUser(poolId);
        cognitoAction("AdminSetUserMFAPreference", """
                {"UserPoolId":"%s","Username":"%s","SMSMfaSettings":{"Enabled":true},
                 "EmailMfaSettings":{"Enabled":true}}
                """.formatted(poolId, user.username())).then().statusCode(200);

        JsonNode select = passwordLogin(clientId, user);
        assertEquals("SELECT_MFA_TYPE", select.path("ChallengeName").asText());
        assertEquals("[\"SMS_MFA\",\"EMAIL_OTP\"]",
                select.path("ChallengeParameters").path("MFAS_CAN_CHOOSE").asText());
        cognitoAction("RespondToAuthChallenge", respond(clientId, "SELECT_MFA_TYPE", select, user,
                "ANSWER", "SOFTWARE_TOKEN_MFA"))
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        JsonNode emailChallenge = cognitoJson("RespondToAuthChallenge", respond(clientId, "SELECT_MFA_TYPE", select,
                user, "ANSWER", "EMAIL_OTP"));
        assertEquals("EMAIL_OTP", emailChallenge.path("ChallengeName").asText());
        String code = sixDigitCode(latestEmail(user.email()).path("Body").path("text_part").asText());
        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", respond(clientId, "EMAIL_OTP", emailChallenge,
                user, "EMAIL_OTP_CODE", code));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());

        cognitoAction("AdminSetUserMFAPreference", """
                {"UserPoolId":"%s","Username":"%s","SMSMfaSettings":{"PreferredMfa":true}}
                """.formatted(poolId, user.username())).then().statusCode(200);
        assertEquals("SMS_MFA", passwordLogin(clientId, user).path("ChallengeName").asText());
    }

    @Test
    void requiredMfaSendsAnSmsCodeToAUserWithAPhoneNumber() throws Exception {
        String poolId = createPool("ON", SMS_CONFIG);
        String clientId = createClient(poolId);
        User user = createUser(poolId);

        JsonNode challenge = passwordLogin(clientId, user);
        assertEquals("SMS_MFA", challenge.path("ChallengeName").asText());
        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", respond(clientId, "SMS_MFA", challenge, user,
                "SMS_MFA_CODE", sixDigitCode(latestSms(user.phone()))));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
    }

    @Test
    void messageFactorsNeedADestinationAndLimitChoiceBasedSignIn() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"MessageMfaChoicePool","UserPoolTier":"ESSENTIALS",
                 "Policies":{"SignInPolicy":{"AllowedFirstAuthFactors":["PASSWORD","EMAIL_OTP"]}}}
                """).path("UserPool").path("Id").asText();
        cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"OPTIONAL",%s}
                """.formatted(poolId, SMS_CONFIG));
        cognitoAction("GetUserPoolMfaConfig", """
                {"UserPoolId":"%s"}
                """.formatted(poolId))
                .then().statusCode(200)
                .body("SmsMfaConfiguration.SmsAuthenticationMessage", equalTo("Your MFA code is {####}"));
        String clientId = createClient(poolId);
        String noPhone = "no-phone-" + UUID.randomUUID();
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","MessageAction":"SUPPRESS"}
                """.formatted(poolId, noPhone));
        cognitoAction("AdminSetUserMFAPreference", """
                {"UserPoolId":"%s","Username":"%s","SMSMfaSettings":{"Enabled":true}}
                """.formatted(poolId, noPhone))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo("User does not have delivery config set to turn on SMS_MFA"));

        User user = createUser(poolId);
        cognitoJson("AdminUpdateUserAttributes", """
                {"UserPoolId":"%s","Username":"%s","UserAttributes":[{"Name":"email_verified","Value":"true"}]}
                """.formatted(poolId, user.username()));
        String userAuth = """
                {"ClientId":"%s","AuthFlow":"USER_AUTH","AuthParameters":{"USERNAME":"%s"}}
                """.formatted(clientId, user.username());
        cognitoAction("InitiateAuth", userAuth)
                .then().statusCode(200)
                .body("AvailableChallenges", contains("PASSWORD", "PASSWORD_SRP", "EMAIL_OTP"));
        cognitoAction("AdminSetUserMFAPreference", """
                {"UserPoolId":"%s","Username":"%s","SMSMfaSettings":{"Enabled":true}}
                """.formatted(poolId, user.username())).then().statusCode(200);
        cognitoAction("InitiateAuth", userAuth)
                .then().statusCode(200)
                .body("AvailableChallenges", contains("PASSWORD", "PASSWORD_SRP"));

        cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"OFF"}
                """.formatted(poolId));
        cognitoAction("GetUserPoolMfaConfig", """
                {"UserPoolId":"%s"}
                """.formatted(poolId))
                .then().statusCode(200)
                .body("$", not(hasKey("SmsMfaConfiguration")));
    }

    private record User(String username, String phone, String email) {}

    private static User createUser(String poolId) throws Exception {
        String username = "mfa-" + UUID.randomUUID();
        String phone = "+1555" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        String email = username + "@example.com";
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","MessageAction":"SUPPRESS",
                 "UserAttributes":[{"Name":"phone_number","Value":"%s"},{"Name":"email","Value":"%s"}]}
                """.formatted(poolId, username, phone, email));
        cognitoJson("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, username, PASSWORD));
        return new User(username, phone, email);
    }

    private static String createPool(String mfaConfiguration, String factorConfiguration) throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"MessageMfaPool"}
                """).path("UserPool").path("Id").asText();
        cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"%s",%s}
                """.formatted(poolId, mfaConfiguration, factorConfiguration));
        return poolId;
    }

    private static String createClient(String poolId) throws Exception {
        return cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"message-mfa-client",
                 "ExplicitAuthFlows":["ALLOW_USER_PASSWORD_AUTH","ALLOW_ADMIN_USER_PASSWORD_AUTH","ALLOW_USER_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
    }

    private static JsonNode passwordLogin(String clientId, User user) throws Exception {
        return cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(clientId, user.username(), PASSWORD));
    }

    private static String respond(String clientId, String challengeName, JsonNode challenge, User user,
                                  String key, String value) {
        return """
                {"ClientId":"%s","ChallengeName":"%s","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"%s","%s":"%s"}}
                """.formatted(clientId, challengeName, challenge.path("Session").asText(), user.username(), key,
                value);
    }

    private static String latestSms(String phone) throws Exception {
        JsonNode messages = JSON.readTree(given().queryParam("phone", phone).get("/_aws/sns")
                .then().statusCode(200).extract().asString()).path("messages");
        assertTrue(messages.isArray() && !messages.isEmpty(), "an SMS should have been sent to " + phone);
        return messages.get(messages.size() - 1).path("Message").asText();
    }

    private static JsonNode latestEmail(String recipient) throws Exception {
        JsonNode messages = JSON.readTree(given().queryParam("email", recipient).get("/_aws/ses")
                .then().statusCode(200).extract().asString()).path("messages");
        assertTrue(messages.isArray() && !messages.isEmpty(), "an email should have been sent to " + recipient);
        return messages.get(messages.size() - 1);
    }

    private static String sixDigitCode(String message) {
        Matcher matcher = SIX_DIGIT_CODE.matcher(message);
        assertTrue(matcher.find(), "the message should carry a six-digit code: " + message);
        return matcher.group(1);
    }
}
