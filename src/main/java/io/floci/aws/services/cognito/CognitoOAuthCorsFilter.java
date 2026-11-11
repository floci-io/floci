package io.floci.aws.services.cognito;

import io.floci.aws.services.s3.S3VirtualHostFilter;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CORS on the endpoints a browser app calls with {@code fetch}: token, revoke and userInfo. AWS
 * allows any Origin there, with credentials, on success and error alike, and answers the preflight
 * itself; authorize and logout, which a browser navigates to, carry no CORS headers. Runs after
 * {@link CognitoCustomDomainFilter} has rewritten a custom-domain request onto these paths, after
 * {@link S3VirtualHostFilter} has marked a bucket host's requests, which keep S3's CORS like any
 * method Cognito does not serve there; after an opted-in {@code GlobalCorsFilter}, whose preflight
 * answer keeps its Private Network Access grant; and before matching, so S3's
 * {@code OPTIONS /{bucket}/{key}} route never refuses the preflight.
 */
@Provider
@PreMatching
@Priority(Priorities.USER + 1) // after S3VirtualHostFilter (USER), whose marker leaves a bucket host's requests to S3
public class CognitoOAuthCorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    /** The method and path of each request Cognito serves; any other there falls through to S3's object routes. */
    private static final Set<String> CORS_ROUTES = Set.of(
            "POST /cognito-idp/oauth2/token",
            "POST /cognito-idp/oauth2/revoke",
            "GET /cognito-idp/oauth2/userInfo",
            "POST /cognito-idp/oauth2/userInfo");
    private static final String REQUEST_METHOD = "Access-Control-Request-Method";
    private static final String REQUEST_HEADERS = "Access-Control-Request-Headers";
    private static final List<Object> VARY = List.of("Origin", REQUEST_METHOD, REQUEST_HEADERS);

    @Override
    public void filter(ContainerRequestContext request) {
        if (!HttpMethod.OPTIONS.equals(request.getMethod()) || corsOrigin(request) == null) {
            return;
        }
        request.abortWith(Response.ok()
                .header("Access-Control-Allow-Methods", request.getHeaderString(REQUEST_METHOD))
                .header("Access-Control-Allow-Headers", requestedHeaders(request))
                .header("Access-Control-Max-Age", "1800")
                .build());
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

    /** The Origin of a request Cognito serves, or of a preflight for one; null for S3's, which keep S3's CORS. */
    private static String corsOrigin(ContainerRequestContext request) {
        if (request.getProperty(S3VirtualHostFilter.ORIGINAL_REQUEST_URI_PROPERTY) != null) {
            return null;
        }
        String method = HttpMethod.OPTIONS.equals(request.getMethod())
                ? request.getHeaderString(REQUEST_METHOD)
                : request.getMethod();
        if (HttpMethod.HEAD.equals(method) && request.getProperty(CognitoCustomDomainFilter.POOL_PROPERTY) != null) {
            // The custom-domain filter serves HEAD with the GET handler.
            method = HttpMethod.GET;
        }
        return CORS_ROUTES.contains(method + " " + request.getUriInfo().getPath()) ? request.getHeaderString("Origin") : null;
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
