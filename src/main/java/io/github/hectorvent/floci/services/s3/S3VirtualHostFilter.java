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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Provider
@PreMatching
@ApplicationScoped
public class S3VirtualHostFilter implements ContainerRequestFilter {

    private final String baseHostname;

    /**
     * Hostname suffixes for which a bare {@code s3.<suffix>} Host header is Floci's own
     * S3 service endpoint (bucketless) rather than a bucket literally named {@code s3}.
     * Derived from the same source of truth the embedded DNS server uses: the always-on
     * builtins ({@code localhost.floci.io}, {@code localhost.localstack.cloud}) plus any
     * configured {@code floci.dns.extra-suffixes}, alongside plain {@code localhost}.
     * Stored lowercase for case-insensitive matching.
     */
    private final Set<String> serviceHostSuffixes;

    @Inject
    public S3VirtualHostFilter(EmulatorConfig config, ContainerDetector containerDetector) {
        this.baseHostname = config.hostname()
                .orElseGet(() -> containerDetector.isRunningInContainer()
                        ? EmbeddedDnsServer.DEFAULT_SUFFIX
                        : extractHostnameFromUrl(config.baseUrl()));
        this.serviceHostSuffixes = buildServiceHostSuffixes(config.dns().extraSuffixes());
    }

    S3VirtualHostFilter() {
        this.baseHostname = "localhost";
        this.serviceHostSuffixes = buildServiceHostSuffixes(Optional.empty());
    }

    /**
     * Builds the service-host suffix set from the DNS source of truth rather than
     * re-hardcoding it: {@code {"localhost"}} plus the {@link EmbeddedDnsServer} builtins
     * plus any configured extra suffixes.
     */
    private static Set<String> buildServiceHostSuffixes(Optional<List<String>> extraSuffixes) {
        Set<String> suffixes = new HashSet<>();
        suffixes.add("localhost");
        EmbeddedDnsServer.BUILTIN_SUFFIXES.forEach(s -> suffixes.add(s.toLowerCase()));
        extraSuffixes.ifPresent(list -> list.forEach(s -> suffixes.add(s.toLowerCase())));
        return Set.copyOf(suffixes);
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

        String bucket = extractBucket(host, baseHostname, serviceHostSuffixes);
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
    static String extractBucket(String host, String baseHostname, Set<String> serviceHostSuffixes) {
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

        if (isS3ServiceEndpointHost(firstLabel, remainder, baseHostname, serviceHostSuffixes)) {
            return null;
        }

        // Primary: remainder must match the configured base hostname,
        // either directly or in the AWS region-qualified s3.<region>.<host> form.
        if (baseHostname != null && matchesEndpointHost(remainder, baseHostname)) {
            return firstLabel;
        }

        // Fallback: well-known AWS S3 domains, for users who route AWS DNS to Floci
        if (isAwsS3Domain(remainder)) {
            return firstLabel;
        }

        // Configured Floci DNS suffixes (builtins + floci.dns.extra-suffixes): route
        // bucket.<suffix>, bucket.s3.<suffix> and bucket.s3.<region>.<suffix> the same
        // way the always-on wildcard-DNS builtins resolve.
        if (matchesConfiguredSuffixHost(remainder, serviceHostSuffixes)) {
            return firstLabel;
        }

        return null;
    }

    /**
     * Matches the virtual-hosted bucket forms for a configured DNS suffix {@code S}
     * (a builtin or a {@code floci.dns.extra-suffix}): {@code bucket.S},
     * {@code bucket.s3.S}, and the region-qualified {@code bucket.s3.<region>.S}.
     * Plain {@code localhost} is skipped here — it keeps its dedicated
     * {@link #matchesEndpointHost} handling via {@link #isAwsS3Domain}.
     */
    private static boolean matchesConfiguredSuffixHost(String remainder, Set<String> serviceHostSuffixes) {
        String lower = remainder.toLowerCase();
        for (String suffix : serviceHostSuffixes) {
            if ("localhost".equals(suffix)) {
                continue;
            }
            if (lower.equals(suffix) || (lower.startsWith("s3.") && lower.endsWith("." + suffix))) {
                return true;
            }
        }
        return false;
    }

    static boolean isS3ServiceEndpointHost(String firstLabel, String remainder, String baseHostname,
                                           Set<String> serviceHostSuffixes) {
        if (!"s3".equalsIgnoreCase(firstLabel)) {
            return false;
        }
        if (baseHostname != null && matchesEndpointHost(remainder, baseHostname)) {
            return true;
        }
        return serviceHostSuffixes.contains(remainder.toLowerCase());
    }

    /**
     * Matches a hostname directly or its region-qualified s3.&lt;region&gt;.&lt;hostname&gt; variant.
     * Example: with hostname="localhost", both "localhost" and "s3.us-east-1.localhost" match.
     */
    private static boolean matchesEndpointHost(String remainder, String hostname) {
        if (remainder.equalsIgnoreCase(hostname)) {
            return true;
        }
        String lowerRem = remainder.toLowerCase();
        String lowerHost = hostname.toLowerCase();
        String suffix = "." + lowerHost;
        if (lowerRem.startsWith("s3.") && lowerRem.endsWith(suffix)
                && lowerRem.length() > "s3.".length() + suffix.length()) {
            return true;
        }
        return false;
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

    /**
     * Returns true for well-known AWS S3 domains and the always-on {@code localhost}
     * endpoint. The Floci wildcard-DNS builtins ({@code localhost.floci.io},
     * {@code localhost.localstack.cloud}) and any configured {@code floci.dns.extra-suffixes}
     * are handled by {@link #matchesConfiguredSuffixHost} instead, so they stay derived
     * from the DNS source of truth rather than hardcoded here.
     */
    private static boolean isAwsS3Domain(String remainder) {
        if ("s3.amazonaws.com".equals(remainder)) {
            return true;
        }
        // s3.<region>.amazonaws.com
        if (remainder.startsWith("s3.") && remainder.endsWith(".amazonaws.com")) {
            return true;
        }
        // localhost always resolves to 127.0.0.1 — accept regardless of FLOCI_HOSTNAME config.
        // Fixes: SDK with endpointOverride("http://localhost:4566") without forcePathStyle sends
        // Host: my-bucket.localhost:4566, which must be recognized even when baseHostname=floci.
        // Also accept the region-qualified s3.<region>.localhost form (e.g. bucket.s3.us-east-1.localhost).
        if (matchesEndpointHost(remainder, "localhost")) {
            return true;
        }
        // S3 website endpoints: bucket.s3-website-<region>.amazonaws.com, bucket.s3-website-<region>.localhost, etc.
        if (remainder.startsWith("s3-website")) {
            return true;
        }
        return false;
    }
}
