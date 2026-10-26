package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Resolves CloudFormation intrinsic functions and pseudo-parameters in template nodes.
 * Supported: Ref, Fn::Sub, Fn::Join, Fn::Select, Fn::If, Fn::Split, Fn::Base64,
 * Fn::GetAtt, Fn::GetAZs, Fn::Cidr, Fn::FindInMap, Fn::ImportValue, Fn::GetStackOutput, Condition.
 */
public class CloudFormationTemplateEngine {

    private static final Logger LOG = Logger.getLogger(CloudFormationTemplateEngine.class);

    static final String GET_STACK_OUTPUT = "Fn::GetStackOutput";

    /**
     * Reads an output of another stack for {@code Fn::GetStackOutput}, the way CloudFormation does
     * with DescribeStacks in the referenced account and Region.
     */
    @FunctionalInterface
    public interface StackOutputResolver {

        /**
         * @param region  the Region of the referenced stack, already validated
         * @param roleArn the role to read it with, or {@code null} for the stack's own account
         * @return the output's value; never {@code null}
         * @throws AwsException when the stack or the output does not exist, or the role cannot
         *                      be assumed
         */
        String resolve(String stackName, String outputName, String region, String roleArn);
    }

    /**
     * Internal attribute on a stub whose type is served by a provisioner whose service is turned
     * off. The template engine reads it to refuse {@code Fn::GetAtt} on an attribute the stub
     * does not have, instead of publishing the unresolved placeholder.
     */
    public static final String SERVICE_DISABLED_ATTR = "__FlociServiceDisabled";

    @FunctionalInterface
    public interface AttributeValueResolver {
        JsonNode resolve(String logicalId, String attributeName, String value);
    }

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
    private final UnaryOperator<String> dynamicReferenceResolver;
    private final StackOutputResolver stackOutputResolver;
    private final Map<String, String> parameterTypes;
    private final AttributeValueResolver attributeValueResolver;
    private final List<String> unresolvedIntrinsics = new ArrayList<>();
    /**
     * Above zero while an intrinsic-only property or {@code Fn::GetStackOutput}'s arguments resolve,
     * including nested intrinsic calls.
     */
    private int dynamicReferencesSuspended;

    /**
     * A minimal engine for a resource provisioned outside any stack (Cloud Control CreateResource).
     * Desired state carries resolved values, so it needs no parameters, mappings, conditions or
     * sibling resources: only intrinsics and dynamic references. The constructors stay
     * package-private so tests in {@code provisioners/} keep mocking the engine.
     */
    public static CloudFormationTemplateEngine standalone(String accountId, String region, String stackName,
                                                          ObjectMapper objectMapper,
                                                          UnaryOperator<String> dynamicReferenceResolver) {
        return new CloudFormationTemplateEngine(accountId, region, stackName, stackName,
                Map.of(), new HashMap<>(), new HashMap<>(), Map.of(), Map.of(), objectMapper,
                name -> null, dynamicReferenceResolver);
    }

    CloudFormationTemplateEngine(String accountId, String region, String stackName, String stackId,
                                 Map<String, String> parameters,
                                 Map<String, String> physicalIds,
                                 Map<String, Map<String, String>> resourceAttributes,
                                 Map<String, Boolean> conditions,
                                 Map<String, JsonNode> mappings,
                                 ObjectMapper objectMapper,
                                 Function<String, String> importValueResolver) {
        this(accountId, region, stackName, stackId, parameters, physicalIds, resourceAttributes,
                conditions, mappings, objectMapper, importValueResolver, null);
    }

    /**
     * @param dynamicReferenceResolver resolves a value that may contain CloudFormation dynamic
     *                                 references ({@code {{resolve:ssm:...}}},
     *                                 {@code {{resolve:secretsmanager:...}}}) against the live
     *                                 services, leaving a value with no dynamic reference syntax
     *                                 untouched. {@code null} skips this stage entirely, which
     *                                 keeps template values that only ever contain intrinsics
     *                                 (tests, Cloud Control desired-state resolution) free of the
     *                                 dependency.
     */
    CloudFormationTemplateEngine(String accountId, String region, String stackName, String stackId,
                                 Map<String, String> parameters,
                                 Map<String, String> physicalIds,
                                 Map<String, Map<String, String>> resourceAttributes,
                                 Map<String, Boolean> conditions,
                                 Map<String, JsonNode> mappings,
                                 ObjectMapper objectMapper,
                                 Function<String, String> importValueResolver,
                                 UnaryOperator<String> dynamicReferenceResolver) {
        this(accountId, region, stackName, stackId, parameters, physicalIds, resourceAttributes,
                conditions, mappings, objectMapper, importValueResolver, dynamicReferenceResolver, null);
    }

