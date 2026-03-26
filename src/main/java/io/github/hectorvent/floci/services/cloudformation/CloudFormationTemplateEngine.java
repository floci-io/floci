package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.jboss.logging.Logger;

import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Resolves CloudFormation intrinsic functions and pseudo-parameters in template nodes.
 * Supported: Ref, Fn::Sub, Fn::Join, Fn::Select, Fn::If, Fn::Split, Fn::Base64,
 * Fn::GetAtt, Condition.
 */
public class CloudFormationTemplateEngine {

    private static final Logger LOG = Logger.getLogger(CloudFormationTemplateEngine.class);

    private final String accountId;
    private final String region;
    private final String stackName;
    private final String stackId;
    private final Map<String, String> parameters;
    private final Map<String, String> physicalIds;
    private final Map<String, Map<String, String>> resourceAttributes;
    private final Map<String, Boolean> conditions;
    private final Map<String, JsonNode> mappings;
    private final ObjectMapper objectMapper;

    CloudFormationTemplateEngine(String accountId, String region, String stackName, String stackId,
                                 Map<String, String> parameters,
                                 Map<String, String> physicalIds,
                                 Map<String, Map<String, String>> resourceAttributes,
                                 Map<String, Boolean> conditions,
                                 Map<String, JsonNode> mappings,
                                 ObjectMapper objectMapper) {
        this.accountId = accountId;
        this.region = region;
        this.stackName = stackName;
        this.stackId = stackId;
        this.parameters = parameters;
        this.physicalIds = physicalIds;
        this.resourceAttributes = resourceAttributes;
        this.conditions = conditions;
        this.mappings = mappings;
        this.objectMapper = objectMapper;
    }

