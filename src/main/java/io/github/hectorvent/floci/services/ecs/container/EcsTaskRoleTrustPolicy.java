package io.github.hectorvent.floci.services.ecs.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Evaluates the small trust-policy surface needed by ECS task-role credentials.
 *
 * <p>ECS assumes a task role as the {@code ecs-tasks.amazonaws.com} service principal. The generic
 * {@code AssumeRolePolicyEvaluator} intentionally only models AWS principals for STS callers, so
 * this evaluator keeps the service-principal rule separate and fails closed for conditions that
 * cannot be evaluated without a request context.
 */
@ApplicationScoped
public class EcsTaskRoleTrustPolicy {

    private static final Logger LOG = Logger.getLogger(EcsTaskRoleTrustPolicy.class);
    private static final String ECS_TASKS_SERVICE = "ecs-tasks.amazonaws.com";
    private static final String ASSUME_ROLE_ACTION = "sts:AssumeRole";
    private static final Set<String> PRINCIPAL_TYPES = Set.of("AWS", "Service", "Federated",
            "CanonicalUser");

    private final ObjectMapper objectMapper;

    @Inject
    public EcsTaskRoleTrustPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns true only when the policy has a matching unconditional Allow and no matching Deny.
     * Unsupported or malformed statements do not grant access.
     */
    public boolean allows(String trustPolicyDocument) {
        if (trustPolicyDocument == null || trustPolicyDocument.isBlank()) {
            return false;
        }

        JsonNode statements;
        try {
            statements = objectMapper.readTree(trustPolicyDocument).path("Statement");
        } catch (Exception e) {
            LOG.warnv("Failed to parse ECS task-role trust policy: {0}", e.getMessage());
            return false;
        }

        boolean allow = false;
        if (statements.isArray()) {
            for (JsonNode statement : statements) {
                switch (evaluateStatement(statement)) {
                    case DENY -> { return false; }
                    case ALLOW -> allow = true;
                    case NO_MATCH -> { }
                    case INVALID -> { return false; }
                }
            }
            return allow;
        }
        if (statements.isObject()) {
            Match result = evaluateStatement(statements);
            return result == Match.ALLOW;
        }
        return false;
    }

    private enum Match { ALLOW, DENY, NO_MATCH, INVALID }

    private Match evaluateStatement(JsonNode statement) {
        if (!validStatementShape(statement)) {
            return Match.INVALID;
        }
        if (!actionApplies(statement) || !matchesEcsPrincipal(statement.get("Principal"))) {
            return Match.NO_MATCH;
        }

        JsonNode effect = statement.get("Effect");
        if ("Deny".equalsIgnoreCase(effect.asText())) {
            return Match.DENY;
        }
        return Match.ALLOW;
    }

    /**
     * Rejects semantics this narrow evaluator cannot prove safe. Trust policies are persisted only
     * after IAM validates them, but old/imported state and hand-written fixtures can still contain
     * malformed or unsupported statements. Ignoring one next to a valid Allow could turn an
     * unknown Deny, condition, or principal inversion into an unintended grant.
     */
    private boolean validStatementShape(JsonNode statement) {
        if (statement == null || !statement.isObject()
                || statement.has("Condition") || statement.has("NotPrincipal")) {
            return false;
        }
        JsonNode effect = statement.get("Effect");
        if (effect == null || !effect.isTextual()
                || !("Allow".equalsIgnoreCase(effect.asText())
                || "Deny".equalsIgnoreCase(effect.asText()))) {
            return false;
        }
        if (!statement.has("Principal") || !validPrincipalShape(statement.get("Principal"))) {
            return false;
        }
        if (statement.has("Action") && statement.has("NotAction")) {
            return false;
        }
        JsonNode action = statement.has("Action") ? statement.get("Action") : statement.get("NotAction");
        return action != null && validTextValue(action);
    }

    private boolean validPrincipalShape(JsonNode principal) {
        if (principal == null || principal.isNull()) {
            return false;
        }
        if (principal.isTextual()) {
            return !principal.asText().isBlank();
        }
        if (!principal.isObject() || principal.isEmpty()) {
            return false;
        }
        var fields = principal.fieldNames();
        while (fields.hasNext()) {
            if (!PRINCIPAL_TYPES.contains(fields.next())) {
                return false;
            }
        }
        JsonNode service = principal.get("Service");
        if (service != null && !validTextValue(service)) {
            return false;
        }
        // AWS, Federated, and other principal types are valid trust-policy data but do not match
        // an ECS service caller. NotPrincipal is rejected above because its complement semantics
        // cannot be evaluated safely without widening this class.
        return true;
    }

    /** Mirrors the Action/NotAction matching used by the general STS trust evaluator. */
    private boolean actionApplies(JsonNode statement) {
        if (statement.has("Action") && statement.has("NotAction")) {
            return false;
        }
        JsonNode action = statement.get("Action");
        if (action != null) {
            return matchesAssumeRoleAction(action);
        }
        JsonNode notAction = statement.get("NotAction");
        return notAction != null && hasTextValue(notAction) && !matchesAssumeRoleAction(notAction);
    }

    private boolean matchesAssumeRoleAction(JsonNode actionNode) {
        if (actionNode.isTextual()) {
            return IamPolicyEvaluator.globMatches(actionNode.asText(), ASSUME_ROLE_ACTION);
        }
        if (actionNode.isArray()) {
            for (JsonNode action : actionNode) {
                if (action.isTextual()
                        && IamPolicyEvaluator.globMatches(action.asText(), ASSUME_ROLE_ACTION)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesEcsPrincipal(JsonNode principal) {
        if (principal == null) {
            return false;
        }
        // A textual wildcard delegates to every principal, including AWS services.
        if (principal.isTextual()) {
            return "*".equals(principal.asText());
        }
        if (!principal.isObject()) {
            return false;
        }
        JsonNode service = principal.get("Service");
        if (service == null) {
            return false;
        }
        if (service.isTextual()) {
            return ECS_TASKS_SERVICE.equals(service.asText());
        }
        if (service.isArray()) {
            for (JsonNode entry : service) {
                if (entry.isTextual() && ECS_TASKS_SERVICE.equals(entry.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTextValue(JsonNode node) {
        if (node.isTextual()) {
            return true;
        }
        if (node.isArray()) {
            for (JsonNode entry : node) {
                if (entry.isTextual()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean validTextValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText().isBlank();
        }
        if (node.isArray() && !node.isEmpty()) {
            for (JsonNode entry : node) {
                if (!entry.isTextual() || entry.asText().isBlank()) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
