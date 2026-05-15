package io.github.hectorvent.floci.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ResourceUsageEnumerator;
import io.github.hectorvent.floci.core.common.UsageLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * AWS Cost Explorer (`ce:*`) emulation.
 * <p>
 * Synthesizes cost and usage from Floci's own resource state, multiplied by
 * the AWS Pricing snapshot served by {@code services/pricing}. Discovery of
 * which services contribute to cost is handled by CDI: every
 * {@code @ApplicationScoped} bean implementing
 * {@link ResourceUsageEnumerator} is auto-injected here, so adding a new
 * Floci service with cost reporting needs no change to this class.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Cost_Explorer_Service.html">AWS Cost Explorer API</a>
 */
@ApplicationScoped
public class CostExplorerService {

    private static final Logger LOG = Logger.getLogger(CostExplorerService.class);

    private final ObjectMapper objectMapper;
    private final Instance<ResourceUsageEnumerator> enumerators;
    private final PricingRateLookup rateLookup;
    private final GroupAggregator aggregator;
    private final double monthlyCreditUsd;

    @Inject
    public CostExplorerService(ObjectMapper objectMapper,
                                Instance<ResourceUsageEnumerator> enumerators,
                                PricingRateLookup rateLookup,
                                EmulatorConfig config) {
        this(objectMapper, enumerators, rateLookup, config.services().ce().creditUsdMonthly());
    }

    CostExplorerService(ObjectMapper objectMapper,
                         Instance<ResourceUsageEnumerator> enumerators,
                         PricingRateLookup rateLookup,
                         double monthlyCreditUsd) {
        this.objectMapper = objectMapper;
        this.enumerators = enumerators;
        this.rateLookup = rateLookup;
        this.aggregator = new GroupAggregator(objectMapper);
        this.monthlyCreditUsd = monthlyCreditUsd;
    }

    public ObjectNode getCostAndUsage(JsonNode request, String defaultRegion) {
        return runCostAndUsage(request, defaultRegion);
    }

    /**
     * Returns the same shape as {@link #getCostAndUsage} for now. Floci's
     * resource-level data is already surfaced through the {@code RESOURCE_ID}
     * dimension, so a caller that wants resource breakdown can issue
     * {@code GetCostAndUsage} with {@code GroupBy=[{Type:DIMENSION,Key:RESOURCE_ID}]}.
     * A separate emit path that returns inline resource attributions can land
     * later if a consumer needs it.
     */
    public ObjectNode getCostAndUsageWithResources(JsonNode request, String defaultRegion) {
        return runCostAndUsage(request, defaultRegion);
    }

