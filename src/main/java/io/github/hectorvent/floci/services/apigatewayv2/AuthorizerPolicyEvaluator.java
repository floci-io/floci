package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** Evaluates Lambda authorizer policies against the requested execute-api resource. */
@ApplicationScoped
public class AuthorizerPolicyEvaluator {

    private final IamPolicyEvaluator iamPolicyEvaluator;

    @Inject
    public AuthorizerPolicyEvaluator(IamPolicyEvaluator iamPolicyEvaluator) {
        this.iamPolicyEvaluator = iamPolicyEvaluator;
    }

    /**
     * Returns whether a matching Allow grants invocation without a matching explicit Deny.
     *
     * @throws IllegalArgumentException when the policy is malformed or the request source IP is unavailable
     */
    public boolean permits(JsonNode policyDocument, String methodArn, String sourceIp) {
        validatePolicy(policyDocument);
        if (sourceIp == null || sourceIp.isBlank()) {
            throw new IllegalArgumentException("Authorizer request source IP is unavailable");
        }
        Map<String, List<String>> conditionContext = Map.of("aws:SourceIp", List.of(sourceIp));
        return iamPolicyEvaluator.evaluate(CallerContext.of(List.of(policyDocument.toString())), null,
                "execute-api:Invoke", methodArn, conditionContext)
                == IamPolicyEvaluator.Decision.ALLOW;
    }

    private void validatePolicy(JsonNode policyDocument) {
        if (!policyDocument.isObject()) {
            throw new IllegalArgumentException("Authorizer policyDocument must be an object");
        }
        JsonNode statements = policyDocument.path("Statement");
        if (statements.isObject()) {
            validateStatement(statements);
            return;
        }
        if (!statements.isArray() || statements.isEmpty()) {
            throw new IllegalArgumentException("Authorizer policy requires a nonempty Statement object or array");
        }
        for (JsonNode statement : statements) {
            validateStatement(statement);
        }
    }

    private void validateStatement(JsonNode statement) {
        String effect = statement.path("Effect").asText();
        if (!statement.isObject() || !("Allow".equals(effect) || "Deny".equals(effect))) {
            throw new IllegalArgumentException("Authorizer statement requires Effect Allow or Deny");
        }
        validateAlternatives(statement, "Action", "NotAction");
        validateAlternatives(statement, "Resource", "NotResource");
        if (statement.has("Condition")) {
            validateCondition(statement.get("Condition"));
        }
    }

    private void validateAlternatives(JsonNode statement, String field, String alternative) {
        if (statement.has(field) == statement.has(alternative)
                || !isStringOrList(statement.has(field) ? statement.get(field) : statement.get(alternative))) {
            throw new IllegalArgumentException("Authorizer statement requires " + field + " or " + alternative
                    + " as a nonempty string or string array");
        }
    }

    private boolean isStringOrList(JsonNode value) {
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

    private void validateCondition(JsonNode condition) {
        if (!condition.isObject() || condition.isEmpty()) {
            throw new IllegalArgumentException("Authorizer Condition must be a nonempty object");
        }
        for (JsonNode operator : condition) {
            if (!operator.isObject() || operator.isEmpty()) {
                throw new IllegalArgumentException("Authorizer condition operator requires a nonempty object");
            }
            for (JsonNode value : operator) {
                if (!value.isBoolean() && !isStringOrList(value)) {
                    throw new IllegalArgumentException("Authorizer condition requires a string, boolean or string array");
                }
            }
        }
    }
}
