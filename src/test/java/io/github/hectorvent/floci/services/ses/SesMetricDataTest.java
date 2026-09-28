package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.EmailInsights;
import io.github.hectorvent.floci.services.ses.model.InsightsBounce;
import io.github.hectorvent.floci.services.ses.model.InsightsEvent;
import io.github.hectorvent.floci.services.ses.model.InsightsEventDetails;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daily aggregation behind BatchGetMetricData. The bucketing rules asserted here are
 * probe-confirmed: whole UTC days, a sparse series, and an exclusive end of range.
 */
class SesMetricDataTest {

    private static final Instant DAY_ONE = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant DAY_TWO = DAY_ONE.plus(1, ChronoUnit.DAYS);
    private static final Instant DAY_FOUR = DAY_ONE.plus(3, ChronoUnit.DAYS);

    private static SentEmail email(Instant sentAt, String source, String configurationSet,
                                   EmailInsights... recipients) {
        return emailForTenant(sentAt, source, configurationSet, null, recipients);
    }

    private static SentEmail emailForTenant(Instant sentAt, String source, String configurationSet,
                                            String tenant, EmailInsights... recipients) {
        SentEmail email = new SentEmail();
        email.setSource(source);
        email.setConfigurationSetName(configurationSet);
        email.setTenantName(tenant);
        email.setSentAt(sentAt);
        email.setInsights(List.of(recipients));
        return email;
    }

    private static EmailInsights recipient(Instant at, String destination, String... types) {
        List<InsightsEvent> events = new ArrayList<>();
        for (String type : types) {
            events.add(InsightsEvent.of(at, type));
        }
        return new EmailInsights(destination, "UNKNOWN_ISP", events);
    }

    private static EmailInsights bounced(Instant at, String destination, String bounceType) {
        return new EmailInsights(destination, "UNKNOWN_ISP", List.of(
                InsightsEvent.of(at, "SEND"),
                new InsightsEvent(at, "BOUNCE", new InsightsEventDetails(
                        new InsightsBounce(bounceType, "General", null), null))));
    }

    private static SesMetricData.Query query(String metric, Instant start, Instant end) {
        return new SesMetricData.Query("q", metric, Map.of(), start, end);
    }

    @Test
    void countsOneBucketPerUtcDay() {
        // Two sends four hours apart on the same day land in one bucket at that day's midnight.
        List<SentEmail> emails = List.of(
                email(DAY_ONE.plus(1, ChronoUnit.HOURS), "sender@example.com", null,
                        recipient(DAY_ONE.plus(1, ChronoUnit.HOURS), "a@example.com", "SEND")),
                email(DAY_ONE.plus(5, ChronoUnit.HOURS), "sender@example.com", null,
                        recipient(DAY_ONE.plus(5, ChronoUnit.HOURS), "b@example.com", "SEND")));

        SesMetricData.Series series = SesMetricData.aggregate(emails,
                query("SEND", DAY_ONE, DAY_ONE.plus(7, ChronoUnit.DAYS)));

        assertEquals(List.of(DAY_ONE), series.timestamps());
        assertEquals(List.of(2L), series.values());
    }

    @Test
    void skipsDaysWithoutData() {
        List<SentEmail> emails = List.of(
                email(DAY_ONE, "sender@example.com", null, recipient(DAY_ONE, "a@example.com", "SEND")),
                email(DAY_FOUR, "sender@example.com", null, recipient(DAY_FOUR, "b@example.com", "SEND")));

        SesMetricData.Series series = SesMetricData.aggregate(emails,
                query("SEND", DAY_ONE, DAY_ONE.plus(7, ChronoUnit.DAYS)));

        // Sparse, not zero filled: the two quiet days in between are absent entirely.
        assertEquals(List.of(DAY_ONE, DAY_FOUR), series.timestamps());
        assertEquals(List.of(1L, 1L), series.values());
    }

