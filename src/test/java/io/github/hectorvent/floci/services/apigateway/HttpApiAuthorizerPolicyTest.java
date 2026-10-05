package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.AuthorizerPolicyFixtures;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Authorizer;
import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import io.github.hectorvent.floci.services.apigatewayv2.model.Route;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpApiAuthorizerPolicyTest {

    private ObjectMapper mapper;
    private ApiGatewayV2Service gateway;
    private LambdaService lambda;
    private HttpHeaders headers;
    private UriInfo uri;
    private CurrentVertxRequest currentVertxRequest;
    private SocketAddress remoteAddress;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        gateway = mock(ApiGatewayV2Service.class);
        lambda = mock(LambdaService.class);
        headers = mock(HttpHeaders.class);
        uri = mock(UriInfo.class);
        currentVertxRequest = mock(CurrentVertxRequest.class);
        RoutingContext routingContext = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpConnection connection = mock(HttpConnection.class);
        remoteAddress = mock(SocketAddress.class);
        when(currentVertxRequest.getCurrent()).thenReturn(routingContext);
        when(routingContext.request()).thenReturn(request);
        when(request.connection()).thenReturn(connection);
        when(connection.remoteAddress()).thenReturn(remoteAddress);
        when(remoteAddress.hostAddress()).thenReturn("127.0.0.1");
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/execute-api/api/test/items"));
        when(lambda.invoke(eq("us-east-1"), eq("backend"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = mapper.readTree((byte[]) invocation.getArgument(2));
                    assertEquals(7, event.at("/requestContext/authorizer/lambda/tenant/id").asInt());
                    return result("{\"statusCode\":200,\"body\":\"allowed\"}");
                });
    }

    static Stream<Arguments> httpPolicies() {
        return Stream.of("1.0", "2.0").flatMap(version -> AuthorizerPolicyFixtures.policies().map(policy -> {
            Object[] arguments = policy.get();
            return Arguments.of(version, arguments[0], arguments[1], arguments[2]);
        }));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("httpPolicies")
    void evaluatesAllStatementsAgainstRequestArn(String version, String scenario, String statements, int expectedStatus) {
        assertPolicy(version, statements, expectedStatus);
    }

    private void assertPolicy(String version, String statements, int expectedStatus) {
        when(lambda.invoke(eq("us-east-1"), eq("authorizer"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = mapper.readTree((byte[]) invocation.getArgument(2));
                    String arn = event.path("1.0".equals(version) ? "methodArn" : "routeArn").asText();
                    assertEquals("arn:aws:execute-api:us-east-1:000000000000:api/test/GET/items", arn);
                    String rendered = AuthorizerPolicyFixtures.renderStatements(statements, arn);
                    return result("{\"principalId\":\"user\",\"policyDocument\":{\"Version\":\"2012-10-17\","
                            + "\"Statement\":" + rendered + "},\"context\":{\"tenant\":{\"id\":7}}}");
                });
        ApiGatewayExecuteController controller = controller(version, false);
        try (Response response = controller.handleGet(headers, uri, "api", "test", "items")) {
            assertEquals(expectedStatus, response.getStatus(), "payload version " + version);
        }
        verify(lambda, times(expectedStatus == 200 ? 1 : 0))
                .invoke(eq("us-east-1"), eq("backend"), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.42", "2001:db8::42"})
    void sourceIpConditionsUseTransportAddress(String sourceIp) {
        when(remoteAddress.hostAddress()).thenReturn(sourceIp);
        when(headers.getHeaderString("X-Forwarded-For")).thenReturn("198.51.100.42");
        String statements = "[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"*\",\"Resource\":\"*\","
                + "\"Condition\":{\"IpAddress\":{\"aws:SourceIp\":\"" + sourceIp + "\"}}}]";
        assertPolicy("2.0", statements, 403);
    }

    @ParameterizedTest
    @CsvSource({
            "1.0, 2001:db8::42, 403",
            "2.0, 2001:db8::42, 403",
            "1.0, 2001:db9::42, 200",
            "2.0, 2001:db9::42, 200",
            "2.0, 203.0.113.42, 200"
    })
    void ipv6CidrDenyOverridesAllow(String version, String sourceIp, int expectedStatus) {
        when(remoteAddress.hostAddress()).thenReturn(sourceIp);
        String statements = """
                [{"Effect":"Allow","Action":"*","Resource":"*"},
                 {"Effect":"Deny","Action":"*","Resource":"*",
                  "Condition":{"IpAddress":{"aws:SourceIp":"2001:db8::/32"}}}]
                """;
        assertPolicy(version, statements, expectedStatus);
    }

    @ParameterizedTest
    @CsvSource({"https, 200", "http, 403"})
    void secureTransportConditionUsesRequestScheme(String scheme, int expectedStatus) {
        when(uri.getRequestUri()).thenReturn(URI.create(scheme + "://localhost/execute-api/api/test/items"));
        String statements = """
                [{"Effect":"Allow","Action":"*","Resource":"*",
                  "Condition":{"Bool":{"aws:SecureTransport":"true"}}}]
                """;
        assertPolicy("2.0", statements, expectedStatus);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void simpleResponsesUseIsAuthorized(boolean authorized) {
        when(lambda.invoke(eq("us-east-1"), eq("authorizer"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result("{\"isAuthorized\":" + authorized + ",\"context\":{\"tenant\":{\"id\":7}}}"));
        try (Response response = controller("2.0", true).handleGet(headers, uri, "api", "test", "items")) {
            assertEquals(authorized ? 200 : 403, response.getStatus());
        }
        verify(lambda, times(authorized ? 1 : 0))
                .invoke(eq("us-east-1"), eq("backend"), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    private ApiGatewayExecuteController controller(String version, boolean simpleResponses) {
        Authorizer authorizer = new Authorizer();
        authorizer.setAuthorizerType("REQUEST");
        authorizer.setAuthorizerUri("arn:aws:lambda:us-east-1:000000000000:function:authorizer");
        authorizer.setAuthorizerPayloadFormatVersion(version);
        authorizer.setEnableSimpleResponses(simpleResponses);
        Route route = new Route();
        route.setRouteKey("GET /items");
        route.setAuthorizationType("CUSTOM");
        route.setAuthorizerId("auth");
        route.setTarget("integrations/backend");
        Integration integration = new Integration();
        integration.setIntegrationType("AWS_PROXY");
        integration.setIntegrationUri("arn:aws:lambda:us-east-1:000000000000:function:backend");
        integration.setPayloadFormatVersion("2.0");
        when(gateway.getApi("us-east-1", "api")).thenReturn(new Api());
        when(gateway.findMatchingRoute("us-east-1", "api", "GET", "/items")).thenReturn(route);
        when(gateway.getAuthorizer("us-east-1", "api", "auth")).thenReturn(authorizer);
        when(gateway.getIntegration("us-east-1", "api", "backend")).thenReturn(integration);

        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext(currentVertxRequest);
        routeContext.routeToHttpApi("us-east-1");
        return new ApiGatewayExecuteController(null, null, gateway, lambda,
                new RegionResolver("us-east-1", "000000000000"), mapper, null, null, null, null, null,
                routeContext, null, null, null, null, new AuthorizerPolicyEvaluator(new IamPolicyEvaluator(mapper)));
    }

    private InvokeResult result(String payload) {
        return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "request");
    }
}
