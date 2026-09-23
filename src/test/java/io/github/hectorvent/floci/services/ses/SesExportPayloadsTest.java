package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.EmailInsights;
import io.github.hectorvent.floci.services.ses.model.InsightsBounce;
import io.github.hectorvent.floci.services.ses.model.InsightsEvent;
import io.github.hectorvent.floci.services.ses.model.InsightsEventDetails;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The export payloads. Every column name, the quoting, the timestamp pattern and the trailing
 * comma are probe-confirmed against real SES, so this test pins the wire format rather than
 * Floci's preference.
 */
class SesExportPayloadsTest {

    private static final Instant SENT = Instant.parse("2026-09-21T01:02:03.123Z");

    private static final String HEADER =
            "\"messageid\",\"sendtimestamp\",\"isp\",\"fromaddress\",\"destination\",\"subject\","
                    + "\"last_delivery_event\",\"last_engagement_event\","
                    + "\"last_delivery_event_timestamp\",\"last_engagement_event_timestamp\","
                    + "\"bounce_sub_type\",\"diagnostic_code\",";

    private static SentEmail email(String subject, EmailInsights... recipients) {
        SentEmail email = new SentEmail();
        email.setMessageId("message-1");
        email.setSource("sender@example.com");
        email.setSubject(subject);
        email.setSentAt(SENT);
        email.setInsights(List.of(recipients));
        return email;
    }

    private static EmailInsights delivered(String destination) {
        return new EmailInsights(destination, "UNKNOWN_ISP", List.of(
                InsightsEvent.of(SENT, "SEND"), InsightsEvent.of(SENT, "DELIVERY")));
    }

    private static EmailInsights bounced(String destination) {
        return new EmailInsights(destination, "UNKNOWN_ISP", List.of(
                InsightsEvent.of(SENT, "SEND"),
                new InsightsEvent(SENT, "BOUNCE", new InsightsEventDetails(
                        new InsightsBounce("PERMANENT", "General", "smtp; 550 user unknown"), null))));
    }

    private static String csv(List<SentEmail> emails) {
        return new String(SesExportPayloads.insightsCsv(SesExportPayloads.insightsRows(emails)),
                StandardCharsets.UTF_8);
    }

    @Test
    void insightsCsvCarriesTheProbedHeaderIncludingItsTrailingComma() {
        String[] lines = csv(List.of(email("hello", delivered("user@example.com")))).split("\n");

        assertEquals(HEADER, lines[0]);
        assertEquals("\"message-1\",\"2026-09-21 01:02:03.123 UTC\",\"UNKNOWN_ISP\","
                + "\"sender@example.com\",\"user@example.com\",\"hello\",\"DELIVERY\",\"\","
                + "\"2026-09-21 01:02:03.123 UTC\",\"\",\"\",\"\",", lines[1]);
    }

    @Test
    void oneRowPerRecipientNotPerMessage() {
        String[] lines = csv(List.of(email("hello",
                delivered("a@example.com"), delivered("b@example.com")))).split("\n");

        assertEquals(3, lines.length);
    }

    @Test
    void aBounceReportsItsTypeAndCarriesTheDetails() {
        String[] lines = csv(List.of(email("hello", bounced("user@example.com")))).split("\n");

        assertTrue(lines[1].contains("\"PERMANENT_BOUNCE\""), lines[1]);
        assertTrue(lines[1].contains("\"General\""), lines[1]);
        assertTrue(lines[1].contains("\"smtp; 550 user unknown\""), lines[1]);
    }

    @Test
    void quotesInsideAValueAreDoubled() {
        String row = csv(List.of(email("say \"hi\"", delivered("user@example.com")))).split("\n")[1];

        assertTrue(row.contains("\"say \"\"hi\"\"\""), row);
    }

    @Test
    void insightsJsonIsOneArrayWithTheCommaLeadingEachRowAfterTheFirst() {
        String json = new String(SesExportPayloads.insightsJson(SesExportPayloads.insightsRows(
                List.of(email("hello", delivered("a@example.com"), delivered("b@example.com"))))),
                StandardCharsets.UTF_8);
        String[] lines = json.split("\n");

        assertEquals("[", lines[0]);
        assertTrue(lines[1].startsWith("{\"messageid\":\"message-1\""), lines[1]);
        assertTrue(lines[2].startsWith(",{"), lines[2]);
        assertEquals("]", lines[3]);
    }

    @Test
    void metricsCsvNamesTheDimensionThenEachMetricAndAggregation() {
        String csv = new String(SesExportPayloads.metricsCsv(List.of("ISP"),
                List.of("SEND_VOLUME", "DELIVERY_VOLUME"),
                List.of(new SesExportPayloads.MetricRow(List.of("UNKNOWN_ISP"), List.of(3.0, 2.0)))),
                StandardCharsets.UTF_8);

        assertEquals("ISP,SEND_VOLUME,DELIVERY_VOLUME\nUNKNOWN_ISP,3.0000,2.0000\n", csv);
    }

    @Test
    void metricsCsvQuotesOnlyTheValuesThatNeedIt() {
        // The probed file leaves values bare, but an unquoted comma would shift every later column.
        String csv = new String(SesExportPayloads.metricsCsv(List.of("EMAIL_IDENTITY"),
                List.of("SEND_VOLUME"),
                List.of(new SesExportPayloads.MetricRow(List.of("a,b@example.com"), List.of(1.0)))),
                StandardCharsets.UTF_8);

        assertEquals("EMAIL_IDENTITY,SEND_VOLUME\n\"a,b@example.com\",1.0000\n", csv);
    }

    @Test
    void metricsJsonKeysTheDimensionInLowerCaseAndNestsTheMetrics() {
        String json = new String(SesExportPayloads.metricsJson(List.of("ISP"),
                List.of("DELIVERY_RATE"),
                List.of(new SesExportPayloads.MetricRow(List.of("UNKNOWN_ISP"), List.of(0.5)))),
                StandardCharsets.UTF_8);

        assertEquals("[{\"isp\":\"UNKNOWN_ISP\",\"metrics\":{\"DELIVERY_RATE\":0.5000}}]\n", json);
    }
}
