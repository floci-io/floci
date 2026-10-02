package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a role's trust policy (AssumeRolePolicyDocument) to decide whether a caller may assume
 * the role via {@code sts:AssumeRole}.
 *
 * <p>Trust policies are principal-centric and carry no {@code Resource} element, so they cannot be
 * evaluated by {@link IamPolicyEvaluator} (which is identity/resource oriented). This focused
 * evaluator matches each statement's {@code Action} and {@code Principal} against the caller, hands
 * its {@code Condition} to {@link IamPolicyEvaluator#conditionMatches} with the request's context,
 * and applies AWS precedence: an explicit {@code Deny} wins, otherwise a matching {@code Allow}
 * grants.
 *
 * <p>Only AWS principals are modeled (account-root, bare account id, exact principal ARN, and
 * {@code "*"}); {@code Service} and {@code Federated} principals never match a SigV4 caller and are
 * ignored. A caller using assumed-role temporary credentials matches a trust policy that names its
 * session ARN or its role: IAM stores a {@code Principal} naming a role as the role's unique ID, so
 * the session matches through the role's own ARN, path included, which the caller passes in.
 */
@ApplicationScoped
public class AssumeRolePolicyEvaluator {

    private static final Logger LOG = Logger.getLogger(AssumeRolePolicyEvaluator.class);
    private static final String ASSUME_ROLE_ACTION = "sts:AssumeRole";
    private static final Pattern ACCOUNT_ROOT_ARN =
            Pattern.compile("^arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::(\\d{12}):root$");

    private final ObjectMapper objectMapper;
    private final IamPolicyEvaluator policyEvaluator;

    @Inject
    public AssumeRolePolicyEvaluator(ObjectMapper objectMapper, IamPolicyEvaluator policyEvaluator) {
        this.objectMapper = objectMapper;
        this.policyEvaluator = policyEvaluator;
    }

    /**
     * Returns true if {@code trustPolicyDocument} allows the caller (identified by
     * {@code callerArn}, in {@code callerAccount}) to perform {@code sts:AssumeRole}.
     *
     * <p>{@code principalArn} is the caller's IAM identity: the user's ARN, or for a role session
     * the role's own ARN, path included, which the session ARN does not carry. A {@code Principal}
     * naming a role is matched against it, and it is the request's {@code aws:PrincipalArn}.
     * {@code requestContext} holds the other condition keys the request carries, such as
     * {@code sts:ExternalId}; each statement's {@code Condition} is evaluated against both.
     *
     * <p>A null/blank/unparseable document or one with no matching {@code Allow} denies.
     */
    public boolean allows(String trustPolicyDocument, String callerArn, String principalArn,
                          String callerAccount, Map<String, List<String>> requestContext) {
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
        if (!isWellFormed(statements)) {
            return false;
        }
        Map<String, List<String>> context = new HashMap<>(requestContext);
        if (principalArn != null) {
            context.put("aws:PrincipalArn", List.of(principalArn));
        }
        boolean allow = false;
        if (statements.isArray()) {
            for (JsonNode stmt : statements) {
                switch (evaluateStatement(stmt, callerArn, principalArn, callerAccount, context)) {
                    case DENY -> { return false; }
                    case ALLOW -> allow = true;
                    case NO_MATCH -> { }
                }
            }
        } else if (statements.isObject()) {
            return evaluateStatement(statements, callerArn, principalArn, callerAccount, context) == Match.ALLOW;
        }
        return allow;
    }

    /**
     * Returns true if the trust policy allows the named AWS service principal to assume the role.
     */
    public boolean allowsService(String trustPolicyDocument, String servicePrincipal) {
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
        if (!isWellFormed(statements)) {
            return false;
        }
        boolean allow = false;
        if (statements.isArray()) {
            for (JsonNode statement : statements) {
                switch (evaluateServiceStatement(statement, servicePrincipal)) {
                    case DENY -> { return false; }
                    case ALLOW -> allow = true;
                    case NO_MATCH -> { }
                }
            }
        } else if (statements.isObject()) {
            return evaluateServiceStatement(statements, servicePrincipal) == Match.ALLOW;
        }
        return allow;
    }

    /**
     * Checks a service trust policy in the context of an AppSync API. AWS supports narrowing
     * service trust with aws:SourceAccount and aws:SourceArn, which form the request context here;
     * a condition on any other key finds it absent and does not match, so it never turns a
     * conditional Allow into an unconditional one.
     */
    public boolean allowsService(String trustPolicyDocument, String servicePrincipal,
                                 String sourceArn, String sourceAccount) {
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
        if (!isWellFormed(statements)) {
            return false;
        }
        Map<String, List<String>> context = new HashMap<>();
        if (sourceArn != null) {
            context.put("aws:SourceArn", List.of(sourceArn));
        }
        if (sourceAccount != null) {
            context.put("aws:SourceAccount", List.of(sourceAccount));
        }
        boolean allow = false;
        if (statements.isArray()) {
            for (JsonNode statement : statements) {
                switch (evaluateServiceStatement(statement, servicePrincipal, context)) {
                    case DENY -> { return false; }
                    case ALLOW -> allow = true;
                    case NO_MATCH -> { }
                }
            }
        } else if (statements.isObject()) {
            return evaluateServiceStatement(statements, servicePrincipal, context) == Match.ALLOW;
        }
        return allow;
    }

    private enum Match { ALLOW, DENY, NO_MATCH }

    /**
     * A statement without a valid {@code Effect}, or with a {@code Condition} that is not an object
     * of non-empty operator objects, invalidates the whole document. IAM refuses such a document
     * when it is written; Floci stores it as given, so it is refused here instead of being read as
     * a statement with no condition.
     */
    private boolean isWellFormed(JsonNode statements) {
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

    private boolean validStatement(JsonNode statement) {
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

    private Match evaluateStatement(JsonNode stmt, String callerArn, String principalArn,
                                    String callerAccount, Map<String, List<String>> context) {
        if (!actionApplies(stmt)) {
            return Match.NO_MATCH;
        }
        if (!matchesPrincipal(stmt.get("Principal"), callerArn, principalArn, callerAccount)) {
            return Match.NO_MATCH;
        }
        if (!policyEvaluator.conditionMatches(stmt.get("Condition"), context)) {
            return Match.NO_MATCH;
        }
        return effectOf(stmt);
    }

    private Match evaluateServiceStatement(JsonNode stmt, String servicePrincipal) {
        if (!actionApplies(stmt) || !matchesServicePrincipal(stmt.get("Principal"), servicePrincipal)) {
            return Match.NO_MATCH;
        }
        return effectOf(stmt);
    }

    private Match evaluateServiceStatement(JsonNode stmt, String servicePrincipal,
                                           Map<String, List<String>> context) {
        if (!actionApplies(stmt) || !matchesServicePrincipal(stmt.get("Principal"), servicePrincipal)) {
            return Match.NO_MATCH;
        }
        if (!policyEvaluator.conditionMatches(stmt.get("Condition"), context)) {
            return Match.NO_MATCH;
        }
        return effectOf(stmt);
    }

    private Match effectOf(JsonNode stmt) {
        return switch (stmt.path("Effect").asText("")) {
            case "Allow" -> Match.ALLOW;
            case "Deny" -> Match.DENY;
            default -> Match.NO_MATCH;
        };
    }

    private boolean matchesServicePrincipal(JsonNode principalNode, String servicePrincipal) {
        if (principalNode == null || servicePrincipal == null) {
            return false;
        }
        JsonNode service = principalNode.isObject() ? principalNode.get("Service") : null;
        if (service == null) {
            return false;
        }
        // A policy may name the service in the universal form or the partition form AWS accepted
        // before it (elasticmapreduce.amazonaws.com.cn); both sides are folded to the universal
        // one so either matches, still exactly and case-sensitively.
        String wanted = ServicePrincipals.canonical(servicePrincipal);
        if (service.isTextual()) {
            return ServicePrincipals.canonical(service.asText()).equals(wanted);
        }
        if (service.isArray()) {
            for (JsonNode entry : service) {
                if (entry.isTextual() && ServicePrincipals.canonical(entry.asText()).equals(wanted)) {
                    return true;
                }
            }
        }
        return false;
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

    private boolean matchesPrincipal(JsonNode principalNode, String callerArn, String principalArn,
                                     String callerAccount) {
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
            return matchesAwsPrincipal(aws.asText(), callerArn, principalArn, callerAccount);
        }
        if (aws.isArray()) {
            for (JsonNode entry : aws) {
                if (entry.isTextual()
                        && matchesAwsPrincipal(entry.asText(), callerArn, principalArn, callerAccount)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesAwsPrincipal(String principal, String callerArn, String principalArn,
                                        String callerAccount) {
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
        Matcher rootMatcher = ACCOUNT_ROOT_ARN.matcher(principal);
        if (rootMatcher.matches()) {
            return rootMatcher.group(1).equals(callerAccount);
        }
        // Otherwise an exact (glob-capable) principal ARN: the caller's own ARN, which for a role
        // session is its assumed-role session ARN, or the role's ARN for a Principal naming the role.
        if (callerArn != null && IamPolicyEvaluator.globMatches(principal, callerArn)) {
            return true;
        }
        return principalArn != null && IamPolicyEvaluator.globMatches(principal, principalArn);
    }
}
