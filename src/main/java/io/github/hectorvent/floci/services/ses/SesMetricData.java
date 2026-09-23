package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.EmailInsights;
import io.github.hectorvent.floci.services.ses.model.InsightsBounce;
import io.github.hectorvent.floci.services.ses.model.InsightsEvent;
import io.github.hectorvent.floci.services.ses.model.InsightsEventDetails;
import io.github.hectorvent.floci.services.ses.model.SentEmail;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Aggregates the stored per-recipient timelines into the daily counts {@code BatchGetMetricData}
 * serves. The timelines are the ones {@link SesMessageInsights} derives at send time, so the two
 * operations cannot disagree about what a send produced.
 *
 * <p>Probe-confirmed against real SES (2026-09-23, us-east-1): buckets are whole UTC days, the
 * series is sparse rather than zero filled (a day with no data is simply absent), and the end of
 * the requested range is exclusive, so a range ending at midnight omits that day.
 *
 * <p>Floci reports only what a send without a real recipient can produce. OPEN, CLICK,
 * DELIVERY_OPEN and DELIVERY_CLICK need a mailbox to act, so they always come back empty; the
 * probed account returned empty series for those metrics too. Every bounce Floci derives is
 * PERMANENT, so TRANSIENT_BOUNCE is likewise always empty.
 */
final class SesMetricData {

    static final Set<String> METRICS = Set.of(
            "SEND", "COMPLAINT", "PERMANENT_BOUNCE", "TRANSIENT_BOUNCE", "OPEN", "CLICK",
            "DELIVERY", "DELIVERY_OPEN", "DELIVERY_CLICK", "DELIVERY_COMPLAINT");

    static final Set<String> DIMENSION_NAMES =
            Set.of("EMAIL_IDENTITY", "CONFIGURATION_SET", "ISP", "TENANT_NAME");

    private static final String EMAIL_IDENTITY = "EMAIL_IDENTITY";
    private static final String CONFIGURATION_SET = "CONFIGURATION_SET";
    private static final String ISP = "ISP";
    private static final String TENANT_NAME = "TENANT_NAME";

    private SesMetricData() {}

    /**
     * One query from the batch, already validated by the controller.
     */
    record Query(String id, String metric, Map<String, String> dimensions,
                 Instant startDate, Instant endDate) {

        Query {
            dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
        }
    }

    /**
     * The counts for one query, in ascending bucket order. Both lists have the same length, and
     * both are empty when nothing matched.
     */
    record Series(List<Instant> timestamps, List<Long> values) {

        static final Series EMPTY = new Series(List.of(), List.of());
    }

    static Series aggregate(List<SentEmail> emails, Query query) {
        Map<Instant, Long> buckets = new TreeMap<>();
        for (SentEmail email : emails) {
            if (!matchesMessageDimensions(email, query.dimensions())) {
                continue;
            }
            for (EmailInsights recipient : orEmpty(email.getInsights())) {
                if (!matchesIsp(recipient, query.dimensions())) {
                    continue;
                }
                countRecipient(recipient, query, buckets);
            }
        }
        if (buckets.isEmpty()) {
            return Series.EMPTY;
        }
        List<Instant> timestamps = new ArrayList<>(buckets.size());
        List<Long> values = new ArrayList<>(buckets.size());
        for (Map.Entry<Instant, Long> bucket : buckets.entrySet()) {
            timestamps.add(bucket.getKey());
            values.add(bucket.getValue());
        }
        return new Series(timestamps, values);
    }

    private static void countRecipient(EmailInsights recipient, Query query,
                                       Map<Instant, Long> buckets) {
        if ("DELIVERY_COMPLAINT".equals(query.metric())) {
            countDeliveredComplaint(recipient, query, buckets);
            return;
        }
        for (InsightsEvent event : orEmpty(recipient.events())) {
            if (counts(event, query.metric())) {
                add(buckets, event.timestamp(), query);
            }
        }
    }

    /**
     * DELIVERY_COMPLAINT is a complaint about a message that was delivered, so it counts the
     * recipient once, in the bucket the complaint itself falls in.
     */
    private static void countDeliveredComplaint(EmailInsights recipient, Query query,
                                                Map<Instant, Long> buckets) {
        boolean delivered = false;
        for (InsightsEvent event : orEmpty(recipient.events())) {
            if ("DELIVERY".equals(event.type())) {
                delivered = true;
            } else if (delivered && "COMPLAINT".equals(event.type())) {
                add(buckets, event.timestamp(), query);
                return;
            }
        }
    }

    private static boolean counts(InsightsEvent event, String metric) {
        String type = event.type();
        if (type == null) {
            return false;
        }
        return switch (metric) {
            case "SEND", "DELIVERY", "COMPLAINT" -> type.equals(metric);
            case "PERMANENT_BOUNCE" -> "BOUNCE".equals(type) && isBounceType(event, "PERMANENT");
            case "TRANSIENT_BOUNCE" -> "BOUNCE".equals(type) && isBounceType(event, "TRANSIENT");
            default -> false;
        };
    }

    private static boolean isBounceType(InsightsEvent event, String bounceType) {
        InsightsEventDetails details = event.details();
        if (details == null) {
            return false;
        }
        InsightsBounce bounce = details.bounce();
        return bounce != null && bounceType.equals(bounce.bounceType());
    }

    private static void add(Map<Instant, Long> buckets, Instant timestamp, Query query) {
        if (timestamp == null || timestamp.isBefore(query.startDate())
                || !timestamp.isBefore(query.endDate())) {
            return;
        }
        buckets.merge(timestamp.truncatedTo(ChronoUnit.DAYS), 1L, Long::sum);
    }

    private static boolean matchesMessageDimensions(SentEmail email, Map<String, String> dimensions) {
        String identity = dimensions.get(EMAIL_IDENTITY);
        if (identity != null && !matchesIdentity(email.getSource(), identity)) {
            return false;
        }
        String configurationSet = dimensions.get(CONFIGURATION_SET);
        if (configurationSet != null && !configurationSet.equals(email.getConfigurationSetName())) {
            return false;
        }
        String tenant = dimensions.get(TENANT_NAME);
        return tenant == null || tenant.equals(email.getTenantName());
    }

    /**
     * An identity dimension names either the sending address or its domain, the way the identity
     * itself can be either; the probe filtered a domain and matched sends from an address in it.
     */
    static boolean matchesIdentity(String source, String identity) {
        if (source == null) {
            return false;
        }
        // A stored Source can carry display-name syntax, so compare the bare mailbox.
        String address = SesService.extractEmailAddress(source);
        if (address.equalsIgnoreCase(identity)) {
            return true;
        }
        int at = address.lastIndexOf('@');
        return at >= 0 && address.substring(at + 1).equalsIgnoreCase(identity);
    }

    private static boolean matchesIsp(EmailInsights recipient, Map<String, String> dimensions) {
        String isp = dimensions.get(ISP);
        return isp == null || isp.equals(recipient.isp());
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
