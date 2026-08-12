package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit coverage for API Gateway request-event construction and HTTP API v2 route matching. */
class ApiGatewayExecuteControllerTest {

    private static ApiGatewayExecuteController controller(ObjectMapper objectMapper) {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        return new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, objectMapper, null,
                null, null, null, null);
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
                """));
        assertEquals("application/problem+json", response.getMediaType().toString());
    }

    @Test
    void buildProxyResponseMatchesExactCaseContentType() {
        // The pre-existing exact-case path must keep working — this is a regression guard,
        // not a new behavior.
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"headers":{"Content-Type":"text/plain"},"body":"hi"}
                """));
        assertEquals("text/plain", response.getMediaType().toString());
    }

    @Test
    void buildProxyResponseMatchesMixedCaseContentType() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"headers":{"CONTENT-TYPE":"application/xml"},"body":"<a/>"}
                """));
        assertEquals("application/xml", response.getMediaType().toString());
    }

    @Test
    void buildProxyResponseDefaultsToJsonWhenNoContentTypeHeaderPresent() {
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"headers":{"X-Other":"value"},"body":"{}"}
                """));
        assertEquals("application/json", response.getMediaType().toString());
    }

    @Test
    void buildProxyResponseDefaultsToJsonWhenContentTypeIsJsonNull() {
        // A JSON-null Content-Type must fall back to the default, the same as a missing header —
        // not be coerced to the literal string "null" (an invalid media type).
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"headers":{"content-type":null},"body":"{}"}
                """));
        assertEquals("application/json", response.getMediaType().toString());
    }

    @Test
    void buildProxyResponseMatchesContentTypeInMultiValueHeaders() {
        // multiValueHeaders is a fully valid way to return any header, including Content-Type —
        // not just repeated ones — and must be scanned the same as headers.
        ApiGatewayExecuteController controller = controller(new ObjectMapper());
        Response response = controller.buildProxyResponse(proxyPayload("""
                {"statusCode":200,"multiValueHeaders":{"content-type":["application/xml"]},"body":"<a/>"}
                """));
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
                """));
        assertEquals("application/xml", response.getMediaType().toString());
    }
}