    public String resolve(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isNumber()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asText();
        }
        if (node.isObject()) {
            if (node.has("Ref")) {
                return resolveRef(node.get("Ref").asText());
            }
            if (node.has("Fn::Sub")) {
                return resolveSub(node.get("Fn::Sub"));
            }
            if (node.has("Fn::Join")) {
                return resolveJoin(node.get("Fn::Join"));
            }
            if (node.has("Fn::Select")) {
                return resolveSelect(node.get("Fn::Select"));
            }
            if (node.has("Fn::If")) {
                return resolveIf(node.get("Fn::If"));
            }
            if (node.has("Fn::Base64")) {
                return Base64.getEncoder().encodeToString(resolve(node.get("Fn::Base64")).getBytes());
            }
            if (node.has("Fn::Split")) {
                return resolve(node.get("Fn::Split").get(1));
            }
            if (node.has("Fn::GetAtt")) {
                return resolveGetAtt(node.get("Fn::GetAtt"));
            }
            if (node.has("Fn::ImportValue")) {
                return resolve(node.get("Fn::ImportValue"));
            }
            if (node.has("Fn::FindInMap")) {
                return resolveFindInMap(node.get("Fn::FindInMap"));
            }
        }
        return node.asText();
    }

    public JsonNode resolveNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node;
        }
        if (node.isObject()) {
            if (node.has("Ref") || node.has("Fn::Sub") || node.has("Fn::Join") ||
                    node.has("Fn::Select") || node.has("Fn::If") || node.has("Fn::Base64") ||
                    node.has("Fn::GetAtt") || node.has("Fn::ImportValue") || node.has("Fn::Split")) {
                return TextNode.valueOf(resolve(node));
            }
            // Plain object — resolve each field
            var resolved = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                resolved.set(entry.getKey(), resolveNode(entry.getValue()));
            }
            return resolved;
        }
        if (node.isArray()) {
            var arr = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                arr.add(resolveNode(item));
            }
            return arr;
        }
        return node;
    }

    private String resolveRef(String name) {
        // Pseudo-parameters
        return switch (name) {
            case "AWS::AccountId" -> accountId;
            case "AWS::Region" -> region;
            case "AWS::StackName" -> stackName;
            case "AWS::StackId" -> stackId;
            case "AWS::Partition" -> "aws";
            case "AWS::URLSuffix" -> "amazonaws.com";
            case "AWS::NoValue" -> "";
            default -> {
                if (physicalIds.containsKey(name)) {
                    yield physicalIds.get(name);
                }
                if (parameters.containsKey(name)) {
                    yield parameters.get(name);
                }
                LOG.debugv("Unresolved Ref: {0}", name);
                yield name;
            }
        };
    }

    private String resolveSub(JsonNode sub) {
        String template;
        Map<String, String> vars = new HashMap<>();
        if (sub.isTextual()) {
            template = sub.textValue();
        } else if (sub.isArray() && sub.size() == 2) {
            template = sub.get(0).textValue();
            JsonNode varMap = sub.get(1);
            if (varMap.isObject()) {
                varMap.fields().forEachRemaining(e -> vars.put(e.getKey(), resolve(e.getValue())));
            }
        } else {
            return sub.asText();
        }

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            if (template.charAt(i) == '$' && i + 1 < template.length() && template.charAt(i + 1) == '{') {
                int end = template.indexOf('}', i + 2);
                if (end == -1) {
                    result.append(template.substring(i));
                    break;
                }
                String varName = template.substring(i + 2, end);
                if (vars.containsKey(varName)) {
                    result.append(vars.get(varName));
                } else if (varName.contains("!")) {
                    // Fn::GetAtt shorthand: ${LogicalId.Attr}
                    String[] parts = varName.split("\\.", 2);
                    result.append(resolveGetAttParts(parts[0], parts.length > 1 ? parts[1] : ""));
                } else {
                    result.append(resolveRef(varName));
                }
                i = end + 1;
            } else {
                result.append(template.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    private String resolveJoin(JsonNode join) {
        if (!join.isArray() || join.size() < 2) {
            return "";
        }
        String delimiter = join.get(0).asText("");
        JsonNode parts = join.get(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(resolve(parts.get(i)));
        }
        return sb.toString();
    }

    private String resolveSelect(JsonNode select) {
        if (!select.isArray() || select.size() < 2) {
            return "";
        }
        int index = select.get(0).asInt(0);
        JsonNode list = select.get(1);
        if (list.isArray() && index < list.size()) {
            return resolve(list.get(index));
        }
        return "";
    }

    private String resolveIf(JsonNode ifNode) {
        if (!ifNode.isArray() || ifNode.size() < 3) {
            return "";
        }
        String conditionName = ifNode.get(0).asText();
        boolean condValue = conditions.getOrDefault(conditionName, false);
        return resolve(condValue ? ifNode.get(1) : ifNode.get(2));
    }

    private String resolveGetAtt(JsonNode getAtt) {
        if (getAtt.isArray() && getAtt.size() == 2) {
            return resolveGetAttParts(getAtt.get(0).asText(), getAtt.get(1).asText());
        }
        if (getAtt.isTextual()) {
            String[] parts = getAtt.textValue().split("\\.", 2);
            return resolveGetAttParts(parts[0], parts.length > 1 ? parts[1] : "");
        }
        return "";
    }

    private String resolveGetAttParts(String logicalId, String attrName) {
        Map<String, String> attrs = resourceAttributes.get(logicalId);
        if (attrs != null && attrs.containsKey(attrName)) {
            return attrs.get(attrName);
        }
        LOG.debugv("Unresolved GetAtt: {0}.{1}", logicalId, attrName);
        return logicalId + "." + attrName;
    }

    private String resolveFindInMap(JsonNode node) {
        if (node.isArray()) {
            String mapName = resolve(node.get(0));
            String topLvlName = resolve(node.get(1));
            String secondLvlName = resolve(node.get(2));

            JsonNode map = mappings.get(mapName);
            if (map != null && map.isObject()) {
                JsonNode topLvl = map.get(topLvlName);
                if (topLvl != null && topLvl.isObject()) {
                    JsonNode secondLvl = topLvl.get(secondLvlName);
                    if (secondLvl != null) {
                        return resolve(secondLvl);
                    }
                }
            }
        }
        return "";
    }
}
