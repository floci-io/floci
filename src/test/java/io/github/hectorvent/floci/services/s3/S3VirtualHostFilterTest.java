package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class S3VirtualHostFilterTest {

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
            // AWS S3 domains (fallback — independent of baseHostname)
            "my-bucket.s3.amazonaws.com,               localhost, my-bucket",
            "my-bucket.s3.amazonaws.com:443,            localhost, my-bucket",
            "my-bucket.s3.us-east-1.amazonaws.com,      localhost, my-bucket",
            "my-bucket.s3.eu-west-1.amazonaws.com:443,  localhost, my-bucket",
    })
    void extractsBucketFromVirtualHostedStyle(String host, String baseHostname, String expectedBucket) {
        assertEquals(expectedBucket, S3VirtualHostFilter.extractBucket(host, baseHostname));
    }

    // --- Path-style: service hostname alone — must NOT extract a bucket ---

    @ParameterizedTest
    @CsvSource({
            // Bare hostname — no dot, never virtual-hosted
            "localhost:4566, localhost",
            "localhost,      localhost",
            "plain-host,     plain-host",
            // K8s service hostname used as endpoint (path-style) — must NOT be rewritten
            "floci.default.svc.cluster.local,           localhost",
            "floci-service.namespace.svc.cluster.local, localhost",
            "my-svc.default.svc,                        localhost",
            // Remainder doesn't match baseHostname and isn't an AWS S3 domain
            "my-bucket.custom.internal, localhost",
            "my-bucket.emulator.local,  localhost",
    })
    void returnsNullForPathStyleOrMismatchedRemainder(String host, String baseHostname) {
        assertNull(S3VirtualHostFilter.extractBucket(host, baseHostname));
    }

    @ParameterizedTest
    @CsvSource({
            "192.168.1.1,      localhost",
            "192.168.1.1:4566, localhost",
            "127.0.0.1,        localhost",
            "10.0.0.1:9000,    localhost",
    })
    void returnsNullForIpAddresses(String host, String baseHostname) {
        assertNull(S3VirtualHostFilter.extractBucket(host, baseHostname));
    }

    @ParameterizedTest
    @NullSource
    void returnsNullForNullHost(String host) {
        assertNull(S3VirtualHostFilter.extractBucket(host, "localhost"));
    }

    @Test
    void returnsNullForNullBaseHostname() {
        // Without a baseHostname, only AWS S3 domains should match
        assertNull(S3VirtualHostFilter.extractBucket("my-bucket.localhost:4566", null));
        assertEquals("my-bucket", S3VirtualHostFilter.extractBucket("my-bucket.s3.amazonaws.com", null));
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
