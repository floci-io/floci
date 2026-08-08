package io.github.hectorvent.floci.services.apigateway;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * Routes requests with an execute-api subdomain host to the API Gateway execute controller.
 *
 * <p>AWS SDKs address API Gateway APIs via
 * {@code {apiId}.execute-api.{region}.amazonaws.com/{stage}/{path}}.
 * This filter recognises the local equivalent
 * ({@code {apiId}.execute-api.localhost:4566/{stage}/{path}}) and rewrites the path to
 * {@code /execute-api/{apiId}/{stage}/{path}} so that {@link ApiGatewayExecuteController}
 * can handle it.
 *
 * <p>Without this filter, requests like {@code POST /@connections/{connectionId}} are
 * intercepted by the S3 controller, which interprets {@code @connections} as a bucket name.
 */
@Provider
@PreMatching
@Priority(10)
public class ApiGatewayExecuteApiFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(ApiGatewayExecuteApiFilter.class);
    private static final String EXECUTE_API_LABEL = "execute-api";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String host = requestContext.getHeaderString("Host");
        if (host == null) {
            return;
        }

        String apiId = extractApiId(host);
        if (apiId == null) {
            return;
        }

        URI uri = requestContext.getUriInfo().getRequestUri();
        String path = uri.getRawPath();

        String newPath = "/execute-api/" + apiId + (path.startsWith("/") ? path : "/" + path);

        URI newUri = UriBuilder.fromUri(uri)
                .replacePath(newPath)
                .build();

        LOG.debugv("Execute-api subdomain routing: {0}{1} -> {2}", host, path, newUri.getPath());
        requestContext.setRequestUri(newUri);
    }

    /**
     * Extracts the API ID from an execute-api subdomain host.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code abc123.execute-api.localhost:4566} → {@code abc123}</li>
     *   <li>{@code abc123.execute-api.us-east-1.localhost:4566} → {@code abc123}</li>
     *   <li>{@code abc123.execute-api.us-east-1.amazonaws.com} → {@code abc123}</li>
     *   <li>{@code localhost:4566} → {@code null}</li>
     *   <li>{@code my-bucket.localhost:4566} → {@code null}</li>
     * </ul>
     */
    static String extractApiId(String host) {
        if (host == null) {
            return null;
        }

        String hostname = stripPort(host);

        // Need {apiId}.execute-api.{something} — at least two dots
        int firstDot = hostname.indexOf('.');
        if (firstDot <= 0) {
            return null;
        }

        String apiId = hostname.substring(0, firstDot);
        String remainder = hostname.substring(firstDot + 1);

        // remainder must start with "execute-api."
        if (!remainder.toLowerCase().startsWith(EXECUTE_API_LABEL + ".")) {
            return null;
        }

        return apiId;
    }

    private static String stripPort(String host) {
        int colonIndex = host.lastIndexOf(':');
        if (colonIndex > 0) {
            String maybePart = host.substring(colonIndex + 1);
            if (!maybePart.isEmpty() && maybePart.chars().allMatch(Character::isDigit)) {
                return host.substring(0, colonIndex);
            }
        }
        return host;
    }
}
