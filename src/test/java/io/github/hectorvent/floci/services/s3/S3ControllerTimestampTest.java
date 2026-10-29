package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;

import java.time.Instant;
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
}
