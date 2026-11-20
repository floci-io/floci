package io.floci.aws.services.redshift;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.model.ScheduledAction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftScheduledActionScheduleTest {

    private static ScheduledAction action(String schedule) {
        ScheduledAction action = new ScheduledAction();
        action.setSchedule(schedule);
        return action;
    }

    @Test
    void atScheduleFiresOnceAtItsUtcTimestamp() {
        ScheduledAction action = action("at(2026-11-20T10:00:00)");

        assertEquals(Optional.of(Instant.parse("2026-11-20T10:00:00Z")),
                RedshiftScheduledActionSchedule.next(action, Instant.parse("2026-11-19T00:00:00Z")));
        assertTrue(RedshiftScheduledActionSchedule.next(action, Instant.parse("2026-11-20T10:00:00Z")).isEmpty());
    }

    @Test
    void cronScheduleListsTheNextInvocationsInUtc() {
        ScheduledAction action = action("cron(0 18 * * ? *)");

        assertEquals(List.of(Instant.parse("2026-11-20T18:00:00Z"), Instant.parse("2026-11-21T18:00:00Z")),
                RedshiftScheduledActionSchedule.upcoming(action, Instant.parse("2026-11-20T09:00:00Z"), 2));
    }

    @Test
    void windowAndDisabledStateLimitInvocations() {
        ScheduledAction action = action("cron(0 18 * * ? *)");
        action.setStartTime(Instant.parse("2026-11-22T00:00:00Z"));
        action.setEndTime(Instant.parse("2026-11-23T20:00:00Z"));

        assertEquals(List.of(Instant.parse("2026-11-22T18:00:00Z"), Instant.parse("2026-11-23T18:00:00Z")),
                RedshiftScheduledActionSchedule.upcoming(action, Instant.parse("2026-11-20T09:00:00Z"), 5));

        action.setEnabled(false);
        assertTrue(RedshiftScheduledActionSchedule.upcoming(action, Instant.parse("2026-11-20T09:00:00Z"), 5).isEmpty());
    }

    @Test
    void latestDueCoalescesMissedInvocations() {
        ScheduledAction action = action("cron(0 18 * * ? *)");

        assertEquals(Optional.of(Instant.parse("2026-11-22T18:00:00Z")),
                RedshiftScheduledActionSchedule.latestDue(action,
                        Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-11-22T19:00:00Z")));
        assertTrue(RedshiftScheduledActionSchedule.latestDue(action,
                Instant.parse("2026-11-22T18:00:00Z"), Instant.parse("2026-11-22T19:00:00Z")).isEmpty());
    }

    @Test
    void malformedSchedulesAreRejectedAsInvalidSchedule() {
        for (String schedule : List.of("rate(1 hour)", "at(tomorrow)", "cron(0 18 * * *)",
                "cron(0 18 * * * *)", "cron(0/30 * * * ? *)", "at(2026-11-20T10:00:00", "")) {
            AwsException failure = assertThrows(AwsException.class,
                    () -> RedshiftScheduledActionSchedule.validate(schedule), schedule);
            assertEquals("InvalidSchedule", failure.getErrorCode(), schedule);
        }
    }
}
