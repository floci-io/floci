package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Validates that the AWS SDK Java accepts the MD5OfMessageAttributes Floci
 * returns for standard, FIFO, and high-throughput FIFO queues. The SDK
 * validates the MD5 client-side and throws when the response checksum
 * doesn't match its own canonical recomputation, so a successful
 * SendMessage is proof of wire-level MD5 parity.
 */
@DisplayName("SQS MD5OfMessageAttributes wire compatibility")
class SqsMd5Test {

    private static SqsClient sqs;

    @BeforeAll
    static void setup() {
        sqs = TestFixtures.sqsClient();
    }

    @AfterAll
    static void cleanup() {
        if (sqs != null) {
            sqs.close();
        }
    }

    @Test
    void standardQueueSendMessageWithAttributesPassesSdkMd5Validation() {
        String queueName = "md5-std-" + UUID.randomUUID();
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName).build()).queueUrl();

        var attrs = Map.of(
                "trace-id", MessageAttributeValue.builder()
                        .dataType("String").stringValue("abc-123").build(),
                "priority", MessageAttributeValue.builder()
                        .dataType("Number").stringValue("42").build());

        var resp = sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody("hello standard")
                .messageAttributes(attrs)
                .build());

        assertThat(resp.md5OfMessageAttributes()).isNotNull();

        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }

    @Test
    void fifoQueueSendMessageWithAttributesPassesSdkMd5Validation() {
        String queueName = "md5-fifo-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build()).queueUrl();

        var attrs = Map.of(
                "trace-id", MessageAttributeValue.builder()
                        .dataType("String").stringValue("abc-123").build(),
                "priority", MessageAttributeValue.builder()
                        .dataType("Number").stringValue("42").build());

        var resp = sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody("hello fifo")
                .messageGroupId("g1")
                .messageAttributes(attrs)
                .build());

        assertThat(resp.md5OfMessageAttributes()).isNotNull();

        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }

    @Test
    void fifoDedupReplayReturnsOriginalIdsAndRecomputedMd5() {
        // Within the 5-minute dedup window, a second SendMessage with the same
        // MessageDeduplicationId must return MD5s computed from the CURRENT
        // request body and attributes (not the original message's cached
        // MD5s), otherwise the SDK throws MD5 mismatch when the two sends
        // differ at all. The MessageId / SequenceNumber assertions guard
        // against a regression that silently disabled dedup.
        String queueName = "md5-dedup-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))
                .build()).queueUrl();

        var firstAttrs = Map.of(
                "k", MessageAttributeValue.builder().dataType("String").stringValue("v1").build());
        var secondAttrs = Map.of(
                "k", MessageAttributeValue.builder().dataType("String").stringValue("v2").build());

        var firstResponse = sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody("body-1").messageGroupId("g1")
                .messageDeduplicationId("same-dedup")
                .messageAttributes(firstAttrs).build());

        var secondResponse = sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody("body-2").messageGroupId("g1")
                .messageDeduplicationId("same-dedup")
                .messageAttributes(secondAttrs).build());

        assertThat(secondResponse.messageId())
                .as("FIFO dedup must return the original MessageId")
                .isEqualTo(firstResponse.messageId());
        assertThat(secondResponse.sequenceNumber())
                .as("FIFO dedup must return the original SequenceNumber")
                .isEqualTo(firstResponse.sequenceNumber());

        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }

    @Test
    void fifoQueueBinaryAttributePassesSdkMd5Validation() {
        String queueName = "md5-fifo-bin-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build()).queueUrl();

        var attrs = Map.of(
                "blob", MessageAttributeValue.builder()
                        .dataType("Binary")
                        .binaryValue(SdkBytes.fromByteArray(new byte[]{1, 2, 3, 4, 5}))
                        .build());

        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody("binary").messageGroupId("g1")
                .messageAttributes(attrs).build());

        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }

    @Test
    void fifoQueueCustomTypeNamePassesSdkMd5Validation() {
        String queueName = "md5-fifo-custom-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build()).queueUrl();

        var attrs = Map.of(
                "priority", MessageAttributeValue.builder()
                        .dataType("Number.int").stringValue("42").build());

        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody("custom").messageGroupId("g1")
                .messageAttributes(attrs).build());

        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }

    @Test
    void highThroughputFifoQueueSendMessageWithAttributesPassesSdkMd5Validation() {
        String queueName = "md5-fair-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true",
                        QueueAttributeName.DEDUPLICATION_SCOPE, "messageGroup",
                        QueueAttributeName.FIFO_THROUGHPUT_LIMIT, "perMessageGroupId"))
                .build()).queueUrl();

        var attrs = Map.of(
                "key", MessageAttributeValue.builder()
                        .dataType("String").stringValue("value").build());

        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody("hello fair")
                .messageGroupId("g1")
                .messageAttributes(attrs)
                .build());

        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }
}
