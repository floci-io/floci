package io.github.hectorvent.floci.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.UsageLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates per-bucket usage lines into the AWS Cost Explorer
 * {@code ResultsByTime[].Groups} / {@code ResultsByTime[].Total} shape.
 */
final class GroupAggregator {

    private final ObjectMapper objectMapper;

    GroupAggregator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Builds a single {@code ResultByTime} entry covering {@code [bucketStart, bucketEnd)}
     * from the provided lines (already filtered + costed).
     */
    ObjectNode buildResultByTime(java.time.Instant bucketStart, java.time.Instant bucketEnd,
                                  List<UsageLine> lines, List<GroupBy> groupBys, Set<String> metrics,
                                  CostSynthesizer synthesizer) {
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode timePeriod = result.putObject("TimePeriod");
        timePeriod.put("Start", bucketStart.toString());
        timePeriod.put("End", bucketEnd.toString());

        // Group key -> metric name -> running total
        Map<List<String>, Map<String, Double>> groupAmounts = new LinkedHashMap<>();
        Map<List<String>, Map<String, String>> groupUnits = new HashMap<>();
        Map<String, Double> totalAmounts = new HashMap<>();
        Map<String, String> totalUnits = new HashMap<>();

        for (UsageLine line : lines) {
            Map<String, CostSynthesizer.MetricValue> computed = synthesizer.compute(line);
            for (String metric : metrics) {
                CostSynthesizer.MetricValue mv = computed.get(metric);
                if (mv == null) {
                    continue;
                }
                totalAmounts.merge(metric, mv.amount(), Double::sum);
                totalUnits.putIfAbsent(metric, mv.unit() == null ? "" : mv.unit());
                if (!groupBys.isEmpty()) {
                    List<String> key = groupKey(groupBys, line);
                    groupAmounts.computeIfAbsent(key, k -> new HashMap<>())
                            .merge(metric, mv.amount(), Double::sum);
                    groupUnits.computeIfAbsent(key, k -> new HashMap<>())
                            .putIfAbsent(metric, mv.unit() == null ? "" : mv.unit());
                }
            }
        }

        ObjectNode totalNode = result.putObject("Total");
        for (Map.Entry<String, Double> entry : totalAmounts.entrySet()) {
            ObjectNode m = totalNode.putObject(entry.getKey());
            m.put("Amount", formatAmount(entry.getValue()));
            m.put("Unit", totalUnits.getOrDefault(entry.getKey(), ""));
        }

        ArrayNode groups = result.putArray("Groups");
        for (Map.Entry<List<String>, Map<String, Double>> entry : groupAmounts.entrySet()) {
            ObjectNode group = groups.addObject();
            ArrayNode keys = group.putArray("Keys");
            for (String k : entry.getKey()) {
                keys.add(k);
            }
            ObjectNode m = group.putObject("Metrics");
            for (Map.Entry<String, Double> me : entry.getValue().entrySet()) {
                ObjectNode mv = m.putObject(me.getKey());
                mv.put("Amount", formatAmount(me.getValue()));
                mv.put("Unit", groupUnits.get(entry.getKey()).getOrDefault(me.getKey(), ""));
            }
        }
        result.put("Estimated", false);
        return result;
    }

    private static List<String> groupKey(List<GroupBy> groupBys, UsageLine line) {
        List<String> out = new ArrayList<>(groupBys.size());
        for (GroupBy gb : groupBys) {
            out.add(switch (gb.type()) {
                case "DIMENSION" -> {
                    String v = FilterExpressionEvaluator.dimensionValue(gb.key(), line);
                    yield v == null ? "" : v;
                }
                case "TAG" -> line.tags() == null ? "" : line.tags().getOrDefault(gb.key(), "");
                case "COST_CATEGORY" -> "";
                default -> throw new AwsException("ValidationException",
                        "Unsupported GroupBy.Type: " + gb.type(), 400);
            });
        }
        return out;
    }

    static List<GroupBy> parseGroupBy(JsonNode node) {
        List<GroupBy> out = new ArrayList<>();
        if (!node.isArray()) {
            return out;
        }
        for (JsonNode entry : node) {
            String type = entry.path("Type").asText("DIMENSION");
            String key = entry.path("Key").asText(null);
            if (key == null || key.isEmpty()) {
                throw new AwsException("ValidationException",
                        "GroupBy entries must include a Key.", 400);
            }
            out.add(new GroupBy(type, key));
        }
        return out;
    }

    static Set<String> parseMetrics(JsonNode node) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode v : node) {
                if (!v.isNull() && !v.asText().isEmpty()) {
                    out.add(v.asText());
                }
            }
        }
        return out;
    }

    static String formatAmount(double amount) {
        // AWS returns amounts as decimal strings.
        return String.format(java.util.Locale.ROOT, "%.10f", amount);
    }

    record GroupBy(String type, String key) {}
}
