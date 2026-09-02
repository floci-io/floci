package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Guards the bound AWS puts on a running execution: a state machine that never reaches a terminal
 * state is ended at 25,000 history events with {@code States.Runtime}. The cut is an event count,
 * not a wall clock, and the counter is neither reset nor offset: the {@code ExecutionFailed} event
 * is event 25,000 itself, so the last event the machine produced is 24,999.
 *
 * <p>A {@code Choice} that loops back through a {@code Pass} reaches it in a few hundred
 * milliseconds, which is why nothing here waits on the clock.
 */
@QuarkusTest
class AslExecutorHistoryEventLimitTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";

    /** A Choice that spins forever, four history events per lap and no terminal state. */
    private static final String RUNAWAY_LOOP = """
            {
              "StartAt": "Loop",
              "States": {
                "Loop": {
                  "Type": "Choice",
                  "Choices": [{"Variable": "$.spin", "BooleanEquals": true, "Next": "Tick"}],
                  "Default": "Done"
                },
                "Tick": {"Type": "Pass", "Next": "Loop"},
                "Done": {"Type": "Succeed"}
              }
            }
            """;

    /**
     * A Parallel branch that laps 10,000 times, four history events per lap. The lap count is high
     * enough that the branch alone produces far more than 25,000 events, and finite so the run ends
     * either way: bounded, at event 25,000; unbounded, by finishing every lap.
     */
    private static final String RUNAWAY_PARALLEL_BRANCH = """
            {
              "QueryLanguage": "JSONata",
              "StartAt": "Seed",
              "States": {
                "Seed": {"Type": "Pass", "Assign": {"laps": 0}, "Next": "Fan"},
                "Fan": {
                  "Type": "Parallel",
                  "Branches": [{
                    "StartAt": "Spin",
                    "States": {
                      "Spin": {
                        "Type": "Choice",
                        "Choices": [{"Condition": "{% $laps < 10000 %}", "Next": "Tick"}],
                        "Default": "Stop"
                      },
                      "Tick": {"Type": "Pass", "Assign": {"laps": "{% $laps + 1 %}"}, "Next": "Spin"},
                      "Stop": {"Type": "Succeed"}
                    }
                  }],
                  "End": true
                }
              }
            }
            """;

    /**
     * The same runaway branch under a Parallel that declares a Retry and a Catch for States.ALL,
     * the two clauses AWS allows on a Parallel. Reaching the limit is States.Runtime, which
     * catchMatches refuses before it reads ErrorEquals, so neither clause runs.
     */
    private static final String RUNAWAY_PARALLEL_BRANCH_UNDER_RETRY_AND_CATCH = """
            {
              "QueryLanguage": "JSONata",
              "StartAt": "Seed",
              "States": {
                "Seed": {"Type": "Pass", "Assign": {"laps": 0}, "Next": "Fan"},
                "Fan": {
                  "Type": "Parallel",
                  "Retry": [{"ErrorEquals": ["States.ALL"], "IntervalSeconds": 30, "MaxAttempts": 3}],
                  "Catch": [{"ErrorEquals": ["States.ALL"], "Next": "Recovered"}],
                  "Branches": [{
                    "StartAt": "Spin",
                    "States": {
                      "Spin": {
                        "Type": "Choice",
                        "Choices": [{"Condition": "{% $laps < 10000 %}", "Next": "Tick"}],
                        "Default": "Stop"
                      },
                      "Tick": {"Type": "Pass", "Assign": {"laps": "{% $laps + 1 %}"}, "Next": "Spin"},
                      "Stop": {"Type": "Succeed"}
                    }
                  }],
                  "Next": "Recovered"
                },
                "Recovered": {"Type": "Succeed"}
              }
            }
            """;

    /**
     * More items than the 25,000-event limit has room for: each one enters and exits a single Pass,
     * so 13,000 items are 26,000 events if the parent is charged for them.
     */
    private static final int DISTRIBUTED_MAP_ITEMS = 13_000;

    /** One item per iteration, run one at a time so the run is the same on every machine. */
    private static final String DISTRIBUTED_MAP_OVER_MANY_ITEMS = """
            {
              "StartAt": "Fan",
              "States": {
                "Fan": {
                  "Type": "Map",
                  "ItemsPath": "$.items",
                  "MaxConcurrency": 1,
                  "ItemProcessor": {
                    "ProcessorConfig": {"Mode": "DISTRIBUTED", "ExecutionType": "STANDARD"},
                    "StartAt": "Handle",
                    "States": {"Handle": {"Type": "Pass", "End": true}}
                  },
                  "End": true
                }
              }
            }
            """;

    /**
     * The same items run inline, 40 at a time. An inline Map's iterations are part of this
     * execution, so together they run it out of history events while 40 of them are in flight.
     */
    private static final String INLINE_MAP_OVER_MANY_ITEMS = """
            {
              "StartAt": "Fan",
              "States": {
                "Fan": {
                  "Type": "Map",
                  "ItemsPath": "$.items",
                  "MaxConcurrency": 40,
                  "ItemProcessor": {
                    "StartAt": "Handle",
                    "States": {"Handle": {"Type": "Pass", "End": true}}
                  },
                  "End": true
                }
              }
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<HistoryEvent> history = new ArrayList<>();
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() {
        executor = new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
                mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
                mock(S3Service.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsService.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class), mock(EmulatorConfig.class), vertx, null);
    }

    @Test
    void runawayLoopFailsWithTheHistoryEventLimitError() {
        Execution execution = run(RUNAWAY_LOOP);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals("The execution reached the maximum number of history events (25000).",
                execution.getCause());
        assertNotNull(execution.getStopDate());
    }

    @Test
    void executionFailedIsTheTwentyFiveThousandthEvent() {
        run(RUNAWAY_LOOP);

        assertEquals(25000, history.size());
        HistoryEvent last = history.get(history.size() - 1);
        assertEquals("ExecutionFailed", last.getType());
        assertEquals(25000L, last.getId());
        // Not reset and not offset: 24,999 is the last event the machine itself produced.
        HistoryEvent lastFromTheMachine = history.get(history.size() - 2);
        assertEquals(24999L, lastFromTheMachine.getId());
        assertNotEquals("ExecutionFailed", lastFromTheMachine.getType());
    }

    @Test
    void eventsProducedInsideAParallelBranchCountTowardsTheLimit() {
        Execution execution = run(RUNAWAY_PARALLEL_BRANCH);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals("The execution reached the maximum number of history events (25000).",
                execution.getCause());
    }

    /**
     * The branch produced the events that ran the execution out of them, and floci publishes none
     * of them. What the caller reads back is the four events the top-level flow did produce, ending
     * in the failure: an unbroken chain, not four events numbered as if 25,000 had happened.
     */
    @Test
    void aParallelBranchLeavesThePublishedHistoryUnbroken() {
        run(RUNAWAY_PARALLEL_BRANCH);

        assertEquals(List.of("PassStateEntered", "PassStateExited", "ParallelStateEntered",
                             "ExecutionFailed"),
                history.stream().map(HistoryEvent::getType).toList());
        for (int index = 0; index < history.size(); index++) {
            assertEquals(index + 1L, history.get(index).getId(), "unexpected id at index " + index);
            assertEquals((long) index, history.get(index).getPreviousEventId(),
                    "unexpected previousEventId at index " + index);
        }
    }

    /**
     * A Distributed Map runs each item as a child execution, and a child execution has a history of
     * its own. The parent is charged for the Map run, not for what the items do inside it, so a
     * Distributed Map over more items than the limit has room for still succeeds.
     */
    @Test
    void aDistributedMapItemDoesNotSpendTheParentsHistoryEvents() {
        Execution execution = run(DISTRIBUTED_MAP_OVER_MANY_ITEMS, manyItemsInput());

        assertEquals("SUCCEEDED", execution.getStatus(),
                "error=" + execution.getError() + " cause=" + execution.getCause());
    }

    /**
     * The same items run inline are the execution's own, whatever the concurrency: 40 iterations
     * counting against one limit run it out of history events, where a Distributed Map's items do
     * not.
     */
    @Test
    void concurrentInlineMapIterationsSpendTheExecutionsHistoryEvents() {
        Execution execution = run(INLINE_MAP_OVER_MANY_ITEMS, manyItemsInput());

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals("The execution reached the maximum number of history events (25000).",
                execution.getCause());
    }

    /**
     * The event that reaches the limit is what ends the execution, so exactly one of the iterations
     * racing for the last countable event may take it. Reading the count and then taking it lets
     * every racer through the check and spends more events than the execution has.
     */
    @Test
    void onlyOneOfTheThreadsRacingForTheLastCountableEventTakesIt() throws Exception {
        int racers = 40;
        // 24,998 events produced: one is left before the limit, and event 25,000 is the failure.
        AtomicLong producedEventCount = new AtomicLong(24_998);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger counted = new AtomicInteger();

        List<Thread> racerThreads = new ArrayList<>();
        for (int racer = 0; racer < racers; racer++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    AslExecutor.countTowardsHistoryEventLimit(producedEventCount);
                    counted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (AslExecutor.FailStateException expected) {
                    // The limit was already reached by another racer.
                }
            });
            racerThreads.add(thread);
            thread.start();
        }
        ready.await();
        go.countDown();
        for (Thread thread : racerThreads) {
            thread.join();
        }

        assertEquals(1, counted.get(), "more than one racer took the last countable event");
    }

    /** {@code {"items": [0, 1, ... ]}}, one item per iteration of the Distributed Map. */
    private static String manyItemsInput() {
        StringBuilder input = new StringBuilder("{\"items\": [0");
        for (int item = 1; item < DISTRIBUTED_MAP_ITEMS; item++) {
            input.append(',').append(item);
        }
        return input.append("]}").toString();
    }

    @Test
    void neitherRetryNorCatchInterceptsTheHistoryEventLimit() {
        long startedAt = System.currentTimeMillis();

        Execution execution = run(RUNAWAY_PARALLEL_BRANCH_UNDER_RETRY_AND_CATCH);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals("The execution reached the maximum number of history events (25000).",
                execution.getCause());
        assertEquals("ExecutionFailed", history.get(history.size() - 1).getType());
        assertNotEquals("SucceedStateEntered", history.get(history.size() - 2).getType());
        // Three attempts at IntervalSeconds 30 would put this past 90 seconds if the Retry ran.
        assertTrue(System.currentTimeMillis() - startedAt < 30_000,
                "the Retry's backoff ran");
    }

    private Execution run(String definition) {
        return run(definition, "{\"spin\": true}");
    }

    private Execution run(String definition, String input) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("history-event-limit-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:%s:%s:stateMachine:history-event-limit-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("history-event-limit-execution");
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:history-event-limit-test:history-event-limit-execution"
                        .formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }
}
