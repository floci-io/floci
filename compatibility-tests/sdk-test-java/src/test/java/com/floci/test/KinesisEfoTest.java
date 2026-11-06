package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.ConsumerStatus;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamConsumerRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamConsumerResponse;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.RegisterStreamConsumerRequest;
import software.amazon.awssdk.services.kinesis.model.RegisterStreamConsumerResponse;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;
import software.amazon.awssdk.services.kinesis.model.StartingPosition;
import software.amazon.awssdk.services.kinesis.model.SubscribeToShardEvent;
import software.amazon.awssdk.services.kinesis.model.SubscribeToShardEventStream;
import software.amazon.awssdk.services.kinesis.model.SubscribeToShardRequest;
import software.amazon.awssdk.services.kinesis.model.SubscribeToShardResponseHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("Kinesis Enhanced Fan-Out (SubscribeToShard)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KinesisEfoTest {

    private static final String STREAM_NAME = "efo-compat-test-stream";
    private static final String CONSUMER_NAME = "efo-compat-test-consumer";

    private static KinesisClient kinesis;
    private static KinesisAsyncClient kinesisAsync;
    private static String streamArn;
    private static String shardId;
    private static String consumerArn;

    @BeforeAll
    static void setup() {
        kinesis = TestFixtures.kinesisClient();
        kinesisAsync = TestFixtures.kinesisAsyncClient();

        assertDoesNotThrow(() -> kinesis.createStream(r -> r.streamName(STREAM_NAME).shardCount(1)));

        DescribeStreamResponse desc = kinesis.describeStream(DescribeStreamRequest.builder().streamName(STREAM_NAME).build());
        streamArn = desc.streamDescription().streamARN();
        shardId = desc.streamDescription().shards().get(0).shardId();

        kinesis.putRecord(PutRecordRequest.builder()
                .streamName(STREAM_NAME)
                .data(SdkBytes.fromUtf8String("{\"event\":\"efo-compat-test\"}"))
                .partitionKey("pk1")
                .build());
    }

    @AfterAll
    static void cleanup() {
        try {
            kinesis.deleteStream(r -> r.streamName(STREAM_NAME));
        } catch (Exception ignored) {}
        if (kinesis != null) kinesis.close();
        if (kinesisAsync != null) kinesisAsync.close();
    }

    @Test
    @Order(1)
    void registerStreamConsumer() {
        RegisterStreamConsumerResponse response = assertDoesNotThrow(() ->
                kinesis.registerStreamConsumer(RegisterStreamConsumerRequest.builder()
                        .streamARN(streamArn)
                        .consumerName(CONSUMER_NAME)
                        .build()));

        assertThat(response.consumer()).isNotNull();
        assertThat(response.consumer().consumerName()).isEqualTo(CONSUMER_NAME);
        assertThat(response.consumer().consumerARN()).isNotBlank();
        consumerArn = response.consumer().consumerARN();
    }

    @Test
    @Order(2)
    void describeStreamConsumer() {
        assertThat(consumerArn).as("consumerArn must be set by registerStreamConsumer").isNotBlank();

        DescribeStreamConsumerResponse response = assertDoesNotThrow(() ->
                kinesis.describeStreamConsumer(DescribeStreamConsumerRequest.builder()
                        .consumerARN(consumerArn)
                        .build()));

        assertThat(response.consumerDescription().consumerName()).isEqualTo(CONSUMER_NAME);
        assertThat(response.consumerDescription().consumerStatus()).isEqualTo(ConsumerStatus.ACTIVE);
    }

    @Test
    @Order(3)
    void subscribeToShard() throws Exception {
        assertThat(consumerArn).as("consumerArn must be set by registerStreamConsumer").isNotBlank();

        List<SubscribeToShardEvent> received = new ArrayList<>();

        SubscribeToShardResponseHandler handler = SubscribeToShardResponseHandler.builder()
                .subscriber(event -> {
                    if (event instanceof SubscribeToShardEvent e) {
                        received.add(e);
                    }
                })
                .build();

        CompletableFuture<Void> future = kinesisAsync.subscribeToShard(
                SubscribeToShardRequest.builder()
                        .consumerARN(consumerArn)
                        .shardId(shardId)
                        .startingPosition(StartingPosition.builder()
                                .type(ShardIteratorType.TRIM_HORIZON)
                                .build())
                        .build(),
                handler);

        future.get(15, TimeUnit.SECONDS);

        assertThat(received).as("at least one SubscribeToShardEvent expected").isNotEmpty();
        assertThat(received.get(0).records()).isNotNull();
    }
}
