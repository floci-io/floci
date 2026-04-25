package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

@Provider
@PreMatching
@ApplicationScoped
public class S3VirtualHostFilter implements ContainerRequestFilter {

    private final String baseHostname;

    @Inject
    public S3VirtualHostFilter(EmulatorConfig config, ContainerDetector containerDetector) {
        this.baseHostname = config.hostname()
                .orElseGet(() -> containerDetector.isRunningInContainer()
                        ? EmbeddedDnsServer.DEFAULT_SUFFIX
                        : extractHostnameFromUrl(config.baseUrl()));
    }

    S3VirtualHostFilter() {
        this.baseHostname = "localhost";
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String host = requestContext.getHeaderString("Host");
        if (host == null) return;

        // Do not hijack requests meant for other AWS services
        String auth = requestContext.getHeaderString("Authorization");
        if (auth != null && auth.contains("Credential=") && !auth.contains("/s3/aws4_request")) {
            return;
        }

        // S3 does not use these content types for bucket/object operations,
        // but other AWS services (AwsQuery, JSON protocols) do.
        String contentType = requestContext.getHeaderString("Content-Type");
        if (contentType != null && (
                contentType.startsWith("application/x-www-form-urlencoded") ||
                contentType.startsWith("application/x-amz-json-"))) {
            return;
        }

        String bucket = extractBucket(host, baseHostname);
        if (bucket == null) return;

        URI uri = requestContext.getUriInfo().getRequestUri();
        String path = uri.getRawPath();

        // Do not rewrite S3 Control API paths — the account ID appears as a host label
        // in the S3ControlClient but the path belongs to the S3 Control service, not S3.
        if (path.startsWith("/v20180820/")) {
            return;
        }

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
     * A request is considered virtual-hosted-style when the hostname's remainder
     * after the first label matches the configured Floci base hostname, or when it
     * matches a well-known AWS S3 domain pattern (for DNS-redirect setups).
     *
     * Examples with baseHostname="localhost":
     *   my-bucket.localhost:4566       -> "my-bucket"
     *   my-bucket.localhost            -> "my-bucket"
     *   floci.svc.cluster.local        -> null  (no bucket prefix, path-style)
     *   my-svc.floci.svc.cluster.local -> null  (remainder doesn't match "localhost")
     *
     * Examples with baseHostname="floci.svc.cluster.local":
     *   my-bucket.floci.svc.cluster.local -> "my-bucket"
     *   floci.svc.cluster.local           -> null  (no bucket prefix, path-style)
     *
     * Returns null if the host does not match a virtual-hosted pattern.
     */
    static String extractBucket(String host, String baseHostname) {
        if (host == null) {
            return null;
        }

        // Strip port if present
        String hostname = stripPort(host);

        // Need at least one dot for a subdomain to exist
        int firstDot = hostname.indexOf('.');
        if (firstDot <= 0) {
            return null;
        }

        // Skip IPv4 addresses (e.g., 192.168.1.1)
        if (isIpv4Address(hostname)) {
            return null;
        }

        String firstLabel = hostname.substring(0, firstDot);
        String remainder  = hostname.substring(firstDot + 1);

        // Primary: remainder must match the configured base hostname
        if (baseHostname != null && remainder.equalsIgnoreCase(baseHostname)) {
            return firstLabel;
        }

        // Fallback: well-known AWS S3 domains, for users who route AWS DNS to Floci
        if (isAwsS3Domain(remainder)) {
            return firstLabel;
        }

        return null;
    }

    /** Extracts the hostname (without scheme or port) from a URL string. */
    static String extractHostnameFromUrl(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
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

    private static boolean isIpv4Address(String hostname) {
        for (int i = 0; i < hostname.length(); i++) {
            char c = hostname.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    /** Returns true for *.s3.amazonaws.com and other well-known S3 domains. */
    private static boolean isAwsS3Domain(String remainder) {
        if ("s3.amazonaws.com".equals(remainder)) {
            return true;
        }
        // s3.<region>.amazonaws.com
        if (remainder.startsWith("s3.") && remainder.endsWith(".amazonaws.com")) {
            return true;
        }
        // Support localstack.cloud subdomains (used by cdklocal and other tools)
        // Example: bucket.s3.localhost.localstack.cloud
        if (remainder.endsWith(".localstack.cloud")) {
            return remainder.startsWith("s3.");
        }
        return false;
    }
}
