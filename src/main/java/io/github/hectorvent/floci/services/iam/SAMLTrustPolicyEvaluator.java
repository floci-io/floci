package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** Evaluates the federated principal and SAML action in a role trust policy. */
@ApplicationScoped
public class SAMLTrustPolicyEvaluator {
    private final ObjectMapper mapper;
    @Inject
    public SAMLTrustPolicyEvaluator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public boolean allows(String document, String providerArn, Map<String, List<String>> claims) {
        try {
            JsonNode statements = mapper.readTree(document).path("Statement");
            if (statements.isArray()) {
                boolean allowed = false;
                for (JsonNode statement : statements) {
                    int result = evaluate(statement, providerArn, claims);
                    if (result < 0) {
                        return false;
                    }
                    if (result > 0) {
                        allowed = true;
                    }
                }
                return allowed;
            }
            return statements.isObject() && evaluate(statements, providerArn, claims) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int evaluate(JsonNode statement, String providerArn, Map<String, List<String>> claims) {
        if (!actionApplies(statement) || !principal(statement.path("Principal"), providerArn)
                || !conditionsSatisfied(statement.get("Condition"), claims)) {
            return 0;
        }
        return "Deny".equalsIgnoreCase(statement.path("Effect").asText()) ? -1 : 1;
    }

    private boolean conditionsSatisfied(JsonNode condition, Map<String, List<String>> claims) {
        if (condition == null || condition.isNull()) {
            return true;
        }
        if (!condition.isObject()) {
            return false;
        }
        var operators = condition.fields();
        while (operators.hasNext()) {
            var operator = operators.next();
            if (!operator.getValue().isObject()) {
                return false;
            }
            var entries = operator.getValue().fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                String key = entry.getKey();
                int colon = key.indexOf(':');
                String claimName = colon < 0 ? key : key.substring(colon + 1);
                List<String> actual = claims.getOrDefault(claimName, List.of());
                List<String> expected = entry.getValue().isArray()
                        ? java.util.stream.StreamSupport.stream(entry.getValue().spliterator(), false)
                                .filter(JsonNode::isTextual).map(JsonNode::asText).toList()
                        : entry.getValue().isTextual() ? List.of(entry.getValue().asText()) : List.of();
                if (expected.isEmpty() || actual.isEmpty()) {
                    return false;
                }
                if (!operatorMatches(operator.getKey(), actual, expected)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean operatorMatches(String operator, List<String> actual, List<String> expected) {
        if ("StringEquals".equals(operator)) {
            return actual.stream().anyMatch(value -> expected.contains(value));
        }
        if ("StringLike".equals(operator)) {
            return actual.stream().anyMatch(value -> expected.stream().anyMatch(pattern ->
                    WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive(pattern, value)));
        }
        if ("StringNotEquals".equals(operator)) {
            return actual.stream().allMatch(value -> expected.stream().noneMatch(value::equals));
        }
        if ("StringNotLike".equals(operator)) {
            return actual.stream().allMatch(value -> expected.stream().noneMatch(pattern ->
                    WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive(pattern, value)));
        }
        return false;
    }

    private boolean actionApplies(JsonNode statement) {
        JsonNode action = statement.get("Action");
        if (action != null) {
            return action(action);
        }
        JsonNode notAction = statement.get("NotAction");
        return notAction != null && !action(notAction);
    }

    private boolean action(JsonNode node) {
        if (node.isTextual()) {
            return matches(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (value.isTextual() && matches(value.asText())) {
                    return true;
                }
            }
        }
        return false;
    }
    private boolean matches(String action) {
        String regex = action.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return "sts:AssumeRoleWithSAML".matches("(?i)^" + regex + "$");
    }
    private boolean principal(JsonNode node, String providerArn) {
        JsonNode federated = node.isObject() ? node.get("Federated") : null;
        if (federated == null) {
            return "*".equals(node.asText());
        }
        if (federated.isTextual()) {
            return "*".equals(federated.asText()) || providerArn.equals(federated.asText());
        }
        if (federated.isArray()) {
            for (JsonNode value : federated) {
                if (value.isTextual()
                        && ("*".equals(value.asText()) || providerArn.equals(value.asText()))) {
                    return true;
                }
            }
        }
        return false;
    }
}
