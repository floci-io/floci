package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testutil.SesMailbox;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@QuarkusTest
class CognitoSignUpTriggerIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void customMessageAdminConfirmationIsReflectedInSignUpResponseOverHttp() throws Exception {
        String id = UUID.randomUUID().toString();
        String username = "trigger-" + id;
        String email = "original-" + id + "@example.com";
        String changedEmail = "changed-" + id + "@example.com";
        String function = "arn:aws:lambda:us-east-1:000000000000:function:custom-message-" + id;
        String poolId = cognitoJson("CreateUserPool", MAPPER.writeValueAsString(Map.of(
                "PoolName", "signup-message-trigger-" + id, "AutoVerifiedAttributes", List.of("email"),
                "LambdaConfig", Map.of("CustomMessage", function)))).path("UserPool").path("Id").asText();
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        try {
            String clientId = cognitoJson("CreateUserPoolClient", MAPPER.writeValueAsString(Map.of(
                    "UserPoolId", poolId, "ClientName", "app")))
                    .path("UserPoolClient").path("ClientId").asText();
            when(lambdaService.invoke(anyString(), eq(function), any(byte[].class),
                    eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                        Future<?> callback = null;
                        try {
                            JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                            assertEquals("CustomMessage_SignUp", event.path("triggerSource").asText());
                            assertEquals(username, event.path("userName").asText());
                            assertEquals(email, event.path("request").path("userAttributes").path("email").asText());
                            callback = callbackExecutor.submit(() -> {
                                String userRequest = MAPPER.writeValueAsString(Map.of(
                                        "UserPoolId", poolId, "Username", username));
                                cognitoAction("AdminConfirmSignUp", userRequest).then().statusCode(200);
                                cognitoAction("AdminUpdateUserAttributes", MAPPER.writeValueAsString(Map.of(
                                        "UserPoolId", poolId, "Username", username, "UserAttributes",
                                        List.of(Map.of("Name", "email", "Value", changedEmail)))))
                                        .then().statusCode(200);
                                return null;
                            });
                            callback.get(5, TimeUnit.SECONDS);
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            callbackFailure.set(failure);
                        } catch (Exception | AssertionError failure) {
                            callbackFailure.set(failure);
                        } finally {
                            if (callback != null) {
                                callback.cancel(true);
                            }
                        }
                        return response(Map.of());
                    });

            JsonNode signUp = cognitoJson("SignUp", MAPPER.writeValueAsString(Map.of("ClientId", clientId,
                    "Username", username, "Password", "Perm1234!", "UserAttributes",
                    List.of(Map.of("Name", "email", "Value", email)))));

            assertNull(callbackFailure.get(), () -> "CustomMessage HTTP callback failed before Lambda returned: "
                    + callbackFailure.get());
            cognitoAction("AdminGetUser", MAPPER.writeValueAsString(Map.of("UserPoolId", poolId,
                    "Username", username))).then().statusCode(200).body("UserStatus", equalTo("CONFIRMED"));
            assertEquals(1, SesMailbox.messagesTo(email).size(), "signup must send to the captured email address");
            assertTrue(SesMailbox.messagesTo(changedEmail).isEmpty(), "the callback's new email must not receive the code");
            assertTrue(signUp.path("UserConfirmed").asBoolean(),
                    "SignUp must report confirmation completed by its synchronous CustomMessage callback");
        } finally {
            callbackExecutor.shutdownNow();
            try {
                assertTrue(callbackExecutor.awaitTermination(5, TimeUnit.SECONDS), "HTTP callback executor must stop");
            } finally {
                cognitoAction("DeleteUserPool", MAPPER.writeValueAsString(Map.of("UserPoolId", poolId)))
                        .then().statusCode(200);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void postConfirmationCanAddTheConfirmedUserToAGroupOverHttp(boolean autoConfirm) throws Exception {
        String id = UUID.randomUUID().toString();
        String username = "trigger-" + id;
        String email = username + "@example.com";
        String groupName = "confirmed-" + id;
        String postFunction = "arn:aws:lambda:us-east-1:000000000000:function:post-confirm-" + id;
        String preFunction = "arn:aws:lambda:us-east-1:000000000000:function:pre-signup-" + id;
        Map<String, String> triggers = autoConfirm
                ? Map.of("PreSignUp", preFunction, "PostConfirmation", postFunction)
                : Map.of("PostConfirmation", postFunction);
        String poolId = cognitoJson("CreateUserPool", MAPPER.writeValueAsString(Map.of(
                "PoolName", "signup-trigger-" + id, "AutoVerifiedAttributes", List.of("email"),
                "LambdaConfig", triggers))).path("UserPool").path("Id").asText();
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        try {
            String clientId = cognitoJson("CreateUserPoolClient", MAPPER.writeValueAsString(Map.of(
                    "UserPoolId", poolId, "ClientName", "app")))
                    .path("UserPoolClient").path("ClientId").asText();
            cognitoAction("CreateGroup", MAPPER.writeValueAsString(Map.of(
                    "UserPoolId", poolId, "GroupName", groupName))).then().statusCode(200);
            if (autoConfirm) {
                when(lambdaService.invoke(anyString(), eq(preFunction), any(byte[].class),
                        eq(InvocationType.RequestResponse)))
                        .thenReturn(response(Map.of("autoConfirmUser", true)));
            }
            when(lambdaService.invoke(anyString(), eq(postFunction), any(byte[].class),
                    eq(InvocationType.RequestResponse))).thenAnswer(invocation -> {
                        Future<Integer> callback = null;
                        try {
                            JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                            assertEquals("PostConfirmation_ConfirmSignUp", event.path("triggerSource").asText());
                            assertEquals(username, event.path("userName").asText());
                            assertEquals(poolId, event.path("userPoolId").asText());
                            if (!autoConfirm) {
                                assertEquals("http-confirm", event.path("request").path("clientMetadata")
                                        .path("source").asText());
                            }
                            callback = callbackExecutor.submit(() -> cognitoAction("AdminAddUserToGroup",
                                    MAPPER.writeValueAsString(Map.of("UserPoolId", poolId,
                                            "Username", username, "GroupName", groupName))).statusCode());
                            assertEquals(200, callback.get(5, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            callbackFailure.set(failure);
                        } catch (Exception | AssertionError failure) {
                            callbackFailure.set(failure);
                        } finally {
                            if (callback != null) {
                                callback.cancel(true);
                            }
                        }
                        return response(Map.of());
                    });

            cognitoAction("SignUp", MAPPER.writeValueAsString(Map.of("ClientId", clientId,
                    "Username", username, "Password", "Perm1234!", "UserAttributes",
                    List.of(Map.of("Name", "email", "Value", email))))).then().statusCode(200);
            if (!autoConfirm) {
                JsonNode messages = SesMailbox.messagesTo(email);
                assertEquals(1, messages.size());
                Matcher code = Pattern.compile("\\b(\\d{6})\\b")
                        .matcher(messages.get(0).path("Body").path("text_part").asText());
                assertTrue(code.find(), "signup email must contain the confirmation code");
                cognitoAction("ConfirmSignUp", MAPPER.writeValueAsString(Map.of("ClientId", clientId,
                        "Username", username, "ConfirmationCode", code.group(1),
                        "ClientMetadata", Map.of("source", "http-confirm")))).then().statusCode(200);
            }
            assertNull(callbackFailure.get(), () -> "PostConfirmation HTTP callback failed before Lambda returned: "
                    + callbackFailure.get());
            String userRequest = MAPPER.writeValueAsString(Map.of("UserPoolId", poolId, "Username", username));
            cognitoAction("AdminGetUser", userRequest).then().statusCode(200)
                    .body("UserStatus", equalTo("CONFIRMED"));
            cognitoAction("AdminListGroupsForUser", userRequest).then().statusCode(200)
                    .body("Groups.GroupName", hasItem(groupName));
        } finally {
            callbackExecutor.shutdownNow();
            try {
                assertTrue(callbackExecutor.awaitTermination(5, TimeUnit.SECONDS), "HTTP callback executor must stop");
            } finally {
                cognitoAction("DeleteUserPool", MAPPER.writeValueAsString(Map.of("UserPoolId", poolId)))
                        .then().statusCode(200);
            }
        }
    }

    private static InvokeResult response(Map<String, Object> response) throws Exception {
        return new InvokeResult(200, null, MAPPER.writeValueAsBytes(Map.of("response", response)), null, "request");
    }
}
