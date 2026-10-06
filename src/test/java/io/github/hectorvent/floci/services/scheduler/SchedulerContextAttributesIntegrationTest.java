package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Scheduler context attributes in a target's {@code Input} are replaced on delivery with the
 * schedule ARN, the scheduled time, the execution id and the attempt number.
 */
@QuarkusTest
class SchedulerContextAttributesIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/scheduler-role";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SCHEDULE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String CONTEXT_INPUT = "{\"arn\":\"<aws.scheduler.schedule-arn>\","
            + "\"time\":\"<aws.scheduler.scheduled-time>\","
            + "\"executionId\":\"<aws.scheduler.execution-id>\","
            + "\"attempt\":<aws.scheduler.attempt-number>}";

    @Inject
    ScheduleInvoker scheduleInvoker;

    @Inject
    SchedulerService schedulerService;

    @Inject
    SqsService sqsService;

    @Inject
    EmulatorConfig config;

    private final List<String> scheduleNames = new ArrayList<>();
    private final List<String> queueUrls = new ArrayList<>();
    private final List<ScheduleDispatcher> testDispatchers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String scheduleName : scheduleNames) {
            try {
                schedulerService.deleteSchedule(scheduleName, null, REGION);
            } catch (AwsException expected) {
                // A test may have deleted the schedule already.
            }
        }
        for (String queueUrl : queueUrls) {
            sqsService.deleteQueue(queueUrl, REGION);
        }
        for (ScheduleDispatcher dispatcher : testDispatchers) {
            dispatcher.onStop(null);
        }
    }

    @Test
    void atScheduleDeliversInputWithContextAttributesReplaced() throws Exception {
        String queueName = uniqueName("ctx-at-q");
        String queueUrl = createQueue(queueName);
        String scheduleName = uniqueName("ctx-at");
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        createSchedule(scheduleName, "at(" + SCHEDULE_TIME.format(fireAt) + ")", queueArn(queueName),
                CONTEXT_INPUT);
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);

        dispatcherFor(scheduleName).tick(fireAt.plusSeconds(5));

        JsonNode body = singleBody(queueUrl);
        assertEquals(schedule.getArn(), body.path("arn").asText());
        assertEquals(fireAt.toString(), body.path("time").asText());
        assertTrue(body.path("time").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"),
                body.toString());
        assertTrue(body.path("executionId").asText().matches("[0-9a-f]{16}"), body.toString());
        assertEquals(1, body.path("attempt").asInt());
        assertTrue(body.path("attempt").isInt(), body.toString());
    }

    @Test
    void rateScheduleDeliversEachOccurrenceWithItsScheduledTime() throws Exception {
        String queueName = uniqueName("ctx-rate-q");
        String queueUrl = createQueue(queueName);
        String scheduleName = uniqueName("ctx-rate");
        createSchedule(scheduleName, "rate(1 minute)", queueArn(queueName), CONTEXT_INPUT);
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        Instant firstFire = schedule.getCreationDate().plus(1, ChronoUnit.MINUTES);
        ScheduleDispatcher dispatcher = dispatcherFor(scheduleName);

        dispatcher.tick(firstFire.plusSeconds(1));

        JsonNode body = singleBody(queueUrl);
        assertEquals(schedule.getArn(), body.path("arn").asText());
        assertEquals(firstFire.truncatedTo(ChronoUnit.SECONDS).toString(), body.path("time").asText());
        assertTrue(body.path("executionId").asText().matches("[0-9a-f]{16}"), body.toString());
        assertEquals(1, body.path("attempt").asInt());
    }

    @Test
    void retriedDeliveryCarriesTheNextAttemptNumber() throws Exception {
        String queueName = uniqueName("ctx-retry-q");
        String scheduleName = uniqueName("ctx-retry");
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        // The queue does not exist yet, so the first attempt fails and is retried on a later tick.
        createSchedule(scheduleName, "at(" + SCHEDULE_TIME.format(fireAt) + ")", queueArn(queueName),
                CONTEXT_INPUT);
        ScheduleDispatcher dispatcher = dispatcherFor(scheduleName);

        dispatcher.tick(fireAt.plusSeconds(1));
        String queueUrl = createQueue(queueName);
        dispatcher.tick(fireAt.plusSeconds(120));

        JsonNode body = singleBody(queueUrl);
        assertEquals(2, body.path("attempt").asInt());
        assertEquals(fireAt.toString(), body.path("time").asText());
        assertTrue(body.path("executionId").asText().matches("[0-9a-f]{16}"), body.toString());
    }

    @Test
    void inputWithoutContextAttributesIsDeliveredUnchanged() {
        String queueName = uniqueName("ctx-plain-q");
        String queueUrl = createQueue(queueName);
        String scheduleName = uniqueName("ctx-plain");
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String input = "{\"hello\":\"<world>\",\"n\":1}";
        createSchedule(scheduleName, "at(" + SCHEDULE_TIME.format(fireAt) + ")", queueArn(queueName), input);

        dispatcherFor(scheduleName).tick(fireAt.plusSeconds(1));

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertEquals(input, messages.get(0).getBody());
    }

    @Test
    void executionIdsDifferBetweenOccurrences() throws Exception {
        String queueName = uniqueName("ctx-ids-q");
        String queueUrl = createQueue(queueName);
        String scheduleName = uniqueName("ctx-ids");
        createSchedule(scheduleName, "rate(1 minute)", queueArn(queueName), CONTEXT_INPUT);
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        Instant firstFire = schedule.getCreationDate().plus(1, ChronoUnit.MINUTES);
        ScheduleDispatcher dispatcher = dispatcherFor(scheduleName);

        dispatcher.tick(firstFire.plusSeconds(1));
        dispatcher.tick(firstFire.plusSeconds(62));

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(2, messages.size());
        String first = MAPPER.readTree(messages.get(0).getBody()).path("executionId").asText();
        String second = MAPPER.readTree(messages.get(1).getBody()).path("executionId").asText();
        assertNotEquals(first, second);
    }

    private JsonNode singleBody(String queueUrl) throws Exception {
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        String body = messages.get(0).getBody();
        assertTrue(!body.contains("<aws.scheduler."), body);
        return MAPPER.readTree(body);
    }

    private String createQueue(String queueName) {
        String queueUrl = sqsService.createQueue(queueName, Map.of(), REGION).getQueueUrl();
        queueUrls.add(queueUrl);
        return queueUrl;
    }

    private static String queueArn(String queueName) {
        return "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + queueName;
    }

    private void createSchedule(String name, String expression, String targetArn, String input) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ScheduleExpression", expression);
        request.putObject("FlexibleTimeWindow").put("Mode", "OFF");
        request.put("State", "ENABLED");
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

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
