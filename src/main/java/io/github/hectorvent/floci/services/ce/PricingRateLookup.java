package io.github.hectorvent.floci.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.pricing.PricingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks up unit prices from the bundled {@link PricingService} snapshot.
 * Used by {@link CostSynthesizer} to attach a USD rate to each
 * {@link io.github.hectorvent.floci.core.common.UsageLine}.
 *
 * <p>Maps a {@code (serviceCode, regionCode, usageType)} key to {@code USD per unit}.
 * Cache is sized per {@code (serviceCode, regionCode)} pair on first hit and reused
 * for the lifetime of the request — the snapshot itself is read-only.
 */
@ApplicationScoped
public class PricingRateLookup {

    private static final Logger LOG = Logger.getLogger(PricingRateLookup.class);

    private final PricingService pricing;
    private final ObjectMapper objectMapper;

    @Inject
    public PricingRateLookup(PricingService pricing, ObjectMapper objectMapper) {
        this.pricing = pricing;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns USD-per-unit for {@code usageType} under {@code (serviceCode, regionCode)}.
     * Returns {@code 0.0} when no matching offer exists in the snapshot.
     */
    public double rate(String serviceCode, String regionCode, String usageType) {
        return ratesFor(serviceCode, regionCode).getOrDefault(usageType, 0.0);
    }

    /** Returns the full {@code usageType -> USD/unit} map for a (service, region) pair. */
    public Map<String, Double> ratesFor(String serviceCode, String regionCode) {
        Map<String, Double> result = new HashMap<>();
        try {
            ObjectNode response = pricing.getProducts(
                    serviceCode,
                    List.of(new PricingService.FilterSpec("TERM_MATCH", "regionCode", regionCode)),
                    null,
                    null,
                    1000);
            JsonNode priceList = response.path("PriceList");
            if (!priceList.isArray()) {
                return result;
            }
            for (JsonNode entry : priceList) {
                JsonNode product;
                try {
                    product = objectMapper.readTree(entry.asText());
                } catch (Exception e) {
                    continue;
                }
                String usageType = product.path("product").path("attributes").path("usagetype").asText(null);
                if (usageType == null || usageType.isEmpty()) {
                    // Fallback for user-supplied snapshots that don't include
                    // a usagetype attribute. The bundled fixtures populate it
                    // directly, so this path applies only to externally provided
                    // snapshots via FLOCI_SERVICES_PRICING_SNAPSHOT_PATH.
                    usageType = synthesizeUsageType(product, serviceCode);
                }
                if (usageType == null) {
                    continue;
                }
                double rate = extractOnDemandRate(product.path("terms"));
                if (rate > 0) {
                    result.merge(usageType, rate, Double::max);
                }
            }
        } catch (Exception e) {
            LOG.warnv(e, "Pricing rate lookup failed for {0}/{1}", serviceCode, regionCode);
        }
        return result;
    }

    /**
     * Returns a synthesized usage-type key for a product offer that doesn't carry
     * the {@code usagetype} attribute. Compatibility-only fallback for incomplete
     * external snapshots; bundled fixtures populate {@code usagetype} directly,
     * and future enumerators should emit AWS-native usage types that match the
     * snapshot's real {@code usagetype} attribute rather than relying on this
     * synthesis path.
     */
    private static String synthesizeUsageType(JsonNode product, String serviceCode) {
        JsonNode attrs = product.path("product").path("attributes");
        return switch (serviceCode) {
            case "AmazonEC2" -> "BoxUsage:" + attrs.path("instanceType").asText("");
            case "AmazonS3" -> "TimedStorage-" + attrs.path("volumeType").asText("");
            case "AWSLambda" -> attrs.path("group").asText("AWS-Lambda-Requests");
            default -> null;
        };
    }

    private static double extractOnDemandRate(JsonNode terms) {
        JsonNode onDemand = terms.path("OnDemand");
        if (!onDemand.isObject()) {
            return 0.0;
        }
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> termIter = onDemand.fields();
        while (termIter.hasNext()) {
            JsonNode dimensions = termIter.next().getValue().path("priceDimensions");
            if (!dimensions.isObject()) {
                continue;
            }
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> dimIter = dimensions.fields();
            while (dimIter.hasNext()) {
                JsonNode dim = dimIter.next().getValue();
                String usd = dim.path("pricePerUnit").path("USD").asText("0");
                try {
                    double v = Double.parseDouble(usd);
                    if (v > 0) {
                        return v;
                    }
                } catch (NumberFormatException ignored) {
                    // skip malformed entries
                }
            }
        }
        return 0.0;
    }
}
