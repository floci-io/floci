package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Global CORS support for browser-based local development.
 *
 * <p>Configured via {@code EXTRA_CORS_ALLOWED_ORIGINS} (or the equivalent
 * {@code FLOCI_SECURITY_EXTRA_CORS_ALLOWED_ORIGINS}). Service-level CORS
 * responses, such as S3 bucket CORS rules or API Gateway integrations, keep
 * precedence because this filter only fills missing CORS headers.</p>
 *
 * <p>Preflights that request Private Network Access (Chrome's
 * {@code Access-Control-Request-Private-Network}) are granted only when opted in
 * via {@code FLOCI_SECURITY_CORS_ALLOW_PRIVATE_NETWORK}, letting a UI served from a
 * public/secure origin reach a loopback backend.</p>
 */
@Provider
@PreMatching
@Priority(Priorities.HEADER_DECORATOR)
public class GlobalCorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String ORIGIN = "Origin";
    private static final String REQUEST_METHOD = "Access-Control-Request-Method";
    private static final String REQUEST_HEADERS = "Access-Control-Request-Headers";
    private static final String REQUEST_PRIVATE_NETWORK = "Access-Control-Request-Private-Network";
    private static final String ALLOW_PRIVATE_NETWORK = "Access-Control-Allow-Private-Network";
    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    private static final String VARY = "Vary";
    private static final String MAX_AGE = "Access-Control-Max-Age";
    private static final String DEFAULT_ALLOW_METHODS = "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS";
    private static final String DEFAULT_ALLOW_HEADERS = String.join(", ",
            "authorization",
            "content-type",
            "x-amz-content-sha256",
            "x-amz-date",
            "x-amz-security-token",
            "x-amz-target",
            "x-amz-user-agent");

    private final jakarta.inject.Provider<EmulatorConfig> configProvider;
    private volatile CorsSettings settings;

    @Inject
    public GlobalCorsFilter(jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.configProvider = configProvider;
    }

    private CorsSettings settings() {
        CorsSettings s = settings;
        if (s == null) {
            synchronized (this) {
                s = settings;
                if (s == null) {
                    s = settings = CorsSettings.from(configProvider.get().security());
                }
            }
        }
        return s;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        CorsSettings s = settings();
        if (!s.enabled()) {
            return;
        }

        String origin = requestContext.getHeaderString(ORIGIN);
        if (origin == null || !s.matchesOrigin(origin)) {
            return;
        }

        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())
                && requestContext.getHeaderString(REQUEST_METHOD) != null) {
            Response.ResponseBuilder builder = Response.noContent()
                    .type(MediaType.TEXT_PLAIN_TYPE)
                    .header(ALLOW_ORIGIN, s.allowOriginHeader(origin))
                    .header(ALLOW_METHODS, DEFAULT_ALLOW_METHODS)
                    .header(ALLOW_HEADERS, s.allowHeaders(requestContext.getHeaderString(REQUEST_HEADERS)))
                    .header(MAX_AGE, "86400")
                    .header(VARY, ORIGIN);

            // Private Network Access: a public/secure page (e.g. an HTTPS-hosted UI)
            // probing a loopback/private-network backend sends this on the preflight;
            // grant it (unless disabled) so the browser doesn't block the follow-up request.
            if (s.allowPrivateNetwork()
                    && "true".equalsIgnoreCase(requestContext.getHeaderString(REQUEST_PRIVATE_NETWORK))) {
                builder.header(ALLOW_PRIVATE_NETWORK, "true");
            }

            s.exposeHeaders().ifPresent(value -> builder.header(EXPOSE_HEADERS, value));
            requestContext.abortWith(builder.build());
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        CorsSettings s = settings();
        if (!s.enabled()) {
            return;
        }

        String origin = requestContext.getHeaderString(ORIGIN);
        if (origin == null || !s.matchesOrigin(origin)) {
            return;
        }

        MultivaluedMap<String, Object> headers = responseContext.getHeaders();
        headers.putIfAbsent(ALLOW_ORIGIN, List.of(s.allowOriginHeader(origin)));
        s.exposeHeaders().ifPresent(value -> headers.putIfAbsent(EXPOSE_HEADERS, List.of(value)));
        addVaryOrigin(headers);
    }

    private static void addVaryOrigin(MultivaluedMap<String, Object> headers) {
        boolean hasOrigin = Optional.ofNullable(headers.get(VARY))
                .orElse(List.of())
                .stream()
                .flatMap(value -> Arrays.stream(String.valueOf(value).split(",")))
                .map(String::trim)
                .anyMatch(ORIGIN::equalsIgnoreCase);
        if (!hasOrigin) {
            headers.add(VARY, ORIGIN);
        }
    }

    private record CorsSettings(boolean disabled,
                                Set<String> allowedOrigins,
                                Set<String> extraAllowedHeaders,
                                Set<String> extraExposeHeaders,
                                boolean allowPrivateNetwork) {

        static CorsSettings from(EmulatorConfig.SecurityConfig security) {
            Set<String> origins = security.extraCorsAllowedOrigins()
                    .map(list -> (Set<String>) new LinkedHashSet<>(list))
                    .orElse(Set.of());
            Set<String> allowedHeaders = security.extraCorsAllowedHeaders()
                    .map(list -> (Set<String>) new LinkedHashSet<>(list))
                    .orElse(Set.of());
            Set<String> exposeHeaders = security.extraCorsExposeHeaders()
                    .map(list -> (Set<String>) new LinkedHashSet<>(list))
                    .orElse(Set.of());
            return new CorsSettings(security.disableCorsHeaders(), origins, allowedHeaders, exposeHeaders,
                    security.corsAllowPrivateNetwork());
        }

        boolean enabled() {
            return !disabled && !allowedOrigins.isEmpty();
        }

        boolean matchesOrigin(String origin) {
            return allowedOrigins.contains("*")
                    || allowedOrigins.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(origin));
        }

        String allowOriginHeader(String origin) {
            return allowedOrigins.contains("*") ? "*" : origin;
        }

        String allowHeaders(String requestedHeaders) {
            LinkedHashSet<String> headers = new LinkedHashSet<>();
            headers.addAll(splitCsv(DEFAULT_ALLOW_HEADERS));
            headers.addAll(extraAllowedHeaders);
            headers.addAll(splitCsv(requestedHeaders));
            return String.join(", ", headers);
        }

        Optional<String> exposeHeaders() {
            return extraExposeHeaders.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", extraExposeHeaders));
        }

        private static LinkedHashSet<String> splitCsv(String value) {
            if (value == null || value.isBlank()) {
                return new LinkedHashSet<>();
            }
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(part -> !part.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }
}
