package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.TransitionDefaultMinimumObjectSize;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SDK-level round-trip tests for {@code PutBucketLifecycleConfiguration} and
 * {@code GetBucketLifecycleConfiguration} using the AWS Java SDK v2.
 *
 * <p>The terraform-provider-aws v6.x stability wait
 * ({@code waitLifecycleConfigEquals}) compares the
 * {@code TransitionDefaultMinimumObjectSize} field between PUT input and GET
 * output. The SDK reads that field <em>only</em> from the
 * {@code x-amz-transition-default-minimum-object-size} response header, never
 * from the XML body. A REST Assured body-equality test against the raw HTTP
 * API would not catch a missing header (this is the gap that let issue #441
 * be auto-closed by a body-only fix). These tests assert SDK-parsed equality.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3LifecycleSdkIntegrationTest {

    private static final String BUCKET = "lifecycle-sdk-bucket";

    @TestHTTPResource("/")
    URI baseUri;

    private S3Client s3;

    @BeforeAll
    void setUp() {
        s3 = S3Client.builder()
                .endpointOverride(baseUri)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
        s3.createBucket(b -> b.bucket(BUCKET));
    }

    @AfterAll
    void tearDown() {
        try {
            s3.deleteBucketLifecycle(b -> b.bucket(BUCKET));
        } catch (Exception ignored) {
            // tolerate already-deleted
        }
        try {
            s3.deleteBucket(b -> b.bucket(BUCKET));
        } catch (Exception ignored) {
            // tolerate already-deleted
        }
        s3.close();
    }

    private static BucketLifecycleConfiguration sampleConfig() {
        return BucketLifecycleConfiguration.builder()
                .rules(LifecycleRule.builder()
                        .id("expire-everything")
                        .status(ExpirationStatus.ENABLED)
                        .filter(LifecycleRuleFilter.builder().prefix("").build())
                        .expiration(LifecycleExpiration.builder().days(365).build())
                        .build())
                .build();
    }

    @Test
    @Order(1)
    void putWithCustomSizeRoundTripsViaSdk() {
        // Send VARIES_BY_STORAGE_CLASS via the SDK's request field, which
        // serializes to the x-amz-transition-default-minimum-object-size
        // request header. PUT response should carry the same header.
        PutBucketLifecycleConfigurationResponse put = s3.putBucketLifecycleConfiguration(req -> req
                .bucket(BUCKET)
                .lifecycleConfiguration(sampleConfig())
                .transitionDefaultMinimumObjectSize(TransitionDefaultMinimumObjectSize.VARIES_BY_STORAGE_CLASS));
        assertEquals(TransitionDefaultMinimumObjectSize.VARIES_BY_STORAGE_CLASS,
                put.transitionDefaultMinimumObjectSize(),
                "PUT response must echo the request size header");

        // GET parses the header into the response field. This is the equality
        // terraform-provider-aws polls on; null/empty here is what hangs the
        // wait in issue #441.
        GetBucketLifecycleConfigurationResponse get =
                s3.getBucketLifecycleConfiguration(req -> req.bucket(BUCKET));
        assertEquals(TransitionDefaultMinimumObjectSize.VARIES_BY_STORAGE_CLASS,
                get.transitionDefaultMinimumObjectSize(),
                "GET must parse the size header into the response field");
        assertEquals(1, get.rules().size());
        assertEquals("expire-everything", get.rules().get(0).id());
        assertEquals(ExpirationStatus.ENABLED, get.rules().get(0).status());
    }

    @Test
    @Order(2)
    void putWithoutSizeFieldDefaultsTo128KOnGet() {
        // The SDK omits the request header when the field is null. Provider
        // default (and AWS default) is ALL_STORAGE_CLASSES_128_K. Floci must
        // emit it on GET so the provider's equality check passes without the
        // user setting transition_default_minimum_object_size in HCL.
        s3.putBucketLifecycleConfiguration(req -> req
                .bucket(BUCKET)
                .lifecycleConfiguration(sampleConfig()));

        GetBucketLifecycleConfigurationResponse get =
                s3.getBucketLifecycleConfiguration(req -> req.bucket(BUCKET));
        assertEquals(TransitionDefaultMinimumObjectSize.ALL_STORAGE_CLASSES_128_K,
                get.transitionDefaultMinimumObjectSize(),
                "GET must default to ALL_STORAGE_CLASSES_128_K when PUT omits the header");
    }
}
