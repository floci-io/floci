package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigateway.AuthorizerPolicyEvaluator;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.AuthorizerPolicyFixtures;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketAuthorizerPolicyTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.hectorvent.floci.services.apigatewayv2.AuthorizerPolicyFixtures#policies")
    void evaluatesAllStatementsAgainstConnectArn(String scenario, String statements, int expectedStatus) throws Exception {
        assertPolicy(statements, expectedStatus, "127.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.42", "2001:db8::42"})
    void sourceIpConditionsUseConnectionAddress(String sourceIp) throws Exception {
        String statements = "[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"*\",\"Resource\":\"*\","
                + "\"Condition\":{\"IpAddress\":{\"aws:SourceIp\":\"" + sourceIp + "\"}}}]";
        assertPolicy(statements, 403, sourceIp);
    }

    @ParameterizedTest
    @CsvSource({
            "2001:db8::42, 403",
            "2001:db9::42, 200",
            "203.0.113.42, 200"
    })
    void ipv6CidrDenyOverridesAllow(String sourceIp, int expectedStatus) throws Exception {
        String statements = """
                [{"Effect":"Allow","Action":"*","Resource":"*"},
                 {"Effect":"Deny","Action":"*","Resource":"*",
                  "Condition":{"IpAddress":{"aws:SourceIp":"2001:db8::/32"}}}]
                """;
        assertPolicy(statements, expectedStatus, sourceIp);
    }

    @ParameterizedTest
    @CsvSource({"true, 200", "false, 403"})
    void secureTransportConditionUsesHandshakeScheme(boolean secureTransport, int expectedStatus) throws Exception {
        String statements = """
                [{"Effect":"Allow","Action":"*","Resource":"*",
                  "Condition":{"Bool":{"aws:SecureTransport":"true"}}}]
                """;
        assertPolicy(statements, expectedStatus, "127.0.0.1", secureTransport);
    }

    private void assertPolicy(String statements, int expectedStatus, String sourceIp) throws Exception {
        assertPolicy(statements, expectedStatus, sourceIp, false);
    }

    private void assertPolicy(String statements, int expectedStatus, String sourceIp, boolean secureTransport)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ApiGatewayV2Service gateway = mock(ApiGatewayV2Service.class);
        LambdaService lambda = mock(LambdaService.class);
        Authorizer authorizer = new Authorizer();
        authorizer.setAuthorizerType("REQUEST");
        authorizer.setAuthorizerUri("arn:aws:lambda:us-east-1:000000000000:function:authorizer");
        when(gateway.getAuthorizer("us-east-1", "api", "auth")).thenReturn(authorizer);
        WebSocketProxyEventBuilder builder = new WebSocketProxyEventBuilder();
        builder.objectMapper = mapper;
        builder.regionResolver = new RegionResolver("us-east-1", "000000000000");
        when(lambda.invoke(eq("us-east-1"), eq("authorizer"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = mapper.readTree((byte[]) invocation.getArgument(2));
                    String arn = event.path("methodArn").asText();
                    assertEquals("arn:aws:execute-api:us-east-1:000000000000:api/test/$connect", arn);
                    String rendered = AuthorizerPolicyFixtures.renderStatements(statements, arn);
                    String payload = "{\"principalId\":\"user\",\"policyDocument\":{\"Version\":\"2012-10-17\","
                            + "\"Statement\":" + rendered + "},\"context\":{\"tenant\":7}}";
                    return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "request");
                });
        WebSocketAuthorizerService service = new WebSocketAuthorizerService(gateway, lambda, builder, mapper,
                new AuthorizerPolicyEvaluator(new IamPolicyEvaluator(mapper)));
        WebSocketAuthorizerService.AuthorizerResult result = service.invokeAndEvaluate("us-east-1", "api", "test", "auth",
                "connection", 0, Map.of(), Map.of(), sourceIp, secureTransport, "agent", Map.of());
        assertEquals(expectedStatus, result.statusCode());
        assertEquals(expectedStatus == 200, result.allowed());
        if (result.allowed()) {
            assertEquals(Map.of("tenant", 7), result.context());
        } else {
            assertNull(result.context());
        }
    }
}
