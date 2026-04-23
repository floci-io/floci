package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class PipesFilterMatcher {

    private static final Logger LOG = Logger.getLogger(PipesFilterMatcher.class);

    private final ObjectMapper objectMapper;

    @Inject
    public PipesFilterMatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<JsonNode> applyFilterCriteria(List<JsonNode> records, JsonNode sourceParameters) {
        if (sourceParameters == null) {
            return records;
        }
        JsonNode filters = sourceParameters.path("FilterCriteria").path("Filters");
        if (filters.isMissingNode() || !filters.isArray() || filters.isEmpty()) {
            return records;
        }
        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode record : records) {
            if (matchesAnyFilter(record, filters)) {
                matched.add(record);
            }
        }
        return matched;
    }

    private boolean matchesAnyFilter(JsonNode record, JsonNode filters) {
        for (JsonNode filter : filters) {
            JsonNode patternNode = filter.path("Pattern");
            if (patternNode.isMissingNode()) {
                continue;
            }
            String patternStr = patternNode.isTextual() ? patternNode.asText() : patternNode.toString();
            if (matchesRecord(record, patternStr)) {
                return true;
            }
        }
        return false;
    }

    boolean matchesRecord(JsonNode record, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        try {
            JsonNode patternNode = objectMapper.readTree(pattern);
            return matchesNode(record, patternNode);
        } catch (Exception e) {
            LOG.warnv("Failed to parse filter pattern: {0}", e.getMessage());
            return false;
        }
    }

    private boolean matchesNode(JsonNode actual, JsonNode pattern) {
        if (!pattern.isObject()) {
            return false;
        }
        var fields = pattern.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            JsonNode patternValue = field.getValue();
            JsonNode actualValue = actual.path(key);

            if (patternValue.isArray()) {
                if (!matchesArrayField(patternValue, actualValue)) {
                    return false;
                }
            } else if (patternValue.isObject()) {
                JsonNode resolvedActual = resolveActualForObject(actualValue);
                if (!matchesNode(resolvedActual, patternValue)) {
                    return false;
                }
            } else {
                LOG.warnv("Invalid filter pattern: scalar value for key \"{0}\". " +
                        "Pattern values must be arrays or objects.", key);
                return false;
            }
        }
        return true;
    }

    private JsonNode resolveActualForObject(JsonNode actualValue) {
        if (actualValue.isTextual()) {
            try {
                JsonNode parsed = objectMapper.readTree(actualValue.asText());
                if (parsed.isObject() || parsed.isArray()) {
                    return parsed;
                }
            } catch (Exception ignored) {
            }
        }
        return actualValue;
    }

    private boolean matchesArrayField(JsonNode patternArray, JsonNode actualValue) {
        for (JsonNode element : patternArray) {
            if (matchesSingleElement(element, actualValue)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSingleElement(JsonNode element, JsonNode actualValue) {
        boolean valueExists = !actualValue.isMissingNode() && !actualValue.isNull();
        String actualStr = valueExists && actualValue.isTextual() ? actualValue.asText() : null;

        if (element.isTextual()) {
            return actualStr != null && actualStr.equals(element.asText());
        }
        if (element.isNull()) {
            return !valueExists;
        }
        if (element.isNumber()) {
            return valueExists && actualValue.isNumber()
                    && actualValue.decimalValue().compareTo(element.decimalValue()) == 0;
        }
        if (element.isObject()) {
            if (element.has("prefix")) {
                return actualStr != null && actualStr.startsWith(element.get("prefix").asText());
            }
            if (element.has("suffix")) {
                return actualStr != null && actualStr.endsWith(element.get("suffix").asText());
            }
            if (element.has("equals-ignore-case")) {
                return actualStr != null && actualStr.equalsIgnoreCase(element.get("equals-ignore-case").asText());
            }
            if (element.has("anything-but")) {
                JsonNode anythingBut = element.get("anything-but");
                if (anythingBut.isArray()) {
                    if (!valueExists) {
                        return true;
                    }
                    for (JsonNode v : anythingBut) {
                        if (v.isTextual() && v.asText().equals(actualStr)) {
                            return false;
                        }
                        if (v.isNumber() && actualValue.isNumber()
                                && actualValue.decimalValue().compareTo(v.decimalValue()) == 0) {
                            return false;
                        }
                    }
                    return true;
                }
                if (anythingBut.isObject() && anythingBut.has("prefix")) {
                    return !valueExists || (actualStr != null && !actualStr.startsWith(anythingBut.get("prefix").asText()));
                }
                if (anythingBut.isTextual()) {
                    return !valueExists || (actualStr != null && !actualStr.equals(anythingBut.asText()));
                }
            }
            if (element.has("exists")) {
                boolean shouldExist = element.get("exists").asBoolean();
                return shouldExist == valueExists;
            }
            if (element.has("numeric")) {
                return matchesNumericFilter(element.get("numeric"), actualValue);
            }
        }
        return false;
    }

    private boolean matchesNumericFilter(JsonNode numericArray, JsonNode actualValue) {
        if (!actualValue.isNumber() || !numericArray.isArray()) {
            return false;
        }
        BigDecimal actual = actualValue.decimalValue();
        for (int i = 0; i + 1 < numericArray.size(); i += 2) {
            String op = numericArray.get(i).asText();
            BigDecimal operand = numericArray.get(i + 1).decimalValue();
            boolean result = switch (op) {
                case "=" -> actual.compareTo(operand) == 0;
                case ">" -> actual.compareTo(operand) > 0;
                case ">=" -> actual.compareTo(operand) >= 0;
                case "<" -> actual.compareTo(operand) < 0;
                case "<=" -> actual.compareTo(operand) <= 0;
                default -> false;
            };
            if (!result) {
                return false;
            }
        }
        return true;
    }
}
