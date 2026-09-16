package io.github.hectorvent.floci.services.bcmpricingcalculator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.bcmpricingcalculator.model.WorkloadEstimate;
import io.github.hectorvent.floci.services.bcmpricingcalculator.model.WorkloadEstimateUsage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class BcmPricingCalculatorJsonHandler {
    private final BcmPricingCalculatorService service;
    private final ObjectMapper objectMapper;

    @Inject
    public BcmPricingCalculatorJsonHandler(BcmPricingCalculatorService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        try {
            return switch (action) {
                case "CreateWorkloadEstimate" -> create(request);
                case "BatchCreateWorkloadEstimateUsage" -> batchCreateUsage(request);
                case "GetWorkloadEstimate" -> get(request);
                case "DeleteWorkloadEstimate" -> delete(request);
                default -> Response.status(400).entity(new AwsErrorResponse(
                        "UnknownOperationException", "Unknown operation: AWSBCMPricingCalculator." + action)).build();
            };
        } catch (AwsException e) {
            throw e;
        }
    }

    private Response create(JsonNode request) {
        WorkloadEstimate estimate = service.create(text(request, "name"), text(request, "clientToken"),
                text(request, "rateType"), stringMap(request.path("tags")));
        return Response.ok(estimateNode(estimate)).build();
    }

    private Response batchCreateUsage(JsonNode request) {
        List<BcmPricingCalculatorService.UsageInput> entries = new ArrayList<>();
        JsonNode usage = request.path("usage");
        if (usage.isArray()) {
            for (JsonNode item : usage) {
                entries.add(new BcmPricingCalculatorService.UsageInput(
                        text(item, "serviceCode"), text(item, "usageType"), text(item, "operation"),
                        text(item, "key"), text(item, "usageAccountId"), text(item, "group"),
                        item.path("amount").isNumber() ? item.path("amount").asDouble() : Double.NaN));
            }
        }
        BcmPricingCalculatorService.UsageBatchResult result = service.batchCreateUsage(
                text(request, "workloadEstimateId"), entries, text(request, "clientToken"));
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("items");
        result.items().forEach(item -> items.add(usageNode(item)));
        ArrayNode errors = out.putArray("errors");
        result.errors().forEach(error -> {
            ObjectNode node = errors.addObject();
            if (error.key() != null) node.put("key", error.key());
            node.put("errorCode", error.errorCode());
            node.put("errorMessage", error.errorMessage());
        });
        return Response.ok(out).build();
    }

    private Response get(JsonNode request) {
        return Response.ok(estimateNode(service.get(text(request, "identifier")))).build();
    }

    private Response delete(JsonNode request) {
        service.delete(text(request, "identifier"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode estimateNode(WorkloadEstimate e) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("id", e.getId());
        out.put("name", e.getName());
        out.put("createdAt", epochSeconds(e.getCreatedAt()));
        out.put("expiresAt", epochSeconds(e.getExpiresAt()));
        out.put("rateType", e.getRateType());
        out.put("rateTimestamp", epochSeconds(e.getRateTimestamp()));
        out.put("status", e.getStatus());
        out.put("totalCost", e.getTotalCost());
        out.put("costCurrency", e.getCostCurrency());
        return out;
    }

    private ObjectNode usageNode(WorkloadEstimateUsage u) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("serviceCode", u.getServiceCode());
        out.put("usageType", u.getUsageType());
        out.put("operation", u.getOperation());
        if (u.getLocation() != null) out.put("location", u.getLocation());
        out.put("id", u.getId());
        out.put("usageAccountId", u.getUsageAccountId());
        if (u.getGroup() != null) out.put("group", u.getGroup());
        ObjectNode quantity = out.putObject("quantity");
        quantity.put("unit", u.getUnit());
        quantity.put("amount", u.getAmount());
        out.put("cost", u.getCost());
        out.put("currency", u.getCurrency());
        out.put("status", u.getStatus());
        out.put("key", u.getKey());
        return out;
    }

    private static double epochSeconds(java.time.Instant instant) {
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asText()));
        return out;
    }
}
