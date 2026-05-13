package io.github.hectorvent.floci.core.common;

import java.time.Instant;
import java.util.Map;

/**
 * One synthesized cost-and-usage row, before Cost Explorer pricing or grouping.
 * <p>
 * Each Floci service that wants to participate in cost reporting emits these via
 * an {@link ResourceUsageEnumerator} bean. Cost Explorer ({@code services/ce})
 * and CUR / BCM Data Exports consume the same stream.
 * <p>
 * Fields map to the AWS CE / CUR dimensions:
 * <ul>
 *   <li>{@link #service()} — {@code Dimension.SERVICE} (e.g. {@code AmazonEC2})</li>
 *   <li>{@link #region()} — {@code Dimension.REGION}</li>
 *   <li>{@link #usageType()} — {@code Dimension.USAGE_TYPE}</li>
 *   <li>{@link #recordType()} — {@code Dimension.RECORD_TYPE}
 *       (one of {@code Usage}, {@code Credit}, {@code Tax}, {@code Refund},
 *       {@code DiscountedUsage},
 *       {@code SavingsPlanCoveredUsage}, {@code SavingsPlanNegation},
 *       {@code SavingsPlanUpfrontFee}, {@code SavingsPlanRecurringFee})</li>
 *   <li>{@link #operation()} — {@code Dimension.OPERATION}</li>
 *   <li>{@link #linkedAccountId()} — {@code Dimension.LINKED_ACCOUNT}</li>
 *   <li>{@link #resourceId()} — populated when the request asks for resource-level data</li>
 *   <li>{@link #tags()} — resource tags surfaced into the {@code TAG} group key namespace</li>
 *   <li>{@link #quantity()} — usage quantity in {@link #usageUnit()} (e.g. {@code Hrs}, {@code GB-Mo})</li>
 *   <li>{@link #periodStart()} / {@link #periodEnd()} — half-open day window</li>
 * </ul>
 *
 * <p>This is a wire-neutral model. The Cost Explorer service maps it to
 * {@code GetCostAndUsage} responses; the CUR service maps it to Parquet rows.
 */
public record UsageLine(
        Instant periodStart,
        Instant periodEnd,
        String service,
        String region,
        String usageType,
        String operation,
        String recordType,
        String linkedAccountId,
        String resourceId,
        Map<String, String> tags,
        double quantity,
        String usageUnit) {

    public static final String RECORD_TYPE_USAGE = "Usage";
    public static final String RECORD_TYPE_CREDIT = "Credit";
    public static final String RECORD_TYPE_TAX = "Tax";
    public static final String RECORD_TYPE_REFUND = "Refund";
    public static final String RECORD_TYPE_DISCOUNTED_USAGE = "DiscountedUsage";
    public static final String RECORD_TYPE_SP_COVERED_USAGE = "SavingsPlanCoveredUsage";
    public static final String RECORD_TYPE_SP_NEGATION = "SavingsPlanNegation";
    public static final String RECORD_TYPE_SP_UPFRONT_FEE = "SavingsPlanUpfrontFee";
    public static final String RECORD_TYPE_SP_RECURRING_FEE = "SavingsPlanRecurringFee";
}
