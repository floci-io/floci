package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * Routes viewer requests addressed to a CloudFront distribution's domain to the distribution
 * request-serving controller.
 *
 * <p>When a request arrives with a {@code Host} header matching a distribution's assigned
 * CloudFront domain name ({@code <id>.cloudfront.net}) or one of its alternate domain names
 * (CNAME aliases), this filter rewrites the request URI to
 * {@code /_cloudfront/{distributionId}{rawPath}} so {@link CloudFrontServingController} can resolve
 * the matching origin and return the content — mirroring how CloudFront fronts an S3 or custom
 * origin.
 *
 * <p>Runs at priority 15: after the Lambda function URL (5) and API Gateway custom domain (10)
 * filters, but before the S3 virtual-host filter (default 5000), so a distribution or alias host
 * is not mistaken for an S3 virtual-hosted bucket.
 */
@Provider
@PreMatching
@Priority(15)
public class CloudFrontDistributionFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(CloudFrontDistributionFilter.class);

    private final CloudFrontService service;

    @Inject
    public CloudFrontDistributionFilter(CloudFrontService service) {
        this.service = service;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String host = requestContext.getHeaderString("Host");
        if (host == null) {
            return;
        }

        Distribution dist = service.findByHost(host);
        if (dist == null) {
            return;
        }

        URI originalUri = requestContext.getUriInfo().getRequestUri();
        String rawPath = originalUri.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            rawPath = "/";
        }

        // The serving controller lives at /_cloudfront/{distId}/{proxy:.*}; the leading slash from
        // rawPath supplies the separator (root "/" becomes /_cloudfront/{id}/).
        String newPath = "/_cloudfront/" + dist.getId() + rawPath;

        URI newUri = UriBuilder.fromUri(originalUri)
                .replacePath(newPath)
                .build();

        LOG.debugv("CloudFront distribution routing: {0}{1} -> {2}", host, rawPath, newUri.getPath());
        requestContext.setRequestUri(newUri);
    }
}
