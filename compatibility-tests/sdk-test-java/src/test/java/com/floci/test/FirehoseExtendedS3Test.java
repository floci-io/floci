package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Issue #1043 — the Terraform/Pulumi provider creates delivery streams with
 * ExtendedS3DestinationConfiguration and reads ExtendedS3DestinationDescription
 * back (plus VersionId/DestinationId to drive UpdateDestination).
 */
@DisplayName("Firehose extended S3 destination — issue #1043")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseExtendedS3Test {

    private static FirehoseClient firehose;
    private static final String STREAM_NAME = "sdk-extended-s3-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-role";
    private static final String BUCKET_ARN = "arn:aws:s3:::floci-firehose-sdk-test";

    private static String versionId;
    private static String destinationId;

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
    @DisplayName("Create delivery stream with ExtendedS3DestinationConfiguration")
    void createExtendedS3DeliveryStream() {
        CreateDeliveryStreamResponse response = firehose.createDeliveryStream(
                CreateDeliveryStreamRequest.builder()
                        .deliveryStreamName(STREAM_NAME)
                        .deliveryStreamType(DeliveryStreamType.DIRECT_PUT)
                        .extendedS3DestinationConfiguration(ExtendedS3DestinationConfiguration.builder()
                                .roleARN(ROLE_ARN)
                                .bucketARN(BUCKET_ARN)
                                .prefix("events/data/")
                                .errorOutputPrefix("events/errors/")
                                .compressionFormat(CompressionFormat.GZIP)
                                .bufferingHints(BufferingHints.builder()
                                        .intervalInSeconds(120)
                                        .sizeInMBs(64)
                                        .build())
                                .build())
                        .build());

        assertThat(response.deliveryStreamARN()).contains(STREAM_NAME);
    }

    @Test
    @Order(2)
    @DisplayName("Describe returns ExtendedS3DestinationDescription (the provider read path)")
    void describeReturnsExtendedS3Description() {
        DeliveryStreamDescription desc = firehose.describeDeliveryStream(
                DescribeDeliveryStreamRequest.builder()
                        .deliveryStreamName(STREAM_NAME)
                        .build())
                .deliveryStreamDescription();

        assertThat(desc.versionId()).isNotBlank();
        assertThat(desc.deliveryStreamType()).isEqualTo(DeliveryStreamType.DIRECT_PUT);
        assertThat(desc.hasMoreDestinations()).isFalse();
        assertThat(desc.destinations()).hasSize(1);

        DestinationDescription destination = desc.destinations().get(0);
        assertThat(destination.destinationId()).isNotBlank();

        ExtendedS3DestinationDescription extended = destination.extendedS3DestinationDescription();
        assertThat(extended).as("ExtendedS3DestinationDescription must be present").isNotNull();
        assertThat(extended.roleARN()).isEqualTo(ROLE_ARN);
        assertThat(extended.bucketARN()).isEqualTo(BUCKET_ARN);
        assertThat(extended.prefix()).isEqualTo("events/data/");
        assertThat(extended.errorOutputPrefix()).isEqualTo("events/errors/");
        assertThat(extended.compressionFormat()).isEqualTo(CompressionFormat.GZIP);
        assertThat(extended.bufferingHints().sizeInMBs()).isEqualTo(64);
        assertThat(extended.bufferingHints().intervalInSeconds()).isEqualTo(120);
        assertThat(extended.encryptionConfiguration().noEncryptionConfig())
                .isEqualTo(NoEncryptionConfig.NO_ENCRYPTION);

        assertThat(destination.s3DestinationDescription())
                .as("deprecated S3DestinationDescription mirror must also be present")
                .isNotNull();
        assertThat(destination.s3DestinationDescription().bucketARN()).isEqualTo(BUCKET_ARN);

        versionId = desc.versionId();
        destinationId = destination.destinationId();
    }

    @Test
    @Order(3)
    @DisplayName("UpdateDestination applies changes and bumps VersionId")
    void updateDestination() {
        firehose.updateDestination(UpdateDestinationRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .currentDeliveryStreamVersionId(versionId)
                .destinationId(destinationId)
                .extendedS3DestinationUpdate(ExtendedS3DestinationUpdate.builder()
                        .compressionFormat(CompressionFormat.SNAPPY)
                        .bufferingHints(BufferingHints.builder()
                                .intervalInSeconds(60)
                                .sizeInMBs(128)
                                .build())
                        .build())
                .build());

        DeliveryStreamDescription desc = firehose.describeDeliveryStream(
                DescribeDeliveryStreamRequest.builder()
                        .deliveryStreamName(STREAM_NAME)
                        .build())
                .deliveryStreamDescription();

        assertThat(desc.versionId()).isNotEqualTo(versionId);
        ExtendedS3DestinationDescription extended =
                desc.destinations().get(0).extendedS3DestinationDescription();
        assertThat(extended.compressionFormat()).isEqualTo(CompressionFormat.SNAPPY);
        assertThat(extended.bufferingHints().sizeInMBs()).isEqualTo(128);
        assertThat(extended.roleARN()).as("untouched fields survive the update").isEqualTo(ROLE_ARN);
        assertThat(extended.prefix()).isEqualTo("events/data/");
    }

    @Test
    @Order(4)
    @DisplayName("UpdateDestination with stale version throws ConcurrentModificationException")
    void updateDestinationStaleVersion() {
        assertThatThrownBy(() -> firehose.updateDestination(UpdateDestinationRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .currentDeliveryStreamVersionId("99")
                .destinationId(destinationId)
                .extendedS3DestinationUpdate(ExtendedS3DestinationUpdate.builder()
                        .compressionFormat(CompressionFormat.GZIP)
                        .build())
                .build()))
                .isInstanceOf(ConcurrentModificationException.class);
    }
}
