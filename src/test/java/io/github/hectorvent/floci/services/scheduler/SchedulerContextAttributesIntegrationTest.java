package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.scheduler.model.DeadLetterConfig;
import io.github.hectorvent.floci.services.scheduler.model.RetryPolicy;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.REGION;
import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.atExpression;
import static io.github.hectorvent.floci.services.scheduler.SchedulerTestHarness.uniqueName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Scheduler context attributes in a target's {@code Input} are replaced on delivery with the
 * schedule ARN, the scheduled time, the execution id and the attempt number.
 */
@QuarkusTest
class SchedulerContextAttributesIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final ObjectMapper MAPPER = new ObjectMapper();
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

    private final List<String> queueUrls = new ArrayList<>();
    private SchedulerTestHarness harness;

    @BeforeEach
    void createHarness() {
        harness = new SchedulerTestHarness(schedulerService, scheduleInvoker, sqsService, config);
    }

    @AfterEach
    void cleanUp() {
        harness.cleanUp();
        for (String queueUrl : queueUrls) {
            sqsService.deleteQueue(queueUrl, REGION);
        }
    }

    @Test
    void atScheduleDeliversInputWithContextAttributesReplaced() throws Exception {
        String queueName = uniqueName("ctx-at-q");
        String queueUrl = createQueue(queueName);
        String scheduleName = uniqueName("ctx-at");
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        harness.createSchedule(scheduleName, atExpression(fireAt), "ENABLED", queueArn(queueName), CONTEXT_INPUT);
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);

        harness.dispatcherFor(scheduleName).tick(fireAt.plusSeconds(5));

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
        harness.createSchedule(scheduleName, "rate(1 minute)", "ENABLED", queueArn(queueName), CONTEXT_INPUT);
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        Instant firstFire = schedule.getCreationDate().plus(1, ChronoUnit.MINUTES);
        ScheduleDispatcher dispatcher = harness.dispatcherFor(scheduleName);

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
        harness.createSchedule(scheduleName, atExpression(fireAt), "ENABLED", queueArn(queueName), CONTEXT_INPUT);
        ScheduleDispatcher dispatcher = harness.dispatcherFor(scheduleName);

        dispatcher.tick(fireAt.plusSeconds(1));
        String queueUrl = createQueue(queueName);
        dispatcher.tick(fireAt.plusSeconds(120));

        JsonNode body = singleBody(queueUrl);
        assertEquals(2, body.path("attempt").asInt());
        assertEquals(fireAt.toString(), body.path("time").asText());
        assertTrue(body.path("executionId").asText().matches("[0-9a-f]{16}"), body.toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deadLetterBodyMatchesLastAttemptContext(boolean expiresByAge) throws Exception {
        String queueName = uniqueName("ctx-missing-q");
        String dlqName = uniqueName("ctx-dlq");
        String dlqUrl = createQueue(dlqName);
        String scheduleName = uniqueName("ctx-dlq-schedule");
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        harness.createSchedule(scheduleName, atExpression(fireAt), "ENABLED", queueArn(queueName), CONTEXT_INPUT);
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        schedule.getTarget().setDeadLetterConfig(new DeadLetterConfig(queueArn(dlqName)));
        schedule.getTarget().setRetryPolicy(expiresByAge ? new RetryPolicy(180, 10) : new RetryPolicy(3600, 1));
        ScheduleDispatcher dispatcher = harness.dispatcherFor(scheduleName);

        dispatcher.tick(fireAt.plusSeconds(1));
        dispatcher.tick(fireAt.plusSeconds(61));
        if (expiresByAge) {
            dispatcher.tick(fireAt.plusSeconds(181));
        }

        List<Message> messages = sqsService.receiveMessage(dlqUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        Message message = messages.get(0);
        JsonNode request = MAPPER.readTree(message.getBody());
        JsonNode input = MAPPER.readTree(request.path("MessageBody").asText());
        assertEquals(2, input.path("attempt").asInt());
        assertEquals(schedule.getArn(), input.path("arn").asText());
        assertEquals(fireAt.toString(), input.path("time").asText());
        assertEquals(input.path("executionId").asText(),
                message.getMessageAttributes().get("EXECUTION_ID").getStringValue());
        assertEquals(input.path("time").asText(),
                message.getMessageAttributes().get("SCHEDULED_TIME").getStringValue());
        assertEquals("1", message.getMessageAttributes().get("RETRY_ATTEMPTS").getStringValue());
        assertEquals(expiresByAge ? "MaximumEventAgeInSeconds" : "MaximumRetryAttempts",
                message.getMessageAttributes().get("EXHAUSTED_RETRY_CONDITION").getStringValue());
    }

    @Test
    void inputWithoutContextAttributesIsDeliveredUnchanged() {
        String queueName = uniqueName("ctx-plain-q");
        String queueUrl = createQueue(queueName);
        String scheduleName = uniqueName("ctx-plain");
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String input = "{\"hello\":\"<world>\",\"n\":1}";
        harness.createSchedule(scheduleName, atExpression(fireAt), "ENABLED", queueArn(queueName), input);

        harness.dispatcherFor(scheduleName).tick(fireAt.plusSeconds(1));

        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertEquals(input, messages.get(0).getBody());
    }

    @Test
    void executionIdsDifferBetweenOccurrences() throws Exception {
        String queueName = uniqueName("ctx-ids-q");
        String queueUrl = createQueue(queueName);
        String scheduleName = uniqueName("ctx-ids");
        harness.createSchedule(scheduleName, "rate(1 minute)", "ENABLED", queueArn(queueName), CONTEXT_INPUT);
        Schedule schedule = schedulerService.getSchedule(scheduleName, null, REGION);
        Instant firstFire = schedule.getCreationDate().plus(1, ChronoUnit.MINUTES);
        ScheduleDispatcher dispatcher = harness.dispatcherFor(scheduleName);

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
}
