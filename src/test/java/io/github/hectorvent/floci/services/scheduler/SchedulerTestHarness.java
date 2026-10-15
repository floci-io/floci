package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.restassured.response.Response;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Creates schedules through the REST API and drives them with a dispatcher scoped to one schedule,
 * then deletes and stops what it created. An integration test builds one per test and calls
 * {@link #cleanUp()} after it.
 */
final class SchedulerTestHarness {

    static final String REGION = "us-east-1";
    static final String ROLE_ARN = "arn:aws:iam::000000000000:role/scheduler-role";

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SCHEDULE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final SchedulerService schedulerService;
    private final ScheduleInvoker scheduleInvoker;
    private final SqsService sqsService;
    private final EmulatorConfig config;
    private final List<String> scheduleNames = new ArrayList<>();
    private final List<ScheduleDispatcher> dispatchers = new ArrayList<>();
    private final List<String> stateMachineArns = new ArrayList<>();

    SchedulerTestHarness(SchedulerService schedulerService, ScheduleInvoker scheduleInvoker,
                         SqsService sqsService, EmulatorConfig config) {
        this.schedulerService = schedulerService;
        this.scheduleInvoker = scheduleInvoker;
        this.sqsService = sqsService;
        this.config = config;
    }

    static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** The one-time expression that fires at {@code time}, to the second. */
    static String atExpression(Instant time) {
        return "at(" + SCHEDULE_TIME.format(time) + ")";
    }

    void createSchedule(String name, String expression, String state, String targetArn, String input) {
        postSchedule(name, scheduleRequest(expression, state, targetArn, input));
    }

    /**
     * Creates an enabled recurring schedule that starts a day from now, and returns that start, its
     * first occurrence. The application's own dispatcher keeps ticking while a test runs, so a
     * schedule that is already due could fire there as well as on the test's dispatcher.
     */
    Instant createRecurringSchedule(String name, String expression, String targetArn, String input) {
        Instant startDate = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        ObjectNode request = scheduleRequest(expression, "ENABLED", targetArn, input);
        request.put("StartDate", startDate.getEpochSecond());
        postSchedule(name, request);
        return startDate;
    }

    private static ObjectNode scheduleRequest(String expression, String state, String targetArn, String input) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ScheduleExpression", expression);
        request.putObject("FlexibleTimeWindow").put("Mode", "OFF");
        request.put("State", state);
        ObjectNode target = request.putObject("Target");
        target.put("Arn", targetArn);
        target.put("RoleArn", ROLE_ARN);
        target.put("Input", input);
        return request;
    }

    private void postSchedule(String name, ObjectNode request) {
        given()
                .contentType("application/json")
                .body(request.toString())
                .when()
                .post("/schedules/" + name)
                .then()
                .statusCode(200);
        scheduleNames.add(name);
    }

    /** Registers a schedule created another way, so {@link #cleanUp()} deletes it too. */
    void trackSchedule(String name) {
        scheduleNames.add(name);
    }

    /** A dispatcher that sees only the named schedule, so other tests' schedules never fire. */
    ScheduleDispatcher dispatcherFor(String scheduleName) {
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        SchedulerService scopedSchedulerService = mock(SchedulerService.class);
        when(scopedSchedulerService.listAllSchedules()).thenReturn(List.of(schedule));
        return track(new ScheduleDispatcher(scopedSchedulerService, scheduleInvoker, sqsService, config));
    }

    /** A dispatcher over every schedule the service holds. */
    ScheduleDispatcher unscopedDispatcher() {
        return track(new ScheduleDispatcher(schedulerService, scheduleInvoker, sqsService, config));
    }

    String createStateMachine(String name, String definition) {
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

    String startExecution(String stateMachineArn) {
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

    List<Map<String, Object>> listExecutions(String stateMachineArn) {
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

    Response waitForTerminalExecution(String executionArn) {
        await()
                .atMost(Duration.ofSeconds(10))
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

    void cleanUp() {
        for (String scheduleName : scheduleNames) {
            try {
                schedulerService.deleteSchedule(scheduleName, null, REGION);
            } catch (AwsException expected) {
                // The test, or ActionAfterCompletion=DELETE, may have deleted it already.
            }
        }
        for (ScheduleDispatcher dispatcher : dispatchers) {
            dispatcher.onStop(null);
        }
        for (String stateMachineArn : stateMachineArns) {
            given().header("X-Amz-Target", "AWSStepFunctions.DeleteStateMachine")
                    .contentType(SFN_CONTENT_TYPE).body(Map.of("stateMachineArn", stateMachineArn))
                    .when().post("/").then().statusCode(200);
        }
    }

    private ScheduleDispatcher track(ScheduleDispatcher dispatcher) {
        dispatchers.add(dispatcher);
        return dispatcher;
    }
}
