package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.services.apigateway.model.BasePathMapping;
import io.github.hectorvent.floci.services.apigateway.model.CustomDomain;
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
 * Routes requests based on the Host header for API Gateway custom domains.
 *
 * <p>When a request arrives with a Host header matching a registered custom domain's
 * {@code regionalDomainName} (e.g., {@code my-domain.regional.local:4566}), this filter
 * resolves the corresponding base path mapping and rewrites the request URI to the
 * standard execute-api path format: {@code /execute-api/{apiId}/{stageName}/{path}}.
 *
 * <p>This enables users to invoke API Gateway APIs using custom domain names, matching
 * real AWS behavior where custom domains route to REST APIs via base path mappings.
 */
@Provider
@PreMatching
@Priority(10) // Run after Lambda URL filter (5) but before general processing
public class ApiGatewayCustomDomainFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(ApiGatewayCustomDomainFilter.class);
    private static final String REGIONAL_SUFFIX = ".regional.local";

    private final ApiGatewayService apiGatewayService;

    @Inject
    public ApiGatewayCustomDomainFilter(ApiGatewayService apiGatewayService) {
        this.apiGatewayService = apiGatewayService;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String host = requestContext.getHeaderString("Host");
        if (host == null) {
            return;
        }

        String hostname = stripPort(host);

        // Try matching by regionalDomainName first, then by bare domain name
        CustomDomain domain = null;
        if (hostname.endsWith(REGIONAL_SUFFIX)) {
            domain = apiGatewayService.findDomainByRegionalHostname(hostname);
        }
        if (domain == null) {
            domain = apiGatewayService.findDomainByName(hostname);
        }
        if (domain == null) {
            return;
        }

        URI originalUri = requestContext.getUriInfo().getRequestUri();
        String path = originalUri.getRawPath();
        if (path == null) {
            path = "/";
        }

        // Resolve the base path mapping for this domain + path
        BasePathMapping mapping = apiGatewayService.resolveBasePathMapping(domain.getDomainName(), path);
        if (mapping == null) {
            LOG.debugv("No base path mapping found for domain {0} path {1}", domain.getDomainName(), path);
            return;
        }

        String restApiId = mapping.getRestApiId();
        String stage = mapping.getStage();

        if (restApiId == null) {
            return;
        }

        // Strip the base path from the request path to get the remaining path
        String remainingPath = apiGatewayService.stripBasePath(path, mapping);
        if (remainingPath.startsWith("/")) {
            remainingPath = remainingPath.substring(1);
        }

        // Build the stage suffix — if no stage is configured on the mapping,
        // the request path's first segment after stripping the base path is NOT treated
        // as a stage name. In real AWS, a mapping without a stage means the API's
        // default stage is used. We use "$default" as the stage name to indicate this.
        String effectiveStage = (stage != null && !stage.isEmpty()) ? stage : "$default";

        // Rewrite to the standard execute-api path
        String newPath = "/execute-api/" + restApiId + "/" + effectiveStage + "/" + remainingPath;

        URI newUri = UriBuilder.fromUri(originalUri)
                .replacePath(newPath)
                .build();

        LOG.debugv("Custom domain routing: {0}{1} -> {2}", host, path, newUri.getPath());
        requestContext.setRequestUri(newUri);
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
