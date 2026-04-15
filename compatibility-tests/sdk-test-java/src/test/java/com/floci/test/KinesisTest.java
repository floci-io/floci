package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DeleteStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamSummaryRequest;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("Kinesis")
class KinesisTest {

    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

    private static KinesisClient kinesis;

    @AfterAll
    static void cleanup() {
        if (kinesis != null) {
            kinesis.close();
        }
    }

    @Test
    void awsSdkV2UsesRootCborRoute() {
        AtomicReference<SdkHttpRequest> requestRef = new AtomicReference<>();

        kinesis = KinesisClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .addExecutionInterceptor(new ExecutionInterceptor() {
                            @Override
                            public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context,
                                                                    ExecutionAttributes executionAttributes) {
                                requestRef.set(context.httpRequest());
                                return context.httpRequest();
                            }
                        })
                        .build())
                .build();

        String streamName = TestFixtures.uniqueName("sdk-v2-kinesis-stream");

        try {
            assertDoesNotThrow(() -> kinesis.createStream(CreateStreamRequest.builder()
                    .streamName(streamName)
                    .shardCount(1)
                    .build()));

            var response = assertDoesNotThrow(() -> kinesis.describeStreamSummary(
                    DescribeStreamSummaryRequest.builder()
                            .streamName(streamName)
                            .build()));

            SdkHttpRequest request = requestRef.get();
            assertThat(request).isNotNull();
            assertThat(request.encodedPath()).isEqualTo("/");
            assertThat(request.firstMatchingHeader("Content-Type").orElse(null))
                    .isEqualTo("application/x-amz-cbor-1.1");
            assertThat(request.firstMatchingHeader("X-Amz-Target").orElse(null))
                    .isEqualTo("Kinesis_20131202.DescribeStreamSummary");
            assertThat(response.streamDescriptionSummary().streamName()).isEqualTo(streamName);
        } finally {
            assertDoesNotThrow(() -> kinesis.deleteStream(DeleteStreamRequest.builder()
                    .streamName(streamName)
                    .build()));
        }
    }
}
