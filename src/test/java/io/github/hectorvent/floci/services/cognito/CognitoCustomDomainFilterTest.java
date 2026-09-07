package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AccountContextFilter;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

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
        ContainerRequestContext request = request("http://" + DOMAIN + ":4566/oauth2/token?x=1", DOMAIN + ":4566");
        when(cognitoService.findCustomDomain(DOMAIN)).thenReturn(Optional.of(domain("us-east-1_abc")));

        filter.filter(request);

        verify(request).setProperty(CognitoCustomDomainFilter.POOL_PROPERTY, "us-east-1_abc");
        verify(request).setProperty(AccountContextFilter.PINNED_ACCOUNT_PROPERTY, ACCOUNT);
        verify(request).setRequestUri(URI.create("http://" + DOMAIN + ":4566/cognito-idp/oauth2/token?x=1"));
    }

    /** HTTP/2 carries no Host header; the authority is on the request URI. */
    @Test
    void fallsBackToTheRequestAuthorityWithoutAHostHeader() {
        ContainerRequestContext request = request("https://" + DOMAIN + "/oauth2/userInfo", null);
        when(cognitoService.findCustomDomain(DOMAIN)).thenReturn(Optional.of(domain("us-east-1_abc")));

        filter.filter(request);

        verify(request).setRequestUri(URI.create("https://" + DOMAIN + "/cognito-idp/oauth2/userInfo"));
    }

    @Test
    void leavesOtherPathsAlone() {
        ContainerRequestContext request = request("http://" + DOMAIN + "/cognito-idp/oauth2/token", DOMAIN);

        filter.filter(request);

        verifyNoInteractions(cognitoService);
        verify(request, never()).setRequestUri(any());
        verify(request, never()).setProperty(any(), any());
    }

    @Test
    void leavesUnknownHostsAlone() {
        ContainerRequestContext request = request("http://nobody.localhost.floci.io/oauth2/token", "nobody.localhost.floci.io");
        when(cognitoService.findCustomDomain("nobody.localhost.floci.io")).thenReturn(Optional.empty());

        filter.filter(request);

        verify(request, never()).setRequestUri(any());
        verify(request, never()).setProperty(any(), any());
    }

    private static ContainerRequestContext request(String uri, String hostHeader) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
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
