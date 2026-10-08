package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.quarkus.arc.Arc;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @InjectMock
    RedshiftDataService redshiftDataService;

    @Test
    void executeStatementRunsAsTheSchedulesAccount() {
        AtomicReference<String> seenAccount = new AtomicReference<>();
        when(redshiftDataService.executeStatement(any(JsonNode.class), anyString())).thenAnswer(invocation -> {
            seenAccount.set(Arc.container().instance(RequestContext.class).get().getAccountId());
            return JsonNodeFactory.instance.objectNode();
        });

        scheduleInvoker.invoke(scheduleFor("arn:aws:scheduler:::aws-sdk:redshiftdata:executeStatement",
                "{\"ClusterIdentifier\":\"analytics\",\"Database\":\"dev\",\"Sql\":\"select 1\"}"),
                Instant.parse("2026-04-21T09:17:54Z"));

        assertEquals(SCHEDULE_ACCOUNT, seenAccount.get());
    }

    @Test
    void batchExecuteStatementRunsAsTheSchedulesAccount() {
        AtomicReference<String> seenAccount = new AtomicReference<>();
        when(redshiftDataService.batchExecuteStatement(any(JsonNode.class), anyString())).thenAnswer(invocation -> {
            seenAccount.set(Arc.container().instance(RequestContext.class).get().getAccountId());
            return JsonNodeFactory.instance.objectNode();
        });

        scheduleInvoker.invoke(scheduleFor("arn:aws:scheduler:::aws-sdk:redshiftdata:batchExecuteStatement",
                "{\"ClusterIdentifier\":\"analytics\",\"Database\":\"dev\",\"Sqls\":[\"select 1\"]}"),
                Instant.parse("2026-04-21T09:17:54Z"));

        assertEquals(SCHEDULE_ACCOUNT, seenAccount.get());
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
