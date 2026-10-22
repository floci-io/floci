package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.SqsServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EventBridge carries no per-message delay to an SQS target or dead-letter queue, so the
 * queue's own DelaySeconds must apply exactly as it does to SendMessage.
 */
class EventBridgeSqsDelaySecondsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String BASE_URL = "http://localhost:4566";
    private static final String RULE_ARN = "arn:aws:events:us-east-1:000000000000:rule/orders";
    private static final String EVENT = "{\"id\":\"e-1\",\"source\":\"orders\",\"detail\":{}}";

    private SqsService sqsService;
    private EventBridgeInvoker invoker;

    @BeforeEach
    void setUp() {
        sqsService = SqsServiceFactory.createInMemory(BASE_URL, new RegionResolver(REGION, ACCOUNT));
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn(BASE_URL);
        invoker = new EventBridgeInvoker(mock(LambdaService.class), sqsService, mock(SnsService.class),
                new ObjectMapper(), config);
    }

    @Test
    void sqsTargetWithDelaySeconds_withholdsTheEvent() {
        String queueUrl = createQueue("eb-delayed-target", Map.of("DelaySeconds", "2"));

        invoker.invokeTarget(new Target("orders-target", queueArn("eb-delayed-target"), null, null), EVENT, REGION);

        assertWithheld(queueUrl);
    }

    @Test
    void sqsTargetWithoutDelaySeconds_deliversImmediately() {
        String queueUrl = createQueue("eb-immediate-target", Map.of());

        invoker.invokeTarget(new Target("orders-target", queueArn("eb-immediate-target"), null, null), EVENT, REGION);

        assertEquals(1, sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION).size());
    }

    @Test
    void deadLetterQueueWithDelaySeconds_withholdsTheUndeliveredEvent() {
        String deadLetterUrl = createQueue("eb-delayed-dlq", Map.of("DelaySeconds", "2"));
        Target target = new Target("orders-target", queueArn("eb-missing-target"), null, null);
        target.setDeadLetterConfig(new Target.DeadLetterConfig(queueArn("eb-delayed-dlq")));
        TargetDispatcher dispatcher = new TargetDispatcher(invoker, sqsService, BASE_URL, Clock.systemUTC(), null);

        dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));

        assertWithheld(deadLetterUrl);
    }

    private void assertWithheld(String queueUrl) {
        assertTrue(sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION).isEmpty());
        assertEquals("1", sqsService.getQueueAttributes(queueUrl,
                List.of("ApproximateNumberOfMessagesDelayed"), REGION).get("ApproximateNumberOfMessagesDelayed"));
    }

    private String createQueue(String queueName, Map<String, String> attributes) {
        return sqsService.createQueue(queueName, attributes, REGION).getQueueUrl();
    }

    private static String queueArn(String queueName) {
        return "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + queueName;
    }
}
