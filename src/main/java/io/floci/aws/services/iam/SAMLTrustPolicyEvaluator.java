package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/** Evaluates the federated principal and SAML action in a role trust policy. */
@ApplicationScoped
public class SAMLTrustPolicyEvaluator {
    private static final String ROLE_SESSION_NAME_KEY = "sts:RoleSessionName";
    private static final String SAML_ACTION = "sts:AssumeRoleWithSAML";
    private final ObjectMapper mapper;
    @Inject
    public SAMLTrustPolicyEvaluator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@code roleSessionName} is the assertion's {@code RoleSessionName} attribute, which a policy
     * reads as {@code sts:RoleSessionName}, or null when the assertion carries none. {@code roleTags}
     * holds the tags of the role being assumed under the keys the generated table gives its type
     * ({@code aws:ResourceTag/<key>}, {@code iam:ResourceTag/<key>}), which a policy reads on it.
     */
    public boolean allows(String document, String providerArn, Map<String, List<String>> claims,
                          String roleSessionName, Map<String, List<String>> roleTags) {
        return allows(SAML_ACTION, document, providerArn, claims, roleSessionName, roleTags);
    }

    /**
     * Whether the trust policy lets this assertion's holder tag the session it is assuming, read on
     * the same document with the same principal and the same conditions as the assume itself.
     */
    public boolean allowsTagging(String document, String providerArn, Map<String, List<String>> claims,
                                 String roleSessionName, Map<String, List<String>> roleTags) {
        return allows(AssumeRolePolicyEvaluator.TAG_SESSION_ACTION, document, providerArn, claims,
                roleSessionName, roleTags);
    }

    private boolean allows(String action, String document, String providerArn,
                           Map<String, List<String>> claims, String roleSessionName,
                           Map<String, List<String>> roleTags) {
        try {
            JsonNode statements = mapper.readTree(document).path("Statement");
            if (!TrustPolicyStatements.wellFormed(statements)) {
                return false;
            }
            if (statements.isArray()) {
                boolean allowed = false;
                for (JsonNode statement : statements) {
                    int result = evaluate(action, statement, providerArn, claims, roleSessionName, roleTags);
                    if (result < 0) {
                        return false;
                    }
                    if (result > 0) {
                        allowed = true;
                    }
                }
                return allowed;
            }
            return statements.isObject()
                    && evaluate(action, statements, providerArn, claims, roleSessionName, roleTags) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int evaluate(String action, JsonNode statement, String providerArn,
                         Map<String, List<String>> claims, String roleSessionName,
                         Map<String, List<String>> roleTags) {
        if (!actionApplies(statement, action) || !principal(statement.path("Principal"), providerArn)
                || !conditionsSatisfied(statement.get("Condition"), claims, roleSessionName, roleTags)) {
            return 0;
        }
        return TrustPolicyStatements.denies(statement) ? -1 : 1;
    }

    private boolean conditionsSatisfied(JsonNode condition, Map<String, List<String>> claims,
                                        String roleSessionName, Map<String, List<String>> roleTags) {
        if (condition == null || condition.isNull()) {
            return true;
        }
        if (!condition.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> operators = condition.fields();
        while (operators.hasNext()) {
            Map.Entry<String, JsonNode> operator = operators.next();
            if (!operator.getValue().isObject()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> entries = operator.getValue().fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                List<String> actual = TrustPolicyStatements.roleValues(entry.getKey(), roleTags)
                        .orElseGet(() -> conditionValues(entry.getKey(), claims, roleSessionName));
                List<String> expected = entry.getValue().isArray()
                        ? StreamSupport.stream(entry.getValue().spliterator(), false)
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

    private static List<String> conditionValues(String key, Map<String, List<String>> claims,
                                                String roleSessionName) {
        if (ROLE_SESSION_NAME_KEY.equalsIgnoreCase(key)) {
            return roleSessionName == null ? List.of() : List.of(roleSessionName);
        }
        int colon = key.indexOf(':');
        String claimName = colon < 0 ? key : key.substring(colon + 1);
        return claims.getOrDefault(claimName, List.of());
    }

    private boolean operatorMatches(String operator, List<String> actual, List<String> expected) {
        if ("StringEquals".equals(operator)) {
            return actual.stream().anyMatch(value -> expected.contains(value));
        }
        if ("StringLike".equals(operator)) {
            return actual.stream().anyMatch(value -> expected.stream().anyMatch(pattern ->
                    IamPolicyEvaluator.caseSensitiveGlobMatches(pattern, value)));
        }
        if ("StringNotEquals".equals(operator)) {
            return actual.stream().allMatch(value -> expected.stream().noneMatch(value::equals));
        }
        if ("StringNotLike".equals(operator)) {
            return actual.stream().allMatch(value -> expected.stream().noneMatch(pattern ->
                    IamPolicyEvaluator.caseSensitiveGlobMatches(pattern, value)));
        }
        return false;
    }

    private boolean actionApplies(JsonNode statement, String action) {
        JsonNode element = statement.get("Action");
        if (element != null) {
            return action(element, action);
        }
        JsonNode notAction = statement.get("NotAction");
        return notAction != null && !action(notAction, action);
    }

    private boolean action(JsonNode node, String action) {
        if (node.isTextual()) {
            return matches(node.asText(), action);
        }
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (value.isTextual() && matches(value.asText(), action)) {
                    return true;
                }
            }
        }
        return false;
    }
    private boolean matches(String pattern, String action) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return action.matches("(?i)^" + regex + "$");
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
