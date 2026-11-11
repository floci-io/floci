package io.floci.aws.services.cognito;

import jakarta.annotation.Priority;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CORS on the endpoints a browser app calls with {@code fetch}: token, revoke and userInfo. AWS
 * allows any Origin there, with credentials, on success and error alike, and answers the preflight
 * itself; authorize and logout, which a browser navigates to, carry no CORS headers. Runs after
 * {@link CognitoCustomDomainFilter} has rewritten a custom-domain request onto these paths, after
 * {@code S3VirtualHostFilter} has moved a bucket's requests off them, and after an opted-in
 * {@code GlobalCorsFilter}, whose preflight answer keeps its Private Network Access grant; and
 * before matching, so S3's {@code OPTIONS /{bucket}/{key}} route never refuses the preflight.
 */
@Provider
@PreMatching
@Priority(Priorities.USER + 1) // after S3VirtualHostFilter (USER), which moves a bucket's requests elsewhere
public class CognitoOAuthCorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    /** The methods Cognito serves on each path; any other method there falls through to S3's object routes. */
    private static final Map<String, Set<String>> CORS_ROUTES = Map.of(
            "/cognito-idp/oauth2/token", Set.of(HttpMethod.POST, HttpMethod.OPTIONS),
            "/cognito-idp/oauth2/revoke", Set.of(HttpMethod.POST, HttpMethod.OPTIONS),
            "/cognito-idp/oauth2/userInfo", Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.OPTIONS));
    private static final String REQUEST_METHOD = "Access-Control-Request-Method";
    private static final String REQUEST_HEADERS = "Access-Control-Request-Headers";
    private static final List<Object> VARY = List.of("Origin", REQUEST_METHOD, REQUEST_HEADERS);

    @Override
    public void filter(ContainerRequestContext request) {
        if (!HttpMethod.OPTIONS.equals(request.getMethod()) || corsOrigin(request) == null) {
            return;
        }
        Response.ResponseBuilder preflight = Response.ok();
        String method = request.getHeaderString(REQUEST_METHOD);
        if (method != null) {
            preflight.header("Access-Control-Allow-Methods", method)
                    .header("Access-Control-Allow-Headers", requestedHeaders(request))
                    .header("Access-Control-Max-Age", "1800");
        }
        request.abortWith(preflight.build());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String origin = corsOrigin(request);
        if (origin == null) {
            return;
        }
        MultivaluedMap<String, Object> headers = response.getHeaders();
        headers.putSingle("Access-Control-Allow-Origin", origin);
        headers.putSingle("Access-Control-Allow-Credentials", "true");
        headers.put("Vary", new ArrayList<>(VARY));
    }

    private static String corsOrigin(ContainerRequestContext request) {
        Set<String> methods = CORS_ROUTES.get(request.getUriInfo().getPath());
        return methods != null && methods.contains(request.getMethod()) ? request.getHeaderString("Origin") : null;
    }

    /** The requested headers as AWS echoes them, trimmed and comma-separated, or null, which leaves the header out. */
    private static String requestedHeaders(ContainerRequestContext request) {
        String requested = request.getHeaderString(REQUEST_HEADERS);
        if (requested == null || requested.isBlank()) {
            return null;
        }
        return Arrays.stream(requested.split(","))
                .map(String::trim)
                .filter(header -> !header.isEmpty())
                .collect(Collectors.joining(", "));
    }
}
