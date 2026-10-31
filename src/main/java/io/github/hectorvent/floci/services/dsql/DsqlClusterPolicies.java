package io.github.hectorvent.floci.services.dsql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.PublicAccessConditions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A cluster's resource policy as DSQL reads and keeps it (measured, eu-west-1, 2026-10-20).
 *
 * <p>DSQL refuses a document with one of three texts: one for JSON it cannot read and for a
 * statement without a principal or a resource, one for a document missing or misusing an element,
 * and one for a service principal. It keeps the document in its own form, which GetClusterPolicy
 * returns: Jackson's default pretty printer, the elements in a fixed order, a list of one value
 * written as the value, condition values as text, a principal's account id as its root ARN, and
 * the operators and keys of a condition in the order a {@link HashMap} holds them. A statement
 * that grants to everyone is refused as public unless a condition restricts it; how DSQL tells is
 * {@link #restricts}.
 */
final class DsqlClusterPolicies {

    static final String UNREADABLE = "Aurora DSQL can't process the request because the policy that you specified"
            + " isn't valid. Provide the policy in a valid format and try again.";
    static final String INVALID = "Invalid input for 'policy'. Aurora DSQL can't apply the policy because the policy"
            + " document isn't valid. Make sure your policy includes all required elements (Version, Statement,"
            + " Principal, Action, and Resource) in valid JSON format and try again.";
    static final String SERVICE_PRINCIPAL = "Aurora DSQL can't process the request because the policy contains an"
            + " invalid service principal. Verify the service principal and try again.";
    static final String PUBLIC = "Aurora DSQL can't apply the policy because it grants public access. Modify the"
            + " policy to restrict access and try again.";

    private static final Set<String> DOCUMENT_KEYS = Set.of("Version", "Id", "Statement");
    private static final Set<String> STATEMENT_KEYS = Set.of(
            "Sid", "Effect", "Principal", "NotPrincipal", "Action", "NotAction", "Resource", "NotResource", "Condition");
    private static final Set<String> VERSIONS = Set.of("2008-10-17", "2012-10-17");
    /** Measured: a document without a Version is kept as the older one. */
    private static final String DEFAULT_VERSION = "2008-10-17";
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern IAM_ARN = Pattern.compile("arn:[^:]+:iam::\\d{12}:(root|role/.+|user/.+)");
    /** A role session, which has no record to look up. */
    private static final Pattern SESSION_ARN = Pattern.compile("arn:[^:]+:sts::\\d{12}:assumed-role/.+");
    /** Jackson's default pretty printer, with the line feed AWS writes on any platform. */
    private static final DefaultPrettyPrinter PRETTY =
            new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));
    private static final String SOURCE_IP = "aws:sourceip";
    private static final String PRINCIPAL_IS_AWS_SERVICE = "aws:principalisawsservice";
    /**
     * The trusted condition keys of DSQL's block public access page and those it was measured to
     * accept too, {@code aws:SourceIp}, {@code aws:SourceVpcArn} and the three {@code aws:Vpce*}:
     * the keys Lambda shares, and {@code aws:SourceIp}. Keys are matched in any case (measured).
     */
    private static final Set<String> RESTRICTING_KEYS =
            lowercase(PublicAccessConditions.with(PublicAccessConditions.TRUSTED_KEYS, "aws:SourceIp"));
    private static final Set<String> ARN_KEYS = lowercase(PublicAccessConditions.ARN_KEYS);

    private DsqlClusterPolicies() {
    }

    /**
     * The policy in the form DSQL keeps it, or the refusal DSQL gives. {@code principalExists}
     * answers for an IAM principal ARN, which DSQL refuses when it names no one.
     */
    static String canonical(ObjectMapper mapper, String policy, String partition, Predicate<String> principalExists) {
        JsonNode document;
        try {
            document = mapper.readTree(policy);
        } catch (JsonProcessingException e) {
            throw refused(UNREADABLE);
        }
        if (document == null || !document.isObject() || !DOCUMENT_KEYS.containsAll(fieldNames(document))) {
            throw refused(INVALID);
        }
        ObjectNode kept = mapper.createObjectNode();
        JsonNode version = document.get("Version");
        if (version != null && !(version.isTextual() && VERSIONS.contains(version.textValue()))) {
            throw refused(INVALID);
        }
        kept.put("Version", version == null ? DEFAULT_VERSION : version.textValue());
        if (document.has("Id")) {
            kept.set("Id", text(document.get("Id")));
        }
        JsonNode statements = document.get("Statement");
        List<JsonNode> each = new ArrayList<>();
        if (statements != null && statements.isObject()) {
            each.add(statements);
        } else if (statements != null && statements.isArray()) {
            statements.forEach(each::add);
        }
        if (each.isEmpty()) {
            throw refused(INVALID);
        }
        ArrayNode keptStatements = kept.putArray("Statement");
        for (JsonNode statement : each) {
            keptStatements.add(statement(mapper, statement, partition, principalExists));
        }
        try {
            return mapper.writer(PRETTY).writeValueAsString(kept);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A kept policy is written as JSON", e);
        }
    }

    /** Whether DSQL refuses the kept policy as public: an Allow to everyone that no condition restricts. */
    static boolean grantsPublicAccess(ObjectMapper mapper, String keptPolicy) {
        try {
            for (JsonNode statement : mapper.readTree(keptPolicy).path("Statement")) {
                if ("Allow".equals(statement.path("Effect").asText())
                        && grantsToEveryone(statement.path("Principal"))
                        && !restricts(statement.path("Condition"))) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A kept policy is JSON", e);
        }
    }

    private static ObjectNode statement(ObjectMapper mapper, JsonNode statement, String partition,
                                        Predicate<String> principalExists) {
        if (!statement.isObject() || !STATEMENT_KEYS.containsAll(fieldNames(statement))) {
            throw refused(INVALID);
        }
        String effect = statement.path("Effect").asText("");
        if (!effect.equalsIgnoreCase("Allow") && !effect.equalsIgnoreCase("Deny")) {
            throw refused(INVALID);
        }
        if (statement.has("NotPrincipal") || !statement.has("Principal")
                || statement.has("NotResource") || !statement.has("Resource")) {
            throw refused(UNREADABLE);
        }
        ObjectNode kept = mapper.createObjectNode();
        if (statement.has("Sid")) {
            kept.set("Sid", text(statement.get("Sid")));
        }
        kept.put("Effect", effect.equalsIgnoreCase("Allow") ? "Allow" : "Deny");
        kept.set("Principal", principal(mapper, statement.get("Principal"), partition, principalExists));
        for (String member : List.of("Action", "NotAction", "Resource")) {
            if (statement.has(member)) {
                kept.set(member, values(mapper, statement.get(member)));
            }
        }
        if (statement.has("Condition")) {
            kept.set("Condition", condition(mapper, statement.get("Condition")));
        }
        return kept;
    }

    private static JsonNode principal(ObjectMapper mapper, JsonNode principal, String partition,
                                      Predicate<String> principalExists) {
        if (principal.isTextual()) {
            if (!"*".equals(principal.textValue())) {
                throw refused(INVALID);
            }
            return principal;
        }
        if (!principal.isObject() || principal.isEmpty()) {
            throw refused(INVALID);
        }
        JsonNode service = principal.get("Service");
        if (service != null) {
            throw refused(texts(service).contains("*") ? INVALID : SERVICE_PRINCIPAL);
        }
        Map<String, JsonNode> kept = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> types = principal.fields();
        while (types.hasNext()) {
            Map.Entry<String, JsonNode> type = types.next();
            switch (type.getKey()) {
                case "AWS" -> {
                    List<String> arns = new ArrayList<>();
                    for (String value : texts(type.getValue())) {
                        String arn = ACCOUNT_ID.matcher(value).matches()
                                ? AwsArnUtils.Arn.global(partition, "iam", value, "root").toString() : value;
                        boolean known = "*".equals(arn) || SESSION_ARN.matcher(arn).matches()
                                || IAM_ARN.matcher(arn).matches() && principalExists.test(arn);
                        if (!known) {
                            throw refused(INVALID);
                        }
                        arns.add(arn);
                    }
                    kept.put("AWS", written(mapper, arns));
                }
                case "Federated" -> kept.put("Federated", values(mapper, type.getValue()));
                default -> throw refused(INVALID);
            }
        }
        ObjectNode node = mapper.createObjectNode();
        kept.forEach(node::set);
        return node;
    }

    private static ObjectNode condition(ObjectMapper mapper, JsonNode condition) {
        if (!condition.isObject()) {
            throw refused(INVALID);
        }
        Map<String, Map<String, JsonNode>> operators = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> each = condition.fields();
        while (each.hasNext()) {
            Map.Entry<String, JsonNode> operator = each.next();
            // Measured: operators are matched as written, so "stringequals" is refused.
            if (!IamPolicyEvaluator.isSupportedConditionOperator(operator.getKey()) || !operator.getValue().isObject()) {
                throw refused(INVALID);
            }
            Map<String, JsonNode> keys = new HashMap<>();
            operator.getValue().fields().forEachRemaining(key -> keys.put(key.getKey(), values(mapper, key.getValue())));
            operators.put(operator.getKey(), keys);
        }
        ObjectNode node = mapper.createObjectNode();
        operators.forEach((operator, keys) -> {
            ObjectNode keysNode = node.putObject(operator);
            keys.forEach(keysNode::set);
        });
        return node;
    }

    /**
     * Whether a condition keeps a statement that grants to everyone from being public, as DSQL
     * decides (measured): one of the restricting keys, compared with an operator that cannot be met
     * by just anyone. No value may hold a policy variable, which the caller could fill in. A string
     * equality restricts whatever else it names, even {@code "*"}, which it takes literally; a string
     * pattern only a value without a wildcard, and for an ARN key one that pins the account. An ARN
     * operator's value that is no ARN matches nothing, so it restricts; an ARN must pin the account.
     * {@code aws:SourceIp} restricts through a narrow range, and {@code aws:PrincipalIsAWSService}
     * when it is true. {@code ForAnyValue:} reads as the operator it qualifies; {@code ForAllValues:},
     * {@code IfExists} and the negated operators let a caller without the key through, so they never
     * restrict.
     */
    static boolean restricts(JsonNode condition) {
        Iterator<Map.Entry<String, JsonNode>> operators = condition.fields();
        while (operators.hasNext()) {
            Map.Entry<String, JsonNode> operator = operators.next();
            String name = operator.getKey();
            if (name.startsWith("ForAnyValue:")) {
                name = name.substring("ForAnyValue:".length());
            }
            Iterator<Map.Entry<String, JsonNode>> keys = operator.getValue().fields();
            while (keys.hasNext()) {
                Map.Entry<String, JsonNode> key = keys.next();
                String lowered = key.getKey().toLowerCase(Locale.ROOT);
                if (RESTRICTING_KEYS.contains(lowered) && restricts(name, lowered, texts(key.getValue()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean restricts(String operator, String key, List<String> values) {
        if (SOURCE_IP.equals(key) && "IpAddress".equals(operator)) {
            return values.stream().allMatch(PublicAccessConditions::isNarrowSourceIp);
        }
        return switch (operator) {
            case "StringEquals", "StringEqualsIgnoreCase" -> values.stream().noneMatch(value -> value.contains("${"));
            case "StringLike" -> values.stream().allMatch(value -> ARN_KEYS.contains(key)
                    ? PublicAccessConditions.pinsAccount(value) && !value.contains("${")
                    : PublicAccessConditions.isFixedValue(value));
            case "ArnEquals", "ArnLike" -> values.stream().allMatch(value -> value.split(":", 6).length < 6
                    || PublicAccessConditions.pinsAccount(value) && !value.contains("${"));
            case "Bool" -> PRINCIPAL_IS_AWS_SERVICE.equals(key) && values.stream().allMatch("true"::equalsIgnoreCase);
            default -> false;
        };
    }

    private static boolean grantsToEveryone(JsonNode principal) {
        return principal.isTextual() ? "*".equals(principal.textValue()) : texts(principal.path("AWS")).contains("*");
    }

    /** A value or a list of values, kept as text, the list of one written as its value. */
    private static JsonNode values(ObjectMapper mapper, JsonNode node) {
        return written(mapper, texts(node));
    }

    private static JsonNode written(ObjectMapper mapper, List<String> values) {
        if (values.size() == 1) {
            return mapper.getNodeFactory().textNode(values.getFirst());
        }
        ArrayNode list = mapper.createArrayNode();
        values.forEach(list::add);
        return list;
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (node.isValueNode()) {
            values.add(node.asText());
            return values;
        }
        if (!node.isArray() || node.isEmpty()) {
            throw refused(INVALID);
        }
        for (JsonNode item : node) {
            if (!item.isValueNode() || item.isNull()) {
                throw refused(INVALID);
            }
            values.add(item.asText());
        }
        return values;
    }

    private static JsonNode text(JsonNode node) {
        if (!node.isTextual()) {
            throw refused(INVALID);
        }
        return node;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> lowercase(Set<String> keys) {
        Set<String> lowered = new HashSet<>();
        keys.forEach(key -> lowered.add(key.toLowerCase(Locale.ROOT)));
        return Set.copyOf(lowered);
    }

    private static AwsException refused(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
