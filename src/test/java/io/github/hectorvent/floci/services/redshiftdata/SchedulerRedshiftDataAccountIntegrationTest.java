package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSession;
import io.github.hectorvent.floci.services.scheduler.ScheduleInvoker;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The dispatcher fires a schedule on a thread with no request, so account-aware storage would
 * fall back to the default account unless the invoker runs the call as the schedule's account.
 */
@QuarkusTest
class SchedulerRedshiftDataAccountIntegrationTest {

    private static final String SCHEDULE_ACCOUNT = "111122223333";

    @Inject
    ScheduleInvoker scheduleInvoker;

    @Inject
    RedshiftDataService redshiftDataService;

    @InjectMock
    RedshiftDataResourceResolver resolver;

    @InjectMock
    RedshiftDataConnectionFactory connectionFactory;

    @BeforeEach
    void setUp() throws SQLException {
        redshiftDataService.clear();
        when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                "arn:aws:redshift:us-east-1:" + SCHEDULE_ACCOUNT + ":cluster:analytics",
                "127.0.0.1", 5439, "dev", "admin", "x",
                new SpectrumSession(SCHEDULE_ACCOUNT, SCHEDULE_ACCOUNT + ":analytics", "dev", List.of(), false)));
        when(connectionFactory.open(any())).thenThrow(new SQLException("connection refused"));
    }

    @Test
    void executeStatementRunsAsTheSchedulesAccount() {
        scheduleInvoker.invoke(scheduleFor("arn:aws:scheduler:::aws-sdk:redshiftdata:executeStatement",
                "{\"ClusterIdentifier\":\"analytics\",\"Database\":\"dev\",\"Sql\":\"select 1\"}"),
                Instant.parse("2026-04-21T09:17:54Z"));

        // Statement was stored under SCHEDULE_ACCOUNT
        JsonNode scheduledStatements = RequestScopes.callAs(SCHEDULE_ACCOUNT,
                () -> redshiftDataService.listStatements(JsonNodeFactory.instance.objectNode()));
        assertEquals(1, scheduledStatements.path("Statements").size());
        String statementId = scheduledStatements.path("Statements").path(0).path("Id").asText();

        JsonNode described = RequestScopes.callAs(SCHEDULE_ACCOUNT,
                () -> redshiftDataService.describeStatement(JsonNodeFactory.instance.objectNode().put("Id", statementId)));
        assertEquals(statementId, described.path("Id").asText());
        awaitFailed(statementId);

        // Statement is not visible under default account
        JsonNode defaultStatements = redshiftDataService.listStatements(JsonNodeFactory.instance.objectNode());
        assertEquals(0, defaultStatements.path("Statements").size());

        AwsException exception = assertThrows(AwsException.class,
                () -> redshiftDataService.describeStatement(JsonNodeFactory.instance.objectNode().put("Id", statementId)));
        assertEquals("ResourceNotFoundException", exception.getErrorCode());
    }

    @Test
    void batchExecuteStatementRunsAsTheSchedulesAccount() {
        scheduleInvoker.invoke(scheduleFor("arn:aws:scheduler:::aws-sdk:redshiftdata:batchExecuteStatement",
                "{\"ClusterIdentifier\":\"analytics\",\"Database\":\"dev\",\"Sqls\":[\"select 1\"]}"),
                Instant.parse("2026-04-21T09:17:54Z"));

        // Statement was stored under SCHEDULE_ACCOUNT
        JsonNode scheduledStatements = RequestScopes.callAs(SCHEDULE_ACCOUNT,
                () -> redshiftDataService.listStatements(JsonNodeFactory.instance.objectNode()));
        assertEquals(1, scheduledStatements.path("Statements").size());
        String statementId = scheduledStatements.path("Statements").path(0).path("Id").asText();

        JsonNode described = RequestScopes.callAs(SCHEDULE_ACCOUNT,
                () -> redshiftDataService.describeStatement(JsonNodeFactory.instance.objectNode().put("Id", statementId)));
        assertEquals(statementId, described.path("Id").asText());
        awaitFailed(statementId);

        // Statement is not visible under default account
        JsonNode defaultStatements = redshiftDataService.listStatements(JsonNodeFactory.instance.objectNode());
        assertEquals(0, defaultStatements.path("Statements").size());

        AwsException exception = assertThrows(AwsException.class,
                () -> redshiftDataService.describeStatement(JsonNodeFactory.instance.objectNode().put("Id", statementId)));
        assertEquals("ResourceNotFoundException", exception.getErrorCode());
    }

    // The SQL runs off the invoking thread. Waiting for its outcome shows it ran as the schedule's account
    // too, and keeps a late write from leaking into the next test.
    private void awaitFailed(String statementId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("FAILED",
                RequestScopes.callAs(SCHEDULE_ACCOUNT, () -> redshiftDataService.describeStatement(
                        JsonNodeFactory.instance.objectNode().put("Id", statementId))).path("Status").asText()));
    }

    private static Schedule scheduleFor(String targetArn, String input) {
        Target target = new Target();
        target.setArn(targetArn);
        target.setRoleArn("arn:aws:iam::" + SCHEDULE_ACCOUNT + ":role/scheduler-role");
        target.setInput(input);
        Schedule schedule = new Schedule();
        schedule.setName("rsdata-account");
        schedule.setGroupName("default");
        schedule.setAccountId(SCHEDULE_ACCOUNT);
        schedule.setArn("arn:aws:scheduler:us-east-1:" + SCHEDULE_ACCOUNT + ":schedule/default/rsdata-account");
        schedule.setTarget(target);
        return schedule;
    }
}
