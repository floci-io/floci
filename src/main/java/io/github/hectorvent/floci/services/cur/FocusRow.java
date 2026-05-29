package io.github.hectorvent.floci.services.cur;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * One row of a Cost and Usage Report following the FOCUS 1.2 / CUR 2.0
 * column shape. Field order is fixed via {@link JsonPropertyOrder} so the
 * NDJSON staging output that DuckDB reads is deterministic.
 *
 * @see <a href="https://focus.finops.org/">FOCUS specification</a>
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "BillingPeriodStart", "BillingPeriodEnd",
        "ChargePeriodStart", "ChargePeriodEnd",
        "BillingAccountId", "SubAccountId",
        "ServiceCategory", "ServiceName",
        "Region",
        "ResourceId", "ResourceName", "ResourceType",
        "ChargeCategory", "ChargeClass", "ChargeDescription",
        "ChargeFrequency", "ChargeSubcategory",
        "PricingQuantity", "PricingUnit",
        "UsageQuantity", "UsageUnit",
        "BilledCost", "EffectiveCost", "ListCost", "ContractedCost",
        "BillingCurrency",
        "Tags"
})
public record FocusRow(
        @JsonProperty("BillingPeriodStart") String billingPeriodStart,
        @JsonProperty("BillingPeriodEnd") String billingPeriodEnd,
        @JsonProperty("ChargePeriodStart") String chargePeriodStart,
        @JsonProperty("ChargePeriodEnd") String chargePeriodEnd,
        @JsonProperty("BillingAccountId") String billingAccountId,
        @JsonProperty("SubAccountId") String subAccountId,
        @JsonProperty("ServiceCategory") String serviceCategory,
        @JsonProperty("ServiceName") String serviceName,
        @JsonProperty("Region") String region,
        @JsonProperty("ResourceId") String resourceId,
        @JsonProperty("ResourceName") String resourceName,
        @JsonProperty("ResourceType") String resourceType,
        @JsonProperty("ChargeCategory") String chargeCategory,
        @JsonProperty("ChargeClass") String chargeClass,
        @JsonProperty("ChargeDescription") String chargeDescription,
        @JsonProperty("ChargeFrequency") String chargeFrequency,
        @JsonProperty("ChargeSubcategory") String chargeSubcategory,
        @JsonProperty("PricingQuantity") double pricingQuantity,
        @JsonProperty("PricingUnit") String pricingUnit,
        @JsonProperty("UsageQuantity") double usageQuantity,
        @JsonProperty("UsageUnit") String usageUnit,
        @JsonProperty("BilledCost") double billedCost,
        @JsonProperty("EffectiveCost") double effectiveCost,
        @JsonProperty("ListCost") double listCost,
        @JsonProperty("ContractedCost") double contractedCost,
        @JsonProperty("BillingCurrency") String billingCurrency,
        @JsonProperty("Tags") Map<String, String> tags) {
}
