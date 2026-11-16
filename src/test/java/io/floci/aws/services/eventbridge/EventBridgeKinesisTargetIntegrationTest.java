package io.floci.aws.services.eventbridge;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.Record;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventBridgeKinesisTargetIntegrationTest {

    private static final String ACCOUNT = "725018800076";

    private EventBridgeClient events;
    private KinesisClient kinesis;
    private String stream;
    private String rule;
    private String streamArn;

    @BeforeEach
    void createStreamAndRule() {
        events = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, Region.US_EAST_1);
        kinesis = AwsTestClients.create(KinesisClient.builder(), ACCOUNT, Region.US_EAST_1);
        String suffix = UUID.randomUUID().toString();
        stream = "events-kinesis-" + suffix;
        rule = "events-kinesis-" + suffix;
        kinesis.createStream(request -> request.streamName(stream).shardCount(1));
        streamArn = kinesis.describeStream(request -> request.streamName(stream))
                .streamDescription().streamARN();
        events.putRule(request -> request.name(rule).eventPattern("{\"source\":[\"example.orders\"]}"));
    }

    @AfterEach
    void deleteStreamAndRule() {
        try {
            events.removeTargets(request -> request.rule(rule).ids("stream"));
            events.deleteRule(request -> request.name(rule));
            kinesis.deleteStream(request -> request.streamName(stream));
        } finally {
            kinesis.close();
            events.close();
        }
    }

    @Test
    void partitionKeyPathUsesOriginalEventAndWritesTransformedData() {
        assertEquals(0, events.putTargets(request -> request.rule(rule).targets(Target.builder()
                .id("stream").arn(streamArn).inputPath("$.detail")
                .kinesisParameters(parameters -> parameters.partitionKeyPath("$.detail.items[0].id"))
                .build())).failedEntryCount());

        Target listed = events.listTargetsByRule(request -> request.rule(rule)).targets().getFirst();
        assertNotNull(listed.kinesisParameters());
        assertEquals("$.detail.items[0].id", listed.kinesisParameters().partitionKeyPath());

        events.putEvents(request -> request.entries(entry -> entry.source("example.orders")
                .detailType("OrderPlaced").detail("{\"items\":[{\"id\":\"order-123\"}]}")));

        List<Record> records = records();
        assertEquals(1, records.size());
        assertEquals("order-123", records.getFirst().partitionKey());
        assertEquals("{\"items\":[{\"id\":\"order-123\"}]}", records.getFirst().data().asUtf8String());
    }

    @Test
    void absentKinesisParametersUsesEventIdAsPartitionKey() {
        assertEquals(0, events.putTargets(request -> request.rule(rule).targets(Target.builder()
                .id("stream").arn(streamArn).build())).failedEntryCount());
        Target listed = events.listTargetsByRule(request -> request.rule(rule)).targets().getFirst();
        assertNull(listed.kinesisParameters());

        String eventId = events.putEvents(request -> request.entries(entry -> entry.source("example.orders")
                .detailType("OrderPlaced").detail("{\"orderId\":\"default\"}")))
                .entries().getFirst().eventId();

        List<Record> records = records();
        assertEquals(1, records.size());
        assertEquals(eventId, records.getFirst().partitionKey());
    }

    @Test
    void missingPartitionKeyPathValueDeadLettersWithoutWritingARecord() {
        try (SqsClient sqs = AwsTestClients.create(SqsClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String queueUrl = sqs.createQueue(request -> request.queueName(rule)).queueUrl();
            try {
                String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
                assertEquals(0, events.putTargets(request -> request.rule(rule).targets(Target.builder()
                        .id("stream").arn(streamArn)
                        .kinesisParameters(parameters -> parameters.partitionKeyPath("$.detail.missing"))
                        .deadLetterConfig(config -> config.arn(queueArn)).build())).failedEntryCount());

                events.putEvents(request -> request.entries(entry -> entry.source("example.orders")
                        .detailType("OrderPlaced").detail("{\"orderId\":\"missing-key\"}")));

                assertTrue(records().isEmpty());
                List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                        .messageAttributeNames("All")).messages();
                assertEquals(1, messages.size());
                assertEquals(streamArn, messages.getFirst().messageAttributes().get("TARGET_ARN").stringValue());
            } finally {
                sqs.deleteQueue(request -> request.queueUrl(queueUrl));
            }
        }
    }

    @Test
    void kinesisParametersRequirePartitionKeyPathWithoutReplacingTheExistingTarget() {
        Target original = Target.builder().id("stream").arn(streamArn).build();
        assertEquals(0, events.putTargets(request -> request.rule(rule).targets(original)).failedEntryCount());

        EventBridgeException error = assertThrows(EventBridgeException.class,
                () -> events.putTargets(request -> request.rule(rule).targets(original.toBuilder()
                        .kinesisParameters(parameters -> parameters.build()).build())));
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
        assertEquals(List.of(original), events.listTargetsByRule(request -> request.rule(rule)).targets());
    }

    @Test
    void targetArnAccountSelectsTheDestinationStream() {
        String destinationAccount = "725018800077";
        try (KinesisClient destination = AwsTestClients.create(KinesisClient.builder(),
                destinationAccount, Region.US_EAST_1)) {
            destination.createStream(request -> request.streamName(stream).shardCount(1));
            try {
                String destinationArn = destination.describeStream(request -> request.streamName(stream))
                        .streamDescription().streamARN();
                assertEquals(0, events.putTargets(request -> request.rule(rule).targets(Target.builder()
                        .id("stream").arn(destinationArn)
                        .roleArn("arn:aws:iam::" + ACCOUNT + ":role/eventbridge-kinesis-target")
                        .build())).failedEntryCount());

                events.putEvents(request -> request.entries(entry -> entry.source("example.orders")
                        .detailType("OrderPlaced").detail("{\"orderId\":\"cross-account\"}")));

                assertEquals(1, records(destination).size());
                assertTrue(records().isEmpty());
            } finally {
                destination.deleteStream(request -> request.streamName(stream));
            }
        }
    }

    @Test
    void westRegionRuleAndTargetDoNotWriteToEastDecoyStream() {
        String westRule = rule + "-west";
        try (EventBridgeClient westEvents = AwsTestClients.create(EventBridgeClient.builder(),
                ACCOUNT, Region.US_WEST_2);
             KinesisClient westKinesis = AwsTestClients.create(KinesisClient.builder(),
                     ACCOUNT, Region.US_WEST_2)) {
            westKinesis.createStream(request -> request.streamName(stream).shardCount(1));
            westEvents.putRule(request -> request.name(westRule)
                    .eventPattern("{\"source\":[\"example.orders\"]}"));
            try {
                String westArn = westKinesis.describeStream(request -> request.streamName(stream))
                        .streamDescription().streamARN();
                assertEquals(0, westEvents.putTargets(request -> request.rule(westRule).targets(Target.builder()
                        .id("stream").arn(westArn).build())).failedEntryCount());

                westEvents.putEvents(request -> request.entries(entry -> entry.source("example.orders")
                        .detailType("OrderPlaced").detail("{\"orderId\":\"west\"}")));

                assertEquals(1, records(westKinesis).size());
                assertTrue(records().isEmpty());
            } finally {
                westEvents.removeTargets(request -> request.rule(westRule).ids("stream"));
                westEvents.deleteRule(request -> request.name(westRule));
                westKinesis.deleteStream(request -> request.streamName(stream));
            }
        }
    }

    @Test
    void missingDestinationStreamDeadLettersTheEvent() {
        String missingArn = streamArn.replace(stream, stream + "-missing");
        try (SqsClient sqs = AwsTestClients.create(SqsClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String queueUrl = sqs.createQueue(request -> request.queueName(rule)).queueUrl();
            try {
                String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
                assertEquals(0, events.putTargets(request -> request.rule(rule).targets(Target.builder()
                        .id("stream").arn(missingArn)
                        .deadLetterConfig(config -> config.arn(queueArn)).build())).failedEntryCount());

                events.putEvents(request -> request.entries(entry -> entry.source("example.orders")
                        .detailType("OrderPlaced").detail("{\"orderId\":\"missing-stream\"}")));

                assertTrue(records().isEmpty());
                List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                        .messageAttributeNames("All")).messages();
                assertEquals(1, messages.size());
                assertEquals(missingArn, messages.getFirst().messageAttributes().get("TARGET_ARN").stringValue());
                assertEquals("NO_RESOURCE", messages.getFirst().messageAttributes().get("ERROR_CODE").stringValue());
            } finally {
                sqs.deleteQueue(request -> request.queueUrl(queueUrl));
            }
        }
    }

    private List<Record> records() {
        return records(kinesis);
    }

    private List<Record> records(KinesisClient client) {
        String shard = client.describeStream(request -> request.streamName(stream))
                .streamDescription().shards().getFirst().shardId();
        String iterator = client.getShardIterator(request -> request.streamName(stream)
                .shardId(shard).shardIteratorType(ShardIteratorType.TRIM_HORIZON)).shardIterator();
        return client.getRecords(request -> request.shardIterator(iterator)).records();
    }
}
