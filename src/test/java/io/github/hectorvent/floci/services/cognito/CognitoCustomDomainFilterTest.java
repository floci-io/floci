package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AccountContextFilter;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CognitoCustomDomainFilterTest {

    private static final String DOMAIN = "auth.example.localhost.floci.io";
    private static final String ACCOUNT = "111122223333";

    private final CognitoService cognitoService = mock(CognitoService.class);
    private final CognitoCustomDomainFilter filter = new CognitoCustomDomainFilter(cognitoService);

    @Test
    void rewritesAnOauthPathOnACustomDomainAndPinsThePool() {
        ContainerRequestContext request = request("POST", "http://" + DOMAIN + ":4566/oauth2/token?x=1", DOMAIN + ":4566");
        when(cognitoService.findCustomDomain(DOMAIN)).thenReturn(Optional.of(domain("us-east-1_abc")));

        filter.filter(request);

        verify(request).setProperty(CognitoCustomDomainFilter.POOL_PROPERTY, "us-east-1_abc");
        verify(request).setProperty(AccountContextFilter.PINNED_ACCOUNT_PROPERTY, ACCOUNT);
        verify(request).setRequestUri(URI.create("http://" + DOMAIN + ":4566/cognito-idp/oauth2/token?x=1"));
    }

    /** HTTP/2 carries no Host header; the authority is on the request URI. */
    @Test
    void fallsBackToTheRequestAuthorityWithoutAHostHeader() {
        ContainerRequestContext request = request("GET", "https://" + DOMAIN + "/oauth2/userInfo", null);
        when(cognitoService.findCustomDomain(DOMAIN)).thenReturn(Optional.of(domain("us-east-1_abc")));

        filter.filter(request);

        verify(request).setRequestUri(URI.create("https://" + DOMAIN + "/cognito-idp/oauth2/userInfo"));
    }

    @Test
    void rewritesManagedLoginPathsOnACustomDomainAndPinsThePool() {
        when(cognitoService.findCustomDomain(DOMAIN)).thenReturn(Optional.of(domain("us-east-1_abc")));
        for (String path : new String[] {"/login", "/logout"}) {
            ContainerRequestContext request = request("GET", "https://" + DOMAIN + path + "?client_id=c&state=a%26b", DOMAIN);

            filter.filter(request);

            verify(request).setProperty(CognitoCustomDomainFilter.POOL_PROPERTY, "us-east-1_abc");
            verify(request).setProperty(AccountContextFilter.PINNED_ACCOUNT_PROPERTY, ACCOUNT);
            verify(request).setRequestUri(URI.create("https://" + DOMAIN + "/cognito-idp" + path + "?client_id=c&state=a%26b"));
        }
    }

    @Test
    void leavesOtherPathsAlone() {
        for (String path : new String[] {"/cognito-idp/oauth2/token", "/login/", "/loginx", "/logout/x", "/signup"}) {
            ContainerRequestContext request = request("GET", "http://" + DOMAIN + path, DOMAIN);

            filter.filter(request);

            verify(request, never()).setRequestUri(any());
            verify(request, never()).setProperty(any(), any());
        }
        verifyNoInteractions(cognitoService);
    }

    /** Without a custom domain, /login is an S3 bucket path and /logout the SSO portal's. */
    @Test
    void leavesManagedLoginPathsOfOtherHostsAlone() {
        when(cognitoService.findCustomDomain("localhost")).thenReturn(Optional.empty());
        for (String path : new String[] {"/login", "/logout"}) {
            ContainerRequestContext request = request("GET", "http://localhost:4566" + path, "localhost:4566");

            filter.filter(request);

            verify(request, never()).setRequestUri(any());
            verify(request, never()).setProperty(any(), any());
        }
    }

    @Test
    void leavesUnknownHostsAlone() {
        when(cognitoService.findCustomDomain("nobody.localhost.floci.io")).thenReturn(Optional.empty());
        for (String path : new String[] {"/oauth2/token", "/oauth2/no-such-path"}) {
            ContainerRequestContext request = request("GET", "http://nobody.localhost.floci.io" + path,
                    "nobody.localhost.floci.io");

            filter.filter(request);

            verify(request, never()).setRequestUri(any());
            verify(request, never()).setProperty(any(), any());
            verify(request, never()).abortWith(any());
        }
    }

    @Test
    void rewritesEveryPathCognitoServesWithItsMethods() {
        when(cognitoService.findCustomDomain(DOMAIN)).thenReturn(Optional.of(domain("us-east-1_abc")));
        for (String route : new String[] {"POST /oauth2/token", "POST /oauth2/revoke", "GET /oauth2/authorize",
                "GET /oauth2/idpresponse", "GET /oauth2/userInfo", "POST /oauth2/userInfo", "GET /login",
                "POST /login", "GET /logout"}) {
            String[] parts = route.split(" ");
            ContainerRequestContext request = request(parts[0], "https://" + DOMAIN + parts[1], DOMAIN);

            filter.filter(request);

            verify(request).setRequestUri(URI.create("https://" + DOMAIN + "/cognito-idp" + parts[1]));
            verify(request, never()).abortWith(any());
        }
    }

    /** OPTIONS is left to the CORS filter and the existing routing, unchanged by the method check. */
    @Test
    void passesAPreflightToAServedPathThrough() {
        when(cognitoService.findCustomDomain(DOMAIN)).thenReturn(Optional.of(domain("us-east-1_abc")));
        ContainerRequestContext request = request("OPTIONS", "https://" + DOMAIN + "/oauth2/token", DOMAIN);

        filter.filter(request);

        verify(request).setRequestUri(URI.create("https://" + DOMAIN + "/cognito-idp/oauth2/token"));
        verify(request, never()).abortWith(any());
    }

    /** AWS's answer, which otherwise S3 would give, as a key of a bucket named cognito-idp. */
    @Test
    void answersAnOauthPathCognitoDoesNotServeWithAJsonNotFound() {
        when(cognitoService.findCustomDomain(DOMAIN)).thenReturn(Optional.of(domain("us-east-1_abc")));
        for (String path : new String[] {"/oauth2/no-such-path", "/oauth2/revoke/x", "/oauth2/", "/oauth2/Token"}) {
            ContainerRequestContext request = request("POST", "https://" + DOMAIN + path, DOMAIN);

            filter.filter(request);

            Response response = abortedWith(request);
            assertEquals(404, response.getStatus(), path);
            assertTrue(MediaType.APPLICATION_JSON_TYPE.isCompatible(response.getMediaType()), path);
            assertEquals("{\"error\":\"This URL doesn't exist on the authorization server.\"}", response.getEntity());
            verify(request, never()).setRequestUri(any());
        }
    }

    @Test
    void answersAServedPathCalledWithAnotherMethodWithAnEmptyMethodNotAllowed() {
        when(cognitoService.findCustomDomain(DOMAIN)).thenReturn(Optional.of(domain("us-east-1_abc")));
        for (String route : new String[] {"GET /oauth2/token POST", "GET /oauth2/revoke POST",
                "DELETE /oauth2/token POST", "POST /oauth2/authorize GET", "POST /oauth2/idpresponse GET",
                "DELETE /oauth2/userInfo GET, POST", "PUT /login GET, POST", "POST /logout GET"}) {
            String[] parts = route.split(" ", 3);
            ContainerRequestContext request = request(parts[0], "https://" + DOMAIN + parts[1], DOMAIN);

            filter.filter(request);

            Response response = abortedWith(request);
            assertEquals(405, response.getStatus(), route);
            assertEquals(parts[2], response.getHeaderString(HttpHeaders.ALLOW), route);
            assertNull(response.getEntity(), route);
            assertNull(response.getMediaType(), route);
            verify(request, never()).setRequestUri(any());
        }
    }

    private static Response abortedWith(ContainerRequestContext request) {
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        return response.getValue();
    }

    private static ContainerRequestContext request(String method, String uri, String hostHeader) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn(method);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create(uri));
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(request.getHeaderString("Host")).thenReturn(hostHeader);
        return request;
    }

    private static UserPoolDomain domain(String poolId) {
        UserPoolDomain domain = new UserPoolDomain();
        domain.setDomain(DOMAIN);
        domain.setUserPoolId(poolId);
        domain.setAwsAccountId(ACCOUNT);
        domain.setCertificateArn("arn:aws:acm:us-east-1:000000000000:certificate/abc");
        return domain;
    }
}
