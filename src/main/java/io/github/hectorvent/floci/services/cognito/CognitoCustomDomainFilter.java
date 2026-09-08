package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AccountContextFilter;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Optional;

/**
 * Routes Cognito custom-domain requests by Host. On AWS a custom domain serves
 * {@code https://<domain>/oauth2/token} and {@code /oauth2/userInfo}; Floci maps those onto
 * the {@code /cognito-idp/oauth2/...} handlers and pins the pool and the account that own the
 * domain, since the request itself carries no AWS credential.
 */
@Provider
@PreMatching
@Priority(12) // after ApiGatewayCustomDomainFilter (10), before CloudFrontDistributionFilter (15)
public class CognitoCustomDomainFilter implements ContainerRequestFilter {

    /**
     * The id of the pool whose custom domain the request arrived on. A request property, not a
     * header, so a caller cannot supply it, and resolved once here so the controllers never
     * fail open if the domain is deleted between the filter and the handler.
     */
    public static final String POOL_PROPERTY = CognitoCustomDomainFilter.class.getName() + ".poolId";

    private static final Logger LOG = Logger.getLogger(CognitoCustomDomainFilter.class);
    private static final String OAUTH_PREFIX = "/oauth2/";
    private static final String TARGET_PREFIX = "/cognito-idp/oauth2/";

    private final CognitoService cognitoService;

    @Inject
    public CognitoCustomDomainFilter(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        URI originalUri = requestContext.getUriInfo().getRequestUri();
        String path = originalUri.getRawPath();
        if (path == null || !path.startsWith(OAUTH_PREFIX)) {
            return;
        }
        // HTTP/2 has no Host header; its :authority arrives as the request URI authority.
        String host = requestContext.getHeaderString("Host");
        if (host == null) {
            host = originalUri.getAuthority();
        }
        if (host == null) {
            return;
        }
        Optional<UserPoolDomain> domain = cognitoService.findCustomDomain(stripPort(host));
        if (domain.isEmpty()) {
            return;
        }

        URI newUri = UriBuilder.fromUri(originalUri)
                .replacePath(TARGET_PREFIX + path.substring(OAUTH_PREFIX.length()))
                .build();
        LOG.debugv("Cognito custom domain routing: {0}{1} -> {2}", host, path, newUri.getPath());
        requestContext.setProperty(POOL_PROPERTY, domain.get().getUserPoolId());
        requestContext.setProperty(AccountContextFilter.PINNED_ACCOUNT_PROPERTY, domain.get().getAwsAccountId());
        requestContext.setRequestUri(newUri);
    }

    private static String stripPort(String host) {
        int colonIndex = host.lastIndexOf(':');
        if (colonIndex > 0) {
            String maybePort = host.substring(colonIndex + 1);
            if (!maybePort.isEmpty() && maybePort.chars().allMatch(Character::isDigit)) {
                return host.substring(0, colonIndex);
            }
        }
        return host;
    }
}
