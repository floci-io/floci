package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3VirtualHostFilterTest {

    /**
     * Always-on service-host suffix set with no configured extra suffixes. Derived from the
     * production factory so it stays in sync if a builtin suffix is ever added, rather than
     * hardcoding a copy of {@code EmbeddedDnsServer.BUILTIN_SUFFIXES}.
     */
    private static final Set<String> DEFAULT_SUFFIXES =
            S3VirtualHostFilter.buildServiceHostSuffixes(Optional.empty(), Optional.empty());

    // --- extractBucket with baseHostname ---

    @ParameterizedTest
    @CsvSource({
            // Standard localhost endpoint
            "my-bucket.localhost:4566, localhost, my-bucket",
            "my-bucket.localhost,      localhost, my-bucket",
            // Custom single-label hostname
            "my-bucket.myhost,         myhost,    my-bucket",
            // Multi-label hostname (e.g. Docker compose service name)
            "my-bucket.floci.internal, floci.internal, my-bucket",
            // K8s-style service hostname with FLOCI_HOSTNAME set
            "my-bucket.floci.default.svc.cluster.local, floci.default.svc.cluster.local, my-bucket",
            "my-bucket.floci-svc.namespace.svc, floci-svc.namespace.svc, my-bucket",
            // localhost is always recognized regardless of baseHostname (fixes virtual-host when FLOCI_HOSTNAME=floci)
            "my-bucket.localhost,      floci, my-bucket",
            "my-bucket.localhost:4566, floci, my-bucket",
            // Region-qualified vhost form: bucket.s3.<region>.<baseHostname>
            "my-bucket.s3.us-east-1.localhost,      localhost, my-bucket",
            "my-bucket.s3.us-east-1.localhost:4566, localhost, my-bucket",
            "my-bucket.s3.eu-west-2.localhost,      localhost, my-bucket",
            // Region-qualified vhost against localhost fallback even when baseHostname differs
            "my-bucket.s3.us-east-1.localhost,      floci, my-bucket",
            // Region-qualified vhost against configured baseHostname
            "my-bucket.s3.us-east-1.floci.internal, floci.internal, my-bucket",
            // AWS S3 domains (fallback — independent of baseHostname)
            "my-bucket.s3.amazonaws.com,               localhost, my-bucket",
            "my-bucket.s3.amazonaws.com:443,            localhost, my-bucket",
            "my-bucket.s3.us-east-1.amazonaws.com,      localhost, my-bucket",
            "my-bucket.s3.eu-west-1.amazonaws.com:443,  localhost, my-bucket",
            // LocalStack-compatible domains (*.localhost.localstack.cloud resolves to 127.0.0.1 via public DNS)
            "my-bucket.s3.localhost.localstack.cloud,           localhost, my-bucket",
            "my-bucket.s3.localhost.localstack.cloud:4566,      localhost, my-bucket",
            "my-bucket.s3.us-east-1.localhost.localstack.cloud, localhost, my-bucket",
            "my-bucket.localhost.localstack.cloud,              localhost, my-bucket",
            "my-bucket.localhost.localstack.cloud:4566,         localhost, my-bucket",
            // Floci public wildcard DNS (*.s3.localhost.floci.io and *.localhost.floci.io resolve to 127.0.0.1)
            "my-bucket.s3.localhost.floci.io,       localhost, my-bucket",
            "my-bucket.s3.localhost.floci.io:4566,  localhost, my-bucket",
            "my-bucket.localhost.floci.io,          localhost, my-bucket",
            "my-bucket.localhost.floci.io:4566,     localhost, my-bucket",
            "my-bucket.s3.us-east-1.localhost.floci.io, localhost, my-bucket",
    })
    void extractsBucketFromVirtualHostedStyle(String host, String baseHostname, String expectedBucket) {
        assertEquals(expectedBucket, S3VirtualHostFilter.extractBucket(host, baseHostname, DEFAULT_SUFFIXES));
    }

    // --- Path-style: service hostname alone — must NOT extract a bucket ---

    @ParameterizedTest
    @CsvSource({
            // Bare hostname — no dot, never virtual-hosted
            "localhost:4566, localhost",
            "localhost,      localhost",
            "plain-host,     plain-host",
            // Bare S3 service hosts must NOT be treated as a bucket named s3
            "s3.localhost:4566,                 localhost",
            "s3.localhost,                      localhost",
            "s3.localhost.localstack.cloud,     localhost",
            "s3.localhost.localstack.cloud:4566, localhost",
            "s3.localhost.floci.io,             localhost",
            "s3.localhost.floci.io:4566,        localhost",
            // Shared edge hosts are endpoints, not buckets named localhost
            "localhost.localstack.cloud,        localhost",
            "localhost.localstack.cloud:4566,   localhost",
            "localhost.floci.io,                localhost",
            "localhost.floci.io:4566,           localhost",
            // K8s service hostname used as endpoint (path-style) — must NOT be rewritten
            "floci.default.svc.cluster.local,           localhost",
            "floci-service.namespace.svc.cluster.local, localhost",
            "my-svc.default.svc,                        localhost",
            // Remainder doesn't match baseHostname and isn't an AWS S3 domain
            "my-bucket.custom.internal, localhost",
            "my-bucket.emulator.local,  localhost",
    })
    void returnsNullForPathStyleOrMismatchedRemainder(String host, String baseHostname) {
        assertNull(S3VirtualHostFilter.extractBucket(host, baseHostname, DEFAULT_SUFFIXES));
    }

    @ParameterizedTest
    @CsvSource({
            "192.168.1.1,      localhost",
            "192.168.1.1:4566, localhost",
            "127.0.0.1,        localhost",
            "10.0.0.1:9000,    localhost",
    })
    void returnsNullForIpAddresses(String host, String baseHostname) {
        assertNull(S3VirtualHostFilter.extractBucket(host, baseHostname, DEFAULT_SUFFIXES));
    }

    @ParameterizedTest
    @NullSource
    void returnsNullForNullHost(String host) {
        assertNull(S3VirtualHostFilter.extractBucket(host, "localhost", DEFAULT_SUFFIXES));
    }

    @Test
    void returnsNullForNullBaseHostname() {
        // path-style bare hostname (no subdomain) — must return null
        assertNull(S3VirtualHostFilter.extractBucket("localhost:4566", null, DEFAULT_SUFFIXES));
        // well-known domains match regardless of baseHostname
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.localhost", null, DEFAULT_SUFFIXES));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.localhost:4566", null, DEFAULT_SUFFIXES));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.s3.amazonaws.com", null, DEFAULT_SUFFIXES));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.s3.localhost.localstack.cloud", null, DEFAULT_SUFFIXES));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.localhost.localstack.cloud", null, DEFAULT_SUFFIXES));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.s3.localhost.floci.io", null, DEFAULT_SUFFIXES));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.s3.us-east-1.localhost.floci.io", null, DEFAULT_SUFFIXES));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.localhost.floci.io", null, DEFAULT_SUFFIXES));
        // Region-qualified vhost against localhost fallback works without baseHostname
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.s3.us-east-1.localhost", null, DEFAULT_SUFFIXES));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.s3.us-east-1.localhost:4566", null, DEFAULT_SUFFIXES));
    }

    // --- Service-host classification derives from the configured suffix set ---

    @Test
    void classifiesServiceHostFromDerivedSuffixSet() {
        // Always-on builtins: bare s3.<builtin> is the service endpoint, not a bucket named "s3"
        assertTrue(S3VirtualHostFilter.isS3ServiceEndpointHost("s3", "localhost", "localhost", DEFAULT_SUFFIXES));
        assertTrue(S3VirtualHostFilter.isS3ServiceEndpointHost("s3", "localhost.localstack.cloud", "localhost", DEFAULT_SUFFIXES));
        assertTrue(S3VirtualHostFilter.isS3ServiceEndpointHost("s3", "localhost.floci.io", "localhost", DEFAULT_SUFFIXES));

        // A configured extra suffix is newly recognised as a service host...
        Set<String> withExtra = Set.of(
                "localhost", "localhost.floci.io", "localhost.localstack.cloud", "emulator.internal");
        assertTrue(S3VirtualHostFilter.isS3ServiceEndpointHost("s3", "emulator.internal", "localhost", withExtra));
        // ...but is not, with only the builtins configured (proves it comes from config, not a literal)
        assertFalse(S3VirtualHostFilter.isS3ServiceEndpointHost("s3", "emulator.internal", "localhost", DEFAULT_SUFFIXES));

        // The configured base hostname still wins regardless of the suffix set
        assertTrue(S3VirtualHostFilter.isS3ServiceEndpointHost("s3", "floci.internal", "floci.internal", DEFAULT_SUFFIXES));

        // Only the "s3" service label is a service host; any other first label is a bucket
        assertFalse(S3VirtualHostFilter.isS3ServiceEndpointHost("my-bucket", "localhost", "localhost", DEFAULT_SUFFIXES));
    }

    // --- Virtual-hosted buckets route on a configured extra suffix (like the builtins) ---

    @Test
    void routesVirtualHostedBucketsForConfiguredExtraSuffix() {
        Set<String> withExtra = Set.of(
                "localhost", "localhost.floci.io", "localhost.localstack.cloud", "localhost.example.internal");

        // All three virtual-hosted forms route to the bucket, exactly as the builtins do
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(
                "my-bucket.localhost.example.internal", "localhost", withExtra));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(
                "my-bucket.localhost.example.internal:4566", "localhost", withExtra));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(
                "my-bucket.s3.localhost.example.internal", "localhost", withExtra));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(
                "my-bucket.s3.us-east-1.localhost.example.internal", "localhost", withExtra));

        // The bare s3.<extra> service host stays bucketless
        assertNull(S3VirtualHostFilter.extractBucket("s3.localhost.example.internal", "localhost", withExtra));

        // Without the suffix configured, the same forms do NOT route (no accidental match)
        assertNull(S3VirtualHostFilter.extractBucket(
                "my-bucket.localhost.example.internal", "localhost", DEFAULT_SUFFIXES));
        assertNull(S3VirtualHostFilter.extractBucket(
                "my-bucket.s3.localhost.example.internal", "localhost", DEFAULT_SUFFIXES));
    }

    // --- The configured hostname is a DNS suffix too, and routes the same forms ---

    @Test
    void routesVirtualHostedBucketsForConfiguredHostname() {
        String hostname = "aws.mycorp.test";
        Set<String> withHostname =
                S3VirtualHostFilter.buildServiceHostSuffixes(Optional.of(hostname), Optional.empty());

        // EmbeddedDnsServer makes *.<hostname> resolvable, so the derived set must carry it too,
        // lowercased like the rest of the set because matching is case-insensitive
        assertTrue(withHostname.contains(hostname));
        assertTrue(S3VirtualHostFilter.buildServiceHostSuffixes(Optional.of("AWS.MyCorp.Test"), Optional.empty())
                .contains(hostname));

        // bucket.s3.<hostname> resolves via wildcard DNS, so it must route as a bucket rather
        // than fall through to path-style with "s3" read as the bucket name
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(
                "my-bucket.s3." + hostname, hostname, withHostname));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(
                "my-bucket.s3." + hostname + ":4566", hostname, withHostname));

        // The forms already covered by baseHostname matching keep working
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(
                "my-bucket." + hostname, hostname, withHostname));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(
                "my-bucket.s3.us-east-1." + hostname, hostname, withHostname));

        // The bare s3.<hostname> service host stays bucketless
        assertNull(S3VirtualHostFilter.extractBucket("s3." + hostname, hostname, withHostname));

        // Without the hostname configured, bucket.s3.<hostname> does not route (no accidental match)
        assertNull(S3VirtualHostFilter.extractBucket(
                "my-bucket.s3." + hostname, "localhost", DEFAULT_SUFFIXES));
    }

    // --- Hostname extraction from URL ---

    @ParameterizedTest
    @CsvSource({
            "http://localhost:4566,                             localhost",
            "http://localhost,                                  localhost",
            "http://floci.default.svc.cluster.local:4566,      floci.default.svc.cluster.local",
            "http://floci-service.namespace.svc.cluster.local, floci-service.namespace.svc.cluster.local",
            "http://my-host:9000,                              my-host",
    })
    void extractsHostnameFromUrl(String url, String expectedHostname) {
        assertEquals(expectedHostname, S3VirtualHostFilter.extractHostnameFromUrl(url));
    }

    @Test
    void extractHostnameFromUrlReturnsNullForNull() {
        assertNull(S3VirtualHostFilter.extractHostnameFromUrl(null));
    }

    // --- Host resolution: HTTP/1.1 Host header vs HTTP/2 :authority fallback ---

    @Test
    void resolveHostPrefersHostHeaderOverUriAuthority() {
        URI uri = URI.create("https://my-bucket.s3.us-east-1.localhost:4566/key.txt");
        assertEquals("my-bucket.localhost:4566", S3VirtualHostFilter.resolveHost("my-bucket.localhost:4566", uri));
    }

    @Test
    void resolveHostFallsBackToUriAuthorityWhenHostHeaderAbsent() {
        // HTTP/2 request: no Host header, authority carried by the URI (:authority).
        URI uri = URI.create("https://my-bucket.s3.us-east-1.localhost:4566/key.txt");
        assertEquals("my-bucket.s3.us-east-1.localhost:4566", S3VirtualHostFilter.resolveHost(null, uri));
    }

    @Test
    void resolveHostReturnsNullWhenNeitherAvailable() {
        assertNull(S3VirtualHostFilter.resolveHost(null, null));
        assertNull(S3VirtualHostFilter.resolveHost(null, URI.create("/relative/path")));
    }

    @Test
    void http2VirtualHostedRequestResolvesBucketWithoutHostHeader() {
        // Regression for #1866: over HTTP/2 the Host header is null, so the bucket must
        // be recovered from the URI authority instead of falling through to path-style.
        URI uri = URI.create("https://my-bucket.s3.us-east-1.localhost:4566/key.txt");
        String host = S3VirtualHostFilter.resolveHost(null, uri);
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(host, "localhost", DEFAULT_SUFFIXES));
    }

    // --- HTTP/2 website request: the path rewrite must preserve the s3-website authority (#1954) ---

    @Test
    void http2WebsiteRequestRewritePreservesAuthorityForDownstreamDetection() {
        // Over HTTP/2 a website request has no Host header. The filter rewrites the path to
        // /bucket/key for the path-style S3 controller, but must keep the s3-website authority
        // on the request URI. Otherwise S3Controller.isWebsiteRequest — which, with no Host
        // header, reads the URI authority (S3VirtualHostFilter.resolveHost) — can no longer
        // recognize the request and serves API XML instead of the index/error document.
        URI requestUri = URI.create("https://my-bucket.s3-website-us-east-1.localhost:4566/index.html");

        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(requestUri);
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getHeaderString("Host")).thenReturn(null); // HTTP/2: no Host header

        new S3VirtualHostFilter().filter(ctx);

        ArgumentCaptor<URI> rewritten = ArgumentCaptor.forClass(URI.class);
        verify(ctx).setRequestUri(rewritten.capture());
        URI newUri = rewritten.getValue();

        // Path rewritten to path-style for the S3 controller...
        assertEquals("/my-bucket/index.html", newUri.getRawPath());
        // ...but the s3-website authority survives, so downstream website detection still fires.
        assertEquals("my-bucket.s3-website-us-east-1.localhost:4566", newUri.getAuthority());
    }

    // --- Dotted bucket names: the bucket is everything before the endpoint suffix (#s3-vhost) ---

    @ParameterizedTest
    @CsvSource({
            // Single-label bucket — unchanged behaviour
            "my-bucket.localhost,                       localhost, my-bucket",
            "my-bucket.localhost:4566,                  localhost, my-bucket",
            // Dotted bucket: a website bucket named after a domain
            "www.example.com.localhost,                 localhost, www.example.com",
            "www.example.com.localhost:4566,            localhost, www.example.com",
            // Deeply dotted bucket
            "a.b.c.d.localhost,                         localhost, a.b.c.d",
            "a.b.c.d.localhost:4611,                    localhost, a.b.c.d",
            // Dots AND hyphens — the exact shape from the Terraform BucketAlreadyExists report
            "r5e817e.floci.example.com-logs.localhost,  localhost, r5e817e.floci.example.com-logs",
            "one.dot-nonexistent.localhost,             localhost, one.dot-nonexistent",
            "a.b.c-nonexistent.localhost,               localhost, a.b.c-nonexistent",
            // Dotted bucket against a multi-label configured base hostname
            "www.example.com.floci.internal,            floci.internal, www.example.com",
            "www.example.com.floci.default.svc.cluster.local, floci.default.svc.cluster.local, www.example.com",
            // Dotted bucket, region-qualified vhost form
            "www.example.com.s3.us-east-1.localhost,    localhost, www.example.com",
            "www.example.com.s3.us-east-1.localhost:4566, localhost, www.example.com",
            "www.example.com.s3.amazonaws.com,          localhost, www.example.com",
            "www.example.com.s3.us-east-1.amazonaws.com, localhost, www.example.com",
            "www.example.com.s3.dualstack.us-east-1.amazonaws.com, localhost, www.example.com",
            // Dotted bucket on the wildcard-DNS builtins
            "www.example.com.localhost.floci.io,        localhost, www.example.com",
            "www.example.com.s3.localhost.floci.io,     localhost, www.example.com",
            "www.example.com.s3.us-east-1.localhost.localstack.cloud, localhost, www.example.com",
            // Dotted bucket on a website endpoint
            "www.example.com.s3-website-us-east-1.localhost, localhost, www.example.com",
            "www.example.com.s3-website.localhost,      localhost, www.example.com",
            // Case-insensitive suffix matching keeps the bucket's own case
            "www.Example.com.LOCALHOST,                 localhost, www.Example.com",
    })
    void extractsDottedBucketNames(String host, String baseHostname, String expectedBucket) {
        assertEquals(expectedBucket, S3VirtualHostFilter.extractBucket(host, baseHostname, DEFAULT_SUFFIXES));
    }

    @Test
    void headOnDottedBucketIsNotDegradedToPathStyle() {
        // Regression: extractBucket split at the FIRST dot, so a dotted bucket's remainder
        // ("floci.example.com-logs.localhost") did not match baseHostname "localhost" and the
        // request degraded to path-style "/" — where HEAD answers 200. The Terraform AWS
        // provider HEADs a bucket before creating it and read that 200 as "already exists",
        // failing with BucketAlreadyExists for a bucket that never existed.
        assertEquals("floci.example.com-logs", S3VirtualHostFilter.extractBucket(
                "floci.example.com-logs.localhost", "localhost", DEFAULT_SUFFIXES));
        assertEquals("floci.example.com-logs", S3VirtualHostFilter.extractBucket(
                "floci.example.com-logs.localhost:4566", "localhost", DEFAULT_SUFFIXES));
    }

    // --- The service-endpoint reading always wins over the new suffix matching ---

    @ParameterizedTest
    @CsvSource({
            // s3.<endpoint> is Floci's own S3 endpoint, never a bucket named "s3"
            "s3.localhost,                        localhost",
            "s3.localhost:4566,                   localhost",
            "s3.floci.internal,                   floci.internal",
            // ...including the region-qualified service endpoint, which the guard alone misses
            "s3.us-east-1.localhost,              localhost",
            "s3.us-east-1.localhost:4566,         localhost",
            "s3.eu-west-2.localhost.floci.io,     localhost",
            "s3.dualstack.us-east-1.amazonaws.com, localhost",
            "s3.amazonaws.com,                    localhost",
            "s3.us-east-1.amazonaws.com,          localhost",
            // Bare website endpoint with no bucket in front
            "s3-website-us-east-1.localhost,      localhost",
            // A non-S3 amazonaws.com host is not a bucket
            "foo.amazonaws.com,                   localhost",
            "queue.amazonaws.com:443,             localhost",
            // Dotted hosts that still end in nothing we know
            "www.example.com.custom.internal,     localhost",
            "a.b.c.d,                             localhost",
    })
    void serviceEndpointsAndUnknownSuffixesStayBucketless(String host, String baseHostname) {
        assertNull(S3VirtualHostFilter.extractBucket(host, baseHostname, DEFAULT_SUFFIXES));
    }

    @Test
    void longestKnownSuffixWinsSoBucketDoesNotAbsorbTheEndpoint() {
        // "localhost" and "localhost.floci.io" are both known; the longer one must match,
        // otherwise the bucket would come out as "my-bucket.localhost".
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(
                "my-bucket.localhost.floci.io", "localhost", DEFAULT_SUFFIXES));
        assertEquals("www.example.com", S3VirtualHostFilter.extractBucket(
                "www.example.com.localhost.localstack.cloud", "localhost", DEFAULT_SUFFIXES));
    }

    @Test
    void dottedBucketsRouteOnConfiguredExtraSuffixes() {
        Set<String> withExtra = Set.of(
                "localhost", "localhost.floci.io", "localhost.localstack.cloud", "localhost.example.internal");
        assertEquals("www.example.com", S3VirtualHostFilter.extractBucket(
                "www.example.com.localhost.example.internal", "localhost", withExtra));
        assertEquals("www.example.com", S3VirtualHostFilter.extractBucket(
                "www.example.com.s3.localhost.example.internal:4566", "localhost", withExtra));
        assertEquals("www.example.com", S3VirtualHostFilter.extractBucket(
                "www.example.com.s3.us-east-1.localhost.example.internal", "localhost", withExtra));
        // Still bucketless for the bare service endpoint on that suffix
        assertNull(S3VirtualHostFilter.extractBucket(
                "s3.us-east-1.localhost.example.internal", "localhost", withExtra));
        // And no accidental match when the suffix is not configured
        assertNull(S3VirtualHostFilter.extractBucket(
                "www.example.com.localhost.example.internal", "localhost", DEFAULT_SUFFIXES));
    }

    @Test
    void ipv4AuthorityIsNeverABucketEvenWithADottedShape() {
        assertNull(S3VirtualHostFilter.extractBucket("192.168.1.1", "localhost", DEFAULT_SUFFIXES));
        assertNull(S3VirtualHostFilter.extractBucket("127.0.0.1:4566", "localhost", DEFAULT_SUFFIXES));
    }

    @Test
    void filterRewritesPathForADottedBucket() {
        URI requestUri = URI.create("http://www.example.com.localhost:4566/index.html");
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(requestUri);
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getHeaderString("Host")).thenReturn("www.example.com.localhost:4566");

        new S3VirtualHostFilter().filter(ctx);

        ArgumentCaptor<URI> rewritten = ArgumentCaptor.forClass(URI.class);
        verify(ctx).setRequestUri(rewritten.capture());
        assertEquals("/www.example.com/index.html", rewritten.getValue().getRawPath());
    }

    // --- Other services' virtual-host schemes must not be swallowed as dotted buckets ---

    @ParameterizedTest
    @CsvSource({
            // API Gateway: <api-id>.execute-api.<region>.<endpoint> and the wildcard-DNS forms
            "abc123.execute-api.localhost,                       localhost",
            "abc123.execute-api.localhost:4566,                  localhost",
            "abc123.execute-api.ap-northeast-2.localhost:4566,   localhost",
            "abc123.execute-api.localhost.floci.io,              localhost",
            "abc123.execute-api.localhost.localstack.cloud,      localhost",
            "AbC123.execute-api.localhost.floci.io,              localhost",
            "abc123.execute-api.us-east-1.amazonaws.com,         localhost",
            // Lambda function URLs
            "url-id.lambda-url.us-east-1.localhost,              localhost",
            "url-id.lambda-url.localhost:4566,                   localhost",
            // CloudFront distribution domains routed at the Floci endpoint
            "e123.cloudfront.localhost,                          localhost",
    })
    void otherServicesVirtualHostsAreNotBuckets(String host, String baseHostname) {
        assertNull(S3VirtualHostFilter.extractBucket(host, baseHostname, DEFAULT_SUFFIXES));
    }

    @Test
    void serviceLabelInFirstPositionKeepsItsPreExistingBucketReading() {
        // emr-serverless.<endpoint> has no id in front; it was read as a bucket before the
        // dotted-bucket fix and still is, so this change alters nothing for it.
        assertEquals("emr-serverless",
                S3VirtualHostFilter.extractBucket("emr-serverless.localhost", "localhost", DEFAULT_SUFFIXES));
    }

    /**
     * Every regional AWS service virtual-hosts as {@code <id>.<service>.<region>.<endpoint>}, and
     * suffix matching reads all of them as a dotted bucket unless something stops it. A label
     * denylist cannot: it has to name each service, and the first one it misses is silently served
     * as an S3 bucket. The structural rule is that a real S3 virtual host never puts a bare region
     * label against the endpoint host — {@code s3}, {@code s3.<region>} or {@code s3-website-<region>}
     * is always there instead.
     *
     * <p>{@code <account>.dkr.ecr.<region>.localhost} is not hypothetical: it is the registry URI
     * {@code EcrRegistryManager} hands out.
     */
    @ParameterizedTest
    @CsvSource({
            // ECR registry, as EcrRegistryManager emits it
            "123456789012.dkr.ecr.us-east-1.localhost,           localhost",
            "123456789012.dkr.ecr.us-east-1.localhost:5100,      localhost",
            "123456789012.dkr.ecr.eu-central-1.localhost.floci.io, localhost",
            // OpenSearch / Elasticsearch domain endpoints
            "search-mydomain-abc.us-east-1.es.localhost,         localhost",
            // IoT data endpoints
            "abc123.iot.us-east-1.localhost,                     localhost",
            // AppSync
            "myapp.appsync-api.us-east-1.localhost,              localhost",
            // Cognito hosted UI
            "mydomain.auth.eu-west-2.localhost,                  localhost",
            // A service Floci does not model yet still must not be swallowed
            "anything.some-future-service.ap-northeast-2.localhost, localhost",
    })
    void regionalServiceVirtualHostsAreNotDottedBuckets(String host, String baseHostname) {
        assertNull(S3VirtualHostFilter.extractBucket(host, baseHostname, DEFAULT_SUFFIXES));
    }

    /**
     * The other direction: the guard must not cost legal bucket names. {@code my.elb.logs} is
     * AWS's own load-balancer access-log naming convention, and no filter in this repository
     * routes {@code *.elb.<endpoint>} or {@code *.mwaa.<endpoint>} by Host, so neither label
     * belongs in the denylist.
     */
    @ParameterizedTest
    @CsvSource({
            "my.elb.logs.localhost,             my.elb.logs",
            "my.elb.logs.localhost:4566,        my.elb.logs",
            "airflow.mwaa.dags.localhost,       airflow.mwaa.dags",
            "my.elb.logs.s3.us-east-1.localhost, my.elb.logs",
    })
    void bucketNamesCarryingServiceWordsStillResolve(String host, String expectedBucket) {
        assertEquals(expectedBucket, S3VirtualHostFilter.extractBucket(host, "localhost", DEFAULT_SUFFIXES));
    }

    /**
     * The region rule only applies when no S3 qualifier is present, so a bucket whose own name
     * ends in a region label is still addressable in the qualified forms.
     */
    @Test
    void aBucketWhoseNameEndsInARegionLabelIsStillAddressableWhenQualified() {
        assertEquals("logs.us-east-1", S3VirtualHostFilter.extractBucket(
                "logs.us-east-1.s3.localhost", "localhost", DEFAULT_SUFFIXES));
        assertEquals("logs.us-east-1", S3VirtualHostFilter.extractBucket(
                "logs.us-east-1.s3.us-east-1.amazonaws.com", "localhost", DEFAULT_SUFFIXES));
        // Unqualified, it reads as another service's regional host — the documented trade-off.
        assertNull(S3VirtualHostFilter.extractBucket(
                "logs.us-east-1.localhost", "localhost", DEFAULT_SUFFIXES));
    }

    /** A single-label bucket is unaffected by the region rule, region-shaped or not. */
    @Test
    void singleLabelBucketsAreUntouchedByTheRegionRule() {
        assertEquals("us-east-1", S3VirtualHostFilter.extractBucket(
                "us-east-1.localhost", "localhost", DEFAULT_SUFFIXES));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket(
                "my-bucket.localhost", "localhost", DEFAULT_SUFFIXES));
    }

    /**
     * The cost of the four remaining denylist labels, pinned rather than left implicit: a bucket
     * whose name carries one of them in a non-leading position is not reachable virtual-hosted and
     * falls back to path-style, which is what it did before this change too.
     *
     * <p>That is the deliberate side of the trade. The alternative failure — validating each
     * service's hostname grammar and letting anything that does not match through — is a silent
     * cross-service hijack: S3 answering a request meant for API Gateway. A bucket that is
     * awkward to address virtual-hosted is recoverable by the client; an API that intermittently
     * returns S3 responses is not. Each label here names a filter in this repository that claims
     * that hostname, so the set stays small and justified.
     */
    @ParameterizedTest
    @CsvSource({
            "my.execute-api.archive.localhost,   localhost",
            "my.lambda-url.archive.localhost,    localhost",
            "my.emr-serverless.archive.localhost, localhost",
            "my.cloudfront.archive.localhost,    localhost",
    })
    void bucketNamesCarryingADenylistedServiceLabelAreDeliberatelyNotReachable(String host, String baseHostname) {
        assertNull(S3VirtualHostFilter.extractBucket(host, baseHostname, DEFAULT_SUFFIXES));
    }

    // --- Regression: an internal "s3" label must not truncate the bucket ---

    /**
     * A qualifier is a complete tail, not a label that may appear anywhere.
     *
     * <p>Scanning labels one at a time and splitting at the last {@code s3} reads
     * {@code my.s3.archive.localhost} as the bucket {@code my}: a request for
     * {@code my.s3.archive} silently reads and writes a <em>different, existing</em> bucket.
     * That is cross-bucket misrouting — strictly worse than the wrong 200 this filter was
     * written to fix, because the client gets a plausible success against the wrong data.
     *
     * <p>Nothing legal follows {@code s3} in an endpoint host except a region,
     * {@code dualstack.<region>}, or the end of the name, so an {@code s3} label followed by an
     * arbitrary label is part of the bucket.
     */
    @ParameterizedTest
    @CsvSource({
            "my.s3.archive.localhost,             my.s3.archive",
            "my.s3.archive.localhost:4566,        my.s3.archive",
            "www.s3.example.com.localhost,        www.s3.example.com",
            "my.s3.archive.localhost.floci.io,    my.s3.archive",
            // s3-website is a qualifier head too, and gets the same treatment
            "data.s3-website.archive.localhost,   data.s3-website.archive",
            // ...and the region-shaped tail is not a region id, so it is bucket text as well
            "my.s3.not-a-region.localhost,        my.s3.not-a-region",
    })
    void anInternalS3LabelDoesNotTruncateTheBucket(String host, String expectedBucket) {
        assertEquals(expectedBucket, S3VirtualHostFilter.extractBucket(host, "localhost", DEFAULT_SUFFIXES));
    }

    /** The same host, stated as the misrouting it would otherwise be. */
    @Test
    void aDottedBucketContainingS3IsNeverResolvedToAShorterBucket() {
        String bucket = S3VirtualHostFilter.extractBucket(
                "my.s3.archive.localhost", "localhost", DEFAULT_SUFFIXES);
        assertNotEquals("my", bucket, "bucket my.s3.archive must not resolve to the bucket my");
        assertEquals("my.s3.archive", bucket);
    }

    /**
     * A real qualifier still splits, including for a dotted bucket that itself contains an
     * {@code s3} label — {@code my.s3.archive} addressed region-qualified.
     */
    @ParameterizedTest
    @CsvSource({
            "my.s3.localhost,                        my",
            "my.s3.us-east-1.localhost,              my",
            "my.s3.archive.s3.localhost,             my.s3.archive",
            "my.s3.archive.s3.us-east-1.localhost,   my.s3.archive",
            "my.s3.archive.s3.dualstack.eu-west-1.localhost, my.s3.archive",
    })
    void aCompleteQualifierTailStillSplitsTheBucket(String host, String expectedBucket) {
        assertEquals(expectedBucket, S3VirtualHostFilter.extractBucket(host, "localhost", DEFAULT_SUFFIXES));
    }

    /**
     * The published S3 endpoint qualifier forms, from the Amazon S3 endpoint tables in the AWS
     * General Reference. The website endpoint spells its region with a dot in newer regions and a
     * dash in older ones, and both are in service, so both are accepted.
     */
    @ParameterizedTest
    @CsvSource({
            "www.example.com.s3-website.eu-west-2.localhost,          www.example.com",
            "www.example.com.s3-website-eu-west-1.localhost,          www.example.com",
            "my.bucket.s3-fips.us-east-1.localhost,                   my.bucket",
            "my.bucket.s3-fips.dualstack.us-east-1.amazonaws.com,     my.bucket",
            "my.bucket.s3-accelerate.amazonaws.com,                   my.bucket",
            "my.bucket.s3-accelerate.dualstack.amazonaws.com,         my.bucket",
            "my.bucket.s3-us-west-2.amazonaws.com,                    my.bucket",
            "my.bucket.s3.dualstack.ap-northeast-3.amazonaws.com,     my.bucket",
    })
    void everyPublishedQualifierFormResolvesTheBucket(String host, String expectedBucket) {
        assertEquals(expectedBucket, S3VirtualHostFilter.extractBucket(host, "localhost", DEFAULT_SUFFIXES));
    }

    // --- Regression: the region guard must reject region IDS, not region SHAPES ---

    /**
     * {@code [a-z]{2}-[a-z-]+-\d+} is the shape of an AWS region id, but it is also the shape of
     * perfectly ordinary bucket labels. Matching by shape rejected {@code data.my-cd-1} — a legal
     * bucket — dropped it back to path-style, and restored the exact false "bucket exists" 200
     * this filter exists to prevent. The guard has to test against the finite list of real region
     * ids instead.
     */
    @ParameterizedTest
    @CsvSource({
            "data.my-cd-1.localhost,          data.my-cd-1",
            "data.my-cd-1.localhost:4566,     data.my-cd-1",
            "reports.eu-team-2.localhost,     reports.eu-team-2",
            "assets.us-west-9.localhost,      assets.us-west-9",
            "logs.ap-corp-1.archive.localhost, logs.ap-corp-1.archive",
            "backup.no-such-region-12.localhost, backup.no-such-region-12",
    })
    void regionShapedBucketLabelsThatAreNotRegionIdsStillResolve(String host, String expectedBucket) {
        assertEquals(expectedBucket, S3VirtualHostFilter.extractBucket(host, "localhost", DEFAULT_SUFFIXES));
    }

    /**
     * The guard it replaces is still doing its job: a real region id in a non-leading label, with
     * no S3 qualifier, is another service's regional virtual host. Tightening to real ids must not
     * weaken this — including for regions outside the set the emulator advertises.
     */
    @ParameterizedTest
    @CsvSource({
            "123456789012.dkr.ecr.us-east-1.localhost,    localhost",
            "search-x.eu-north-1.es.localhost,            localhost",
            "abc.iot.me-central-1.localhost,              localhost",
            "abc.transfer.us-gov-west-1.localhost,        localhost",
            "abc.service.cn-northwest-1.localhost,        localhost",
    })
    void realRegionIdsStillMarkAnotherServicesRegionalHost(String host, String baseHostname) {
        assertNull(S3VirtualHostFilter.extractBucket(host, baseHostname, DEFAULT_SUFFIXES));
    }

    /**
     * The residual cost of the region rule, pinned rather than left implicit: a bucket whose name
     * carries a <em>real</em> region id in a non-leading position is still not reachable in the
     * unqualified virtual-hosted form. It stays reachable qualified and path-style, and it was
     * equally unreachable before this filter existed — so the cost is a recoverable one, paid to
     * avoid an unrecoverable cross-service hijack.
     */
    @Test
    void aBucketNamedAfterARealRegionIsStillTheDocumentedCost() {
        assertNull(S3VirtualHostFilter.extractBucket(
                "data.us-east-1.localhost", "localhost", DEFAULT_SUFFIXES));
        assertEquals("data.us-east-1", S3VirtualHostFilter.extractBucket(
                "data.us-east-1.s3.localhost", "localhost", DEFAULT_SUFFIXES));
    }
}
