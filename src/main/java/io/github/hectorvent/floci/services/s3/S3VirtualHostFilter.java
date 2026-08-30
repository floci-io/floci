package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontDistributionFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.Arrays;
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
     * builtins ({@code localhost.floci.io}, {@code localhost.localstack.cloud}) plus the
     * configured {@code floci.hostname} and any {@code floci.dns.extra-suffixes},
     * alongside plain {@code localhost}. Stored lowercase for case-insensitive matching.
     */
    private final Set<String> serviceHostSuffixes;

    /**
     * Host labels belonging to other AWS services that virtual-host under the same endpoint
     * host as S3. A hostname carrying one of these is that service's, never an S3 bucket
     * whose name happens to contain dots — see {@link #bucketFromPrefix}.
     *
     * <p>Every entry names a service that Floci itself routes by {@code Host} in a
     * <em>regionless</em> form, which {@link #isForeignRegionalHost} cannot catch. A label is only
     * justified when some filter in this repository claims that hostname; a speculative entry
     * costs real bucket names, because {@code my.elb.logs} — AWS's own load-balancer
     * access-log naming convention — is a perfectly legal bucket.
     */
    private static final Set<String> NON_S3_SERVICE_LABELS = Set.of(
            "execute-api",      // ApiGatewayExecuteApiHostFilter: <api-id>.execute-api[.<region>].<host>
            "lambda-url",       // LambdaUrlRoutingFilter: <url-id>.lambda-url.<region>.<host>
            "emr-serverless",   // EmrServerlessRouteFilter: emr-serverless.<host>
            "cloudfront");      // CloudFrontDistributionFilter: <dist-id>.cloudfront.net

    /**
     * The label that makes an S3 endpoint host IPv6-capable: {@code s3.dualstack.<region>},
     * {@code s3-fips.dualstack.<region>}, {@code s3-accelerate.dualstack}.
     */
    private static final String DUALSTACK = "dualstack";

    @Inject
    public S3VirtualHostFilter(EmulatorConfig config, ContainerDetector containerDetector) {
        this.baseHostname = config.hostname()
                .orElseGet(() -> containerDetector.isRunningInContainer()
                        ? EmbeddedDnsServer.DEFAULT_SUFFIX
                        : extractHostnameFromUrl(config.baseUrl()));
        this.serviceHostSuffixes = buildServiceHostSuffixes(config.hostname(), config.dns().extraSuffixes());
    }

    S3VirtualHostFilter() {
        this.baseHostname = "localhost";
        this.serviceHostSuffixes = buildServiceHostSuffixes(Optional.empty(), Optional.empty());
    }

    /**
     * Builds the service-host suffix set from the DNS source of truth rather than
     * re-hardcoding it: {@code {"localhost"}} plus the {@link EmbeddedDnsServer} builtins
     * plus the configured hostname and any extra suffixes. The three configured inputs
     * mirror what {@code EmbeddedDnsServer} makes resolvable, so a host that reaches Floci
     * by wildcard DNS is routed by the same rules it resolved under. Package-private so
     * tests reuse the same derivation instead of duplicating the builtin list.
     */
    static Set<String> buildServiceHostSuffixes(Optional<String> hostname, Optional<List<String>> extraSuffixes) {
        Set<String> suffixes = new HashSet<>();
        suffixes.add("localhost");
        EmbeddedDnsServer.BUILTIN_SUFFIXES.forEach(s -> suffixes.add(s.toLowerCase()));
        hostname.ifPresent(h -> suffixes.add(h.toLowerCase()));
        extraSuffixes.ifPresent(list -> list.forEach(s -> suffixes.add(s.toLowerCase())));
        return Set.copyOf(suffixes);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        URI uri = requestContext.getUriInfo().getRequestUri();

        // HTTP/2 (RFC 9113) has no "Host" header — the authority travels in the
        // ":authority" pseudo-header, surfaced here as the request URI authority.
        // Falling back to it keeps virtual-hosted-style routing working when a
        // browser negotiates HTTP/2 over HTTPS (where the Host header is absent).
        String host = resolveHost(requestContext.getHeaderString("Host"), uri);
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

        String path = uri.getRawPath();

        // Do not rewrite S3 Control API paths — the account ID appears as a host label
        // in the S3ControlClient but the path belongs to the S3 Control service, not S3.
        if (path.startsWith("/v20180820/")) {
            return;
        }

        // A higher-priority CloudFront distribution filter may have already routed this request.
        // Use its server-side marker rather than trusting a user-controlled path prefix.
        if (Boolean.TRUE.equals(requestContext.getProperty(CloudFrontDistributionFilter.ROUTED_PROPERTY))) {
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
     * Resolves the effective request authority used for virtual-host detection.
     *
     * <p>HTTP/1.1 carries it in the {@code Host} header. HTTP/2 (RFC 9113) has no
     * {@code Host} header — the authority is in the {@code :authority} pseudo-header,
     * which the container exposes as the request URI authority. When the {@code Host}
     * header is absent we fall back to the URI authority so virtual-hosted-style
     * requests are recognized on both protocol versions.
     *
     * @param hostHeader the value of the {@code Host} header, or {@code null}
     * @param requestUri the request URI, or {@code null}
     * @return the effective authority ({@code host[:port]}), or {@code null} if neither is available
     */
    static String resolveHost(String hostHeader, URI requestUri) {
        if (hostHeader != null) {
            return hostHeader;
        }
        return requestUri != null ? requestUri.getAuthority() : null;
    }

    /**
     * Extracts a bucket name from a virtual-hosted-style Host header.
     *
     * <p>A request is considered virtual-hosted-style when the hostname <em>ends with</em> a
     * known endpoint host — the configured Floci base hostname, plain {@code localhost}, a
     * configured DNS suffix, or a well-known AWS S3 domain (for DNS-redirect setups). The
     * bucket is everything in front of that suffix, minus an optional S3 qualifier
     * ({@code s3}, {@code s3.<region>}, {@code s3-website-<region>}, …).
     *
     * <p><strong>The bucket is not just the first label.</strong> S3 bucket names may contain
     * dots, and naming a website bucket after a domain is the conventional pattern, so
     * {@code www.example.com.localhost} is the bucket {@code www.example.com} — not the bucket
     * {@code www} with an unrecognised remainder. Splitting at the first dot made every dotted
     * bucket fall through to path-style, where {@code HEAD /} answers 200 and the AWS provider
     * reads that as "bucket already exists".
     *
     * Examples with baseHostname="localhost":
     *   my-bucket.localhost:4566       -> "my-bucket"
     *   my-bucket.localhost            -> "my-bucket"
     *   www.example.com.localhost      -> "www.example.com"   (dotted bucket)
     *   my.bucket-logs.localhost:4566  -> "my.bucket-logs"    (dots and hyphens)
     *   my.s3.archive.localhost        -> "my.s3.archive"    (an s3 label followed by a
     *                                                         non-qualifier is bucket text)
     *   data.my-cd-1.localhost         -> "data.my-cd-1"     (region-SHAPED, not a region id)
     *   my-bucket.s3.us-east-1.localhost -> "my-bucket"       (region-qualified)
     *   www.example.com.s3.us-east-1.amazonaws.com -> "www.example.com"
     *   s3.localhost                   -> null  (service endpoint, not a bucket named "s3")
     *   s3.us-east-1.localhost         -> null  (region-qualified service endpoint)
     *   floci.svc.cluster.local        -> null  (no bucket prefix, path-style)
     *   my-svc.floci.svc.cluster.local -> null  (does not end with a known endpoint host)
     *
     * Examples with baseHostname="floci.svc.cluster.local":
     *   my-bucket.floci.svc.cluster.local -> "my-bucket"
     *   floci.svc.cluster.local           -> null  (no bucket prefix, path-style)
     *
     * <p>Inherent ambiguity: a bucket whose <em>own</em> name ends in a complete qualifier
     * ({@code foo.s3}, {@code foo.s3.us-east-1}) is indistinguishable from a qualified reference
     * to {@code foo} and is read as the latter. That is the whole of the ambiguity — a bucket
     * whose name merely <em>contains</em> {@code s3} ({@code my.s3.archive}) is not ambiguous and
     * is not truncated. The service endpoint always wins over the bucket reading, so
     * {@code s3.localhost} stays bucketless.
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

        // The service-endpoint guard runs first and wins over every suffix match below, so a
        // bare s3.<endpoint> host is never mistaken for a bucket literally named "s3".
        String firstLabel = hostname.substring(0, firstDot);
        String remainder  = hostname.substring(firstDot + 1);
        if (isS3ServiceEndpointHost(firstLabel, remainder, baseHostname, serviceHostSuffixes)) {
            return null;
        }

        // Primary: the longest known endpoint host this hostname ends with — the configured
        // base hostname, always-on localhost, or a configured Floci DNS suffix (builtins +
        // floci.dns.extra-suffixes). Longest wins, so bucket.localhost.floci.io yields
        // "bucket" rather than "bucket.localhost" when both suffixes are known.
        String prefix = longestEndpointPrefix(hostname, baseHostname, serviceHostSuffixes);
        if (prefix != null) {
            return bucketFromPrefix(prefix, false);
        }

        // Fallback: well-known AWS S3 domains, for users who route AWS DNS to Floci. An S3
        // qualifier is required here, so a plain foo.amazonaws.com is not an S3 bucket.
        String awsPrefix = stripHostSuffix(hostname, "amazonaws.com");
        if (awsPrefix != null) {
            return bucketFromPrefix(awsPrefix, true);
        }

        // Website endpoints keep their historical tail-agnostic handling: any
        // bucket.s3-website-<region>.<anything> is virtual-hosted.
        return bucketBeforeWebsiteQualifier(hostname);
    }

    /**
     * Returns the part of {@code hostname} preceding the longest known endpoint host it ends
     * with, or {@code null} if it ends with none of them. Candidates are the configured base
     * hostname, plain {@code localhost} (which always resolves to 127.0.0.1 regardless of
     * {@code FLOCI_HOSTNAME}), and every configured DNS suffix.
     */
    private static String longestEndpointPrefix(String hostname, String baseHostname,
                                                Set<String> serviceHostSuffixes) {
        String best = baseHostname == null ? null : stripHostSuffix(hostname, baseHostname);
        best = shorter(best, stripHostSuffix(hostname, "localhost"));
        for (String suffix : serviceHostSuffixes) {
            best = shorter(best, stripHostSuffix(hostname, suffix));
        }
        return best;
    }

    /** The shorter of two candidate prefixes is the one that matched the longer suffix. */
    private static String shorter(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.length() < a.length() ? b : a;
    }

    /**
     * Returns everything before {@code "." + endpointHost} (case-insensitive), or {@code null}
     * when {@code hostname} does not end with that suffix or has nothing in front of it.
     */
    private static String stripHostSuffix(String hostname, String endpointHost) {
        if (endpointHost == null || endpointHost.isEmpty()) {
            return null;
        }
        String suffix = "." + endpointHost;
        if (hostname.length() <= suffix.length()) {
            return null;
        }
        int start = hostname.length() - suffix.length();
        if (!hostname.regionMatches(true, start, suffix, 0, suffix.length())) {
            return null;
        }
        return hostname.substring(0, start);
    }

    /**
     * Reduces the part of the hostname in front of the endpoint host to a bucket name by
     * removing a trailing S3 endpoint qualifier when one is present.
     *
     * <p>Everything before the qualifier is the bucket, so dots inside the bucket name survive.
     * A prefix that <em>is</em> a qualifier ({@code s3}, {@code s3.us-east-1}) is the bucketless
     * service endpoint and yields {@code null}.
     *
     * <p><strong>A qualifier is a tail, not a label.</strong> The split point is the first index
     * from which the remaining labels form a <em>complete</em> qualifier — see
     * {@link #isS3QualifierTail}. Testing labels one at a time truncates dotted buckets whose name
     * happens to contain {@code s3}: for {@code my.s3.archive.localhost} a per-label test finds
     * {@code s3} at index 1 and hands back the bucket {@code my}, so a request for
     * {@code my.s3.archive} silently reads and writes a different bucket. Nothing legal follows
     * {@code s3} except a region, {@code dualstack.<region>}, or the end of the prefix, so
     * {@code s3.archive} is bucket text and the whole prefix is the bucket.
     *
     * @param requireQualifier when true, a prefix with no qualifier is not a bucket at all
     *                         (used for {@code amazonaws.com}, where only s3-qualified hosts count)
     */
    private static String bucketFromPrefix(String prefix, boolean requireQualifier) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        String[] labels = prefix.split("\\.", -1);

        // Other AWS services virtual-host under the same endpoint host as S3 does
        // (<api-id>.execute-api.<endpoint>, <url-id>.lambda-url.<region>.<endpoint>, …).
        // Suffix matching would otherwise read those as a dotted bucket named
        // "<api-id>.execute-api" and hijack the request away from their own filters.
        // Index 0 is exempt: a host whose *first* label is the service label
        // (emr-serverless.<endpoint>) was already read as a bucket before this change.
        for (int i = 1; i < labels.length; i++) {
            if (NON_S3_SERVICE_LABELS.contains(labels[i].toLowerCase())) {
                return null;
            }
        }

        int qualifier = -1;
        for (int i = 0; i < labels.length; i++) {
            if (isS3QualifierTail(labels, i)) {
                qualifier = i;
                break;
            }
        }
        if (qualifier < 0) {
            if (requireQualifier) {
                return null;
            }
            // No S3 qualifier anywhere, and an AWS region id appears in a non-leading position:
            // this is another service's regional virtual host, not a dotted bucket. See
            // isForeignRegionalHost — this is what keeps <acct>.dkr.ecr.<region>.localhost and
            // <domain>.<region>.es.localhost from being served as buckets.
            if (isForeignRegionalHost(labels)) {
                return null;
            }
            return prefix;
        }
        if (qualifier == 0) {
            // Bare service endpoint: s3.<host>, s3.<region>.<host>, s3-website-<region>.<host>
            return null;
        }
        return String.join(".", Arrays.copyOfRange(labels, 0, qualifier));
    }

    /**
     * True when {@code labels[from..]} is exactly an S3 endpoint qualifier — that is, when the
     * labels from {@code from} onwards account for the <em>whole</em> remainder of the prefix and
     * spell one of the endpoint forms AWS actually publishes:
     *
     * <pre>
     *   s3                                s3-fips.&lt;region&gt;
     *   s3.&lt;region&gt;                       s3-fips.dualstack.&lt;region&gt;
     *   s3.dualstack.&lt;region&gt;             s3-accelerate
     *   s3-&lt;region&gt;            (legacy)   s3-accelerate.dualstack
     *   s3-website                        s3-website.&lt;region&gt;    (newer regions)
     *                                     s3-website-&lt;region&gt;    (older regions)
     * </pre>
     *
     * <p>Forms taken from the Amazon S3 endpoint tables in the AWS General Reference. Note that
     * the website endpoint spells its region with a dot in newer regions and a dash in older ones
     * ({@code s3-website.eu-west-2} vs {@code s3-website-eu-west-1}), so both are accepted.
     *
     * <p>The "whole remainder" requirement is the point of this method. It is what makes an
     * {@code s3} label that is followed by something else — {@code my.s3.archive} — bucket text
     * rather than a qualifier, and it is what stops the bucket being truncated at it.
     *
     * <p>{@code s3-accesspoint} and {@code s3-control} are deliberately absent: those hosts are
     * prefixed by an access point name or an account id, not a bucket, and {@code filter} already
     * routes S3 Control away by path.
     */
    private static boolean isS3QualifierTail(String[] labels, int from) {
        int n = labels.length;
        if (from >= n) {
            return false;
        }
        String head = labels[from].toLowerCase();
        int i = from + 1;

        // Transfer acceleration is global: it names no region, only an optional dualstack.
        if ("s3-accelerate".equals(head)) {
            if (i < n && DUALSTACK.equals(labels[i].toLowerCase())) {
                i++;
            }
            return i == n;
        }

        // Legacy dash forms carry the region inside the head label, so nothing may follow:
        // s3-website-us-east-1, s3-us-west-2.
        if (head.startsWith("s3-website-") && AwsRegions.isRegionId(head.substring("s3-website-".length()))) {
            return i == n;
        }
        if (head.startsWith("s3-") && AwsRegions.isRegionId(head.substring("s3-".length()))) {
            return i == n;
        }

        // Dotted forms: <head>[.dualstack][.<region>]
        if (!"s3".equals(head) && !"s3-fips".equals(head) && !"s3-website".equals(head)) {
            return false;
        }
        boolean dualstack = i < n && DUALSTACK.equals(labels[i].toLowerCase());
        if (dualstack) {
            i++;
        }
        if (i < n && AwsRegions.isRegionId(labels[i])) {
            i++;
        } else if (dualstack) {
            // "dualstack" only appears in front of a region; a bare s3.dualstack is not an
            // endpoint, so treat the whole thing as bucket text rather than truncating.
            return false;
        }
        return i == n;
    }

    /**
     * True when an unqualified prefix carries an AWS region id in a non-leading label — the shape
     * every <em>other</em> regional service virtual-hosts under: {@code <acct>.dkr.ecr.<region>},
     * {@code <domain>.<region>.es}, {@code <id>.iot.<region>}. A real S3 virtual host always
     * carries an {@code s3} qualifier instead, and when it does the region is read from there, so
     * a bucket whose own name contains a region stays addressable as {@code logs.us-east-1.s3.<host>}.
     * One structural rule covers every regional service at once, including services Floci does not
     * model yet, where a label denylist would have to enumerate them and would silently hijack the
     * first one it missed.
     *
     * <p><strong>Region ids, not a region-shaped pattern.</strong> This used to match
     * {@code [a-z]{2}-[a-z-]+-\d+}, which is also the shape of ordinary bucket labels:
     * {@code data.my-cd-1} is a legal bucket, and matching by shape rejected it, dropped it back to
     * path-style, and restored the false "bucket exists" 200 this filter exists to prevent. The
     * finite list in {@link AwsRegions#KNOWN_IDS} is what separates "another service's regional
     * virtual host" from "a bucket whose label happens to look region-shaped".
     *
     * <p>Two residual costs, both accepted deliberately:
     * <ul>
     *   <li>A bucket whose name contains a <em>real</em> region id in a non-leading position
     *       ({@code data.us-east-1}) is still not addressable in the unqualified virtual-hosted
     *       form. It remains addressable qualified ({@code data.us-east-1.s3.<host>}) and
     *       path-style, and it was equally unaddressable before this filter existed.</li>
     *   <li>A regional service host in a region AWS launches after {@link AwsRegions#KNOWN_IDS}
     *       was last updated would be read as a bucket. That is the reason to keep the list
     *       current; the failure is a wrong route rather than data loss, and the
     *       {@code NON_S3_SERVICE_LABELS} entries still cover the services Floci itself routes.</li>
     * </ul>
     */
    private static boolean isForeignRegionalHost(String[] labels) {
        // Index 0 is exempt so a bucket literally named "us-east-1" keeps working.
        for (int i = 1; i < labels.length; i++) {
            if (AwsRegions.isRegionId(labels[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Historical tail-agnostic website handling: {@code bucket.s3-website-<region>.<anything>}
     * is virtual-hosted even when the tail is not a configured endpoint host.
     */
    private static String bucketBeforeWebsiteQualifier(String hostname) {
        String[] labels = hostname.split("\\.", -1);
        for (int i = labels.length - 2; i >= 1; i--) {
            if (labels[i].toLowerCase().startsWith("s3-website")) {
                return String.join(".", Arrays.copyOfRange(labels, 0, i));
            }
        }
        return null;
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

}
