package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountContextFilterTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String DEFAULT_REGION = "us-east-1";

    private AccountResolver accountResolver;
    private RegionResolver regionResolver;
    private RequestContext requestContext;
    private AccountContextFilter filter;

    @BeforeEach
    void setUp() {
        accountResolver = new AccountResolver(DEFAULT_ACCOUNT);
        regionResolver = new RegionResolver(DEFAULT_REGION, DEFAULT_ACCOUNT);
        requestContext = new RequestContext();
        filter = new AccountContextFilter(accountResolver, regionResolver, requestContext);
    }

    @Test
    void resolvesFromAuthHeaderWhenPresent() {
        ContainerRequestContext ctx = mockContext(
            "AWS4-HMAC-SHA256 Credential=000000000001/20260617/us-west-2/s3/aws4_request, SignedHeaders=host, Signature=abc",
            null
        );
        filter.filter(ctx);
        assertEquals("000000000001", requestContext.getAccountId());
        assertEquals("us-west-2", requestContext.getRegion());
    }

    @Test
    void resolvesFromPresignedCredentialWhenNoAuthHeader() {
        ContainerRequestContext ctx = mockContext(null,
            "000000000002/20260617/eu-west-1/s3/aws4_request");
        filter.filter(ctx);
        assertEquals("000000000002", requestContext.getAccountId());
        assertEquals("eu-west-1", requestContext.getRegion());
    }

    @Test
    void authHeaderTakesPrecedenceOverPresignedCredential() {
        ContainerRequestContext ctx = mockContext(
            "AWS4-HMAC-SHA256 Credential=000000000001/20260617/us-west-2/s3/aws4_request, SignedHeaders=host, Signature=abc",
            "000000000002/20260617/eu-west-1/s3/aws4_request"
        );
        filter.filter(ctx);
        assertEquals("000000000001", requestContext.getAccountId());
        assertEquals("us-west-2", requestContext.getRegion());
    }

    @Test
    void fallsBackToDefaultsWhenNoAuthInfo() {
        ContainerRequestContext ctx = mockContext(null, null);
        filter.filter(ctx);
        assertEquals(DEFAULT_ACCOUNT, requestContext.getAccountId());
        assertEquals(DEFAULT_REGION, requestContext.getRegion());
    }

    @Test
    void emptyAuthHeaderFallsBackToPresignedCredential() {
        ContainerRequestContext ctx = mockContext("",
            "000000000002/20260617/eu-west-1/s3/aws4_request");
        filter.filter(ctx);
        assertEquals("000000000002", requestContext.getAccountId());
        assertEquals("eu-west-1", requestContext.getRegion());
    }

    @Test
    void emptyPresignedCredentialFallsBackToDefaults() {
        ContainerRequestContext ctx = mockContext("", "");
        filter.filter(ctx);
        assertEquals(DEFAULT_ACCOUNT, requestContext.getAccountId());
        assertEquals(DEFAULT_REGION, requestContext.getRegion());
    }

    private ContainerRequestContext mockContext(String authHeader, String xAmzCredential) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getHeaderString("Authorization")).thenReturn(authHeader);

        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        if (xAmzCredential != null) {
            queryParams.add("X-Amz-Credential", xAmzCredential);
        }
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);
        when(ctx.getUriInfo()).thenReturn(uriInfo);

        return ctx;
    }
}
