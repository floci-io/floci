package io.floci.aws.services.appsync.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.appsync.graphql.AppSyncTransportException;
import io.floci.aws.services.appsync.graphql.auth.LambdaAuthorizerCache;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventsLambdaAuthorizerTest {

    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:111122223333:function:my-authorizer";
    private static final Map<String, Object> CONFIG = Map.of("authorizerUri", FUNCTION_ARN,
            "authorizerResultTtlInSeconds", 300);

    private final LambdaService lambdaService = mock(LambdaService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventsLambdaAuthorizer authorizer =
            new EventsLambdaAuthorizer(lambdaService, new LambdaAuthorizerCache(), mapper);

    private static EventsLambdaAuthorizer.Request subscribe(String channel, String token) {
        return new EventsLambdaAuthorizer.Request("api-1", "111122223333", "us-east-1",
                EventsOperation.EVENT_SUBSCRIBE, "appservices-v7-6-0", channel, token, Map.of("host", "h"));
    }

    private void answers(String json) {
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, json.getBytes(StandardCharsets.UTF_8), null, "r-1"));
    }

    /** The function receives the token, operation and channel exactly as the client sent them. */
    @Test
    @SuppressWarnings("unchecked")
    void theFunctionReceivesTheTokenOperationAndChannelVerbatim() throws Exception {
        answers("{\"isAuthorized\": true, \"handlerContext\": {\"team\": \"t-1\", \"nested\": {\"x\": 1}}}");

        Map<String, Object> context = authorizer.authorize(
                subscribe("/appservices-v7-6-0/protocol/111122223333/run-1", "Bearer abc.def"), CONFIG);

        assertEquals(Map.of("team", "t-1"), context);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArn(eq(FUNCTION_ARN), payload.capture(), eq(InvocationType.RequestResponse));
        Map<String, Object> event = mapper.readValue(payload.getValue(), Map.class);
        assertEquals("Bearer abc.def", event.get("authorizationToken"));
        Map<String, Object> requestContext = (Map<String, Object>) event.get("requestContext");
        assertEquals("EVENT_SUBSCRIBE", requestContext.get("operation"));
        assertEquals("/appservices-v7-6-0/protocol/111122223333/run-1", requestContext.get("channel"));
        assertEquals("appservices-v7-6-0", requestContext.get("channelNamespaceName"));
        assertEquals("api-1", requestContext.get("apiId"));
        assertEquals("111122223333", requestContext.get("accountId"));
        assertEquals(Map.of("host", "h"), event.get("requestHeaders"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aConnectCarriesNullNamespaceAndChannel() throws Exception {
        answers("{\"isAuthorized\": true}");

        authorizer.authorize(new EventsLambdaAuthorizer.Request("api-1", "111122223333", "us-east-1",
                EventsOperation.EVENT_CONNECT, null, null, "token", Map.of()), CONFIG);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArn(eq(FUNCTION_ARN), payload.capture(), any());
        Map<String, Object> requestContext = (Map<String, Object>) mapper.readValue(payload.getValue(), Map.class)
                .get("requestContext");
        assertEquals("EVENT_CONNECT", requestContext.get("operation"));
        assertNull(requestContext.get("channel"));
        assertNull(requestContext.get("channelNamespaceName"));
    }

    @Test
    void aDenialIsUnauthorized() {
        answers("{\"isAuthorized\": false}");

        AppSyncTransportException denied = assertThrows(AppSyncTransportException.class,
                () -> authorizer.authorize(subscribe("/ns/a", "token"), CONFIG));

        assertEquals(401, denied.getHttpStatus());
        assertEquals("UnauthorizedException", denied.getErrorType());
    }

    @Test
    void anInvocationErrorOrUnreadableResultIsUnauthorized() {
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(), any()))
                .thenReturn(new InvokeResult(200, "Unhandled", "{}".getBytes(StandardCharsets.UTF_8), null, "r"));
        assertThrows(AppSyncTransportException.class, () -> authorizer.authorize(subscribe("/ns/a", "t"), CONFIG));

        answers("not json");
        assertThrows(AppSyncTransportException.class, () -> authorizer.authorize(subscribe("/ns/b", "t"), CONFIG));
    }

    /** A result is reused for the same operation, channel and token, and not for another channel. */
    @Test
    void aResultIsCachedPerOperationChannelAndToken() {
        answers("{\"isAuthorized\": true}");

        authorizer.authorize(subscribe("/ns/a", "token"), CONFIG);
        authorizer.authorize(subscribe("/ns/a", "token"), CONFIG);
        authorizer.authorize(subscribe("/ns/b", "token"), CONFIG);

        verify(lambdaService, times(2)).invokeArn(eq(FUNCTION_ARN), any(), any());
    }

    @Test
    void aZeroTtlOverrideIsNotCached() {
        answers("{\"isAuthorized\": true, \"ttlOverride\": 0}");

        authorizer.authorize(subscribe("/ns/a", "token"), CONFIG);
        authorizer.authorize(subscribe("/ns/a", "token"), CONFIG);

        verify(lambdaService, times(2)).invokeArn(eq(FUNCTION_ARN), any(), any());
    }

    @Test
    void aTokenFailingTheValidationExpressionNeverReachesTheFunction() {
        Map<String, Object> config = Map.of("authorizerUri", FUNCTION_ARN, "identityValidationExpression", "^ok-.*");

        assertThrows(AppSyncTransportException.class,
                () -> authorizer.authorize(subscribe("/ns/a", "bad-token"), config));

        verify(lambdaService, never()).invokeArn(anyString(), any(), any());
    }

    @Test
    void aFunctionNameIsInvokedInTheApisRegion() {
        when(lambdaService.invoke(eq("us-east-1"), eq("my-authorizer"), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, "{\"isAuthorized\":true}".getBytes(StandardCharsets.UTF_8),
                        null, "r"));

        authorizer.authorize(subscribe("/ns/a", "token"), Map.of("authorizerUri", "my-authorizer"));

        verify(lambdaService).invoke(eq("us-east-1"), eq("my-authorizer"), any(), any());
    }

    @Test
    void theOverrideWinsOverTheProviderTtl() {
        assertEquals(0, EventsLambdaAuthorizer.effectiveTtl(0, 300));
        assertEquals(10, EventsLambdaAuthorizer.effectiveTtl(10, 300));
        assertEquals(300, EventsLambdaAuthorizer.effectiveTtl(null, 300));
        assertEquals(0, EventsLambdaAuthorizer.effectiveTtl(-5, 300));
    }
}