    @Test
    void endOfRangeIsExclusive() {
        List<SentEmail> emails = List.of(
                email(DAY_ONE, "sender@example.com", null, recipient(DAY_ONE, "a@example.com", "SEND")),
                email(DAY_TWO, "sender@example.com", null, recipient(DAY_TWO, "b@example.com", "SEND")));

        SesMetricData.Series series = SesMetricData.aggregate(emails, query("SEND", DAY_ONE, DAY_TWO));

        assertEquals(List.of(DAY_ONE), series.timestamps());
    }

    @Test
    void countsOnePerRecipientNotPerMessage() {
        List<SentEmail> emails = List.of(email(DAY_ONE, "sender@example.com", null,
                recipient(DAY_ONE, "a@example.com", "SEND"),
                recipient(DAY_ONE, "b@example.com", "SEND"),
                recipient(DAY_ONE, "c@example.com", "SEND")));

        SesMetricData.Series series = SesMetricData.aggregate(emails,
                query("SEND", DAY_ONE, DAY_TWO));

        assertEquals(List.of(3L), series.values());
    }

    @Test
    void separatesPermanentFromTransientBounces() {
        List<SentEmail> emails = List.of(email(DAY_ONE, "sender@example.com", null,
                bounced(DAY_ONE, "a@example.com", "PERMANENT"),
                bounced(DAY_ONE, "b@example.com", "TRANSIENT")));

        assertEquals(List.of(1L), SesMetricData.aggregate(emails,
                query("PERMANENT_BOUNCE", DAY_ONE, DAY_TWO)).values());
        assertEquals(List.of(1L), SesMetricData.aggregate(emails,
                query("TRANSIENT_BOUNCE", DAY_ONE, DAY_TWO)).values());
    }

    @Test
    void deliveryComplaintCountsADeliveredRecipientThatThenComplained() {
        List<SentEmail> emails = List.of(email(DAY_ONE, "sender@example.com", null,
                recipient(DAY_ONE, "a@example.com", "SEND", "DELIVERY", "COMPLAINT"),
                // Complained without a delivery, so it is a complaint but not a delivered one.
                recipient(DAY_ONE, "b@example.com", "SEND", "COMPLAINT")));

        assertEquals(List.of(2L), SesMetricData.aggregate(emails,
                query("COMPLAINT", DAY_ONE, DAY_TWO)).values());
        assertEquals(List.of(1L), SesMetricData.aggregate(emails,
                query("DELIVERY_COMPLAINT", DAY_ONE, DAY_TWO)).values());
    }

    @Test
    void metricsThatNeedARealRecipientStayEmpty() {
        List<SentEmail> emails = List.of(email(DAY_ONE, "sender@example.com", null,
                recipient(DAY_ONE, "a@example.com", "SEND", "DELIVERY")));

        for (String metric : List.of("OPEN", "CLICK", "DELIVERY_OPEN", "DELIVERY_CLICK")) {
            SesMetricData.Series series = SesMetricData.aggregate(emails,
                    query(metric, DAY_ONE, DAY_TWO));
            assertTrue(series.timestamps().isEmpty(), metric + " should report nothing");
            assertTrue(series.values().isEmpty(), metric + " should report nothing");
        }
    }

    @Test
    void identityDimensionMatchesTheAddressOrItsDomain() {
        List<SentEmail> emails = List.of(email(DAY_ONE, "sender@example.com", null,
                recipient(DAY_ONE, "a@example.com", "SEND")));

        for (String identity : List.of("sender@example.com", "example.com", "EXAMPLE.COM")) {
            assertEquals(List.of(1L), SesMetricData.aggregate(emails,
                    new SesMetricData.Query("q", "SEND", Map.of("EMAIL_IDENTITY", identity),
                            DAY_ONE, DAY_TWO)).values(), identity);
        }
        assertTrue(SesMetricData.aggregate(emails,
                new SesMetricData.Query("q", "SEND", Map.of("EMAIL_IDENTITY", "other.example.com"),
                        DAY_ONE, DAY_TWO)).values().isEmpty());
    }

