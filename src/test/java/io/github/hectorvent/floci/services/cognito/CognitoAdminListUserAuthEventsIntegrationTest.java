package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CognitoAdminListUserAuthEventsIntegrationTest {
    private static final String PASSWORD = "Perm1234!";
    private static final String WRONG_PASSWORD = "Wrong1234!";

    @Inject
    Clock clock;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void poolWithoutThreatProtectionRefusesTheOperation() throws Exception {
        String defaultPool = createPool("\"UserPoolTier\":\"ESSENTIALS\"");
        String essentialsAudit = createPool("\"UserPoolTier\":\"ESSENTIALS\","
                + "\"UserPoolAddOns\":{\"AdvancedSecurityMode\":\"AUDIT\"}");
        String plusOff = createPool("\"UserPoolTier\":\"PLUS\",\"UserPoolAddOns\":{\"AdvancedSecurityMode\":\"OFF\"}");
        String plusNoAddOns = createPool("\"UserPoolTier\":\"PLUS\"");
        for (String poolId : new String[] {defaultPool, essentialsAudit, plusOff, plusNoAddOns}) {
            String username = createUser(poolId);
            listEvents(poolId, username, "")
                    .then().statusCode(400)
                    .body("__type", equalTo("UserPoolAddOnNotEnabledException"));
        }
    }

    @Test
    void missingPoolAndMissingUserAreReported() throws Exception {
        listEvents("us-east-1_doesnotexist", "nobody", "")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        String poolId = createEligiblePool("AUDIT");
        listEvents(poolId, "nobody-" + UUID.randomUUID(), "")
                .then().statusCode(400).body("__type", equalTo("UserNotFoundException"));
    }

    @Test
    void poolIsCheckedBeforeEligibilityAndEligibilityBeforeUser() throws Exception {
        String ineligible = createPool("\"UserPoolTier\":\"ESSENTIALS\"");
        listEvents(ineligible, "nobody", "")
                .then().statusCode(400).body("__type", equalTo("UserPoolAddOnNotEnabledException"));
    }

    @Test
    void userWithoutSignInsHasNoEvents() throws Exception {
        String poolId = createEligiblePool("ENFORCED");
        String username = createUser(poolId);
        listEvents(poolId, username, "")
                .then().statusCode(200)
                .body("AuthEvents.size()", equalTo(0))
                .body("containsKey('NextToken')", equalTo(false));
    }

    @Test
    void failedThenPassedSignInIsListedNewestFirst() throws Exception {
        String poolId = createEligiblePool("AUDIT");
        String clientId = createClient(poolId);
        String username = createUser(poolId);

        cognitoAction("InitiateAuth", passwordAuth(clientId, username, WRONG_PASSWORD))
                .then().statusCode(400).body("__type", equalTo("NotAuthorizedException"));
        cognitoJson("InitiateAuth", passwordAuth(clientId, username, PASSWORD));

        JsonNode events = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username, ""))
                .path("AuthEvents");
        assertEquals(2, events.size());
        JsonNode passed = events.get(0);
        JsonNode failed = events.get(1);
        assertEquals("SignIn", passed.path("EventType").asText());
        assertEquals("Pass", passed.path("EventResponse").asText());
        assertChallenges(passed, "Password", "Success");
        assertEquals("SignIn", failed.path("EventType").asText());
        assertEquals("Fail", failed.path("EventResponse").asText());
        assertChallenges(failed, "Password", "Failure");
        assertTrue(passed.path("CreationDate").isNumber());
        assertTrue(passed.path("CreationDate").asDouble() >= failed.path("CreationDate").asDouble());
        assertFalse(passed.path("EventId").asText().isEmpty());
        assertFalse(passed.path("EventId").asText().equals(failed.path("EventId").asText()));
        for (JsonNode event : events) {
            assertTrue(event.path("EventRisk").isMissingNode());
            assertTrue(event.path("EventContextData").isMissingNode());
            assertTrue(event.path("EventFeedback").isMissingNode());
        }
    }

    @Test
    void adminInitiateAuthRecordsAndTheSubResolvesTheUser() throws Exception {
        String poolId = createEligiblePool("AUDIT");
        String clientId = createClient(poolId);
        String username = createUser(poolId);
        cognitoAction("AdminInitiateAuth", """
                {"UserPoolId":"%s","ClientId":"%s","AuthFlow":"ADMIN_USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(poolId, clientId, username, WRONG_PASSWORD))
                .then().statusCode(400);
        String sub = "";
        for (JsonNode attribute : cognitoJson("AdminGetUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, username)).path("UserAttributes")) {
            if ("sub".equals(attribute.path("Name").asText())) {
                sub = attribute.path("Value").asText();
            }
        }
        assertFalse(sub.isEmpty());
        JsonNode events = cognitoJson("AdminListUserAuthEvents", listBody(poolId, sub, "")).path("AuthEvents");
        assertEquals(1, events.size());
        assertEquals("Fail", events.get(0).path("EventResponse").asText());
    }

    @Test
    void unknownUserAndIneligiblePoolRecordNothing() throws Exception {
        String ineligible = createPool("\"UserPoolTier\":\"PLUS\"");
        String clientId = createClient(ineligible);
        String username = createUser(ineligible);
        cognitoJson("InitiateAuth", passwordAuth(clientId, username, PASSWORD));
        cognitoAction("InitiateAuth", passwordAuth(clientId, username, WRONG_PASSWORD)).then().statusCode(400);

        cognitoJson("UpdateUserPool", """
                {"UserPoolId":"%s","UserPoolTier":"PLUS","UserPoolAddOns":{"AdvancedSecurityMode":"AUDIT"}}
                """.formatted(ineligible));
        listEvents(ineligible, username, "").then().statusCode(200).body("AuthEvents.size()", equalTo(0));

        cognitoAction("InitiateAuth", passwordAuth(clientId, "ghost-" + UUID.randomUUID(), PASSWORD))
                .then().statusCode(400);
        listEvents(ineligible, username, "").then().statusCode(200).body("AuthEvents.size()", equalTo(0));
    }

    @Test
    void paginationWalksEveryEventOnceNewestFirst() throws Exception {
        String poolId = createEligiblePool("AUDIT");
        String clientId = createClient(poolId);
        String username = createUser(poolId);
        for (int i = 0; i < 5; i++) {
            if (i % 2 == 0) {
                cognitoAction("InitiateAuth", passwordAuth(clientId, username, WRONG_PASSWORD)).then().statusCode(400);
            } else {
                cognitoJson("InitiateAuth", passwordAuth(clientId, username, PASSWORD));
            }
        }
        JsonNode all = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username, "")).path("AuthEvents");
        assertEquals(5, all.size());

        JsonNode first = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username, "\"MaxResults\":2"));
        assertEquals(2, first.path("AuthEvents").size());
        assertFalse(first.path("NextToken").asText().isEmpty());
        JsonNode second = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username,
                "\"MaxResults\":2,\"NextToken\":\"" + first.path("NextToken").asText() + "\""));
        assertEquals(2, second.path("AuthEvents").size());
        JsonNode third = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username,
                "\"MaxResults\":2,\"NextToken\":\"" + second.path("NextToken").asText() + "\""));
        assertEquals(1, third.path("AuthEvents").size());
        assertTrue(third.path("NextToken").isMissingNode());

        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonNode page : new JsonNode[] {first, second, third}) {
            for (JsonNode event : page.path("AuthEvents")) {
                assertEquals(all.get(index++).path("EventId").asText(), event.path("EventId").asText());
                assertTrue(seen.add(event.path("EventId").asText()));
            }
        }
        assertEquals(5, seen.size());

        JsonNode zero = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username, "\"MaxResults\":0"));
        assertEquals(5, zero.path("AuthEvents").size());
    }

    @Test
    void invalidPageArgumentsAreRejected() throws Exception {
        String poolId = createEligiblePool("AUDIT");
        String username = createUser(poolId);
        for (int bad : new int[] {61, -1}) {
            listEvents(poolId, username, "\"MaxResults\":" + bad)
                    .then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"))
                    .body("message", containsString("1 validation error detected"))
                    .body("message", containsString("maxResults"));
        }
        listEvents(poolId, username, "\"NextToken\":\"bogus\"")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void maxResultsThatIsNotAnIntegerIsASerializationException() throws Exception {
        String poolId = createEligiblePool("AUDIT");
        String username = createUser(poolId);
        for (String bad : new String[] {"1.5", "\"2\"", "4294967297", "true"}) {
            listEvents(poolId, username, "\"MaxResults\":" + bad)
                    .then().statusCode(400)
                    .body("__type", equalTo("SerializationException"))
                    .body("message", equalTo("MaxResults must be an integer."));
        }
    }

    @Test
    void nullMaxResultsBehavesLikeAnOmittedOne() throws Exception {
        String poolId = createEligiblePool("AUDIT");
        String clientId = createClient(poolId);
        String username = createUser(poolId);
        for (int i = 0; i < 3; i++) {
            cognitoJson("InitiateAuth", passwordAuth(clientId, username, PASSWORD));
        }
        listEvents(poolId, username, "\"MaxResults\":null")
                .then().statusCode(200)
                .body("AuthEvents.size()", equalTo(3))
                .body("containsKey('NextToken')", equalTo(false));
    }

    @Test
    void newPasswordChallengeStaysInProgressUntilItIsAnswered() throws Exception {
        String poolId = createEligiblePool("ENFORCED");
        String clientId = createClient(poolId);
        String username = "temp-" + UUID.randomUUID();
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","TemporaryPassword":"Temp1234!","MessageAction":"SUPPRESS"}
                """.formatted(poolId, username));

        JsonNode challenge = cognitoJson("InitiateAuth", passwordAuth(clientId, username, "Temp1234!"));
        assertEquals("NEW_PASSWORD_REQUIRED", challenge.path("ChallengeName").asText());
        JsonNode events = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username, "")).path("AuthEvents");
        assertEquals(1, events.size());
        assertEquals("InProgress", events.get(0).path("EventResponse").asText());
        assertChallenges(events.get(0), "Password", "Success");
        String eventId = events.get(0).path("EventId").asText();

        JsonNode done = cognitoJson("RespondToAuthChallenge", """
                {"ClientId":"%s","ChallengeName":"NEW_PASSWORD_REQUIRED","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"%s","NEW_PASSWORD":"%s"}}
                """.formatted(clientId, challenge.path("Session").asText(), username, PASSWORD));
        assertFalse(done.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
        events = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username, "")).path("AuthEvents");
        assertEquals(1, events.size());
        assertEquals(eventId, events.get(0).path("EventId").asText());
        assertEquals("Pass", events.get(0).path("EventResponse").asText());
        assertChallenges(events.get(0), "Password", "Success");
    }

    @Test
    void softwareTokenMfaSignInAppendsTheMfaChallenge() throws Exception {
        String poolId = createEligiblePool("AUDIT");
        cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"OPTIONAL","SoftwareTokenMfaConfiguration":{"Enabled":true}}
                """.formatted(poolId));
        String clientId = createClient(poolId);
        String username = createUser(poolId);
        String accessToken = cognitoJson("InitiateAuth", passwordAuth(clientId, username, PASSWORD))
                .path("AuthenticationResult").path("AccessToken").asText();
        String secret = cognitoJson("AssociateSoftwareToken", """
                {"AccessToken":"%s"}
                """.formatted(accessToken)).path("SecretCode").asText();
        cognitoJson("VerifySoftwareToken", """
                {"AccessToken":"%s","UserCode":"%s"}
                """.formatted(accessToken, CognitoTotp.code(secret, clock.instant())));
        cognitoJson("SetUserMFAPreference", """
                {"AccessToken":"%s","SoftwareTokenMfaSettings":{"Enabled":true,"PreferredMfa":true}}
                """.formatted(accessToken));

        JsonNode challenged = cognitoJson("InitiateAuth", passwordAuth(clientId, username, PASSWORD));
        assertEquals("SOFTWARE_TOKEN_MFA", challenged.path("ChallengeName").asText());
        JsonNode events = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username, "")).path("AuthEvents");
        assertEquals(2, events.size());
        assertEquals("InProgress", events.get(0).path("EventResponse").asText());
        assertEquals("Pass", events.get(1).path("EventResponse").asText());

        cognitoAction("RespondToAuthChallenge", mfaResponse(clientId, challenged, username,
                        invalidTotpCode(secret, clock.instant())))
                .then().statusCode(400).body("__type", equalTo("CodeMismatchException"));
        events = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username, "")).path("AuthEvents");
        assertEquals("InProgress", events.get(0).path("EventResponse").asText());
        assertEquals(1, events.get(0).path("ChallengeResponses").size());

        String code = CognitoTotp.code(secret, clock.instant().plusSeconds(30));
        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", mfaResponse(clientId, challenged, username, code));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
        events = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username, "")).path("AuthEvents");
        assertEquals(2, events.size());
        JsonNode event = events.get(0);
        assertEquals("Pass", event.path("EventResponse").asText());
        assertEquals(2, event.path("ChallengeResponses").size());
        assertEquals("Password", event.path("ChallengeResponses").get(0).path("ChallengeName").asText());
        assertEquals("Success", event.path("ChallengeResponses").get(0).path("ChallengeResponse").asText());
        assertEquals("Mfa", event.path("ChallengeResponses").get(1).path("ChallengeName").asText());
        assertEquals("Success", event.path("ChallengeResponses").get(1).path("ChallengeResponse").asText());
    }

    @Test
    void srpWrongProofFailsAndRightProofPasses() throws Exception {
        String poolId = createEligiblePool("AUDIT");
        String clientId = createClient(poolId);
        String username = createUser(poolId);

        JsonNode wrong = srpStart(clientId, username);
        Map<String, String> badProof = CognitoSrpTestClient.proof(poolId, username, WRONG_PASSWORD,
                jsonMap(wrong.path("ChallengeParameters")));
        cognitoAction("RespondToAuthChallenge", srpResponse(clientId, wrong, badProof))
                .then().statusCode(400).body("__type", equalTo("NotAuthorizedException"));

        JsonNode right = srpStart(clientId, username);
        Map<String, String> proof = CognitoSrpTestClient.proof(poolId, username, PASSWORD,
                jsonMap(right.path("ChallengeParameters")));
        JsonNode done = cognitoJson("RespondToAuthChallenge", srpResponse(clientId, right, proof));
        assertFalse(done.path("AuthenticationResult").path("AccessToken").asText().isEmpty());

        JsonNode events = cognitoJson("AdminListUserAuthEvents", listBody(poolId, username, "")).path("AuthEvents");
        assertEquals(2, events.size());
        assertEquals("Pass", events.get(0).path("EventResponse").asText());
        assertEquals("Fail", events.get(1).path("EventResponse").asText());
        assertChallenges(events.get(1), "Password", "Failure");
    }

    @Test
    void recreatedUserStartsWithNoHistory() throws Exception {
        String poolId = createEligiblePool("AUDIT");
        String clientId = createClient(poolId);
        String username = createUser(poolId);
        cognitoJson("InitiateAuth", passwordAuth(clientId, username, PASSWORD));
        listEvents(poolId, username, "").then().body("AuthEvents.size()", equalTo(1));

        cognitoJson("AdminDeleteUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, username));
        createUser(poolId, username);
        listEvents(poolId, username, "").then().statusCode(200).body("AuthEvents.size()", equalTo(0));
    }

    @Test
    void userAuthChoiceFlowRecordsNothing() throws Exception {
        String poolId = createEligiblePool("AUDIT");
        cognitoJson("UpdateUserPool", """
                {"UserPoolId":"%s","UserPoolTier":"PLUS","UserPoolAddOns":{"AdvancedSecurityMode":"AUDIT"},
                 "Policies":{"SignInPolicy":{"AllowedFirstAuthFactors":["PASSWORD"]}}}
                """.formatted(poolId));
        String clientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"choice",
                 "ExplicitAuthFlows":["ALLOW_USER_AUTH","ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        String username = createUser(poolId);
        JsonNode done = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PREFERRED_CHALLENGE":"PASSWORD","PASSWORD":"%s"}}
                """.formatted(clientId, username, PASSWORD));
        assertFalse(done.path("AuthenticationResult").path("AccessToken").asText().isEmpty());
        listEvents(poolId, username, "").then().statusCode(200).body("AuthEvents.size()", equalTo(0));
    }

    // ──────────────────────────── helpers ────────────────────────────

    /** A six-digit code that is no valid TOTP for the secret within two steps either side of now. */
    private static String invalidTotpCode(String secret, Instant now) {
        for (int i = 0; i < 1_000_000; i++) {
            String candidate = String.format(Locale.ROOT, "%06d", i);
            if (!CognitoTotp.validCode(secret, candidate, now.minusSeconds(30))
                    && !CognitoTotp.validCode(secret, candidate, now)
                    && !CognitoTotp.validCode(secret, candidate, now.plusSeconds(30))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Every six-digit code is valid");
    }

    private static void assertChallenges(JsonNode event, String name, String response) {
        assertEquals(1, event.path("ChallengeResponses").size());
        assertEquals(name, event.path("ChallengeResponses").get(0).path("ChallengeName").asText());
        assertEquals(response, event.path("ChallengeResponses").get(0).path("ChallengeResponse").asText());
    }

    private static String createEligiblePool(String mode) throws Exception {
        return createPool("\"UserPoolTier\":\"PLUS\",\"UserPoolAddOns\":{\"AdvancedSecurityMode\":\"" + mode + "\"}");
    }

    private static String createPool(String extra) throws Exception {
        return cognitoJson("CreateUserPool", """
                {"PoolName":"AuthEvents-%s",%s}
                """.formatted(UUID.randomUUID(), extra)).path("UserPool").path("Id").asText();
    }

    private static String createClient(String poolId) throws Exception {
        return cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"auth-events-client",
                 "ExplicitAuthFlows":["ALLOW_USER_PASSWORD_AUTH","ALLOW_ADMIN_USER_PASSWORD_AUTH",
                                      "ALLOW_USER_SRP_AUTH","ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
    }

    private static String createUser(String poolId) throws Exception {
        String username = "user-" + UUID.randomUUID();
        createUser(poolId, username);
        return username;
    }

    private static void createUser(String poolId, String username) throws Exception {
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","MessageAction":"SUPPRESS"}
                """.formatted(poolId, username));
        cognitoJson("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, username, PASSWORD));
    }

    private static String passwordAuth(String clientId, String username, String password) {
        return """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(clientId, username, password);
    }

    private static String listBody(String poolId, String username, String extra) {
        return "{\"UserPoolId\":\"" + poolId + "\",\"Username\":\"" + username + "\""
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static Response listEvents(String poolId, String username, String extra) {
        return cognitoAction("AdminListUserAuthEvents", listBody(poolId, username, extra));
    }

    private static String mfaResponse(String clientId, JsonNode challenge, String username, String code) {
        return """
                {"ClientId":"%s","ChallengeName":"SOFTWARE_TOKEN_MFA","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"%s","SOFTWARE_TOKEN_MFA_CODE":"%s"}}
                """.formatted(clientId, challenge.path("Session").asText(), username, code);
    }

    private static JsonNode srpStart(String clientId, String username) throws Exception {
        return cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_SRP_AUTH",
                 "AuthParameters":{"USERNAME":"%s","SRP_A":"%s"}}
                """.formatted(clientId, username, CognitoSrpTestClient.publicA()));
    }

    private static String srpResponse(String clientId, JsonNode challenge, Map<String, String> proof) {
        return """
                {"ClientId":"%s","ChallengeName":"PASSWORD_VERIFIER","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"%s","PASSWORD_CLAIM_SECRET_BLOCK":"%s",
                  "PASSWORD_CLAIM_SIGNATURE":"%s","TIMESTAMP":"%s"}}
                """.formatted(clientId, challenge.path("Session").asText(), proof.get("USERNAME"),
                proof.get("PASSWORD_CLAIM_SECRET_BLOCK"), proof.get("PASSWORD_CLAIM_SIGNATURE"),
                proof.get("TIMESTAMP"));
    }

    private static Map<String, String> jsonMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        node.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        return map;
    }
}
