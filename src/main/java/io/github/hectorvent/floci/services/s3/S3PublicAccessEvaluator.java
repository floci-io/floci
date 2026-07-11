package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import org.jboss.logging.Logger;

import java.util.Iterator;
import java.util.List;

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
