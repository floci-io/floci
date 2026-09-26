package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import org.jboss.logging.Logger;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

final class S3PublicAccessEvaluator {

    private static final Logger LOG = Logger.getLogger(S3PublicAccessEvaluator.class);

    /**
     * The condition keys that, pinned to a fixed value, keep a statement with a wildcard
     * principal out of Block Public Access's definition of public.
     */
    private static final Set<String> NON_PUBLIC_CONDITION_KEYS = Set.of(
            "aws:principalarn",
            "aws:principalaccount",
            "aws:principalorgid",
            "aws:principalorgpaths",
            "aws:sourcearn",
            "aws:sourcevpc",
            "aws:sourcevpce",
            "aws:sourceowner",
            "aws:sourceaccount",
            "aws:userid",
            "s3:dataaccesspointarn",
            "s3:dataaccesspointaccount");

    /** Recognised like the keys above, but the value additionally has to be a narrow range. */
    private static final String SOURCE_IP_CONDITION_KEY = "aws:sourceip";
    private static final String DATA_ACCESS_POINT_ARN_CONDITION_KEY = "s3:dataaccesspointarn";
    private static final Pattern BUCKET_POLICY_ACCESS_POINT_ARN = Pattern.compile(
            "arn:[a-z0-9-]+:s3:[a-z0-9-]+:[0-9]{12}:accesspoint/[a-zA-Z0-9*?._-]+");

    enum PublicAccessDecision {
        ALLOW,
        DENY,
        NEUTRAL
    }

    record PrincipalPolicyEvaluation(
            PublicAccessDecision decision,
            boolean directPrincipalAllow
    ) {
    }

    private S3PublicAccessEvaluator() {
    }

    static boolean publicPolicyAllows(ObjectMapper objectMapper, String policy, String action, String resourceArn) {
        return publicPolicyDecision(objectMapper, policy, action, resourceArn) == PublicAccessDecision.ALLOW;
    }

    static PublicAccessDecision publicPolicyDecision(ObjectMapper objectMapper, String policy, String action, String resourceArn) {
        if (policy == null || policy.isBlank()) {
            return PublicAccessDecision.NEUTRAL;
        }
        try {
            JsonNode statements = objectMapper.readTree(policy).path("Statement");
            boolean allowed = false;
            Iterable<JsonNode> iterable = statements.isArray() ? statements : List.of(statements);
            for (JsonNode statement : iterable) {
                String effect = statement.path("Effect").asText("");
                if (!"Allow".equalsIgnoreCase(effect) && !"Deny".equalsIgnoreCase(effect)) {
                    continue;
                }
                if (!statementMatchesPublicPrincipalActionResource(statement, action, resourceArn)) {
                    continue;
                }
                if ("Deny".equalsIgnoreCase(effect)) {
                    return PublicAccessDecision.DENY;
                }
                if (statement.hasNonNull("Condition")) {
                    continue;
                }
                allowed = true;
            }
            return allowed ? PublicAccessDecision.ALLOW : PublicAccessDecision.NEUTRAL;
        } catch (JsonProcessingException e) {
            LOG.debugv("Failed to evaluate S3 bucket policy for public access: {0}", e.getMessage());
            return PublicAccessDecision.NEUTRAL;
        }
    }

    static PublicAccessDecision principalPolicyDecision(
            ObjectMapper objectMapper,
            String policy,
            String principalType,
            String principalValue,
            String action,
            String resourceArn,
            Map<String, String> context) {
        return principalPolicyEvaluation(
                objectMapper, policy, principalType, principalValue, action, resourceArn, context).decision();
    }

