package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.sqs.SqsService;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SCHEDULE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final SchedulerService schedulerService;
    private final ScheduleInvoker scheduleInvoker;
    private final SqsService sqsService;
    private final EmulatorConfig config;
    private final List<String> scheduleNames = new ArrayList<>();
    private final List<ScheduleDispatcher> dispatchers = new ArrayList<>();

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
    }

    private ScheduleDispatcher track(ScheduleDispatcher dispatcher) {
        dispatchers.add(dispatcher);
        return dispatcher;
    }
}
