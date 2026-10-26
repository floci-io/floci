package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
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
import software.amazon.awssdk.services.kinesis.model.DescribeStreamSummaryResponse;
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.kinesis.model.ListShardsRequest;
import software.amazon.awssdk.services.kinesis.model.ListShardsResponse;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.Shard;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;
import software.amazon.awssdk.services.kinesis.model.SplitShardRequest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
    @DisplayName("PutRecord and PutRecords route to open descendants after repeated splits")
    void writesUseOpenDescendantsAfterRepeatedSplits() {
        try (KinesisClient client = TestFixtures.kinesisClient()) {
            String streamName = TestFixtures.uniqueName("sdk-kinesis-reshard-routing");
            client.createStream(CreateStreamRequest.builder().streamName(streamName).shardCount(1).build());
            try {
                Shard parent = client.listShards(ListShardsRequest.builder().streamName(streamName).build())
                        .shards().get(0);
                client.putRecord(PutRecordRequest.builder().streamName(streamName).partitionKey("b")
                        .data(SdkBytes.fromUtf8String("before split")).build());
                split(client, streamName, parent);
                List<Shard> children = client.listShards(ListShardsRequest.builder().streamName(streamName).build())
                        .shards().stream().filter(shard -> shard.sequenceNumberRange().endingSequenceNumber() == null)
                        .collect(Collectors.toList());
                for (Shard child : children) {
                    split(client, streamName, child);
                }
                Set<String> openIds = client.listShards(ListShardsRequest.builder().streamName(streamName).build())
                        .shards().stream().filter(shard -> shard.sequenceNumberRange().endingSequenceNumber() == null)
                        .map(Shard::shardId).collect(Collectors.toSet());
                assertThat(openIds).hasSize(4);

                PutRecordResponse single = client.putRecord(PutRecordRequest.builder().streamName(streamName)
                        .partitionKey("b").data(SdkBytes.fromUtf8String("single")).build());
                assertThat(openIds).contains(single.shardId());
                PutRecordsResponse batch = client.putRecords(PutRecordsRequest.builder().streamName(streamName)
                        .records(PutRecordsRequestEntry.builder().partitionKey("b")
                                        .data(SdkBytes.fromUtf8String("batch ordinary")).build(),
                                PutRecordsRequestEntry.builder().partitionKey("b")
                                        .explicitHashKey(parent.hashKeyRange().endingHashKey())
                                        .data(SdkBytes.fromUtf8String("batch explicit")).build()).build());
                assertThat(batch.failedRecordCount()).isZero();
                assertThat(batch.records()).allSatisfy(record -> {
                    assertThat(openIds).contains(record.shardId());
                    assertThat(record.sequenceNumber()).isNotBlank();
                });
                String iterator = client.getShardIterator(GetShardIteratorRequest.builder().streamName(streamName)
                        .shardId(parent.shardId()).shardIteratorType(ShardIteratorType.TRIM_HORIZON).build())
                        .shardIterator();
                assertThat(client.getRecords(GetRecordsRequest.builder().shardIterator(iterator).build()).records())
                        .singleElement().satisfies(record ->
                                assertThat(record.data().asUtf8String()).isEqualTo("before split"));
            } finally {
                client.deleteStream(DeleteStreamRequest.builder().streamName(streamName).build());
            }
        }
    }

    private static void split(KinesisClient client, String streamName, Shard shard) {
        BigInteger start = new BigInteger(shard.hashKeyRange().startingHashKey());
        BigInteger end = new BigInteger(shard.hashKeyRange().endingHashKey());
        client.splitShard(SplitShardRequest.builder().streamName(streamName).shardToSplit(shard.shardId())
                .newStartingHashKey(start.add(end).add(BigInteger.ONE).divide(BigInteger.TWO).toString()).build());
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

            DescribeStreamSummaryResponse response = assertDoesNotThrow(() -> kinesis.describeStreamSummary(
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

    @Test
    @DisplayName("ListShards paginates through an opaque NextToken")
    void listShardsPaginatesThroughEveryPage() {
        KinesisClient client = TestFixtures.kinesisClient();
        String streamName = TestFixtures.uniqueName("sdk-v2-kinesis-pagination");

        try {
            assertDoesNotThrow(() -> client.createStream(CreateStreamRequest.builder()
                    .streamName(streamName)
                    .shardCount(3)
                    .build()));

            List<String> seen = new ArrayList<>();
            String nextToken = null;
            int pages = 0;
            do {
                ListShardsRequest.Builder request = ListShardsRequest.builder().maxResults(1);
                if (nextToken == null) {
                    // AWS documents NextToken as the only stream identifier once paging has started.
                    request.streamName(streamName);
                } else {
                    request.nextToken(nextToken);
                }
                ListShardsResponse page = assertDoesNotThrow(() -> client.listShards(request.build()));
                page.shards().forEach(shard -> seen.add(shard.shardId()));
                nextToken = page.nextToken();
                pages++;
            } while (nextToken != null && pages < 10);

            assertThat(seen).hasSize(3);
            assertThat(new HashSet<>(seen)).hasSize(3);
            assertThat(nextToken).isNull();
        } finally {
            assertDoesNotThrow(() -> client.deleteStream(DeleteStreamRequest.builder()
                    .streamName(streamName)
                    .build()));
            client.close();
        }
    }
}
