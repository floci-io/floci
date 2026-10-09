package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs a one-time schedule whose universal target is the Redshift Data API against a real
 * PostgreSQL-backed cluster. Skipped when Docker is not available.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulerRedshiftDataDockerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER_ID = "it-sched-rsdata";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/scheduler-role";
    private static final String TARGET_ARN = "arn:aws:scheduler:::aws-sdk:redshiftdata:executeStatement";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SCHEDULE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Inject
    RedshiftService redshift;

    @Inject
    ScheduleInvoker scheduleInvoker;

    @Inject
    SchedulerService schedulerService;

    @Inject
    SqsService sqsService;

    @Inject
    EmulatorConfig config;

    private final List<String> scheduleNames = new ArrayList<>();
    private final List<ScheduleDispatcher> testDispatchers = new ArrayList<>();
    private boolean clusterCreated;

    @BeforeAll
    void createCluster() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for this test");
        RestAssuredJsonUtils.configureAwsContentTypes();
        redshift.createCluster(CLUSTER_ID, "dc2.large", "admin", "Secret123");
        clusterCreated = true;
    }

    @AfterAll
    void deleteCluster() {
        if (clusterCreated) {
            redshift.deleteCluster(CLUSTER_ID);
        }
    }

    @AfterEach
    void cleanUp() {
        for (String scheduleName : scheduleNames) {
            try {
                schedulerService.deleteSchedule(scheduleName, null, REGION);
            } catch (AwsException expected) {
                if (!"ResourceNotFoundException".equals(expected.getErrorCode())) {
                    throw expected;
                }
            }
        }
        for (ScheduleDispatcher dispatcher : testDispatchers) {
            dispatcher.onStop(null);
        }
    }

    @Test
    void oneTimeScheduleRunsSqlOnAProvisionedCluster() {
        String statementName = "sched-" + UUID.randomUUID().toString().substring(0, 8);
        String scheduleName = "rsdata-at-" + UUID.randomUUID().toString().substring(0, 8);
        String input = MAPPER.createObjectNode()
                .put("ClusterIdentifier", CLUSTER_ID)
                .put("Database", "dev")
                .put("DbUser", "admin")
                .put("StatementName", statementName)
                .put("Sql", "select 1 as one")
                .toString();
        Instant fireAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        createSchedule(scheduleName, "at(" + SCHEDULE_TIME.format(fireAt) + ")", input);

        dispatcherFor(scheduleName).tick(fireAt);

        String statementId = RestAssuredJsonUtils.awsAction("RedshiftData", "ListStatements",
                        "{\"StatementName\":\"" + statementName + "\"}")
                .then().statusCode(200).extract().path("Statements[0].Id");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals("FINISHED", RestAssuredJsonUtils.awsAction("RedshiftData", "DescribeStatement",
                                "{\"Id\":\"" + statementId + "\"}")
                        .then().statusCode(200).extract().path("Status")));
    }

    private void createSchedule(String name, String expression, String input) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ScheduleExpression", expression);
        request.putObject("FlexibleTimeWindow").put("Mode", "OFF");
        request.put("State", "ENABLED");
        ObjectNode target = request.putObject("Target");
        target.put("Arn", TARGET_ARN);
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

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception ignored) {
            // No docker binary or daemon: the test is skipped through the assumption, not failed.
            return false;
        }
    }
}