    /**
     * @param stackOutputResolver reads another stack's output for {@code Fn::GetStackOutput};
     *                            {@code null} fails every such reference, as nothing can answer it
     */
    CloudFormationTemplateEngine(String accountId, String region, String stackName, String stackId,
                                 Map<String, String> parameters,
                                 Map<String, String> physicalIds,
                                 Map<String, Map<String, String>> resourceAttributes,
                                 Map<String, Boolean> conditions,
                                 Map<String, JsonNode> mappings,
                                 ObjectMapper objectMapper,
                                 Function<String, String> importValueResolver,
                                 UnaryOperator<String> dynamicReferenceResolver,
                                 StackOutputResolver stackOutputResolver) {
        this(accountId, region, stackName, stackId, parameters, physicalIds, resourceAttributes,
                conditions, mappings, objectMapper, importValueResolver, dynamicReferenceResolver,
                stackOutputResolver, Map.of());
    }

    /**
     * @param parameterTypes each declared parameter's {@code Type}, which tells a {@code Ref} to a
     *                       list parameter, resolved to the comma-joined text, from one to a string
     *                       (see {@link #yieldsString})
     */
    CloudFormationTemplateEngine(String accountId, String region, String stackName, String stackId,
                                 Map<String, String> parameters,
                                 Map<String, String> physicalIds,
                                 Map<String, Map<String, String>> resourceAttributes,
                                 Map<String, Boolean> conditions,
                                 Map<String, JsonNode> mappings,
                                 ObjectMapper objectMapper,
                                 Function<String, String> importValueResolver,
                                 UnaryOperator<String> dynamicReferenceResolver,
                                 StackOutputResolver stackOutputResolver,
                                 Map<String, String> parameterTypes) {
        this(accountId, region, stackName, stackId, parameters, physicalIds, resourceAttributes,
                conditions, mappings, objectMapper, importValueResolver, dynamicReferenceResolver,
                stackOutputResolver, parameterTypes, (logicalId, attributeName, value) -> TextNode.valueOf(value));
    }

    CloudFormationTemplateEngine(String accountId, String region, String stackName, String stackId,
                                 Map<String, String> parameters,
                                 Map<String, String> physicalIds,
                                 Map<String, Map<String, String>> resourceAttributes,
                                 Map<String, Boolean> conditions,
                                 Map<String, JsonNode> mappings,
                                 ObjectMapper objectMapper,
                                 Function<String, String> importValueResolver,
                                 UnaryOperator<String> dynamicReferenceResolver,
                                 StackOutputResolver stackOutputResolver,
                                 Map<String, String> parameterTypes,
                                 AttributeValueResolver attributeValueResolver) {
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
        this.dynamicReferenceResolver = dynamicReferenceResolver;
        this.stackOutputResolver = stackOutputResolver;
        this.parameterTypes = parameterTypes;
        this.attributeValueResolver = attributeValueResolver;
    }

    /** The id of the stack this engine resolves for, as {@code AWS::StackId} returns it. */
    public String stackId() {
        return stackId;
    }

    /**
     * Resolves a property value, including CloudFormation dynamic reference syntax
     * ({@code {{resolve:ssm:...}}}, {@code {{resolve:secretsmanager:...}}}) in the result, the same
     * way {@link #resolveNode} does. This is the entry point every scalar-property resolution in
     * this codebase goes through, directly or via {@link #resolveNode}, so a dynamic reference is
     * substituted regardless of which of the two a caller uses.
     */
    public String resolve(JsonNode node) {
        return resolveDynamicReferences(resolveIntrinsic(node)).asText();
    }

    /** Resolves a string-only context, preserving literal and parameter coercion. */
    public String resolveString(JsonNode node, String errorMessage) {
        requireStringAttribute(node, errorMessage);
        return resolve(node);
    }

