package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import static io.github.hectorvent.floci.services.ses.SesV2Json.stringArrayOrAbsent;

/**
 * The {@code Include} / {@code Exclude} filters and {@code MaxResults} of a message-insights
 * export. A row has to match every member present in {@code Include} and no member present in
 * {@code Exclude}; within one member the values are alternatives.
 *
 * <p>{@code TenantName} matches the tenant the send named, which is carried beside the row's
 * columns because the exported file has no tenant column.
 *
 * <p>{@code LastEngagementEvent} filters on opens and clicks, which Floci never derives, so an
 * include on it selects nothing and an exclude on it removes nothing. That is stated in
 * {@code docs/services/ses.md} rather than treated as an error, because the request is valid.
 */
final class SesExportFilters {

    private static final int NO_LIMIT = -1;

    private SesExportFilters() {}

    static List<SesExportPayloads.InsightsRow> apply(List<SesExportPayloads.InsightsRow> rows,
                                                     JsonNode insights) {
        JsonNode include = insights.path("Include");
        JsonNode exclude = insights.path("Exclude");
        List<SesExportPayloads.InsightsRow> kept = new ArrayList<>();
        for (SesExportPayloads.InsightsRow row : rows) {
            if (include.isObject() && !matches(row, include)) {
                continue;
            }
            if (exclude.isObject() && hasConstraint(exclude) && matches(row, exclude)) {
                continue;
            }
            kept.add(row);
        }
        // The service validated the range at create time; this stays defensive so a stored job
        // written by an older build cannot slice with a negative bound.
        JsonNode maxResults = insights.path("MaxResults");
        if (maxResults.isInt() && maxResults.intValue() > 0 && kept.size() > maxResults.intValue()) {
            return kept.subList(0, maxResults.intValue());
        }
        return kept;
    }

    /**
     * The filter members, each paired with the value it reads off a row, so validating a request
     * and filtering its rows can never drift apart.
     */
    private enum Member {
        FROM_EMAIL_ADDRESS("FromEmailAddress", 5, row -> row.columns().get("fromaddress")),
        DESTINATION("Destination", 5, row -> row.columns().get("destination")),
        SUBJECT("Subject", 1, row -> row.columns().get("subject")),
        ISP("Isp", 5, row -> row.columns().get("isp")),
        LAST_DELIVERY_EVENT("LastDeliveryEvent", 5,
                row -> row.columns().get("last_delivery_event")),
        LAST_ENGAGEMENT_EVENT("LastEngagementEvent", 2,
                row -> row.columns().get("last_engagement_event")),
        // The tenant filter arrived with the 2026-09 tenant launch and is absent from the pinned
        // botocore model, so its list has no length to enforce yet.
        TENANT_NAME("TenantName", NO_LIMIT, SesExportPayloads.InsightsRow::tenantName);

        private final String wireName;
        private final int maxValues;
        private final Function<SesExportPayloads.InsightsRow, String> value;

        Member(String wireName, int maxValues,
               Function<SesExportPayloads.InsightsRow, String> value) {
            this.wireName = wireName;
            this.maxValues = maxValues;
            this.value = value;
        }
    }

    /**
     * Rejects a filter block that is not an object and a member that is not a list of strings,
     * rather than reading it as no filter at all and quietly returning rows the caller excluded,
     * then holds each member to the length the model gives it.
     */
    static void validate(JsonNode insights) {
        List<String> violations = new ArrayList<>();
        requireFilterBlock(insights.path("Include"), "include", violations);
        requireFilterBlock(insights.path("Exclude"), "exclude", violations);
        if (!violations.isEmpty()) {
            String header = violations.size() == 1
                    ? "1 validation error detected: "
                    : violations.size() + " validation errors detected: ";
            throw new AwsException("BadRequestException",
                    header + String.join("; ", violations), 400);
        }
    }

    private static void requireFilterBlock(JsonNode filters, String path, List<String> violations) {
        if (filters.isMissingNode() || filters.isNull()) {
            return;
        }
        if (!filters.isObject()) {
            throw new AwsException("SerializationException", null, 400);
        }
        for (Member member : Member.values()) {
            List<String> values = stringArrayOrAbsent(filters, member.wireName);
            if (values != null && member.maxValues != NO_LIMIT && values.size() > member.maxValues) {
                violations.add("Value at 'exportDataSource.messageInsightsDataSource." + path + "."
                        + member.wireName.substring(0, 1).toLowerCase(Locale.ROOT)
                        + member.wireName.substring(1)
                        + "' failed to satisfy constraint: Member must have length less than or "
                        + "equal to " + member.maxValues);
            }
        }
    }

    /**
     * A member with no values constrains nothing, so a block carrying none matches every row. That
     * reading is harmless for {@code Include} and empties the file for {@code Exclude}, which is
     * why the exclude side asks first whether there is anything to exclude on.
     */
    private static boolean hasConstraint(JsonNode filters) {
        for (Member member : Member.values()) {
            JsonNode values = filters.path(member.wireName);
            if (values.isArray() && !values.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(SesExportPayloads.InsightsRow row, JsonNode filters) {
        for (Member member : Member.values()) {
            if (!matchesMember(filters, member.wireName, member.value.apply(row))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesMember(JsonNode filters, String member, String actual) {
        JsonNode values = filters.path(member);
        if (!values.isArray() || values.isEmpty()) {
            return true;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (JsonNode value : values) {
            wanted.add(value.asText());
        }
        return actual != null && wanted.contains(actual);
    }
}
