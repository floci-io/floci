package io.floci.aws.core.common;

import com.cronutils.model.time.ExecutionTime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsCronScheduleTest {
    private static final Instant NOON = Instant.parse("2026-11-20T12:00:00Z");

    private static ExecutionTime parse(String fields) {
        return AwsCronSchedule.parse(fields.split("\s+"), IllegalArgumentException::new);
    }

    @Test
    void nextAndLatestAreStrictlyAfterAndAtOrBefore() {
        ExecutionTime hourly = parse("0 * * * ? *");

        assertEquals(Optional.of(Instant.parse("2026-11-20T13:00:00Z")), AwsCronSchedule.next(hourly, NOON));
        assertEquals(Optional.of(NOON), AwsCronSchedule.latest(hourly, NOON));
        assertEquals(Optional.of(NOON), AwsCronSchedule.latest(hourly, NOON.plusSeconds(1800)));
    }

    @Test
    void aBadExpressionRaisesTheCallersException() {
        assertEquals("The cron schedule needs six fields.",
                assertThrows(IllegalStateException.class,
                        () -> AwsCronSchedule.parse(new String[] {"0", "*"}, IllegalStateException::new)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> parse("0 * * * * *"));
        assertThrows(IllegalArgumentException.class, () -> parse("0 * ? * ? *"));
        assertThrows(IllegalArgumentException.class, () -> parse("99 * * * ? *"));
    }

    @Test
    void spacingUnderAnHourIsRefusedAndAnHourIsAccepted() {
        AwsCronSchedule.requireHourlySpacing(parse("0 * * * ? *"), IllegalArgumentException::new);

        assertThrows(IllegalArgumentException.class,
                () -> AwsCronSchedule.requireHourlySpacing(parse("0/30 * * * ? *"), IllegalArgumentException::new));
    }

    @Test
    void aWindowMovesTheSearchAndKeepsItsEndsInclusive() {
        Instant start = NOON.plusSeconds(3600);
        Instant end = NOON.plusSeconds(7200);

        assertEquals(start.minusNanos(1), AwsCronSchedule.searchFrom(NOON, start));
        assertEquals(NOON, AwsCronSchedule.searchFrom(NOON, null));
        assertEquals(end, AwsCronSchedule.searchUpTo(NOON.plusSeconds(9000), end));
        assertEquals(NOON, AwsCronSchedule.searchUpTo(NOON, end));
        assertTrue(AwsCronSchedule.inWindow(start, start, end));
        assertTrue(AwsCronSchedule.inWindow(end, start, end));
        assertFalse(AwsCronSchedule.inWindow(end.plusSeconds(1), start, end));
        assertTrue(AwsCronSchedule.inWindow(NOON, null, null));
    }
}
