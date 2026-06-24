package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Resolves CloudFormation intrinsic functions and pseudo-parameters in template nodes.
 * Supported: Ref, Fn::Sub, Fn::Join, Fn::Select, Fn::If, Fn::Split, Fn::Base64,
 * Fn::GetAtt, Fn::GetAZs, Fn::Cidr, Fn::FindInMap, Fn::ImportValue, Condition.
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
    private final Function<String, String> importValueResolver;

    CloudFormationTemplateEngine(String accountId, String region, String stackName, String stackId,
                                 Map<String, String> parameters,
                                 Map<String, String> physicalIds,
                                 Map<String, Map<String, String>> resourceAttributes,
                                 Map<String, Boolean> conditions,
                                 Map<String, JsonNode> mappings,
                                 ObjectMapper objectMapper,
                                 Function<String, String> importValueResolver) {
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
        this.importValueResolver = importValueResolver;
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
            if (node.has("Fn::GetAZs")) {
                return String.join(",", resolveAvailabilityZones(node.get("Fn::GetAZs")));
            }
            if (node.has("Fn::Cidr")) {
                return String.join(",", resolveCidr(node.get("Fn::Cidr")));
            }
            if (node.has("Fn::GetAtt")) {
                return resolveGetAtt(node.get("Fn::GetAtt"));
            }
            if (node.has("Fn::ImportValue")) {
                return resolveImportValue(node.get("Fn::ImportValue"));
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
                    node.has("Fn::GetAtt") || node.has("Fn::ImportValue") || node.has("Fn::Split") ||
                    node.has("Fn::GetAZs") || node.has("Fn::Cidr") || node.has("Fn::FindInMap")) {
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
        int index = parseInt(resolve(select.get(0)), 0);
        List<String> items = resolveList(select.get(1));
        if (index >= 0 && index < items.size()) {
            return items.get(index);
        }
        return "";
    }

    /**
     * Resolves a node that represents a list — a literal array, a list-producing intrinsic
     * ({@code Fn::GetAZs}, {@code Fn::Cidr}, {@code Fn::Split}), or a comma-delimited scalar
     * (e.g. a {@code Ref} to a {@code List<>} parameter).
     */
    private List<String> resolveList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                out.add(resolve(item));
            }
            return out;
        }
        if (node.isObject()) {
            if (node.has("Fn::GetAZs")) {
                return resolveAvailabilityZones(node.get("Fn::GetAZs"));
            }
            if (node.has("Fn::Cidr")) {
                return resolveCidr(node.get("Fn::Cidr"));
            }
            if (node.has("Fn::Split")) {
                return resolveSplit(node.get("Fn::Split"));
            }
        }
        String scalar = resolve(node);
        if (!scalar.isEmpty()) {
            out.addAll(Arrays.asList(scalar.split(",", -1)));
        }
        return out;
    }

    private List<String> resolveSplit(JsonNode split) {
        if (!split.isArray() || split.size() < 2) {
            return List.of();
        }
        String delimiter = resolve(split.get(0));
        String source = resolve(split.get(1));
        if (delimiter.isEmpty()) {
            return List.of(source);
        }
        return Arrays.asList(source.split(Pattern.quote(delimiter), -1));
    }

    /**
     * {@code Fn::GetAZs} — returns the Availability Zones for a region. The argument is the region
     * (empty string means the stack's region). Floci seeds three default zones per region.
     */
    private List<String> resolveAvailabilityZones(JsonNode arg) {
        String r = (arg == null || arg.isNull()) ? region : resolve(arg);
        if (r == null || r.isBlank()) {
            r = region;
        }
        return List.of(r + "a", r + "b", r + "c");
    }

    /**
     * {@code Fn::Cidr} — splits an IPv4 CIDR block into {@code count} subnets, each with
     * {@code cidrBits} host bits (i.e. a /{@code (32 - cidrBits)} mask). IPv6 is not supported.
     */
    private List<String> resolveCidr(JsonNode args) {
        if (!args.isArray() || args.size() < 2) {
            return List.of();
        }
        String ipBlock = resolve(args.get(0));
        int count = parseInt(resolve(args.get(1)), 0);
        int cidrBits = args.size() > 2 ? parseInt(resolve(args.get(2)), 8) : 8;
        int slash = ipBlock.indexOf('/');
        if (slash < 0 || count <= 0 || cidrBits <= 0 || cidrBits > 32) {
            return List.of();
        }
        long base = ipv4ToLong(ipBlock.substring(0, slash));
        if (base < 0) {
            return List.of();
        }
        int newPrefix = 32 - cidrBits;
        long step = 1L << cidrBits;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(longToIpv4(base + (long) i * step) + "/" + newPrefix);
        }
        return out;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long ipv4ToLong(String ip) {
        String[] octets = ip.trim().split("\\.");
        if (octets.length != 4) {
            return -1;
        }
        long result = 0;
        for (String octet : octets) {
            int value = parseInt(octet, -1);
            if (value < 0 || value > 255) {
                return -1;
            }
            result = (result << 8) | value;
        }
        return result;
    }

    private static String longToIpv4(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "."
                + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
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

    private String resolveImportValue(JsonNode node) {
        String exportName = resolve(node);
        if (importValueResolver != null) {
            String value = importValueResolver.apply(exportName);
            if (value != null) {
                return value;
            }
        }
        LOG.warnv("Unresolved Fn::ImportValue: {0}", exportName);
        throw new AwsException("ValidationError", "No export named " + exportName + " found", 400);
    }
}
