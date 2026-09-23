package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.SqsServiceFactory;
import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SNS fan-out carries no per-message delay, so a subscribed queue's own DelaySeconds
 * must apply to messages published through a topic exactly as it does to SendMessage.
 */
class SnsSqsFanoutDelaySecondsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String BASE_URL = "http://localhost:4566";

    private SnsService snsService;
    private SqsService sqsService;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        sqsService = SqsServiceFactory.createInMemory(BASE_URL, regionResolver);
        snsService = new SnsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver, sqsService, null);
    }

    @Test
    void publish_toStandardQueueWithDelaySeconds_withholdsTheMessage() {
        String queueUrl = createQueue("delay-standard-queue", Map.of("DelaySeconds", "2"));
        String topicArn = createTopicSubscribedTo("delay-standard-topic", "delay-standard-queue", Map.of());

        snsService.publish(topicArn, null, "via-sns", null, REGION);

        assertTrue(sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION).isEmpty());
        assertEquals("1", sqsService.getQueueAttributes(queueUrl,
                List.of("ApproximateNumberOfMessagesDelayed"), REGION)
                .get("ApproximateNumberOfMessagesDelayed"));
    }

    @Test
    void publish_toStandardQueueWithRawDeliveryAndDelaySeconds_withholdsTheMessage() {
        String queueUrl = createQueue("delay-raw-queue", Map.of("DelaySeconds", "2"));
        String topicArn = createTopicSubscribedTo("delay-raw-topic", "delay-raw-queue",
                Map.of("RawMessageDelivery", "true"));

        snsService.publish(topicArn, null, "via-sns-raw", null, REGION);

        assertTrue(sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION).isEmpty());
    }

    @Test
    void publish_toStandardQueueWithoutDelaySeconds_deliversImmediately() {
        String queueUrl = createQueue("no-delay-queue", Map.of());
        String topicArn = createTopicSubscribedTo("no-delay-topic", "no-delay-queue", Map.of());

        snsService.publish(topicArn, null, "no-delay", null, REGION);

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().getBody().contains("no-delay"));
    }

    @Test
    void publish_toFifoQueueWithDelaySeconds_withholdsTheMessage() {
        String queueUrl = createQueue("delay-fifo-queue.fifo",
                Map.of("FifoQueue", "true", "DelaySeconds", "2"));

        snsService.createTopic("delay-fifo-topic.fifo", Map.of("FifoTopic", "true"), null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":delay-fifo-topic.fifo";
        snsService.subscribe(topicArn, "sqs",
                "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":delay-fifo-queue.fifo", REGION, Map.of());

        snsService.publish(topicArn, null, null, "via-sns-fifo",
                null, null, "group-1", "dedup-1", REGION);

        assertTrue(sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION).isEmpty());
    }

    private String createQueue(String queueName, Map<String, String> attributes) {
        sqsService.createQueue(queueName, attributes, REGION);
        return BASE_URL + "/" + ACCOUNT + "/" + queueName;
    }

    private String createTopicSubscribedTo(String topicName, String queueName,
                                           Map<String, String> subscriptionAttributes) {
        snsService.createTopic(topicName, null, null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":" + topicName;
        snsService.subscribe(topicArn, "sqs",
                "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + queueName, REGION, subscriptionAttributes);
        return topicArn;
    }
}
