package io.floci.aws.services.eks;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * When each Kubernetes version Floci serves leaves Amazon EKS standard support, from the Amazon EKS Kubernetes release
 * calendar, whose dates are UTC. 1.28 to 1.30 have left the calendar since; their dates are the ones it gave them.
 */
final class EksVersionCalendar {

    private static final Map<String, LocalDate> END_OF_STANDARD_SUPPORT = Map.of(
            "1.28", LocalDate.of(2024, 11, 26),
            "1.29", LocalDate.of(2025, 3, 23),
            "1.30", LocalDate.of(2025, 7, 23),
            "1.31", LocalDate.of(2025, 11, 26),
            "1.32", LocalDate.of(2026, 3, 23),
            "1.33", LocalDate.of(2026, 7, 29),
            "1.34", LocalDate.of(2026, 12, 2),
            "1.35", LocalDate.of(2027, 3, 27),
            "1.36", LocalDate.of(2027, 8, 2),
            "1.37", LocalDate.of(2027, 12, 1));

    private EksVersionCalendar() {
    }

    /** Whether {@code version} is in standard support at {@code now}. A version the calendar does not list yet is. */
    static boolean inStandardSupport(String version, Instant now) {
        LocalDate end = END_OF_STANDARD_SUPPORT.get(version);
        return end == null || now.isBefore(end.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
