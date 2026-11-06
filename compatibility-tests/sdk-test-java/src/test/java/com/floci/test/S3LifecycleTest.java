package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.TransitionDefaultMinimumObjectSize;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDK-level round-trip tests for {@code PutBucketLifecycleConfiguration} and
 * {@code GetBucketLifecycleConfiguration}.
 *
 * <p>The terraform-provider-aws v6.x stability wait
 * ({@code waitLifecycleConfigEquals}) compares the
 * {@code TransitionDefaultMinimumObjectSize} field between PUT input and GET
 * output. The AWS Java/Go SDK reads that field <em>only</em> from the
 * {@code x-amz-transition-default-minimum-object-size} response header, never
 * from the XML body. A body-equality test against the raw HTTP API does not
 * catch a missing header (this is the gap that let issue #441 be auto-closed
 * by a body-only fix). These tests assert SDK-parsed equality.
 */
@DisplayName("S3 Lifecycle — TransitionDefaultMinimumObjectSize round-trip (#441)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3LifecycleTest {

    private static final String BUCKET = "compat-lifecycle-bucket";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    private static S3Client s3;

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException ignored) {}
    }

    @AfterAll
    static void cleanup() {
        if (s3 == null) return;
        try { s3.deleteBucketLifecycle(b -> b.bucket(BUCKET)); } catch (Exception ignored) {}
        try { s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build()); } catch (Exception ignored) {}
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
    @DisplayName("#441 PUT with VARIES_BY_STORAGE_CLASS round-trips via SDK on GET")
    void putWithCustomSizeRoundTripsViaSdk() {
        // The SDK serializes transitionDefaultMinimumObjectSize to the
        // x-amz-transition-default-minimum-object-size request header. PUT
        // response should carry the same header.
        PutBucketLifecycleConfigurationResponse put = s3.putBucketLifecycleConfiguration(req -> req
                .bucket(BUCKET)
                .lifecycleConfiguration(sampleConfig())
                .transitionDefaultMinimumObjectSize(TransitionDefaultMinimumObjectSize.VARIES_BY_STORAGE_CLASS));
        assertThat(put.transitionDefaultMinimumObjectSize())
                .as("PUT response must echo the request size header")
                .isEqualTo(TransitionDefaultMinimumObjectSize.VARIES_BY_STORAGE_CLASS);

        // GET parses the header into the response field. This is the equality
        // terraform-provider-aws polls on; null/empty here is what hangs the
        // wait in issue #441.
        GetBucketLifecycleConfigurationResponse get =
                s3.getBucketLifecycleConfiguration(req -> req.bucket(BUCKET));
        assertThat(get.transitionDefaultMinimumObjectSize())
                .as("GET must parse the size header into the response field")
                .isEqualTo(TransitionDefaultMinimumObjectSize.VARIES_BY_STORAGE_CLASS);
        assertThat(get.rules()).hasSize(1);
        assertThat(get.rules().get(0).id()).isEqualTo("expire-everything");
        assertThat(get.rules().get(0).status()).isEqualTo(ExpirationStatus.ENABLED);
    }

    @Test
    @Order(2)
    @DisplayName("#441 PUT without size field defaults to ALL_STORAGE_CLASSES_128_K on GET")
    void putWithoutSizeFieldDefaultsTo128KOnGet() {
        // The SDK omits the request header when the field is null. Provider
        // default (and AWS default) is ALL_STORAGE_CLASSES_128_K. This PUT
        // overwrites the VARIES_BY_STORAGE_CLASS config left by @Order(1) — the
        // header-less PUT must reset the stored value to the default, otherwise
        // a stale VARIES leaks through and the provider's equality check fails.
        s3.putBucketLifecycleConfiguration(req -> req
                .bucket(BUCKET)
                .lifecycleConfiguration(sampleConfig()));

        GetBucketLifecycleConfigurationResponse get =
                s3.getBucketLifecycleConfiguration(req -> req.bucket(BUCKET));
        assertThat(get.transitionDefaultMinimumObjectSize())
                .as("GET must default to ALL_STORAGE_CLASSES_128_K when PUT omits the header")
                .isEqualTo(TransitionDefaultMinimumObjectSize.ALL_STORAGE_CLASSES_128_K);
    }

    @Test
    @Order(3)
    @DisplayName("#5137 objects under an Expiration rule report expiry-date and rule-id")
    void objectsUnderAnExpirationRuleReportTheirExpiry() {
        // The configuration from @Order(2): Days 365 on every key, rule id expire-everything.
        String key = "expiring.txt";
        try {
            PutObjectResponse put = s3.putObject(req -> req.bucket(BUCKET).key(key), RequestBody.fromString("hi"));
            HeadObjectResponse head = s3.headObject(req -> req.bucket(BUCKET).key(key));

            // Creation time plus 365 days, rounded up to the next midnight UTC.
            Instant expiry = head.lastModified().plus(365, ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
            String expected = "expiry-date=\"" + HTTP_DATE.format(expiry) + "\", rule-id=\"expire-everything\"";
            assertThat(head.expiration()).isEqualTo(expected);
            assertThat(put.expiration()).isEqualTo(expected);
        } finally {
            s3.deleteObject(req -> req.bucket(BUCKET).key(key));
        }
    }
}
