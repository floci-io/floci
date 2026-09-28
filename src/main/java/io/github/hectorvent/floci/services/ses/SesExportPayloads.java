package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.EmailInsights;
import io.github.hectorvent.floci.services.ses.model.InsightsBounce;
import io.github.hectorvent.floci.services.ses.model.InsightsEvent;
import io.github.hectorvent.floci.services.ses.model.InsightsEventDetails;
import io.github.hectorvent.floci.services.ses.model.SentEmail;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the two export payloads AWS produces, from the same stored timelines
 * {@link SesMessageInsights} derives and {@link SesMetricData} aggregates.
 *
 * <p>Probe-confirmed against real SES (2026-09-23, us-east-1): the column names and their order,
 * the quoting, the {@code yyyy-MM-dd HH:mm:ss.SSS UTC} timestamps, the trailing comma that leaves
 * a thirteenth unnamed column on the message-insights CSV, the leading-comma JSON array, and the
 * four-decimal metric values.
 */
final class SesExportPayloads {

    private static final DateTimeFormatter EXPORT_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final List<String> INSIGHTS_COLUMNS = List.of(
            "messageid", "sendtimestamp", "isp", "fromaddress", "destination", "subject",
            "last_delivery_event", "last_engagement_event", "last_delivery_event_timestamp",
            "last_engagement_event_timestamp", "bounce_sub_type", "diagnostic_code");

    private SesExportPayloads() {}

    /**
     * One exported row: an envelope recipient of one stored message. The tenant sits beside the
     * columns rather than among them, because {@code TenantName} is a filter member with no
     * column of its own in the probed file.
     */
    record InsightsRow(Map<String, String> columns, String tenantName) {}

    static List<InsightsRow> insightsRows(List<SentEmail> emails) {
        List<InsightsRow> rows = new ArrayList<>();
        for (SentEmail email : emails) {
            for (EmailInsights recipient : orEmpty(email.getInsights())) {
                rows.add(insightsRow(email, recipient));
            }
        }
        return rows;
    }

    private static InsightsRow insightsRow(SentEmail email, EmailInsights recipient) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("messageid", email.getMessageId());
        columns.put("sendtimestamp", format(email.getSentAt()));
        columns.put("isp", recipient.isp());
        columns.put("fromaddress", email.getSource());
        columns.put("destination", recipient.destination());
        columns.put("subject", email.getSubject());

        InsightsEvent last = lastDeliveryEvent(recipient);
        columns.put("last_delivery_event", last == null ? null : deliveryEventName(last));
        columns.put("last_engagement_event", null);
        columns.put("last_delivery_event_timestamp", last == null ? null : format(last.timestamp()));
        columns.put("last_engagement_event_timestamp", null);
        InsightsBounce bounce = bounceOf(last);
        columns.put("bounce_sub_type", bounce == null ? null : bounce.bounceSubType());
        columns.put("diagnostic_code", bounce == null ? null : bounce.diagnosticCode());
        return new InsightsRow(columns, email.getTenantName());
    }

    /**
     * The delivery-side enum splits a bounce by type, where the stored timeline carries one
     * {@code BOUNCE} event plus its details. Floci only ever derives a permanent bounce, so the
     * transient and undetermined values never appear.
     */
    private static String deliveryEventName(InsightsEvent event) {
        if (!"BOUNCE".equals(event.type())) {
            return event.type();
        }
        InsightsBounce bounce = bounceOf(event);
        String bounceType = bounce == null ? null : bounce.bounceType();
        if ("TRANSIENT".equals(bounceType)) {
            return "TRANSIENT_BOUNCE";
        }
        if ("PERMANENT".equals(bounceType)) {
            return "PERMANENT_BOUNCE";
        }
        return "UNDETERMINED_BOUNCE";
    }

    private static InsightsEvent lastDeliveryEvent(EmailInsights recipient) {
        InsightsEvent last = null;
        for (InsightsEvent event : orEmpty(recipient.events())) {
            last = event;
        }
        return last;
    }

    private static InsightsBounce bounceOf(InsightsEvent event) {
        if (event == null) {
            return null;
        }
        InsightsEventDetails details = event.details();
        return details == null ? null : details.bounce();
    }

    static byte[] insightsCsv(List<InsightsRow> rows) {
        StringBuilder out = new StringBuilder();
        for (String column : INSIGHTS_COLUMNS) {
            out.append(quote(column)).append(',');
        }
        out.append('\n');
        for (InsightsRow row : rows) {
            for (String column : INSIGHTS_COLUMNS) {
                out.append(quote(row.columns().get(column))).append(',');
            }
            out.append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] insightsJson(List<InsightsRow> rows) {
        StringBuilder out = new StringBuilder("[\n");
        boolean first = true;
        for (InsightsRow row : rows) {
            out.append(first ? "" : ",");
            first = false;
            out.append('{');
            boolean firstColumn = true;
            for (String column : INSIGHTS_COLUMNS) {
                String value = row.columns().get(column);
                if (value == null) {
                    continue;
                }
                if (!firstColumn) {
                    out.append(',');
                }
                firstColumn = false;
                out.append(jsonString(column)).append(':').append(jsonString(value));
            }
            out.append("}\n");
        }
        out.append("]\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** One exported metric row: the dimension values it is keyed by, then the requested metrics. */
    record MetricRow(List<String> dimensionValues, List<Double> values) {}

    static byte[] metricsCsv(List<String> dimensionNames, List<String> metricColumns,
                             List<MetricRow> rows) {
        StringBuilder out = new StringBuilder(String.join(",", dimensionNames));
        for (String column : metricColumns) {
            out.append(',').append(column);
        }
        out.append('\n');
        for (MetricRow row : rows) {
            for (int i = 0; i < row.dimensionValues().size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(csvField(row.dimensionValues().get(i)));
            }
            for (Double value : row.values()) {
                out.append(',').append(decimal(value));
            }
            out.append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] metricsJson(List<String> dimensionNames, List<String> metricColumns,
                              List<MetricRow> rows) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (MetricRow row : rows) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('{');
            for (int i = 0; i < dimensionNames.size(); i++) {
                out.append(jsonString(dimensionNames.get(i).toLowerCase(Locale.ROOT))).append(':')
                        .append(jsonString(row.dimensionValues().get(i))).append(',');
            }
            out.append("\"metrics\":{");
            for (int i = 0; i < metricColumns.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(jsonString(metricColumns.get(i))).append(':')
                        .append(decimal(row.values().get(i)));
            }
            out.append("}}");
        }
        out.append("]\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The probed metrics file leaves its values bare, so a value is quoted only when it has to be.
     * A dimension value can carry a comma, and an unquoted one would shift every later column.
     */
    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return quote(value);
    }

    private static String decimal(Double value) {
        return String.format(Locale.ROOT, "%.4f", value == null ? 0.0 : value);
    }

    private static String format(Instant instant) {
        return instant == null ? null : EXPORT_TIMESTAMP.format(instant);
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
