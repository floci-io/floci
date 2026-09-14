package io.github.hectorvent.floci.services.sqs;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedSqsTest {
    private SqsClient client(EmbeddedSqs server) {
        return SqsClient.builder().endpointOverride(server.endpoint()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5)))
                .build();
    }

    @Test
    void sdkRoundTripVisibilityAndDelete() throws Exception {
        try (EmbeddedSqs server = EmbeddedSqs.start(); SqsClient client = client(server)) {
            String url = client.createQueue(r -> r.queueName("orders")).queueUrl();
            assertTrue(url.startsWith(server.endpoint().toString()));
            client.sendMessage(r -> r.queueUrl(url).messageBody("hello"));
            Message message = client.receiveMessage(r -> r.queueUrl(url).visibilityTimeout(30)).messages().getFirst();
            assertEquals("hello", message.body());
            assertTrue(client.receiveMessage(r -> r.queueUrl(url)).messages().isEmpty());
            client.changeMessageVisibility(r -> r.queueUrl(url).receiptHandle(message.receiptHandle()).visibilityTimeout(0));
            Message again = client.receiveMessage(r -> r.queueUrl(url)).messages().getFirst();
            assertEquals(message.messageId(), again.messageId());
            client.deleteMessage(r -> r.queueUrl(url).receiptHandle(again.receiptHandle()));
            assertTrue(client.receiveMessage(r -> r.queueUrl(url)).messages().isEmpty());
        }
    }

    @Test
    void fifoDeduplicatesAndPreservesOrder() throws Exception {
        try (EmbeddedSqs server = EmbeddedSqs.start(); SqsClient client = client(server)) {
            String url = client.createQueue(r -> r.queueName("orders.fifo")
                    .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))).queueUrl();
            for (int i = 0; i < 2; i++) {
                client.sendMessage(r -> r.queueUrl(url).messageBody("first").messageGroupId("group").messageDeduplicationId("a"));
            }
            client.sendMessage(r -> r.queueUrl(url).messageBody("second").messageGroupId("group").messageDeduplicationId("b"));
            Message first = client.receiveMessage(r -> r.queueUrl(url)).messages().getFirst();
            assertEquals("first", first.body());
            client.deleteMessage(r -> r.queueUrl(url).receiptHandle(first.receiptHandle()));
            Message second = client.receiveMessage(r -> r.queueUrl(url)).messages().getFirst();
            assertEquals("second", second.body());
            client.deleteMessage(r -> r.queueUrl(url).receiptHandle(second.receiptHandle()));
            assertTrue(client.receiveMessage(r -> r.queueUrl(url)).messages().isEmpty());
        }
    }

    @Test
    void instancesAreIsolatedAndMissingQueueIsSdkError() throws Exception {
        try (EmbeddedSqs a = EmbeddedSqs.start(); EmbeddedSqs b = EmbeddedSqs.start();
             SqsClient first = client(a); SqsClient second = client(b)) {
            first.createQueue(r -> r.queueName("private"));
            assertTrue(second.listQueues().queueUrls().isEmpty());
            SqsException error = assertThrows(SqsException.class, () -> second.getQueueUrl(r -> r.queueName("private")));
            assertEquals(400, error.statusCode());
        }
    }

    @Test
    void failedDeliveryMovesToDeadLetterQueue() throws Exception {
        try (EmbeddedSqs server = EmbeddedSqs.start(); SqsClient client = client(server)) {
            String dlq = client.createQueue(r -> r.queueName("dead")).queueUrl();
            String arn = client.getQueueAttributes(r -> r.queueUrl(dlq)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            String source = client.createQueue(r -> r.queueName("source").attributes(Map.of(
                    QueueAttributeName.REDRIVE_POLICY,
                    "{\"deadLetterTargetArn\":\"" + arn + "\",\"maxReceiveCount\":\"1\"}"))).queueUrl();
            client.sendMessage(r -> r.queueUrl(source).messageBody("retry-me"));
            Message first = client.receiveMessage(r -> r.queueUrl(source)).messages().getFirst();
            client.changeMessageVisibility(r -> r.queueUrl(source).receiptHandle(first.receiptHandle()).visibilityTimeout(0));
            assertTrue(client.receiveMessage(r -> r.queueUrl(source)).messages().isEmpty());
            assertEquals("retry-me", client.receiveMessage(r -> r.queueUrl(dlq)).messages().getFirst().body());
        }
    }

    @Test
    void emptyLongPollHonorsWaitTime() throws Exception {
        try (EmbeddedSqs server = EmbeddedSqs.start(); SqsClient client = client(server)) {
            String url = client.createQueue(r -> r.queueName("empty")).queueUrl();
            long started = System.nanoTime();
            assertTrue(client.receiveMessage(r -> r.queueUrl(url).waitTimeSeconds(1)).messages().isEmpty());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 800,
                    "Long poll must wait, not immediately return an empty response");
        }
    }

    @Test
    void closeReleasesListeningPortAndIsIdempotent() throws Exception {
        EmbeddedSqs server = EmbeddedSqs.start();
        int port = server.endpoint().getPort();
        server.close();
        server.close();
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
        }
    }
}
