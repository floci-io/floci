package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link TimeBucketing}, focused on the boundary-alignment
 * behavior AWS Cost Explorer guarantees: Start truncates down, End rounds up
 * to the next granularity boundary so every bucket spans a whole granularity
 * unit.
 */
class TimeBucketingTest {

    @Test
    void splitDaily_truncatesNonAlignedEndToNextDay() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-03T13:30:00Z");
        List<TimeBucketing.Bucket> buckets = TimeBucketing.split(start, end, TimeBucketing.Granularity.DAILY);
        assertThat(buckets, hasSize(3));
        assertThat(buckets.get(0).start(), equalTo(Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(buckets.get(2).end(), equalTo(Instant.parse("2026-01-04T00:00:00Z")));
    }

    @Test
    void splitHourly_truncatesNonAlignedBoundaries() {
        Instant start = Instant.parse("2026-01-01T00:30:00Z");
        Instant end = Instant.parse("2026-01-01T03:45:00Z");
        List<TimeBucketing.Bucket> buckets = TimeBucketing.split(start, end, TimeBucketing.Granularity.HOURLY);
        assertThat(buckets, hasSize(4));
        assertThat(buckets.get(0).start(), equalTo(Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(buckets.get(3).end(), equalTo(Instant.parse("2026-01-01T04:00:00Z")));
    }

    @Test
    void splitMonthly_anchorsToFirstOfMonth() {
        Instant start = Instant.parse("2026-01-15T00:00:00Z");
        Instant end = Instant.parse("2026-03-10T00:00:00Z");
        List<TimeBucketing.Bucket> buckets = TimeBucketing.split(start, end, TimeBucketing.Granularity.MONTHLY);
        assertThat(buckets, hasSize(3));
        assertThat(buckets.get(0).start(), equalTo(Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(buckets.get(2).end(), equalTo(Instant.parse("2026-04-01T00:00:00Z")));
    }

    @Test
    void splitDaily_alreadyAlignedEndDoesNotAddExtraDay() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-03T00:00:00Z");
        List<TimeBucketing.Bucket> buckets = TimeBucketing.split(start, end, TimeBucketing.Granularity.DAILY);
        assertThat(buckets, hasSize(2));
        assertThat(buckets.get(1).end(), equalTo(Instant.parse("2026-01-03T00:00:00Z")));
    }

    @Test
    void split_endNotAfterStart_throws() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        AwsException ex = assertThrows(AwsException.class,
                () -> TimeBucketing.split(t, t, TimeBucketing.Granularity.DAILY));
        assertThat(ex.getErrorCode(), equalTo("DataUnavailableException"));
    }

    @Test
    void parseGranularity_acceptsThreeValues() {
        assertThat(TimeBucketing.parseGranularity("DAILY"), equalTo(TimeBucketing.Granularity.DAILY));
        assertThat(TimeBucketing.parseGranularity("HOURLY"), equalTo(TimeBucketing.Granularity.HOURLY));
        assertThat(TimeBucketing.parseGranularity("MONTHLY"), equalTo(TimeBucketing.Granularity.MONTHLY));
    }

    @Test
    void parseGranularity_rejectsInvalid() {
        assertThrows(AwsException.class, () -> TimeBucketing.parseGranularity("YEARLY"));
        assertThrows(AwsException.class, () -> TimeBucketing.parseGranularity(""));
        assertThrows(AwsException.class, () -> TimeBucketing.parseGranularity(null));
    }
}
