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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The verified SigV4 caller behind an AWS_IAM route has to reach the integration, not just gate it:
 * REST renders it under {@code requestContext.identity}, HTTP APIs under
 * {@code requestContext.authorizer.iam}. Both were unreachable before AWS_IAM was enforced at
 * all: REST's identity fields were hardcoded to null with a comment saying so.
 */
class ProxyEventIamIdentityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecuteApiSigV4Authorizer.CallerIdentity CALLER =
            new ExecuteApiSigV4Authorizer.CallerIdentity(
                    "AKIAIOSFODNN7EXAMPLE", "000000000000",
                    "arn:aws:iam::000000000000:user/alice", "AIDAEXAMPLEUSERID");

    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() throws Exception {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/execute-api/api1/test/iam"));

        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, MAPPER, null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null);
    }

    @Test
    void restIdentityCarriesTheVerifiedIamCaller() throws Exception {
        JsonNode identity = MAPPER.readTree(controller.buildProxyEvent(
                        "us-east-1", "api1", "GET", "/iam", "iam", "/iam", "res1", "test", null,
                        headers, uriInfo, null, "req-1", null, null, null, CALLER))
                .path("requestContext").path("identity");

        assertEquals("AKIAIOSFODNN7EXAMPLE", identity.get("accessKey").asText());
        assertEquals("000000000000", identity.get("accountId").asText());
        assertEquals("AIDAEXAMPLEUSERID", identity.get("caller").asText());
        assertEquals("AIDAEXAMPLEUSERID", identity.get("user").asText());
        assertEquals("arn:aws:iam::000000000000:user/alice", identity.get("userArn").asText());
    }

    @Test
    void restIdentityStaysExplicitlyNullWithoutIamAuthorization() throws Exception {
        JsonNode identity = MAPPER.readTree(controller.buildProxyEvent(
                        "us-east-1", "api1", "GET", "/open", "open", "/open", "res2", "test", null,
                        headers, uriInfo, null, "req-2", null, null, null, null))
                .path("requestContext").path("identity");

        // AWS sends these as explicit JSON null rather than omitting them.
        for (String field : new String[]{"accessKey", "accountId", "caller", "user", "userArn"}) {
            assertTrue(identity.has(field) && identity.get(field).isNull(),
                    field + " must be present and null on a non-IAM method");
        }
    }

    @Test
    void httpApiRendersTheVerifiedCallerUnderAuthorizerIam() throws Exception {
        JsonNode iam = MAPPER.readTree(controller.buildV2ProxyEvent(
                        "GET", "/iam", "GET /iam", "api1", "us-east-1", "test",
                        headers, uriInfo, null, "req-3", null, null, null, CALLER))
                .path("requestContext").path("authorizer").path("iam");

        assertEquals("AKIAIOSFODNN7EXAMPLE", iam.get("accessKey").asText());
        assertEquals("000000000000", iam.get("accountId").asText());
        assertEquals("AIDAEXAMPLEUSERID", iam.get("callerId").asText());
        assertEquals("AIDAEXAMPLEUSERID", iam.get("userId").asText());
        assertEquals("arn:aws:iam::000000000000:user/alice", iam.get("userArn").asText());
        assertTrue(iam.get("cognitoIdentity").isNull());
        assertTrue(iam.get("principalOrgId").isNull());
    }

    @Test
    void httpApiOmitsTheAuthorizerNodeWithoutIamAuthorization() throws Exception {
        JsonNode requestContext = MAPPER.readTree(controller.buildV2ProxyEvent(
                        "GET", "/open", "GET /open", "api1", "us-east-1", "test",
                        headers, uriInfo, null, "req-4", null, null, null, null))
                .path("requestContext");

        assertTrue(requestContext.path("authorizer").isMissingNode(),
                "a route with no authorizer must not grow an authorizer node");
    }
}
