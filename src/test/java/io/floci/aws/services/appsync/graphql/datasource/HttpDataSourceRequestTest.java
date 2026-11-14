package io.floci.aws.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.appsync.model.DataSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpDataSourceRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesEndpointBasePathAndEscapedResourcePath() {
        HttpDataSourceRequest request = parse("https://api.example.com/base/",
                Map.of("method", "GET", "resourcePath", "/items/a%2Fb"));

        assertEquals("https://api.example.com/base/items/a%2Fb", request.uri().toString());
        assertEquals("GET", request.method());
        assertArrayEquals(new byte[0], request.body());
    }

    @Test
    void encodesQueryValuesOnceAndPreservesUtf8Body() {
        HttpDataSourceRequest request = parse("https://api.example.com", Map.of(
                "version", "2018-05-29", "method", "POST", "resourcePath", "/items",
                "params", Map.of("query", Map.of("q", "a+b /ü&x=1"),
                        "headers", Map.of("Content-Type", "text/plain", "X-Request-Id", "abc"),
                        "body", "hello ü")));

        assertEquals("https://api.example.com/items?q=a%2Bb%20%2F%C3%BC%26x%3D1", request.uri().toString());
        assertEquals(Map.of("content-type", "text/plain", "x-request-id", "abc"), request.headers());
        assertEquals("hello ü", new String(request.body(), StandardCharsets.UTF_8));
    }

    @Test
    void serializesStructuredBodyAndHonorsDeclaredCharset() throws Exception {
        HttpDataSourceRequest json = parse("https://api.example.com", Map.of(
                "method", "PUT", "resourcePath", "/", "params", Map.of("body", Map.of("id", 7))));
        assertEquals(mapper.readTree("{\"id\":7}"), mapper.readTree(json.body()));

        HttpDataSourceRequest text = parse("https://api.example.com", Map.of(
                "method", "PATCH", "resourcePath", "/", "params", Map.of("body", "café",
                        "headers", Map.of("Content-Type", "text/plain; charset=ISO-8859-1"))));
        assertArrayEquals("café".getBytes(StandardCharsets.ISO_8859_1), text.body());
    }

    @Test
    void refusesPathsThatReplaceOriginOrCarryQueryOrFragment() {
        for (String path : List.of("https://other.example/a", "//other.example/a", "relative", "/a?x=1", "/a#f", "/a\\b")) {
            assertInvalid("https://api.example.com", Map.of("method", "GET", "resourcePath", path));
        }
    }

    @Test
    void refusesAmbiguousEndpointAuthorityAndUnsupportedSchemes() {
        for (String endpoint : List.of("file:///etc/passwd", "https://user:pass@api.example.com", "https://api.example.com?a=1",
                "https://api.example.com#f", "http://", "https://api.example.com:99999")) {
            assertInvalid(endpoint, Map.of("method", "GET", "resourcePath", "/"));
        }
    }

    @Test
    void refusesReservedOrInjectedHeadersAndNonStringParams() {
        for (String name : List.of("Host", "Connection", "Content-Length", "Transfer-Encoding", "Expect", "User-Agent", "Keep-Alive")) {
            assertInvalid("https://api.example.com", Map.of("method", "GET", "resourcePath", "/",
                    "params", Map.of("headers", Map.of(name, "value"))));
        }
        assertInvalid("https://api.example.com", Map.of("method", "GET", "resourcePath", "/",
                "params", Map.of("headers", Map.of("x-test", "good\r\nHost: evil"))));
        assertInvalid("https://api.example.com", Map.of("method", "GET", "resourcePath", "/",
                "params", Map.of("query", Map.of("id", 1))));
        assertInvalid("https://api.example.com", Map.of("method", "GET", "resourcePath", "/", "params", "bad"));
    }

    @Test
    void refusesMissingMethodPathUnsupportedMethodAndVersion() {
        for (Map<String, Object> request : List.<Map<String, Object>>of(Map.of("method", "GET"), Map.of("resourcePath", "/"),
                Map.of("method", "TRACE", "resourcePath", "/"),
                Map.of("method", "GET", "resourcePath", "/", "version", "2017-02-28"))) {
            assertInvalid("https://api.example.com", request);
        }
    }

    private HttpDataSourceRequest parse(String endpoint, Map<String, Object> request) {
        DataSource source = new DataSource();
        source.setName("Http");
        source.setHttpConfig(Map.of("endpoint", endpoint));
        return HttpDataSourceRequest.parse(source, request, mapper);
    }

    private void assertInvalid(String endpoint, Map<String, Object> request) {
        AwsException error = assertThrows(AwsException.class, () -> parse(endpoint, request));
        assertEquals("MappingTemplate", error.getErrorCode());
    }
}
