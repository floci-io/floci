package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.EmailInsights;
import io.github.hectorvent.floci.services.ses.model.ExportJob;
import io.github.hectorvent.floci.services.ses.model.SentEmail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.github.hectorvent.floci.services.ses.SesV2Json.stringArrayOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMemberOrAbsent;

/**
 * The metrics half of an export job. Every count comes from {@link SesMetricData}, the same
 * aggregation {@code BatchGetMetricData} serves, so an export and a query of the same window
 * cannot disagree.
 *
 * <p>One deviation, documented in {@code docs/services/ses.md}: real SES emits a row for every ISP
 * it knows, zeros included, which is a catalogue Floci has no honest way to reproduce. Floci emits
 * a row per dimension combination it has actually seen. A `RATE` aggregation is the metric's volume
 * over the send volume for the same combination, which matches the definition AWS documents; the
 * probe could not confirm it, because the probed account's rate export carried no data.
 */
final class SesExportMetrics {

    private static final String PATH = "exportDataSource.metricsDataSource.";
    private static final String METRIC_ENUM =
            "[DELIVERY_COMPLAINT, CLICK, SEND, OPEN, COMPLAINT, DELIVERY, DELIVERY_OPEN, "
                    + "PERMANENT_BOUNCE, DELIVERY_CLICK, TRANSIENT_BOUNCE]";
    private static final String DIMENSION_ENUM =
            "[EMAIL_IDENTITY, CONFIGURATION_SET, ISP, TENANT_NAME]";
    private static final String AGGREGATION_ENUM = "[RATE, VOLUME]";
    private static final int MAX_DIMENSIONS = 3;
    private static final int MAX_DIMENSION_VALUES = 10;
    private static final int MAX_METRICS = 10;

    private SesExportMetrics() {}

    /**
     * The model's constraints on a metrics data source: {@code Dimensions} (1 to 3 known names,
     * each with 1 to 10 values), {@code Namespace} and {@code Metrics} (1 to 10) are required, and
     * a metric's {@code Name} and {@code Aggregation} are checked against their enums when present,
     * which is as far as the model goes: neither member is required, and an absent
     * {@code Aggregation} keeps its {@code VOLUME} default. Without this an unknown dimension was
     * dropped, an unknown metric aggregated to zero and an unrecognised aggregation was read as
     * {@code VOLUME}, so a malformed request produced a successful, meaningless export.
     *
     * <p>The probe never sent a malformed source, so the wording and the position in the sequence
     * are Floci's, chosen to match the sibling {@code BatchGetMetricData}. Dimension values are
     * deliberately not checked: {@code *} is a legal value here and the export probe never showed
     * the per-value rules the batch operation applies.
     */
    static void validate(JsonNode metrics) {
        requireWireTypes(metrics);
        List<String> violations = new ArrayList<>();
        validateDimensions(metrics.path("Dimensions"), violations);
        JsonNode namespace = metrics.path("Namespace");
        if (!namespace.isTextual() || !"VDM".equals(namespace.textValue())) {
            violations.add(constraint("namespace",
                    "Member must satisfy enum value set: [VDM]"));
        }
        validateMetrics(metrics.path("Metrics"), violations);
        if (!violations.isEmpty()) {
            String header = violations.size() == 1
                    ? "1 validation error detected: "
                    : violations.size() + " validation errors detected: ";
            throw new AwsException("BadRequestException",
                    header + String.join("; ", violations), 400);
        }
    }

    /**
     * The deserialization-level checks, which come first and answer {@code SerializationException}
     * like every other typed SES v2 member: a dimension value list of numbers was otherwise
     * coerced to strings, and a metric that is a bare string was read as one with no members at
     * all, both producing a successful but meaningless export.
     */
    private static void requireWireTypes(JsonNode metrics) {
        JsonNode dimensions = metrics.path("Dimensions");
        if (present(dimensions)) {
            if (!dimensions.isObject()) {
                throw serializationError();
            }
            Iterator<String> names = dimensions.fieldNames();
            while (names.hasNext()) {
                stringArrayOrAbsent(dimensions, names.next());
            }
        }
        stringMemberOrAbsent(metrics, "Namespace");
        JsonNode requested = metrics.path("Metrics");
        if (present(requested)) {
            if (!requested.isArray()) {
                throw serializationError();
            }
            for (JsonNode metric : requested) {
                if (!metric.isObject()) {
                    throw serializationError();
                }
                stringMemberOrAbsent(metric, "Name");
                stringMemberOrAbsent(metric, "Aggregation");
            }
        }
    }

