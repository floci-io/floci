package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a role's trust policy (AssumeRolePolicyDocument) to decide whether a caller may assume
 * the role via {@code sts:AssumeRole}.
 *
 * <p>Trust policies are principal-centric and carry no {@code Resource} element, so they cannot be
 * evaluated by {@link IamPolicyEvaluator} (which is identity/resource oriented). This focused
 * evaluator matches each statement's {@code Action} and {@code Principal} against the caller, hands
 * its {@code Condition} to {@link IamPolicyEvaluator#conditionMatches} with the request's context
 * and the document's own {@code Version}, which is what decides whether a {@code ${...}} in a
 * condition value is a policy variable, and applies AWS precedence: an explicit {@code Deny} wins,
 * otherwise a matching {@code Allow} grants.
 *
 * <p>The condition context is the request's own, with the caller's principal keys written onto it
 * by {@link IamService.CallerArns#putPrincipalKeys}. The user guide puts {@code aws:PrincipalType}
 * and {@code aws:userid} in "the request context for all requests" and {@code aws:username} in it
 * for every IAM user, and an {@code sts:AssumeRole} request is no exception, so a trust policy
 * condition reads the same principal as an identity or resource policy would.
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
     * How a trust policy answers a caller's {@code sts:AssumeRole}. A {@code Principal} naming an
     * account, by its id or its root ARN, delegates the decision to that account's own policies
     * (IAM User Guide, "AWS account principals"), so an Allow through one is told apart from an
     * Allow naming the caller.
     */
    public enum TrustDecision {
        /** An Allow names the caller, its role or every principal. */
        ALLOW,
        /** An Allow names the caller's account and nothing narrower. */
        ALLOW_ACCOUNT,
        EXPLICIT_DENY,
        NEUTRAL
    }

    private enum PrincipalMatch { NONE, ACCOUNT, CALLER }

    /**
     * Returns true if {@code trustPolicyDocument} allows {@code caller} (in {@code callerAccount})
     * to perform {@code sts:AssumeRole}, through any principal: see {@link #decide} for the
     * arguments.
     */
    public boolean allows(String trustPolicyDocument, IamService.CallerArns caller,
                          String callerAccount, Map<String, List<String>> requestContext) {
        TrustDecision decision = decide(trustPolicyDocument, caller, callerAccount, requestContext);
        return decision == TrustDecision.ALLOW || decision == TrustDecision.ALLOW_ACCOUNT;
    }

    /**
     * Returns true if {@code trustPolicyDocument} lets some principal of {@code account} assume the
     * role: the account itself, through its root, or a principal of it that an Allow names, each
     * decided by {@link #decide} with its conditions and Deny statements. It answers for a service
     * that assumes a role on an account's behalf without a caller Floci knows, as CloudFormation does
     * for {@code Fn::GetStackOutput}: Floci keeps no execution role or caller for a stack operation.
     */
    public boolean allowsAnyPrincipalOf(String trustPolicyDocument, String account, String partition) {
        String root = AwsArnUtils.Arn.global(partition, "iam", account, "root").toString();
        if (allows(trustPolicyDocument, IamService.CallerArns.accountRoot(root, account), account, Map.of())) {
            return true;
        }
        for (String principal : allowedAwsPrincipals(trustPolicyDocument)) {
            if (AwsArnUtils.isArn(principal) && account.equals(AwsArnUtils.parse(principal).accountId())
                    && allows(trustPolicyDocument, callerNamed(principal), account, Map.of())) {
                return true;
            }
        }
        return false;
    }

    /** The {@code Principal.AWS} entries of the Allow statements that apply to {@code sts:AssumeRole}. */
    private List<String> allowedAwsPrincipals(String trustPolicyDocument) {
        List<String> principals = new ArrayList<>();
        if (trustPolicyDocument == null || trustPolicyDocument.isBlank()) {
            return principals;
        }
        JsonNode statements;
        try {
            statements = objectMapper.readTree(trustPolicyDocument).path("Statement");
        } catch (Exception e) {
            LOG.warnv("Failed to parse trust policy: {0}", e.getMessage());
            return principals;
        }
        if (!isWellFormed(statements)) {
            return principals;
        }
        Iterable<JsonNode> statementList = statements.isArray() ? statements : List.of(statements);
        for (JsonNode stmt : statementList) {
            if (effectOf(stmt) != Match.ALLOW || !actionApplies(stmt)) {
                continue;
            }
            JsonNode aws = stmt.path("Principal").path("AWS");
            Iterable<JsonNode> awsList = aws.isArray() ? aws : List.of(aws);
            for (JsonNode entry : awsList) {
                if (entry.isTextual()) {
                    principals.add(entry.asText());
                }
            }
        }
        return principals;
    }

    /** The principal {@code principalArn} names, as it calls: a role through a session of it. */
    private static IamService.CallerArns callerNamed(String principalArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(principalArn);
        if (!arn.resource().startsWith("role/")) {
            return new IamService.CallerArns(principalArn, principalArn);
        }
        String roleName = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        String session = AwsArnUtils.Arn.global(arn.partition(), "sts", arn.accountId(), "assumed-role/" + roleName)
                .toString();
        return new IamService.CallerArns(session, principalArn, IamService.CallerArns.TYPE_ASSUMED_ROLE, null, null);
    }

    /**
     * Decides whether {@code trustPolicyDocument} lets {@code caller} (in {@code callerAccount})
     * perform {@code sts:AssumeRole}, and through which principal.
     *
     * <p>{@code caller} carries both ARNs: the identity it acts as, which for a role session is
     * its {@code assumed-role} session ARN, and its principal ARN, which for that session is the
     * role's own ARN, path included. A {@code Principal} naming a role is matched against the
     * second. It also carries the keys that say who is calling, which it writes onto the context
     * through {@link IamService.CallerArns#putPrincipalKeys}, the same writer every other context
     * a request is evaluated against uses: a trust policy sees the caller it would see anywhere
     * else. {@code requestContext} holds the keys the request itself carries, such as
     * {@code sts:ExternalId}; each statement's {@code Condition} is evaluated against both.
     *
     * <p>A null/blank/unparseable document or one with no matching statement is
     * {@link TrustDecision#NEUTRAL}, and a caller with no principal ARN writes no keys at all,
     * which leaves every statement keyed on who is calling not applying.
     */
    public TrustDecision decide(String trustPolicyDocument, IamService.CallerArns caller,
                                String callerAccount, Map<String, List<String>> requestContext) {
        if (trustPolicyDocument == null || trustPolicyDocument.isBlank()) {
            return TrustDecision.NEUTRAL;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(trustPolicyDocument);
        } catch (Exception e) {
            LOG.warnv("Failed to parse trust policy: {0}", e.getMessage());
            return TrustDecision.NEUTRAL;
        }
        JsonNode statements = root.path("Statement");
        if (!isWellFormed(statements)) {
            return TrustDecision.NEUTRAL;
        }
        String version = root.path("Version").asText(null);
        String callerArn = caller == null ? null : caller.callerArn();
        String principalArn = caller == null ? null : caller.principalArn();
        Map<String, List<String>> context = new HashMap<>(requestContext);
        if (principalArn != null) {
            caller.putPrincipalKeys(context);
        }
        Iterable<JsonNode> statementList = statements.isArray() ? statements : List.of(statements);
        TrustDecision decision = TrustDecision.NEUTRAL;
        for (JsonNode stmt : statementList) {
            TrustDecision match =
                    evaluateStatement(stmt, callerArn, principalArn, callerAccount, context, version);
            if (match == TrustDecision.EXPLICIT_DENY) {
                return TrustDecision.EXPLICIT_DENY;
            }
            if (match == TrustDecision.ALLOW || decision == TrustDecision.NEUTRAL) {
                decision = match;
            }
        }
        return decision;
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
     * Checks a service trust policy against the context of a call the service makes: its principal
     * keys, and aws:SourceArn and aws:SourceAccount when it names them. A condition on any other
     * key finds it absent and does not match, so it never turns a conditional Allow into an
     * unconditional one.
     */
    public boolean allowsService(String trustPolicyDocument, String servicePrincipal,
                                 String sourceArn, String sourceAccount) {
        if (trustPolicyDocument == null || trustPolicyDocument.isBlank()) {
            return false;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(trustPolicyDocument);
        } catch (Exception e) {
            LOG.warnv("Failed to parse trust policy: {0}", e.getMessage());
            return false;
        }
        JsonNode statements = root.path("Statement");
        if (!isWellFormed(statements)) {
            return false;
        }
        String version = root.path("Version").asText(null);
        Map<String, List<String>> context = IamConditionContextResolver.serviceCallContext(
                servicePrincipal, null, null, sourceArn, sourceAccount);
        boolean allow = false;
        if (statements.isArray()) {
            for (JsonNode statement : statements) {
                switch (evaluateServiceStatement(statement, servicePrincipal, context, version)) {
                    case DENY -> { return false; }
                    case ALLOW -> allow = true;
                    case NO_MATCH -> { }
                }
            }
        } else if (statements.isObject()) {
            return evaluateServiceStatement(statements, servicePrincipal, context, version) == Match.ALLOW;
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

    private TrustDecision evaluateStatement(JsonNode stmt, String callerArn, String principalArn,
                                            String callerAccount, Map<String, List<String>> context,
                                            String policyVersion) {
        if (!actionApplies(stmt)) {
            return TrustDecision.NEUTRAL;
        }
        PrincipalMatch principal = matchPrincipal(stmt.get("Principal"), callerArn, principalArn, callerAccount);
        if (principal == PrincipalMatch.NONE) {
            return TrustDecision.NEUTRAL;
        }
        if (!policyEvaluator.conditionMatches(stmt.get("Condition"), context, policyVersion)) {
            return TrustDecision.NEUTRAL;
        }
        return switch (effectOf(stmt)) {
            case DENY -> TrustDecision.EXPLICIT_DENY;
            case ALLOW -> principal == PrincipalMatch.CALLER ? TrustDecision.ALLOW : TrustDecision.ALLOW_ACCOUNT;
            case NO_MATCH -> TrustDecision.NEUTRAL;
        };
    }

    private Match evaluateServiceStatement(JsonNode stmt, String servicePrincipal) {
        if (!actionApplies(stmt) || !matchesServicePrincipal(stmt.get("Principal"), servicePrincipal)) {
            return Match.NO_MATCH;
        }
        return effectOf(stmt);
    }

    private Match evaluateServiceStatement(JsonNode stmt, String servicePrincipal,
                                           Map<String, List<String>> context, String policyVersion) {
        if (!actionApplies(stmt) || !matchesServicePrincipal(stmt.get("Principal"), servicePrincipal)) {
            return Match.NO_MATCH;
        }
        if (!policyEvaluator.conditionMatches(stmt.get("Condition"), context, policyVersion)) {
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
        // "*" and {"AWS": "*"} name every principal, a service among them.
        if (principalNode.isTextual()) {
            return "*".equals(principalNode.asText());
        }
        if (!principalNode.isObject()) {
            return false;
        }
        // A policy may name the service in the universal form or the partition form AWS accepted
        // before it (elasticmapreduce.amazonaws.com.cn); both sides are folded to the universal
        // one so either matches, still exactly and case-sensitively.
        String wanted = ServicePrincipals.canonical(servicePrincipal);
        return anyEntry(principalNode.get("AWS"), "*"::equals)
                || anyEntry(principalNode.get("Service"), name -> ServicePrincipals.canonical(name).equals(wanted));
    }

    /** True if {@code node}, a string or an array of them, holds one that {@code matches} accepts. */
    private static boolean anyEntry(JsonNode node, Predicate<String> matches) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            return matches.test(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode entry : node) {
                if (entry.isTextual() && matches.test(entry.asText())) {
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

    /** The narrowest of the statement's principals that matches the caller wins: the caller over its account. */
    private PrincipalMatch matchPrincipal(JsonNode principalNode, String callerArn, String principalArn,
                                          String callerAccount) {
        if (principalNode == null) {
            return PrincipalMatch.NONE;
        }
        // Principal: "*"
        if (principalNode.isTextual()) {
            return "*".equals(principalNode.asText()) ? PrincipalMatch.CALLER : PrincipalMatch.NONE;
        }
        if (!principalNode.isObject()) {
            return PrincipalMatch.NONE;
        }
        // Only the AWS principal type can match a SigV4 caller.
        JsonNode aws = principalNode.get("AWS");
        if (aws == null) {
            return PrincipalMatch.NONE;
        }
        if (aws.isTextual()) {
            return matchAwsPrincipal(aws.asText(), callerArn, principalArn, callerAccount);
        }
        PrincipalMatch match = PrincipalMatch.NONE;
        if (aws.isArray()) {
            for (JsonNode entry : aws) {
                PrincipalMatch entryMatch = entry.isTextual()
                        ? matchAwsPrincipal(entry.asText(), callerArn, principalArn, callerAccount)
                        : PrincipalMatch.NONE;
                if (entryMatch == PrincipalMatch.CALLER) {
                    return PrincipalMatch.CALLER;
                }
                if (entryMatch == PrincipalMatch.ACCOUNT) {
                    match = PrincipalMatch.ACCOUNT;
                }
            }
        }
        return match;
    }

    private PrincipalMatch matchAwsPrincipal(String principal, String callerArn, String principalArn,
                                             String callerAccount) {
        if (principal == null) {
            return PrincipalMatch.NONE;
        }
        if ("*".equals(principal)) {
            return PrincipalMatch.CALLER;
        }
        // Bare 12-digit account id, or an account-root ARN → matches any principal in that account.
        if (principal.matches("\\d{12}")) {
            return principal.equals(callerAccount) ? PrincipalMatch.ACCOUNT : PrincipalMatch.NONE;
        }
        Matcher rootMatcher = ACCOUNT_ROOT_ARN.matcher(principal);
        if (rootMatcher.matches()) {
            return rootMatcher.group(1).equals(callerAccount) ? PrincipalMatch.ACCOUNT : PrincipalMatch.NONE;
        }
        // Otherwise an exact (glob-capable) principal ARN: the caller's own ARN, which for a role
        // session is its assumed-role session ARN, or the role's ARN for a Principal naming the role.
        if (callerArn != null && IamPolicyEvaluator.globMatches(principal, callerArn)) {
            return PrincipalMatch.CALLER;
        }
        return principalArn != null && IamPolicyEvaluator.globMatches(principal, principalArn)
                ? PrincipalMatch.CALLER : PrincipalMatch.NONE;
    }
}
