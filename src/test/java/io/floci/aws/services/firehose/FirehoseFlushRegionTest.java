package io.floci.aws.services.firehose;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.PutObjectOptions;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.FirehoseRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * A flush outside any request, as the scheduled one runs, delivers as its stream's account and in its
 * stream's Region: every store the delivery touches, the Regional Glue catalog included, reads both from
 * the request scope.
 */
@QuarkusTest
class FirehoseFlushRegionTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(731200000000L);
    private static final String REGION = "eu-west-1";

    @InjectSpy
    S3Service s3Service;

    @Inject
    FirehoseService firehoseService;

    @Inject
    RegionResolver regionResolver;

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureAwsJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aFlushOutsideARequestDeliversInItsStreamsAccountAndRegion() {
        String account = Long.toString(ACCOUNTS.incrementAndGet());
        String stream = "flush-region-" + account;
        String bucket = "flush-region-" + account;
        AtomicReference<String> scope = new AtomicReference<>();
        doAnswer(invocation -> {
            scope.set(regionResolver.getAccountId() + " " + regionResolver.getRegion());
            return invocation.callRealMethod();
        }).when(s3Service).putObject(eq(bucket), anyString(), any(byte[].class), anyString(), anyMap(),
                any(PutObjectOptions.class));
        firehose(account, "CreateDeliveryStream", """
                {"DeliveryStreamName": "%s", "DeliveryStreamType": "DirectPut",
                 "ExtendedS3DestinationConfiguration": {
                   "RoleARN": "arn:aws:iam::%s:role/firehose-delivery-role",
                   "BucketARN": "arn:aws:s3:::%s",
                   "BufferingHints": {"SizeInMBs": 5, "IntervalInSeconds": 60}}}
                """.formatted(stream, account, bucket)).statusCode(200);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(),
                EnforcementFixtures.auth(account, "s3"), bucket));
        cleanup.register(() -> firehose(account, "DeleteDeliveryStream",
                "{\"DeliveryStreamName\": \"" + stream + "\"}"));
        firehose(account, "PutRecord", "{\"DeliveryStreamName\": \"" + stream + "\", \"Record\": {\"Data\": \""
                + FirehoseRequests.encode("{\"n\":1}") + "\"}}").statusCode(200);

        firehoseService.flush(account, REGION, stream);

        assertEquals(account + " " + REGION, scope.get());
    }

    private static ValidatableResponse firehose(String account, String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Firehose_20150804." + action)
                .header("Authorization", EnforcementFixtures.auth(account, REGION, "firehose"))
                .body(body)
        .when().post("/")
        .then();
    }
}
