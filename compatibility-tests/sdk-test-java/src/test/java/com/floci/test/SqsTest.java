package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SQS Simple Queue Service")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqsTest {

    private static SqsClient sqs;
    private static String queueUrl;
    private static String dlqUrl;

    @BeforeAll
    static void setup() {
        sqs = TestFixtures.sqsClient();
    }

    @AfterAll
    static void cleanup() {
        if (sqs != null) {
            try {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            } catch (Exception ignored) {}
            try {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(dlqUrl).build());
            } catch (Exception ignored) {}
            sqs.close();
        }
    }

    @Test
    @Order(1)
    void createQueue() {
        CreateQueueResponse response = sqs.createQueue(CreateQueueRequest.builder()
                .queueName("sdk-test-queue")
                .build());
        queueUrl = response.queueUrl();

        assertThat(queueUrl).isNotNull().contains("sdk-test-queue");
    }

    @Test
    @Order(2)
    void getQueueUrl() {
        GetQueueUrlResponse response = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                .queueName("sdk-test-queue")
                .build());

        assertThat(response.queueUrl()).isEqualTo(queueUrl);
    }

    @Test
    @Order(3)
    void listQueues() {
        ListQueuesResponse response = sqs.listQueues();

        assertThat(response.queueUrls())
                .anyMatch(u -> u.contains("sdk-test-queue"));
    }

    @Test
    @Order(4)
    void sendMessage() {
        SendMessageResponse response = sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody("Hello from AWS SDK!")
                .build());

        assertThat(response.messageId()).isNotEmpty();
    }

    @Test
    @Order(5)
    void receiveMessage() {
        ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .build());

        assertThat(response.messages()).isNotEmpty();
        assertThat(response.messages().get(0).body()).isEqualTo("Hello from AWS SDK!");

        // Cleanup message
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(response.messages().get(0).receiptHandle())
                .build());
    }

    @Test
    @Order(6)
    void queueEmptyAfterDelete() {
        ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .build());

        assertThat(response.messages()).isEmpty();
    }

    @Test
    @Order(7)
    void sendMessageBatch() {
        SendMessageBatchResponse response = sqs.sendMessageBatch(SendMessageBatchRequest.builder()
                .queueUrl(queueUrl)
                .entries(
                        SendMessageBatchRequestEntry.builder().id("msg1").messageBody("Batch message 1").build(),
                        SendMessageBatchRequestEntry.builder().id("msg2").messageBody("Batch message 2").build(),
                        SendMessageBatchRequestEntry.builder().id("msg3").messageBody("Batch message 3").build()
                )
                .build());

        assertThat(response.successful()).hasSize(3);
        assertThat(response.failed()).isEmpty();
    }

    @Test
    @Order(8)
    void deleteMessageBatch() {
        ReceiveMessageResponse receiveResponse = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(3)
                .build());

        List<DeleteMessageBatchRequestEntry> deleteEntries = new ArrayList<>();
        for (int i = 0; i < receiveResponse.messages().size(); i++) {
            deleteEntries.add(DeleteMessageBatchRequestEntry.builder()
                    .id("del" + i)
                    .receiptHandle(receiveResponse.messages().get(i).receiptHandle())
                    .build());
        }

        DeleteMessageBatchResponse response = sqs.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                .queueUrl(queueUrl)
                .entries(deleteEntries)
                .build());

        assertThat(response.successful()).hasSize(3);
    }

    @Test
    @Order(9)
    void setQueueAttributes() {
        sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributesWithStrings(Map.of("VisibilityTimeout", "60"))
                .build());

        GetQueueAttributesResponse attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNamesWithStrings("VisibilityTimeout")
                .build());

        assertThat(attrs.attributesAsStrings().get("VisibilityTimeout")).isEqualTo("60");
    }

    @Test
    @Order(10)
    void tagQueue() {
        sqs.tagQueue(TagQueueRequest.builder()
                .queueUrl(queueUrl)
                .tags(Map.of("env", "test", "team", "backend"))
                .build());
    }

    @Test
    @Order(11)
    void listQueueTags() {
        ListQueueTagsResponse response = sqs.listQueueTags(ListQueueTagsRequest.builder()
                .queueUrl(queueUrl)
                .build());

        assertThat(response.tags().get("env")).isEqualTo("test");
        assertThat(response.tags().get("team")).isEqualTo("backend");
    }

    @Test
    @Order(12)
    void untagQueue() {
        sqs.untagQueue(UntagQueueRequest.builder()
                .queueUrl(queueUrl)
                .tagKeys("team")
                .build());

        ListQueueTagsResponse response = sqs.listQueueTags(ListQueueTagsRequest.builder()
                .queueUrl(queueUrl)
                .build());

        assertThat(response.tags().get("env")).isEqualTo("test");
        assertThat(response.tags()).doesNotContainKey("team");
    }

    @Test
    @Order(13)
    void changeMessageVisibilityBatch() {
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody("vis-batch-1").build());
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody("vis-batch-2").build());

        ReceiveMessageResponse rcv = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl).maxNumberOfMessages(2).build());

        List<ChangeMessageVisibilityBatchRequestEntry> visEntries = new ArrayList<>();
        for (int i = 0; i < rcv.messages().size(); i++) {
            visEntries.add(ChangeMessageVisibilityBatchRequestEntry.builder()
                    .id("vis" + i)
                    .receiptHandle(rcv.messages().get(i).receiptHandle())
                    .visibilityTimeout(0)
                    .build());
        }

        ChangeMessageVisibilityBatchResponse response = sqs.changeMessageVisibilityBatch(
                ChangeMessageVisibilityBatchRequest.builder()
                        .queueUrl(queueUrl)
                        .entries(visEntries)
                        .build());

        assertThat(response.successful()).hasSize(2);

        // Cleanup
        ReceiveMessageResponse cleanup = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl).maxNumberOfMessages(2).build());
        for (Message msg : cleanup.messages()) {
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
        }
    }

    @Test
    @Order(14)
    void messageAttributesString() {
        sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody("msg-attrs")
                .messageAttributes(Map.of("myattr",
                        MessageAttributeValue.builder().dataType("String").stringValue("myval").build())));

        ReceiveMessageResponse rcv = sqs.receiveMessage(b -> b.queueUrl(queueUrl)
                .maxNumberOfMessages(1).messageAttributeNames("All"));

        assertThat(rcv.messages()).isNotEmpty();
        assertThat(rcv.messages().get(0).messageAttributes().get("myattr").stringValue())
                .isEqualTo("myval");

        sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(rcv.messages().get(0).receiptHandle()));
    }

    @Test
    @Order(15)
    void messageAttributesBinary() {
        byte[] binaryPayload = new byte[]{1, 2, 3, 4, 5};
        sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody("binary-msg")
                .messageAttributes(Map.of("payload",
                        MessageAttributeValue.builder()
                                .dataType("Binary")
                                .binaryValue(SdkBytes.fromByteArray(binaryPayload))
                                .build())));

        ReceiveMessageResponse rcv = sqs.receiveMessage(b -> b.queueUrl(queueUrl)
                .maxNumberOfMessages(1).messageAttributeNames("All"));

        assertThat(rcv.messages()).isNotEmpty();
        assertThat(rcv.messages().get(0).messageAttributes()).containsKey("payload");
        assertThat(rcv.messages().get(0).messageAttributes().get("payload").binaryValue()).isNotNull();

        sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(rcv.messages().get(0).receiptHandle()));
    }

    @Test
    @Order(16)
    void longPolling() {
        long start = System.currentTimeMillis();
        sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(1).waitTimeSeconds(2));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(1800); // Should wait ~2s
    }

    @Test
    @Order(17)
    void dlqRouting() {
        dlqUrl = sqs.createQueue(b -> b.queueName("sdk-test-dlq")).queueUrl();
        String dlqArn = sqs.getQueueAttributes(b -> b.queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);

        String redrivePolicy = "{\"maxReceiveCount\":\"2\", \"deadLetterTargetArn\":\"" + dlqArn + "\"}";
        sqs.setQueueAttributes(b -> b.queueUrl(queueUrl)
                .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrivePolicy)));

        // Send a message
        sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody("dlq-test"));

        // Receive 1 (count=1)
        Message m1 = sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(1)).messages().get(0);
        sqs.changeMessageVisibility(b -> b.queueUrl(queueUrl).receiptHandle(m1.receiptHandle()).visibilityTimeout(0));

        // Receive 2 (count=2)
        Message m2 = sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(1)).messages().get(0);
        sqs.changeMessageVisibility(b -> b.queueUrl(queueUrl).receiptHandle(m2.receiptHandle()).visibilityTimeout(0));

        // Receive 3 (count=3 -> moves to DLQ)
        ReceiveMessageResponse r3 = sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(1));
        assertThat(r3.messages()).isEmpty();

        ReceiveMessageResponse dlqRcv = sqs.receiveMessage(b -> b.queueUrl(dlqUrl).maxNumberOfMessages(1));
        assertThat(dlqRcv.messages()).isNotEmpty();
        assertThat(dlqRcv.messages().get(0).body()).isEqualTo("dlq-test");

        // Cleanup
        sqs.deleteMessage(b -> b.queueUrl(dlqUrl).receiptHandle(dlqRcv.messages().get(0).receiptHandle()));
    }

    @Test
    @Order(18)
    void listDeadLetterSourceQueues() {
        Assumptions.assumeTrue(dlqUrl != null);

        ListDeadLetterSourceQueuesResponse response = sqs.listDeadLetterSourceQueues(b -> b.queueUrl(dlqUrl));

        assertThat(response.queueUrls()).contains(queueUrl);
    }

    @Test
    @Order(19)
    void startMessageMoveTask() {
        Assumptions.assumeTrue(dlqUrl != null);

        String dlqArn = sqs.getQueueAttributes(a -> a.queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);

        StartMessageMoveTaskResponse moveResp = sqs.startMessageMoveTask(b -> b.sourceArn(dlqArn));
        assertThat(moveResp.taskHandle()).isNotNull();

        ListMessageMoveTasksResponse listMoves = sqs.listMessageMoveTasks(b -> b.sourceArn(dlqArn));
        assertThat(listMoves.results()).isNotNull();
    }

    @Test
    @Order(20)
    void deleteQueue() {
        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
        if (dlqUrl != null) {
            sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(dlqUrl).build());
        }
        queueUrl = null;
        dlqUrl = null;
    }
}
