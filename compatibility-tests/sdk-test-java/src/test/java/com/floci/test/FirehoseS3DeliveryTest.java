package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.*;
import software.amazon.awssdk.services.firehose.model.Record;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Issue #1886 — PutRecord / PutRecordBatch must actually deliver buffered
 * records to the S3 destination following BufferingHints (IntervalInSeconds /
 * SizeInMBs), instead of silently accepting and dropping them.
 */
@DisplayName("Firehose S3 buffered delivery — issue #1886")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseS3DeliveryTest {

    private static FirehoseClient firehose;
    private static S3Client s3;

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String STREAM_NAME = "sdk-delivery-" + SUFFIX;
    private static final String BUCKET = "floci-firehose-delivery-" + SUFFIX;
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-role";

    private static String firstDeliveredKey;

    @BeforeAll
    static void setup() {
        firehose = TestFixtures.firehoseClient();
        s3 = TestFixtures.s3Client();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void cleanup() {
        if (firehose != null) {
            try {
                firehose.deleteDeliveryStream(DeleteDeliveryStreamRequest.builder()
                        .deliveryStreamName(STREAM_NAME).build());
            } catch (Exception ignored) {}
            firehose.close();
        }
        if (s3 != null) {
            try {
                for (S3Object object : listObjects("")) {
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(object.key()).build());
                }
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
            } catch (Exception ignored) {}
            s3.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Create delivery stream with 1s/1MiB buffering hints")
    void createDeliveryStream() {
        firehose.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .deliveryStreamType(DeliveryStreamType.DIRECT_PUT)
                .s3DestinationConfiguration(S3DestinationConfiguration.builder()
                        .roleARN(ROLE_ARN)
                        .bucketARN("arn:aws:s3:::" + BUCKET)
                        .prefix("events/")
                        .bufferingHints(BufferingHints.builder()
                                .sizeInMBs(1)
                                .intervalInSeconds(1)
                                .build())
                        .build())
                .build());
    }

    @Test
    @Order(2)
    @DisplayName("PutRecord delivers to the destination bucket within the buffering interval")
    void putRecordIsDelivered() throws InterruptedException {
        firehose.putRecord(PutRecordRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .record(Record.builder()
                        .data(SdkBytes.fromUtf8String("{\"hello\":\"world\"}"))
                        .build())
                .build());

        List<S3Object> objects = awaitObjects("events/", 1);
        firstDeliveredKey = objects.get(0).key();
        String body = s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(BUCKET).key(firstDeliveredKey).build())
                .asString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("{\"hello\":\"world\"}\n");
    }

    @Test
    @Order(3)
    @DisplayName("PutRecordBatch delivers all records")
    void putRecordBatchIsDelivered() throws InterruptedException {
        PutRecordBatchResponse response = firehose.putRecordBatch(PutRecordBatchRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .records(
                        Record.builder().data(SdkBytes.fromUtf8String("{\"n\":1}")).build(),
                        Record.builder().data(SdkBytes.fromUtf8String("{\"n\":2}")).build())
                .build());
        assertThat(response.failedPutCount()).isZero();

        // S3 lists keys lexicographically, so identify the batch object by
        // excluding the key already delivered in the previous test.
        String batchKey = awaitObjects("events/", 2).stream()
                .map(S3Object::key)
                .filter(key -> !key.equals(firstDeliveredKey))
                .findFirst().orElseThrow();
        String body = s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(BUCKET).key(batchKey).build())
                .asString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("{\"n\":1}\n{\"n\":2}\n");
    }

    @Test
    @Order(4)
    @DisplayName("Out-of-range BufferingHints are rejected like AWS")
    void outOfRangeBufferingHintsAreRejected() {
        assertThatThrownBy(() -> firehose.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                .deliveryStreamName("sdk-bad-hints-" + SUFFIX)
                .s3DestinationConfiguration(S3DestinationConfiguration.builder()
                        .roleARN(ROLE_ARN)
                        .bucketARN("arn:aws:s3:::" + BUCKET)
                        .bufferingHints(BufferingHints.builder()
                                .sizeInMBs(129)
                                .intervalInSeconds(300)
                                .build())
                        .build())
                .build()))
                .isInstanceOf(FirehoseException.class)
                .hasMessageContaining("sizeInMBs");
    }

    @Test
    @Order(5)
    @DisplayName("Duplicate CreateDeliveryStream returns ResourceInUseException")
    void duplicateCreateIsRejected() {
        assertThatThrownBy(() -> firehose.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .s3DestinationConfiguration(S3DestinationConfiguration.builder()
                        .roleARN(ROLE_ARN)
                        .bucketARN("arn:aws:s3:::" + BUCKET)
                        .build())
                .build()))
                .isInstanceOf(ResourceInUseException.class);
    }

    /** Polls the bucket until at least {@code count} objects exist under the prefix (10s cap). */
    private static List<S3Object> awaitObjects(String prefix, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<S3Object> objects = listObjects(prefix);
        while (objects.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            objects = listObjects(prefix);
        }
        assertThat(objects).hasSizeGreaterThanOrEqualTo(count);
        return objects;
    }

    private static List<S3Object> listObjects(String prefix) {
        return s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(BUCKET).prefix(prefix).build()).contents();
    }
}
