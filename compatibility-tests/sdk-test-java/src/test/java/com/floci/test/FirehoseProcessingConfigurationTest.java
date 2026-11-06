package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Happy-path proof that the SDK's marshalling of the nested ProcessingConfiguration
 * shapes round-trips through Floci: create, the echo AWS fills in, and the
 * whole-block replacement on UpdateDestination. The validation matrix lives in the
 * emulator's unit tests.
 */
@DisplayName("Firehose processing configuration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseProcessingConfigurationTest {

    private static FirehoseClient firehose;
    private static final String STREAM_NAME = "sdk-processing-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-role";
    private static final String BUCKET_ARN = "arn:aws:s3:::floci-firehose-sdk-test";
    private static final String LAMBDA_ARN = "arn:aws:lambda:us-east-1:000000000000:function:transform";

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

    private static Processor lambdaProcessor(ProcessorParameter... parameters) {
        return Processor.builder().type(ProcessorType.LAMBDA).parameters(parameters).build();
    }

    private static ProcessorParameter parameter(ProcessorParameterName name, String value) {
        return ProcessorParameter.builder().parameterName(name).parameterValue(value).build();
    }

    private static ProcessingConfiguration stored() {
        return firehose.describeDeliveryStream(DescribeDeliveryStreamRequest.builder()
                        .deliveryStreamName(STREAM_NAME).build())
                .deliveryStreamDescription().destinations().get(0)
                .extendedS3DestinationDescription().processingConfiguration();
    }

    private static String currentVersion() {
        return firehose.describeDeliveryStream(DescribeDeliveryStreamRequest.builder()
                        .deliveryStreamName(STREAM_NAME).build())
                .deliveryStreamDescription().versionId();
    }

    @Test
    @Order(1)
    @DisplayName("Create accepts a Lambda processor and echoes it with AWS's own additions")
    void createWithProcessingConfiguration() {
        firehose.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .deliveryStreamType(DeliveryStreamType.DIRECT_PUT)
                .extendedS3DestinationConfiguration(ExtendedS3DestinationConfiguration.builder()
                        .roleARN(ROLE_ARN)
                        .bucketARN(BUCKET_ARN)
                        .processingConfiguration(ProcessingConfiguration.builder()
                                .enabled(true)
                                .processors(lambdaProcessor(
                                        parameter(ProcessorParameterName.BUFFER_INTERVAL_IN_SECONDS, "70"),
                                        parameter(ProcessorParameterName.BUFFER_SIZE_IN_M_BS, "2"),
                                        parameter(ProcessorParameterName.LAMBDA_ARN, LAMBDA_ARN)))
                                .build())
                        .build())
                .build());

        ProcessingConfiguration processing = stored();
        assertThat(processing.enabled()).isTrue();
        assertThat(processing.processors()).hasSize(1);

        // NumberOfRetries and RoleArn are added by the service, and the order is its
        // own rather than the order the parameters were sent in.
        List<ProcessorParameter> parameters = processing.processors().get(0).parameters();
        assertThat(parameters).extracting(p -> p.parameterName().toString())
                .containsExactly("LambdaArn", "NumberOfRetries", "RoleArn",
                        "BufferSizeInMBs", "BufferIntervalInSeconds");
        assertThat(parameters.get(1).parameterValue()).isEqualTo("3");
        assertThat(parameters.get(2).parameterValue()).isEqualTo(ROLE_ARN);
    }

    @Test
    @Order(2)
    @DisplayName("An update replaces the whole block rather than merging it")
    void updateReplacesTheBlock() {
        firehose.updateDestination(UpdateDestinationRequest.builder()
                .deliveryStreamName(STREAM_NAME)
                .currentDeliveryStreamVersionId(currentVersion())
                .destinationId("destinationId-000000000001")
                .extendedS3DestinationUpdate(ExtendedS3DestinationUpdate.builder()
                        .processingConfiguration(ProcessingConfiguration.builder()
                                .enabled(false)
                                .build())
                        .build())
                .build());

        ProcessingConfiguration processing = stored();
        assertThat(processing.enabled()).isFalse();
        assertThat(processing.processors()).isEmpty();
    }
}
