package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
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
    private static final Pattern ACCOUNT_ROOT_ARN =
            Pattern.compile("^arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::(\\d{12}):root$");
    private static final Pattern ASSUMED_ROLE_ARN = Pattern.compile(
            "^arn:(" + AwsArnUtils.PARTITION_REGEX + "):sts::(\\d{12}):assumed-role/([^/]+)/.*$");

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
        if (!hasValidEffects(statements)) {
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
        if (!hasValidEffects(statements)) {
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
     * service trust with aws:SourceAccount and aws:SourceArn. Unknown condition keys or operators
     * are rejected rather than silently turning a conditional Allow into an unconditional one.
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
        if (!hasValidEffects(statements)) {
            return false;
        }
        boolean allow = false;
        if (statements.isArray()) {
            for (JsonNode statement : statements) {
                switch (evaluateServiceStatement(statement, servicePrincipal, sourceArn, sourceAccount)) {
                    case DENY -> { return false; }
                    case ALLOW -> allow = true;
                    case NO_MATCH -> { }
                }
            }
        } else if (statements.isObject()) {
            return evaluateServiceStatement(statements, servicePrincipal,
                    sourceArn, sourceAccount) == Match.ALLOW;
        }
        return allow;
    }

    private enum Match { ALLOW, DENY, NO_MATCH }

    private boolean hasValidEffects(JsonNode statements) {
        if (statements.isArray()) {
            if (statements.isEmpty()) {
                return false;
            }
            for (JsonNode statement : statements) {
                if (!validEffect(statement)) {
                    return false;
                }
            }
            return true;
        }
        return validEffect(statements);
    }

    private boolean validEffect(JsonNode statement) {
        if (!statement.isObject()) {
            return false;
        }
        String effect = statement.path("Effect").asText("");
        return "Allow".equals(effect) || "Deny".equals(effect);
    }

    private Match evaluateStatement(JsonNode stmt, String callerArn, String callerAccount) {
        if (!actionApplies(stmt)) {
            return Match.NO_MATCH;
        }
        if (!matchesPrincipal(stmt.get("Principal"), callerArn, callerAccount)) {
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
                                           String sourceArn, String sourceAccount) {
        if (!actionApplies(stmt) || !matchesServicePrincipal(stmt.get("Principal"), servicePrincipal)) {
            return Match.NO_MATCH;
        }
        Match condition = serviceCondition(stmt.get("Condition"), sourceArn, sourceAccount);
        if (condition != Match.ALLOW) {
            return condition;
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

    /** ALLOW means the condition matches, NO_MATCH means it does not, DENY means unsupported. */
    private Match serviceCondition(JsonNode condition, String sourceArn, String sourceAccount) {
        if (condition == null) {
            return Match.ALLOW;
        }
        if (!condition.isObject() || condition.isEmpty()) {
            return Match.DENY;
        }
        for (Iterator<Map.Entry<String, JsonNode>> operators = condition.fields();
             operators.hasNext();) {
            Map.Entry<String, JsonNode> operator = operators.next();
            if (!"StringEquals".equals(operator.getKey()) && !"StringLike".equals(operator.getKey())
                    && !"ArnEquals".equals(operator.getKey()) && !"ArnLike".equals(operator.getKey())) {
                return Match.DENY;
            }
            if (!operator.getValue().isObject() || operator.getValue().isEmpty()) {
                return Match.DENY;
            }
            for (Iterator<Map.Entry<String, JsonNode>> keys = operator.getValue().fields();
                 keys.hasNext();) {
                Map.Entry<String, JsonNode> key = keys.next();
                String actual = switch (key.getKey().toLowerCase(Locale.ROOT)) {
                    case "aws:sourcearn" -> sourceArn;
                    case "aws:sourceaccount" -> sourceAccount;
                    default -> null;
                };
                if (!"aws:sourcearn".equalsIgnoreCase(key.getKey())
                        && !"aws:sourceaccount".equalsIgnoreCase(key.getKey())) {
                    return Match.DENY;
                }
                if (actual == null || !matchesServiceConditionValue(operator.getKey(), key.getValue(), actual)) {
                    return Match.NO_MATCH;
                }
            }
        }
        return Match.ALLOW;
    }

    private boolean matchesServiceConditionValue(String operator, JsonNode values, String actual) {
        if (values.isArray()) {
            for (JsonNode value : values) {
                if (matchesServiceConditionValue(operator, value, actual)) {
                    return true;
                }
            }
            return false;
        }
        if (!values.isTextual()) {
            return false;
        }
        if ("StringEquals".equals(operator)) {
            return values.asText().equals(actual);
        }
        return globMatchesCaseSensitive(values.asText(), actual);
    }

    private boolean globMatchesCaseSensitive(String pattern, String value) {
        int patternIndex = 0;
        int valueIndex = 0;
        int starIndex = -1;
        int starValueIndex = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()
                    && (pattern.charAt(patternIndex) == '?' || pattern.charAt(patternIndex) == value.charAt(valueIndex))) {
                patternIndex++;
                valueIndex++;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                starIndex = patternIndex++;
                starValueIndex = valueIndex;
            } else if (starIndex >= 0) {
                patternIndex = starIndex + 1;
                valueIndex = ++starValueIndex;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
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
        Matcher rootMatcher = ACCOUNT_ROOT_ARN.matcher(principal);
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
     *
     * <p>The caller's partition is carried across rather than assumed. IAM ARNs are regionless, so
     * {@code Arn.of} cannot derive it, and hardcoding {@code aws} here would rewrite a GovCloud
     * caller into a commercial role ARN that no GovCloud trust policy can match: the caller would
     * be denied a role they are entitled to, with nothing in the response saying why.
     */
    private static String assumedRoleToRoleArn(String arn) {
        Matcher m = ASSUMED_ROLE_ARN.matcher(arn);
        if (!m.matches()) {
            return null;
        }
        return new AwsArnUtils.Arn(m.group(1), "iam", "", m.group(2), "role/" + m.group(3)).toString();
    }
}
