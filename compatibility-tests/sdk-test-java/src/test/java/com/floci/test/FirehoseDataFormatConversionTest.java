package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Happy-path proof that the SDK's marshalling of the nested
 * DataFormatConversionConfiguration shapes round-trips through Floci:
 * create, describe echo, and the member-wise merge on UpdateDestination.
 * The validation matrix (error messages and precedence) lives in the
 * emulator's unit tests.
 */
@DisplayName("Firehose data format conversion configuration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseDataFormatConversionTest {

    private static FirehoseClient firehose;
    private static final String STREAM_NAME = "sdk-format-conversion-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-role";
    private static final String BUCKET_ARN = "arn:aws:s3:::floci-firehose-sdk-test";

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
            } catch (Exception e) {
                System.err.println("[WARN] Best-effort cleanup failed for delivery stream "
                        + STREAM_NAME + ": " + e.getMessage());
            }
            firehose.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Create accepts a full conversion configuration")
    void createWithConversionConfiguration() {
        CreateDeliveryStreamResponse response = firehose.createDeliveryStream(
                CreateDeliveryStreamRequest.builder()
                        .deliveryStreamName(STREAM_NAME)
                        .deliveryStreamType(DeliveryStreamType.DIRECT_PUT)
                        .extendedS3DestinationConfiguration(ExtendedS3DestinationConfiguration.builder()
                                .roleARN(ROLE_ARN)
                                .bucketARN(BUCKET_ARN)
                                .compressionFormat(CompressionFormat.UNCOMPRESSED)
                                .bufferingHints(BufferingHints.builder()
                                        .sizeInMBs(64)
                                        .intervalInSeconds(60)
                                        .build())
                                .dataFormatConversionConfiguration(DataFormatConversionConfiguration.builder()
                                        .enabled(true)
                                        .schemaConfiguration(SchemaConfiguration.builder()
                                                .roleARN(ROLE_ARN)
                                                .databaseName("analytics")
                                                .tableName("events")
                                                .region("us-east-1")
                                                .build())
                                        .inputFormatConfiguration(InputFormatConfiguration.builder()
                                                .deserializer(Deserializer.builder()
                                                        .openXJsonSerDe(OpenXJsonSerDe.builder().build())
                                                        .build())
                                                .build())
                                        .outputFormatConfiguration(OutputFormatConfiguration.builder()
                                                .serializer(Serializer.builder()
                                                        .parquetSerDe(ParquetSerDe.builder()
                                                                .compression(ParquetCompression.GZIP)
                                                                .writerVersion(ParquetWriterVersion.V2)
                                                                .build())
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build());

        assertThat(response.deliveryStreamARN()).contains(STREAM_NAME);
    }

    @Test
    @Order(2)
    @DisplayName("Describe echoes the conversion configuration as given")
    void describeEchoesConversionConfiguration() {
        ExtendedS3DestinationDescription destination = firehose.describeDeliveryStream(
                DescribeDeliveryStreamRequest.builder().deliveryStreamName(STREAM_NAME).build())
                .deliveryStreamDescription().destinations().get(0).extendedS3DestinationDescription();

        DataFormatConversionConfiguration conversion = destination.dataFormatConversionConfiguration();
        assertThat(conversion).isNotNull();
        assertThat(conversion.enabled()).isTrue();
        assertThat(conversion.schemaConfiguration().roleARN()).isEqualTo(ROLE_ARN);
        assertThat(conversion.schemaConfiguration().databaseName()).isEqualTo("analytics");
        assertThat(conversion.schemaConfiguration().tableName()).isEqualTo("events");
        assertThat(conversion.schemaConfiguration().region()).isEqualTo("us-east-1");
        assertThat(conversion.schemaConfiguration().catalogId()).isNull();
        assertThat(conversion.inputFormatConfiguration().deserializer().openXJsonSerDe()).isNotNull();
        ParquetSerDe parquet = conversion.outputFormatConfiguration().serializer().parquetSerDe();
        assertThat(parquet.compression()).isEqualTo(ParquetCompression.GZIP);
        assertThat(parquet.writerVersion()).isEqualTo(ParquetWriterVersion.V2);
        assertThat(parquet.blockSizeBytes()).isNull();
    }

    @Test
    @Order(3)
    @DisplayName("UpdateDestination with only Enabled=false keeps the stored members")
    void updateDisablingConversionKeepsStoredMembers() {
        DeliveryStreamDescription described = firehose.describeDeliveryStream(
                DescribeDeliveryStreamRequest.builder().deliveryStreamName(STREAM_NAME).build())
                .deliveryStreamDescription();

        firehose.updateDestination(UpdateDestinationRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .currentDeliveryStreamVersionId(described.versionId())
                .destinationId(described.destinations().get(0).destinationId())
                .extendedS3DestinationUpdate(ExtendedS3DestinationUpdate.builder()
                        .dataFormatConversionConfiguration(DataFormatConversionConfiguration.builder()
                                .enabled(false)
                                .build())
                        .build())
                .build());

        DataFormatConversionConfiguration conversion = firehose.describeDeliveryStream(
                DescribeDeliveryStreamRequest.builder().deliveryStreamName(STREAM_NAME).build())
                .deliveryStreamDescription().destinations().get(0)
                .extendedS3DestinationDescription().dataFormatConversionConfiguration();

        assertThat(conversion.enabled()).isFalse();
        assertThat(conversion.schemaConfiguration().databaseName()).isEqualTo("analytics");
        assertThat(conversion.outputFormatConfiguration().serializer().parquetSerDe()).isNotNull();
    }
}
