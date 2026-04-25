package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Firehose Delivery Streams")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseTest {

    private static FirehoseClient firehose;
    private static final String STREAM_NAME = "sdk-test-stream-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void setup() {
        firehose = TestFixtures.firehoseClient();
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
    }

    @Test
    @Order(1)
    @DisplayName("Create delivery stream")
    void createDeliveryStream() {
        CreateDeliveryStreamResponse response = firehose.createDeliveryStream(
                CreateDeliveryStreamRequest.builder()
                        .deliveryStreamName(STREAM_NAME)
                        .deliveryStreamType(DeliveryStreamType.DIRECT_PUT)
                        .s3DestinationConfiguration(S3DestinationConfiguration.builder()
                                .bucketARN("arn:aws:s3:::floci-firehose-sdk-test")
                                .roleARN("arn:aws:iam::000000000000:role/firehose-role")
                                .bufferingHints(BufferingHints.builder()
                                        .intervalInSeconds(60)
                                        .sizeInMBs(1)
                                        .build())
                                .build())
                        .build());

        assertThat(response.deliveryStreamARN()).contains(STREAM_NAME);
    }

    @Test
    @Order(2)
    @DisplayName("Describe delivery stream")
    void describeDeliveryStream() {
        DescribeDeliveryStreamResponse response = firehose.describeDeliveryStream(
                DescribeDeliveryStreamRequest.builder()
                        .deliveryStreamName(STREAM_NAME)
                        .build());

        DeliveryStreamDescription desc = response.deliveryStreamDescription();
        assertThat(desc.deliveryStreamName()).isEqualTo(STREAM_NAME);
        assertThat(desc.deliveryStreamStatus()).isEqualTo(DeliveryStreamStatus.ACTIVE);
        assertThat(desc.deliveryStreamARN()).contains(STREAM_NAME);
    }

    @Test
    @Order(3)
    @DisplayName("List delivery streams includes created stream")
    void listDeliveryStreams() {
        ListDeliveryStreamsResponse response = firehose.listDeliveryStreams(
                ListDeliveryStreamsRequest.builder().build());

        assertThat(response.deliveryStreamNames()).contains(STREAM_NAME);
    }

    @Test
    @Order(4)
    @DisplayName("Put single record")
    void putRecord() {
        PutRecordResponse response = firehose.putRecord(PutRecordRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .record(software.amazon.awssdk.services.firehose.model.Record.builder()
                        .data(SdkBytes.fromString("{\"event\":\"test\"}", StandardCharsets.UTF_8))
                        .build())
                .build());

        assertThat(response.recordId()).isNotBlank();
    }

    @Test
    @Order(5)
    @DisplayName("Put record batch")
    void putRecordBatch() {
        List<software.amazon.awssdk.services.firehose.model.Record> records = List.of(
                software.amazon.awssdk.services.firehose.model.Record.builder().data(SdkBytes.fromString("{\"i\":1}", StandardCharsets.UTF_8)).build(),
                software.amazon.awssdk.services.firehose.model.Record.builder().data(SdkBytes.fromString("{\"i\":2}", StandardCharsets.UTF_8)).build(),
                software.amazon.awssdk.services.firehose.model.Record.builder().data(SdkBytes.fromString("{\"i\":3}", StandardCharsets.UTF_8)).build()
        );

        PutRecordBatchResponse response = firehose.putRecordBatch(PutRecordBatchRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .records(records)
                .build());

        assertThat(response.failedPutCount()).isEqualTo(0);
        assertThat(response.requestResponses()).hasSize(3);
        response.requestResponses().forEach(r -> assertThat(r.recordId()).isNotBlank());
    }

    @Test
    @Order(6)
    @DisplayName("Describe non-existent stream throws ResourceNotFoundException")
    void describeNonExistentStream() {
        assertThatThrownBy(() -> firehose.describeDeliveryStream(
                DescribeDeliveryStreamRequest.builder()
                        .deliveryStreamName("nonexistent-stream-xyz")
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(7)
    @DisplayName("Delete delivery stream")
    void deleteDeliveryStream() {
        firehose.deleteDeliveryStream(DeleteDeliveryStreamRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .build());

        assertThatThrownBy(() -> firehose.describeDeliveryStream(
                DescribeDeliveryStreamRequest.builder()
                        .deliveryStreamName(STREAM_NAME)
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