    /**
     * Resolves intrinsics only, leaving any {@code {{resolve:...}}} dynamic reference syntax in the
     * result untouched. For the one property family where {@code ssm-secure} is a valid dynamic
     * reference service (RDS {@code MasterUsername}/{@code MasterUserPassword}): {@link #resolve}
     * rejects {@code ssm-secure} outright since it is invalid everywhere else, so that property has
     * to skip the general dynamic-reference stage and resolve it separately, with the permission
     * only that property is allowed.
     */
    public String resolveWithoutDynamicReferences(JsonNode node) {
        dynamicReferencesSuspended++;
        try {
            return resolveIntrinsic(node);
        } finally {
            dynamicReferencesSuspended--;
        }
    }

    private String resolveIntrinsic(JsonNode node) {
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
                requireStringAttribute(node.get("Fn::Base64"),
                        "Template error: every Fn::Base64 object must have a String-typed value.");
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
            if (node.has(GET_STACK_OUTPUT)) {
                return resolveGetStackOutput(node.get(GET_STACK_OUTPUT));
            }
        }
        return node.asText();
    }

    public JsonNode resolveNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isTextual()) {
            return resolveDynamicReferences(node.textValue());
        }
        if (node.isNumber() || node.isBoolean()) {
            return node;
        }
        if (node.isObject()) {
            if (node.has("Fn::If")) {
                // Unlike the other intrinsics below, Fn::If's two branches can be any JSON shape
                // (array, object, or scalar) - it just forwards one of them verbatim. Collapsing it
                // through resolve() like the scalar-only intrinsics would stringify a chosen array or
                // object instead of preserving it, so a conditional list (e.g. a Tags property) reads
                // as unresolvable everywhere a caller checks isArray() on the result.
                JsonNode branch = selectIfBranch(node.get("Fn::If"));
                return branch == null ? TextNode.valueOf("") : resolveNode(branch);
            }
            if (node.has("Fn::Split") || node.has("Fn::GetAZs") || node.has("Fn::Cidr")) {
                return objectMapper.valueToTree(resolveList(node));
            }
            if (node.has("Fn::GetAtt")) {
                JsonNode attribute = resolveGetAttNode(node.get("Fn::GetAtt"));
                return attribute.isTextual() ? resolveDynamicReferences(attribute.textValue()) : attribute;
            }
            if (node.has("Fn::Select")) {
                return resolveNode(selectedElement(node.get("Fn::Select")));
            }
            if (callsIntrinsic(node)) {
                return TextNode.valueOf(resolve(node));
            }
            // Plain object: resolve each field.
            ObjectNode resolved = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                resolved.set(entry.getKey(), resolveNode(entry.getValue()));
            }
            return resolved;
        }
        if (node.isArray()) {
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                arr.add(resolveNode(item));
            }
            return arr;
        }
        return node;
    }

    /** Resolves optional structured properties, omitting only selected AWS::NoValue expressions. */
    public JsonNode resolveNodeOmittingNoValue(JsonNode node) {
        return resolveNode(omitNoValue(node));
    }

    /**
     * The template node with every {@code AWS::NoValue} that stands for a member, a list item or an
     * {@code Fn::If} branch left out, and every {@code Fn::If} outside another function replaced by
     * the branch its condition chooses; other intrinsic calls stay as they are, unresolved. A
     * {@link MissingNode} when the node itself is {@code AWS::NoValue}. A caller that needs to see how
     * a member is written, a literal or a call, reads it from here before resolving it.
     */
    public JsonNode omitNoValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isObject()) {
            if (node.size() == 1 && "AWS::NoValue".equals(node.path("Ref").asText())) {
                return MissingNode.getInstance();
            }
            if (node.size() == 1 && node.has("Fn::If")) {
                JsonNode branch = selectIfBranch(node.get("Fn::If"));
                if (branch != null) {
                    return omitNoValue(branch);
                }
            }
            if (isIntrinsic(node)) {
                return node;
            }
            ObjectNode resolved = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = omitNoValue(field.getValue());
                if (!value.isMissingNode()) {
                    resolved.set(field.getKey(), value);
                }
            }
            return resolved;
        }
        if (node.isArray()) {
            ArrayNode resolved = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                JsonNode value = omitNoValue(item);
                if (!value.isMissingNode()) {
                    resolved.add(value);
                }
            }
            return resolved;
        }
        return node;
    }

    /**
     * Whether {@code node} calls an intrinsic function, which {@link #resolveNode} evaluates, rather
     * than being a literal object whose fields it resolves one by one.
     */
    public static boolean callsIntrinsic(JsonNode node) {
        return node.isObject() && (isIntrinsic(node) || node.has(GET_STACK_OUTPUT));
    }

    private static boolean isIntrinsic(JsonNode node) {
        return node.has("Ref") || node.has("Fn::Sub") || node.has("Fn::Join")
                || node.has("Fn::Select") || node.has("Fn::Base64") || node.has("Fn::GetAtt")
                || node.has("Fn::ImportValue") || node.has("Fn::FindInMap")
                || node.has("Fn::Split") || node.has("Fn::GetAZs") || node.has("Fn::Cidr")
                || node.has("Fn::If");
    }

    /**
     * Resolves a value against {@link #dynamicReferenceResolver} when it carries CloudFormation
     * dynamic reference syntax ({@code {{resolve:ssm:...}}}, {@code {{resolve:secretsmanager:...}}}).
     * CloudFormation substitutes these for any string property in a template, not only the RDS
     * master-credential properties that first needed them (see
     * <a href="https://github.com/floci-io/floci/issues/2213">#2213</a>), so {@link #resolveNode}
     * applies this to every textual result: a literal string carrying the syntax outright, and the
     * text an intrinsic function (e.g. {@code Fn::Sub}) produces.
     */
    private TextNode resolveDynamicReferences(String value) {
        if (dynamicReferenceResolver == null || dynamicReferencesSuspended > 0 || value == null
                || !value.contains("{{resolve:")) {
            return TextNode.valueOf(value);
        }
        return TextNode.valueOf(dynamicReferenceResolver.apply(value));
    }

    /**
     * Resolves a node that must be stored as a JSON document (SNS/SQS RedrivePolicy and
     * FilterPolicy, Step Functions definitions, IAM policy documents) to its string form.
     * <p>
     * resolveNode collapses intrinsics such as Fn::Join into a {@link TextNode}. Calling
     * {@code toString()} on that node re-quotes and re-escapes the already-serialized JSON
     * (e.g. CDK's Fn::Join-spliced RedrivePolicy), so the value is double-encoded and the
     * downstream service can no longer parse it. Unwrapping textual results preserves the
     * literal JSON while non-textual nodes (plain objects) keep the normal serialization.
     *
     * @see <a href="https://github.com/floci-io/floci/issues/2317">#2317</a>
     */
    public String resolveJsonAttribute(JsonNode node) {
        JsonNode resolved = resolveNode(node);
        if (resolved == null || resolved.isNull() || resolved.isMissingNode()) {
            return null;
        }
        return resolved.isTextual() ? resolved.asText() : resolved.toString();
    }

    public String resolveJsonAttributeStrict(JsonNode node) {
        int before = unresolvedIntrinsics.size();
        String resolved = resolveJsonAttribute(node);
        if (unresolvedIntrinsics.size() > before) {
            List<String> raised = unresolvedIntrinsics.subList(before, unresolvedIntrinsics.size());
            throw new AwsException("ValidationError",
                    "Policy document contains unresolved CloudFormation intrinsics: "
                            + String.join(", ", raised)
                            + ". Storing them would silently void the statements that use them.", 400);
        }
        return resolved;
    }

    private String resolveRef(String name) {
        // Pseudo-parameters
        return switch (name) {
            case "AWS::AccountId" -> accountId;
            case "AWS::Region" -> region;
            case "AWS::StackName" -> stackName;
            case "AWS::StackId" -> stackId;
            case "AWS::Partition" -> AwsRegions.partitionFor(region);
            case "AWS::URLSuffix" -> AwsRegions.dnsSuffixFor(region);
            // Floci keeps no notification ARNs for a stack, so the list is empty.
            case "AWS::NotificationARNs" -> "";
            case "AWS::NoValue" -> "";
            default -> {
                if (physicalIds.containsKey(name)) {
                    yield physicalIds.get(name);
                }
                if (parameters.containsKey(name)) {
                    yield parameters.get(name);
                }
                LOG.debugv("Unresolved Ref: {0}", name);
                unresolvedIntrinsics.add("Ref " + name);
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
                varMap.fields().forEachRemaining(e -> {
                    requireStringAttribute(e.getValue(),
                            "Template error: every value of the context object of every Fn::Sub object"
                                    + " must be a string or a function that returns a string.");
                    vars.put(e.getKey(), resolve(e.getValue()));
                });
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
                } else if (varName.startsWith("!")) {
                    // ${!Literal} is Fn::Sub's escape sequence for a literal ${Literal}: emit it
                    // verbatim with no substitution. It is NOT the Fn::GetAtt shorthand.
                    result.append("${").append(varName, 1, varName.length()).append('}');
                } else if (!varName.startsWith("AWS::") && varName.contains(".")) {
                    // Fn::GetAtt shorthand: ${LogicalId.Attr}. A logical id and a parameter name
                    // are both alphanumeric, so a dot can only mean an attribute reference.
                    String[] parts = varName.split("\\.", 2);
                    JsonNode attribute = objectMapper.createArrayNode().add(parts[0]).add(parts[1]);
                    requireStringAttribute(objectMapper.createObjectNode().set("Fn::GetAtt", attribute),
                            "Template error: variable " + varName
                                    + " in Fn::Sub expression does not resolve to a string.");
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
        requireStringAttribute(join.get(1),
                "Template error: every Fn::Join object requires two parameters, (1) a string delimiter"
                        + " and (2) a list of strings to be joined or a function that returns a list"
                        + " of strings (such as Fn::GetAZs) to be joined.");
        // CDK splices a dynamic reference across fragments, for example
        // ["{{resolve:secretsmanager:", {"Ref": "Secret"}, ":SecretString:password::}}"], so the
        // fragments are only intrinsic-resolved here and the caller resolves dynamic references on
        // the concatenated string. Resolving them per fragment rejects every fragment as unclosed.
        return String.join(delimiter, resolveList(join.get(1), false));
    }

    private void requireStringAttribute(JsonNode node, String message) {
        if (node == null) {
            return;
        }
        if (node.has("Fn::GetAtt") && !isStringAttribute(node.get("Fn::GetAtt"))) {
            throw new AwsException("ValidationError", message, 400);
        }
        if (node.has("Fn::If")) {
            requireStringAttribute(selectIfBranch(node.get("Fn::If")), message);
        } else if (node.has("Fn::Select")) {
            requireStringAttribute(selectedElement(node.get("Fn::Select")), message);
        } else if (node.isArray()) {
            node.forEach(item -> requireStringAttribute(item, message));
        }
    }

    private boolean isStringAttribute(JsonNode getAtt) {
        String[] parts = getAttParts(getAtt);
        if (parts == null) {
            return true;
        }
        Map<String, String> attributes = findAttributeSource(parts[0], parts[1]);
        return attributes == null
                || attributeValueResolver.resolve(parts[0], parts[1], attributes.get(parts[1])).isTextual();
    }

    private String resolveSelect(JsonNode select) {
        return resolve(selectedElement(select));
    }

    private JsonNode selectedElement(JsonNode select) {
        if (!select.isArray() || select.size() < 2) {
            return TextNode.valueOf("");
        }
        int index = parseInt(resolve(select.get(0)), 0);
        JsonNode source = select.get(1);
        while (source != null && source.has("Fn::If")) {
            source = selectIfBranch(source.get("Fn::If"));
        }
        List<JsonNode> items = new ArrayList<>();
        if (source != null && source.isArray()) {
            for (JsonNode element : source) {
                if (isListValuedIntrinsic(element)) {
                    resolveList(element).forEach(value -> items.add(TextNode.valueOf(value)));
                } else {
                    items.add(element);
                }
            }
        } else {
            resolveList(source).forEach(value -> items.add(TextNode.valueOf(value)));
        }
        if (index >= 0 && index < items.size()) {
            return items.get(index);
        }
        return TextNode.valueOf("");
    }

    /**
     * Resolves a node that represents a list — a literal array, a list-producing intrinsic
     * ({@code Fn::GetAZs}, {@code Fn::Cidr}, {@code Fn::Split}), an {@code Fn::If} choosing between
     * two such lists, or a comma-delimited scalar (e.g. a {@code Ref} to a {@code List<>} parameter).
     */
    private List<String> resolveList(JsonNode node) {
        return resolveList(node, true);
    }

    /**
     * @param resolveDynamicReferences whether scalar elements pass through the dynamic-reference
     *                                 stage individually; {@code Fn::Join} passes {@code false}
     *                                 because a reference may span several fragments.
     */
    private List<String> resolveList(JsonNode node, boolean resolveDynamicReferences) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return out;
        }
        if (node.isArray()) {
            return resolveListElements(node, resolveDynamicReferences);
        }
        if (node.isObject()) {
            if (node.has("Fn::If")) {
                // Fn::If's branch can itself be any of the shapes this method already handles
                // (literal array, Fn::Split, ...), so recurse into it rather than falling through
                // to the scalar branch below, which would stringify a list-shaped branch instead
                // of splitting it.
                JsonNode branch = selectIfBranch(node.get("Fn::If"));
                return branch == null ? out : resolveList(branch, resolveDynamicReferences);
            }
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
        String scalar = resolveDynamicReferences ? resolve(node) : resolveIntrinsic(node);
        if (!scalar.isEmpty()) {
            out.addAll(Arrays.asList(scalar.split(",", -1)));
        }
        return out;
    }

    /**
     * Expands a literal array's elements, recursing into any element that is itself a
     * list-valued intrinsic ({@code Fn::Split}, {@code Fn::GetAZs}, {@code Fn::Cidr}, or an
     * {@code Fn::If} evaluating to one) so it contributes its own elements rather than one
     * comma-joined string. Unlike {@link #resolveStringList}, this keeps blank entries: a
     * literal array is positional (consumed by {@code Fn::Select} via {@link #resolveList}),
     * so dropping a blank element ahead of the selected index would shift every later index.
     */
    private List<String> resolveListElements(JsonNode node) {
        return resolveListElements(node, true);
    }

    /**
     * A list property written as a literal array, as text, a list-valued intrinsic among its elements
     * expanded in place and blank entries kept: it reaches the resource as written (measured:
     * {@code SecurityGroups ["", "sg-..."]} reaches a rule target as both). See
     * {@link #resolveListElements}.
     */
    public List<String> resolveLiteralList(JsonNode array) {
        return resolveListElements(array);
    }

    private List<String> resolveListElements(JsonNode node, boolean resolveDynamicReferences) {
        List<String> out = new ArrayList<>();
        for (JsonNode element : node) {
            if (isListValuedIntrinsic(element)) {
                out.addAll(resolveList(element, resolveDynamicReferences));
            } else {
                out.add(resolveDynamicReferences ? resolve(element) : resolveIntrinsic(element));
            }
        }
        return out;
    }

    /**
     * Resolves a node to a flat list of strings, expanding list-valued intrinsics
     * ({@code Fn::Split}, {@code Fn::GetAZs}, {@code Fn::Cidr}, or an {@code Fn::If} evaluating
     * to one) whether the node itself is one or they appear as elements of a literal array,
     * and dropping blank entries.
     *
     * <p>Provisioners read list properties (SubnetIds, VPCZoneIdentifier, …) with this so a
     * cross-stack {@code Fn::Split} over {@code Fn::ImportValue} — the shape CDK emits when a
     * VPC exports its subnet ids as one comma-joined value — resolves to the real ids instead
     * of a single comma-joined string or an empty list (issue #2937).
     */
    public List<String> resolveStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return out;
        }
        if (node.isArray()) {
            out.addAll(resolveListElements(node));
        } else {
            out.addAll(resolveList(node));
        }
        out.removeIf(value -> value == null || value.isBlank());
        return out;
    }

    /**
     * Whether {@code node} yields a string, which CloudFormation's template validation refuses where
     * a list is expected, "expected type: JSONArray, found: String" (measured, eu-west-1,
     * 2026-10-18): a literal scalar, a {@code Ref} to a parameter whose type is not a list
     * ({@code String}, {@code Number}, ...) or to a pseudo-parameter but
     * {@code AWS::NotificationARNs}, {@code Fn::Select}, {@code Fn::Join}, {@code Fn::Sub},
     * {@code Fn::Base64}, or an {@code Fn::If} choosing one of these. A {@code Ref} to a {@code CommaDelimitedList},
     * {@code List<...>} or SSM list parameter is a list, though it resolves to the comma-joined
     * text. What only provisioning can tell (a resource's {@code Ref}, {@code Fn::GetAtt},
     * {@code Fn::ImportValue}, {@code Fn::FindInMap}) is not reported here.
     */
    public boolean yieldsString(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isArray()) {
            return false;
        }
        if (node.isValueNode()) {
            return true;
        }
        if (node.has("Ref")) {
            String name = node.get("Ref").asText();
            if (name.startsWith("AWS::")) {
                return !"AWS::NotificationARNs".equals(name) && !"AWS::NoValue".equals(name);
            }
            String type = parameterTypes.get(name);
            return type != null && !isListParameterType(type);
        }
        if (node.has("Fn::If")) {
            return yieldsString(selectIfBranch(node.get("Fn::If")));
        }
        return node.has("Fn::Select") || node.has("Fn::Join") || node.has("Fn::Sub") || node.has("Fn::Base64");
    }

    /** The parameter types whose value is a list: CommaDelimitedList, List<...> and their SSM forms. */
    private static boolean isListParameterType(String type) {
        return "CommaDelimitedList".equals(type) || type.startsWith("List<")
                || "AWS::SSM::Parameter::Value<CommaDelimitedList>".equals(type)
                || type.startsWith("AWS::SSM::Parameter::Value<List<");
    }

    private boolean isListValuedIntrinsic(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isArray()) {
            return true;
        }
        if (node.isObject()) {
            if (node.has("Fn::Split") || node.has("Fn::GetAZs") || node.has("Fn::Cidr")) {
                return true;
            }
            if (node.has("Fn::If")) {
                JsonNode branch = selectIfBranch(node.get("Fn::If"));
                return branch != null && isListValuedIntrinsic(branch);
            }
        }
        return false;
    }

    private List<String> resolveSplit(JsonNode split) {
        if (!split.isArray() || split.size() < 2) {
            return List.of();
        }
        requireStringAttribute(split.get(1), "Template error: every Fn::Split object requires two parameters, "
                + "(1) a string delimiter and (2) a string to be split or a function that returns a string to be split.");
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
        JsonNode branch = selectIfBranch(ifNode);
        return branch == null ? "" : resolve(branch);
    }

    /** Picks Fn::If's true/false branch without resolving it further, so the caller decides
     *  whether to collapse it to a scalar ({@link #resolve}) or preserve its shape ({@link #resolveNode}). */
    private JsonNode selectIfBranch(JsonNode ifNode) {
        if (!ifNode.isArray() || ifNode.size() < 3) {
            return null;
        }
        String conditionName = ifNode.get(0).asText();
        boolean condValue = conditions.getOrDefault(conditionName, false);
        return condValue ? ifNode.get(1) : ifNode.get(2);
    }

    private String resolveGetAtt(JsonNode getAtt) {
        return resolveGetAttNode(getAtt).asText();
    }

    private JsonNode resolveGetAttNode(JsonNode getAtt) {
        String[] parts = getAttParts(getAtt);
        return parts == null ? TextNode.valueOf("") : resolveGetAttNodeParts(parts[0], parts[1]);
    }

    private static String[] getAttParts(JsonNode getAtt) {
        if (getAtt.isArray() && getAtt.size() == 2) {
            return new String[] {getAtt.get(0).asText(), getAtt.get(1).asText()};
        }
        if (getAtt.isTextual()) {
            String[] parts = getAtt.textValue().split("\\.", 2);
            return new String[] {parts[0], parts.length > 1 ? parts[1] : ""};
        }
        return null;
    }

    private Map<String, String> findAttributeSource(String logicalId, String attrName) {
        Map<String, String> attributes = resourceAttributes.get(logicalId);
        // Provisioners keep private state under __Floci keys; AWS resolves no such attribute.
        return attributes != null && attributes.containsKey(attrName) && !attrName.startsWith("__Floci")
                ? attributes : null;
    }

    private JsonNode resolveGetAttNodeParts(String logicalId, String attrName) {
        String value = resolveGetAttParts(logicalId, attrName);
        if (findAttributeSource(logicalId, attrName) != null) {
            return attributeValueResolver.resolve(logicalId, attrName, value);
        }
        return TextNode.valueOf(value);
    }

    private String resolveGetAttParts(String logicalId, String attrName) {
        Map<String, String> attributes = findAttributeSource(logicalId, attrName);
        if (attributes != null) {
            return attributes.get(attrName);
        }
        Map<String, String> attrs = resourceAttributes.get(logicalId);
        if (attrs != null && attrs.containsKey(SERVICE_DISABLED_ATTR)) {
            throw new AwsException("ValidationError", "Template error: " + logicalId
                    + " was stubbed because its service is disabled, so attribute " + attrName
                    + " is unavailable.", 400);
        }
        LOG.warnv("Unresolved GetAtt: {0}.{1}", logicalId, attrName);
        unresolvedIntrinsics.add("Fn::GetAtt " + logicalId + "." + attrName);
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

    /** Whether {@code node} holds {@code Fn::GetStackOutput} anywhere inside it. */
    static boolean containsGetStackOutput(JsonNode node) {
        return node != null && !node.findValues(GET_STACK_OUTPUT).isEmpty();
    }

    /**
     * {@code Fn::GetStackOutput}: the value of another stack's output, read when this resource is
     * created or updated. A stack or an output that does not exist fails the operation, as on AWS;
     * the reference never resolves to an empty string.
     *
     * <p>What AWS does, measured rather than read: a parameter value may use any intrinsic,
     * a resource's {@code Ref} or {@code Fn::GetAtt} included, and it is resolved like any other
     * property; a key the function does not define is ignored; {@code Region} defaults to this
     * stack's, and one of another partition fails the call the way an invalid token does;
     * {@code RoleArn} must parse as an ARN, and whether it can be assumed is the resolver's call.
     * The positions AWS's page lists as unsupported (an Fn::Sub variable map, Fn::Base64, a
     * direct Outputs value) resolve; Conditions and Fn::ImportValue are refused when the stack
     * operation is requested, before this runs.
     */
    private String resolveGetStackOutput(JsonNode args) {
        if (args == null || !args.isObject()) {
            throw new AwsException("ValidationError",
                    "TemplateError: Fn::GetStackOutput requires a map of named parameters", 400);
        }
        String referencedStack = getStackOutputParameter(args, "StackName");
        String outputName = getStackOutputParameter(args, "OutputName");
        String referencedRegion = getStackOutputParameter(args, "Region");
        String roleArn = getStackOutputParameter(args, "RoleArn");
        if (referencedStack.isEmpty()) {
            throw new AwsException("ValidationError", "GetStackOutput: StackName is required", 400);
        }
        if (outputName.isEmpty()) {
            throw new AwsException("ValidationError", "GetStackOutput: OutputName is required", 400);
        }
        if (referencedRegion.isEmpty()) {
            referencedRegion = region;
        } else if (!referencedRegion.equals(region)) {
            if (!AwsRegions.isRegionId(referencedRegion)) {
                throw new AwsException("ValidationError", "TemplateError: Region " + referencedRegion
                        + " in Fn::GetStackOutput is not a valid AWS region.", 400);
            }
            if (!AwsRegions.partitionFor(referencedRegion).equals(AwsRegions.partitionFor(region))) {
                throw new AwsException("InvalidClientTokenId", sdkErrorMessage(
                        "The security token included in the request is invalid", "AmazonCloudFormation", 403,
                        "InvalidClientTokenId"), 403);
            }
        }
        if (roleArn.isEmpty()) {
            roleArn = null;
        } else if (!AwsArnUtils.isArn(roleArn)) {
            throw new AwsException("ValidationError",
                    "TemplateError: Invalid RoleArn parameter " + roleArn + " in Fn::GetStackOutput", 400);
        }
        if (stackOutputResolver == null) {
            throw new AwsException("ValidationError",
                    "Template error: Fn::GetStackOutput cannot be resolved outside a stack", 400);
        }
        return stackOutputResolver.resolve(referencedStack, outputName, referencedRegion, roleArn);
    }

    /**
     * A parameter's value, with every dynamic reference in it left as written, one inside an
     * {@code Fn::Sub} variable map included: CloudFormation does not resolve them there, so the
     * stack name reaches DescribeStacks unresolved and fails its name constraint (measured,
     * eu-west-1, 2026-10-17).
     */
    private String getStackOutputParameter(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || value.isNull()) {
            return "";
        }
        return resolveWithoutDynamicReferences(value);
    }

    /**
     * The message CloudFormation reports for an error a service call returned while it resolved a
     * reference: the service's message followed by the SDK's description of the call.
     */
    static String sdkErrorMessage(String message, String service, int status, String errorCode) {
        return message + " (Service: " + service + "; Status Code: " + status + "; Error Code: " + errorCode
                + "; Request ID: " + UUID.randomUUID() + "; Proxy: null)";
    }
}
