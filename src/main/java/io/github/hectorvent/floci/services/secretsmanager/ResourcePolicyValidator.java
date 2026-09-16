package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks a Secrets Manager resource policy for principals broad enough that the policy would hand
 * the secret to anyone.
 *
 * <p>Real AWS runs the policy through Zelkova, an automated reasoning engine, and reports each
 * finding under a check name. Floci does not carry a reasoning engine, so it implements the one
 * check that catches the mistake the API exists to catch - a wildcard principal - and reports it
 * under the same {@code REFLEXIVE_PRINCIPAL_CHECK} name real AWS uses, so a caller keying off the
 * check name behaves identically. Narrower Zelkova findings (a policy that grants broad access by
 * way of conditions, say) are not detected, so a pass here is weaker than a pass on AWS.
 *
 * @see <a href="https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_ValidateResourcePolicy.html">
 *     AWS Secrets Manager - ValidateResourcePolicy</a>
 */
public final class ResourcePolicyValidator {

    /** The name AWS reports a broad-principal finding under. */
    public static final String REFLEXIVE_PRINCIPAL_CHECK = "REFLEXIVE_PRINCIPAL_CHECK";

    private static final String WILDCARD = "*";

    /** A single finding: the check that failed and what it found. */
    public record ValidationError(String checkName, String errorMessage) {
    }

    private ResourcePolicyValidator() {
    }

    /**
     * Returns the findings for {@code policy}, empty when the policy passes. The document is
     * assumed to already be syntactically valid JSON; callers raise
     * MalformedPolicyDocumentException before reaching here.
     */
    public static List<ValidationError> validate(JsonNode policy) {
        List<ValidationError> errors = new ArrayList<>();
        JsonNode statements = policy.path("Statement");

        // A lone statement object is as valid as an array of them.
        if (statements.isObject()) {
            checkStatement(statements, errors);
        } else if (statements.isArray()) {
            for (JsonNode statement : statements) {
                checkStatement(statement, errors);
            }
        }
        return errors;
    }

    private static void checkStatement(JsonNode statement, List<ValidationError> errors) {
        // Only Allow statements can widen access; a wildcard principal on a Deny is a deny-all,
        // which is restrictive rather than dangerous.
        if (!"Allow".equalsIgnoreCase(statement.path("Effect").asText())) {
            return;
        }
        if (isWildcardPrincipal(statement.path("Principal"))) {
            errors.add(new ValidationError(REFLEXIVE_PRINCIPAL_CHECK,
                    "The resource policy grants broad access to your secret. "
                            + "Amend the policy so that it does not use a wildcard principal."));
        }
    }

    /**
     * A principal is wildcard either as the bare string {@code "*"} or as a keyed form whose value
     * is {@code "*"} - {@code {"AWS": "*"}} and {@code {"AWS": ["*"]}} both grant everyone.
     */
    private static boolean isWildcardPrincipal(JsonNode principal) {
        if (principal.isMissingNode() || principal.isNull()) {
            return false;
        }
        if (principal.isTextual()) {
            return WILDCARD.equals(principal.asText());
        }
        if (principal.isObject()) {
            for (JsonNode value : principal) {
                if (containsWildcard(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsWildcard(JsonNode value) {
        if (value.isTextual()) {
            return WILDCARD.equals(value.asText());
        }
        if (value.isArray()) {
            for (JsonNode entry : value) {
                if (entry.isTextual() && WILDCARD.equals(entry.asText())) {
                    return true;
                }
            }
        }
        return false;
    }
}
