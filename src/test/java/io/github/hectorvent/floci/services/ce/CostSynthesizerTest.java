package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.core.common.UsageLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

/**
 * Unit tests for {@link CostSynthesizer} — focused on rate application and
 * the special-case treatment of USD-denominated record types
 * (Credit / Refund / Tax / SavingsPlan upfront / SavingsPlan recurring).
 */
class CostSynthesizerTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-01-02T00:00:00Z");

    private CostSynthesizer synthesizer;

    @BeforeEach
    void setUp() {
        // Hand-rolled stub so the test stays a pure unit test (no Pricing snapshot
        // load on the classpath, no Quarkus boot).
        PricingRateLookup stubLookup = new PricingRateLookup(null, null) {
            @Override
            public Map<String, Double> ratesFor(String serviceCode, String regionCode) {
                if ("AmazonEC2".equals(serviceCode)) {
                    return Map.of("BoxUsage:t3.micro", 0.0104);
                }
                return Map.of();
            }
        };
        synthesizer = new CostSynthesizer(stubLookup);
    }

    @Test
    void usageRecordType_multipliesQuantityByRate() {
        UsageLine line = new UsageLine(START, END,
                "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                UsageLine.RECORD_TYPE_USAGE,
                "000000000000", "i-test", Map.of(),
                24.0, "Hrs");
        Map<String, CostSynthesizer.MetricValue> metrics = synthesizer.compute(line);

        assertThat(metrics.get("UnblendedCost").amount(), closeTo(24.0 * 0.0104, 1e-9));
        assertThat(metrics.get("UnblendedCost").unit(), equalTo("USD"));
        assertThat(metrics.get("UsageQuantity").amount(), equalTo(24.0));
        assertThat(metrics.get("UsageQuantity").unit(), equalTo("Hrs"));
    }

    @Test
    void creditRecordType_costEqualsQuantity() {
        // Credit lines arrive denominated in USD already and must NOT be passed
        // through the rate lookup (which has no entry for Credits/Credit-Promotional).
        UsageLine credit = new UsageLine(START, END,
                "Credits", "us-east-1", "Credit-Promotional", "ApplyPromoCredit",
                UsageLine.RECORD_TYPE_CREDIT,
                "000000000000", null, Map.of(),
                -100.0, "USD");
        Map<String, CostSynthesizer.MetricValue> metrics = synthesizer.compute(credit);

        assertThat(metrics.get("UnblendedCost").amount(), equalTo(-100.0));
        assertThat(metrics.get("UnblendedCost").unit(), equalTo("USD"));
        assertThat(metrics.get("BlendedCost").amount(), equalTo(-100.0));
        assertThat(metrics.get("AmortizedCost").amount(), equalTo(-100.0));
        assertThat(metrics.get("NetUnblendedCost").amount(), equalTo(-100.0));
        assertThat(metrics.get("NetAmortizedCost").amount(), equalTo(-100.0));
        assertThat(metrics.get("UsageQuantity").amount(), equalTo(0.0));
    }

    @Test
    void taxRecordType_costEqualsQuantity() {
        UsageLine tax = new UsageLine(START, END,
                "Tax", "us-east-1", "Tax", "Tax",
                UsageLine.RECORD_TYPE_TAX,
                "000000000000", null, Map.of(),
                7.50, "USD");
        Map<String, CostSynthesizer.MetricValue> metrics = synthesizer.compute(tax);
        assertThat(metrics.get("UnblendedCost").amount(), equalTo(7.50));
    }

    @Test
    void usageRecordType_unknownRateProducesZeroCost() {
        UsageLine line = new UsageLine(START, END,
                "AmazonRDS", "us-east-1", "InstanceUsage:db.t3.micro", "CreateDBInstance",
                UsageLine.RECORD_TYPE_USAGE,
                "000000000000", null, Map.of(),
                24.0, "Hrs");
        Map<String, CostSynthesizer.MetricValue> metrics = synthesizer.compute(line);
        assertThat(metrics.get("UnblendedCost").amount(), equalTo(0.0));
        assertThat(metrics.get("UsageQuantity").amount(), equalTo(24.0));
    }
}
