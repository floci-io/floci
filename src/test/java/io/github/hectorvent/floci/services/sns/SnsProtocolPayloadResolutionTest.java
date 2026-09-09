package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sns.model.PlatformApplication;
import io.github.hectorvent.floci.services.sns.model.PlatformEndpoint;
import io.github.hectorvent.floci.services.sns.model.PushNotification;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.SqsServiceFactory;
import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@code MessageStructure="json"} resolves a per-protocol payload for every
 * subscription protocol, not just {@code application}, matching real SNS: each subscriber
 * receives the value under its protocol key, falling back to {@code default}.
 */
class SnsProtocolPayloadResolutionTest {

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
    void publish_jsonStructure_deliversSqsKeyToRawSqsSubscriber() {
        String queueUrl = subscribeQueue("raw-queue", Map.of("RawMessageDelivery", "true"));

        snsService.publish(topic("alerts"), null, null,
                "{\"default\":\"hello\",\"sqs\":\"hi sqs\"}", null, "json",
                null, null, null, REGION);

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertEquals("hi sqs", messages.get(0).getBody());
    }

    @Test
    void publish_jsonStructure_embedsSqsKeyInSnsEnvelope() {
        String queueUrl = subscribeQueue("envelope-queue", Map.of());

        snsService.publish(topic("alerts"), null, null,
                "{\"default\":\"hello\",\"sqs\":\"hi sqs\"}", null, "json",
                null, null, null, REGION);

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        String body = messages.get(0).getBody();
        assertTrue(body.contains("\"Message\":\"hi sqs\""),
                "SNS envelope should carry the resolved sqs payload, got: " + body);
        assertFalse(body.contains("\\\"default\\\""),
                "SNS envelope should not carry the raw json structure, got: " + body);
    }

    @Test
    void publish_jsonStructure_fallsBackToDefaultWhenProtocolKeyAbsent() {
        String queueUrl = subscribeQueue("fallback-queue", Map.of("RawMessageDelivery", "true"));

        snsService.publish(topic("alerts"), null, null,
                "{\"default\":\"hello\",\"GCM\":\"push only\"}", null, "json",
                null, null, null, REGION);

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertEquals("hello", messages.get(0).getBody());
    }

    @Test
    void publish_jsonStructure_ignoresNonStringProtocolKeyAndFallsBackToDefault() {
        String queueUrl = subscribeQueue("nonstring-queue", Map.of("RawMessageDelivery", "true"));

        // Real SNS ignores a key whose value isn't a string, delivering default instead of "42".
        snsService.publish(topic("alerts"), null, null,
                "{\"default\":\"hi\",\"sqs\":42}", null, "json",
                null, null, null, REGION);

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertEquals("hi", messages.get(0).getBody());
    }

    @Test
    void publish_jsonStructure_rejectsNonStringDefault() {
        subscribeQueue("nonstring-default-queue", Map.of("RawMessageDelivery", "true"));

        AwsException ex = assertThrows(AwsException.class, () -> snsService.publish(
                topic("alerts"), null, null,
                "{\"default\":42}", null, "json",
                null, null, null, REGION));
        assertEquals("InvalidParameter", ex.getErrorCode());
    }

    @Test
    void publish_withoutJsonStructure_deliversRawMessageUnchanged() {
        String queueUrl = subscribeQueue("plain-queue", Map.of("RawMessageDelivery", "true"));

        snsService.publish(topic("alerts"), null, null,
                "{\"default\":\"hello\",\"sqs\":\"hi sqs\"}", null, null,
                null, null, null, REGION);

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertEquals("{\"default\":\"hello\",\"sqs\":\"hi sqs\"}", messages.get(0).getBody());
    }

    @Test
    void publish_jsonStructure_resolvesPerProtocolForSqsAndPlatformEndpointOnSameTopic() {
        String topicArn = topic("mixed");
        String queueUrl = subscribeQueueTo(topicArn, "mixed-queue", Map.of("RawMessageDelivery", "true"));

        PlatformApplication app = snsService.createPlatformApplication("android-app", "GCM", Map.of(), REGION);
        PlatformEndpoint device = snsService.createPlatformEndpoint(app.getArn(), "fcm-token", null, Map.of(), REGION);
        snsService.subscribe(topicArn, "application", device.getArn(), REGION, Map.of());

        snsService.publish(topicArn, null, null,
                "{\"default\":\"hello\",\"sqs\":\"hi sqs\",\"GCM\":\"hi device\"}", null, "json",
                null, null, null, REGION);

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertEquals("hi sqs", messages.get(0).getBody());

        List<PushNotification> captured = snsService.peekPushNotifications(device.getArn());
        assertEquals(1, captured.size());
        assertEquals("hi device", captured.get(0).payload());
    }

    private String topic(String name) {
        return snsService.createTopic(name, null, null, REGION).getTopicArn();
    }

    private String subscribeQueue(String queueName, Map<String, String> attributes) {
        return subscribeQueueTo(topic("alerts"), queueName, attributes);
    }

    private String subscribeQueueTo(String topicArn, String queueName, Map<String, String> attributes) {
        sqsService.createQueue(queueName, Map.of(), REGION);
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + queueName;
        snsService.subscribe(topicArn, "sqs", queueArn, REGION, attributes);
        return BASE_URL + "/" + ACCOUNT + "/" + queueName;
    }
}
