package io.floci.aws.services.secretsmanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.iam.PolicyDocument;

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
        for (JsonNode statement : PolicyDocument.statementsOf(policy)) {
            checkStatement(statement, errors);
        }
        return errors;
    }

    private static void checkStatement(JsonNode statement, List<ValidationError> errors) {
        // Only Allow statements can widen access; a wildcard principal on a Deny is a deny-all,
        // which is restrictive rather than dangerous.
        if (!"Allow".equalsIgnoreCase(statement.path("Effect").asText())) {
            return;
        }
        if (PolicyDocument.namesEveryone(statement.path("Principal"))) {
            errors.add(new ValidationError(REFLEXIVE_PRINCIPAL_CHECK,
                    "The resource policy grants broad access to your secret. "
                            + "Amend the policy so that it does not use a wildcard principal."));
        }
    }
}
