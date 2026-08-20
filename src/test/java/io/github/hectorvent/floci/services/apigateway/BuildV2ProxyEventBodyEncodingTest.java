package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuildV2ProxyEventBodyEncodingTest {

    private static final byte[] REQUEST_BODY = "hello world".getBytes(StandardCharsets.UTF_8);
    private static final String TEXT_BODY = "hello world";
    private static final String BASE64_BODY = Base64.getEncoder().encodeToString(REQUEST_BODY);

    private ApiGatewayExecuteController controller;
    private ObjectMapper objectMapper;
    private HttpHeaders headers;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() throws Exception {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(headers.getHeaderString("User-Agent")).thenReturn(null);

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/test/echo"));

        objectMapper = new ObjectMapper();
        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, objectMapper, null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodyEncodingCases")
    void serializesBodyAccordingToContentType(
            String caseName,
            String contentType,
            byte[] requestBody,
            String expectedBody,
            boolean expectedBase64Encoded) throws Exception {
        when(headers.getHeaderString(HttpHeaders.CONTENT_TYPE)).thenReturn(contentType);

        String json = controller.buildV2ProxyEvent(
                "POST", "/echo", "POST /echo",
                "abc123", "us-east-1", "$default", headers, uriInfo, requestBody, "req-1");

        JsonNode event = objectMapper.readTree(json);
        if (expectedBody == null) {
            assertTrue(event.path("body").isNull());
        } else {
            assertEquals(expectedBody, event.path("body").asText());
        }
        assertEquals(expectedBase64Encoded, event.path("isBase64Encoded").asBoolean());
    }

    private static Stream<Arguments> bodyEncodingCases() {
        return Stream.of(
                arguments("text/plain", "text/plain", REQUEST_BODY, TEXT_BODY, false),
                arguments("text/plain UTF-8", "text/plain; charset=utf-8", REQUEST_BODY, TEXT_BODY, false),
                arguments("text/plain uppercase UTF-8", "TEXT/PLAIN; CHARSET=UTF-8", REQUEST_BODY, TEXT_BODY, false),
                arguments("text/html", "text/html", REQUEST_BODY, TEXT_BODY, false),
                arguments("text/csv", "text/csv", REQUEST_BODY, TEXT_BODY, false),
                arguments("text/xml", "text/xml", REQUEST_BODY, TEXT_BODY, false),
                arguments("application/json", "application/json", REQUEST_BODY, TEXT_BODY, false),
                arguments("application/json UTF-8", "application/json; charset=utf-8", REQUEST_BODY, TEXT_BODY, false),
                arguments("application/json uppercase UTF-8", "APPLICATION/JSON; CHARSET=UTF-8",
                        REQUEST_BODY, TEXT_BODY, false),
                arguments("application/xml", "application/xml", REQUEST_BODY, TEXT_BODY, false),
                arguments("application/javascript", "application/javascript", REQUEST_BODY, TEXT_BODY, false),
                arguments("application/graphql", "application/graphql", REQUEST_BODY, TEXT_BODY, false),
                arguments("missing Content-Type", null, REQUEST_BODY, BASE64_BODY, true),
                arguments("text/plain ISO-8859-1", "text/plain; charset=iso-8859-1", REQUEST_BODY, BASE64_BODY, true),
                arguments("application/x-www-form-urlencoded", "application/x-www-form-urlencoded",
                        REQUEST_BODY, BASE64_BODY, true),
                arguments("application/octet-stream", "application/octet-stream", REQUEST_BODY, BASE64_BODY, true),
                arguments("application/x-protobuf", "application/x-protobuf", REQUEST_BODY, BASE64_BODY, true),
                arguments("application/zip", "application/zip", REQUEST_BODY, BASE64_BODY, true),
                arguments("application/pdf", "application/pdf", REQUEST_BODY, BASE64_BODY, true),
                arguments("multipart/form-data", "multipart/form-data; boundary=test", REQUEST_BODY, BASE64_BODY, true),
                arguments("image/png", "image/png", REQUEST_BODY, BASE64_BODY, true),
                arguments("image/jpeg", "image/jpeg", REQUEST_BODY, BASE64_BODY, true),
                arguments("image/gif", "image/gif", REQUEST_BODY, BASE64_BODY, true),
                arguments("audio/mpeg", "audio/mpeg", REQUEST_BODY, BASE64_BODY, true),
                arguments("video/mp4", "video/mp4", REQUEST_BODY, BASE64_BODY, true),
                arguments("custom content type", "weird/unknown", REQUEST_BODY, BASE64_BODY, true),
                arguments("empty body", "application/octet-stream", new byte[0], null, false)
        );
    }
}
