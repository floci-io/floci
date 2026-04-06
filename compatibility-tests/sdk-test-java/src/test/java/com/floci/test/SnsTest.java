package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.CreateTopicResponse;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
import software.amazon.awssdk.services.sns.model.GetTopicAttributesRequest;
import software.amazon.awssdk.services.sns.model.GetTopicAttributesResponse;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicRequest;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicResponse;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;
import software.amazon.awssdk.services.sns.model.UnsubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SNS Simple Notification Service")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SnsTest {

    private static SnsClient sns;
    private static SqsClient sqs;
    private static String topicArn;
    private static String queueUrl;
    private static String queueArn;
    private static String subscriptionArn;

    @BeforeAll
    static void setup() {
        sns = TestFixtures.snsClient();
        sqs = TestFixtures.sqsClient();
    }

    @AfterAll
    static void cleanup() {
        if (sns != null && sqs != null) {
            try {
                sns.deleteTopic(DeleteTopicRequest.builder().topicArn(topicArn).build());
            } catch (Exception ignored) {}
            try {
                sqs.deleteQueue(software.amazon.awssdk.services.sqs.model.DeleteQueueRequest.builder()
                        .queueUrl(queueUrl).build());
            } catch (Exception ignored) {}
            sns.close();
            sqs.close();
        }
    }

    @Test
    @Order(1)
    void createTopic() {
        String topicName = "sdk-test-topic-" + System.currentTimeMillis();
        CreateTopicResponse response = sns.createTopic(CreateTopicRequest.builder()
                .name(topicName).build());
        topicArn = response.topicArn();

        assertThat(topicArn).isNotNull().contains(topicName);
    }

    @Test
    @Order(2)
    void listTopics() {
        ListTopicsResponse response = sns.listTopics();

        assertThat(response.topics())
                .anyMatch(t -> t.topicArn().equals(topicArn));
    }

    @Test
    @Order(3)
    void getTopicAttributes() {
        GetTopicAttributesResponse response = sns.getTopicAttributes(
                GetTopicAttributesRequest.builder().topicArn(topicArn).build());

        assertThat(response.attributes()).containsKey("TopicArn");
    }

    @Test
    @Order(4)
    void subscribeSqs() {
        String queueName = "sns-test-queue-" + System.currentTimeMillis();
        queueUrl = sqs.createQueue(software.amazon.awssdk.services.sqs.model.CreateQueueRequest.builder()
                .queueName(queueName).build()).queueUrl();
        queueArn = sqs.getQueueAttributes(software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN)
                .build())
                .attributes().get(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN);

        SubscribeResponse response = sns.subscribe(SubscribeRequest.builder()
                .topicArn(topicArn)
                .protocol("sqs")
                .endpoint(queueArn)
                .build());
        subscriptionArn = response.subscriptionArn();

        assertThat(subscriptionArn).isNotNull();
    }

    @Test
    @Order(5)
    void listSubscriptionsByTopic() {
        ListSubscriptionsByTopicResponse response = sns.listSubscriptionsByTopic(
                ListSubscriptionsByTopicRequest.builder().topicArn(topicArn).build());

        assertThat(response.subscriptions())
                .anyMatch(s -> s.subscriptionArn().equals(subscriptionArn));
    }

    @Test
    @Order(6)
    void publish() {
        PublishResponse response = sns.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message("hello from sns")
                .subject("test-subject")
                .build());

        assertThat(response.messageId()).isNotNull();
    }

    @Test
    @Order(7)
    void verifySqsDelivery() throws InterruptedException {
        Thread.sleep(500); // Allow async delivery

        software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse recv = sqs.receiveMessage(
                software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(2)
                        .build());

        assertThat(recv.messages()).isNotEmpty();
        assertThat(recv.messages().get(0).body()).contains("hello from sns");

        sqs.deleteMessage(software.amazon.awssdk.services.sqs.model.DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(recv.messages().get(0).receiptHandle())
                .build());
    }

    @Test
    @Order(8)
    void publishWithMessageAttributes() throws InterruptedException {
        sns.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message("msg with attrs")
                .messageAttributes(Map.of(
                        "my-attr", MessageAttributeValue.builder()
                                .dataType("String").stringValue("my-value").build()
                ))
                .build());

        Thread.sleep(500);

        software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse recv = sqs.receiveMessage(
                software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(2)
                        .build());

        assertThat(recv.messages()).isNotEmpty();
        assertThat(recv.messages().get(0).body()).contains("my-value");

        sqs.deleteMessage(software.amazon.awssdk.services.sqs.model.DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(recv.messages().get(0).receiptHandle())
                .build());
    }

    @Test
    @Order(9)
    void rawMessageDelivery() throws InterruptedException {
        String rawQueueName = "sns-raw-delivery-" + System.currentTimeMillis();
        String rawQueueUrl = sqs.createQueue(software.amazon.awssdk.services.sqs.model.CreateQueueRequest.builder()
                .queueName(rawQueueName).build()).queueUrl();
        String rawQueueArn = sqs.getQueueAttributes(software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest.builder()
                .queueUrl(rawQueueUrl)
                .attributeNames(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN)
                .build())
                .attributes().get(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN);

        String rawSubArn = sns.subscribe(SubscribeRequest.builder()
                .topicArn(topicArn)
                .protocol("sqs")
                .endpoint(rawQueueArn)
                .build()).subscriptionArn();

        sns.setSubscriptionAttributes(SetSubscriptionAttributesRequest.builder()
                .subscriptionArn(rawSubArn)
                .attributeName("RawMessageDelivery")
                .attributeValue("true")
                .build());

        sns.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message("raw-delivery-content")
                .messageAttributes(Map.of(
                        "color", MessageAttributeValue.builder()
                                .dataType("String").stringValue("blue").build(),
                        "count", MessageAttributeValue.builder()
                                .dataType("Number").stringValue("42").build()
                ))
                .build());

        Thread.sleep(500);

        software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse rawRecv = sqs.receiveMessage(
                software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.builder()
                        .queueUrl(rawQueueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(2)
                        .messageAttributeNames("All")
                        .build());

        assertThat(rawRecv.messages()).isNotEmpty();
        String body = rawRecv.messages().get(0).body();
        assertThat(body).doesNotContain("\"Type\":\"Notification\"");
        assertThat(body).isEqualTo("raw-delivery-content");

        var msgAttrs = rawRecv.messages().get(0).messageAttributes();
        assertThat(msgAttrs).containsKey("color");
        assertThat(msgAttrs.get("color").stringValue()).isEqualTo("blue");
        assertThat(msgAttrs).containsKey("count");
        assertThat(msgAttrs.get("count").dataType()).isEqualTo("Number");

        // Cleanup
        sns.unsubscribe(UnsubscribeRequest.builder().subscriptionArn(rawSubArn).build());
        sqs.deleteQueue(software.amazon.awssdk.services.sqs.model.DeleteQueueRequest.builder()
                .queueUrl(rawQueueUrl).build());
    }

    @Test
    @Order(10)
    void unsubscribe() {
        sns.unsubscribe(UnsubscribeRequest.builder().subscriptionArn(subscriptionArn).build());
    }

    @Test
    @Order(11)
    void deleteTopic() {
        sns.deleteTopic(DeleteTopicRequest.builder().topicArn(topicArn).build());
        topicArn = null;
    }

    @Test
    @Order(12)
    void fifoExplicitDedup() throws InterruptedException {
        String fifoQueueName = "sns-fifo-explicit-" + System.currentTimeMillis() + ".fifo";
        String fifoQueueUrl = sqs.createQueue(software.amazon.awssdk.services.sqs.model.CreateQueueRequest.builder()
                .queueName(fifoQueueName)
                .attributes(Map.of(software.amazon.awssdk.services.sqs.model.QueueAttributeName.FIFO_QUEUE, "true"))
                .build()).queueUrl();
        String fifoQueueArn = sqs.getQueueAttributes(software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest.builder()
                .queueUrl(fifoQueueUrl)
                .attributeNames(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN)
                .build())
                .attributes().get(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN);

        String fifoTopicName = "sns-fifo-explicit-" + System.currentTimeMillis() + ".fifo";
        String fifoTopicArn = sns.createTopic(CreateTopicRequest.builder()
                .name(fifoTopicName)
                .attributes(Map.of("FifoTopic", "true"))
                .build()).topicArn();

        String fifoSubArn = sns.subscribe(SubscribeRequest.builder()
                .topicArn(fifoTopicArn)
                .protocol("sqs")
                .endpoint(fifoQueueArn)
                .build()).subscriptionArn();

        String explicitDedupId = "dedup-" + System.currentTimeMillis();
        sns.publish(PublishRequest.builder()
                .topicArn(fifoTopicArn)
                .message("fifo message with explicit dedup")
                .messageGroupId("test-group")
                .messageDeduplicationId(explicitDedupId)
                .build());

        Thread.sleep(500);

        software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse fifoRecv = sqs.receiveMessage(
                software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.builder()
                        .queueUrl(fifoQueueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(2)
                        .messageSystemAttributeNames(software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID)
                        .build());

        assertThat(fifoRecv.messages()).isNotEmpty();
        String receivedDedupId = fifoRecv.messages().get(0).attributes()
                .get(software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID);
        assertThat(receivedDedupId).isEqualTo(explicitDedupId);

        // Cleanup
        sns.unsubscribe(UnsubscribeRequest.builder().subscriptionArn(fifoSubArn).build());
        sns.deleteTopic(DeleteTopicRequest.builder().topicArn(fifoTopicArn).build());
        sqs.deleteQueue(software.amazon.awssdk.services.sqs.model.DeleteQueueRequest.builder()
                .queueUrl(fifoQueueUrl).build());
    }

    @Test
    @Order(13)
    void fifoContentBasedDedup() throws InterruptedException {
        String cbdQueueName = "sns-fifo-cbd-" + System.currentTimeMillis() + ".fifo";
        String cbdQueueUrl = sqs.createQueue(software.amazon.awssdk.services.sqs.model.CreateQueueRequest.builder()
                .queueName(cbdQueueName)
                .attributes(Map.of(software.amazon.awssdk.services.sqs.model.QueueAttributeName.FIFO_QUEUE, "true"))
                .build()).queueUrl();
        String cbdQueueArn = sqs.getQueueAttributes(software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest.builder()
                .queueUrl(cbdQueueUrl)
                .attributeNames(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN)
                .build())
                .attributes().get(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN);

        String cbdTopicName = "sns-fifo-cbd-" + System.currentTimeMillis() + ".fifo";
        String cbdTopicArn = sns.createTopic(CreateTopicRequest.builder()
                .name(cbdTopicName)
                .attributes(Map.of(
                        "FifoTopic", "true",
                        "ContentBasedDeduplication", "true"
                ))
                .build()).topicArn();

        String cbdSubArn = sns.subscribe(SubscribeRequest.builder()
                .topicArn(cbdTopicArn)
                .protocol("sqs")
                .endpoint(cbdQueueArn)
                .build()).subscriptionArn();

        sns.publish(PublishRequest.builder()
                .topicArn(cbdTopicArn)
                .message("fifo message with content-based dedup")
                .messageGroupId("test-group")
                .build());

        Thread.sleep(500);

        software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse cbdRecv = sqs.receiveMessage(
                software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.builder()
                        .queueUrl(cbdQueueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(2)
                        .messageSystemAttributeNames(software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID)
                        .build());

        assertThat(cbdRecv.messages()).isNotEmpty();
        String receivedDedupId = cbdRecv.messages().get(0).attributes()
                .get(software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID);
        assertThat(receivedDedupId).isNotEmpty();

        // Cleanup
        sns.unsubscribe(UnsubscribeRequest.builder().subscriptionArn(cbdSubArn).build());
        sns.deleteTopic(DeleteTopicRequest.builder().topicArn(cbdTopicArn).build());
        sqs.deleteQueue(software.amazon.awssdk.services.sqs.model.DeleteQueueRequest.builder()
                .queueUrl(cbdQueueUrl).build());
    }
}
