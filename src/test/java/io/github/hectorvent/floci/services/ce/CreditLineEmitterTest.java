package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.core.common.UsageLine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

class CreditLineEmitterTest {

    private static final Instant JAN_15 = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant MAR_15 = Instant.parse("2026-03-15T00:00:00Z");

    /**
     * Stub synthesizer that returns a fixed monthly cost: $300 for the entire
     * Jan-15 to Mar-15 line. Used to verify proportional split across months.
     */
    private CostSynthesizer fixedCostSynthesizer(double total) {
        return new CostSynthesizer(new PricingRateLookup(null, null) {
            @Override
            public Map<String, Double> ratesFor(String serviceCode, String regionCode) {
                return Map.of();
            }
        }) {
            @Override
            Map<String, MetricValue> compute(UsageLine line) {
                if (UsageLine.RECORD_TYPE_USAGE.equals(line.recordType())) {
                    return Map.of(
                            "UnblendedCost", new MetricValue(total, "USD"),
                            "BlendedCost", new MetricValue(total, "USD"),
                            "AmortizedCost", new MetricValue(total, "USD"),
                            "NetUnblendedCost", new MetricValue(total, "USD"),
                            "NetAmortizedCost", new MetricValue(total, "USD"),
                            "UsageQuantity", new MetricValue(line.quantity(), line.usageUnit()),
                            "NormalizedUsageAmount", new MetricValue(line.quantity(), line.usageUnit()));
                }
                return Map.of();
            }
        };
    }

    private static UsageLine usage(Instant start, Instant end) {
        return new UsageLine(start, end,
                "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                UsageLine.RECORD_TYPE_USAGE,
                "000000000000", "i-test", Map.of(),
                24.0, "Hrs");
    }

    @Test
    void singleMonthLine_emitsOneCredit() {
        CreditLineEmitter emitter = new CreditLineEmitter(50.0);
        UsageLine line = usage(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"));
        List<UsageLine> credits = emitter.emit(Stream.of(line), fixedCostSynthesizer(200.0)).toList();
        assertThat(credits, hasSize(1));
        assertThat(credits.get(0).quantity(), equalTo(-50.0));
        assertThat(credits.get(0).recordType(), equalTo(UsageLine.RECORD_TYPE_CREDIT));
    }

    @Test
    void multiMonthLine_emitsCreditPerCalendarMonth() {
        // Line spans Jan 15 -> Mar 15, total cost $300. Three months touched —
        // Jan (17d), Feb (28d), Mar (14d). Credit = $50/mo capped at the monthly
        // share. All three months must produce a credit line.
        CreditLineEmitter emitter = new CreditLineEmitter(50.0);
        UsageLine line = usage(JAN_15, MAR_15);
        List<UsageLine> credits = emitter.emit(Stream.of(line), fixedCostSynthesizer(300.0)).toList();
        assertThat(credits, hasSize(3));
        for (UsageLine credit : credits) {
            assertThat(credit.recordType(), equalTo(UsageLine.RECORD_TYPE_CREDIT));
            assertThat(credit.region(), equalTo("us-east-1"));
        }
        // Each month has share > $50 of $300 (Jan ~$87, Feb ~$144, Mar ~$72), so each
        // credit caps at the configured -$50.
        for (UsageLine credit : credits) {
            assertThat(credit.quantity(), equalTo(-50.0));
        }
        // Windows should align to the three calendar months.
        List<YearMonth> months = credits.stream()
                .map(c -> YearMonth.from(c.periodStart().atOffset(ZoneOffset.UTC)))
                .toList();
        assertThat(months, hasItem(YearMonth.of(2026, 1)));
        assertThat(months, hasItem(YearMonth.of(2026, 2)));
        assertThat(months, hasItem(YearMonth.of(2026, 3)));
    }

    @Test
    void multiMonthLine_smallTotalCapsCreditAtUsageCost() {
        // Line spans Jan 15 -> Mar 15, but total cost is only $9. Per-month share
        // is well under $50, so the credit caps at the monthly usage cost rather
        // than the configured monthly limit.
        CreditLineEmitter emitter = new CreditLineEmitter(50.0);
        UsageLine line = usage(JAN_15, MAR_15);
        List<UsageLine> credits = emitter.emit(Stream.of(line), fixedCostSynthesizer(9.0)).toList();
        assertThat(credits, hasSize(3));
        double total = 0;
        for (UsageLine c : credits) {
            // Each absolute credit is < $50.
            assertThat(Math.abs(c.quantity()) <= 50.0, equalTo(true));
            total += -c.quantity();
        }
        // Sum of credits == sum of usage cost (since credit per month >= monthly cost).
        assertThat(total, closeTo(9.0, 1e-6));
    }

    @Test
    void zeroConfiguredCredit_emitsNothing() {
        CreditLineEmitter emitter = new CreditLineEmitter(0.0);
        UsageLine line = usage(JAN_15, MAR_15);
        List<UsageLine> credits = emitter.emit(Stream.of(line), fixedCostSynthesizer(300.0)).toList();
        assertThat(credits, empty());
    }

    @Test
    void zeroCostUsage_emitsNothing() {
        CreditLineEmitter emitter = new CreditLineEmitter(50.0);
        UsageLine line = usage(JAN_15, MAR_15);
        List<UsageLine> credits = emitter.emit(Stream.of(line), fixedCostSynthesizer(0.0)).toList();
        assertThat(credits, empty());
    }
}
