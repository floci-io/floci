package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How a role's trust policy statements are read, the same way on every path that assumes the role:
 * {@code sts:AssumeRole}, {@code sts:AssumeRoleWithWebIdentity} and {@code sts:AssumeRoleWithSAML}.
 *
 * <p>A statement without a valid {@code Effect}, or with a {@code Condition} that is not an object of
 * non-empty operator objects, invalidates the whole document. IAM refuses such a document when it is
 * written; Floci stores it as given, so it is refused here instead of being read as a statement with
 * no condition, or as an allow.
 */
final class TrustPolicyStatements {

    private TrustPolicyStatements() {
    }

    /** True when {@code statements}, the document's {@code Statement} element, can be read at all. */
    static boolean wellFormed(JsonNode statements) {
        if (statements.isArray()) {
            if (statements.isEmpty()) {
                return false;
            }
            for (JsonNode statement : statements) {
                if (!validStatement(statement)) {
                    return false;
                }
            }
            return true;
        }
        return validStatement(statements);
    }

    /**
     * The values a condition key reads from the role being assumed: its tags, given under the keys the generated table
     * gives the role's type ({@code aws:ResourceTag/<key>}, {@code iam:ResourceTag/<key>}), a key matched whatever its
     * case, as AWS matches a tag key; empty for a key the role gives no value under.
     */
    static Optional<List<String>> roleValues(String conditionKey, Map<String, List<String>> roleTags) {
        if (conditionKey == null || roleTags == null) {
            return Optional.empty();
        }
        return roleTags.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(conditionKey))
                .map(Map.Entry::getValue).findFirst();
    }

    /** True when a statement of a {@linkplain #wellFormed well-formed} document is a {@code Deny}. */
    static boolean denies(JsonNode statement) {
        return "Deny".equals(statement.path("Effect").asText());
    }

    private static boolean validStatement(JsonNode statement) {
        if (!statement.isObject()) {
            return false;
        }
        String effect = statement.path("Effect").asText("");
        if (!"Allow".equals(effect) && !"Deny".equals(effect)) {
            return false;
        }
        JsonNode condition = statement.get("Condition");
        if (condition == null) {
            return true;
        }
        if (!condition.isObject() || condition.isEmpty()) {
            return false;
        }
        for (JsonNode operator : condition) {
            if (!operator.isObject() || operator.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
