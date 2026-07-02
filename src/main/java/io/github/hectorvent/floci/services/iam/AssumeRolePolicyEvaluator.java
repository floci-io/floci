package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.regex.Pattern;

/**
 * Evaluates a role's trust policy (AssumeRolePolicyDocument) to decide whether a caller may assume
 * the role via {@code sts:AssumeRole}.
 *
 * <p>Trust policies are principal-centric and carry no {@code Resource} element, so they cannot be
 * evaluated by {@link IamPolicyEvaluator} (which is identity/resource oriented). This focused
 * evaluator matches each statement's {@code Action} and {@code Principal} against the caller and
 * applies AWS precedence: an explicit {@code Deny} wins, otherwise a matching {@code Allow} grants.
 *
 * <p>Only AWS principals are modeled (account-root, bare account id, exact principal ARN, and
 * {@code "*"}); {@code Service} and {@code Federated} principals never match a SigV4 caller and are
 * ignored. A caller using assumed-role temporary credentials (whose ARN is an STS
 * {@code assumed-role} ARN) also matches a trust policy that names the underlying IAM role ARN, as
 * AWS resolves the session back to its role for trust-policy evaluation.
 */
@ApplicationScoped
public class AssumeRolePolicyEvaluator {

    private static final Logger LOG = Logger.getLogger(AssumeRolePolicyEvaluator.class);
    private static final String ASSUME_ROLE_ACTION = "sts:AssumeRole";
    private static final Pattern ACCOUNT_ROOT_ARN = Pattern.compile("^arn:aws:iam::(\\d{12}):root$");
    private static final Pattern ASSUMED_ROLE_ARN =
            Pattern.compile("^arn:aws:sts::(\\d{12}):assumed-role/([^/]+)/.*$");

    private final ObjectMapper objectMapper;

    @Inject
    public AssumeRolePolicyEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns true if {@code trustPolicyDocument} allows the caller (identified by
     * {@code callerArn}, in {@code callerAccount}) to perform {@code sts:AssumeRole}.
     *
     * <p>A null/blank/unparseable document or one with no matching {@code Allow} denies.
     */
    public boolean allows(String trustPolicyDocument, String callerArn, String callerAccount) {
        if (trustPolicyDocument == null || trustPolicyDocument.isBlank()) {
            return false;
        }
        JsonNode statements;
        try {
            statements = objectMapper.readTree(trustPolicyDocument).path("Statement");
        } catch (Exception e) {
            LOG.warnv("Failed to parse trust policy: {0}", e.getMessage());
            return false;
        }

        boolean allow = false;
        if (statements.isArray()) {
            for (JsonNode stmt : statements) {
                switch (evaluateStatement(stmt, callerArn, callerAccount)) {
                    case DENY -> { return false; }
                    case ALLOW -> allow = true;
                    case NO_MATCH -> { }
                }
            }
        } else if (statements.isObject()) {
            return evaluateStatement(statements, callerArn, callerAccount) == Match.ALLOW;
        }
        return allow;
    }

    private enum Match { ALLOW, DENY, NO_MATCH }

    private Match evaluateStatement(JsonNode stmt, String callerArn, String callerAccount) {
        if (!actionApplies(stmt)) {
            return Match.NO_MATCH;
        }
        if (!matchesPrincipal(stmt.get("Principal"), callerArn, callerAccount)) {
            return Match.NO_MATCH;
        }
        return "Deny".equalsIgnoreCase(stmt.path("Effect").asText("Allow")) ? Match.DENY : Match.ALLOW;
    }

    /**
     * True if the statement's action element applies to {@code sts:AssumeRole}. An {@code Action}
     * element applies when any of its patterns match; a {@code NotAction} element applies when none
     * of its patterns match (AWS semantics, mirroring {@link IamPolicyEvaluator}'s action handling).
     * A statement with neither key expresses no action constraint and does not apply.
     */
    private boolean actionApplies(JsonNode stmt) {
        JsonNode action = stmt.get("Action");
        if (action != null) {
            return matchesAssumeRoleAction(action);
        }
        JsonNode notAction = stmt.get("NotAction");
        if (notAction != null) {
            return !matchesAssumeRoleAction(notAction);
        }
        return false;
    }

    private boolean matchesAssumeRoleAction(JsonNode actionNode) {
        if (actionNode == null) {
            return false;
        }
        if (actionNode.isTextual()) {
            return IamPolicyEvaluator.globMatches(actionNode.asText(), ASSUME_ROLE_ACTION);
        }
        if (actionNode.isArray()) {
            for (JsonNode a : actionNode) {
                if (a.isTextual() && IamPolicyEvaluator.globMatches(a.asText(), ASSUME_ROLE_ACTION)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesPrincipal(JsonNode principalNode, String callerArn, String callerAccount) {
        if (principalNode == null) {
            return false;
        }
        // Principal: "*"
        if (principalNode.isTextual()) {
            return "*".equals(principalNode.asText());
        }
        if (!principalNode.isObject()) {
            return false;
        }
        // Only the AWS principal type can match a SigV4 caller.
        JsonNode aws = principalNode.get("AWS");
        if (aws == null) {
            return false;
        }
        if (aws.isTextual()) {
            return matchesAwsPrincipal(aws.asText(), callerArn, callerAccount);
        }
        if (aws.isArray()) {
            for (JsonNode entry : aws) {
                if (entry.isTextual() && matchesAwsPrincipal(entry.asText(), callerArn, callerAccount)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesAwsPrincipal(String principal, String callerArn, String callerAccount) {
        if (principal == null) {
            return false;
        }
        if ("*".equals(principal)) {
            return true;
        }
        // Bare 12-digit account id, or an account-root ARN → matches any principal in that account.
        if (principal.matches("\\d{12}")) {
            return principal.equals(callerAccount);
        }
        var rootMatcher = ACCOUNT_ROOT_ARN.matcher(principal);
        if (rootMatcher.matches()) {
            return rootMatcher.group(1).equals(callerAccount);
        }
        // Otherwise an exact (glob-capable) principal ARN.
        if (callerArn == null) {
            return false;
        }
        if (IamPolicyEvaluator.globMatches(principal, callerArn)) {
            return true;
        }
        // When the caller used assumed-role temporary credentials, callerArn is the STS
        // assumed-role ARN (arn:aws:sts::ACCT:assumed-role/Role/session). A role's trust policy is
        // written with the role's *IAM* principal ARN (arn:aws:iam::ACCT:role/Role) — AWS resolves
        // the session back to the role for trust matching — so also match that canonical form.
        String roleArn = assumedRoleToRoleArn(callerArn);
        return roleArn != null && IamPolicyEvaluator.globMatches(principal, roleArn);
    }

    /**
     * Maps an STS assumed-role ARN ({@code arn:aws:sts::ACCT:assumed-role/Role/session}) to the
     * underlying IAM role ARN ({@code arn:aws:iam::ACCT:role/Role}), or {@code null} if {@code arn}
     * is not an assumed-role ARN.
     */
    private static String assumedRoleToRoleArn(String arn) {
        var m = ASSUMED_ROLE_ARN.matcher(arn);
        return m.matches() ? "arn:aws:iam::" + m.group(1) + ":role/" + m.group(2) : null;
    }
}
