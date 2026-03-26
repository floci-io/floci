package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class S3VirtualHostFilterTest {

    @ParameterizedTest
    @CsvSource({
            // localhost variants
            "my-bucket.localhost:4566,       my-bucket",
            "my-bucket.localhost,            my-bucket",
            // S3-style domains
            "my-bucket.s3.amazonaws.com,     my-bucket",
            "my-bucket.s3.amazonaws.com:443, my-bucket",
            "my-bucket.s3.us-east-1.amazonaws.com,     my-bucket",
            "my-bucket.s3.eu-west-1.amazonaws.com:443, my-bucket",
            // Custom / arbitrary hostnames
            "my-bucket.myhost:4566,          my-bucket",
            "my-bucket.custom.internal:9000, my-bucket",
            "my-bucket.emulator.local,       my-bucket",
    })
    void extractsBucketFromVirtualHostedStyle(String host, String expectedBucket) {
        assertEquals(expectedBucket, S3VirtualHostFilter.extractBucket(host));
    }

    @ParameterizedTest
    @CsvSource({
            "localhost:4566",
            "localhost",
            "plain-host",
            "plain-host:8080",
            "192.168.1.1",
            "192.168.1.1:4566",
            "127.0.0.1",
            "10.0.0.1:9000",
    })
    @NullSource
    void returnsNullForNonVirtualHostedStyle(String host) {
        assertNull(S3VirtualHostFilter.extractBucket(host));
    }
}
