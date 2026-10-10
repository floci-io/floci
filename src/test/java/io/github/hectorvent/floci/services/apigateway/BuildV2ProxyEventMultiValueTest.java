package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Payload format 2.0 has no {@code multiValueHeaders} or {@code multiValueQueryStringParameters}:
 * AWS combines duplicate headers and duplicate query strings with commas, and moves the request's
 * cookies into a {@code cookies} array. See "Payload format differences" in
 * https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html.
 * The Lambda authorizer gets the same 2.0 payload (see the format 2.0 example in
 * https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html),
 * so every case runs against both builders.
 */
class BuildV2ProxyEventMultiValueTest {

    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private MultivaluedMap<String, String> requestHeaders;
    private UriInfo uriInfo;
    private MultivaluedMap<String, String> queryParams;

    @BeforeEach
    void setUp() throws Exception {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        requestHeaders = new MultivaluedHashMap<>();
        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);

        queryParams = new MultivaluedHashMap<>();
        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/echo"));

        controller = new ApiGatewayExecuteController(
                null, null, null, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null, null, null);
    }

    enum Builder { INTEGRATION, AUTHORIZER }

    private JsonNode buildEvent(Builder builder) throws Exception {
        String json = switch (builder) {
            case INTEGRATION -> controller.buildV2ProxyEvent(
                    "GET", "/echo", "GET /echo",
                    "abc123", "us-east-1", "$default", headers, uriInfo, null, "req-1");
            case AUTHORIZER -> controller.buildRequestAuthorizerEventV2(
                    "GET", "/echo", "GET /echo",
                    "abc123", "$default", "us-east-1", headers, uriInfo);
        };
        return new ObjectMapper().readTree(json);
    }

    @ParameterizedTest
    @EnumSource(Builder.class)
    void repeatedQueryKeyIsCommaJoined(Builder builder) throws Exception {
        queryParams.addAll("q", List.of("1", "2"));
        queryParams.add("single", "x");

        JsonNode qsp = buildEvent(builder).get("queryStringParameters");

        assertEquals("1,2", qsp.get("q").asText());
        assertEquals("x", qsp.get("single").asText());
    }

    @ParameterizedTest
    @EnumSource(Builder.class)
    void repeatedHeaderIsCommaJoined(Builder builder) throws Exception {
        requestHeaders.addAll("X-Trace", List.of("first", "second"));
        requestHeaders.add("X-Single", "only");

        JsonNode headersNode = buildEvent(builder).get("headers");

        assertEquals("first,second", headersNode.get("x-trace").asText());
        assertEquals("only", headersNode.get("x-single").asText());
    }

    @ParameterizedTest
    @EnumSource(Builder.class)
    void cookieHeaderIsSplitIntoCookiesArray(Builder builder) throws Exception {
        requestHeaders.add("Cookie", "a=1; b=2");

        JsonNode cookies = buildEvent(builder).get("cookies");

        assertEquals("[\"a=1\",\"b=2\"]", String.valueOf(cookies));
    }

    @ParameterizedTest
    @EnumSource(Builder.class)
    void everyCookieHeaderContributesToCookiesArray(Builder builder) throws Exception {
        requestHeaders.addAll("cookie", List.of("a=1; b=2", "c=3"));

        JsonNode cookies = buildEvent(builder).get("cookies");

        assertEquals("[\"a=1\",\"b=2\",\"c=3\"]", String.valueOf(cookies));
    }

    @ParameterizedTest
    @EnumSource(Builder.class)
    void requestWithoutCookiesOmitsCookiesField(Builder builder) throws Exception {
        requestHeaders.add("X-Single", "only");

        assertFalse(buildEvent(builder).has("cookies"));
    }
}
