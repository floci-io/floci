package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S3ControllerTimestampTest {

    @Test
    void isoSecondsMatchesFormatter() {
        List<Instant> instants = List.of(
                Instant.EPOCH,
                Instant.parse("2000-02-29T23:59:59Z"),
                Instant.parse("2026-10-29T10:15:14.999Z"),
                Instant.parse("1999-12-31T23:59:59.5Z"),
                Instant.parse("2038-01-19T03:14:07Z"),
                Instant.parse("0999-12-31T23:59:59Z"),
                Instant.parse("+10000-01-01T00:00:00Z"));
        for (Instant instant : instants) {
            assertEquals(S3Controller.ISO_FORMAT.format(instant), S3Controller.isoSeconds(instant));
        }
    }

    @Test
    void rfc822MatchesFormatter() {
        List<Instant> instants = new ArrayList<>(List.of(
                Instant.EPOCH,
                Instant.parse("1999-12-31T23:59:59Z"),
                Instant.parse("2000-02-29T00:00:00Z"),
                Instant.parse("2026-10-29T10:15:14.999Z"),
                Instant.parse("2038-01-19T03:14:07Z"),
                Instant.parse("2100-03-01T12:00:00Z"),
                Instant.parse("0999-12-31T23:59:59Z"),
                Instant.parse("+10000-01-01T00:00:00Z")));
        for (int day = 26; day <= 31; day++) {
            instants.add(Instant.parse("2026-10-" + day + "T05:06:07Z"));
        }
        instants.add(Instant.parse("2026-11-01T05:06:07Z"));
        for (Instant instant : instants) {
            assertEquals(S3Controller.RFC_822.format(instant), S3Controller.rfc822(instant));
        }
    }
}
