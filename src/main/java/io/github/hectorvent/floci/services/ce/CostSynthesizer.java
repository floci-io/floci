package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.core.common.UsageLine;

import java.util.HashMap;
import java.util.Map;

/**
 * Multiplies a {@link UsageLine} by the matching unit price from
 * {@link PricingRateLookup} to produce per-metric cost amounts.
 * <p>
 * AWS exposes five cost metrics — {@code UnblendedCost}, {@code BlendedCost},
 * {@code AmortizedCost}, {@code NetUnblendedCost}, {@code NetAmortizedCost} —
 * plus {@code UsageQuantity} and {@code NormalizedUsageAmount}. Floci has no
 * commitment math, so all five cost metrics return the same value.
 */
class CostSynthesizer {

    private final PricingRateLookup rates;
    private final Map<String, Map<String, Double>> rateCache = new HashMap<>();

    CostSynthesizer(PricingRateLookup rates) {
        this.rates = rates;
    }

    /** Returns a metric-name -> {amount, unit} map for the given line. */
    Map<String, MetricValue> compute(UsageLine line) {
        double cost;
        double quantity;
        String quantityUnit;
        if (isUsdDenominated(line)) {
            // Credit / Refund / SavingsPlan upfront and recurring fees arrive
            // already denominated in USD. Treat the line quantity as the cost
            // amount directly; do not multiply by a per-unit rate. UsageQuantity
            // is reported as zero because AWS does not surface a separate usage
            // unit for these record types.
            cost = line.quantity();
            quantity = 0.0;
            quantityUnit = line.usageUnit() == null ? "USD" : line.usageUnit();
        } else {
            cost = line.quantity() * lookupRate(line);
            quantity = line.quantity();
            quantityUnit = line.usageUnit();
        }
        Map<String, MetricValue> metrics = new HashMap<>();
        metrics.put("UnblendedCost", new MetricValue(cost, "USD"));
        metrics.put("BlendedCost", new MetricValue(cost, "USD"));
        metrics.put("AmortizedCost", new MetricValue(cost, "USD"));
        metrics.put("NetUnblendedCost", new MetricValue(cost, "USD"));
        metrics.put("NetAmortizedCost", new MetricValue(cost, "USD"));
        metrics.put("UsageQuantity", new MetricValue(quantity, quantityUnit));
        metrics.put("NormalizedUsageAmount", new MetricValue(quantity, quantityUnit));
        return metrics;
    }

    private static boolean isUsdDenominated(UsageLine line) {
        String rt = line.recordType();
        if (rt == null) {
            return false;
        }
        return UsageLine.RECORD_TYPE_CREDIT.equals(rt)
                || UsageLine.RECORD_TYPE_REFUND.equals(rt)
                || UsageLine.RECORD_TYPE_TAX.equals(rt)
                || UsageLine.RECORD_TYPE_SP_UPFRONT_FEE.equals(rt)
                || UsageLine.RECORD_TYPE_SP_RECURRING_FEE.equals(rt);
    }

    private double lookupRate(UsageLine line) {
        Map<String, Double> svc = rateCache.computeIfAbsent(
                line.service() + "|" + line.region(),
                key -> rates.ratesFor(line.service(), line.region()));
        return svc.getOrDefault(line.usageType(), 0.0);
    }

    record MetricValue(double amount, String unit) {}
}
