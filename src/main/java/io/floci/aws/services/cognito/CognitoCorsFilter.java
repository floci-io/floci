package io.floci.aws.services.cognito;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsProtocolClaimFilter;
import io.floci.aws.core.common.MemoizedSupplier;
import io.floci.aws.core.common.ProtocolClaim;
import io.floci.aws.core.common.WireProtocol;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Cognito exposed headers for browser origins admitted by the global CORS policy. */
@Provider
@Priority(Priorities.HEADER_DECORATOR + 1)
public class CognitoCorsFilter implements ContainerResponseFilter {

    private static final String EXPOSE_HEADERS = "x-amzn-RequestId,x-amzn-ErrorType,x-amzn-ErrorMessage,Date";

    private final MemoizedSupplier<EmulatorConfig> config;

    @Inject
    public CognitoCorsFilter(jakarta.inject.Provider<EmulatorConfig> configProvider) {
        // Qualify the CDI Provider to distinguish it from the JAX-RS annotation.
        config = new MemoizedSupplier<>(configProvider::get);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (!isBrowserRootRequest(requestContext)
                || !(requestContext.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim)
                || claim.protocol() != WireProtocol.AWS_JSON_1_1 || claim.service() == null
                || !"cognito-idp".equals(claim.service().externalKey())
                || !enabledForOrigin(requestContext.getHeaderString("Origin"))) {
            return;
        }

        mergeExposedHeaders(responseContext.getHeaders());
    }

    private boolean enabledForOrigin(String origin) {
        EmulatorConfig config = this.config.get();
        EmulatorConfig.SecurityConfig security = config.security();
        return config.services().cognito().enabled() && !security.disableCorsHeaders()
                && security.extraCorsAllowedOrigins().orElse(List.of()).stream()
                .anyMatch(allowed -> "*".equals(allowed) || allowed.equalsIgnoreCase(origin));
    }

    private void mergeExposedHeaders(MultivaluedMap<String, Object> headers) {
        Set<String> exposedHeaders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        addHeaderNames(exposedHeaders, EXPOSE_HEADERS);
        for (Object value : headers.getOrDefault("Access-Control-Expose-Headers", List.of())) {
            addHeaderNames(exposedHeaders, String.valueOf(value));
        }

        EmulatorConfig.SecurityConfig security = config.get().security();
        security.extraCorsExposeHeaders().ifPresent(values ->
                values.forEach(value -> addHeaderNames(exposedHeaders, value)));
        headers.putSingle("Access-Control-Expose-Headers", String.join(", ", exposedHeaders));
    }

    private static void addHeaderNames(Set<String> names, String value) {
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .forEach(names::add);
    }

    private static boolean isBrowserRootRequest(ContainerRequestContext requestContext) {
        String origin = requestContext.getHeaderString("Origin");
        String path = requestContext.getUriInfo().getPath();
        return origin != null && !origin.isBlank() && (path.isEmpty() || "/".equals(path));
    }
}
