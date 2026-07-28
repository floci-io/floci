package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ApiGatewayExecuteController#buildV2ProxyEvent} surfaces the
 * validated JWT's claims at {@code requestContext.authorizer.jwt.claims} for HTTP API
 * (V2) routes with {@code AuthorizationType: JWT}, matching the payload 2.0 event
 * real API Gateway delivers to an AWS_PROXY Lambda.
 */
class BuildV2ProxyEventJwtAuthorizerClaimsTest {

    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uriInfo;

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String token(String payloadJson) {
        return b64url("{\"alg\":\"none\"}") + "." + b64url(payloadJson) + ".sig";
    }

    @BeforeEach
    void setUp() throws Exception {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(headers.getHeaderString("User-Agent")).thenReturn(null);

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/api/v1/assets"));

        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null
        );
    }

    @Test
    void jwtRouteInjectsClaimsIntoRequestContext() throws Exception {
        String jwt = token("{\"sub\":\"user-123\",\"email\":\"a@example.com\",\"exp\":9999999999}");
        when(headers.getHeaderString("Authorization")).thenReturn("Bearer " + jwt);

        String json = controller.buildV2ProxyEvent(
                "GET", "/assets", "GET /assets", "JWT",
                "abc123", "v1", headers, uriInfo, null, "req-1");
        JsonNode authorizer = new ObjectMapper().readTree(json)
                .path("requestContext").path("authorizer");

        assertFalse(authorizer.isMissingNode(), "authorizer must be present for JWT routes");
        JsonNode claims = authorizer.path("jwt").path("claims");
        assertEquals("user-123", claims.get("sub").asText());
        assertEquals("a@example.com", claims.get("email").asText());
        assertEquals("9999999999", claims.get("exp").asText(),
                "claim values are stringified in the 2.0 payload");
        assertTrue(authorizer.path("jwt").get("scopes").isNull(),
                "scopes is null when no authorization scopes are configured");
    }

    @Test
    void jwtRouteWithoutTokenOmitsAuthorizer() throws Exception {
        when(headers.getHeaderString("Authorization")).thenReturn(null);

        String json = controller.buildV2ProxyEvent(
                "GET", "/assets", "GET /assets", "JWT",
                "abc123", "v1", headers, uriInfo, null, "req-2");
        JsonNode event = new ObjectMapper().readTree(json);

        assertTrue(event.path("requestContext").path("authorizer").isMissingNode());
    }

    @Test
    void nonJwtRouteOmitsAuthorizerEvenWithBearerToken() throws Exception {
        String jwt = token("{\"sub\":\"user-123\"}");
        when(headers.getHeaderString("Authorization")).thenReturn("Bearer " + jwt);

        String json = controller.buildV2ProxyEvent(
                "GET", "/assets", "GET /assets", "NONE",
                "abc123", "v1", headers, uriInfo, null, "req-3");
        JsonNode event = new ObjectMapper().readTree(json);

        assertTrue(event.path("requestContext").path("authorizer").isMissingNode());
    }

    @Test
    void malformedTokenOmitsAuthorizer() throws Exception {
        when(headers.getHeaderString("Authorization")).thenReturn("Bearer not-a-jwt");

        String json = controller.buildV2ProxyEvent(
                "GET", "/assets", "GET /assets", "JWT",
                "abc123", "v1", headers, uriInfo, null, "req-4");
        JsonNode event = new ObjectMapper().readTree(json);

        assertTrue(event.path("requestContext").path("authorizer").isMissingNode());
    }
}
