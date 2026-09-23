package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CustomResourceLiveness;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.scheduler.SchedulerController;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Wait states must honor {@code Seconds}, {@code SecondsPath}, {@code Timestamp}, and
 * {@code TimestampPath}. Timestamp support was missing: the state fell through with no pause and
 * ran early. With no {@code TimeoutSeconds}, the execution deadline is unbounded, so the injected
 * {@link AslExecutor.Sleeper} is the only thing that records the requested pause.
 */
class AslExecutorWaitStateTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void secondsAboveThirtyAreHonoredWhenTheCapIsRaised() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);

        Execution execution = run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Seconds":45,"End":true}}}
                """, "{}");

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals(TimeUnit.SECONDS.toNanos(45), sleeper.singleSleep());
    }

    @Test
    void secondsAreCappedAtTheConfiguredMaximum() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 10);

        run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Seconds":100,"End":true}}}
                """, "{}");

        assertEquals(TimeUnit.SECONDS.toNanos(10), sleeper.singleSleep());
    }

    @Test
    void absoluteTimestampWaitsUntilThatInstant() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant wake = NOW.plusSeconds(45);

        run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"%s","End":true}}}
                """.formatted(wake), "{}");

        assertEquals(TimeUnit.SECONDS.toNanos(45), sleeper.singleSleep());
    }

    @Test
    void timestampPathResolvesTheWakeInstant() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant wake = NOW.plusSeconds(20);

        run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","TimestampPath":"$.wake","End":true}}}
                """, "{\"wake\":\"" + wake + "\"}");

        assertEquals(TimeUnit.SECONDS.toNanos(20), sleeper.singleSleep());
    }

    @Test
    void jsonataLiteralTimestampWaitsUntilThatInstant() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant wake = NOW.plusSeconds(45);

        run(executor, """
                {"QueryLanguage":"JSONata","StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"%s","End":true}}}
                """.formatted(wake), "{}");

        assertEquals(TimeUnit.SECONDS.toNanos(45), sleeper.singleSleep());
    }

    @Test
    void jsonataTimestampExpressionResolvesTheWakeInstant() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant wake = NOW.plusSeconds(20);

        run(executor, """
                {"QueryLanguage":"JSONata","StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"{% $states.input.wake %}","End":true}}}
                """, "{\"wake\":\"" + wake + "\"}");

        assertEquals(TimeUnit.SECONDS.toNanos(20), sleeper.singleSleep());
    }

    @Test
    void timestampInThePastDoesNotSleep() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);
        Instant past = NOW.minusSeconds(10);

        Execution execution = run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"%s","End":true}}}
                """.formatted(past), "{}");

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(sleeper.sleeps().isEmpty());
    }

    @Test
    void timestampExactlyNowDoesNotSleep() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);

        run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"%s","End":true}}}
                """.formatted(NOW), "{}");

        assertTrue(sleeper.sleeps().isEmpty());
    }

    @Test
    void invalidTimestampFailsTheExecution() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AslExecutor executor = newExecutor(sleeper, 120);

        Execution execution = run(executor, """
                {"StartAt":"W","States":{"W":{"Type":"Wait","Timestamp":"not-a-timestamp","End":true}}}
                """, "{}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertTrue(sleeper.sleeps().isEmpty());
    }

    private static AslExecutor newExecutor(RecordingSleeper sleeper, int maxWaitSeconds) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                mock(SnsJsonHandler.class),
                mock(CloudFormationQueryHandler.class),
                mock(Ec2Service.class),
                mock(S3Service.class),
                mock(EcsService.class),
                mock(EcsJsonHandler.class),
                mock(EventBridgeHandler.class),
                mock(SchedulerService.class),
                mock(SchedulerController.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class),
                mock(EmulatorConfig.class),
                null,
                mock(CustomResourceLiveness.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                sleeper,
                maxWaitSeconds);
    }

    private static Execution run(AslExecutor executor, String definition, String input) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("wait-state-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:us-east-1:000000000000:stateMachine:wait-state-test");
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("wait-exec");
        execution.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:wait-state-test:wait-exec");
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }

    private static final class RecordingSleeper implements AslExecutor.Sleeper {

        private final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long nanos) {
            sleeps.add(nanos);
        }

        List<Long> sleeps() {
            return sleeps;
        }

        long singleSleep() {
            assertEquals(1, sleeps.size(), "expected exactly one recorded sleep");
            return sleeps.getFirst();
        }
    }
}