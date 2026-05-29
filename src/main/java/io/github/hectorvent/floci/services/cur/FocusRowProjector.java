package io.github.hectorvent.floci.services.cur;

import io.github.hectorvent.floci.core.common.UsageLine;
import io.github.hectorvent.floci.services.ce.PricingRateLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects {@link UsageLine} rows into FOCUS 1.2 / CUR 2.0 column shape.
 * <p>
 * Pure transformation; no I/O. The downstream {@code ParquetEmitter} handles
 * NDJSON staging and DuckDB invocation.
 *
 * <p>Cost math mirrors {@code services.ce.CostSynthesizer}: {@code BilledCost}
 * (and the four other USD metrics) are computed as
 * {@code quantity * unitPrice} for usage lines, and as {@code quantity}
 * (already USD-denominated, may be negative) for {@code Credit} / {@code Refund}
 * / {@code Tax} / {@code SavingsPlan*Fee} record types.
 */
@ApplicationScoped
public class FocusRowProjector {

    /** Record types whose quantity is already in USD and bypasses unit-price multiplication. */
    private static final Set<String> USD_DENOMINATED = Set.of(
            UsageLine.RECORD_TYPE_CREDIT,
            UsageLine.RECORD_TYPE_REFUND,
            UsageLine.RECORD_TYPE_TAX,
            UsageLine.RECORD_TYPE_SP_UPFRONT_FEE,
            UsageLine.RECORD_TYPE_SP_RECURRING_FEE);

    private final PricingRateLookup rateLookup;

    @Inject
    public FocusRowProjector(PricingRateLookup rateLookup) {
        this.rateLookup = rateLookup;
    }

    /** Builds the FOCUS rows for {@code lines}. Empty input returns empty output. */
    public List<FocusRow> project(List<UsageLine> lines) {
        // Cache per-(service, region) rate lookups for the request scope.
        Map<String, Map<String, Double>> rateCache = new HashMap<>();
        List<FocusRow> rows = new java.util.ArrayList<>(lines.size());
        for (UsageLine line : lines) {
            rows.add(projectOne(line, rateCache));
        }
        return rows;
    }

    /** Builds a single FOCUS row. Used by both {@link #project} and tests. */
    FocusRow projectOne(UsageLine line, Map<String, Map<String, Double>> rateCache) {
        double cost = costFor(line, rateCache);
        Map<String, String> tags = line.tags() == null ? Map.of() : new LinkedHashMap<>(line.tags());

        Instant chargeStart = line.periodStart();
        Instant chargeEnd = line.periodEnd();
        BillingPeriod billing = billingPeriodFor(chargeStart);

        return new FocusRow(
                billing.start.toString(),
                billing.end.toString(),
                chargeStart.toString(),
                chargeEnd.toString(),
                line.linkedAccountId(),
                line.linkedAccountId(),
                serviceCategoryFor(line.service()),
                line.service(),
                line.region(),
                line.resourceId(),
                line.resourceId(),
                resourceTypeFor(line.service()),
                chargeCategoryFor(line.recordType()),
                "Standard",
                "Synthesized usage line",
                "Usage-Based",
                line.usageType(),
                line.quantity(), line.usageUnit(),
                line.quantity(), line.usageUnit(),
                cost, cost, cost, cost,
                "USD",
                tags);
    }

    /**
     * Returns the cost (single value reused for {@code BilledCost},
     * {@code EffectiveCost}, {@code ListCost}, {@code ContractedCost}). Floci has
     * no commitment math, so all four metrics resolve to the same number.
     */
    double costFor(UsageLine line, Map<String, Map<String, Double>> rateCache) {
        if (line.recordType() != null && USD_DENOMINATED.contains(line.recordType())) {
            return line.quantity();
        }
        Map<String, Double> rates = rateCache.computeIfAbsent(
                line.service() + "|" + line.region(),
                key -> rateLookup.ratesFor(line.service(), line.region()));
        double rate = rates.getOrDefault(line.usageType(), 0.0);
        return line.quantity() * rate;
    }

    /**
     * Returns the half-open billing-period bounds covering {@code instant}, AWS-style:
     * first day of the month at 00:00 UTC through first day of the next month at 00:00 UTC.
     */
    private static BillingPeriod billingPeriodFor(Instant instant) {
        YearMonth ym = YearMonth.from(instant.atOffset(ZoneOffset.UTC));
        Instant start = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new BillingPeriod(start, end);
    }

    private static String serviceCategoryFor(String serviceCode) {
        if (serviceCode == null) {
            return "Other";
        }
        return switch (serviceCode) {
            case "AmazonEC2", "AWSLambda", "AmazonECS", "AmazonEKS",
                 "AmazonElasticContainerService", "AWSBatch" -> "Compute";
            case "AmazonS3", "AmazonEBS" -> "Storage";
            case "AmazonRDS", "AmazonDynamoDB", "AmazonRedshift",
                 "AmazonElastiCache", "AmazonOpenSearchService" -> "Databases";
            case "AmazonRoute53", "AmazonAPIGateway", "AmazonCloudFront",
                 "AWSDirectConnect", "AWSTransit" -> "Networking";
            case "AWSKeyManagementService", "AWSCertificateManager",
                 "AmazonCognito", "AWSSecretsManager" -> "Identity";
            case "AmazonCloudWatch", "AWSCloudFormation", "AWSSystemsManager",
                 "AWSConfig" -> "Management and Governance";
            case "AmazonKinesis", "AmazonKinesisFirehose", "AmazonMSK",
                 "AmazonAthena", "AWSGlue" -> "Analytics";
            default -> "Other";
        };
    }

    private static String resourceTypeFor(String serviceCode) {
        if (serviceCode == null) {
            return null;
        }
        return switch (serviceCode) {
            case "AmazonEC2" -> "Instance";
            case "AmazonS3" -> "Bucket";
            case "AWSLambda" -> "Function";
            case "AmazonRDS" -> "DBInstance";
            case "AmazonDynamoDB" -> "Table";
            default -> serviceCode;
        };
    }

    private static String chargeCategoryFor(String recordType) {
        if (recordType == null) {
            return "Usage";
        }
        return switch (recordType) {
            case UsageLine.RECORD_TYPE_USAGE,
                 UsageLine.RECORD_TYPE_DISCOUNTED_USAGE,
                 UsageLine.RECORD_TYPE_SP_COVERED_USAGE -> "Usage";
            case UsageLine.RECORD_TYPE_CREDIT -> "Credit";
            case UsageLine.RECORD_TYPE_TAX -> "Tax";
            case UsageLine.RECORD_TYPE_REFUND -> "Refund";
            case UsageLine.RECORD_TYPE_SP_UPFRONT_FEE,
                 UsageLine.RECORD_TYPE_SP_RECURRING_FEE,
                 UsageLine.RECORD_TYPE_SP_NEGATION -> "Adjustment";
            default -> "Usage";
        };
    }

    private record BillingPeriod(Instant start, Instant end) {
    }
}
