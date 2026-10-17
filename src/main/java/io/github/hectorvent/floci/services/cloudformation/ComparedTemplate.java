package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A template in the form CloudFormation compares when it decides what an update changes. Measured
 * against CloudFormation (eu-west-1, 2026-10-16): of each active resource it compares the Type,
 * Properties, Metadata, DeletionPolicy, UpdateReplacePolicy and UpdatePolicy, and of each active
 * output the Value, Description and Export. Whatever does not depend on a resource is resolved
 * first, so a literal written as {@code Fn::Join}, a parameter written as {@code Fn::Sub} or an
 * {@code Fn::If} whose other branch changed compares equal, while a {@code Ref} or
 * {@code Fn::GetAtt} of a resource, {@code Fn::ImportValue} and {@code Fn::GetStackOutput} compare
 * as written: {@code Ref A} rewritten as {@code Fn::Sub "${A}"} is a change. A dynamic reference
 * compares as the value it resolves to, through the function given. DependsOn, CreationPolicy, the
 * name of a resource's Condition, an output whose Condition is false and every section but
 * Resources and Outputs are not compared.
 */
final class ComparedTemplate {

    private static final List<String> RESOURCE_KEYS = List.of(
            "Type", "Properties", "Metadata", "DeletionPolicy", "UpdateReplacePolicy", "UpdatePolicy");
    private static final List<String> OUTPUT_KEYS = List.of("Value", "Description", "Export");
    private static final Pattern SUB_VARIABLE = Pattern.compile("\\$\\{([^}]*)}");
    private static final String DYNAMIC_REFERENCE = "{{resolve:";

    private final ObjectMapper objectMapper;
    private final CloudFormationTemplateEngine engine;
    private final Map<String, Boolean> conditions;
    private final Set<String> resourceIds = new HashSet<>();
    private final UnaryOperator<String> dynamicReferenceValue;

    /**
     * @param engine                resolves what does not depend on a resource: built with the
     *                              template's parameter values, mappings and conditions, and without
     *                              a dynamic reference resolver, so a dynamic reference reaches
     *                              {@code dynamicReferenceValue} as written
     * @param dynamicReferenceValue the value a string holding a dynamic reference compares as
     */
    private ComparedTemplate(ObjectMapper objectMapper, CloudFormationTemplateEngine engine,
                             Map<String, Boolean> conditions, UnaryOperator<String> dynamicReferenceValue) {
        this.objectMapper = objectMapper;
        this.engine = engine;
        this.conditions = conditions;
        this.dynamicReferenceValue = dynamicReferenceValue;
    }

    /**
     * The compared form of {@code template}: an object with a {@code Resources} and an
     * {@code Outputs} member, each keyed by logical id. Two deployments of a stack change nothing
     * when their forms are equal; one resource changes when its member differs.
     */
    static ObjectNode of(JsonNode template, ObjectMapper objectMapper, CloudFormationTemplateEngine engine,
                         Map<String, Boolean> conditions, UnaryOperator<String> dynamicReferenceValue) {
        ComparedTemplate compared = new ComparedTemplate(objectMapper, engine, conditions, dynamicReferenceValue);
        template.path("Resources").fieldNames().forEachRemaining(compared.resourceIds::add);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("Resources", compared.section(template.path("Resources"), RESOURCE_KEYS));
        result.set("Outputs", compared.section(template.path("Outputs"), OUTPUT_KEYS));
        return result;
    }

    private ObjectNode section(JsonNode entries, List<String> keys) {
        ObjectNode section = objectMapper.createObjectNode();
        entries.fields().forEachRemaining(entry -> {
            JsonNode definition = entry.getValue();
            String condition = definition.path("Condition").asText(null);
            if (condition != null && !conditions.getOrDefault(condition, false)) {
                return;
            }
            ObjectNode compared = objectMapper.createObjectNode();
            for (String key : keys) {
                JsonNode value = definition.get(key);
                if (value != null) {
                    compared.set(key, compared(value, false));
                }
            }
            section.set(entry.getKey(), compared);
        });
        return section;
    }

