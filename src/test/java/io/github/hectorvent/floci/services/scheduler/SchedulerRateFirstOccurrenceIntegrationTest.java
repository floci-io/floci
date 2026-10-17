package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.testing.IndependentServiceSettingsProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.REGION;
import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.ROLE_ARN;
import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.uniqueName;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a rate schedule first fires at its stored start date, or at its creation date when
 * it has none, whether it is created or updated directly or through a Step Functions SDK task.
 *
 * <p>Each test ticks a dispatcher of its own at chosen instants, so the background dispatcher is
 * off: {@code floci.services.scheduler.invocation-enabled=false} comes from
 * {@link IndependentServiceSettingsProfile}, which this class shares with other classes that each
 * call a different service.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
class SchedulerRateFirstOccurrenceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    SchedulerService schedulerService;
    @Inject
    ScheduleInvoker scheduleInvoker;
    @Inject
    SqsService sqsService;
    @Inject
    EmulatorConfig config;

    private SchedulerTestHarness harness;
    private final List<String> queues = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        harness = new SchedulerTestHarness(schedulerService, scheduleInvoker, sqsService, config);
    }

    @AfterEach
    void cleanUp() {
        harness.cleanUp();
        for (String queue : queues) {
            sqsService.deleteQueue(queue, REGION);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,omitted", "false,future", "false,updated",
            "true,omitted", "true,future", "true,updated"})
    void firstOccurrenceUsesStoredStartThroughDirectAndSdkTasks(boolean sdkTask, String mode) throws Exception {
        String name = uniqueName("rate-first");
        String queueName = uniqueName("rate-first-q");
        String queueUrl = sqsService.createQueue(queueName, Map.of(), REGION).getQueueUrl();
        queues.add(queueUrl);
        String queueArn = sqsService.getQueueAttributes(queueUrl, List.of("QueueArn"), REGION).get("QueueArn");
        Instant start = Instant.now().plusSeconds(86400);
        ObjectNode request = MAPPER.createObjectNode();
        request.put("Name", name);
        request.put("ScheduleExpression", "rate(10 minutes)");
        request.put("State", "ENABLED");
        request.putObject("FlexibleTimeWindow").put("Mode", "OFF");
        ObjectNode target = request.putObject("Target");
        target.put("Arn", queueArn);
        target.put("RoleArn", ROLE_ARN);
        target.put("Input", "<aws.scheduler.scheduled-time>");
        if (!"omitted".equals(mode)) {
            request.put("StartDate", start.toEpochMilli() / 1000.0);
        }
        harness.trackSchedule(name);
        applySchedule(request, sdkTask, false);
        if ("updated".equals(mode)) {
            request.put("StartDate", start.plusSeconds(120).toEpochMilli() / 1000.0);
            applySchedule(request, sdkTask, true);
        }
        Schedule schedule = schedulerService.getSchedule(name, null, REGION);
        Instant firstFire = schedule.getStartDate() != null ? schedule.getStartDate() : schedule.getCreationDate();
        ScheduleDispatcher dispatcher = harness.dispatcherFor(name);
        dispatcher.tick(firstFire.minusMillis(1));
        assertTrue(sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION).isEmpty());

        dispatcher.tick(firstFire.plusSeconds(5));
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertEquals(firstFire.truncatedTo(ChronoUnit.SECONDS).toString(), messages.get(0).getBody());
        dispatcher.tick(firstFire.plusSeconds(604));
        assertTrue(sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION).isEmpty());
        dispatcher.tick(firstFire.plusSeconds(605));
        messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertEquals(firstFire.plusSeconds(605).truncatedTo(ChronoUnit.SECONDS).toString(), messages.get(0).getBody());
    }

    private void applySchedule(ObjectNode request, boolean sdkTask, boolean update) {
        if (!sdkTask) {
            if (update) {
                given().contentType("application/json").body(request.toString())
                        .when().put("/schedules/" + request.path("Name").asText()).then().statusCode(200);
            } else {
                given().contentType("application/json").body(request.toString())
                        .when().post("/schedules/" + request.path("Name").asText()).then().statusCode(200);
            }
            return;
        }
        ObjectNode definition = MAPPER.createObjectNode();
        definition.put("StartAt", "Schedule");
        ObjectNode task = definition.putObject("States").putObject("Schedule");
        task.put("Type", "Task");
        task.put("Resource", "arn:aws:states:::aws-sdk:scheduler:" + (update ? "updateSchedule" : "createSchedule"));
        task.set("Parameters", request);
        task.put("End", true);
        String machine = harness.createStateMachine(uniqueName("rate-first-task"), definition.toString());
        String execution = harness.startExecution(machine);
        assertEquals("SUCCEEDED", harness.waitForTerminalExecution(execution).jsonPath().getString("status"));
    }
}
