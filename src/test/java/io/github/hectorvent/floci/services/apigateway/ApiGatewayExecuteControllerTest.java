package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                null, null, null,
                regionResolver, objectMapper, null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null);
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
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode event = objectMapper.createObjectNode();
        ApiGatewayExecuteController controller = controller(objectMapper);
        controller.putSingleValueHeaders(event, headers);
        controller.putMultiValueHeaders(event, headers);

        assertEquals("third", event.path("headers").path("X-Dup").asText());
        assertEquals(
                objectMapper.valueToTree(List.of("first", "second", "third")),
                event.path("multiValueHeaders").path("X-Dup"));
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
                apiGatewayService, apiGatewayV2Service, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null);

        Response response = controller.dispatch("GET", "abc123", "prod", "hello", headers, null, null);

        assertEquals(404, response.getStatus());
        verify(apiGatewayV2Service).findMatchingRoute("eu-west-1", "abc123", "GET", "/hello");
    }

    @Test
    void signedV2RequestDoesNotConsultRegionFallback() {
        RegionResolver regionResolver = mock(RegionResolver.class);
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
                apiGatewayService, apiGatewayV2Service, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null);

        controller.dispatch("GET", "abc123", "prod", "hello", headers, null, null);

        // A correctly-signed (or otherwise resolved) request must not pay for the v1 region
        // scan — resolveRestApiRegion is only for the "region is a guess" case.
        verify(apiGatewayService, never()).resolveRestApiRegion(anyString(), anyString());
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
                apiGatewayService, apiGatewayV2Service, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null);

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
}
