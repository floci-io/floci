package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class SchedulerStepFunctionsIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/scheduler-role";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SCHEDULE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

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

    private final List<String> scheduleNames = new ArrayList<>();
    private final List<String> stateMachineArns = new ArrayList<>();
    private final List<ScheduleDispatcher> testDispatchers = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() {
        for (String scheduleName : scheduleNames) {
            try {
                schedulerService.deleteSchedule(scheduleName, null, REGION);
            } catch (AwsException expected) {
                // A schedule with ActionAfterCompletion=DELETE may already be gone.
            }
        }
        for (String stateMachineArn : stateMachineArns) {
            stepFunctionsService.deleteStateMachine(stateMachineArn);
        }
        for (ScheduleDispatcher dispatcher : testDispatchers) {
            dispatcher.onStop(null);
        }
    }

    @Test
    void oneTimeScheduleStartsStateMachineWithExactInput() {
        String stateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-at");
        String input = "{\"order\":{\"id\":42},\"items\":[\"a\",\"b\"]}";
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        createSchedule(scheduleName, "at(" + SCHEDULE_TIME.format(fireAt) + ")", "ENABLED",
                stateMachineArn, input);

        dispatcherFor(scheduleName).tick(fireAt);

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
        createSchedule(scheduleName, "rate(1 minute)", "ENABLED", stateMachineArn, "{\"kind\":\"recurring\"}");
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        Instant firstFire = schedule.getCreationDate().plus(61, ChronoUnit.SECONDS);
        ScheduleDispatcher dispatcher = dispatcherFor(scheduleName);

        dispatcher.tick(firstFire);
        dispatcher.tick(firstFire.plus(61, ChronoUnit.SECONDS));

        assertEquals(2, listExecutions(stateMachineArn).size());
    }

    @Test
    void disabledScheduleDoesNotStartStateMachine() {
        String stateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-disabled");
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        createSchedule(scheduleName, "at(" + SCHEDULE_TIME.format(fireAt) + ")", "DISABLED",
                stateMachineArn, "{\"disabled\":true}");

        dispatcherFor(scheduleName).tick(fireAt);

        assertEquals(0, listExecutions(stateMachineArn).size());
    }

    @Test
    void stepFunctionsDeleteScheduleStopsFutureOccurrence() {
        String targetStateMachineArn = createPassStateMachine();
        String scheduleName = uniqueName("sfn-delete");
        createSchedule(scheduleName, "rate(1 minute)", "ENABLED",
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

        ScheduleDispatcher dispatcher = new ScheduleDispatcher(
                schedulerService, scheduleInvoker, sqsService, config);
        testDispatchers.add(dispatcher);
        dispatcher.tick(fireAt);

        assertEquals(0, listExecutions(targetStateMachineArn).size());
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

    private void createSchedule(String name, String expression, String state,
                                String targetArn, String input) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ScheduleExpression", expression);
        request.putObject("FlexibleTimeWindow").put("Mode", "OFF");
        request.put("State", state);
        ObjectNode target = request.putObject("Target");
        target.put("Arn", targetArn);
        target.put("RoleArn", ROLE_ARN);
        target.put("Input", input);

        given()
                .contentType("application/json")
                .body(request.toString())
                .when()
                .post("/schedules/" + name)
                .then()
                .statusCode(200);
        scheduleNames.add(name);
    }

    private ScheduleDispatcher dispatcherFor(String scheduleName) {
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        SchedulerService scopedSchedulerService = mock(SchedulerService.class);
        when(scopedSchedulerService.listAllSchedules()).thenReturn(List.of(schedule));
        ScheduleDispatcher dispatcher = new ScheduleDispatcher(
                scopedSchedulerService, scheduleInvoker, sqsService, config);
        testDispatchers.add(dispatcher);
        return dispatcher;
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

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
