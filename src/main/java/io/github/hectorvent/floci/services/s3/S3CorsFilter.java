package io.github.hectorvent.floci.services.s3;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Adds CORS response headers to actual (non-preflight) S3 requests when the
 * request carries an {@code Origin} header and a matching CORS rule exists for
 * the target bucket.
 *
 * <p>Preflight ({@code OPTIONS}) requests are handled directly by the dedicated
 * endpoint methods in {@link S3Controller} and are intentionally skipped here.
 */
@Provider
public class S3CorsFilter implements ContainerResponseFilter {

    @Inject
    S3Service s3Service;

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {

        // Only handle actual requests — preflights are processed by the OPTIONS endpoints
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) return;

        String origin = requestContext.getHeaderString("Origin");
        if (origin == null || origin.isBlank()) return;

        String bucket = extractBucket(requestContext.getUriInfo().getPath());
        if (bucket == null) return;

        Optional<S3Service.CorsEvalResult> evalResult =
                s3Service.evaluateCors(bucket, origin, requestContext.getMethod(), List.of());
        if (evalResult.isEmpty()) return;

        S3Service.CorsEvalResult cors = evalResult.get();
        MultivaluedMap<String, Object> headers = responseContext.getHeaders();

        // putSingle replaces any value already set by a resource method or earlier filter,
        // preventing duplicate Access-Control-Allow-Origin / Expose-Headers entries.
        headers.putSingle("Access-Control-Allow-Origin", cors.allowedOrigin());

        // Merge "Origin" into Vary without duplicating it; Vary may already carry other
        // tokens (e.g. "Accept-Encoding") added by the JAX-RS runtime or other filters.
        boolean varyHasOrigin = Optional.ofNullable(headers.get("Vary"))
                .orElse(List.of())
                .stream()
                .anyMatch(v -> Arrays.stream(v.toString().split(","))
                        .map(String::trim)
                        .anyMatch("Origin"::equalsIgnoreCase));
        if (!varyHasOrigin) {
            headers.add("Vary", "Origin");
        }

        if (!cors.exposeHeaders().isEmpty()) {
            headers.putSingle("Access-Control-Expose-Headers", String.join(", ", cors.exposeHeaders()));
        }
    }

    /**
     * Extracts the bucket name from a JAX-RS request path such as
     * {@code /my-bucket} or {@code /my-bucket/some/key.txt}.
     */
    private static String extractBucket(String path) {
        if (path == null || path.isEmpty()) return null;
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        String bucket = slash > 0 ? p.substring(0, slash) : p;
        return bucket.isEmpty() ? null : bucket;
    }
}