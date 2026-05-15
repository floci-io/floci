package io.github.hectorvent.floci.services.cur;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.UsageLine;
import io.github.hectorvent.floci.services.ce.PricingRateLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Pure unit tests for {@link FocusRowProjector} — no Quarkus boot, no HTTP,
 * no DuckDB, no S3.
 */
class FocusRowProjectorTest {

    private static final Instant JAN_15 = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant JAN_16 = Instant.parse("2026-01-16T00:00:00Z");

    private FocusRowProjector projector;

    @BeforeEach
    void setUp() {
        // Stub rate lookup that knows about exactly one usage type.
        PricingRateLookup stubLookup = new PricingRateLookup(null, null) {
            @Override
            public Map<String, Double> ratesFor(String serviceCode, String regionCode) {
                if ("AmazonEC2".equals(serviceCode) && "us-east-1".equals(regionCode)) {
                    return Map.of("BoxUsage:t3.micro", 0.0104);
                }
                return Map.of();
            }
        };
        projector = new FocusRowProjector(stubLookup);
    }

    @Test
    void usageRow_costEqualsQuantityTimesRate() {
        UsageLine line = new UsageLine(JAN_15, JAN_16,
                "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                UsageLine.RECORD_TYPE_USAGE,
                "111122223333", "i-test", Map.of("Owner", "team-a"),
                24.0, "Hrs");

        FocusRow row = projector.project(List.of(line)).get(0);

        assertThat(row.serviceName(), equalTo("AmazonEC2"));
        assertThat(row.serviceCategory(), equalTo("Compute"));
        assertThat(row.region(), equalTo("us-east-1"));
        assertThat(row.resourceId(), equalTo("i-test"));
        assertThat(row.resourceType(), equalTo("Instance"));
        assertThat(row.chargeCategory(), equalTo("Usage"));
        assertThat(row.chargeFrequency(), equalTo("Usage-Based"));
        assertThat(row.chargeSubcategory(), equalTo("BoxUsage:t3.micro"));
        assertThat(row.usageQuantity(), closeTo(24.0, 1e-9));
        assertThat(row.usageUnit(), equalTo("Hrs"));
        assertThat(row.billedCost(), closeTo(24.0 * 0.0104, 1e-9));
        assertThat(row.effectiveCost(), closeTo(24.0 * 0.0104, 1e-9));
        assertThat(row.listCost(), closeTo(24.0 * 0.0104, 1e-9));
        assertThat(row.contractedCost(), closeTo(24.0 * 0.0104, 1e-9));
        assertThat(row.billingCurrency(), equalTo("USD"));
        assertThat(row.billingAccountId(), equalTo("111122223333"));
        assertThat(row.subAccountId(), equalTo("111122223333"));
    }

    @Test
    void creditRow_billedCostEqualsQuantity_negativeAllowed() {
        // Credits arrive with quantity already in USD; projector must NOT multiply
        // by a per-unit rate (there's none for service "Credits").
        UsageLine credit = new UsageLine(JAN_15, JAN_16,
                "Credits", "us-east-1", "Credit-Promotional", "ApplyPromoCredit",
                UsageLine.RECORD_TYPE_CREDIT,
                "000000000000", null, Map.of(),
                -100.0, "USD");

        FocusRow row = projector.projectOne(credit, new HashMap<>());

        assertThat(row.chargeCategory(), equalTo("Credit"));
        assertThat(row.billedCost(), equalTo(-100.0));
        assertThat(row.effectiveCost(), equalTo(-100.0));
        assertThat(row.usageQuantity(), equalTo(-100.0));
        assertThat(row.usageUnit(), equalTo("USD"));
    }

    @Test
    void taxAndRefund_areUsdDenominated() {
        UsageLine tax = new UsageLine(JAN_15, JAN_16,
                "Tax", "us-east-1", "Tax", "Tax",
                UsageLine.RECORD_TYPE_TAX,
                "000000000000", null, Map.of(),
                7.50, "USD");
        FocusRow taxRow = projector.projectOne(tax, new HashMap<>());
        assertThat(taxRow.chargeCategory(), equalTo("Tax"));
        assertThat(taxRow.billedCost(), equalTo(7.50));

        UsageLine refund = new UsageLine(JAN_15, JAN_16,
                "Refund", "us-east-1", "Refund", "Refund",
                UsageLine.RECORD_TYPE_REFUND,
                "000000000000", null, Map.of(),
                -12.0, "USD");
        FocusRow refundRow = projector.projectOne(refund, new HashMap<>());
        assertThat(refundRow.chargeCategory(), equalTo("Refund"));
        assertThat(refundRow.billedCost(), equalTo(-12.0));
    }