    static PrincipalPolicyEvaluation principalPolicyEvaluation(
            ObjectMapper objectMapper,
            String policy,
            String principalType,
            String principalValue,
            String action,
            String resourceArn,
            Map<String, String> context) {
        if (policy == null || policy.isBlank()) {
            return new PrincipalPolicyEvaluation(PublicAccessDecision.NEUTRAL, false);
        }
        try {
            JsonNode statements = objectMapper.readTree(policy).path("Statement");
            boolean allowed = false;
            boolean directPrincipalAllow = false;
            Iterable<JsonNode> iterable = statements.isArray() ? statements : List.of(statements);
            for (JsonNode statement : iterable) {
                String effect = statement.path("Effect").asText("");
                if (!"Allow".equalsIgnoreCase(effect) && !"Deny".equalsIgnoreCase(effect)) {
                    continue;
                }
                if (!principalMatches(statement, principalType, principalValue)
                        || !actionMatches(statement, action)
                        || !resourceMatches(statement, resourceArn)) {
                    continue;
                }
                if (!conditionsMatch(statement.path("Condition"), context)) {
                    continue;
                }
                if ("Deny".equalsIgnoreCase(effect)) {
                    return new PrincipalPolicyEvaluation(PublicAccessDecision.DENY, false);
                }
                allowed = true;
                directPrincipalAllow |= principalDirectlyMatches(
                        statement, principalType, principalValue);
            }
            PublicAccessDecision decision = allowed
                    ? PublicAccessDecision.ALLOW : PublicAccessDecision.NEUTRAL;
            return new PrincipalPolicyEvaluation(decision, directPrincipalAllow);
        } catch (JsonProcessingException e) {
            LOG.debugv("Failed to evaluate S3 bucket policy for principal access: {0}", e.getMessage());
            return new PrincipalPolicyEvaluation(PublicAccessDecision.NEUTRAL, false);
        }
    }

