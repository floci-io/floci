package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.testing.MutableClock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.withSettings;

@QuarkusTest
class TargetDispatcherRetryIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String RULE_ARN = "arn:aws:events:us-east-1:" + ACCOUNT_ID + ":rule/scope-age-test";
    private static final String EVENT = "{\"id\":\"scope-age-test\",\"source\":\"integration\"}";

    @Inject
    EventBridgeInvoker invoker;

    @Inject
    SqsService sqsService;

    @Inject
    EmulatorConfig config;

    @Inject
    MutableClock clock;

    @Test
    void repeatedScopeFailuresDeadLetterOnceWhenMaximumEventAgeExpires() {
        clock.reset();
        String queueName = "eventbridge-scope-age-" + UUID.randomUUID();
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT_ID + ":" + queueName;
        String queueUrl = RequestScopes.callAs(ACCOUNT_ID, REGION,
                () -> sqsService.createQueue(queueName, Map.of(), REGION).getQueueUrl());
        Target target = new Target("scope-age-target", "arn:aws:sqs:" + REGION + ":" + ACCOUNT_ID + ":target",
                null, null);
        target.setRetryPolicy(new Target.RetryPolicy(5, 2));
        target.setDeadLetterConfig(new Target.DeadLetterConfig(queueArn));
        TargetDispatcher dispatcher = new TargetDispatcher(invoker, sqsService, config.baseUrl(), clock, null);

        try {
            try (MockedStatic<RequestScopes> scopes =
                         mockStatic(RequestScopes.class, withSettings().mockMaker(MockMakers.INLINE))) {
                scopes.when(() -> RequestScopes.runAs(eq(ACCOUNT_ID), any(Runnable.class)))
                        .thenThrow(new IllegalStateException("Request scope is not active"));

                dispatcher.dispatch(RULE_ARN, target, EVENT, REGION, () -> List.of(target));
                clock.advance(Duration.ofSeconds(1));
                dispatcher.tick();
                clock.advance(Duration.ofSeconds(1));
                dispatcher.tick();

                scopes.verify(() -> RequestScopes.runAs(eq(ACCOUNT_ID), any(Runnable.class)),
                        times(3));
            }

            List<Message> deadLetters = RequestScopes.callAs(ACCOUNT_ID, REGION,
                    () -> sqsService.receiveMessage(queueUrl, 10, 0, 0, REGION));
            assertEquals(1, deadLetters.size());
            assertEquals(EVENT, deadLetters.getFirst().getBody());
            assertEquals("MaximumEventAgeInSeconds", deadLetters.getFirst().getMessageAttributes()
                    .get("EXHAUSTED_RETRY_CONDITION").getStringValue());
            assertEquals("1", deadLetters.getFirst().getMessageAttributes()
                    .get("RETRY_ATTEMPTS").getStringValue());
        } finally {
            RequestScopes.runAs(ACCOUNT_ID, REGION, () -> sqsService.deleteQueue(queueUrl, REGION));
        }
    }
}