    private static AwsException serializationError() {
        return new AwsException("SerializationException", null, 400);
    }

    private static void validateDimensions(JsonNode dimensions, List<String> violations) {
        if (!dimensions.isObject()) {
            violations.add(constraint("dimensions", "Member must not be null"));
            return;
        }
        if (dimensions.isEmpty()) {
            violations.add(constraint("dimensions",
                    "Member must have length greater than or equal to 1"));
            return;
        }
        if (dimensions.size() > MAX_DIMENSIONS) {
            violations.add(constraint("dimensions",
                    "Member must have length less than or equal to " + MAX_DIMENSIONS));
        }
        Iterator<Map.Entry<String, JsonNode>> fields = dimensions.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!SesMetricData.DIMENSION_NAMES.contains(field.getKey())) {
                violations.add(constraint("dimensions",
                        "Member must satisfy enum value set: " + DIMENSION_ENUM));
                continue;
            }
            JsonNode values = field.getValue();
            if (!values.isArray() || values.isEmpty()) {
                violations.add(constraint("dimensions." + field.getKey(),
                        "Member must have length greater than or equal to 1"));
            } else if (values.size() > MAX_DIMENSION_VALUES) {
                violations.add(constraint("dimensions." + field.getKey(),
                        "Member must have length less than or equal to " + MAX_DIMENSION_VALUES));
            }
        }
    }

    private static void validateMetrics(JsonNode metrics, List<String> violations) {
        if (!metrics.isArray()) {
            violations.add(constraint("metrics", "Member must not be null"));
            return;
        }
        if (metrics.isEmpty()) {
            violations.add(constraint("metrics",
                    "Member must have length greater than or equal to 1"));
            return;
        }
        if (metrics.size() > MAX_METRICS) {
            violations.add(constraint("metrics",
                    "Member must have length less than or equal to " + MAX_METRICS));
        }
        for (int i = 0; i < metrics.size(); i++) {
            JsonNode metric = metrics.get(i);
            // Neither member is required by the model, so only a value that is present is
            // checked: an absent Aggregation keeps its VOLUME default.
            JsonNode name = metric.path("Name");
            if (present(name) && !SesMetricData.METRICS.contains(name.asText())) {
                violations.add(constraint("metrics." + (i + 1) + ".member.name",
                        "Member must satisfy enum value set: " + METRIC_ENUM));
            }
            JsonNode aggregation = metric.path("Aggregation");
            if (present(aggregation)
                    && !("RATE".equals(aggregation.asText()) || "VOLUME".equals(aggregation.asText()))) {
                violations.add(constraint("metrics." + (i + 1) + ".member.aggregation",
                        "Member must satisfy enum value set: " + AGGREGATION_ENUM));
            }
        }
    }

    private static boolean present(JsonNode node) {
        return !node.isMissingNode() && !node.isNull();
    }

    private static String constraint(String member, String constraint) {
        return "Value at '" + PATH + member + "' failed to satisfy constraint: " + constraint;
    }

    static byte[] render(List<SentEmail> emails, JsonNode metrics, String dataFormat) {
        List<String> dimensionNames = new ArrayList<>();
        Map<String, Set<String>> requested = new LinkedHashMap<>();
        JsonNode dimensions = metrics.path("Dimensions");
        Iterator<Map.Entry<String, JsonNode>> fields = dimensions.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            dimensionNames.add(field.getKey());
            Set<String> values = new LinkedHashSet<>();
            for (JsonNode value : field.getValue()) {
                values.add(value.asText());
            }
            requested.put(field.getKey(), values);
        }

        List<String> metricColumns = new ArrayList<>();
        List<String> metricNames = new ArrayList<>();
        List<Boolean> asRate = new ArrayList<>();
        for (JsonNode metric : metrics.path("Metrics")) {
            String name = metric.path("Name").asText("");
            String aggregation = metric.path("Aggregation").asText("VOLUME");
            metricColumns.add(name + "_" + aggregation);
            metricNames.add(name);
            asRate.add("RATE".equals(aggregation));
        }

        Instant start = instant(metrics.path("StartDate"));
        Instant end = instant(metrics.path("EndDate"));

        List<List<String>> combinations = combinations(emails, dimensionNames, requested);
        List<SesExportPayloads.MetricRow> rows = new ArrayList<>(combinations.size());
        for (List<String> combination : combinations) {
            Map<String, String> filter = new LinkedHashMap<>();
            for (int i = 0; i < dimensionNames.size(); i++) {
                filter.put(dimensionNames.get(i), combination.get(i));
            }
            List<Double> values = new ArrayList<>(metricNames.size());
            for (int i = 0; i < metricNames.size(); i++) {
                double volume = total(emails, metricNames.get(i), filter, start, end);
                if (!asRate.get(i)) {
                    values.add(volume);
                    continue;
                }
                double sends = total(emails, "SEND", filter, start, end);
                values.add(sends == 0.0 ? 0.0 : volume / sends);
            }
            rows.add(new SesExportPayloads.MetricRow(combination, values));
        }

        return ExportJob.FORMAT_CSV.equals(dataFormat)
                ? SesExportPayloads.metricsCsv(dimensionNames, metricColumns, rows)
                : SesExportPayloads.metricsJson(dimensionNames, metricColumns, rows);
    }

    private static double total(List<SentEmail> emails, String metric, Map<String, String> filter,
                                Instant start, Instant end) {
        SesMetricData.Series series = SesMetricData.aggregate(emails,
                new SesMetricData.Query("export", metric, filter, start, end));
        double sum = 0.0;
        for (Long value : series.values()) {
            sum += value;
        }
        return sum;
    }

    /**
     * The dimension combinations present in the stored messages, narrowed to the requested values.
     * A requested value of {@code *} means every value, which is how AWS asks for the whole
     * catalogue.
     */
    private static List<List<String>> combinations(List<SentEmail> emails, List<String> names,
                                                   Map<String, Set<String>> requested) {
        Set<List<String>> seen = new LinkedHashSet<>();
        for (SentEmail email : emails) {
            List<EmailInsights> recipients = email.getInsights() == null
                    ? List.of() : email.getInsights();
            for (EmailInsights recipient : recipients) {
                List<String> combination = new ArrayList<>(names.size());
                boolean usable = true;
                for (String name : names) {
                    String value = dimensionValue(name, email, recipient);
                    if (value == null || !wants(name, requested.get(name), value)) {
                        usable = false;
                        break;
                    }
                    combination.add(value);
                }
                if (usable) {
                    seen.add(combination);
                }
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * An {@code EMAIL_IDENTITY} value names either a sending address or its domain, so a requested
     * domain has to match the addresses inside it. {@link SesMetricData} draws that line for
     * {@code BatchGetMetricData}, and the two read the same sends, so they read it the same way.
     */
    private static boolean wants(String name, Set<String> wanted, String value) {
        if (wanted.contains("*") || wanted.contains(value)) {
            return true;
        }
        if (!"EMAIL_IDENTITY".equals(name)) {
            return false;
        }
        return wanted.stream().anyMatch(identity -> SesMetricData.matchesIdentity(value, identity));
    }

    private static String dimensionValue(String name, SentEmail email, EmailInsights recipient) {
        return switch (name) {
            case "ISP" -> recipient.isp();
            // The bare address, lower-cased, not the stored Source: a display name would key a row
            // that the identity matching behind the counts then refuses, leaving every value at
            // zero, and that matching ignores case, so two spellings would each count both sends.
            case "EMAIL_IDENTITY" -> email.getSource() == null
                    ? null : SesService.extractEmailAddress(email.getSource()).toLowerCase(Locale.ROOT);
            case "CONFIGURATION_SET" -> email.getConfigurationSetName();
            case "TENANT_NAME" -> email.getTenantName();
            default -> null;
        };
    }

    private static Instant instant(JsonNode node) {
        if (node.isNumber()) {
            return Instant.ofEpochMilli(Math.round(node.doubleValue() * 1000.0));
        }
        return node.isTextual() ? Instant.parse(node.asText()) : Instant.EPOCH;
    }
}
