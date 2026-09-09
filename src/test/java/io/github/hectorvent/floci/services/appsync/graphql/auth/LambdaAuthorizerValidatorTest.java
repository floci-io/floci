package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LambdaAuthorizerValidatorTest {

    @Mock
    LambdaService lambdaService;

    private final ObjectMapper mapper = new ObjectMapper();
    private LambdaAuthorizerValidator validator;
    private AuthRequestInfo info;
    private Map<String, Object> config;

    @BeforeEach
    void setUp() {
        validator = new LambdaAuthorizerValidator(lambdaService, new LambdaAuthorizerCache(), mapper);
        info = new AuthRequestInfo("{ hello }", "GetHello", Map.of("id", "1"),
                List.of(), "req-1", "000000000000", "us-east-1", Map.of("authorization", "tok"));
        config = Map.of(
                "authorizerUri", "arn:aws:lambda:us-east-1:000000000000:function:authz",
                "authorizerResultTtlInSeconds", 30);
    }

    @Test
    void authorizedWithFlatResolverContext() throws Exception {
        when(lambdaService.invoke(eq("us-east-1"), eq("arn:aws:lambda:us-east-1:000000000000:function:authz"),
                any(), eq(InvocationType.RequestResponse)))
                .thenReturn(invoke("{\"isAuthorized\":true,\"resolverContext\":{\"apple\":\"green\"}}"));

        LambdaAuthorizerResult result = validator.authorize("api-1", "tok", config, info);

        assertTrue(result.authorized());
        assertEquals("green", result.resolverContext().get("apple"));

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq("us-east-1"), any(), payload.capture(), eq(InvocationType.RequestResponse));
        String event = new String(payload.getValue(), StandardCharsets.UTF_8);
        assertTrue(event.contains("\"authorizationToken\":\"tok\""));
        assertTrue(event.contains("\"queryString\":\"{ hello }\""));
        assertFalse(event.contains("policyDocument"));
    }

    @Test
    void apiGatewayPolicyWithoutIsAuthorizedIs401() {
        when(lambdaService.invoke(any(), any(), any(), any()))
                .thenReturn(invoke("{\"principalId\":\"user\",\"policyDocument\":{\"Version\":\"2012-10-17\"}}"));
        assertThrows(AppSyncTransportException.class,
                () -> validator.authorize("api-1", "tok", config, info));
    }

    @Test
    void regexDenyDoesNotInvokeLambda() {
        Map<String, Object> withRegex = Map.of(
                "authorizerUri", "arn:aws:lambda:us-east-1:000000000000:function:authz",
                "identityValidationExpression", "^ok-.*");
        assertThrows(AppSyncTransportException.class,
                () -> validator.authorize("api-1", "bad-token", withRegex, info));
        verify(lambdaService, times(0)).invoke(any(), any(), any(), any());
    }

    @Test
    void nestedResolverContextKeysDropped() {
        when(lambdaService.invoke(any(), any(), any(), any()))
                .thenReturn(invoke("{\"isAuthorized\":true,\"resolverContext\":{\"apple\":\"green\",\"nested\":{\"x\":1}}}"));
        LambdaAuthorizerResult result = validator.authorize("api-1", "tok", config, info);
        assertEquals("green", result.resolverContext().get("apple"));
        assertFalse(result.resolverContext().containsKey("nested"));
    }

    @Test
    void emptyObjectIs401() {
        when(lambdaService.invoke(any(), any(), any(), any())).thenReturn(invoke("{}"));
        assertThrows(AppSyncTransportException.class,
                () -> validator.authorize("api-1", "tok", config, info));
    }

    @Test
    void cacheHitSkipsSecondInvoke() {
        when(lambdaService.invoke(any(), any(), any(), any()))
                .thenReturn(invoke("{\"isAuthorized\":true,\"resolverContext\":{\"k\":\"v\"}}"));
        validator.authorize("api-1", "tok", config, info);
        validator.authorize("api-1", "tok", config, info);
        verify(lambdaService, times(1)).invoke(any(), any(), any(), any());
    }

    @Test
    void ttlOverrideZeroInvokesTwice() {
        when(lambdaService.invoke(any(), any(), any(), any()))
                .thenReturn(invoke("{\"isAuthorized\":true,\"ttlOverride\":0}"));
        validator.authorize("api-1", "tok", config, info);
        validator.authorize("api-1", "tok", config, info);
        verify(lambdaService, times(2)).invoke(any(), any(), any(), any());
    }

    private static InvokeResult invoke(String json) {
        return new InvokeResult(200, null, json.getBytes(StandardCharsets.UTF_8), "", "id");
    }
}
