package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.PolicyStatement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates IAM policy documents against a requested action and resource.
 *
 * <p>Implements the AWS policy evaluation logic across Phases 1-4:
 * <ul>
 *   <li>Phase 1: identity-based policies (inline + attached + groups)</li>
 *   <li>Phase 2: resource-based policies (same-account grant semantics)</li>
 *   <li>Phase 3: session policies + permission boundaries</li>
 *   <li>Phase 4: condition operators, NotAction, NotResource</li>
 * </ul>
 *
 * <p>Evaluation algorithm (AWS order of precedence):
 * <ol>
 *   <li>Explicit Deny in ANY policy → DENY</li>
 *   <li>identityAllow OR resourceAllow</li>
 *   <li>AND (no session policy OR sessionAllow)</li>
 *   <li>AND (no boundary OR boundaryAllow)</li>
 *   <li>→ ALLOW</li>
 *   <li>Otherwise → DENY (implicit)</li>
 * </ol>
 */
@ApplicationScoped
public class IamPolicyEvaluator {

    public enum Decision { ALLOW, DENY }

    private static final Logger LOG = Logger.getLogger(IamPolicyEvaluator.class);

    private final ObjectMapper objectMapper;

    @Inject
    public IamPolicyEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Full evaluation including resource policies, session policy, boundary, and conditions.
     *
     * @param caller        identity context (identity policies, optional session policy, optional boundary)
     * @param resourcePolicies resource-based policy documents (Phase 2); may be null or empty
     * @param action        IAM action, e.g. "s3:GetObject"
     * @param resource      resource ARN, e.g. "arn:aws:s3:::my-bucket/key"
     * @param conditionCtx  condition context key-value pairs (lowercase keys); may be null or empty
     * @return {@link Decision#ALLOW} or {@link Decision#DENY}
     */
    public Decision evaluate(CallerContext caller,
                             List<String> resourcePolicies,
                             String action,
                             String resource,
                             Map<String, String> conditionCtx) {
        Map<String, String> ctx = conditionCtx == null ? Map.of() : conditionCtx;

        List<PolicyStatement> identityStmts = parseAll(caller.identityPolicies());
        List<PolicyStatement> resourceStmts = resourcePolicies == null ? List.of() : parseAll(resourcePolicies);
        List<PolicyStatement> sessionStmts  = caller.sessionPolicyDocument() == null
                ? null : parseAll(List.of(caller.sessionPolicyDocument()));
        List<PolicyStatement> boundaryStmts = caller.boundaryPolicyDocument() == null
                ? null : parseAll(List.of(caller.boundaryPolicyDocument()));

        // 1. Explicit deny in ANY policy → DENY immediately
        if (anyExplicitDeny(identityStmts, action, resource, ctx)
                || anyExplicitDeny(resourceStmts, action, resource, ctx)
                || (sessionStmts  != null && anyExplicitDeny(sessionStmts,  action, resource, ctx))
                || (boundaryStmts != null && anyExplicitDeny(boundaryStmts, action, resource, ctx))) {
            return Decision.DENY;
        }

        // 2. Base grant: identity OR resource-based policy must allow
        boolean identityAllow = anyExplicitAllow(identityStmts, action, resource, ctx);
        boolean resourceAllow = anyExplicitAllow(resourceStmts, action, resource, ctx);
        if (!identityAllow && !resourceAllow) {
            return Decision.DENY;
        }

        // 3. Session policy (if present) must also allow (intersection)
        if (sessionStmts != null && !anyExplicitAllow(sessionStmts, action, resource, ctx)) {
            return Decision.DENY;
        }

        // 4. Permission boundary (if present) must also allow (caps maximum permissions)
        if (boundaryStmts != null && !anyExplicitAllow(boundaryStmts, action, resource, ctx)) {
            return Decision.DENY;
        }

        return Decision.ALLOW;
    }

    /**
     * Convenience overload: identity policies only, no conditions.
     * Backward-compatible with Phase 1 callers.
     */
    public Decision evaluate(List<String> policyDocuments, String action, String resource) {
        return evaluate(CallerContext.of(policyDocuments), null, action, resource, null);
    }

    /**
     * Evaluates a standalone set of policy documents — used by SimulateCustomPolicy.
     */
    public Decision simulateCustomPolicy(List<String> policyDocuments,
                                          String action,
                                          String resource,
                                          Map<String, String> conditionCtx) {
        return evaluate(CallerContext.of(policyDocuments), null, action, resource, conditionCtx);
    }

    // -----------------------------------------------------------------------
    // Statement matching
    // -----------------------------------------------------------------------

    private boolean anyExplicitDeny(List<PolicyStatement> stmts, String action, String resource,
                                     Map<String, String> ctx) {
        for (PolicyStatement stmt : stmts) {
            if (stmt.isDeny() && matchesStatement(stmt, action, resource, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyExplicitAllow(List<PolicyStatement> stmts, String action, String resource,
                                      Map<String, String> ctx) {
        for (PolicyStatement stmt : stmts) {
            if (stmt.isAllow() && matchesStatement(stmt, action, resource, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesStatement(PolicyStatement stmt, String action, String resource,
                                      Map<String, String> ctx) {
        return matchesAction(stmt, action)
                && matchesResource(stmt, resource)
                && matchesConditions(stmt.getConditions(), ctx);
    }

    /** Action: matches if any Action pattern matches; NotAction: matches if NO pattern matches. */
    private boolean matchesAction(PolicyStatement stmt, String action) {
        if (stmt.getActions() != null) {
            return matchesAny(stmt.getActions(), action);
        }
        if (stmt.getNotActions() != null) {
            return !matchesAny(stmt.getNotActions(), action);
        }
        return false;
    }

    /** Resource: matches if any Resource pattern matches; NotResource: matches if NO pattern matches. */
    private boolean matchesResource(PolicyStatement stmt, String resource) {
        if (stmt.getResources() != null) {
            return matchesAny(stmt.getResources(), resource);
        }
        if (stmt.getNotResources() != null) {
            return !matchesAny(stmt.getNotResources(), resource);
        }
        return false;
    }

    private boolean matchesAny(List<String> patterns, String value) {
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (globMatches(pattern, value)) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Condition evaluation (Phase 4)
    // -----------------------------------------------------------------------

    /**
     * Evaluates all condition blocks. AND between blocks, OR within each block's value list.
     * Returns true if ALL blocks pass (or there are no conditions).
     */
    private boolean matchesConditions(Map<String, Map<String, List<String>>> conditions,
                                       Map<String, String> ctx) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Map<String, List<String>>> entry : conditions.entrySet()) {
            if (!evaluateConditionBlock(entry.getKey(), entry.getValue(), ctx)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateConditionBlock(String operator,
                                            Map<String, List<String>> keyValueMap,
                                            Map<String, String> ctx) {
        boolean ifExists = operator.endsWith("IfExists");
        String baseOp = ifExists ? operator.substring(0, operator.length() - "IfExists".length()) : operator;

        for (Map.Entry<String, List<String>> entry : keyValueMap.entrySet()) {
            String condKey = entry.getKey().toLowerCase();
            List<String> condValues = entry.getValue();
            String ctxValue = ctx.get(condKey);

            if (ctxValue == null) {
                if ("Null".equalsIgnoreCase(baseOp)) {
                    // Null: {key: "true"} → key must be absent → pass when any condValue is "true"
                    boolean expectAbsent = condValues.stream().anyMatch("true"::equalsIgnoreCase);
                    if (!expectAbsent) {
                        return false;
                    }
                    continue;
                }
                if (ifExists) {
                    continue; // key missing + IfExists → pass this key
                }
                return false; // key missing, no IfExists → fail entire block
            }

            if ("Null".equalsIgnoreCase(baseOp)) {
                // Key is present — Null:{key:"true"} should fail, Null:{key:"false"} should pass
                boolean expectAbsent = condValues.stream().anyMatch("true"::equalsIgnoreCase);
                if (expectAbsent) {
                    return false; // expected absent but key has value
                }
                continue;
            }

            // OR across condValues for this key
            boolean keyMatch = false;
            for (String condValue : condValues) {
                if (evaluateSingleCondition(baseOp, ctxValue, condValue)) {
                    keyMatch = true;
                    break;
                }
            }
            if (!keyMatch) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateSingleCondition(String operator, String ctxValue, String condValue) {
        return switch (operator) {
            case "StringEquals"              -> ctxValue.equals(condValue);
            case "StringNotEquals"           -> !ctxValue.equals(condValue);
            case "StringEqualsIgnoreCase"    -> ctxValue.equalsIgnoreCase(condValue);
            case "StringNotEqualsIgnoreCase" -> !ctxValue.equalsIgnoreCase(condValue);
            case "StringLike"                -> globMatches(condValue, ctxValue);
            case "StringNotLike"             -> !globMatches(condValue, ctxValue);
            case "ArnEquals", "ArnLike"      -> globMatches(condValue, ctxValue);
            case "ArnNotEquals", "ArnNotLike"-> !globMatches(condValue, ctxValue);
            case "Bool"                      -> Boolean.parseBoolean(condValue) == Boolean.parseBoolean(ctxValue);
            case "NumericEquals"             -> compareNumeric(ctxValue, condValue) == 0;
            case "NumericNotEquals"          -> compareNumeric(ctxValue, condValue) != 0;
            case "NumericLessThan"           -> compareNumeric(ctxValue, condValue) < 0;
            case "NumericLessThanEquals"     -> compareNumeric(ctxValue, condValue) <= 0;
            case "NumericGreaterThan"        -> compareNumeric(ctxValue, condValue) > 0;
            case "NumericGreaterThanEquals"  -> compareNumeric(ctxValue, condValue) >= 0;
            case "DateEquals"                -> compareDates(ctxValue, condValue) == 0;
            case "DateNotEquals"             -> compareDates(ctxValue, condValue) != 0;
            case "DateLessThan"              -> compareDates(ctxValue, condValue) < 0;
            case "DateLessThanEquals"        -> compareDates(ctxValue, condValue) <= 0;
            case "DateGreaterThan"           -> compareDates(ctxValue, condValue) > 0;
            case "DateGreaterThanEquals"     -> compareDates(ctxValue, condValue) >= 0;
            case "IpAddress"                 -> matchesIpAddress(condValue, ctxValue);
            case "NotIpAddress"              -> !matchesIpAddress(condValue, ctxValue);
            default -> {
                LOG.warnv("Unsupported condition operator: {0} — treating as no-match", operator);
                yield false;
            }
        };
    }

    private int compareNumeric(String ctxValue, String condValue) {
        try {
            return Double.compare(Double.parseDouble(ctxValue), Double.parseDouble(condValue));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int compareDates(String ctxValue, String condValue) {
        try {
            return Instant.parse(ctxValue).compareTo(Instant.parse(condValue));
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean matchesIpAddress(String condValue, String ctxValue) {
        if (condValue.contains("/")) {
            return matchesCidr(condValue, ctxValue);
        }
        return condValue.equals(ctxValue);
    }

    private boolean matchesCidr(String cidr, String ip) {
        try {
            String[] parts = cidr.split("/");
            int prefix = Integer.parseInt(parts[1]);
            long cidrAddr = ipToLong(parts[0]);
            long ipAddr = ipToLong(ip);
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (cidrAddr & mask) == (ipAddr & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private long ipToLong(String ip) {
        String[] octets = ip.split("\\.");
        long result = 0;
        for (String octet : octets) {
            result = (result << 8) | Integer.parseInt(octet);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Glob matching (case-insensitive, supports * and ?)
    // -----------------------------------------------------------------------

    /**
     * Case-insensitive glob matching supporting {@code *} (any sequence) and {@code ?} (any char).
     */
    public static boolean globMatches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        return globMatchesHelper(pattern.toLowerCase(), value.toLowerCase(), 0, 0);
    }

    private static boolean globMatchesHelper(String pat, String val, int pi, int vi) {
        while (pi < pat.length() && vi < val.length()) {
            char p = pat.charAt(pi);
            if (p == '*') {
                while (pi < pat.length() && pat.charAt(pi) == '*') {
                    pi++;
                }
                if (pi == pat.length()) {
                    return true;
                }
                for (int i = vi; i <= val.length(); i++) {
                    if (globMatchesHelper(pat, val, pi, i)) {
                        return true;
                    }
                }
                return false;
            } else if (p == '?' || p == val.charAt(vi)) {
                pi++;
                vi++;
            } else {
                return false;
            }
        }
        while (pi < pat.length() && pat.charAt(pi) == '*') {
            pi++;
        }
        return pi == pat.length() && vi == val.length();
    }

    // -----------------------------------------------------------------------
    // Policy document parsing
    // -----------------------------------------------------------------------

    private List<PolicyStatement> parseAll(List<String> documents) {
        List<PolicyStatement> result = new ArrayList<>();
        if (documents == null) {
            return result;
        }
        for (String doc : documents) {
            try {
                result.addAll(parseStatements(doc));
            } catch (Exception e) {
                LOG.warnv("Failed to parse policy document: {0}", e.getMessage());
            }
        }
        return result;
    }

    private List<PolicyStatement> parseStatements(String document) throws Exception {
        JsonNode root = objectMapper.readTree(document);
        JsonNode stmtNode = root.path("Statement");
        List<PolicyStatement> result = new ArrayList<>();
        if (stmtNode.isArray()) {
            for (JsonNode s : stmtNode) {
                result.add(parseStatement(s));
            }
        } else if (stmtNode.isObject()) {
            result.add(parseStatement(stmtNode));
        }
        return result;
    }

    private PolicyStatement parseStatement(JsonNode stmt) {
        String effect = stmt.path("Effect").asText("Allow");
        List<String> actions     = nodeToList(stmt.get("Action"));
        List<String> notActions  = nodeToList(stmt.get("NotAction"));
        List<String> resources   = nodeToList(stmt.get("Resource"));
        List<String> notResources= nodeToList(stmt.get("NotResource"));
        Map<String, Map<String, List<String>>> conditions = parseConditions(stmt.get("Condition"));
        return new PolicyStatement(
                effect,
                actions.isEmpty()     ? null : actions,
                notActions.isEmpty()  ? null : notActions,
                resources.isEmpty()   ? null : resources,
                notResources.isEmpty()? null : notResources,
                conditions);
    }

    private Map<String, Map<String, List<String>>> parseConditions(JsonNode condNode) {
        if (condNode == null || condNode.isNull() || !condNode.isObject()) {
            return null;
        }
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        condNode.fields().forEachRemaining(opEntry -> {
            Map<String, List<String>> kvMap = new LinkedHashMap<>();
            opEntry.getValue().fields().forEachRemaining(kvEntry ->
                    kvMap.put(kvEntry.getKey(), nodeToList(kvEntry.getValue())));
            result.put(opEntry.getKey(), kvMap);
        });
        return result.isEmpty() ? null : result;
    }

    private List<String> nodeToList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null) {
            return list;
        }
        if (node.isTextual()) {
            list.add(node.asText());
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                list.add(item.asText());
            }
        }
        return list;
    }
}
