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
     * <p><b>Known limitation — singleton sequence reduction:</b>
     * AWS Step Functions does not apply JSONata's singleton sequence rule (a 1-element sequence
     * is not unwrapped to its single element). dashjoin's JSONata implementation DOES apply this
     * rule, so expressions that map over a 1-element array — e.g.
     * {@code $states.result.Items.{"id": id}} — return a single object instead of a 1-element
     * array when the array has exactly one element.
     *
     * <p>Workaround: wrap the expression in {@code [...]} to force array output, e.g.
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
                resolved.set(entry.getKey(), resolveTemplate(entry.getValue(), statesVar));
            }
            return resolved;
        }
        if (template.isArray()) {
            ArrayNode resolved = objectMapper.createArrayNode();
            for (JsonNode element : template) {
                resolved.add(resolveTemplate(element, statesVar));
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
