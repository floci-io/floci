package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import java.net.URI;

@Provider
@PreMatching
public class S3VirtualHostFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String host = requestContext.getHeaderString("Host");
        if (host == null) return;

        String bucket = extractBucket(host);
        if (bucket == null) return;

        URI uri = requestContext.getUriInfo().getRequestUri();
        String path = uri.getRawPath();

        // Rewrite path from /key to /bucket/key
        String newPath = "/" + bucket + (path.startsWith("/") ? "" : "/") + path;

        URI newUri = UriBuilder.fromUri(uri)
                .replacePath(newPath)
                .build();

        requestContext.setRequestUri(newUri);
    }

    /**
     * Extracts a bucket name from a virtual-hosted-style Host header.
     *
     * The first label of the hostname (before the first dot) is treated as the
     * bucket name whenever the hostname contains at least one dot and is not an
     * IP address. This works for any endpoint hostname — localhost, custom hosts,
     * or S3-style domains — without requiring configuration.
     *
     * Note: AWS SDKs automatically fall back to path-style for bucket names that
     * contain dots, so the first-label heuristic is sufficient.
     *
     * Returns null if the host does not match a virtual-hosted pattern.
     */
    static String extractBucket(String host) {
        if (host == null) return null;

        // Strip port if present
        String hostname = host;
        int colonIndex = hostname.lastIndexOf(':');
        if (colonIndex > 0) {
            String maybePart = hostname.substring(colonIndex + 1);
            if (!maybePart.isEmpty() && maybePart.chars().allMatch(Character::isDigit)) {
                hostname = hostname.substring(0, colonIndex);
            }
        }

        // Need at least one dot for a subdomain to exist
        int firstDot = hostname.indexOf('.');
        if (firstDot <= 0) {
            return null;
        }

        // Skip IPv4 addresses (e.g., 192.168.1.1)
        if (isIpv4Address(hostname)) {
            return null;
        }

        return hostname.substring(0, firstDot);
    }

    private static boolean isIpv4Address(String hostname) {
        for (int i = 0; i < hostname.length(); i++) {
            char c = hostname.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }
}
