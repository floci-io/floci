package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.services.apigatewayv2.LambdaAuthorizerApiFixtures.AUTHORIZER;
import static io.github.hectorvent.floci.services.apigatewayv2.LambdaAuthorizerApiFixtures.BACKEND;
import static io.github.hectorvent.floci.services.apigatewayv2.LambdaAuthorizerApiFixtures.result;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@QuarkusTest
class HttpApiAuthorizerIdentitySourceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @InjectMock
    LambdaService lambdaService;

    private final AtomicReference<JsonNode> authorizerEvent = new AtomicReference<>();
    private String apiId;
    private String stageName = "test";
    private String payloadFormat = "2.0";
    private boolean allowed = true;

    @BeforeEach
    void stubLambdaBoundary() {
        LambdaAuthorizerApiFixtures.stubAuthorizer(lambdaService, event -> {
            authorizerEvent.set(event);
            if ("1.0".equals(payloadFormat)) {
                return result("{\"principalId\":\"user\",\"policyDocument\":{\"Version\":\"2012-10-17\","
                        + "\"Statement\":[{\"Action\":\"execute-api:Invoke\",\"Effect\":\""
                        + (allowed ? "Allow" : "Deny") + "\",\"Resource\":\""
                        + event.path("methodArn").asText() + "\"}]}}");
            }
            return result("{\"isAuthorized\":" + allowed + "}");
        });
        LambdaAuthorizerApiFixtures.stubBackend(lambdaService);
    }

    @AfterEach
    void deleteApi() {
        if (apiId != null) {
            given().delete("/v2/apis/" + apiId).then().statusCode(204);
        }
    }

    @Test
    void headerIdentityIsAnArrayAndKeepsTheBearerPrefix() {
        configureApi(List.of("$request.header.authorization"));
        given().header("Authorization", "Bearer opaque-token").get(path()).then().statusCode(200);
        assertIdentities(List.of("Bearer opaque-token"));
    }

    @Test
    void multipleSourcesKeepConfiguredOrderAndResolveContextAndStageValues() {
        configureApi(List.of("$context.routeKey", "$stageVariables.tenant", "$request.querystring.Token",
                "$request.header.X-Identity", "$context.stage", "$context.requestId"));
        given().header("x-identity", "header-value").queryParam("Token", "query-value")
                .get(path()).then().statusCode(200);
        JsonNode event = authorizerEvent.get();
        assertNotNull(event);
        assertIdentities(List.of("GET /items", "stage-tenant", "query-value", "header-value", "test",
                event.path("requestContext").path("requestId").asText()));
    }

    @Test
    void contextAliasesUseTheValuesFromThisEvent() {
        configureApi(List.of("$context.httpMethod", "$context.protocol", "$context.identity.sourceIp",
                "$context.identity.userAgent", "$context.requestTime", "$context.requestTimeEpoch", "$context.extendedRequestId"));
        given().header("User-Agent", "identity-client").get(path()).then().statusCode(200);
        JsonNode context = authorizerEvent.get().path("requestContext");
        assertIdentities(List.of("GET", "HTTP/1.1", context.path("http").path("sourceIp").asText(),
                "identity-client", context.path("time").asText(), context.path("timeEpoch").asText(),
                context.path("requestId").asText()));
    }

    @ParameterizedTest
    @CsvSource({"1.0,test", "1.0,$default", "2.0,test", "2.0,$default"})
    void contextPathIncludesOnlyANamedStage(String format, String stage) {
        payloadFormat = format;
        stageName = stage;
        configureApi(List.of("$context.path"));
        given().get(path()).then().statusCode(200);
        assertIdentities(List.of("$default".equals(stage) ? "/items" : "/test/items"));
    }

    @Test
    void repeatedRequestValuesAreJoinedLikeTheEventFields() {
        configureApi(List.of("$request.querystring.Token", "$request.header.X-Identity", "$request.querystring.Token"));
        given().header("X-Identity", "first", "second").queryParam("Token", "one", "two")
                .get(path()).then().statusCode(200);
        assertIdentities(List.of("one,two", "first,second", "one,two"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2.0"})
    void noConfiguredSourcesProducesAnEmptyIdentityValue(String format) {
        payloadFormat = format;
        configureApi(List.of());
        given().get(path()).then().statusCode(200);
        assertIdentities(List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$request.header.Missing", "$request.querystring.Missing", "$request.querystring.Token",
            "$stageVariables.missing", "$context.missing", "$context.http"})
    void missingOrWrongCaseRequestSourceDoesNotInvokeLambda(String source) {
        configureApi(List.of(source));
        given().queryParam("token", "wrong-case").get(path()).then().statusCode(401);
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(AUTHORIZER), any(byte[].class), eq(InvocationType.RequestResponse));
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(BACKEND), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void emptyConfiguredValueDoesNotInvokeLambda() {
        configureApi(List.of("$request.querystring.Token"));
        given().queryParam("Token", "").get(path()).then().statusCode(401);
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(AUTHORIZER), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void formatOneResolvesSourcesAndRendersCommaSeparatedIdentityFields() {
        payloadFormat = "1.0";
        configureApi(List.of("$request.header.authorization", "$request.querystring.Token",
                "$stageVariables.tenant", "$context.routeKey", "$context.stage", "$context.requestId"));
        given().header("Authorization", "Bearer identity").queryParam("Token", "query-value")
                .get(path()).then().statusCode(200);
        String expected = "Bearer identity,query-value,stage-tenant,GET /items,test,"
                + authorizerEvent.get().path("requestContext").path("requestId").asText();
        assertEquals(expected, authorizerEvent.get().path("identitySource").asText());
        assertEquals(expected, authorizerEvent.get().path("authorizationToken").asText());
    }

    @Test
    void formatOneContextAliasesUseTheValuesFromThisEvent() {
        payloadFormat = "1.0";
        configureApi(List.of("$context.httpMethod", "$context.protocol", "$context.identity.sourceIp",
                "$context.identity.userAgent", "$context.requestTime", "$context.requestTimeEpoch", "$context.extendedRequestId"));
        given().header("User-Agent", "identity-client").get(path()).then().statusCode(200);
        JsonNode context = authorizerEvent.get().path("requestContext");
        assertEquals(String.join(",", "GET", "HTTP/1.1", context.path("identity").path("sourceIp").asText(),
                "identity-client", context.path("requestTime").asText(), context.path("requestTimeEpoch").asText(),
                context.path("requestId").asText()), authorizerEvent.get().path("identitySource").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$request.header.Missing", "$request.querystring.Missing", "$request.querystring.Token",
            "$stageVariables.missing", "$context.missing", "$context.identity"})
    void formatOneMissingOrWrongCaseSourceDoesNotInvokeLambda(String source) {
        payloadFormat = "1.0";
        configureApi(List.of(source));
        given().queryParam("token", "wrong-case").get(path()).then().statusCode(401);
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(AUTHORIZER), any(byte[].class), eq(InvocationType.RequestResponse));
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(BACKEND), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void formatOneRepeatedRequestValuesUseTheLastDeliveredValues() {
        payloadFormat = "1.0";
        configureApi(List.of("$request.querystring.Token", "$request.header.X-Identity", "$request.querystring.Token"));
        given().header("X-Identity", "first", "second").queryParam("Token", "one", "two")
                .get(path()).then().statusCode(200);
        assertIdentities(List.of("two", "second", "two"));
    }

    @Test
    void formatOneEmptyLastQueryValueDoesNotInvokeLambda() {
        payloadFormat = "1.0";
        configureApi(List.of("$request.querystring.Token"));
        given().queryParam("Token", "present", "").get(path()).then().statusCode(401);
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(AUTHORIZER), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2.0"})
    void anEarlierEmptyQueryValueDoesNotHideTheDeliveredValue(String format) {
        payloadFormat = format;
        configureApi(List.of("$request.querystring.Token"));
        given().queryParam("Token", "", "present").get(path()).then().statusCode(200);
        assertIdentities(List.of("1.0".equals(format) ? "present" : ",present"));
    }

    @Test
    void identityValuesDoNotBypassADenyResponse() {
        allowed = false;
        configureApi(List.of("$request.header.Authorization"));
        given().header("Authorization", "Bearer denied-token").get(path()).then().statusCode(403);
        assertIdentities(List.of("Bearer denied-token"));
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(BACKEND), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    private void assertIdentities(List<String> expected) {
        assertNotNull(authorizerEvent.get());
        JsonNode identities = "1.0".equals(payloadFormat) ? MAPPER.valueToTree(String.join(",", expected)) : MAPPER.valueToTree(expected);
        assertEquals(identities, authorizerEvent.get().get("identitySource"));
        if ("1.0".equals(payloadFormat)) {
            assertEquals(identities, authorizerEvent.get().get("authorizationToken"));
        }
    }

    private String path() {
        return "/execute-api/" + apiId + "/" + stageName + "/items";
    }

    private void configureApi(List<String> sources) {
        apiId = LambdaAuthorizerApiFixtures.createApi("HTTP", stageName, Map.of("tenant", "stage-tenant"),
                Map.of("authorizerPayloadFormatVersion", payloadFormat, "enableSimpleResponses", "2.0".equals(payloadFormat),
                        "identitySource", sources));
    }
}
