package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * How a resource policy document reads: the statements it carries, and the principals
 * those statements reach.
 *
 * <p>S3's block public access, Secrets Manager's block public policy and Payment
 * Cryptography's public policy refusal all read a policy this way, and each of them had a
 * copy of it. The copies disagreed: one of them did not see a wildcard principal written
 * as a list, and one of them saw a wildcard in a shape the others did not. The rule is
 * here now, and the three read it.
 */
public final class PolicyDocument {

    /** The wildcard that names every principal. */
    private static final String WILDCARD = "*";

    private PolicyDocument() {
    }

    /**
     * The statements of a policy, whether it carries one alone or a list of them.
     *
     * @param policy the policy document
     * @return the statements, empty when the document carries none
     */
    public static List<JsonNode> statementsOf(JsonNode policy) {
        JsonNode statements = policy.path("Statement");
        if (statements.isObject()) {
            return List.of(statements);
        }
        List<JsonNode> out = new ArrayList<>();
        if (statements.isArray()) {
            statements.forEach(out::add);
        }
        return out;
    }

    /**
     * Whether a principal names every principal.
     *
     * <p>A principal names everyone when it is the wildcard itself, when it is a list that
     * holds the wildcard, or when it is a map whose values hold it: {@code "*"},
     * {@code {"AWS": "*"}}, {@code {"AWS": ["*"]}} and {@code ["*"]} all name everyone.
     *
     * @param principal the principal of a statement
     * @return whether the principal names everyone
     */
    public static boolean namesEveryone(JsonNode principal) {
        if (principal == null || principal.isMissingNode() || principal.isNull()) {
            return false;
        }
        if (principal.isTextual()) {
            return WILDCARD.equals(principal.asText());
        }
        if (principal.isArray()) {
            return holdsEveryone(principal);
        }
        if (principal.isObject()) {
            for (JsonNode value : principal) {
                if (holdsEveryone(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a statement grants to everyone.
     *
     * <p>A grant that names everyone as its principal reaches everyone, and a grant that
     * names the principals it excludes reaches everyone else. A grant that excludes
     * everyone reaches nobody.
     *
     * @param statement the statement of a policy
     * @return whether the statement reaches everyone
     */
    public static boolean grantsToEveryone(JsonNode statement) {
        if (statement.hasNonNull("Principal")) {
            return namesEveryone(statement.path("Principal"));
        }
        return statement.hasNonNull("NotPrincipal") && !namesEveryone(statement.path("NotPrincipal"));
    }

    /** Whether a value holds the wildcard, whether it is the value itself or a list of them. */
    private static boolean holdsEveryone(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return WILDCARD.equals(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (WILDCARD.equals(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }
}
