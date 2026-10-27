package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit coverage for API Gateway request-event construction and HTTP API v2 route matching. */
class ApiGatewayExecuteControllerTest {

    private static ApiGatewayExecuteController controller(ObjectMapper objectMapper) {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        return new ApiGatewayExecuteController(
                null, null, null, null,
                regionResolver, objectMapper, null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null, null, null);
    }

    @Test
    void capturesGreedyProxyMultiSegment() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "ANY /wallet/{proxy+}", "/wallet/users/123/orders");
        assertEquals("users/123/orders", p.get("proxy"));
    }

    @Test
    void capturesNonGreedyNamedParam() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "GET /users/{id}", "/users/42");
        assertEquals("42", p.get("id"));
    }

    @Test
    void capturesMultipleNamedParams() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "GET /users/{user}/orders/{order}", "/users/u-1/orders/o-2");
        assertEquals("u-1", p.get("user"));
        assertEquals("o-2", p.get("order"));
    }

    @Test
    void capturesNamedParamsContainingUnderscores() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "GET /api/v1/key-ids/{key_id}/jobs/{job_id}",
                "/api/v1/key-ids/key-123/jobs/job-456");
        assertEquals("key-123", p.get("key_id"));
        assertEquals("job-456", p.get("job_id"));
    }

    @Test
    void capturesNamedParamsContainingDigits() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "GET /items/{item1}", "/items/value-1");
        assertEquals("value-1", p.get("item1"));
    }

    @Test
    void capturesMixedGreedyAndNamedParams() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "ANY /users/{user}/files/{path+}", "/users/u-1/files/a/b/c");
        assertEquals("u-1", p.get("user"));
        assertEquals("a/b/c", p.get("path"));
    }

    @Test
    void noMatchReturnsEmptyMap() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "GET /users/{id}", "/orders/42");
        assertTrue(p.isEmpty());
    }

    @Test
    void nullRouteKeyReturnsEmptyMap() {
        assertTrue(ApiGatewayExecuteController.extractV2PathParams(null, "/x").isEmpty());
    }

    @Test
    void malformedRouteKeyReturnsEmptyMap() {
        // No method/path split — caller passed garbage.
        assertTrue(ApiGatewayExecuteController.extractV2PathParams("garbage", "/x").isEmpty());
    }

    @Test
    void repeatedCallsAgainstSameRouteAreStable() {
        // Second hit reuses the cached compiled Pattern; output must be
        // identical for the same inputs. Run hot to give the cache a chance
        // to be exercised across multiple invocations.
        String routeKey = "ANY /payments/{proxy+}";
        for (int i = 0; i < 100; i++) {
            Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                    routeKey, "/payments/spei/" + i);
            assertEquals("spei/" + i, p.get("proxy"));
        }
    }

    @Test
    void duplicateRequestHeaderUsesLastSingleValueAndPreservesAllMultiValues() {
        MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.add("X-Dup", "first");
        requestHeaders.add("X-Dup", "second");
        requestHeaders.add("X-Dup", "third");

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode event = objectMapper.createObjectNode();
        ApiGatewayExecuteController controller = controller(objectMapper);
        controller.putSingleValueHeaders(event, requestHeaders);
        controller.putMultiValueHeaders(event, requestHeaders);

        assertEquals("third", event.path("headers").path("X-Dup").asText());
        assertEquals(
                objectMapper.valueToTree(List.of("first", "second", "third")),
                event.path("multiValueHeaders").path("X-Dup"));
    }

    @Test
    void duplicateQueryParamUsesLastSingleValueAndPreservesAllMultiValues() {
        // Measured against a real REST API (us-west-2, Lambda proxy integration):
        // ?x=1&x=2&x=3 yields queryStringParameters {"x":"3"} and
        // multiValueQueryStringParameters {"x":["1","2","3"]}, matching how AWS
        // collapses duplicate request headers.
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        queryParams.add("x", "1");
        queryParams.add("x", "2");
        queryParams.add("x", "3");
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode event = objectMapper.createObjectNode();
        ApiGatewayExecuteController controller = controller(objectMapper);
        controller.putQueryStringParameters(event, uriInfo);
        controller.putMultiValueQueryStringParameters(event, uriInfo);

        assertEquals("3", event.path("queryStringParameters").path("x").asText());
        assertEquals(
                objectMapper.valueToTree(List.of("1", "2", "3")),
                event.path("multiValueQueryStringParameters").path("x"));
    }

    @Test
    void repeatedQueryParamEndingEmptyKeepsTheTrailingEmptyValue() {
        // ?x=1&x= yields {"x":""}: the last value wins even when it is empty.
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        queryParams.add("x", "1");
        queryParams.add("x", "");
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode event = objectMapper.createObjectNode();
        ApiGatewayExecuteController controller = controller(objectMapper);
        controller.putQueryStringParameters(event, uriInfo);

        assertEquals("", event.path("queryStringParameters").path("x").asText());
    }

    @Test
    void bracketedQueryParamNameSurvivesUnchanged() {
        // JSON:API style filter[status]=open reaches the integration with the brackets intact;
        // AWS does not rewrite or drop the name. Pinned so the parameter map stays a passthrough.
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        queryParams.add("filter[status]", "open");
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode event = objectMapper.createObjectNode();
        ApiGatewayExecuteController controller = controller(objectMapper);
        controller.putQueryStringParameters(event, uriInfo);
        controller.putMultiValueQueryStringParameters(event, uriInfo);

        assertEquals("open", event.path("queryStringParameters").path("filter[status]").asText());
        assertEquals(
                objectMapper.valueToTree(List.of("open")),
                event.path("multiValueQueryStringParameters").path("filter[status]"));
    }

    @Test
    void absentQueryStringYieldsExplicitNulls() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode event = objectMapper.createObjectNode();
        ApiGatewayExecuteController controller = controller(objectMapper);
        controller.putQueryStringParameters(event, uriInfo);
        controller.putMultiValueQueryStringParameters(event, uriInfo);

        assertTrue(event.path("queryStringParameters").isNull());
        assertTrue(event.path("multiValueQueryStringParameters").isNull());
    }

    @Test
    void convertsAuthorizerValuesToAwsVtlStringShape() {
        Map<String, Object> result = ApiGatewayExecuteController.vtlAuthorizerContext(
                "real-principal",
                Map.of(
                        "principalId", "context-principal",
                        "stringKey", "value",
                        "numberKey", 1,
                        "booleanKey", true));

        assertEquals(Map.of(
                "principalId", "real-principal",
                "stringKey", "value",
                "numberKey", "1",
                "booleanKey", "true"), result);
    }

    @Test
    void omitsEmptyAuthorizerVtlContext() {
        assertNull(ApiGatewayExecuteController.vtlAuthorizerContext(null, null));
        assertNull(ApiGatewayExecuteController.vtlAuthorizerContext(null, Map.of()));
    }

    // ── HTTP API Lambda proxy responses AWS cannot read ──────

    /**
     * Measured on HTTP APIs (eu-west-1, 2026-10-19), for payload formats 1.0 and 2.0: a 1.0 result
     * that is not an object with a statusCode, and a statusCode that is not a whole number from 100
     * to 599, are answered with 500 and the generic message, and so are a body that is an object or a
     * list, an isBase64Encoded that strconv.ParseBool would not read, and a body said to be base64
     * that does not decode; a statusCode given as text that spells one is taken, headers that are
     * not an object are left out, and a number or a boolean body is its text. A function that raises
     * is covered below.
     */
    @ParameterizedTest(name = "{0} payload {1}")
    @MethodSource("httpApiProxyResponses")
    void httpApiAnswersAProxyResponseAsAwsReadsIt(String result, String format, int status, String body) {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        try (Response response = controller.buildProxyResponse(proxyPayload(result), true, "2.0".equals(format))) {
            assertEquals(status, response.getStatus());
            assertEquals(body, bodyOf(response));
        }
    }

    static Stream<Arguments> httpApiProxyResponses() {
        String error = "{\"message\":\"Internal Server Error\"}";
        return Stream.of(
                Arguments.of("\"hello\"", "1.0", 500, error),
                Arguments.of("null", "1.0", 500, error),
                Arguments.of("5", "1.0", 500, error),
                Arguments.of("{\"statusCode\":\"abc\",\"body\":\"x\"}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":\"abc\",\"body\":\"x\"}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":99,\"body\":\"x\"}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":99,\"body\":\"x\"}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":600,\"body\":\"x\"}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":600,\"body\":\"x\"}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":201.5,\"body\":\"x\"}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":201.5,\"body\":\"x\"}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":\"201\",\"body\":\"x\"}", "1.0", 201, "x"),
                Arguments.of("{\"statusCode\":\"201\",\"body\":\"x\"}", "2.0", 201, "x"),
                Arguments.of("{\"statusCode\":201,\"headers\":\"x\",\"body\":\"x\"}", "1.0", 201, "x"),
                Arguments.of("{\"statusCode\":201,\"headers\":\"x\",\"body\":\"x\"}", "2.0", 201, "x"),
                Arguments.of("{\"statusCode\":200,\"body\":\"not base64!\",\"isBase64Encoded\":true}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":\"not base64!\",\"isBase64Encoded\":true}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":{\"a\":1}}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":{\"a\":1}}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":[\"a\"]}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":[\"a\"]}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"yes\"}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"yes\"}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"tRuE\"}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"tRuE\"}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":2}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":2}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":1.5}", "1.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":1.5}", "2.0", 500, error),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":true}", "1.0", 200, "hello"),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":true}", "2.0", 200, "hello"),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"True\"}", "1.0", 200, "hello"),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"True\"}", "2.0", 200, "hello"),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"TRUE\"}", "1.0", 200, "hello"),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"TRUE\"}", "2.0", 200, "hello"),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"t\"}", "1.0", 200, "hello"),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"t\"}", "2.0", 200, "hello"),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":1}", "1.0", 200, "hello"),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":1}", "2.0", 200, "hello"),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"F\"}", "1.0", 200, "aGVsbG8="),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":\"F\"}", "2.0", 200, "aGVsbG8="),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":0}", "1.0", 200, "aGVsbG8="),
                Arguments.of("{\"statusCode\":200,\"body\":\"aGVsbG8=\",\"isBase64Encoded\":0}", "2.0", 200, "aGVsbG8="),
                Arguments.of("{\"statusCode\":200,\"body\":5}", "1.0", 200, "5"),
                Arguments.of("{\"statusCode\":200,\"body\":5}", "2.0", 200, "5"),
                Arguments.of("{\"statusCode\":200,\"body\":true}", "1.0", 200, "true"),
                Arguments.of("{\"statusCode\":200,\"body\":true}", "2.0", 200, "true"),
                Arguments.of("null", "2.0", 200, "null"),
                Arguments.of("5", "2.0", 200, "5"));
    }

    // ── Lambda proxy response Content-Type header matching ──────

    private static InvokeResult proxyPayload(String payloadJson) {
        return new InvokeResult(200, null, payloadJson.getBytes(StandardCharsets.UTF_8), null, "req-1");
    }

    @Test
    void buildProxyResponseMatchesLowercaseContentType() {
        // The AWS Lambda Web Adapter sidecar (and many handlers) emit lowercase header names.
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":404,"headers":{"content-type":"application/problem+json"},"body":"{}"}
                """), false);
        assertEquals("application/problem+json", response.getMediaType().toString());
    }

    @Test
    void buildProxyResponseMatchesExactCaseContentType() {
        // The pre-existing exact-case path must keep working — this is a regression guard,
        // not a new behavior.
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"headers":{"Content-Type":"text/plain"},"body":"hi"}
                """), false);
        assertEquals("text/plain", response.getMediaType().toString());
    }

    @Test
    void buildProxyResponseMatchesMixedCaseContentType() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"headers":{"CONTENT-TYPE":"application/xml"},"body":"<a/>"}
                """), false);
        assertEquals("application/xml", response.getMediaType().toString());
    }

    @Test
    void buildProxyResponseDefaultsToJsonWhenNoContentTypeHeaderPresent() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"headers":{"X-Other":"value"},"body":"{}"}
                """), false);
        assertEquals("application/json", response.getMediaType().toString());
    }

    @ParameterizedTest(name = "payload format {0}")
    @ValueSource(strings = {"1.0", "2.0"})
    void buildProxyResponseAnswersTextPlainWhenAnHttpApiResultHasNoContentType(String format) {
        // Measured on HTTP APIs (eu-west-1, 2026-10-19): a result with a statusCode and a body but
        // no Content-Type header of its own is answered text/plain; charset=utf-8, in both payload
        // formats. A REST API keeps answering application/json.
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"headers":{"X-Other":"value"},"body":"hello"}
                """), true, "2.0".equals(format));
        assertEquals(MediaType.valueOf("text/plain; charset=utf-8"), response.getMediaType());
    }

    @Test
    void buildProxyResponseDefaultsToJsonWhenContentTypeIsJsonNull() {
        // A JSON-null Content-Type must fall back to the default, the same as a missing header —
        // not be coerced to the literal string "null" (an invalid media type).
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"headers":{"content-type":null},"body":"{}"}
                """), false);
        assertEquals("application/json", response.getMediaType().toString());
    }

    @Test
    void buildProxyResponseMatchesContentTypeInMultiValueHeaders() {
        // multiValueHeaders is a fully valid way to return any header, including Content-Type —
        // not just repeated ones — and must be scanned the same as headers.
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"multiValueHeaders":{"content-type":["application/xml"]},"body":"<a/>"}
                """), false);
        assertEquals("application/xml", response.getMediaType().toString());
    }

    @Test
    void buildProxyResponseMultiValueHeadersTakesPrecedenceOverHeaders() {
        // API Gateway merges headers and multiValueHeaders, with multiValueHeaders winning
        // on conflicts.
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,\
                "headers":{"Content-Type":"text/plain"},\
                "multiValueHeaders":{"Content-Type":["application/xml"]},\
                "body":"<a/>"}
                """), false);
        assertEquals("application/xml", response.getMediaType().toString());
    }

    // ── HTTP API (v2) region resolution for unsigned / non-SigV4 requests ──────
    //
    // The two tests below (unsignedRequestFindsV2ApiDeployedOutsideDefaultRegion and
    // signedV2RequestDoesNotConsultRegionFallback) exercise the v2 cross-region dispatch
    // behavior that #2054 introduced on `resolveApiRegion` — not this PR's change, which is
    // scoped to the v1 REST path's Authorization-header check below. They're kept here because
    // they're the only controller-level coverage of that v2 behavior; treat them as pinning
    // #2054, not as regression guards for this PR.

    @Test
    void unsignedRequestFindsV2ApiDeployedOutsideDefaultRegion() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        ApiGatewayV2Service apiGatewayV2Service = mock(ApiGatewayV2Service.class);
        ApiGatewayService apiGatewayService = mock(ApiGatewayService.class);
        HttpHeaders headers = mock(HttpHeaders.class);

        when(regionResolver.resolveRegion(headers)).thenReturn("us-east-1");
        // resolveRegionFromAuthOrNull is unstubbed and returns null by default, i.e. unresolved.
        // Not a v1 API at the default region...
        when(apiGatewayService.resolveRestApiRegion("us-east-1", "abc123")).thenReturn("us-east-1");
        when(apiGatewayService.getRestApi("us-east-1", "abc123")).thenThrow(
                new AwsException("NotFoundException", "Invalid REST API id specified", 404));
        // The API was actually created in eu-west-1; the default-region lookup must miss...
        // ...so resolveApiRegion is consulted and finds the real region.
        when(apiGatewayV2Service.resolveApiRegion("us-east-1", "abc123")).thenReturn("eu-west-1");
        when(apiGatewayV2Service.getApi("eu-west-1", "abc123")).thenReturn(new Api());
        // No route configured — dispatchV2 returns 404, but that's downstream of the region fix;
        // what this test asserts is which region the API/route lookups actually ran against.
        when(apiGatewayV2Service.findMatchingRoute("eu-west-1", "abc123", "GET", "/hello"))
                .thenReturn(null);

        ApiGatewayExecuteController controller = new ApiGatewayExecuteController(
                apiGatewayService, null, apiGatewayV2Service, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null, null, null);

        Response response = controller.dispatch("GET", "abc123", "prod", "hello", headers, null, null);

        assertEquals(404, response.getStatus());
        verify(apiGatewayV2Service).findMatchingRoute("eu-west-1", "abc123", "GET", "/hello");
    }

    @Test
    void signedV2RequestDoesNotConsultRegionFallback() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        RequestContext requestContext = new RequestContext();
        requestContext.setAccountId("000000000000");
        ApiGatewayV2Service apiGatewayV2Service = mock(ApiGatewayV2Service.class);
        ApiGatewayService apiGatewayService = mock(ApiGatewayService.class);
        HttpHeaders headers = mock(HttpHeaders.class);

        when(regionResolver.resolveRegion(headers)).thenReturn("us-east-1");
        when(headers.getHeaderString("Authorization")).thenReturn(
                "AWS4-HMAC-SHA256 Credential=AKID/20260215/us-east-1/execute-api/aws4_request");
        when(regionResolver.resolveRegionFromAuthOrNull(anyString())).thenReturn("us-east-1");
        // Not a v1 API — falls through to v2 at the already-resolved (signed) region.
        when(apiGatewayService.getRestApi("us-east-1", "abc123")).thenThrow(
                new AwsException("NotFoundException", "Invalid REST API id specified", 404));
        when(apiGatewayV2Service.resolveApiRegion("us-east-1", "abc123")).thenReturn("us-east-1");
        when(apiGatewayV2Service.getApi("us-east-1", "abc123")).thenReturn(new Api());
        when(apiGatewayV2Service.findMatchingRoute("us-east-1", "abc123", "GET", "/hello"))
                .thenReturn(null);

        ApiGatewayExecuteController controller = new ApiGatewayExecuteController(
                apiGatewayService, null, apiGatewayV2Service, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, requestContext, null, null, null);

        controller.dispatch("GET", "abc123", "prod", "hello", headers, null, null);

        // A correctly-signed (or otherwise resolved) request must not pay for the v1 region
        // scan — resolveRestApiRegion is only for the "region is a guess" case.
        verify(apiGatewayService, never()).resolveRestApiRegion(anyString(), anyString());
        verify(apiGatewayService).findRestApiOwner("abc123", "000000000000", "us-east-1");
        verify(apiGatewayV2Service, atLeastOnce()).getApi("us-east-1", "abc123");
    }

    @Test
    void nonSigV4AuthorizationHeaderFallsBackToRestApiRegionScan() {
        // A Cognito bearer JWT (or any Authorization header without a SigV4 Credential=...)
        // must be treated the same as a missing header: resolveRegion silently defaulted,
        // so the v1 REST path also needs to fall back to scanning for the real region. Uses
        // the real RegionResolver (not a mock) so the test exercises the actual header-parsing
        // logic in resolveRegionFromAuthOrNull, not just a stubbed answer.
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        ApiGatewayV2Service apiGatewayV2Service = mock(ApiGatewayV2Service.class);
        ApiGatewayService apiGatewayService = mock(ApiGatewayService.class);
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString("Authorization")).thenReturn("Bearer eyJhbGciOiJIUzI1NiJ9.fake.jwt");

        when(apiGatewayService.resolveRestApiRegion("us-east-1", "restapi1")).thenReturn("ap-southeast-2");
        when(apiGatewayService.getRestApi("ap-southeast-2", "restapi1")).thenThrow(
                new AwsException("NotFoundException", "Invalid REST API id specified", 404));
        // Not a v1 API either — falls through to v2, which must also scan.
        when(apiGatewayV2Service.resolveApiRegion("us-east-1", "restapi1")).thenReturn("us-east-1");
        when(apiGatewayV2Service.getApi("us-east-1", "restapi1")).thenThrow(
                new AwsException("NotFoundException", "Invalid API id specified", 404));

        ApiGatewayExecuteController controller = new ApiGatewayExecuteController(
                apiGatewayService, null, apiGatewayV2Service, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null, null, null);

        controller.dispatch("GET", "restapi1", "prod", "hello", headers, null, null);

        verify(apiGatewayService).resolveRestApiRegion("us-east-1", "restapi1");
        verify(apiGatewayService).getRestApi("ap-southeast-2", "restapi1");
    }

    @Test
    void projectsHttpApiV2CookiesAsRepeatedSetCookieHeadersAlongsideResponseHeaders() {
        ObjectMapper objectMapper = new ObjectMapper();
        ApiGatewayExecuteController controller = controller(objectMapper);
        InvokeResult result = new InvokeResult(
                200,
                null,
                """
                {
                  "statusCode": 200,
                  "headers": {
                    "X-Trace": "cookie-projection"
                  },
                  "cookies": [
                    "session=one; Path=/; HttpOnly",
                    "csrf=two; Path=/; Secure"
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8),
                null,
                "request-id");

        try (Response response = controller.buildProxyResponse(result, true)) {
            assertEquals(
                    List.of("session=one; Path=/; HttpOnly", "csrf=two; Path=/; Secure"),
                    response.getStringHeaders().get(HttpHeaders.SET_COOKIE));
            assertEquals("cookie-projection", response.getHeaderString("X-Trace"));
        }
    }

    @Test
    void ignoresHttpApiV2CookiesForRestApiV1Responses() {
        ObjectMapper objectMapper = new ObjectMapper();
        ApiGatewayExecuteController controller = controller(objectMapper);
        InvokeResult result = new InvokeResult(
                200,
                null,
                """
                {
                  "statusCode": 200,
                  "headers": {
                    "X-Trace": "rest-v1"
                  },
                  "multiValueHeaders": {
                    "Set-Cookie": [
                      "legacy=one; Path=/; HttpOnly",
                      "legacy=two; Path=/; Secure"
                    ]
                  },
                  "cookies": ["http-api-v2=ignored; Path=/"]
                }
                """.getBytes(StandardCharsets.UTF_8),
                null,
                "request-id");

        try (Response response = controller.buildProxyResponse(result, false)) {
            assertEquals(
                    List.of("legacy=one; Path=/; HttpOnly", "legacy=two; Path=/; Secure"),
                    response.getStringHeaders().get(HttpHeaders.SET_COOKIE));
            assertEquals("rest-v1", response.getHeaderString("X-Trace"));
        }
    }

    // ── HTTP API (v2) payload format 2.0 response inference ──────
    //
    // https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html
    // "Lambda function response for format 2.0": valid JSON without a statusCode is answered with
    // statusCode 200, content-type application/json, and the function's response as the body.

    private static InvokeResult functionError(String payloadJson) {
        return new InvokeResult(200, "Unhandled", payloadJson.getBytes(StandardCharsets.UTF_8), null, "req-1");
    }

    private static String bodyOf(Response response) {
        Object entity = response.getEntity();
        return entity instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : (String) entity;
    }

    @Test
    void httpApiV2ObjectWithoutStatusCodeIsTheBody() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        try (Response response = controller.buildProxyResponse(
                proxyPayload("{\"message\":\"Hello from Lambda!\"}"), true)) {
            assertEquals(200, response.getStatus());
            assertEquals("application/json", String.valueOf(response.getMediaType()));
            assertEquals("{\"message\":\"Hello from Lambda!\"}", bodyOf(response));
        }
    }

    @Test
    void httpApiV2StringResultIsTheBody() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        try (Response response = controller.buildProxyResponse(
                proxyPayload("\"Hello from Lambda!\""), true)) {
            assertEquals(200, response.getStatus());
            assertEquals("application/json", String.valueOf(response.getMediaType()));
            assertEquals("Hello from Lambda!", bodyOf(response));
        }
    }

    @Test
    void httpApiV2ResponseFieldsWithoutStatusCodeAreReturnedAsTheBody() {
        // Without a statusCode nothing is interpreted: headers and body are part of the JSON body.
        String payload = "{\"headers\":{\"X-Trace\":\"inferred\"},\"body\":\"inner\"}";
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        try (Response response = controller.buildProxyResponse(proxyPayload(payload), true)) {
            assertEquals(200, response.getStatus());
            assertEquals("application/json", String.valueOf(response.getMediaType()));
            assertEquals(payload, bodyOf(response));
            assertNull(response.getHeaderString("X-Trace"));
        }
    }

    @Test
    void httpApiV2FunctionErrorAnswersInternalServerErrorMessage() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        try (Response response = controller.buildProxyResponse(functionError(
                "{\"errorType\":\"Error\",\"errorMessage\":\"boom\",\"trace\":[]}"), true)) {
            assertEquals(500, response.getStatus());
            assertEquals("application/json", String.valueOf(response.getMediaType()));
            assertEquals("{\"message\":\"Internal Server Error\"}", bodyOf(response));
        }
    }

    @Test
    void httpApiPayloadV1ResponseWithoutStatusCodeAnswersInternalServerErrorMessage() {
        // Format 1.0 infers nothing, so a result without a statusCode is one the HTTP API cannot read.
        String payload = "{\"headers\":{\"X-Trace\":\"value\"},\"body\":\"inner\"}";
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        try (Response response = controller.buildProxyResponse(proxyPayload(payload), true, false)) {
            assertEquals(500, response.getStatus());
            assertEquals("{\"message\":\"Internal Server Error\"}", bodyOf(response));
            assertNull(response.getHeaderString("X-Trace"));
        }
    }

    @Test
    void httpApiPayloadV1FunctionErrorAnswersInternalServerErrorMessage() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        try (Response response = controller.buildProxyResponse(functionError(
                "{\"errorType\":\"Error\",\"errorMessage\":\"boom\",\"trace\":[]}"), true, false)) {
            assertEquals(500, response.getStatus());
            assertEquals("{\"message\":\"Internal Server Error\"}", bodyOf(response));
        }
    }

    @Test
    void restApiV1ResponseWithoutStatusCodeIsNotInferred() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        try (Response response = controller.buildProxyResponse(proxyPayload("{\"body\":\"inner\"}"), false)) {
            assertEquals(200, response.getStatus());
            assertEquals("inner", bodyOf(response));
        }
    }

    @Test
    void restApiV1FunctionErrorKeepsEmptyBody() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        try (Response response = controller.buildProxyResponse(functionError(
                "{\"errorType\":\"Error\",\"errorMessage\":\"boom\",\"trace\":[]}"), false)) {
            assertEquals(502, response.getStatus());
            assertNull(response.getEntity());
        }
    }

    @Test
    void requestTimeUsesEnglishMonthUnderNonEnglishDefaultLocale() {
        // The formatter is built at class initialisation, so switching the default locale here
        // cannot reach it: pinning its locale is what keeps the month ASCII on any machine.
        assertEquals(Locale.ENGLISH, ApiGatewayExecuteController.GATEWAY_REQUEST_TIME.getLocale());
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.JAPAN);
            assertEquals("05/Oct/2026:13:45:30 +0000", ApiGatewayExecuteController.GATEWAY_REQUEST_TIME
                    .format(Instant.parse("2026-10-05T13:45:30Z").atZone(ZoneOffset.UTC)));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void v2ProxyEventTimeIsEnglishUtcUnderNonUtcHost() throws Exception {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        assertEnglishUtcTimeUnderNonUtcHost(() -> controller.buildV2ProxyEvent("GET", "/items", "GET /items",
                "api1", "us-east-1", "$default", emptyHeaders(), uriInfoFor("http://localhost/items"),
                new byte[0], "req-1", null, null, null, null));
    }

    @Test
    void payloadFormatOneUsesV1EventShape() throws Exception {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        UriInfo uriInfo = uriInfoFor("http://localhost/echo-v1?q=1&q=2");
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.addAll("q", "1", "2");
        when(uriInfo.getQueryParameters()).thenReturn(query);
        HttpHeaders headers = emptyHeaders();
        MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.addAll("X-Trace", "first", "second");
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);

        JsonNode event = new ObjectMapper().readTree(controller.buildV1ProxyEvent(
                "GET", "/echo-v1", "GET /echo-v1", "api1", "us-east-1", "test",
                Map.of("environment", "review"), headers, uriInfo, new byte[0], "req-1",
                Map.of("sub", "user-1"), List.of("read"), null, null));

        assertEquals("1.0", event.path("version").asText());
        assertEquals("/echo-v1", event.path("resource").asText());
        assertEquals("/echo-v1", event.path("path").asText());
        assertEquals("GET", event.path("httpMethod").asText());
        assertTrue(event.path("rawPath").isMissingNode());
        assertTrue(event.path("pathParameters").isNull());
        assertEquals("review", event.path("stageVariables").path("environment").asText());
        assertTrue(event.path("requestContext").path("resourceId").isNull());
        assertTrue(event.path("requestContext").path("requestTime").isTextual());
        assertTrue(event.path("requestContext").path("requestTimeEpoch").asLong() > 0);
        assertTrue(event.path("requestContext").path("routeKey").isMissingNode());
        assertTrue(event.path("requestContext").path("time").isMissingNode());
        assertTrue(event.path("requestContext").path("timeEpoch").isMissingNode());
        assertEquals("user-1", event.path("requestContext").path("authorizer").path("claims")
                .path("sub").asText());
        assertEquals("read", event.path("requestContext").path("authorizer").path("scopes")
                .path(0).asText());
        assertTrue(event.path("requestContext").path("authorizer").path("jwt").isMissingNode());
        assertEquals("second", event.path("headers").path("x-trace").asText());
        assertTrue(event.path("headers").path("X-Trace").isMissingNode());
        JsonNode queryValues = event.path("multiValueQueryStringParameters").path("q");
        assertEquals("1", queryValues.path(0).asText());
        assertEquals("2", queryValues.path(1).asText());
    }

    @Test
    void v2RequestAuthorizerEventTimeIsEnglishUtcUnderNonUtcHost() throws Exception {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        assertEnglishUtcTimeUnderNonUtcHost(() -> controller.buildRequestAuthorizerEventV2("GET", "/items",
                "GET /items", "api1", "$default", "us-east-1", emptyHeaders(),
                uriInfoFor("http://localhost/items"), null).toString());
    }

    private static HttpHeaders emptyHeaders() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        return headers;
    }

    private static UriInfo uriInfoFor(String uri) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create(uri));
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        return uriInfo;
    }

    private static void assertEnglishUtcTimeUnderNonUtcHost(Supplier<String> buildEvent) throws Exception {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalZone = TimeZone.getDefault();
        String event;
        try {
            Locale.setDefault(Locale.JAPAN);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            event = buildEvent.get();
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalZone);
        }

        JsonNode ctx = new ObjectMapper().readTree(event).path("requestContext");
        String time = ctx.path("time").asText();
        assertTrue(time.matches("\\d{2}/[A-Z][a-z]{2}/\\d{4}:\\d{2}:\\d{2}:\\d{2} \\+0000"), time);
        long timeMillis = ZonedDateTime.parse(time, ApiGatewayExecuteController.GATEWAY_REQUEST_TIME)
                .toInstant().toEpochMilli();
        long timeEpoch = ctx.path("timeEpoch").asLong();
        assertTrue(timeEpoch >= timeMillis && timeEpoch - timeMillis < 2000, time + " vs " + timeEpoch);
    }

    // ──────────────────────────── HTTP API CORS on integration responses ────────────────────────────

    private static final String APP_ORIGIN = "https://app.example.com";

    private static Api.Cors cors(List<String> allowOrigins, Boolean allowCredentials, List<String> exposeHeaders) {
        return new Api.Cors(allowOrigins, List.of("GET", "POST"), List.of("authorization"), exposeHeaders,
                600, allowCredentials);
    }

    private static Response integrationResponseWithItsOwnCors() {
        return Response.status(201)
                .entity("{\"ok\":true}".getBytes(StandardCharsets.UTF_8))
                .type("application/json")
                .header("X-Trace", "kept")
                .header("Access-Control-Allow-Origin", "https://backend.example.com")
                .header("access-control-allow-methods", "PUT")
                .header("Access-Control-Allow-Headers", "x-backend")
                .header("Access-Control-Max-Age", "1")
                .header("Access-Control-Expose-Headers", "x-backend")
                .header("Access-Control-Allow-Credentials", "false")
                .build();
    }

    @Test
    void httpApiCorsAddsTheConfiguredHeadersForAnAllowedOrigin() {
        Response response = ApiGatewayExecuteController.withHttpApiCors(
                cors(List.of(APP_ORIGIN), true, List.of("x-request-id", "date")), "GET", APP_ORIGIN, null, null,
                integrationResponseWithItsOwnCors());

        assertEquals(201, response.getStatus());
        assertEquals("{\"ok\":true}", new String((byte[]) response.getEntity(), StandardCharsets.UTF_8));
        assertEquals("application/json", response.getHeaderString("Content-Type"));
        assertEquals("kept", response.getHeaderString("X-Trace"));
        assertEquals(List.of(APP_ORIGIN), response.getStringHeaders().get("Access-Control-Allow-Origin"));
        assertEquals(List.of("true"), response.getStringHeaders().get("Access-Control-Allow-Credentials"));
        assertEquals(List.of("x-request-id,date"), response.getStringHeaders().get("Access-Control-Expose-Headers"));
        assertNull(response.getHeaderString("Vary"), "AWS adds no Vary to an HTTP API response");
        assertNull(response.getHeaderString("Access-Control-Allow-Methods"));
        assertNull(response.getHeaderString("Access-Control-Allow-Headers"));
        assertNull(response.getHeaderString("Access-Control-Max-Age"));
    }

    @Test
    void httpApiCorsOmitsCredentialsAndExposeHeadersThatAreNotConfigured() {
        Response response = ApiGatewayExecuteController.withHttpApiCors(
                cors(List.of(APP_ORIGIN), null, null), "GET", APP_ORIGIN, null, null, integrationResponseWithItsOwnCors());

        assertEquals(APP_ORIGIN, response.getHeaderString("Access-Control-Allow-Origin"));
        assertNull(response.getHeaderString("Access-Control-Allow-Credentials"));
        assertNull(response.getHeaderString("Access-Control-Expose-Headers"));
    }

    @Test
    void httpApiCorsIgnoresTheIntegrationsCorsHeadersForADisallowedOrigin() {
        Response response = ApiGatewayExecuteController.withHttpApiCors(
                cors(List.of(APP_ORIGIN), true, List.of("x-request-id")), "GET", "https://evil.example", null, null,
                integrationResponseWithItsOwnCors());

        assertEquals(201, response.getStatus());
        assertEquals("kept", response.getHeaderString("X-Trace"));
        for (String header : List.of("Access-Control-Allow-Origin", "Access-Control-Allow-Credentials",
                "Access-Control-Expose-Headers", "Access-Control-Allow-Methods",
                "Access-Control-Allow-Headers", "Access-Control-Max-Age", "Vary")) {
            assertNull(response.getHeaderString(header), header);
        }
    }

    @Test
    void httpApiCorsAddsNothingWithoutAnOriginHeader() {
        Response response = ApiGatewayExecuteController.withHttpApiCors(
                cors(List.of("*"), null, List.of("x-request-id")), "GET", null, null, null, integrationResponseWithItsOwnCors());

        assertNull(response.getHeaderString("Access-Control-Allow-Origin"));
        assertNull(response.getHeaderString("Access-Control-Expose-Headers"));
        assertEquals("kept", response.getHeaderString("X-Trace"));
    }

    @Test
    void httpApiCorsAnswersAWildcardWithAStarAndNoVary() {
        Response response = ApiGatewayExecuteController.withHttpApiCors(
                cors(List.of("*"), null, null), "GET", APP_ORIGIN, null, null, integrationResponseWithItsOwnCors());

        assertEquals("*", response.getHeaderString("Access-Control-Allow-Origin"));
        assertNull(response.getHeaderString("Vary"));
    }

    @Test
    void httpApiCorsEchoesAnOriginAPrefixWildcardAllows() {
        Response allowed = ApiGatewayExecuteController.withHttpApiCors(
                cors(List.of("https://*"), null, null), "GET", APP_ORIGIN, null, null, integrationResponseWithItsOwnCors());
        Response refused = ApiGatewayExecuteController.withHttpApiCors(
                cors(List.of("https://*"), null, null), "GET", "http://app.example.com", null, null, integrationResponseWithItsOwnCors());

        assertEquals(APP_ORIGIN, allowed.getHeaderString("Access-Control-Allow-Origin"));
        assertNull(refused.getHeaderString("Access-Control-Allow-Origin"));
    }

    @Test
    void httpApiCorsKeepsTheIntegrationsOwnVary() {
        Response integration = Response.ok().header("Vary", "Accept-Encoding, origin").build();

        Response response = ApiGatewayExecuteController.withHttpApiCors(
                cors(List.of(APP_ORIGIN), null, null), "GET", APP_ORIGIN, null, null, integration);

        assertEquals(List.of("Accept-Encoding, origin"), response.getStringHeaders().get("Vary"));
    }

    /**
     * Measured (eu-west-1, 2026-10-16): a preflight gets the methods, headers, credentials and max
     * age, but no exposed headers, joined with a bare comma.
     */
    @Test
    void httpApiCorsAnswersAPreflightThatAsksForWhatIsAllowed() {
        Response response = ApiGatewayExecuteController.withHttpApiCors(
                new Api.Cors(List.of(APP_ORIGIN), List.of("GET", "POST"), List.of("authorization", "x-a"),
                        List.of("x-one"), 60, true),
                "OPTIONS", APP_ORIGIN, "GET", "Authorization, X-A", Response.noContent().build());

        assertEquals(APP_ORIGIN, response.getHeaderString("Access-Control-Allow-Origin"));
        assertEquals("GET,POST", response.getHeaderString("Access-Control-Allow-Methods"));
        assertEquals("authorization,x-a", response.getHeaderString("Access-Control-Allow-Headers"));
        assertEquals("true", response.getHeaderString("Access-Control-Allow-Credentials"));
        assertEquals("60", response.getHeaderString("Access-Control-Max-Age"));
        assertNull(response.getHeaderString("Access-Control-Expose-Headers"));
        assertNull(response.getHeaderString("Vary"), "a single exact origin does not vary");
    }

    /**
     * Measured (eu-west-1, 2026-10-18): a preflight carries {@code Vary: origin} unless the
     * configuration names a single exact origin or {@code *}, also when {@code *} is listed beside an
     * exact origin and answers for every origin.
     */
    @Test
    void httpApiCorsPreflightVariesByOriginUnlessOneOriginOrAStarIsConfigured() {
        Map<List<String>, String> expectedVary = new LinkedHashMap<>();
        expectedVary.put(List.of(APP_ORIGIN), null);
        expectedVary.put(List.of("*"), null);
        expectedVary.put(List.of(APP_ORIGIN, "https://other.example.com"), "origin");
        expectedVary.put(List.of("https://*"), "origin");
        expectedVary.put(List.of("*", APP_ORIGIN), "origin");
        expectedVary.forEach((origins, vary) -> {
            Response preflight = ApiGatewayExecuteController.withHttpApiCors(
                    new Api.Cors(origins, List.of("GET"), null, null, null, null),
                    "OPTIONS", APP_ORIGIN, "GET", null, Response.noContent().build());
            Response actual = ApiGatewayExecuteController.withHttpApiCors(
                    new Api.Cors(origins, List.of("GET"), null, null, null, null),
                    "GET", APP_ORIGIN, null, null, Response.ok().build());

            assertEquals(vary, preflight.getHeaderString("Vary"), origins.toString());
            assertNull(actual.getHeaderString("Vary"), origins + " on an actual request");
        });
        assertEquals("*", ApiGatewayExecuteController.withHttpApiCors(
                new Api.Cors(List.of("*", APP_ORIGIN), List.of("GET"), null, null, null, null),
                "OPTIONS", APP_ORIGIN, "GET", null, Response.noContent().build())
                .getHeaderString("Access-Control-Allow-Origin"));
    }

    /** Measured: a preflight names the allowed headers even when none are configured, with an empty value. */
    @Test
    void httpApiCorsPreflightNamesNoHeadersWhenNoneAreConfigured() {
        Response response = ApiGatewayExecuteController.withHttpApiCors(
                new Api.Cors(List.of(APP_ORIGIN), List.of("GET"), null, null, null, null),
                "OPTIONS", APP_ORIGIN, "GET", null, Response.noContent().build());

        assertEquals(List.of(""), response.getStringHeaders().get("Access-Control-Allow-Headers"));
        assertNull(response.getHeaderString("Access-Control-Max-Age"));
        assertNull(response.getHeaderString("Access-Control-Allow-Credentials"));
    }

    /**
     * Measured: a preflight asking for a method or a header the configuration does not allow, an
     * API without AllowMethods, and an OPTIONS request that is not a preflight get no CORS header.
     */
    @Test
    void httpApiCorsAddsNothingToAPreflightItRefuses() {
        Api.Cors configured = new Api.Cors(List.of(APP_ORIGIN), List.of("GET", "POST"), List.of("authorization"),
                null, 60, null);
        Api.Cors noMethods = new Api.Cors(List.of(APP_ORIGIN), null, List.of("authorization"), null, null, null);
        List<Response> refused = List.of(
                ApiGatewayExecuteController.withHttpApiCors(configured, "OPTIONS", APP_ORIGIN, "DELETE", null,
                        Response.noContent().build()),
                ApiGatewayExecuteController.withHttpApiCors(configured, "OPTIONS", APP_ORIGIN, "GET", "x-not-allowed",
                        Response.noContent().build()),
                ApiGatewayExecuteController.withHttpApiCors(noMethods, "OPTIONS", APP_ORIGIN, "GET", null,
                        Response.noContent().build()),
                ApiGatewayExecuteController.withHttpApiCors(configured, "OPTIONS", APP_ORIGIN, null, null,
                        Response.ok().build()));
        for (Response response : refused) {
            assertNull(response.getHeaderString("Access-Control-Allow-Origin"));
            assertNull(response.getHeaderString("Access-Control-Allow-Methods"));
        }
    }

    /** A configured list may hold a null entry: it matches nothing and is left out of the header. */
    @Test
    void httpApiCorsSkipsANullEntryOfAConfiguredList() {
        Response response = ApiGatewayExecuteController.withHttpApiCors(
                new Api.Cors(List.of(APP_ORIGIN), Arrays.asList(null, "GET"), Arrays.asList(null, "x-a"),
                        null, null, null),
                "OPTIONS", APP_ORIGIN, "GET", "x-a", Response.noContent().build());

        assertEquals(APP_ORIGIN, response.getHeaderString("Access-Control-Allow-Origin"));
        assertEquals("GET", response.getHeaderString("Access-Control-Allow-Methods"));
        assertEquals("x-a", response.getHeaderString("Access-Control-Allow-Headers"));
    }

    /** Measured: wildcards allow any method and header, and are sent back as they are. */
    @Test
    void httpApiCorsAllowsAnyMethodAndHeaderForWildcards() {
        Response response = ApiGatewayExecuteController.withHttpApiCors(
                new Api.Cors(List.of("*"), List.of("*"), List.of("*"), null, null, null),
                "OPTIONS", APP_ORIGIN, "DELETE", "x-anything", Response.noContent().build());

        assertEquals("*", response.getHeaderString("Access-Control-Allow-Origin"));
        assertEquals("*", response.getHeaderString("Access-Control-Allow-Methods"));
        assertEquals("*", response.getHeaderString("Access-Control-Allow-Headers"));
    }

    /** Measured: an actual request gets the headers whatever its method, DELETE when only GET is allowed too. */
    @Test
    void httpApiCorsAddsTheActualHeadersWhateverTheMethod() {
        Response response = ApiGatewayExecuteController.withHttpApiCors(
                cors(List.of(APP_ORIGIN), null, List.of("x-one", "x-two")), "DELETE", APP_ORIGIN, null, null,
                Response.ok().build());

        assertEquals(APP_ORIGIN, response.getHeaderString("Access-Control-Allow-Origin"));
        assertEquals("x-one,x-two", response.getHeaderString("Access-Control-Expose-Headers"));
    }

    @Test
    void httpApiCorsDropsTheCorsHeadersALambdaReturnsInLowercase() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response lambdaResponse = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"headers":{"access-control-allow-origin":"*","x-trace":"lambda"},
                 "body":"hi"}
                """), true);

        Response response = ApiGatewayExecuteController.withHttpApiCors(
                cors(List.of(APP_ORIGIN), null, null), "GET", "https://evil.example", null, null, lambdaResponse);

        assertNull(response.getHeaderString("Access-Control-Allow-Origin"));
        assertEquals("lambda", response.getHeaderString("x-trace"));
        assertEquals("hi", new String((byte[]) response.getEntity(), StandardCharsets.UTF_8));
    }
}