    @Test
    void identityDimensionMatchesASourceCarryingADisplayName() {
        // The send path stores Source verbatim, so it can carry "Name <addr>".
        List<SentEmail> emails = List.of(email(DAY_ONE, "Sender Name <sender@example.com>", null,
                recipient(DAY_ONE, "a@example.com", "SEND")));

        for (String identity : List.of("sender@example.com", "example.com")) {
            assertEquals(List.of(1L), SesMetricData.aggregate(emails,
                    new SesMetricData.Query("q", "SEND", Map.of("EMAIL_IDENTITY", identity),
                            DAY_ONE, DAY_TWO)).values(), identity);
        }
    }

    @Test
    void configurationSetDimensionFiltersOnTheResolvedSet() {
        List<SentEmail> emails = List.of(
                email(DAY_ONE, "sender@example.com", "tracked",
                        recipient(DAY_ONE, "a@example.com", "SEND")),
                email(DAY_ONE, "sender@example.com", null,
                        recipient(DAY_ONE, "b@example.com", "SEND")));

        assertEquals(List.of(1L), SesMetricData.aggregate(emails,
                new SesMetricData.Query("q", "SEND", Map.of("CONFIGURATION_SET", "tracked"),
                        DAY_ONE, DAY_TWO)).values());
        assertTrue(SesMetricData.aggregate(emails,
                new SesMetricData.Query("q", "SEND", Map.of("CONFIGURATION_SET", "absent"),
                        DAY_ONE, DAY_TWO)).values().isEmpty());
    }

    @Test
    void tenantDimensionFiltersOnTheTenantTheSendNamed() {
        // AWS accepts TENANT_NAME as a fourth dimension; the pinned SDK model still lists three.
        List<SentEmail> emails = List.of(
                emailForTenant(DAY_ONE, "sender@example.com", null, "blue",
                        recipient(DAY_ONE, "a@example.com", "SEND")),
                emailForTenant(DAY_ONE, "sender@example.com", null, null,
                        recipient(DAY_ONE, "b@example.com", "SEND")));

        assertEquals(List.of(1L), SesMetricData.aggregate(emails,
                new SesMetricData.Query("q", "SEND", Map.of("TENANT_NAME", "blue"),
                        DAY_ONE, DAY_TWO)).values());
        assertTrue(SesMetricData.aggregate(emails,
                new SesMetricData.Query("q", "SEND", Map.of("TENANT_NAME", "green"),
                        DAY_ONE, DAY_TWO)).values().isEmpty());
    }

    @Test
    void ispDimensionMatchesOnlyTheValueFlociReports() {
        List<SentEmail> emails = List.of(email(DAY_ONE, "sender@example.com", null,
                recipient(DAY_ONE, "a@example.com", "SEND")));

        assertEquals(List.of(1L), SesMetricData.aggregate(emails,
                new SesMetricData.Query("q", "SEND", Map.of("ISP", "UNKNOWN_ISP"),
                        DAY_ONE, DAY_TWO)).values());
        assertTrue(SesMetricData.aggregate(emails,
                new SesMetricData.Query("q", "SEND", Map.of("ISP", "gmail.com"),
                        DAY_ONE, DAY_TWO)).values().isEmpty());
    }

    @Test
    void severalDimensionsAllHaveToMatch() {
        List<SentEmail> emails = List.of(email(DAY_ONE, "sender@example.com", "tracked",
                recipient(DAY_ONE, "a@example.com", "SEND")));

        assertEquals(List.of(1L), SesMetricData.aggregate(emails,
                new SesMetricData.Query("q", "SEND",
                        Map.of("EMAIL_IDENTITY", "example.com", "CONFIGURATION_SET", "tracked",
                                "ISP", "UNKNOWN_ISP"), DAY_ONE, DAY_TWO)).values());
        assertTrue(SesMetricData.aggregate(emails,
                new SesMetricData.Query("q", "SEND",
                        Map.of("EMAIL_IDENTITY", "example.com", "CONFIGURATION_SET", "other"),
                        DAY_ONE, DAY_TWO)).values().isEmpty());
    }
}
