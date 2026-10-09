package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.REGION;
import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.ROLE_ARN;
import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.atExpression;
import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.uniqueName;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class SchedulerStepFunctionsIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    ScheduleInvoker scheduleInvoker;

    @Inject
    SchedulerService schedulerService;

    @Inject
    StepFunctionsService stepFunctionsService;

    @Inject
    SqsService sqsService;

    @Inject
    EmulatorConfig config;

    private final List<String> stateMachineArns = new ArrayList<>();
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
        for (String stateMachineArn : stateMachineArns) {
            stepFunctionsService.deleteStateMachine(stateMachineArn);
        }
    }

    @Test
    void oneTimeScheduleStartsStateMachineWithExactInput() {
        String stateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-at");
        String input = "{\"order\":{\"id\":42},\"items\":[\"a\",\"b\"]}";
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        harness.createSchedule(scheduleName, atExpression(fireAt), "ENABLED", stateMachineArn, input);

        harness.dispatcherFor(scheduleName).tick(fireAt);

        List<Map<String, Object>> executions = listExecutions(stateMachineArn);
        assertEquals(1, executions.size());
        Response execution = waitForTerminalExecution((String) executions.get(0).get("executionArn"));
        assertEquals("SUCCEEDED", execution.jsonPath().getString("status"));
        assertEquals(input, execution.jsonPath().getString("input"));
        assertEquals(input, execution.jsonPath().getString("output"));
    }

    @Test
    void recurringScheduleStartsOneExecutionPerOccurrence() {
        String stateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-rate");
        harness.createSchedule(scheduleName, "rate(1 minute)", "ENABLED", stateMachineArn, "{\"kind\":\"recurring\"}");
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        Instant firstFire = schedule.getCreationDate().plus(61, ChronoUnit.SECONDS);
        ScheduleDispatcher dispatcher = harness.dispatcherFor(scheduleName);

        dispatcher.tick(firstFire);
        dispatcher.tick(firstFire.plus(61, ChronoUnit.SECONDS));

        assertEquals(2, listExecutions(stateMachineArn).size());
    }

    @Test
    void disabledScheduleDoesNotStartStateMachine() {
        String stateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-disabled");
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        harness.createSchedule(scheduleName, atExpression(fireAt), "DISABLED", stateMachineArn, "{\"disabled\":true}");

        harness.dispatcherFor(scheduleName).tick(fireAt);

        assertEquals(0, listExecutions(stateMachineArn).size());
    }

    @Test
    void stepFunctionsDeleteScheduleStopsFutureOccurrence() {
        String targetStateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-delete");
        harness.createSchedule(scheduleName, "rate(1 minute)", "ENABLED",
                targetStateMachineArn, "{\"deleted\":false}");
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        Instant fireAt = schedule.getCreationDate().plus(61, ChronoUnit.SECONDS);
        String deleterStateMachineArn = createStateMachine(uniqueName("schedule-deleter"), """
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

        Response deletion = waitForTerminalExecution(startExecution(deleterStateMachineArn));
        assertEquals("SUCCEEDED", deletion.jsonPath().getString("status"));

        harness.unscopedDispatcher().tick(fireAt);

        assertEquals(0, listExecutions(targetStateMachineArn).size());
    }

    @Test
    void sdkTaskStructuredInputReachesDueStateMachineTarget() {
        String targetStateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-sdk-input");
        String expectedInput = "{\"scheduleId\":\"schedule-123\",\"nested\":{\"quote\":\"a\\\"b\"}}";
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String creatorStateMachineArn = createStateMachine(uniqueName("schedule-creator"), """
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

        Response creation = waitForTerminalExecution(startExecution(creatorStateMachineArn));
        assertEquals("SUCCEEDED", creation.jsonPath().getString("status"));
        harness.trackSchedule(scheduleName);
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        assertEquals(expectedInput, schedule.getTarget().getInput());

        harness.dispatcherFor(scheduleName).tick(fireAt);

        List<Map<String, Object>> executions = listExecutions(targetStateMachineArn);
        assertEquals(1, executions.size());
        Response targetExecution = waitForTerminalExecution((String) executions.get(0).get("executionArn"));
        assertEquals("SUCCEEDED", targetExecution.jsonPath().getString("status"));
        assertEquals(expectedInput, targetExecution.jsonPath().getString("input"));
        assertEquals(expectedInput, targetExecution.jsonPath().getString("output"));
    }

    private String createPassStateMachine() {
        String name = uniqueName("scheduled-workflow");
        String definition = "{\"StartAt\":\"Pass\",\"States\":{\"Pass\":{\"Type\":\"Pass\",\"End\":true}}}";
        return createStateMachine(name, definition);
    }

    private String createStateMachine(String name, String definition) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("name", name);
        request.put("definition", definition);
        request.put("roleArn", ROLE_ARN);

        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body(request.toString())
                .when()
                .post("/");
        response.then().statusCode(200);
        String stateMachineArn = response.jsonPath().getString("stateMachineArn");
        stateMachineArns.add(stateMachineArn);
        return stateMachineArn;
    }

    private String startExecution(String stateMachineArn) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("stateMachineArn", stateMachineArn);
        request.put("input", "{}");
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body(request.toString())
                .when()
                .post("/");
        response.then().statusCode(200);
        return response.jsonPath().getString("executionArn");
    }

    private List<Map<String, Object>> listExecutions(String stateMachineArn) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("stateMachineArn", stateMachineArn);
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.ListExecutions")
                .contentType(SFN_CONTENT_TYPE)
                .body(request.toString())
                .when()
                .post("/");
        response.then().statusCode(200);
        return response.jsonPath().getList("executions");
    }

    private Response waitForTerminalExecution(String executionArn) {
        await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !"RUNNING".equals(describeExecution(executionArn).jsonPath().getString("status")));
        return describeExecution(executionArn);
    }

    private Response describeExecution(String executionArn) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("executionArn", executionArn);
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body(request.toString())
                .when()
                .post("/");
        response.then().statusCode(200);
        return response;
    }
}
