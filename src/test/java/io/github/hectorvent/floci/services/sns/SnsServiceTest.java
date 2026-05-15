package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String BASE_URL = "http://localhost:4566";

    private SnsService snsService;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        // SqsService and LambdaService are null — delivery failures are caught and logged; fanout is covered by IT
        snsService = new SnsService(
            new InMemoryStorage<>(),
            new InMemoryStorage<>(),
            regionResolver,
            null,
            null
        );
    }

    @Test
    void createTopic_returnsTopicWithArn() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        assertNotNull(topic);
        assertEquals("my-topic", topic.getName());
        assertEquals("arn:aws:sns:us-east-1:000000000000:my-topic", topic.getTopicArn());
    }

    @Test
    void createTopic_idempotent() {
        Topic first = snsService.createTopic("my-topic", null, null, REGION);
        Topic second = snsService.createTopic("my-topic", null, null, REGION);
        assertEquals(first.getTopicArn(), second.getTopicArn());
    }

    @Test
    void createTopic_requiresName() {
        assertThrows(AwsException.class, () -> snsService.createTopic(null, null, null, REGION));
        assertThrows(AwsException.class, () -> snsService.createTopic("", null, null, REGION));
    }

    @Test
    void listTopics_returnsCreatedTopics() {
        snsService.createTopic("topic-a", null, null, REGION);
        snsService.createTopic("topic-b", null, null, REGION);
        List<Topic> topics = snsService.listTopics(REGION);
        assertEquals(2, topics.size());
    }

    @Test
    void listTopics_isolatedByRegion() {
        snsService.createTopic("topic-east", null, null, "us-east-1");
        snsService.createTopic("topic-west", null, null, "us-west-2");
        assertEquals(1, snsService.listTopics("us-east-1").size());
        assertEquals(1, snsService.listTopics("us-west-2").size());
    }

    @Test
    void deleteTopic_removesTopicAndSubscriptions() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        snsService.subscribe(topic.getTopicArn(), "sqs", "http://queue-url", REGION, Map.of());
        snsService.deleteTopic(topic.getTopicArn(), REGION);

        assertTrue(snsService.listTopics(REGION).isEmpty());
        assertTrue(snsService.listSubscriptions(REGION).isEmpty());
    }

    @Test
    void deleteTopic_throwsForMissing() {
        assertThrows(AwsException.class,
            () -> snsService.deleteTopic("arn:aws:sns:us-east-1:000000000000:nonexistent", REGION));
    }

    @Test
    void getTopicAttributes_returnsAttributes() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        Map<String, String> attrs = snsService.getTopicAttributes(topic.getTopicArn(), REGION);
        assertTrue(attrs.containsKey("TopicArn"));
        assertEquals(topic.getTopicArn(), attrs.get("TopicArn"));
        assertTrue(attrs.containsKey("SubscriptionsConfirmed"));
        assertEquals("0", attrs.get("SubscriptionsConfirmed"));
    }

    @Test
    void subscribe_returnsSubscription() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        Subscription sub = snsService.subscribe(topic.getTopicArn(), "sqs",
                "http://localhost:4566/000000000000/my-queue", REGION,
                Map.of("attr1", "value1", "attr2", "value2"));
        assertNotNull(sub.getSubscriptionArn());
        assertEquals(topic.getTopicArn(), sub.getTopicArn());
        assertEquals("sqs", sub.getProtocol());
        assertEquals(ACCOUNT, sub.getOwner());
        assertEquals(Map.of("attr1", "value1", "attr2", "value2"), sub.getAttributes());
    }

    @Test
    void subscribe_idempotent() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        Subscription sub1 = snsService.subscribe(topic.getTopicArn(), "sqs",
                "arn:aws:sqs:us-east-1:000000000000:my-queue", REGION, Map.of());
        Subscription sub2 = snsService.subscribe(topic.getTopicArn(), "sqs",
                "arn:aws:sqs:us-east-1:000000000000:my-queue", REGION, Map.of());
        assertEquals(sub1.getSubscriptionArn(), sub2.getSubscriptionArn());
        assertEquals(1, snsService.listSubscriptions(REGION).size());
    }

    @Test
    void subscribe_differentEndpoints_createsSeparateSubscriptions() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        snsService.subscribe(topic.getTopicArn(), "sqs",
                "arn:aws:sqs:us-east-1:000000000000:queue-1", REGION, Map.of());
        snsService.subscribe(topic.getTopicArn(), "sqs",
                "arn:aws:sqs:us-east-1:000000000000:queue-2", REGION, Map.of());
        assertEquals(2, snsService.listSubscriptions(REGION).size());
    }

    @Test
    void subscribe_throwsForMissingTopic() {
        assertThrows(AwsException.class,
            () -> snsService.subscribe("arn:aws:sns:us-east-1:000000000000:nonexistent",
                    "sqs", "http://queue", REGION, Map.of()));
    }

    @Test
    void subscribe_requiresProtocol() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        assertThrows(AwsException.class,
            () -> snsService.subscribe(topic.getTopicArn(), null, "http://queue", REGION, Map.of()));
    }

    @Test
    void unsubscribe_removesSubscription() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        Subscription sub = snsService.subscribe(topic.getTopicArn(), "sqs",
                "http://queue", REGION, Map.of());
        snsService.unsubscribe(sub.getSubscriptionArn(), REGION);
        assertTrue(snsService.listSubscriptions(REGION).isEmpty());
    }

    @Test
    void listSubscriptionsByTopic_filtersCorrectly() {
        Topic topicA = snsService.createTopic("topic-a", null, null, REGION);
        Topic topicB = snsService.createTopic("topic-b", null, null, REGION);
        snsService.subscribe(topicA.getTopicArn(), "sqs", "http://queue1", REGION, Map.of());
        snsService.subscribe(topicA.getTopicArn(), "sqs", "http://queue2", REGION, Map.of());
        snsService.subscribe(topicB.getTopicArn(), "sqs", "http://queue3", REGION, Map.of());

        assertEquals(2, snsService.listSubscriptionsByTopic(topicA.getTopicArn(), REGION).size());
        assertEquals(1, snsService.listSubscriptionsByTopic(topicB.getTopicArn(), REGION).size());
    }

    @Test
    void publish_withSqsSubscriber_returnsMessageId() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        snsService.subscribe(topic.getTopicArn(), "sqs",
                BASE_URL + "/" + ACCOUNT + "/fanout-queue", REGION, Map.of());
        // Fanout delivery is exercised — message ID returned confirms success
        String messageId = snsService.publish(topic.getTopicArn(), null, "Hello SNS!", null, REGION);
        assertNotNull(messageId);
    }

    @Test
    void publish_withPhoneNumber_returnsMessageId() {
        String messageId = snsService.publish(null, null, "+819012345678", "Hello phone!", null, null, REGION);
        assertNotNull(messageId);
    }

    @Test
    void publish_requiresTopicArn() {
        assertThrows(AwsException.class,
            () -> snsService.publish(null, null, "msg", null, REGION));
    }

    @Test
    void publish_requiresMessage() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        assertThrows(AwsException.class,
            () -> snsService.publish(topic.getTopicArn(), null, null, null, REGION));
    }

    @Test
    void publish_noSubscribers_succeeds() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        String messageId = snsService.publish(topic.getTopicArn(), null, "Hello!", null, REGION);
        assertNotNull(messageId);
    }

    @Test
    void tagResource_and_listTags() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        snsService.tagResource(topic.getTopicArn(), Map.of("env", "test"), REGION);
        Map<String, String> tags = snsService.listTagsForResource(topic.getTopicArn(), REGION);
        assertEquals("test", tags.get("env"));
    }

    @Test
    void untagResource_removesTags() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        snsService.tagResource(topic.getTopicArn(), Map.of("env", "test", "team", "ops"), REGION);
        snsService.untagResource(topic.getTopicArn(), List.of("env"), REGION);
        Map<String, String> tags = snsService.listTagsForResource(topic.getTopicArn(), REGION);
        assertFalse(tags.containsKey("env"));
        assertEquals("ops", tags.get("team"));
    }

    @Test
    void publish_messageExceedsSizeLimit_throwsInvalidParameterException() {
        Topic topic = snsService.createTopic("size-topic", null, null, REGION);
        String tooBig = "x".repeat(262_145);
        AwsException ex = assertThrows(AwsException.class, () ->
                snsService.publish(topic.getTopicArn(), null, tooBig, null, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertTrue(ex.getMessage().toLowerCase().contains("too long"));
    }

    @Test
    void publish_messagePlusAttributesExceedsLimit_throws() {
        Topic topic = snsService.createTopic("size-topic", null, null, REGION);
        String body = "x".repeat(262_100);
        Map<String, io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue> attrs = Map.of(
                "longAttribute", new io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue(
                        "y".repeat(200), "String"));
        AwsException ex = assertThrows(AwsException.class, () ->
                snsService.publish(topic.getTopicArn(), null, null, body, null, attrs, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void publishBatch_totalSizeExceedsLimit_throwsBatchRequestTooLong() {
        Topic topic = snsService.createTopic("size-topic", null, null, REGION);
        String halfMeg = "x".repeat(130_000);
        List<Map<String, Object>> entries = List.of(
                Map.of("Id", "1", "Message", halfMeg),
                Map.of("Id", "2", "Message", halfMeg),
                Map.of("Id", "3", "Message", halfMeg));
        AwsException ex = assertThrows(AwsException.class, () ->
                snsService.publishBatch(topic.getTopicArn(), entries, REGION));
        assertEquals("BatchRequestTooLong", ex.getErrorCode());
    }

    @Test
    void subscriptionsConfirmed_countsCorrectly() {
        Topic topic = snsService.createTopic("my-topic", null, null, REGION);
        snsService.subscribe(topic.getTopicArn(), "sqs", "http://queue1", REGION, Map.of());
        snsService.subscribe(topic.getTopicArn(), "sqs", "http://queue2", REGION, Map.of());
        Map<String, String> attrs = snsService.getTopicAttributes(topic.getTopicArn(), REGION);
        assertEquals("2", attrs.get("SubscriptionsConfirmed"));
    }
}
