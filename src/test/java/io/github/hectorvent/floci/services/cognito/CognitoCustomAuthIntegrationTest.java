package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class CognitoCustomAuthIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customAuthWithoutCreateSupportsMultipleRounds(boolean admin) throws Exception {
        String id = UUID.randomUUID().toString();
        String define = "arn:aws:lambda:us-east-1:000000000000:function:define-" + id;
        String verify = "arn:aws:lambda:us-east-1:000000000000:function:verify-" + id;
        String poolId = cognitoJson("CreateUserPool", MAPPER.writeValueAsString(Map.of(
                "PoolName", "custom-auth-" + id,
                "LambdaConfig", Map.of("DefineAuthChallenge", define, "VerifyAuthChallengeResponse", verify))))
                .path("UserPool").path("Id").asText();
        try {
            String clientId = cognitoJson("CreateUserPoolClient", MAPPER.writeValueAsString(Map.of(
                    "UserPoolId", poolId, "ClientName", "app", "ExplicitAuthFlows", List.of("ALLOW_CUSTOM_AUTH"))))
                    .path("UserPoolClient").path("ClientId").asText();
            cognitoAction("AdminCreateUser", MAPPER.writeValueAsString(Map.of(
                    "UserPoolId", poolId, "Username", "alice", "MessageAction", "SUPPRESS")))
                    .then().statusCode(200);
            cognitoAction("AdminSetUserPassword", MAPPER.writeValueAsString(Map.of(
                    "UserPoolId", poolId, "Username", "alice", "Password", "Perm1234!", "Permanent", true)))
                    .then().statusCode(200);
            when(lambdaService.invoke(anyString(), eq(define), any(byte[].class), eq(InvocationType.RequestResponse)))
                    .thenReturn(new InvokeResult(200, null, MAPPER.writeValueAsBytes(Map.of(
                            "response", Map.of("challengeName", "CUSTOM_CHALLENGE"))), null, "request"))
                    .thenReturn(new InvokeResult(200, null, MAPPER.writeValueAsBytes(Map.of(
                            "response", Map.of("challengeName", "CUSTOM_CHALLENGE"))), null, "request"))
                    .thenReturn(new InvokeResult(200, null, MAPPER.writeValueAsBytes(Map.of(
                            "response", Map.of("issueTokens", true))), null, "request"));
            when(lambdaService.invoke(anyString(), eq(verify), any(byte[].class), eq(InvocationType.RequestResponse)))
                    .thenReturn(new InvokeResult(200, null, MAPPER.writeValueAsBytes(Map.of(
                            "response", Map.of("answerCorrect", true))), null, "request"));

            Map<String, Object> request = new HashMap<>(Map.of(
                    "ClientId", clientId, "AuthFlow", "CUSTOM_AUTH", "AuthParameters", Map.of("USERNAME", "alice")));
            if (admin) {
                request.put("UserPoolId", poolId);
            }
            JsonNode started = cognitoJson(admin ? "AdminInitiateAuth" : "InitiateAuth",
                    MAPPER.writeValueAsString(request));
            assertEquals("CUSTOM_CHALLENGE", started.path("ChallengeName").asText());
            assertEquals(MAPPER.valueToTree(Map.of("USERNAME", "alice")), started.path("ChallengeParameters"));
            String session = started.path("Session").asText();
            assertFalse(session.isBlank());
            assertFalse(started.has("AuthenticationResult"));
            request.remove("AuthFlow");
            request.remove("AuthParameters");
            request.put("ChallengeName", "CUSTOM_CHALLENGE");
            request.put("ChallengeResponses", Map.of("USERNAME", "alice", "ANSWER", "blue"));
            request.put("Session", session);
            String action = admin ? "AdminRespondToAuthChallenge" : "RespondToAuthChallenge";
            JsonNode next = cognitoJson(action, MAPPER.writeValueAsString(request));
            assertEquals("CUSTOM_CHALLENGE", next.path("ChallengeName").asText());
            assertFalse(next.has("AuthenticationResult"));
            assertEquals(started.path("ChallengeParameters"), next.path("ChallengeParameters"));
            assertFalse(next.path("Session").asText().isBlank());
            assertNotEquals(session, next.path("Session").asText());
            cognitoAction(action, MAPPER.writeValueAsString(request)).then().statusCode(400)
                    .body("__type", endsWith("NotAuthorizedException"));
            request.put("Session", next.path("Session").asText());
            JsonNode completed = cognitoJson(action, MAPPER.writeValueAsString(request));
            assertFalse(completed.path("AuthenticationResult").path("AccessToken").asText().isBlank());

            ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
            verify(lambdaService, times(3)).invoke(anyString(), eq(define), payloads.capture(),
                    eq(InvocationType.RequestResponse));
            for (int round = 0; round < 3; round++) {
                JsonNode history = MAPPER.readTree(payloads.getAllValues().get(round)).path("request").path("session");
                assertEquals(round, history.size());
                for (JsonNode entry : history) {
                    assertEquals("CUSTOM_CHALLENGE", entry.path("challengeName").asText());
                    assertTrue(entry.path("challengeResult").asBoolean());
                }
            }
            ArgumentCaptor<byte[]> answers = ArgumentCaptor.forClass(byte[].class);
            verify(lambdaService, times(2)).invoke(anyString(), eq(verify), answers.capture(),
                    eq(InvocationType.RequestResponse));
            for (byte[] payload : answers.getAllValues()) {
                JsonNode triggerRequest = MAPPER.readTree(payload).path("request");
                assertEquals("blue", triggerRequest.path("challengeAnswer").asText());
                assertEquals(MAPPER.createObjectNode(), triggerRequest.path("privateChallengeParameters"));
            }
            verifyNoMoreInteractions(lambdaService);
        } finally {
            cognitoAction("DeleteUserPool", MAPPER.writeValueAsString(Map.of("UserPoolId", poolId)))
                    .then().statusCode(200);
        }
    }
}
