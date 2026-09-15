package io.github.hectorvent.floci.services.bcmpricingcalculator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bcmpricingcalculator.model.WorkloadEstimate;
import io.github.hectorvent.floci.services.bcmpricingcalculator.model.WorkloadEstimateUsage;
import io.github.hectorvent.floci.services.pricing.PricingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class BcmPricingCalculatorService {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9-]+");
    private static final Pattern ACCOUNT = Pattern.compile("\\d{12}");
    private static final List<String> RATE_TYPES = List.of(
            "BEFORE_DISCOUNTS", "AFTER_DISCOUNTS", "AFTER_DISCOUNTS_AND_COMMITMENTS");

    public record UsageInput(String serviceCode, String usageType, String operation, String key,
                             String usageAccountId, String group, double amount) {}
    public record UsageError(String key, String errorCode, String errorMessage) {}
    public record UsageBatchResult(List<WorkloadEstimateUsage> items, List<UsageError> errors) {}

    private final StorageBackend<String, WorkloadEstimate> estimates;
    private final StorageBackend<String, WorkloadEstimateUsage> usages;
    private final RegionResolver regionResolver;
    private final PricingService pricingService;
    private final ObjectMapper objectMapper;

    @Inject
    public BcmPricingCalculatorService(StorageFactory storageFactory, RegionResolver regionResolver,
                                       PricingService pricingService, ObjectMapper objectMapper) {
        this(storageFactory.create("bcmpricingcalculator", "workload-estimates.json",
                        new TypeReference<Map<String, WorkloadEstimate>>() {}),
                storageFactory.create("bcmpricingcalculator", "workload-estimate-usages.json",
                        new TypeReference<Map<String, WorkloadEstimateUsage>>() {}),
                regionResolver, pricingService, objectMapper);
    }

    BcmPricingCalculatorService(StorageBackend<String, WorkloadEstimate> estimates,
                                StorageBackend<String, WorkloadEstimateUsage> usages,
                                RegionResolver regionResolver, PricingService pricingService,
                                ObjectMapper objectMapper) {
        this.estimates = estimates;
        this.usages = usages;
        this.regionResolver = regionResolver;
        this.pricingService = pricingService;
        this.objectMapper = objectMapper;
    }

    public synchronized WorkloadEstimate create(String name, String clientToken, String rateType, Map<String, String> tags) {
        if (name == null) throw validation("name is required.");
        if (name.length() > 64 || !NAME.matcher(name).matches()) throw validation("name is invalid.");
        validateToken(clientToken);
        String effectiveRate = rateType == null || rateType.isBlank() ? "BEFORE_DISCOUNTS" : rateType;
        if (!RATE_TYPES.contains(effectiveRate)) throw validation("rateType is invalid.");
        if (tags != null && tags.size() > 200) throw validation("tags exceeds the maximum size of 200.");

        if (clientToken != null) {
            WorkloadEstimate replay = estimates.scan(_ -> true).stream()
                    .filter(e -> clientToken.equals(e.getClientToken())).findFirst().orElse(null);
            if (replay != null) {
                if (!replay.getName().equals(name) || !replay.getRateType().equals(effectiveRate)) {
                    throw new AwsException("ConflictException", "The idempotency token was reused with different parameters.", 400,
                            Map.of("resourceId", replay.getId(), "resourceType", "WorkloadEstimate"));
                }
                return replay;
            }
        }

        Instant now = Instant.now();
        WorkloadEstimate estimate = new WorkloadEstimate();
        estimate.setId(UUID.randomUUID().toString());
        estimate.setName(name);
        estimate.setOwnerAccountId(regionResolver.getAccountId());
        estimate.setRateType(effectiveRate);
        estimate.setStatus("VALID");
        estimate.setTotalCost(0.0);
        estimate.setCostCurrency("USD");
        estimate.setCreatedAt(now);
        estimate.setRateTimestamp(now);
        estimate.setExpiresAt(now.plus(30, ChronoUnit.DAYS));
        estimate.setClientToken(clientToken);
        estimate.setTags(tags);
        estimates.put(estimate.getId(), estimate);
        return estimate;
    }

    public WorkloadEstimate get(String id) {
        validateId(id);
        return estimates.get(id).orElseThrow(() -> notFound(id));
    }

    public void delete(String id) {
        validateId(id);
        if (estimates.get(id).isEmpty()) return;
        estimates.delete(id);
        usages.keys().stream().filter(k -> k.startsWith(id + "/")).toList().forEach(usages::delete);
    }

    public synchronized UsageBatchResult batchCreateUsage(String estimateId, List<UsageInput> entries, String clientToken) {
        WorkloadEstimate estimate = get(estimateId);
        validateToken(clientToken);
        if (entries == null || entries.isEmpty() || entries.size() > 25) {
            throw validation("usage must contain between 1 and 25 entries.");
        }
        List<WorkloadEstimateUsage> items = new ArrayList<>();
        List<UsageError> errors = new ArrayList<>();
        for (UsageInput input : entries) {
            try {
                validateUsage(input);
                String key = estimateId + "/" + input.key();
                if (usages.get(key).isPresent()) {
                    errors.add(new UsageError(input.key(), "CONFLICT", "A usage entry with this key already exists."));
                    continue;
                }
                PricingMatch match = resolvePrice(input);
                WorkloadEstimateUsage usage = new WorkloadEstimateUsage();
                usage.setId(UUID.randomUUID().toString());
                usage.setWorkloadEstimateId(estimateId);
                usage.setKey(input.key());
                usage.setServiceCode(input.serviceCode());
                usage.setUsageType(input.usageType());
                usage.setOperation(input.operation());
                usage.setUsageAccountId(input.usageAccountId());
                usage.setGroup(input.group());
                usage.setAmount(input.amount());
                usage.setUnit(match.unit());
                usage.setCost(roundMoney(input.amount() * match.rate()));
                usage.setCurrency("USD");
                usage.setStatus("VALID");
                usage.setLocation(match.location());
                usages.put(key, usage);
                items.add(usage);
            } catch (AwsException e) {
                errors.add(new UsageError(input == null ? null : input.key(), "BAD_REQUEST", e.getMessage()));
            }
        }
        recompute(estimate);
        return new UsageBatchResult(items, errors);
    }

    public List<WorkloadEstimateUsage> listUsage(String estimateId) {
        get(estimateId);
        return usages.scan(k -> k.startsWith(estimateId + "/")).stream()
                .sorted(java.util.Comparator.comparing(WorkloadEstimateUsage::getKey)).toList();
    }

    private void recompute(WorkloadEstimate estimate) {
        double total = listUsageWithoutLookup(estimate.getId()).stream().mapToDouble(WorkloadEstimateUsage::getCost).sum();
        estimate.setTotalCost(roundMoney(total));
        estimate.setStatus("VALID");
        estimate.setRateTimestamp(Instant.now());
        estimates.put(estimate.getId(), estimate);
    }

    private List<WorkloadEstimateUsage> listUsageWithoutLookup(String estimateId) {
        return usages.scan(k -> k.startsWith(estimateId + "/"));
    }

    private PricingMatch resolvePrice(UsageInput input) {
        List<PricingService.FilterSpec> filters = new ArrayList<>();
        filters.add(new PricingService.FilterSpec("TERM_MATCH", "usagetype", input.usageType()));
        if (input.operation() != null && !input.operation().isBlank()) {
            filters.add(new PricingService.FilterSpec("TERM_MATCH", "operation", input.operation()));
        }
        ObjectNode response = pricingService.getProducts(input.serviceCode(), filters, null, null, 10);
        JsonNode prices = response.path("PriceList");
        if (!prices.isArray() || prices.size() != 1) {
            throw validation("Pricing data is unavailable for the supplied serviceCode, usageType, and operation.");
        }
        try {
            JsonNode product = objectMapper.readTree(prices.get(0).asText());
            JsonNode attrs = product.path("product").path("attributes");
            String location = attrs.path("location").asText(null);
            JsonNode onDemand = product.path("terms").path("OnDemand");
            JsonNode term = onDemand.elements().hasNext() ? onDemand.elements().next() : null;
            JsonNode dimensions = term == null ? null : term.path("priceDimensions");
            JsonNode dimension = dimensions != null && dimensions.elements().hasNext() ? dimensions.elements().next() : null;
            if (dimension == null) throw new IllegalArgumentException();
            double rate = Double.parseDouble(dimension.path("pricePerUnit").path("USD").asText());
            return new PricingMatch(rate, dimension.path("unit").asText("Usage"), location);
        } catch (Exception e) {
            throw validation("Pricing data is unavailable for the supplied usage.");
        }
    }

    private static void validateUsage(UsageInput input) {
        if (input == null) throw validation("usage entry is required.");
        if (input.serviceCode() == null || input.usageType() == null || input.operation() == null
                || input.key() == null || input.usageAccountId() == null) throw validation("usage entry is missing a required field.");
        if (input.key().length() > 10 || !input.key().matches("[A-Za-z0-9]*")) throw validation("key is invalid.");
        if (!ACCOUNT.matcher(input.usageAccountId()).matches()) throw validation("usageAccountId is invalid.");
        if (!Double.isFinite(input.amount()) || input.amount() < 0) throw validation("amount is invalid.");
    }

    private static void validateToken(String token) {
        if (token != null && (token.isEmpty() || token.length() > 64 || !token.matches("[\\u0021-\\u007E]+"))) {
            throw validation("clientToken is invalid.");
        }
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw validation("identifier is invalid.");
        }
    }

    private static double roundMoney(double value) { return Math.round(value * 100_000_000d) / 100_000_000d; }
    private static AwsException validation(String message) { return new AwsException("ValidationException", message, 400); }
    private static AwsException notFound(String id) { return new AwsException("ResourceNotFoundException", "Workload estimate " + id + " was not found.", 400,
            Map.of("resourceId", id, "resourceType", "WorkloadEstimate")); }
    private record PricingMatch(double rate, String unit, String location) {}
}