    @Test
    void missingResourceId_setsNullResourceFields() {
        UsageLine line = new UsageLine(JAN_15, JAN_16,
                "AmazonRDS", "us-east-1", "InstanceUsage:db.t3.micro", "CreateDBInstance",
                UsageLine.RECORD_TYPE_USAGE,
                "111122223333", null, Map.of(),
                24.0, "Hrs");

        FocusRow row = projector.projectOne(line, new HashMap<>());

        assertThat(row.resourceId(), nullValue());
        assertThat(row.resourceName(), nullValue());
        assertThat(row.resourceType(), equalTo("DBInstance"));
        // No rate registered for AmazonRDS, so cost should be zero (not throw).
        assertThat(row.billedCost(), equalTo(0.0));
    }

    @Test
    void tagsAreSerializedAsJsonObject() throws Exception {
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("Owner", "team-a");
        tags.put("CostCenter", "1234");

        UsageLine line = new UsageLine(JAN_15, JAN_16,
                "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                UsageLine.RECORD_TYPE_USAGE,
                "111122223333", "i-tagged", tags,
                24.0, "Hrs");

        FocusRow row = projector.projectOne(line, new HashMap<>());
        assertThat(row.tags(), aMapWithSize(2));
        assertThat(row.tags(), hasEntry("Owner", "team-a"));
        assertThat(row.tags(), hasEntry("CostCenter", "1234"));

        // Round-trip through Jackson to verify the FocusRow shape Jackson emits is
        // a plain JSON object (which is what DuckDB's read_json_auto expects).
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(row);
        assertThat(json.contains("\"Tags\":{\"Owner\":\"team-a\",\"CostCenter\":\"1234\"}"),
                equalTo(true));
    }

    @Test
    void billingPeriodAlignsToCalendarMonth() {
        UsageLine line = new UsageLine(
                Instant.parse("2026-03-17T13:30:00Z"),
                Instant.parse("2026-03-18T13:30:00Z"),
                "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                UsageLine.RECORD_TYPE_USAGE,
                "111122223333", "i-billing", Map.of(),
                24.0, "Hrs");
        FocusRow row = projector.projectOne(line, new HashMap<>());

        // Billing period must always be first-of-month → first-of-next-month.
        assertThat(row.billingPeriodStart(), equalTo("2026-03-01T00:00:00Z"));
        assertThat(row.billingPeriodEnd(), equalTo("2026-04-01T00:00:00Z"));

        // Charge period preserves the line's exact window.
        assertThat(row.chargePeriodStart(), equalTo("2026-03-17T13:30:00Z"));
        assertThat(row.chargePeriodEnd(), equalTo("2026-03-18T13:30:00Z"));
    }

    @Test
    void regionAndAccountFlowThrough() {
        UsageLine line = new UsageLine(JAN_15, JAN_16,
                "AmazonS3", "eu-west-1", "TimedStorage-Standard", "StandardStorage",
                UsageLine.RECORD_TYPE_USAGE,
                "999988887777", "arn:aws:s3:::my-bucket", Map.of(),
                10.0, "GB-Mo");
        FocusRow row = projector.projectOne(line, new HashMap<>());

        assertThat(row.region(), equalTo("eu-west-1"));
        assertThat(row.billingAccountId(), equalTo("999988887777"));
        assertThat(row.subAccountId(), equalTo("999988887777"));
        assertThat(row.serviceCategory(), equalTo("Storage"));
        assertThat(row.resourceType(), equalTo("Bucket"));
        assertThat(row.resourceId(), equalTo("arn:aws:s3:::my-bucket"));
    }

    @Test
    void emptyTagsMapIsEmittedNotNull() {
        UsageLine line = new UsageLine(JAN_15, JAN_16,
                "AWSLambda", "us-east-1", "AWS-Lambda-Requests", "Invoke",
                UsageLine.RECORD_TYPE_USAGE,
                "000000000000", null, null,
                0.0, "Requests");
        FocusRow row = projector.projectOne(line, new HashMap<>());

        // Null tags input becomes empty map (Jackson omits null but we want {} for DuckDB).
        assertThat(row.tags(), notNullValue());
        assertThat(row.tags().isEmpty(), equalTo(true));
    }

    @Test
    void rateCacheIsReusedAcrossLines() {
        // Two lines with same (service, region) — only one ratesFor() call.
        int[] callCount = {0};
        PricingRateLookup countingLookup = new PricingRateLookup(null, null) {
            @Override
            public Map<String, Double> ratesFor(String serviceCode, String regionCode) {
                callCount[0]++;
                return Map.of("BoxUsage:t3.micro", 0.0104);
            }
        };
        FocusRowProjector p = new FocusRowProjector(countingLookup);

        UsageLine line1 = new UsageLine(JAN_15, JAN_16,
                "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                UsageLine.RECORD_TYPE_USAGE, "000", "i1", Map.of(), 1.0, "Hrs");
        UsageLine line2 = new UsageLine(JAN_15, JAN_16,
                "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                UsageLine.RECORD_TYPE_USAGE, "000", "i2", Map.of(), 2.0, "Hrs");

        p.project(List.of(line1, line2));
        assertThat(callCount[0], equalTo(1));
    }
}