    /**
     * @param asWritten whether {@code node} is inside a function compared as written, where an
     *                  {@code AWS::NoValue} is part of what is written rather than an absent value
     */
    private JsonNode compared(JsonNode node, boolean asWritten) {
        if (node.isTextual()) {
            return dynamicReferencesIn(node);
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(element -> {
                JsonNode compared = compared(element, asWritten);
                if (asWritten || !isNoValue(compared)) {
                    array.add(compared);
                }
            });
            return array;
        }
        if (!node.isObject()) {
            return node;
        }
        String function = intrinsicName(node);
        if (function != null) {
            JsonNode argument = node.get(function);
            if ("Fn::If".equals(function) && argument.isArray() && argument.size() == 3) {
                return compared(conditions.getOrDefault(argument.get(0).asText(), false)
                        ? argument.get(1) : argument.get(2), asWritten);
            }
            if (isNoValue(node)) {
                return node;
            }
            if (!dependsOnResource(node)) {
                return dynamicReferencesIn(engine.resolveNode(node));
            }
            if ("Fn::Sub".equals(function) && argument.isTextual()) {
                ObjectNode sub = objectMapper.createObjectNode();
                sub.put("Fn::Sub", substituteStaticVariables(argument.asText(), objectMapper.createObjectNode()));
                return sub;
            }
            if ("Fn::Sub".equals(function) && argument.isArray() && argument.size() == 2
                    && argument.get(0).isTextual()) {
                ObjectNode sub = objectMapper.createObjectNode();
                sub.putArray("Fn::Sub")
                        .add(substituteStaticVariables(argument.get(0).asText(), argument.get(1)))
                        .add(compared(argument.get(1), true));
                return sub;
            }
            ObjectNode kept = objectMapper.createObjectNode();
            kept.set(function, compared(argument, true));
            return kept;
        }
        ObjectNode object = objectMapper.createObjectNode();
        node.fields().forEachRemaining(field -> {
            JsonNode compared = compared(field.getValue(), asWritten);
            if (asWritten || !isNoValue(compared)) {
                object.set(field.getKey(), compared);
            }
        });
        return object;
    }

    /**
     * Whether {@code node} reads a resource, an export or another stack's output, which are
     * compared as written. An {@code Fn::If} depends only on the branch its condition chooses.
     */
    private boolean dependsOnResource(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (dependsOnResource(element)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isObject()) {
            return false;
        }
        String function = intrinsicName(node);
        JsonNode argument = function == null ? null : node.get(function);
        if ("Ref".equals(function)) {
            return resourceIds.contains(argument.asText());
        }
        if ("Fn::GetAtt".equals(function) || "Fn::ImportValue".equals(function)
                || "Fn::GetStackOutput".equals(function)) {
            return true;
        }
        if ("Fn::If".equals(function) && argument.isArray() && argument.size() == 3) {
            return dependsOnResource(conditions.getOrDefault(argument.get(0).asText(), false)
                    ? argument.get(1) : argument.get(2));
        }
        if ("Fn::Sub".equals(function)) {
            JsonNode variables = argument.isArray() ? argument.path(1) : objectMapper.createObjectNode();
            String text = (argument.isArray() ? argument.path(0) : argument).asText();
            return subReadsResource(text, variables) || dependsOnResource(variables);
        }
        for (JsonNode child : node) {
            if (dependsOnResource(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean subReadsResource(String text, JsonNode variables) {
        Matcher matcher = SUB_VARIABLE.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!name.startsWith("!") && !variables.has(name)) {
                int dot = name.indexOf('.');
                if (resourceIds.contains(dot < 0 ? name : name.substring(0, dot))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * An {@code Fn::Sub} string that reads a resource, with every other variable (a parameter, a
     * pseudo parameter) replaced by its value, so only the resource reads remain as written. A
     * variable its own map defines stays as written: the map is compared beside it.
     */
    private String substituteStaticVariables(String text, JsonNode variables) {
        Matcher matcher = SUB_VARIABLE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            int dot = name.indexOf('.');
            boolean readsResource = !name.startsWith("!")
                    && resourceIds.contains(dot < 0 ? name : name.substring(0, dot));
            String replacement = name.startsWith("!") || readsResource || variables.has(name)
                    ? matcher.group()
                    : engine.resolve(objectMapper.createObjectNode().put("Ref", name));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return dynamicReferencesIn(TextNode.valueOf(result.toString())).asText();
    }

    private JsonNode dynamicReferencesIn(JsonNode node) {
        if (node.isTextual()) {
            String text = node.asText();
            return text.contains(DYNAMIC_REFERENCE) ? TextNode.valueOf(dynamicReferenceValue.apply(text)) : node;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(element -> array.add(dynamicReferencesIn(element)));
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = objectMapper.createObjectNode();
            node.fields().forEachRemaining(field -> object.set(field.getKey(), dynamicReferencesIn(field.getValue())));
            return object;
        }
        return node;
    }

    private static String intrinsicName(JsonNode node) {
        if (node.size() != 1) {
            return null;
        }
        String name = node.fieldNames().next();
        return "Ref".equals(name) || name.startsWith("Fn::") ? name : null;
    }

    private static boolean isNoValue(JsonNode node) {
        return node.isObject() && node.size() == 1 && "AWS::NoValue".equals(node.path("Ref").asText(null));
    }
}
