package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Validates Lambda authorizer policies and evaluates them against the requested execute-api ARN,
 * so a policy means the same thing on REST, HTTP and WebSocket APIs.
 */
@ApplicationScoped
public class AuthorizerPolicyEvaluator {

    private final IamPolicyEvaluator iamPolicyEvaluator;

    @Inject
    public AuthorizerPolicyEvaluator(IamPolicyEvaluator iamPolicyEvaluator) {
        this.iamPolicyEvaluator = iamPolicyEvaluator;
    }

    /**
     * Rejects a policy document API Gateway cannot evaluate.
     *
     * @throws IllegalArgumentException when the policy is malformed
     */
    public static void validate(JsonNode policy) {
        JsonNode statements = policy.path("Statement");
        if (!policy.isObject() || (!statements.isObject() && !statements.isArray()) || statements.isEmpty()) {
            throw new IllegalArgumentException("Authorizer must return a policy with statements");
        }
        if (statements.isObject()) {
            validateStatement(statements);
            return;
        }
        for (JsonNode statement : statements) {
            validateStatement(statement);
        }
    }

    private static void validateStatement(JsonNode statement) {
        String effect = statement.path("Effect").asText();
        if (!statement.isObject() || (!"Allow".equals(effect) && !"Deny".equals(effect))
                || (statement.has("Action") == statement.has("NotAction"))
                || (statement.has("Resource") == statement.has("NotResource"))
                || !validStringOrList(statement.has("Action") ? statement.get("Action") : statement.path("NotAction"))
                || !validStringOrList(statement.has("Resource") ? statement.get("Resource") : statement.path("NotResource"))
                || (statement.has("Condition") && !validCondition(statement.get("Condition")))) {
            throw new IllegalArgumentException("Invalid authorizer policy statement");
        }
    }

    /**
     * An operator IAM does not know, or one with no keys or values, would otherwise evaluate as
     * a non-match or an unconditional match, so a malformed Deny could be skipped or a malformed
     * Allow could grant access. An empty {@code Condition} object stays valid, as in IAM.
     */
    private static boolean validCondition(JsonNode condition) {
        if (!condition.isObject()) {
            return false;
        }
        for (Map.Entry<String, JsonNode> operator : condition.properties()) {
            JsonNode keys = operator.getValue();
            if (!IamPolicyEvaluator.isSupportedConditionOperator(operator.getKey())
                    || !keys.isObject() || keys.isEmpty()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> key : keys.properties()) {
                if (key.getKey().isEmpty() || !validConditionValues(key.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validConditionValues(JsonNode values) {
        if (!values.isArray()) {
            return validConditionValue(values);
        }
        if (values.isEmpty()) {
            return false;
        }
        for (JsonNode value : values) {
            if (!validConditionValue(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validConditionValue(JsonNode value) {
        return value.isTextual() || value.isNumber() || value.isBoolean();
    }

    private static boolean validStringOrList(JsonNode value) {
        if (value.isTextual()) {
            return !value.asText().isEmpty();
        }
        if (!value.isArray() || value.isEmpty()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The global condition keys an authorizer policy can test for the current request. */
    public static Map<String, List<String>> requestConditions(String sourceIp, boolean secureTransport) {
        return Map.of("aws:SourceIp", List.of(sourceIp),
                "aws:SecureTransport", List.of(Boolean.toString(secureTransport)),
                "aws:CurrentTime", List.of(Instant.now().toString()));
    }

    /**
     * Validates the policy, then returns whether a matching Allow grants invocation without a
     * matching explicit Deny.
     *
     * @throws IllegalArgumentException when the policy is malformed
     */
    public boolean permits(JsonNode policyDocument, String methodArn, String sourceIp, boolean secureTransport) {
        validate(policyDocument);
        return permits(policyDocument.toString(), methodArn, requestConditions(sourceIp, secureTransport));
    }

    /** Returns whether an already validated policy grants invocation of the method ARN. */
    public boolean permits(String policyDocument, String methodArn, Map<String, List<String>> conditions) {
        return iamPolicyEvaluator.evaluate(CallerContext.of(List.of(policyDocument)), null,
                "execute-api:Invoke", methodArn, conditions) == IamPolicyEvaluator.Decision.ALLOW;
    }
}
