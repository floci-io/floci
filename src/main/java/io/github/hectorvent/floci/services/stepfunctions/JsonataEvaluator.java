package io.github.hectorvent.floci.services.stepfunctions;

import com.dashjoin.jsonata.Jsonata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Iterator;
import java.util.Map;

import static io.github.hectorvent.floci.services.stepfunctions.AslExecutor.FailStateException;

import static com.dashjoin.jsonata.Jsonata.jsonata;

/**
 * Evaluates JSONata expressions for Step Functions.
 * Handles {% expression %} delimiters, $states variable binding,
 * and recursive template resolution for Arguments/Output fields.
 *
 * Only pure expressions are evaluated: "{% $states.input.name %}" → any type.
 * Strings that are not a single {% %} expression pass through unchanged
 * (AWS does not support string interpolation with multiple {% %} blocks).
 */
@ApplicationScoped
public class JsonataEvaluator {

    private final ObjectMapper objectMapper;

    @Inject
    public JsonataEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Check if the string is a JSONata expression (starts with {% and ends with %}).
     */
    static boolean isExpression(String value) {
        return value != null && value.startsWith("{%") && value.endsWith("%}");
    }

    /**
     * Strip {% %} delimiters and return the inner expression, trimmed.
     */
    static String unwrap(String value) {
        return value.substring(2, value.length() - 2).trim();
    }

    /**
     * Evaluate a single JSONata expression string with $states bound.
     * The expression may or may not have {% %} delimiters.
     *
     * <p><b>Singleton sequence reduction:</b>
     * Both real AWS Step Functions and the JSONata spec apply singleton sequence reduction:
     * a 1-element sequence produced by an object-mapping expression (e.g.
     * {@code $states.result.Items.{"id": id}}) is reduced to the single object rather than
     * remaining a 1-element array. Floci's behavior matches AWS.
     *
     * <p>To force an array regardless of element count, wrap in {@code [...]}, e.g.
     * {@code [$states.result.Items.{"id": id}]}.
     */
    JsonNode evaluate(String expression, JsonNode statesVar) {
        String expr = isExpression(expression) ? unwrap(expression) : expression;
        try {
            Jsonata jsonataExpr = jsonata(expr);
            Jsonata.Frame frame = jsonataExpr.createFrame();
            frame.bind("states", toObject(statesVar));
            Object result = jsonataExpr.evaluate(null, frame);
            return toJsonNode(result);
        } catch (Exception e) {
            throw new AslExecutor.FailStateException("States.QueryEvaluationError", e.getMessage());
        }
    }

    /**
     * Walk a JSON template (Arguments or Output), evaluating any {% %} strings found.
     * Non-expression values pass through unchanged.
     *
     * Only pure {% expression %} strings are evaluated (can return any JSON type).
     * All other strings pass through unchanged.
     */
    JsonNode resolveTemplate(JsonNode template, JsonNode statesVar) {
        if (template == null || template.isNull() || template.isMissingNode()) {
            return template;
        }
        if (template.isTextual()) {
            String text = template.asText();
            if (isExpression(text)) {
                return evaluate(text, statesVar);
            }
            return template;
        }
        if (template.isObject()) {
            ObjectNode resolved = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = template.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode value = resolveTemplate(entry.getValue(), statesVar);
                // Per JSONata spec: undefined (null) values are omitted from object output,
                // matching real AWS Step Functions behavior.
                if (value != null && !value.isNull() && !value.isMissingNode()) {
                    resolved.set(entry.getKey(), value);
                }
            }
            return resolved;
        }
        if (template.isArray()) {
            ArrayNode resolved = objectMapper.createArrayNode();
            for (int i = 0; i < template.size(); i++) {
                JsonNode element = template.get(i);
                JsonNode value = resolveTemplate(element, statesVar);
                // Per real AWS behavior: undefined array elements fail the execution.
                // Unlike object fields (which are omitted), undefined in an array is a runtime error.
                if (value == null || value.isNull() || value.isMissingNode()) {
                    String expr = element.isTextual() ? element.asText() : element.toString();
                    throw new FailStateException("States.Runtime",
                            "The JSONata expression '" + expr + "' at array index " + i + " returned nothing (undefined).");
                }
                resolved.add(value);
            }
            return resolved;
        }
        // Primitives (number, boolean) pass through
        return template;
    }

    private Object toObject(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return objectMapper.convertValue(node, Object.class);
    }

    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        return objectMapper.valueToTree(value);
    }
}
