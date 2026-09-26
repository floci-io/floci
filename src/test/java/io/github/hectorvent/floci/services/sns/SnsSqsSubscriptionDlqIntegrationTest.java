package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.SqsServiceFactory;
import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnsSqsSubscriptionDlqIntegrationTest {

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
    void publish_routesExhaustedDeliveryToSubscriptionDlq() throws Exception {
        String dlqName = "sns-subscription-dlq";
        String dlqArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + dlqName;
        String dlqUrl = BASE_URL + "/" + ACCOUNT + "/" + dlqName;
        sqsService.createQueue(dlqName, null, REGION);

        Topic topic = snsService.createTopic("sns-dlq-topic", null, null, REGION);
        String unavailableQueueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":unavailable-queue";
        Subscription subscription = snsService.subscribe(
                topic.getTopicArn(), "sqs", unavailableQueueArn, REGION, Map.of());
        snsService.setSubscriptionAttribute(subscription.getSubscriptionArn(), "RedrivePolicy",
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\"}", REGION);

        String publishMessageId = snsService.publish(topic.getTopicArn(), null,
                "synthetic-payload", "synthetic-subject", REGION);

        assertNotNull(publishMessageId);
        List<Message> deadLetterMessages = sqsService.receiveMessage(dlqUrl, 1, 30, 0, REGION);
        assertEquals(1, deadLetterMessages.size());
        JsonNode envelope = new ObjectMapper().readTree(deadLetterMessages.getFirst().getBody());
        assertEquals("Notification", envelope.path("Type").asText());
        assertEquals(publishMessageId, envelope.path("MessageId").asText());
        assertEquals(topic.getTopicArn(), envelope.path("TopicArn").asText());
        assertEquals("synthetic-payload", envelope.path("Message").asText());
        assertEquals("synthetic-subject", envelope.path("Subject").asText());
        assertTrue(sqsService.receiveMessage(dlqUrl, 1, 30, 0, REGION).isEmpty(),
                "The notification should appear only once in the subscription DLQ");
    }

    @Test
    void publish_routesStandardTopicNotificationToFifoSubscriptionDlq() throws Exception {
        String dlqName = "sns-subscription-dlq.fifo";
        String dlqArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + dlqName;
        String dlqUrl = BASE_URL + "/" + ACCOUNT + "/" + dlqName;
        sqsService.createQueue(dlqName, Map.of("FifoQueue", "true"), REGION);

        Topic topic = snsService.createTopic("sns-standard-topic", null, null, REGION);
        Subscription subscription = snsService.subscribe(topic.getTopicArn(), "sqs",
                "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":unavailable-fifo-source.fifo", REGION, Map.of());
        snsService.setSubscriptionAttribute(subscription.getSubscriptionArn(), "RedrivePolicy",
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\"}", REGION);

        String publishMessageId = snsService.publish(topic.getTopicArn(), null,
                "synthetic-payload", null, REGION);

        List<Message> deadLetterMessages = sqsService.receiveMessage(dlqUrl, 1, 30, 0, REGION);
        assertEquals(1, deadLetterMessages.size());
        Message deadLetter = deadLetterMessages.getFirst();
        JsonNode envelope = new ObjectMapper().readTree(deadLetter.getBody());
        assertEquals(publishMessageId, deadLetter.getMessageGroupId());
        assertEquals(publishMessageId, deadLetter.getMessageDeduplicationId());
        assertEquals(publishMessageId, envelope.path("MessageId").asText());
    }
}
