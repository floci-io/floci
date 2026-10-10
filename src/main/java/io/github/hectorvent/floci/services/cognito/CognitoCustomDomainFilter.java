package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AccountContextFilter;
import io.github.hectorvent.floci.core.common.RequestHost;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Routes Cognito custom-domain requests by Host. On AWS a custom domain serves
 * {@code https://<domain>/oauth2/token}, {@code /oauth2/revoke}, {@code /oauth2/userInfo},
 * {@code /oauth2/authorize}, {@code /oauth2/idpresponse}, and managed login's {@code /login} and
 * {@code /logout}; Floci maps those onto the {@code /cognito-idp/...} handlers and pins the pool and
 * the account that own the domain, since the request itself carries no AWS credential. Any other
 * path under {@code /oauth2/} is answered here with AWS's JSON 404, and a served path called with a
 * method it does not serve with an empty 405, so neither reaches S3 as a key of a bucket named
 * {@code cognito-idp}. On any other host these paths are left alone, so {@code /login} stays an S3
 * bucket path and {@code /logout} the SSO portal's.
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
    private static final Set<String> MANAGED_LOGIN_PATHS = Set.of("/login", "/logout");
    private static final String TARGET_PREFIX = "/cognito-idp";
    /** The paths the Cognito handlers serve on a custom domain, and their methods. */
    private static final Map<String, List<String>> SERVED_ROUTES = Map.of(
            "/oauth2/token", List.of(HttpMethod.POST),
            "/oauth2/revoke", List.of(HttpMethod.POST),
            "/oauth2/authorize", List.of(HttpMethod.GET),
            "/oauth2/idpresponse", List.of(HttpMethod.GET),
            "/oauth2/userInfo", List.of(HttpMethod.GET, HttpMethod.POST),
            "/login", List.of(HttpMethod.GET, HttpMethod.POST),
            "/logout", List.of(HttpMethod.GET));
    /** AWS's answer to a path under /oauth2/ that its authorization server does not serve. */
    private static final String NOT_FOUND_BODY = "{\"error\":\"This URL doesn't exist on the authorization server.\"}";

    private final CognitoService cognitoService;

    @Inject
    public CognitoCustomDomainFilter(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        URI originalUri = requestContext.getUriInfo().getRequestUri();
        String path = originalUri.getRawPath();
        if (path == null || !(path.startsWith(OAUTH_PREFIX) || MANAGED_LOGIN_PATHS.contains(path))) {
            return;
        }
        String host = RequestHost.of(requestContext);
        if (host == null) {
            return;
        }
        Optional<UserPoolDomain> domain = cognitoService.findCustomDomain(stripPort(host));
        if (domain.isEmpty()) {
            return;
        }
        List<String> methods = SERVED_ROUTES.get(path);
        if (methods == null) {
            requestContext.abortWith(Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON_TYPE.withCharset("UTF-8"))
                    .entity(NOT_FOUND_BODY)
                    .build());
            return;
        }
        // OPTIONS is left to the CORS filter and the existing routing, unchanged by this method check.
        String method = requestContext.getMethod();
        if (!methods.contains(method) && !HttpMethod.OPTIONS.equals(method)) {
            requestContext.abortWith(Response.status(Response.Status.METHOD_NOT_ALLOWED)
                    .header(HttpHeaders.ALLOW, String.join(", ", methods))
                    .build());
            return;
        }

        URI newUri = UriBuilder.fromUri(originalUri)
                .replacePath(TARGET_PREFIX + path)
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
