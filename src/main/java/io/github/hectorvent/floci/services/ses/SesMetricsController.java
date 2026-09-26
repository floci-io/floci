package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.ses.SesV2Json.epochSeconds;
import static io.github.hectorvent.floci.services.ses.SesV2Json.readOptionBody;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMemberOrAbsent;

/**
 * SES V2 metric data ({@code POST /v2/email/metrics/batch}), the aggregate view of the same stored
 * timelines {@code GetMessageInsights} serves one message at a time.
 *
 * <p>Probe-confirmed against real SES (2026-09-23, us-east-1). The operation is gated on Virtual
 * Deliverability Manager, like message insights. Results come back in an unspecified order, both
 * {@code Results} and {@code Errors} are always present, and a result always carries its three
 * members even when the series is empty. {@link SesMetricData} documents the bucketing.
 *
 * <p>Validation order is probed, strongest first: the size of {@code Queries}, a duplicate
 * {@code Id}, an unsupported dimension name, the aggregated member constraints, the dimension
 * value, then the dates. Two orderings could not be observed and are noted in the service docs.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesMetricsController {

    private static final int MAX_QUERIES = 10;
    private static final int MAX_DIMENSIONS = 3;
    private static final Pattern NAME_CHARSET = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Duration MAX_INTERVAL = Duration.ofDays(60);
    private static final Duration WINDOW_SLACK = Duration.ofDays(1);

    // The enum sets AWS spells out in its validation messages, in AWS's own order.
    private static final String METRIC_ENUM =
            "[DELIVERY_COMPLAINT, CLICK, SEND, OPEN, COMPLAINT, DELIVERY, DELIVERY_OPEN, "
                    + "PERMANENT_BOUNCE, DELIVERY_CLICK, TRANSIENT_BOUNCE]";
    private static final String NAMESPACE_ENUM = "[VDM]";

    private final SesSentEmailService sentEmailService;
    private final SesAccountService accountService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesMetricsController(SesSentEmailService sentEmailService, SesAccountService accountService,
                                RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.sentEmailService = sentEmailService;
        this.accountService = accountService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/metrics/batch")
    public Response batchGetMetricData(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        accountService.requireVdmEnabled(region);

        JsonNode request = readOptionBody(objectMapper, body);
        List<JsonNode> queryNodes = requireQueries(request);
        requireUniqueIds(queryNodes);
        requireKnownDimensionNames(queryNodes);
        requireMemberConstraints(queryNodes);
        // Probe-confirmed request-wide: a blank identity in the second query outranks a reversed
        // date range in the first, and the other way round too.
        for (JsonNode query : queryNodes) {
            requireDimensionValues(dimensionsOf(query));
        }

        List<SesMetricData.Query> queries = new ArrayList<>(queryNodes.size());
        for (int i = 0; i < queryNodes.size(); i++) {
            queries.add(parseQuery(queryNodes.get(i), i + 1));
        }

        List<SentEmail> emails = withinAnyWindow(sentEmailService.listInRegion(region), queries);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode results = result.putArray("Results");
        for (SesMetricData.Query query : queries) {
            results.add(render(query, SesMetricData.aggregate(emails, query)));
        }
        // Never populated in any probe: a bad request fails the whole call rather than one query.
        result.putArray("Errors");
        return Response.ok(result).build();
    }

    /**
     * Drops the messages no query in the batch can reach, so the per-query aggregation passes walk
     * only the requested window rather than every message the region retains. The region's messages
     * are already listed and held by then, so this bounds the repeated walks, not the initial scan.
     * Events are derived at send time and sit within milliseconds of {@code sentAt}, so a day of
     * slack on each side is far more than any timeline needs; the per-event range check in
     * {@link SesMetricData} remains the authority.
     */
    private static List<SentEmail> withinAnyWindow(List<SentEmail> emails,
                                                   List<SesMetricData.Query> queries) {
        Instant earliest = null;
        Instant latest = null;
        for (SesMetricData.Query query : queries) {
            if (earliest == null || query.startDate().isBefore(earliest)) {
                earliest = query.startDate();
            }
            if (latest == null || query.endDate().isAfter(latest)) {
                latest = query.endDate();
            }
        }
        // A caller may legitimately name a timestamp within a day of Instant's own bounds, and the
        // slack must not run off them and turn that into a server error.
        Instant from = earliest.isBefore(Instant.MIN.plus(WINDOW_SLACK))
                ? Instant.MIN : earliest.minus(WINDOW_SLACK);
        Instant to = latest.isAfter(Instant.MAX.minus(WINDOW_SLACK))
                ? Instant.MAX : latest.plus(WINDOW_SLACK);
        List<SentEmail> kept = new ArrayList<>();
        for (SentEmail email : emails) {
            Instant sentAt = email.getSentAt();
            if (sentAt == null || (!sentAt.isBefore(from) && sentAt.isBefore(to))) {
                kept.add(email);
            }
        }
        return kept;
    }

    private ObjectNode render(SesMetricData.Query query, SesMetricData.Series series) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", query.id());
        ArrayNode timestamps = node.putArray("Timestamps");
        for (Instant timestamp : series.timestamps()) {
            timestamps.add(epochSeconds(timestamp));
        }
        ArrayNode values = node.putArray("Values");
        for (Long value : series.values()) {
            values.add(value);
        }
        return node;
    }

    private static List<JsonNode> requireQueries(JsonNode request) {
        JsonNode queries = request.path("Queries");
        // Absent and null fail the list constraint, but a present value of the wrong shape is a
        // deserialization failure, the same treatment the Dimensions map gets below.
        if (!queries.isMissingNode() && !queries.isNull() && !queries.isArray()) {
            throw SesV2Json.unexpectedStartError(queries);
        }
        if (!queries.isArray() || queries.isEmpty()) {
            throw listConstraint("Member must have length greater than or equal to 1");
        }
        if (queries.size() > MAX_QUERIES) {
            throw listConstraint("Member must have length less than or equal to " + MAX_QUERIES);
        }
        List<JsonNode> nodes = new ArrayList<>(queries.size());
        for (JsonNode query : queries) {
            if (!query.isObject()) {
                throw SesV2Json.unexpectedStartError(query);
            }
            nodes.add(query);
        }
        return nodes;
    }

    private static void requireUniqueIds(List<JsonNode> queries) {
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode query : queries) {
            String id = stringMemberOrAbsent(query, "Id");
            if (id != null && !seen.add(id)) {
                throw new AwsException("BadRequestException",
                        "All queries must have a unique identifier.", 400);
            }
        }
    }

    /**
     * An unknown dimension name outranks the member constraints: a query carrying both an unknown
     * dimension and an invalid Metric reports the dimension.
     */
    private static void requireKnownDimensionNames(List<JsonNode> queries) {
        for (JsonNode query : queries) {
            JsonNode dimensions = query.path("Dimensions");
            if (dimensions.isMissingNode() || dimensions.isNull()) {
                continue;
            }
            // A map member has to be a JSON object: an array or a scalar is a deserialization
            // failure, not an absent Dimensions that silently drops the filter.
            if (!dimensions.isObject()) {
                throw SesV2Json.unexpectedStartError(dimensions);
            }
            Iterator<String> names = dimensions.fieldNames();
            while (names.hasNext()) {
                if (!SesMetricData.DIMENSION_NAMES.contains(names.next())) {
                    throw new AwsException("BadRequestException",
                            "Unsupported dimension provided.", 400);
                }
            }
        }
    }

    /**
     * The Smithy member constraints, aggregated into one message the way AWS reports them. The
     * probed order is metric, namespace, id; the position of an empty Dimensions map among them was
     * not observed.
     */
    private static void requireMemberConstraints(List<JsonNode> queries) {
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            JsonNode query = queries.get(i);
            int position = i + 1;
            String metric = stringMemberOrAbsent(query, "Metric");
            if (metric == null || !SesMetricData.METRICS.contains(metric)) {
                violations.add(memberConstraint(position, "metric",
                        "Member must satisfy enum value set: " + METRIC_ENUM));
            }
            String namespace = stringMemberOrAbsent(query, "Namespace");
            if (namespace == null || !"VDM".equals(namespace)) {
                violations.add(memberConstraint(position, "namespace",
                        "Member must satisfy enum value set: " + NAMESPACE_ENUM));
            }
            String id = stringMemberOrAbsent(query, "Id");
            if (id == null || id.isEmpty()) {
                violations.add(memberConstraint(position, "id",
                        "Member must have length greater than or equal to 1"));
            } else if (id.length() > 255) {
                violations.add(memberConstraint(position, "id",
                        "Member must have length less than or equal to 255"));
            }
            JsonNode dimensions = query.path("Dimensions");
            if (dimensions.isObject() && dimensions.isEmpty()) {
                violations.add(memberConstraint(position, "dimensions",
                        "Member must have length greater than or equal to 1"));
            }
            // Reachable only since TENANT_NAME made a fourth key possible; probe-confirmed.
            if (dimensions.isObject() && dimensions.size() > MAX_DIMENSIONS) {
                violations.add(memberConstraint(position, "dimensions",
                        "Member must have length less than or equal to " + MAX_DIMENSIONS));
            }
        }
        if (!violations.isEmpty()) {
            throw aggregated(violations);
        }
    }

    private static Map<String, String> dimensionsOf(JsonNode query) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        JsonNode node = query.path("Dimensions");
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getValue().isTextual()) {
                    throw new AwsException("SerializationException", null, 400);
                }
                dimensions.put(field.getKey(), field.getValue().textValue());
            }
        }
        return dimensions;
    }

    private SesMetricData.Query parseQuery(JsonNode query, int position) {
        Map<String, String> dimensions = dimensionsOf(query);

        Instant startDate = requireTimestamp(query, "StartDate", "startDate", position);
        Instant endDate = requireTimestamp(query, "EndDate", "endDate", position);
        if (!startDate.isBefore(endDate)) {
            throw new AwsException("BadRequestException",
                    "Expected start date to be before the end date.", 400);
        }
        if (Duration.between(startDate, endDate).compareTo(MAX_INTERVAL) > 0) {
            throw new AwsException("BadRequestException",
                    "Invalid interval, can request at most 60 days. Please partition your "
                            + "requested interval.", 400);
        }
        return new SesMetricData.Query(stringMemberOrAbsent(query, "Id"),
                stringMemberOrAbsent(query, "Metric"), dimensions, startDate, endDate);
    }

    /**
     * Each dimension validates its own value, probe-confirmed 2026-09-24 against real SES. ISP,
     * the configuration set and the tenant share one charset, ASCII alphanumerics plus underscore
     * and hyphen, which is the rule AWS spells out in its own two messages. An identity is either
     * a bare name or {@code local@domain} with both halves present. The order in which two bad
     * values in one query are reported was not observed and is Floci's choice.
     */
    private static void requireDimensionValues(Map<String, String> dimensions) {
        String identity = dimensions.get("EMAIL_IDENTITY");
        if (identity != null && !isIdentity(identity)) {
            throw new AwsException("BadRequestException", "Invalid email identity provided.", 400);
        }
        String isp = dimensions.get("ISP");
        if (isp != null && !NAME_CHARSET.matcher(isp).matches()) {
            throw new AwsException("BadRequestException", "Invalid ISP name provided.", 400);
        }
        requireName(dimensions.get("CONFIGURATION_SET"), "configuration set name",
                "The configuration set name must be specified.");
        requireName(dimensions.get("TENANT_NAME"), "tenant name", "TenantName cannot be empty");
    }

    private static void requireName(String value, String noun, String blankMessage) {
        if (value == null) {
            return;
        }
        if (value.isEmpty()) {
            throw new AwsException("BadRequestException", blankMessage, 400);
        }
        if (!NAME_CHARSET.matcher(value).matches()) {
            throw new AwsException("BadRequestException", "Invalid " + noun + " <" + value
                    + ">: only alphanumeric ASCII characters, '_', and '-' are allowed.", 400);
        }
    }

    /**
     * Accepts every identity the probe accepted (a bare label, a domain, and local@domain in any
     * case) and rejects every one it refused (blank, a missing half, a space, a star).
     */
    private static boolean isIdentity(String value) {
        if (value.isEmpty() || value.chars().anyMatch(c -> c == '*' || Character.isWhitespace(c))) {
            return false;
        }
        int at = value.indexOf('@');
        if (at < 0) {
            return true;
        }
        return at > 0 && at < value.length() - 1 && value.indexOf('@', at + 1) < 0;
    }

    private static Instant requireTimestamp(JsonNode query, String field, String member, int position) {
        JsonNode node = query.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw aggregated(List.of(memberConstraint(position, member, "Member must not be null")));
        }
        if (node.isNumber()) {
            try {
                return Instant.ofEpochMilli(Math.round(node.doubleValue() * 1000.0));
            } catch (DateTimeException e) {
                // A value outside Instant's range is the caller's, so it is a 400, not a 500.
                throw new AwsException("SerializationException", e.getMessage(), 400);
            }
        }
        if (node.isTextual()) {
            try {
                return Instant.parse(node.asText());
            } catch (DateTimeParseException e) {
                throw new AwsException("SerializationException", e.getMessage(), 400);
            }
        }
        throw SesV2Json.unexpectedStartError(node);
    }

    private static String memberConstraint(int position, String member, String constraint) {
        return "Value at 'queries." + position + ".member." + member
                + "' failed to satisfy constraint: " + constraint;
    }

    private static AwsException listConstraint(String constraint) {
        return aggregated(List.of("Value at 'queries' failed to satisfy constraint: " + constraint));
    }

    private static AwsException aggregated(List<String> violations) {
        String header = violations.size() == 1
                ? "1 validation error detected: "
                : violations.size() + " validation errors detected: ";
        return new AwsException("BadRequestException",
                header + String.join("; ", violations), 400);
    }
}
