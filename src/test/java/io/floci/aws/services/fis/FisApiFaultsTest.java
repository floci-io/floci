package io.floci.aws.services.fis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.fis.model.ExperimentTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code aws:fis:inject-api-*} actions in {@link FisService}: the parameters a template must give them, the
 * faults a running one puts in force, and how each completes once its duration runs out.
 */
class FisApiFaultsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/fis-role";
    private static final String TARGET_ROLE = "arn:aws:iam::" + ACCOUNT_ID + ":role/app/checkout";
    private static final String THROTTLE = "aws:fis:inject-api-throttle-error";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SteppedClock clock = new SteppedClock();
    private FisStores stores;
    private FisService service;

    @BeforeEach
    void setUp() {
        service = service(false);
    }

    @Test
    void aServiceAwsDoesNotListIsRefusedUnlessAnyServiceIsOn() throws Exception {
        String action = action(THROTTLE, "s3", "GetObject", "100", "PT1M", null);

        AwsException refused = assertThrows(AwsException.class, () -> createTemplate(actions("api", action)));
        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("ec2 and kinesis"), refused.getMessage());

        service = service(true);
        createTemplate(actions("api", action));
    }

    @Test
    void aPercentageOutsideOneToAHundredIsRefused() {
        for (String percentage : List.of("0", "101", "50%")) {
            AwsException refused = assertThrows(AwsException.class, () -> createTemplate(
                    actions("api", action(THROTTLE, "ec2", "DescribeInstances", percentage, "PT1M", null))));
            assertTrue(refused.getMessage().contains("from 1 to 100"), percentage + ": " + refused.getMessage());
        }
    }

    @Test
    void aDurationOutsideAMinuteToTwelveHoursIsRefused() {
        for (String duration : List.of("PT59S", "PT12H1S", "1m")) {
            AwsException refused = assertThrows(AwsException.class, () -> createTemplate(
                    actions("api", action(THROTTLE, "ec2", "DescribeInstances", "100", duration, null))));
            assertTrue(refused.getMessage().contains("one minute to 12 hours"), duration + ": " + refused.getMessage());
        }
    }

    @Test
    void anEmptyOperationBetweenCommasIsRefused() {
        AwsException refused = assertThrows(AwsException.class, () -> createTemplate(
                actions("api", action(THROTTLE, "ec2", "DescribeInstances,", "100", "PT1M", null))));
        assertTrue(refused.getMessage().contains("separated using commas"), refused.getMessage());
    }

    @Test
    void aRunningActionIsAFaultOnItsTargetRolesCalls() throws Exception {
        start(createTemplate(actions("api",
                action(THROTTLE, "ec2", "DescribeInstances,RunInstances", "40", "PT5M", null))));

        List<FisApiFault> faults = service.activeApiFaults(REGION);

        assertEquals(1, faults.size());
        FisApiFault fault = faults.getFirst();
        assertEquals(FisApiFault.Kind.THROTTLE, fault.kind());
        assertEquals("ec2", fault.service());
        assertEquals(Set.of("DescribeInstances", "RunInstances"), fault.operations());
        assertEquals(40, fault.percentage());
        assertEquals(Set.of(TARGET_ROLE), fault.roleArns());
        assertTrue(fault.matches(TARGET_ROLE, Set.of("ec2"), "RunInstances"));
    }

    @Test
    void theActionCompletesWhenItsDurationRunsOutAndTheExperimentWithIt() throws Exception {
        String id = start(createTemplate(actions("api",
                action(THROTTLE, "ec2", "DescribeInstances", "100", "PT1M", null))));
        double started = experiment(id).path("startTime").asDouble();

        clock.advance(Duration.ofSeconds(59));
        assertEquals("running", experiment(id).path("actions").path("api").path("state").path("status").asText());
        assertEquals(1, service.activeApiFaults(REGION).size());

        clock.advance(Duration.ofSeconds(30));
        JsonNode experiment = experiment(id);
        JsonNode action = experiment.path("actions").path("api");
        assertEquals("completed", action.path("state").path("status").asText());
        assertEquals(started + 60, action.path("endTime").asDouble(), 0.001);
        assertEquals("completed", experiment.path("state").path("status").asText());
        assertEquals(started + 60, experiment.path("endTime").asDouble(), 0.001);
        assertTrue(service.activeApiFaults(REGION).isEmpty());
    }

    @Test
    void anActionThatStartsAfterAnotherStartsWhenThatOneCompletes() throws Exception {
        String id = start(createTemplate(actions(
                "first", action(THROTTLE, "ec2", "DescribeInstances", "100", "PT1M", null),
                "second", action("aws:fis:inject-api-internal-error", "kinesis", "PutRecord", "100", "PT2M",
                        "first"))));
        double started = experiment(id).path("startTime").asDouble();

        clock.advance(Duration.ofSeconds(90));
        JsonNode second = experiment(id).path("actions").path("second");
        assertEquals("running", second.path("state").path("status").asText());
        assertEquals(started + 60, second.path("startTime").asDouble(), 0.001);
        List<FisApiFault> faults = service.activeApiFaults(REGION);
        assertEquals(1, faults.size());
        assertEquals(FisApiFault.Kind.INTERNAL, faults.getFirst().kind());

        clock.advance(Duration.ofSeconds(90));
        JsonNode experiment = experiment(id);
        assertEquals("completed", experiment.path("state").path("status").asText());
        assertEquals(started + 180, experiment.path("endTime").asDouble(), 0.001);
    }

    @Test
    void anotherKindOfActionKeepsTheExperimentRunning() throws Exception {
        String id = start(createTemplate(actions(
                "api", action(THROTTLE, "ec2", "DescribeInstances", "100", "PT1M", null),
                "wait", "{\"actionId\":\"aws:fis:wait\",\"parameters\":{\"duration\":\"PT1M\"}}")));

        clock.advance(Duration.ofMinutes(5));

        JsonNode experiment = experiment(id);
        assertEquals("completed", experiment.path("actions").path("api").path("state").path("status").asText());
        assertEquals("running", experiment.path("actions").path("wait").path("state").path("status").asText());
        assertEquals("running", experiment.path("state").path("status").asText());
    }

    @Test
    void aRegionOrAccountWithoutAnInjectApiExperimentKeepsNothing() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertTrue(service.activeApiFaults("xx-test-" + i).isEmpty());
        }
        assertEquals(0, service.keptApiFaultKeys());

        start(createTemplate(actions("api",
                action(THROTTLE, "ec2", "DescribeInstances", "100", "PT1M", null))));
        assertEquals(1, service.activeApiFaults(REGION).size());
        clock.advance(Duration.ofMinutes(2));

        assertTrue(service.activeApiFaults(REGION).isEmpty());
        assertEquals(0, service.keptApiFaultKeys());
    }

    @Test
    void aRunningExperimentIsFoundAgainAfterARestart() throws Exception {
        start(createTemplate(actions("api",
                action(THROTTLE, "ec2", "DescribeInstances", "100", "PT10M", null))));

        RegionResolver resolver = new RegionResolver(REGION, ACCOUNT_ID);
        FisService restarted = new FisService(stores, mapper, resolver, new FisCatalog(mapper, resolver),
                new Random(), () -> false, clock);

        assertEquals(1, restarted.activeApiFaults(REGION).size());
    }

    @Test
    void aStoredTemplateWhoseDurationWasNeverCheckedIsRefusedAtStart() throws Exception {
        String templateId = createTemplate(actions("api",
                action(THROTTLE, "ec2", "DescribeInstances", "100", "PT1M", null)));
        String key = REGION + "::template::" + templateId;
        ExperimentTemplate stored = stores.templates.get(key).orElseThrow();
        stored.getActions().get("api").getParameters().put("duration", "forever");
        stores.templates.put(key, stored);

        AwsException refused = assertThrows(AwsException.class, () -> start(templateId));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("one minute to 12 hours"), refused.getMessage());
    }

    @Test
    void stoppingTheExperimentEndsItsFaults() throws Exception {
        String id = start(createTemplate(actions("api",
                action(THROTTLE, "ec2", "DescribeInstances", "100", "PT10M", null))));
        assertEquals(1, service.activeApiFaults(REGION).size());

        service.stopExperiment(REGION, id);

        assertTrue(service.activeApiFaults(REGION).isEmpty());
    }

    private FisService service(boolean anyService) {
        stores = FisStores.inMemory();
        RegionResolver resolver = new RegionResolver(REGION, ACCOUNT_ID);
        return new FisService(stores, mapper, resolver, new FisCatalog(mapper, resolver), new Random(),
                () -> anyService, clock);
    }

    private static String action(String actionId, String service, String operations, String percentage,
                                 String duration, String startAfter) {
        return """
                {"actionId":"%s","targets":{"Roles":"roles"},%s
                 "parameters":{"service":"%s","operations":"%s","percentage":"%s","duration":"%s"}}
                """.formatted(actionId, startAfter == null ? "" : "\"startAfter\":[\"" + startAfter + "\"],",
                service, operations, percentage, duration);
    }

    private static String actions(String... namesAndActions) {
        StringBuilder actions = new StringBuilder("{");
        for (int i = 0; i < namesAndActions.length; i += 2) {
            actions.append(i == 0 ? "" : ",").append('"').append(namesAndActions[i]).append("\":")
                    .append(namesAndActions[i + 1]);
        }
        return actions.append('}').toString();
    }

    private String createTemplate(String actions) throws Exception {
        ObjectNode template = (ObjectNode) mapper.readTree("""
                {
                  "clientToken":"%s",
                  "description":"inject-api template",
                  "roleArn":"%s",
                  "stopConditions":[{"source":"none"}],
                  "targets":{"roles":{"resourceType":"aws:iam:role","resourceArns":["%s"],"selectionMode":"ALL"}},
                  "actions":%s
                }
                """.formatted(UUID.randomUUID(), ROLE_ARN, TARGET_ROLE, actions));
        return service.createExperimentTemplate(REGION, template).path("experimentTemplate").path("id").asText();
    }

    private String start(String templateId) throws Exception {
        return service.startExperiment(REGION, mapper.readTree("""
                {"clientToken":"%s","experimentTemplateId":"%s"}
                """.formatted(UUID.randomUUID(), templateId))).path("experiment").path("id").asText();
    }

    private JsonNode experiment(String id) {
        return service.getExperiment(REGION, id).path("experiment");
    }

    /** A clock that stands still until the test moves it on. */
    private static final class SteppedClock extends Clock {

        private Instant instant = Instant.parse("2026-11-17T12:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
