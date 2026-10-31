package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.redshiftserverless.model.ScheduledAction;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RedshiftServerlessScheduleTest {
    private static final Instant NOW = Instant.parse("2030-01-07T02:00:00Z");

    @Test
    void cronComputesNextAndLatestInUtc() {
        ScheduledAction action = cron("(0 3 ? * MON *)");
        RedshiftServerlessSchedule.validate(action);
        assertEquals(NOW.plusSeconds(3600), RedshiftServerlessSchedule.next(action, NOW).orElseThrow());
        assertEquals(NOW.plusSeconds(3600),
                RedshiftServerlessSchedule.latestDue(action, NOW, NOW.plusSeconds(7200)).orElseThrow());
        assertTrue(RedshiftServerlessSchedule.latestDue(action, NOW, NOW).isEmpty());
    }

    @Test
    void numericWeekdayUsesAwsNumbering() {
        assertEquals(NOW.plusSeconds(3600),
                RedshiftServerlessSchedule.next(cron("0 3 ? * 2 *"), NOW).orElseThrow());
    }

    @Test
    void supportsExistingCronWrapper() {
        assertEquals(NOW.plusSeconds(3600),
                RedshiftServerlessSchedule.next(cron("cron(0 3 ? * MON *)"), NOW).orElseThrow());
    }

    @Test
    void rejectsInvalidAndSubHourlyCron() {
        for (String expression : new String[]{"x", "* * *", "0 3 * * * *", "0,30 * * * ? *"}) {
            assertThrows(AwsException.class, () -> RedshiftServerlessSchedule.validate(cron(expression)), expression);
        }
    }

    @Test
    void activeWindowBoundsNextAndDue() {
        ScheduledAction action = cron("0 * * * ? *");
        action.setStartTime(NOW.plusSeconds(7200));
        action.setEndTime(NOW.plusSeconds(10800));
        assertEquals(action.getStartTime(), RedshiftServerlessSchedule.next(action, NOW).orElseThrow());
        assertEquals(action.getEndTime(),
                RedshiftServerlessSchedule.latestDue(action, NOW, NOW.plusSeconds(14400)).orElseThrow());
        assertTrue(RedshiftServerlessSchedule.next(action, action.getEndTime()).isEmpty());
        action.setEnabled(false);
        assertTrue(RedshiftServerlessSchedule.next(action, NOW).isEmpty());
        assertTrue(RedshiftServerlessSchedule.latestDue(action, NOW, NOW.plusSeconds(14400)).isEmpty());
    }

    @Test
    void oneOffIsDueOnlyOnceAndWithinWindow() {
        ScheduledAction action = new ScheduledAction();
        action.setScheduleAt(NOW.plusSeconds(10));
        RedshiftServerlessSchedule.validate(action);
        assertEquals(action.getScheduleAt(), RedshiftServerlessSchedule.next(action, NOW).orElseThrow());
        assertEquals(action.getScheduleAt(),
                RedshiftServerlessSchedule.latestDue(action, NOW, NOW.plusSeconds(10)).orElseThrow());
        assertTrue(RedshiftServerlessSchedule.latestDue(action, action.getScheduleAt(), NOW.plusSeconds(20)).isEmpty());
        action.setEndTime(NOW.plusSeconds(9));
        assertTrue(RedshiftServerlessSchedule.next(action, NOW).isEmpty());
    }

    private ScheduledAction cron(String expression) {
        ScheduledAction action = new ScheduledAction();
        action.setScheduleCron(expression);
        return action;
    }
}
