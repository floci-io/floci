package io.github.hectorvent.floci.services.sqs;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Validates that the AWS SDK Java accepts the MD5OfMessageAttributes
 * Floci returns for both standard and FIFO queues. The SDK validates
 * MD5 client-side and throws when the response checksum doesn't match
 * its own canonical recomputation, so a successful SendMessage is
 * proof of wire-level MD5 parity.
 */
@QuarkusTest
class SqsSdkMd5IntegrationTest {

    @Inject
    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    int testPort;

    private SqsClient sqs;

    @BeforeEach
    void setUp() {
        sqs = SqsClient.builder()
                .endpointOverride(URI.create("http://localhost:" + testPort))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (sqs != null) {
            sqs.close();
        }
    }

    @Test
    void standardQueue_sendMessageWithAttributes_sdkAcceptsMd5() {
        String queueName = "md5-std-" + UUID.randomUUID();
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName).build()).queueUrl();

        var attrs = Map.of(
                "trace-id", MessageAttributeValue.builder()
                        .dataType("String").stringValue("abc-123").build(),
                "priority", MessageAttributeValue.builder()
                        .dataType("Number").stringValue("42").build());

        assertDoesNotThrow(() -> {
            var resp = sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody("hello standard")
                    .messageAttributes(attrs)
                    .build());
            assertNotNull(resp.md5OfMessageAttributes());
        });
    }

    @Test
    void fifoQueue_sendMessageWithAttributes_sdkAcceptsMd5() {
        String queueName = "md5-fifo-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.FIFO_QUEUE, "true",
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build()).queueUrl();

        var attrs = Map.of(
                "trace-id", MessageAttributeValue.builder()
                        .dataType("String").stringValue("abc-123").build(),
                "priority", MessageAttributeValue.builder()
                        .dataType("Number").stringValue("42").build());

        assertDoesNotThrow(() -> {
            var resp = sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody("hello fifo")
                    .messageGroupId("g1")
                    .messageAttributes(attrs)
                    .build());
            assertNotNull(resp.md5OfMessageAttributes());
        });
    }

    @Test
    void fifoQueue_dedupReplay_sdkAcceptsMd5OnSecondSend() {
        // Within the 5-minute dedup window, a second SendMessage with the
        // same MessageDeduplicationId must return MD5s computed from the
        // CURRENT request body and attributes (not the original message's
        // cached MD5s), otherwise the SDK throws MD5 mismatch when the two
        // sends differ at all.
        String queueName = "md5-dedup-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.FIFO_QUEUE, "true"))
                .build()).queueUrl();

        var firstAttrs = Map.of(
                "k", MessageAttributeValue.builder().dataType("String").stringValue("v1").build());
        var secondAttrs = Map.of(
                "k", MessageAttributeValue.builder().dataType("String").stringValue("v2").build());

        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody("body-1").messageGroupId("g1")
                .messageDeduplicationId("same-dedup")
                .messageAttributes(firstAttrs).build());

        // Duplicate within window with different attributes/body — SDK will
        // throw "MD5 hash mismatch" if Floci returns the cached first MD5.
        assertDoesNotThrow(() -> sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody("body-2").messageGroupId("g1")
                .messageDeduplicationId("same-dedup")
                .messageAttributes(secondAttrs).build()));
    }

    @Test
    void fifoQueue_binaryAttribute_sdkAcceptsMd5() {
        String queueName = "md5-fifo-bin-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.FIFO_QUEUE, "true",
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build()).queueUrl();

        var attrs = Map.of(
                "blob", MessageAttributeValue.builder()
                        .dataType("Binary")
                        .binaryValue(software.amazon.awssdk.core.SdkBytes.fromByteArray(new byte[]{1, 2, 3, 4, 5}))
                        .build());

        assertDoesNotThrow(() -> sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody("binary").messageGroupId("g1")
                .messageAttributes(attrs).build()));
    }

    @Test
    void fifoQueue_customTypeName_sdkAcceptsMd5() {
        String queueName = "md5-fifo-custom-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.FIFO_QUEUE, "true",
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build()).queueUrl();

        var attrs = Map.of(
                "priority", MessageAttributeValue.builder()
                        .dataType("Number.int").stringValue("42").build());

        assertDoesNotThrow(() -> sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody("custom").messageGroupId("g1")
                .messageAttributes(attrs).build()));
    }

    @Test
    void highThroughputFifoQueue_sendMessageWithAttributes_sdkAcceptsMd5() {
        String queueName = "md5-fair-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.FIFO_QUEUE, "true",
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true",
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.DEDUPLICATION_SCOPE, "messageGroup",
                        software.amazon.awssdk.services.sqs.model.QueueAttributeName.FIFO_THROUGHPUT_LIMIT, "perMessageGroupId"))
                .build()).queueUrl();

        var attrs = Map.of(
                "key", MessageAttributeValue.builder()
                        .dataType("String").stringValue("value").build());

        assertDoesNotThrow(() -> sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody("hello fair")
                .messageGroupId("g1")
                .messageAttributes(attrs)
                .build()));
    }
}
