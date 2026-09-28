package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RequestHost;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;

/**
 * Routes requests based on the Host header for Lambda Function URLs.
 *
 * Rewrites http://<urlId>.lambda-url.<region>.localhost:4566/path
 * to /lambda-url/<urlId>/path
 */
@Provider
@PreMatching
@Priority(5) // Run early
public class LambdaUrlRoutingFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(LambdaUrlRoutingFilter.class);

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String host = RequestHost.of(requestContext);
        if (host == null) return;

        // Pattern: <urlId>.lambda-url.<region>.<anything>
        if (host.contains(".lambda-url.")) {
            String[] parts = host.split("\\.");
            if (parts.length >= 3) {
                String urlId = parts[0];
                // We don't strictly need region here because urlId is enough to find the target,
                // but we could extract it if needed.

                URI originalUri = requestContext.getUriInfo().getRequestUri();
                String path = originalUri.getRawPath();
                if (path == null) path = "/";

                URI newUri = UriBuilder.fromUri(originalUri)
                        .host("localhost") // Normalize host
                        .replacePath("/lambda-url/" + urlId + path)
                        .build();

                LOG.debugv("Routing Lambda URL: {0} -> {1}", host, newUri.getPath());
                requestContext.setRequestUri(newUri);
            }
        }
    }
}
