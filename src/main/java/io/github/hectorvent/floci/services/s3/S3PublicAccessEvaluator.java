package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import org.jboss.logging.Logger;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class S3PublicAccessEvaluator {

    private static final Logger LOG = Logger.getLogger(S3PublicAccessEvaluator.class);

    enum PublicAccessDecision {
        ALLOW,
        DENY,
        NEUTRAL
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
                if (!principalMatches(statement, principalType, principalValue)
                        || !actionMatches(statement, action)
                        || !resourceMatches(statement, resourceArn)) {
                    continue;
                }
                if (!conditionsMatch(statement.path("Condition"), context)) {
                    continue;
                }
                if ("Deny".equalsIgnoreCase(effect)) {
                    return PublicAccessDecision.DENY;
                }
                allowed = true;
            }
            return allowed ? PublicAccessDecision.ALLOW : PublicAccessDecision.NEUTRAL;
        } catch (JsonProcessingException e) {
            LOG.debugv("Failed to evaluate S3 bucket policy for principal access: {0}", e.getMessage());
            return PublicAccessDecision.NEUTRAL;
        }
    }

    static String bucketArn(String bucketName) {
        return "arn:aws:s3:::" + bucketName;
    }

    static String objectArn(String bucketName, String key) {
        return bucketArn(bucketName) + "/" + key;
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