    private ObjectNode runCostAndUsage(JsonNode request, String defaultRegion) {
        TimeWindow window = parseTimeWindow(request);
        TimeBucketing.Granularity granularity = TimeBucketing.parseGranularity(
                request.path("Granularity").asText(null));
        Set<String> metrics = GroupAggregator.parseMetrics(request.path("Metrics"));
        if (metrics.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'Metrics' failed to satisfy constraint: Member must contain at least 1 element.", 400);
        }
        List<GroupAggregator.GroupBy> groupBys = GroupAggregator.parseGroupBy(request.path("GroupBy"));
        JsonNode filter = request.has("Filter") ? request.get("Filter") : null;

        List<UsageLine> all = collectLines(window.start(), window.end(), defaultRegion);
        // Apply filter once across the full window so the same set is reused
        // per bucket (lines are emitted per request scope, no cross-bucket leakage).
        List<UsageLine> filtered = new ArrayList<>();
        for (UsageLine line : all) {
            if (FilterExpressionEvaluator.matches(filter, line)) {
                filtered.add(line);
            }
        }

        CostSynthesizer synthesizer = new CostSynthesizer(rateLookup);
        if (monthlyCreditUsd > 0) {
            CreditLineEmitter creditEmitter = new CreditLineEmitter(monthlyCreditUsd);
            List<UsageLine> credits = creditEmitter.emit(filtered.stream(), synthesizer).toList();
            for (UsageLine credit : credits) {
                if (FilterExpressionEvaluator.matches(filter, credit)) {
                    filtered.add(credit);
                }
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode resultsByTime = response.putArray("ResultsByTime");
        for (TimeBucketing.Bucket bucket : TimeBucketing.split(window.start(), window.end(), granularity)) {
            List<UsageLine> bucketLines = new ArrayList<>();
            for (UsageLine line : filtered) {
                if (overlaps(line, bucket)) {
                    bucketLines.add(scaleToBucket(line, bucket));
                }
            }
            resultsByTime.add(aggregator.buildResultByTime(bucket.start(), bucket.end(),
                    bucketLines, groupBys, metrics, synthesizer));
        }
        ArrayNode groupDefinitions = response.putArray("GroupDefinitions");
        for (GroupAggregator.GroupBy gb : groupBys) {
            ObjectNode def = groupDefinitions.addObject();
            def.put("Type", gb.type());
            def.put("Key", gb.key());
        }
        response.putArray("DimensionValueAttributes");
        return response;
    }

    public ObjectNode getDimensionValues(JsonNode request, String defaultRegion) {
        TimeWindow window = parseTimeWindow(request);
        String dimension = request.path("Dimension").asText(null);
        if (dimension == null || dimension.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'Dimension' failed to satisfy constraint: Member must not be null.", 400);
        }
        String search = request.path("SearchString").asText(null);
        JsonNode filter = request.has("Filter") ? request.get("Filter") : null;

        Set<String> values = new TreeSet<>();
        for (UsageLine line : collectLines(window.start(), window.end(), defaultRegion)) {
            if (!FilterExpressionEvaluator.matches(filter, line)) {
                continue;
            }
            String v = FilterExpressionEvaluator.dimensionValue(dimension, line);
            if (v != null && !v.isEmpty() && (search == null || v.contains(search))) {
                values.add(v);
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("DimensionValues");
        for (String v : values) {
            ObjectNode entry = arr.addObject();
            entry.put("Value", v);
        }
        response.put("ReturnSize", values.size());
        response.put("TotalSize", values.size());
        return response;
    }

    public ObjectNode getTags(JsonNode request, String defaultRegion) {
        TimeWindow window = parseTimeWindow(request);
        String tagKey = request.path("TagKey").asText(null);
        String search = request.path("SearchString").asText(null);
        JsonNode filter = request.has("Filter") ? request.get("Filter") : null;

        Set<String> values = new TreeSet<>();
        Set<String> keys = new TreeSet<>();
        for (UsageLine line : collectLines(window.start(), window.end(), defaultRegion)) {
            if (!FilterExpressionEvaluator.matches(filter, line)) {
                continue;
            }
            if (line.tags() == null) {
                continue;
            }
            for (Map.Entry<String, String> tag : line.tags().entrySet()) {
                keys.add(tag.getKey());
                if (tagKey != null && !tagKey.isEmpty()) {
                    if (tagKey.equals(tag.getKey())
                            && (search == null || tag.getValue().contains(search))) {
                        values.add(tag.getValue());
                    }
                }
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Tags");
        Set<String> emit = (tagKey == null || tagKey.isEmpty()) ? keys : values;
        for (String v : emit) {
            arr.add(v);
        }
        response.put("ReturnSize", emit.size());
        response.put("TotalSize", emit.size());
        return response;
    }

    public ObjectNode getReservationCoverage() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("CoveragesByTime");
        ObjectNode total = response.putObject("Total");
        ObjectNode coverageHours = total.putObject("CoverageHours");
        coverageHours.put("OnDemandHours", "0");
        coverageHours.put("ReservedHours", "0");
        coverageHours.put("TotalRunningHours", "0");
        coverageHours.put("CoverageHoursPercentage", "0");
        return response;
    }

    public ObjectNode getReservationUtilization() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("UtilizationsByTime");
        ObjectNode total = response.putObject("Total");
        total.put("UtilizationPercentage", "0");
        total.put("UtilizationPercentageInUnits", "0");
        total.put("PurchasedHours", "0");
        total.put("PurchasedUnits", "0");
        total.put("TotalActualHours", "0");
        total.put("TotalActualUnits", "0");
        total.put("UnusedHours", "0");
        total.put("UnusedUnits", "0");
        total.put("OnDemandCostOfRIHoursUsed", "0");
        total.put("NetRISavings", "0");
        total.put("TotalPotentialRISavings", "0");
        total.put("AmortizedUpfrontFee", "0");
        total.put("AmortizedRecurringFee", "0");
        total.put("TotalAmortizedFee", "0");
        total.put("RICostForUnusedHours", "0");
        total.put("RealizedSavings", "0");
        total.put("UnrealizedSavings", "0");
        return response;
    }

    public ObjectNode getSavingsPlansCoverage() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("SavingsPlansCoverages");
        return response;
    }

    public ObjectNode getSavingsPlansUtilization() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("SavingsPlansUtilizationsByTime");
        ObjectNode total = response.putObject("Total");
        ObjectNode utilization = total.putObject("Utilization");
        utilization.put("TotalCommitment", "0");
        utilization.put("UsedCommitment", "0");
        utilization.put("UnusedCommitment", "0");
        utilization.put("UtilizationPercentage", "0");
        return response;
    }

    public ObjectNode getCostCategories(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("CostCategoryNames");
        response.putArray("CostCategoryValues");
        response.put("ReturnSize", 0);
        response.put("TotalSize", 0);
        return response;
    }

    private List<UsageLine> collectLines(Instant start, Instant end, String region) {
        List<UsageLine> all = new ArrayList<>();
        for (ResourceUsageEnumerator enumerator : enumerators) {
            try {
                enumerator.enumerate(start, end, region).forEach(all::add);
            } catch (Exception e) {
                LOG.warnv(e, "Enumerator {0} failed", enumerator.getClass().getSimpleName());
            }
        }
        return all;
    }

    private TimeWindow parseTimeWindow(JsonNode request) {
        JsonNode tp = request.path("TimePeriod");
        if (!tp.isObject() || tp.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'TimePeriod' failed to satisfy constraint: Member must not be null.", 400);
        }
        Instant start = TimeBucketing.parseDate(tp.path("Start").asText(null), "TimePeriod.Start");
        Instant end = TimeBucketing.parseDate(tp.path("End").asText(null), "TimePeriod.End");
        return new TimeWindow(start, end);
    }

    private static boolean overlaps(UsageLine line, TimeBucketing.Bucket bucket) {
        return line.periodStart().isBefore(bucket.end()) && line.periodEnd().isAfter(bucket.start());
    }

    /**
     * Returns a copy of {@code line} with quantity scaled to the fraction of the
     * line's window that falls inside {@code bucket}. Keeps daily/hourly outputs
     * proportional when the request window doesn't align to the line's day.
     */
    private static UsageLine scaleToBucket(UsageLine line, TimeBucketing.Bucket bucket) {
        Instant overlapStart = line.periodStart().isAfter(bucket.start()) ? line.periodStart() : bucket.start();
        Instant overlapEnd = line.periodEnd().isBefore(bucket.end()) ? line.periodEnd() : bucket.end();
        long lineSeconds = Math.max(1, line.periodEnd().getEpochSecond() - line.periodStart().getEpochSecond());
        long overlapSeconds = Math.max(0, overlapEnd.getEpochSecond() - overlapStart.getEpochSecond());
        double factor = overlapSeconds / (double) lineSeconds;
        return new UsageLine(
                bucket.start(), bucket.end(),
                line.service(), line.region(), line.usageType(), line.operation(),
                line.recordType(), line.linkedAccountId(), line.resourceId(),
                line.tags(),
                line.quantity() * factor,
                line.usageUnit());
    }

    private record TimeWindow(Instant start, Instant end) {}
}