    /**
     * Whether Block Public Access considers this bucket policy public. AWS begins by assuming a
     * policy is public and then looks for a reason it is not: a statement that grants to everyone
     * is non-public only when it pins one of the recognised condition keys to a fixed value, one
     * containing neither a wildcard nor an IAM policy variable. A single public statement makes
     * the whole policy public, which is what {@code RestrictPublicBuckets} keys off.
     *
     * <p>Bucket policies can use a wildcard in the access point name when the account is fixed.
     * Floci does not model access points, so the different access-point-policy rule does not apply.
     */
    static boolean policyIsPublic(ObjectMapper objectMapper, String policy) {
        if (policy == null || policy.isBlank()) {
            return false;
        }
        try {
            JsonNode statements = objectMapper.readTree(policy).path("Statement");
            Iterable<JsonNode> iterable = statements.isArray() ? statements : List.of(statements);
            for (JsonNode statement : iterable) {
                if (statementIsPublic(statement)) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException e) {
            LOG.debugv("Failed to evaluate S3 bucket policy status: {0}", e.getMessage());
            return false;
        }
    }

    private static boolean statementIsPublic(JsonNode statement) {
        if (statement == null || !statement.isObject()) {
            return false;
        }
        if (!"Allow".equalsIgnoreCase(statement.path("Effect").asText(""))) {
            return false;
        }
        if (!grantsToEveryone(statement)) {
            return false;
        }
        return !conditionPinsToFixedValues(statement.path("Condition"));
    }

    private static boolean grantsToEveryone(JsonNode statement) {
        if (statement.hasNonNull("Principal")) {
            return hasPublicPrincipal(statement.path("Principal"));
        }
        // An Allow on NotPrincipal grants to everyone the statement does not name.
        return statement.hasNonNull("NotPrincipal");
    }

    private static boolean conditionPinsToFixedValues(JsonNode conditions) {
        if (conditions == null || !conditions.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> operators = conditions.fields();
        while (operators.hasNext()) {
            Map.Entry<String, JsonNode> operator = operators.next();
            String operatorName = operator.getKey();
            if (operatorName.regionMatches(true, 0, "ForAnyValue:", 0, "ForAnyValue:".length())) {
                operatorName = operatorName.substring("ForAnyValue:".length());
            }
            if (!operator.getValue().isObject() || !operatorNarrowsAccess(operatorName)) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> entries = operator.getValue().fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (DATA_ACCESS_POINT_ARN_CONDITION_KEY.equals(key)
                        && allValuesFixed(entry.getValue(), S3PublicAccessEvaluator::isFixedAccountAccessPointArn)) {
                    return true;
                }
                if (NON_PUBLIC_CONDITION_KEYS.contains(key)
                        && !operatorName.equalsIgnoreCase("IpAddress")
                        && allValuesFixed(entry.getValue(), S3PublicAccessEvaluator::isFixedValue)) {
                    return true;
                }
                if (SOURCE_IP_CONDITION_KEY.equals(key)
                        && operatorName.equalsIgnoreCase("IpAddress")
                        && allSourceIpRangesNarrow(entry.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean operatorNarrowsAccess(String operator) {
        return switch (operator.toLowerCase(Locale.ROOT)) {
            case "stringequals", "stringlike", "arnequals", "arnlike", "ipaddress" -> true;
            default -> false;
        };
    }

    private static boolean allValuesFixed(JsonNode value, Predicate<String> isFixed) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return isFixed.test(value.asText());
        }
        if (value.isArray() && !value.isEmpty()) {
            for (JsonNode item : value) {
                if (!item.isTextual() || !isFixed.test(item.asText())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isFixedValue(String value) {
        return !value.isEmpty()
                && value.indexOf('*') < 0
                && value.indexOf('?') < 0
                && !value.contains("${");
    }

    private static boolean isFixedAccountAccessPointArn(String value) {
        return BUCKET_POLICY_ACCESS_POINT_ARN.matcher(value).matches();
    }

    private static boolean allSourceIpRangesNarrow(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return isNarrowCidr(value.asText());
        }
        if (value.isArray() && !value.isEmpty()) {
            for (JsonNode item : value) {
                if (!item.isTextual() || !isNarrowCidr(item.asText())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * AWS still calls a policy public when it is conditioned on an {@code aws:SourceIp} range
     * broader than /8 for IPv4 or /32 for IPv6, so only a prefix at least that long makes the
     * statement non-public. A bare address carries no prefix and is as narrow as a range gets.
     */
    private static boolean isNarrowCidr(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            return isFixedValue(cidr);
        }
        String address = cidr.substring(0, slash);
        if (!isFixedValue(address)) {
            return false;
        }
        try {
            int prefixLength = Integer.parseInt(cidr.substring(slash + 1).trim());
            return address.indexOf(':') >= 0 ? prefixLength >= 32 : prefixLength >= 8;
        } catch (NumberFormatException e) {
            LOG.debugv("Treating aws:SourceIp value with an unparseable prefix as public: {0}", cidr);
            return false;
        }
    }

    static String bucketArn(String partition, String bucketName) {
        return AwsArnUtils.Arn.global(partition, "s3", "", bucketName).toString();
    }

    static String objectArn(String partition, String bucketName, String key) {
        return bucketArn(partition, bucketName) + "/" + key;
    }

    private static boolean statementMatchesPublicPrincipalActionResource(JsonNode statement, String action, String resourceArn) {
        return principalMatchesPublic(statement)
                && actionMatches(statement, action)
                && resourceMatches(statement, resourceArn);
    }

    private static boolean principalMatchesPublic(JsonNode statement) {
        if (statement.hasNonNull("Principal")) {
            return hasPublicPrincipal(statement.path("Principal"));
        }
        if (statement.hasNonNull("NotPrincipal")) {
            return !hasPublicPrincipal(statement.path("NotPrincipal"));
        }
        return false;
    }

    private static boolean principalMatches(
            JsonNode statement, String principalType, String principalValue) {
        if (statement.hasNonNull("Principal")) {
            return principalNodeMatches(statement.path("Principal"), principalType, principalValue);
        }
        if (statement.hasNonNull("NotPrincipal")) {
            return !principalNodeMatches(
                    statement.path("NotPrincipal"), principalType, principalValue);
        }
        return false;
    }

    private static boolean principalDirectlyMatches(
            JsonNode statement, String principalType, String principalValue) {
        if (!statement.hasNonNull("Principal")) {
            return false;
        }
        JsonNode principal = statement.path("Principal");
        return principalNodeDirectlyMatches(principal, principalType, principalValue)
                || ("AWS".equalsIgnoreCase(principalType)
                && hasPublicPrincipal(principal)
                && conditionDirectlyMatchesPrincipalArn(
                        statement.path("Condition"), principalValue));
    }

    private static boolean conditionDirectlyMatchesPrincipalArn(
            JsonNode conditions, String principalValue) {
        if (conditions == null || !conditions.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> operators = conditions.fields();
        while (operators.hasNext()) {
            Map.Entry<String, JsonNode> operator = operators.next();
            if (!("StringEquals".equalsIgnoreCase(operator.getKey())
                    || "ArnEquals".equalsIgnoreCase(operator.getKey()))
                    || !operator.getValue().isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> entries = operator.getValue().fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                if ("aws:PrincipalArn".equalsIgnoreCase(entry.getKey())
                        && conditionValueMatches(entry.getValue(), principalValue, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean principalNodeDirectlyMatches(
            JsonNode principal, String principalType, String principalValue) {
        if (principal == null || principal.isMissingNode() || principal.isNull()) {
            return false;
        }
        if (principal.isTextual() || principal.isArray()) {
            return principalValueDirectlyMatches(principal, principalValue);
        }
        if (!principal.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = principal.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase(principalType)
                    && principalValueDirectlyMatches(field.getValue(), principalValue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean principalValueDirectlyMatches(JsonNode candidate, String principalValue) {
        if (candidate == null || candidate.isNull()) {
            return false;
        }
        if (candidate.isTextual()) {
            return candidate.asText().equals(principalValue);
        }
        if (candidate.isArray()) {
            for (JsonNode item : candidate) {
                if (principalValueDirectlyMatches(item, principalValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean principalNodeMatches(
            JsonNode principal, String principalType, String principalValue) {
        if (principal == null || principal.isMissingNode() || principal.isNull()) {
            return false;
        }
        if (principal.isTextual() || principal.isArray()) {
            return principalValueMatches(principal, principalValue);
        }
        if (!principal.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = principal.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase(principalType)
                    && principalValueMatches(field.getValue(), principalValue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean principalValueMatches(JsonNode candidate, String principalValue) {
        if (candidate == null || candidate.isNull()) {
            return false;
        }
        if (candidate.isTextual()) {
            String value = candidate.asText();
            return "*".equals(value) || value.equals(principalValue);
        }
        if (candidate.isArray()) {
            for (JsonNode item : candidate) {
                if (principalValueMatches(item, principalValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean conditionsMatch(JsonNode conditions, Map<String, String> context) {
        if (conditions == null || conditions.isMissingNode() || conditions.isNull()) {
            return true;
        }
        if (!conditions.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> operators = conditions.fields();
        while (operators.hasNext()) {
            Map.Entry<String, JsonNode> operator = operators.next();
            boolean glob = "StringLike".equalsIgnoreCase(operator.getKey())
                    || "ArnLike".equalsIgnoreCase(operator.getKey());
            boolean exact = "StringEquals".equalsIgnoreCase(operator.getKey())
                    || "ArnEquals".equalsIgnoreCase(operator.getKey());
            if ((!glob && !exact) || !operator.getValue().isObject()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> entries = operator.getValue().fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String actual = contextValue(context, entry.getKey());
                if (actual == null || !conditionValueMatches(entry.getValue(), actual, glob)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String contextValue(Map<String, String> context, String key) {
        if (context == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : context.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean conditionValueMatches(JsonNode expected, String actual, boolean glob) {
        if (expected == null || expected.isNull()) {
            return false;
        }
        if (expected.isTextual()) {
            return glob
                    ? IamPolicyEvaluator.globMatches(expected.asText(), actual)
                    : expected.asText().equals(actual);
        }
        if (expected.isArray()) {
            for (JsonNode item : expected) {
                if (conditionValueMatches(item, actual, glob)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean actionMatches(JsonNode statement, String action) {
        if (statement.hasNonNull("Action")) {
            return nodeMatches(statement.get("Action"), action);
        }
        if (statement.hasNonNull("NotAction")) {
            JsonNode notAction = statement.get("NotAction");
            return nodeCanMatch(notAction) && !nodeMatches(notAction, action);
        }
        return false;
    }

    private static boolean resourceMatches(JsonNode statement, String resourceArn) {
        if (statement.hasNonNull("Resource")) {
            return nodeMatches(statement.get("Resource"), resourceArn);
        }
        if (statement.hasNonNull("NotResource")) {
            JsonNode notResource = statement.get("NotResource");
            return nodeCanMatch(notResource) && !nodeMatches(notResource, resourceArn);
        }
        return false;
    }

    private static boolean hasPublicPrincipal(JsonNode principal) {
        if (principal == null || principal.isMissingNode() || principal.isNull()) {
            return false;
        }
        if (principal.isTextual()) {
            return "*".equals(principal.asText());
        }
        if (principal.isArray()) {
            for (JsonNode item : principal) {
                if ("*".equals(item.asText())) {
                    return true;
                }
            }
            return false;
        }
        if (principal.isObject()) {
            Iterator<JsonNode> values = principal.elements();
            while (values.hasNext()) {
                if (nodeContainsPublicPrincipal(values.next())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean nodeContainsPublicPrincipal(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return "*".equals(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if ("*".equals(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean nodeCanMatch(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return true;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean nodeMatches(JsonNode node, String value) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return IamPolicyEvaluator.globMatches(node.asText(), value);
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && IamPolicyEvaluator.globMatches(item.asText(), value)) {
                    return true;
                }
            }
        }
        return false;
    }
}
