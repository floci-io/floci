package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.REGION;
import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.ROLE_ARN;
import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.atExpression;
import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.uniqueName;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class SchedulerStepFunctionsIntegrationTest {

    @Inject
    ScheduleInvoker scheduleInvoker;

    @Inject
    SchedulerService schedulerService;

    @Inject
    SqsService sqsService;

    @Inject
    EmulatorConfig config;

    private SchedulerTestHarness harness;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createHarness() {
        harness = new SchedulerTestHarness(schedulerService, scheduleInvoker, sqsService, config);
    }

    @AfterEach
    void cleanUp() {
        harness.cleanUp();
    }

    @Test
    void oneTimeScheduleStartsStateMachineWithExactInput() {
        String stateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-at");
        String input = "{\"order\":{\"id\":42},\"items\":[\"a\",\"b\"]}";
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        harness.createSchedule(scheduleName, atExpression(fireAt), "ENABLED", stateMachineArn, input);

        harness.dispatcherFor(scheduleName).tick(fireAt);

        List<Map<String, Object>> executions = harness.listExecutions(stateMachineArn);
        assertEquals(1, executions.size());
        Response execution = harness.waitForTerminalExecution((String) executions.get(0).get("executionArn"));
        assertEquals("SUCCEEDED", execution.jsonPath().getString("status"));
        assertEquals(input, execution.jsonPath().getString("input"));
        assertEquals(input, execution.jsonPath().getString("output"));
    }

    @Test
    void recurringScheduleStartsOneExecutionPerOccurrence() {
        String stateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-rate");
        Instant firstFire = harness.createRecurringSchedule(scheduleName, "rate(1 minute)", stateMachineArn,
                "{\"kind\":\"recurring\"}");
        ScheduleDispatcher dispatcher = harness.dispatcherFor(scheduleName);

        dispatcher.tick(firstFire);
        dispatcher.tick(firstFire.plus(61, ChronoUnit.SECONDS));

        assertEquals(2, harness.listExecutions(stateMachineArn).size());
    }

    @Test
    void disabledScheduleDoesNotStartStateMachine() {
        String stateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-disabled");
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        harness.createSchedule(scheduleName, atExpression(fireAt), "DISABLED", stateMachineArn, "{\"disabled\":true}");

        harness.dispatcherFor(scheduleName).tick(fireAt);

        assertEquals(0, harness.listExecutions(stateMachineArn).size());
    }

    @Test
    void stepFunctionsDeleteScheduleStopsFutureOccurrence() {
        String targetStateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-delete");
        Instant fireAt = harness.createRecurringSchedule(scheduleName, "rate(1 minute)",
                targetStateMachineArn, "{\"deleted\":false}");
        String deleterStateMachineArn = harness.createStateMachine(uniqueName("schedule-deleter"), """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Delete",
                  "States": {
                    "Delete": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:scheduler:deleteSchedule",
                      "Arguments": {"Name": "SCHEDULE_NAME"},
                      "End": true
                    }
                  }
                }
                """.replace("SCHEDULE_NAME", scheduleName));

        Response deletion = harness.waitForTerminalExecution(harness.startExecution(deleterStateMachineArn));
        assertEquals("SUCCEEDED", deletion.jsonPath().getString("status"));

        harness.unscopedDispatcher().tick(fireAt);

        assertEquals(0, harness.listExecutions(targetStateMachineArn).size());
    }

    @Test
    void sdkTaskStructuredInputReachesDueStateMachineTarget() {
        String targetStateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-sdk-input");
        String expectedInput = "{\"scheduleId\":\"schedule-123\",\"nested\":{\"quote\":\"a\\\"b\"}}";
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String creatorStateMachineArn = harness.createStateMachine(uniqueName("schedule-creator"), """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Create",
                  "States": {
                    "Create": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:scheduler:createSchedule",
                      "Arguments": {
                        "Name": "SCHEDULE_NAME",
                        "ScheduleExpression": "SCHEDULE_EXPRESSION",
                        "FlexibleTimeWindow": {"Mode": "OFF"},
                        "Target": {
                          "Arn": "TARGET_ARN",
                          "RoleArn": "ROLE",
                          "Input": {
                            "scheduleId": "schedule-123",
                            "nested": {"quote": "a\\\"b"}
                          }
                        }
                      },
                      "End": true
                    }
                  }
                }
                """
                .replace("SCHEDULE_NAME", scheduleName)
                .replace("SCHEDULE_EXPRESSION", atExpression(fireAt))
                .replace("TARGET_ARN", targetStateMachineArn)
                .replace("ROLE", ROLE_ARN));

        Response creation = harness.waitForTerminalExecution(harness.startExecution(creatorStateMachineArn));
        assertEquals("SUCCEEDED", creation.jsonPath().getString("status"));
        harness.trackSchedule(scheduleName);
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        assertEquals(expectedInput, schedule.getTarget().getInput());

        harness.dispatcherFor(scheduleName).tick(fireAt);

        List<Map<String, Object>> executions = harness.listExecutions(targetStateMachineArn);
        assertEquals(1, executions.size());
        Response targetExecution = harness.waitForTerminalExecution((String) executions.get(0).get("executionArn"));
        assertEquals("SUCCEEDED", targetExecution.jsonPath().getString("status"));
        assertEquals(expectedInput, targetExecution.jsonPath().getString("input"));
        assertEquals(expectedInput, targetExecution.jsonPath().getString("output"));
    }

    private String createPassStateMachine() {
        String name = uniqueName("scheduled-workflow");
        String definition = "{\"StartAt\":\"Pass\",\"States\":{\"Pass\":{\"Type\":\"Pass\",\"End\":true}}}";
        return harness.createStateMachine(name, definition);
    }

}
