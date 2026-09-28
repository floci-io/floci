package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AWS drops the {@code Authorization} header before a REST Lambda proxy integration when the
 * method is AWS_IAM or the header carries a SigV4 signature ("Amazon API Gateway important notes
 * for REST APIs", header table footnote). A function whose Bearer parser treats a missing header as
 * "no user token" broke only on Floci, because the IAM caller's SigV4 header reached it.
 */
class ProxyEventAuthorizationHeaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecuteApiSigV4Authorizer.CallerIdentity CALLER =
            new ExecuteApiSigV4Authorizer.CallerIdentity(
                    "AKIAIOSFODNN7EXAMPLE", "000000000000",
                    "arn:aws:iam::000000000000:user/alice", "AIDAEXAMPLEUSERID");
    private static final String SIGV4 = "AWS4-HMAC-SHA256 "
            + "Credential=AKIAIOSFODNN7EXAMPLE/20260928/us-east-1/execute-api/aws4_request, "
            + "SignedHeaders=host;x-amz-date, Signature=abc123";

    private ApiGatewayExecuteController controller;
    private MultivaluedMap<String, String> requestHeaders;
    private HttpHeaders headers;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() throws Exception {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        requestHeaders = new MultivaluedHashMap<>();
        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/execute-api/api1/test/orders"));

        controller = new ApiGatewayExecuteController(
                null, null, null, null,
                regionResolver, MAPPER, null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null);
    }

    @Test
    void awsIamMethodDropsTheSignatureButKeepsTheOtherSigningHeaders() throws Exception {
        requestHeaders.add("Authorization", SIGV4);
        requestHeaders.add("X-Amz-Date", "20260928T120000Z");
        requestHeaders.add("X-Amz-Security-Token", "session-token");

        JsonNode event = event(CALLER);

        assertAuthorizationAbsent(event);
        assertEquals("20260928T120000Z", event.path("headers").path("X-Amz-Date").asText());
        assertEquals("session-token", event.path("headers").path("X-Amz-Security-Token").asText());
        assertEquals("session-token",
                event.path("multiValueHeaders").path("X-Amz-Security-Token").path(0).asText());
    }

    @Test
    void awsIamMethodDropsAnAuthorizationHeaderThatIsNotSigV4() throws Exception {
        // A presigned-URL caller is authenticated by the query string, so its Authorization header
        // can be anything. AWS still drops it: the method is AWS_IAM.
        requestHeaders.add("Authorization", "Bearer user-token");

        assertAuthorizationAbsent(event(CALLER));
    }

    @Test
    void noneMethodDropsASigV4AuthorizationHeader() throws Exception {
        requestHeaders.add("Authorization", SIGV4);

        assertAuthorizationAbsent(event(null));
    }

    @Test
    void noneMethodPassesABearerAuthorizationHeaderThrough() throws Exception {
        requestHeaders.add("Authorization", "Bearer user-token");

        JsonNode event = event(null);

        assertEquals("Bearer user-token", event.path("headers").path("Authorization").asText());
        assertEquals("Bearer user-token",
                event.path("multiValueHeaders").path("Authorization").path(0).asText());
    }

    @Test
    void headerNameIsMatchedCaseInsensitively() throws Exception {
        requestHeaders.add("authorization", SIGV4);

        JsonNode event = event(null);

        assertTrue(event.path("headers").path("authorization").isMissingNode());
        assertTrue(event.path("multiValueHeaders").path("authorization").isMissingNode());
    }

    private JsonNode event(ExecuteApiSigV4Authorizer.CallerIdentity iamIdentity) throws Exception {
        return MAPPER.readTree(controller.buildProxyEvent(
                "us-east-1", "api1", "GET", "/orders", "/orders", "res1", "test", null,
                headers, uriInfo, null, "req-1", null, null, null, iamIdentity));
    }

    private static void assertAuthorizationAbsent(JsonNode event) {
        assertTrue(event.path("headers").path("Authorization").isMissingNode(),
                "headers must not carry Authorization");
        assertTrue(event.path("multiValueHeaders").path("Authorization").isMissingNode(),
                "multiValueHeaders must not carry Authorization");
    }
}
