package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3VirtualHostFilterTest {

    /** Always-on service-host suffix set with no configured extra suffixes. */
    private static final Set<String> DEFAULT_SUFFIXES =
            Set.of("localhost", "localhost.floci.io", "localhost.localstack.cloud");

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
}
