package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CognitoCustomSrpAuthTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "Perm1234!";
    private static final String DEFINE = "arn:aws:lambda:us-east-1:000000000000:function:define";
    private static final String CREATE = "arn:aws:lambda:us-east-1:000000000000:function:create";
    private static final String VERIFY = "arn:aws:lambda:us-east-1:000000000000:function:verify";

    private CognitoService service;
    private LambdaService lambda;
    private MutableClock clock;
    private UserPool pool;
    private UserPoolClient client;
    private final List<JsonNode> histories = new ArrayList<>();
    private boolean temporaryPassword;

    @BeforeEach
    void setUp() throws Exception {
        lambda = mock(LambdaService.class);
        clock = new MutableClock();
        service = new CognitoService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", "cloudfront.net", new RegionResolver("us-east-1", "000000000000"),
                lambda, mock(AcmService.class), null, null, null, clock);
        pool = service.createUserPool(Map.of("PoolName", "CustomSrp",
                "Policies", Map.of("PasswordPolicy", Map.of("MinimumLength", 8)), "LambdaConfig", Map.of(
                "DefineAuthChallenge", DEFINE, "CreateAuthChallenge", CREATE,
                "VerifyAuthChallengeResponse", VERIFY)), "us-east-1");
        service.adminCreateUser(pool.getId(), USERNAME, Map.of(), null);
        service.adminSetUserPassword(pool.getId(), USERNAME, PASSWORD, true);
        client = service.createUserPoolClient(pool.getId(), "app", false, false, List.of(), List.of());
        client.setExplicitAuthFlows(List.of("ALLOW_CUSTOM_AUTH"));
        when(lambda.invoke(anyString(), eq(DEFINE), any(byte[].class), any())).thenAnswer(call -> {
            JsonNode history = MAPPER.readTree((byte[]) call.getArgument(2)).path("request").path("session");
            histories.add(history);
            String last = history.isEmpty() ? "" : history.get(history.size() - 1).path("challengeName").asText();
            if ("SRP_A".equals(last)) {
                return ok(Map.of("challengeName", "PASSWORD_VERIFIER"));
            }
            if ("PASSWORD_VERIFIER".equals(last) && temporaryPassword) {
                return ok(Map.of("challengeName", "NEW_PASSWORD_REQUIRED"));
            }
            if ("CUSTOM_CHALLENGE".equals(last)) {
                return ok(Map.of("issueTokens", true));
            }
            return ok(Map.of("challengeName", "CUSTOM_CHALLENGE"));
        });
        when(lambda.invoke(anyString(), eq(CREATE), any(byte[].class), any())).thenReturn(ok(Map.of(
                "publicChallengeParameters", Map.of("question", "colour"),
                "privateChallengeParameters", Map.of("answer", "blue"))));
        when(lambda.invoke(anyString(), eq(VERIFY), any(byte[].class), any())).thenReturn(ok(Map.of("answerCorrect", true)));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void srpProofPrecedesCustomChallengesAndTokens(boolean admin, boolean temporary) throws Exception {
        temporaryPassword = temporary;
        service.adminSetUserPassword(pool.getId(), USERNAME, PASSWORD, !temporary);
        Map<String, Object> started = start(admin);
        verify(lambda, never()).invoke(anyString(), eq(CREATE), any(byte[].class), any());
        Map<String, String> proof = proof(started);
        Map<String, Object> next = respond(admin, client.getClientId(), "PASSWORD_VERIFIER", started, proof);
        assertFalse(next.containsKey("AuthenticationResult"));
        assertNotEquals(started.get("Session"), next.get("Session"));
        assertDenied(() -> respond(admin, client.getClientId(), "PASSWORD_VERIFIER", started, proof));
        if (temporary) {
            assertEquals("NEW_PASSWORD_REQUIRED", next.get("ChallengeName"));
            Map<?, ?> params = (Map<?, ?>) next.get("ChallengeParameters");
            assertEquals(USERNAME, params.get("USER_ID_FOR_SRP"));
            assertTrue(MAPPER.readTree((String) params.get("requiredAttributes")).isArray());
            assertTrue(MAPPER.readTree((String) params.get("userAttributes")).isObject());
            Map<String, Object> passwordChallenge = next;
            next = respond(admin, client.getClientId(), "NEW_PASSWORD_REQUIRED", next,
                    Map.of("USERNAME", USERNAME, "NEW_PASSWORD", "NewPerm1234!"));
            assertFalse(next.containsKey("AuthenticationResult"));
            assertDenied(() -> respond(admin, client.getClientId(), "NEW_PASSWORD_REQUIRED", passwordChallenge,
                    Map.of("USERNAME", USERNAME, "NEW_PASSWORD", "Another1234!")));
        }
        assertEquals("CUSTOM_CHALLENGE", next.get("ChallengeName"));
        Map<String, Object> completed = respond(admin, client.getClientId(), "CUSTOM_CHALLENGE", next,
                Map.of("USERNAME", USERNAME, "ANSWER", "blue"));
        assertNotNull(((Map<?, ?>) completed.get("AuthenticationResult")).get("AccessToken"));
        JsonNode history = histories.get(histories.size() - 1);
        List<String> expected = temporary
                ? List.of("SRP_A", "PASSWORD_VERIFIER", "NEW_PASSWORD_REQUIRED", "CUSTOM_CHALLENGE")
                : List.of("SRP_A", "PASSWORD_VERIFIER", "CUSTOM_CHALLENGE");
        assertEquals(expected.size(), history.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), history.get(i).path("challengeName").asText());
            assertTrue(history.get(i).path("challengeResult").asBoolean());
        }
        verify(lambda, times(1)).invoke(anyString(), eq(CREATE), any(byte[].class), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "username", "client", "phase", "expired", "plaintext", "block", "missing-block"})
    void invalidOrMisboundProofCannotAdvanceTheFlow(String failure) throws Exception {
        Map<String, Object> started = start(false);
        Map<String, String> proof = new HashMap<>(proof(started));
        String respondingClient = client.getClientId();
        String challenge = "PASSWORD_VERIFIER";
        switch (failure) {
            case "signature" -> proof.put("PASSWORD_CLAIM_SIGNATURE", Base64.getEncoder().encodeToString(new byte[32]));
            case "username" -> proof.put("USERNAME", "someone-else");
            case "client" -> respondingClient = service.createUserPoolClient(pool.getId(), "other", false, false,
                    List.of(), List.of()).getClientId();
            case "phase" -> { challenge = "CUSTOM_CHALLENGE"; proof.put("ANSWER", "blue"); }
            case "expired" -> clock.advance(Duration.ofMinutes(4));
            case "plaintext" -> proof.put("PASSWORD_CLAIM_SIGNATURE", PASSWORD);
            case "block" -> proof.put("PASSWORD_CLAIM_SECRET_BLOCK", "substituted");
            case "missing-block" -> proof.remove("PASSWORD_CLAIM_SECRET_BLOCK");
            default -> fail("Unknown case");
        }
        String targetClient = respondingClient;
        String targetChallenge = challenge;
        assertDenied(() -> respond(false, targetClient, targetChallenge, started, proof));
        assertEquals(1, histories.size());
        verify(lambda, never()).invoke(anyString(), eq(VERIFY), any(byte[].class), any());
    }

    @Test
    void srpSessionCannotBeAnsweredWithAPlainPassword() {
        Map<String, Object> started = start(false);
        AwsException error = assertThrows(AwsException.class, () -> respond(false, client.getClientId(),
                "PASSWORD_VERIFIER", started, Map.of("USERNAME", USERNAME, "ANSWER", PASSWORD)));
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(1, histories.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "N", "not-hex", "-1"})
    void invalidPublicValueIsRejectedBeforeStartingTheCustomSequence(String value) {
        String publicValue = "N".equals(value) ? CognitoSrpHelper.N.toString(16) : value;
        AwsException error = assertThrows(AwsException.class, () -> service.initiateAuth(client.getClientId(),
                "CUSTOM_AUTH", Map.of("USERNAME", USERNAME, "CHALLENGE_NAME", "SRP_A", "SRP_A", publicValue)));
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertTrue(histories.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"phase", "expired", "consumed", "unknown"})
    void passwordChangeCannotBypassTheCustomSequence(String failure) throws Exception {
        Map<String, Object> started = start(false);
        if ("expired".equals(failure)) {
            clock.advance(Duration.ofMinutes(4));
        } else if ("consumed".equals(failure)) {
            respond(false, client.getClientId(), "PASSWORD_VERIFIER", started, proof(started));
        } else if ("unknown".equals(failure)) {
            started = Map.of("Session", "unknown-session");
        }
        Map<String, Object> previous = started;
        assertDenied(() -> respond(false, client.getClientId(), "NEW_PASSWORD_REQUIRED", previous,
                Map.of("USERNAME", USERNAME, "NEW_PASSWORD", "Bypass1234!")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "client", "expired"})
    void passwordChangeIsBoundToTheVerifiedCustomUserAndClient(String failure) throws Exception {
        temporaryPassword = true;
        service.adminSetUserPassword(pool.getId(), USERNAME, PASSWORD, false);
        Map<String, Object> started = start(false);
        Map<String, Object> passwordChallenge = respond(false, client.getClientId(), "PASSWORD_VERIFIER", started, proof(started));
        String originalHash = service.adminGetUser(pool.getId(), USERNAME).getPasswordHash();
        String target = "client".equals(failure) ? service.createUserPoolClient(pool.getId(), "other", false, false,
                List.of(), List.of()).getClientId() : client.getClientId();
        if ("expired".equals(failure)) {
            clock.advance(Duration.ofMinutes(4));
        }
        Map<String, String> response = Map.of("USERNAME", "username".equals(failure) ? "someone-else" : USERNAME,
                "NEW_PASSWORD", "Bypass1234!");
        assertDenied(() -> respond(false, target, "NEW_PASSWORD_REQUIRED", passwordChallenge, response));
        assertEquals(originalHash, service.adminGetUser(pool.getId(), USERNAME).getPasswordHash());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitlyRequestedSrpRequiresThePublicValue(boolean admin) {
        Map<String, String> params = Map.of("USERNAME", USERNAME, "CHALLENGE_NAME", "SRP_A");
        AwsException error = assertThrows(AwsException.class, () -> {
            if (admin) {
                service.adminInitiateAuth(pool.getId(), client.getClientId(), "CUSTOM_AUTH", params, Map.of());
            } else {
                service.initiateAuth(client.getClientId(), "CUSTOM_AUTH", params);
            }
        });
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertTrue(histories.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"false,missing", "false,weak", "true,missing", "true,weak"})
    void rejectedNewPasswordCanBeCorrectedOnTheSameSession(boolean custom, String invalid) throws Exception {
        temporaryPassword = true;
        service.adminSetUserPassword(pool.getId(), USERNAME, PASSWORD, false);
        Map<String, Object> challenge;
        if (custom) {
            Map<String, Object> started = start(false);
            challenge = respond(false, client.getClientId(), "PASSWORD_VERIFIER", started, proof(started));
        } else {
            client.setExplicitAuthFlows(List.of("ALLOW_USER_PASSWORD_AUTH"));
            challenge = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                    Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD));
        }
        Map<String, String> bad = new HashMap<>(Map.of("USERNAME", USERNAME));
        if ("weak".equals(invalid)) {
            bad.put("NEW_PASSWORD", "x");
        }
        assertThrows(AwsException.class, () -> respond(false, client.getClientId(), "NEW_PASSWORD_REQUIRED", challenge, bad));
        Map<String, Object> next = respond(false, client.getClientId(), "NEW_PASSWORD_REQUIRED", challenge,
                Map.of("USERNAME", USERNAME, "NEW_PASSWORD", "Corrected1234!"));
        if (custom) {
            assertEquals("CUSTOM_CHALLENGE", next.get("ChallengeName"));
            assertFalse(next.containsKey("AuthenticationResult"));
        } else {
            assertNotNull(next.get("AuthenticationResult"));
        }
    }

    @Test
    void rejectedCustomResponseDoesNotReplaceClientMetadata() throws Exception {
        Map<String, Object> started = start(false);
        Map<String, Object> next = service.respondToAuthChallenge(client.getClientId(), "PASSWORD_VERIFIER",
                (String) started.get("Session"), proof(started), Map.of("trace", "original"));
        assertDenied(() -> service.respondToAuthChallenge(client.getClientId(), "CUSTOM_CHALLENGE",
                (String) next.get("Session"), Map.of("USERNAME", "someone-else", "ANSWER", "blue"),
                Map.of("trace", "rejected")));
        respond(false, client.getClientId(), "CUSTOM_CHALLENGE", next, Map.of("USERNAME", USERNAME, "ANSWER", "blue"));
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambda).invoke(anyString(), eq(VERIFY), payload.capture(), any());
        assertEquals("original", MAPPER.readTree(payload.getValue()).path("request").path("clientMetadata").path("trace").asText());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attributeFailureAfterPasswordChangeDoesNotLeaveTheSessionReusable(boolean custom) throws Exception {
        temporaryPassword = true;
        pool.setUsernameAttributes(List.of("email"));
        service.adminCreateUser(pool.getId(), "occupied@example.com", Map.of("email", "occupied@example.com"), null);
        service.adminSetUserPassword(pool.getId(), USERNAME, PASSWORD, false);
        Map<String, Object> challenge;
        if (custom) {
            Map<String, Object> started = start(false);
            challenge = respond(false, client.getClientId(), "PASSWORD_VERIFIER", started, proof(started));
        } else {
            client.setExplicitAuthFlows(List.of("ALLOW_USER_PASSWORD_AUTH"));
            challenge = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                    Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD));
        }
        String before = service.adminGetUser(pool.getId(), USERNAME).getPasswordHash();
        AwsException failure = assertThrows(AwsException.class, () -> respond(false, client.getClientId(),
                "NEW_PASSWORD_REQUIRED", challenge, Map.of("USERNAME", USERNAME, "NEW_PASSWORD", "Changed1234!",
                        "userAttributes.email", "occupied@example.com")));
        assertEquals("AliasExistsException", failure.getErrorCode());
        assertNotEquals(before, service.adminGetUser(pool.getId(), USERNAME).getPasswordHash());
        assertDenied(() -> respond(false, client.getClientId(), "NEW_PASSWORD_REQUIRED", challenge,
                Map.of("USERNAME", USERNAME, "NEW_PASSWORD", "Reused1234!")));
    }

    @Test
    void simultaneousProofResponsesAdvanceOnlyOnce() throws Exception {
        Map<String, Object> started = start(false);
        Map<String, String> proof = proof(started);
        CountDownLatch defining = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(lambda.invoke(anyString(), eq(DEFINE), any(byte[].class), any())).thenAnswer(call -> {
            defining.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            return ok(Map.of("challengeName", "CUSTOM_CHALLENGE"));
        });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Map<String, Object>> first = executor.submit(() -> respond(false, client.getClientId(),
                    "PASSWORD_VERIFIER", started, proof));
            try {
                assertTrue(defining.await(5, TimeUnit.SECONDS));
                assertDenied(() -> respond(false, client.getClientId(), "PASSWORD_VERIFIER", started, proof));
            } finally {
                finish.countDown();
            }
            assertEquals("CUSTOM_CHALLENGE", first.get(5, TimeUnit.SECONDS).get("ChallengeName"));
        }
        verify(lambda, times(2)).invoke(anyString(), eq(DEFINE), any(byte[].class), any());
    }

    private Map<String, Object> start(boolean admin) {
        Map<String, String> params = Map.of("USERNAME", USERNAME, "CHALLENGE_NAME", "SRP_A",
                "SRP_A", CognitoSrpTestClient.publicA());
        Map<String, Object> started = admin
                ? service.adminInitiateAuth(pool.getId(), client.getClientId(), "CUSTOM_AUTH", params, Map.of())
                : service.initiateAuth(client.getClientId(), "CUSTOM_AUTH", params);
        assertEquals("PASSWORD_VERIFIER", started.get("ChallengeName"));
        assertFalse(started.containsKey("AuthenticationResult"));
        Map<?, ?> challenge = (Map<?, ?>) started.get("ChallengeParameters");
        for (String member : List.of("USER_ID_FOR_SRP", "USERNAME", "SALT", "SRP_B", "SECRET_BLOCK")) {
            assertNotNull(challenge.get(member));
        }
        return started;
    }

    private Map<String, Object> respond(boolean admin, String clientId, String challenge,
                                         Map<String, Object> previous, Map<String, String> responses) {
        String session = (String) previous.get("Session");
        return admin ? service.adminRespondToAuthChallenge(pool.getId(), clientId, challenge, session, responses, Map.of())
                : service.respondToAuthChallenge(clientId, challenge, session, responses);
    }

    private static void assertDenied(Runnable operation) {
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class, operation::run).getErrorCode());
    }

    private static InvokeResult ok(Map<String, Object> response) throws Exception {
        return new InvokeResult(200, null, MAPPER.writeValueAsBytes(Map.of("response", response)), null, "request");
    }

    private Map<String, String> proof(Map<String, Object> started) throws Exception {
        return CognitoSrpTestClient.proof(pool.getId(), USERNAME, PASSWORD, (Map<?, ?>) started.get("ChallengeParameters"));
    }
}
