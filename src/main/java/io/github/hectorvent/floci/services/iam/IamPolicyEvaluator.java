package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsServiceNamespaces;
import io.github.hectorvent.floci.core.common.CidrCanonicalizer;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** An account's root principal ({@code arn:<partition>:iam::<account>:root}) in any partition. */
    private static final Pattern ROOT_PRINCIPAL_ARN =
            Pattern.compile("arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::\\d{12}:root");

    public enum Decision { ALLOW, DENY }

    public enum ResourcePolicyDecision {
        ALLOW,
        ALLOW_DIRECT_IAM_USER,
        EXPLICIT_DENY,
        NEUTRAL
    }

    public enum ResourceAccountRelationship {
        SAME_ACCOUNT,
        CROSS_ACCOUNT
    }

    public enum SimulationDecision {
        ALLOWED("allowed"),
        EXPLICIT_DENY("explicitDeny"),
        IMPLICIT_DENY("implicitDeny");

        private final String awsValue;

        SimulationDecision(String awsValue) {
            this.awsValue = awsValue;
        }

        public String awsValue() {
            return awsValue;
        }
    }

    private static final Logger LOG = Logger.getLogger(IamPolicyEvaluator.class);

    // Parsing is a pure function of the document text, so entries never go stale. The bound
    // only guards against growth from many distinct session policies.
    static final int MAX_CACHED_DOCUMENTS = 2048;

    // Matches an IAM policy variable such as ${aws:username} inside a Resource pattern or a
    // Condition value. Stops at the first ',' or '}' so a default value (${key, 'default'}),
    // whose default may itself contain '{{' / '}}' placeholder markers, doesn't get swept into
    // the captured key name.
    private static final Pattern POLICY_VARIABLE = Pattern.compile("\\$\\{\\s*([^,}]+?)\\s*[,}]");

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedDocument> cachedDocuments = new ConcurrentHashMap<>();

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
     * @param conditionCtx  condition context key -> values; may be null or empty
     * @return {@link Decision#ALLOW} or {@link Decision#DENY}
     */
    public Decision evaluate(CallerContext caller,
                             List<String> resourcePolicies,
                             String action,
                             String resource,
                             Map<String, List<String>> conditionCtx) {
        String principalArn = caller != null ? caller.principalArn() : null;
        ResourcePolicyDecision resourceDecision = evaluateResourcePolicy(
                resourcePolicies, principalArn, action, resource, conditionCtx);
        return evaluateResolvedResourcePolicy(
                caller, resourceDecision, ResourceAccountRelationship.SAME_ACCOUNT, action, resource, conditionCtx);
    }

    /**
     * Evaluates an already principal-filtered resource-policy decision together with the
     * caller's identity policies and permission ceilings.
     */
    public Decision evaluateResolvedResourcePolicy(
            CallerContext caller,
            ResourcePolicyDecision resourcePolicyDecision,
            String action,
            String resource,
            Map<String, List<String>> conditionCtx) {
        return evaluateResolvedResourcePolicy(
                caller,
                resourcePolicyDecision,
                ResourceAccountRelationship.SAME_ACCOUNT,
                action,
                resource,
                conditionCtx);
    }

    /**
     * Evaluates a principal-filtered resource policy with the caller/resource account relationship.
     * Cross-account access requires both identity and resource policy allows. A same-account resource
     * policy that directly names an IAM user ARN is not limited by an implicit permissions-boundary
     * deny, although every explicit deny still wins.
     */
    public Decision evaluateResolvedResourcePolicy(
            CallerContext caller,
            ResourcePolicyDecision resourcePolicyDecision,
            ResourceAccountRelationship accountRelationship,
            String action,
            String resource,
            Map<String, List<String>> conditionCtx) {
        Map<String, List<String>> ctx = normalizeConditionContext(conditionCtx);
        List<PolicyStatement> identityStmts = parseAll(caller.identityPolicies());
        List<PolicyStatement> sessionStmts = caller.sessionPolicyDocument() == null
                ? null : parseAll(List.of(caller.sessionPolicyDocument()));
        List<PolicyStatement> boundaryStmts = caller.boundaryPolicyDocument() == null
                ? null : parseAll(List.of(caller.boundaryPolicyDocument()));
        ResourcePolicyDecision resolvedDecision = resourcePolicyDecision == null
                ? ResourcePolicyDecision.NEUTRAL : resourcePolicyDecision;
        return evaluateParsed(
                caller, identityStmts, sessionStmts, boundaryStmts,
                resolvedDecision == ResourcePolicyDecision.EXPLICIT_DENY,
                resolvedDecision == ResourcePolicyDecision.ALLOW
                        || resolvedDecision == ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER,
                resolvedDecision == ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER,
                accountRelationship == null
                        ? ResourceAccountRelationship.CROSS_ACCOUNT : accountRelationship,
                lowercase(action), resource, ctx);
    }

    /**
     * Evaluates a resource-based policy standalone for an IAM caller identified by its ARN. With no
     * ARN, the request's {@code aws:PrincipalArn} stands in for it, which is IAM-only too: the key
     * names IAM users, roles, federated users and the account root, never a service.
     */
    public ResourcePolicyDecision evaluateResourcePolicy(
            List<String> resourcePolicies,
            String principalArn,
            String action,
            String resource,
            Map<String, List<String>> conditionCtx) {
        String callerArn = principalArn;
        if (callerArn == null) {
            List<String> principalArns = normalizeConditionContext(conditionCtx).get("aws:principalarn");
            if (principalArns != null && !principalArns.isEmpty()) {
                callerArn = principalArns.getFirst();
            }
        }
        RequestPrincipal principal = callerArn == null
                ? RequestPrincipal.anonymous()
                : RequestPrincipal.iam(callerArn);
        return evaluateResourcePolicyFor(resourcePolicies, principal, action, resource, conditionCtx);
    }

    /**
     * Evaluates a resource-based policy standalone for a typed caller. Each principal type in the
     * policy matches only the callers it can name: {@code AWS} an IAM identity, {@code Service} a
     * service by its exact name, {@code "*"} anyone. Named apart from the String form so a call
     * passing a literal null caller to that one stays unambiguous.
     */
    public ResourcePolicyDecision evaluateResourcePolicyFor(
            List<String> resourcePolicies,
            RequestPrincipal principal,
            String action,
            String resource,
            Map<String, List<String>> conditionCtx) {
        if (resourcePolicies == null || resourcePolicies.isEmpty()) {
            return ResourcePolicyDecision.NEUTRAL;
        }
        Map<String, List<String>> ctx = normalizeConditionContext(conditionCtx);
        String loweredAction = lowercase(action);
        List<PolicyStatement> stmts = parseAll(resourcePolicies);
        if (anyResourceExplicitDeny(stmts, principal, loweredAction, resource, ctx)) {
            return ResourcePolicyDecision.EXPLICIT_DENY;
        }
        if (anyResourceDirectIamUserAllow(stmts, principal, loweredAction, resource, ctx)) {
            return ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER;
        }
        if (anyResourceExplicitAllow(stmts, principal, loweredAction, resource, ctx)) {
            return ResourcePolicyDecision.ALLOW;
        }
        return ResourcePolicyDecision.NEUTRAL;
    }

    /**
     * Decides a call an AWS service makes on its own behalf, such as SNS delivering to a queue. The
     * resource policy decides alone: a service principal carries no identity, session or boundary
     * policy, and SCPs apply only to the IAM users and roles of an organization's accounts. An
     * explicit Deny denies, an Allow allows, and a policy that says nothing denies.
     */
    public Decision evaluateServicePrincipal(
            List<String> resourcePolicies,
            String servicePrincipal,
            String action,
            String resource,
            Map<String, List<String>> conditionCtx) {
        ResourcePolicyDecision decision = evaluateResourcePolicyFor(
                resourcePolicies, RequestPrincipal.service(servicePrincipal), action, resource, conditionCtx);
        return decision == ResourcePolicyDecision.ALLOW ? Decision.ALLOW : Decision.DENY;
    }

    private Decision evaluateParsed(
            CallerContext caller,
            List<PolicyStatement> identityStmts,
            List<PolicyStatement> sessionStmts,
            List<PolicyStatement> boundaryStmts,
            boolean resourceExplicitDeny,
            boolean resourceAllow,
            boolean directIamUserResourceAllow,
            ResourceAccountRelationship accountRelationship,
            String action,
            String resource,
            Map<String, List<String>> ctx) {

        // 0. Service control policies gate everything: the action must be allowed at EVERY
        //    organization level and explicitly denied at none, before identity policies are
        //    even consulted. An empty level means FullAWSAccess semantics and is skipped;
        //    a level holding an unparseable document denies.
        if (!scpAllows(caller.scpLevels(), action, resource, ctx)) {
            return Decision.DENY;
        }

        // 1. Explicit deny in ANY policy → DENY immediately
        if (anyExplicitDeny(identityStmts, action, resource, ctx)
                || resourceExplicitDeny
                || (sessionStmts  != null && anyExplicitDeny(sessionStmts,  action, resource, ctx))
                || (boundaryStmts != null && anyExplicitDeny(boundaryStmts, action, resource, ctx))) {
            return Decision.DENY;
        }

        // 2. Base grant: same-account access needs either policy family; cross-account access
        //    needs both the caller's identity policy and the resource owner's policy.
        boolean identityAllow = anyExplicitAllow(identityStmts, action, resource, ctx);
        if (accountRelationship == ResourceAccountRelationship.CROSS_ACCOUNT) {
            if (!identityAllow || !resourceAllow) {
                return Decision.DENY;
            }
        } else if (!identityAllow && !resourceAllow) {
            return Decision.DENY;
        }

        // 3. Session policy (if present) must also allow (intersection)
        if (sessionStmts != null && !anyExplicitAllow(sessionStmts, action, resource, ctx)) {
            return Decision.DENY;
        }

        // 4. A same-account resource policy that names an IAM user ARN grants directly to that
        //    user. Its boundary's implicit deny does not cap the grant; explicit denies were
        //    already handled above. Other grants remain limited by the boundary intersection.
        boolean directSameAccountUserGrant = directIamUserResourceAllow
                && accountRelationship == ResourceAccountRelationship.SAME_ACCOUNT;
        if (!directSameAccountUserGrant && boundaryStmts != null
                && !anyExplicitAllow(boundaryStmts, action, resource, ctx)) {
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
                                          Map<String, List<String>> conditionCtx) {
        return evaluate(CallerContext.of(policyDocuments), null, action, resource, conditionCtx);
    }

    public SimulationDecision simulatePrincipalPolicy(CallerContext caller,
                                                      String action,
                                                      String resource,
                                                      Map<String, List<String>> conditionCtx) {
        Map<String, List<String>> ctx = normalizeConditionContext(conditionCtx);
        String loweredAction = lowercase(action);
        List<PolicyStatement> identityStmts = parseAll(caller.identityPolicies());
        List<PolicyStatement> sessionStmts = caller.sessionPolicyDocument() == null
                ? null : parseAll(List.of(caller.sessionPolicyDocument()));
        List<PolicyStatement> boundaryStmts = caller.boundaryPolicyDocument() == null
                ? null : parseAll(List.of(caller.boundaryPolicyDocument()));

        if (anyExplicitDeny(identityStmts, loweredAction, resource, ctx)
                || (sessionStmts != null && anyExplicitDeny(sessionStmts, loweredAction, resource, ctx))
                || (boundaryStmts != null && anyExplicitDeny(boundaryStmts, loweredAction, resource, ctx))) {
            return SimulationDecision.EXPLICIT_DENY;
        }
        if (!anyExplicitAllow(identityStmts, loweredAction, resource, ctx)) {
            return SimulationDecision.IMPLICIT_DENY;
        }
        if (sessionStmts != null && !anyExplicitAllow(sessionStmts, loweredAction, resource, ctx)) {
            return SimulationDecision.IMPLICIT_DENY;
        }
        if (boundaryStmts != null && !anyExplicitAllow(boundaryStmts, loweredAction, resource, ctx)) {
            return SimulationDecision.IMPLICIT_DENY;
        }
        return SimulationDecision.ALLOWED;
    }

    /**
     * Returns the context keys referenced across the given policy documents: every Condition
     * operator's key names, every {@code ${...}} policy variable named in a Resource entry, and
     * every one named in a Condition value (AWS documents policy variables as usable only in
     * the Resource element and in Condition values, never in NotResource, Action or Principal).
     * AWS's own primary example for this response is a Resource-embedded variable:
     * {@code ${aws:username}} inside a Resource ARN.
     *
     * <p>The three single-character escapes ({@code ${*}}, {@code ${?}}, {@code ${$}}) are
     * literal-character substitutions, not context-key references, and are excluded. A default
     * value ({@code ${key, 'default'}}) is stripped so only {@code key} is reported.
     *
     * <p>Not sorted and not de-duplicated: AWS's own documented example response for
     * GetContextKeysForPrincipalPolicy repeats a key that is referenced by more than one
     * statement, so this returns them in statement order exactly as found, matching that
     * observed behavior rather than imposing an artificial, AWS-inconsistent cleanup.
     *
     * <p>A document that fails to parse contributes no keys, matching {@link #parseAll}'s
     * handling elsewhere in this class.
     */
    public List<String> contextKeysReferencedIn(List<String> policyDocuments) {
        List<String> keys = new ArrayList<>();
        for (PolicyStatement stmt : parseAll(policyDocuments)) {
            Map<String, Map<String, List<String>>> conditions = stmt.getConditions();
            if (conditions != null) {
                for (Map<String, List<String>> byContextKey : conditions.values()) {
                    for (Map.Entry<String, List<String>> entry : byContextKey.entrySet()) {
                        keys.add(entry.getKey());
                        collectPolicyVariableKeys(entry.getValue(), keys);
                    }
                }
            }
            collectPolicyVariableKeys(stmt.getResources(), keys);
        }
        return keys;
    }

    /**
     * Whether {@code policyDocument} lets its holder reach any action in {@code serviceNamespace},
     * backing {@code ListPoliciesGrantingServiceAccess}.
     *
     * <p>Only {@code Allow} statements grant, and only the permissions-policy logic is applied:
     * AWS documents that this operation ignores resource-based policies, ACLs, Organizations
     * policies, permissions boundaries and trust policies. Resources and conditions are not
     * consulted either, because the question is which policies could grant the service at all,
     * not whether a specific call would be authorized.
     *
     * <p>A document that fails to parse grants nothing, matching {@link #parseAll} elsewhere here.
     */
    public boolean grantsServiceAccess(String policyDocument, String serviceNamespace) {
        if (policyDocument == null || serviceNamespace == null || serviceNamespace.isBlank()) {
            return false;
        }
        for (PolicyStatement stmt : parseAll(List.of(policyDocument))) {
            if (!"Allow".equalsIgnoreCase(stmt.getEffect())) {
                continue;
            }
            if (stmt.getActions() != null) {
                for (String pattern : stmt.getActions()) {
                    if (actionPatternReaches(pattern, serviceNamespace)) {
                        return true;
                    }
                }
            } else if (stmt.getNotActions() != null && !excludesEntirely(stmt.getNotActions(), serviceNamespace)) {
                // Allow + NotAction grants everything the list does not carve out, so the
                // namespace is still reachable unless the list removes all of it.
                return true;
            }
        }
        return false;
    }

    /**
     * The service namespaces {@code policyDocuments} grant, backing the service list in
     * {@code GetServiceLastAccessedDetails}.
     *
     * <p>AWS lists a service the entity could reach even when it was never used, so the list comes
     * from the same permissions-policy logic as {@link #grantsServiceAccess}. A grant that names no
     * namespace is expanded against {@link AwsServiceNamespaces}: {@code Action: "*"} yields every
     * published namespace, a globbed prefix such as {@code s3*} yields the ones it matches, and an
     * {@code Allow} with {@code NotAction} yields everything the list does not carve out entirely.
     *
     * <p>A namespace written out literally is kept whether or not the vendored catalog knows it, so
     * a catalog that has fallen behind an AWS launch still reports a service the policy names
     * outright; only wildcard expansion depends on the catalog being current.
     *
     * <p>A document that fails to parse grants nothing, matching {@link #parseAll} elsewhere here.
     */
    public List<String> servicesGrantedBy(List<String> policyDocuments) {
        Set<String> granted = new TreeSet<>();
        for (PolicyStatement stmt : parseAll(policyDocuments)) {
            if (!"Allow".equalsIgnoreCase(stmt.getEffect())) {
                continue;
            }
            if (stmt.getNotActions() != null) {
                for (String namespace : AwsServiceNamespaces.all()) {
                    if (!excludesEntirely(stmt.getNotActions(), namespace)) {
                        granted.add(namespace);
                    }
                }
                continue;
            }
            if (stmt.getActions() == null) {
                continue;
            }
            for (String pattern : stmt.getActions()) {
                collectGrantedNamespaces(pattern, granted);
            }
        }
        return List.copyOf(granted);
    }

    /** Adds whatever one action pattern's service part denotes: a name, a glob, or everything. */
    private static void collectGrantedNamespaces(String pattern, Set<String> granted) {
        if ("*".equals(pattern)) {
            granted.addAll(AwsServiceNamespaces.all());
            return;
        }
        int colon = pattern == null ? -1 : pattern.indexOf(':');
        if (colon < 0) {
            // Not "service:Action" and not "*"; IAM would reject it, so it grants nothing.
            return;
        }
        String namespace = pattern.substring(0, colon);
        if (namespace.indexOf('*') < 0 && namespace.indexOf('?') < 0) {
            granted.add(namespace);
            return;
        }
        for (String candidate : AwsServiceNamespaces.all()) {
            if (globMatches(namespace, candidate)) {
                granted.add(candidate);
            }
        }
    }

    /** An action pattern reaches a namespace when its service part matches, whatever the verb is. */
    private static boolean actionPatternReaches(String pattern, String serviceNamespace) {
        if ("*".equals(pattern)) {
            return true;
        }
        int colon = pattern == null ? -1 : pattern.indexOf(':');
        if (colon < 0) {
            // Not "service:Action" and not "*"; IAM would reject it, so it grants nothing here.
            return false;
        }
        return globMatches(pattern.substring(0, colon), serviceNamespace);
    }

    /** True only if the NotAction list removes every action in the namespace, not merely some. */
    private static boolean excludesEntirely(List<String> notActions, String serviceNamespace) {
        for (String pattern : notActions) {
            if ("*".equals(pattern)) {
                return true;
            }
            int colon = pattern == null ? -1 : pattern.indexOf(':');
            if (colon >= 0 && "*".equals(pattern.substring(colon + 1))
                    && globMatches(pattern.substring(0, colon), serviceNamespace)) {
                return true;
            }
        }
        return false;
    }

    /** Appends the key name inside every {@code ${key}} policy variable found in {@code values}. */
    private void collectPolicyVariableKeys(List<String> values, List<String> keys) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            Matcher matcher = POLICY_VARIABLE.matcher(value);
            while (matcher.find()) {
                String key = matcher.group(1).trim();
                if (!key.equals("*") && !key.equals("?") && !key.equals("$")) {
                    keys.add(key);
                }
            }
        }
    }

    /**
     * SCP evaluation: at every organization level the action must be explicitly allowed
     * and not explicitly denied. {@code null} levels mean SCPs don't apply; a level with
     * no documents at all (defensive — the last SCP on a target can't be detached) is
     * FullAWSAccess semantics and passes.
     *
     * <p>A level containing a document that fails to parse denies. The ceiling cannot tell
     * what an unreadable guardrail would have said, and every target also carries
     * FullAWSAccess, so skipping the bad document would silently leave the level allowing
     * everything the operator meant to forbid.</p>
     */
    private boolean scpAllows(List<List<String>> scpLevels, String action, String resource,
                              Map<String, List<String>> ctx) {
        if (scpLevels == null) {
            return true;
        }
        for (List<String> level : scpLevels) {
            ParsedDocuments parsed = parseAllTracked(level);
            if (parsed.anyFailed()) {
                return false;
            }
            List<PolicyStatement> levelStmts = parsed.statements();
            if (levelStmts.isEmpty()) {
                continue;
            }
            if (anyExplicitDeny(levelStmts, action, resource, ctx)
                    || !anyExplicitAllow(levelStmts, action, resource, ctx)) {
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Statement matching
    // -----------------------------------------------------------------------

    /**
     * Lower-cases keys and copies the value lists, dropping null keys, null lists and null
     * members. An empty list is preserved: an empty set is not the same thing as an absent
     * key — AWS's set operators give it its own semantics (ForAllValues matches vacuously,
     * ForAnyValue does not match).
     */
    private Map<String, List<String>> normalizeConditionContext(Map<String, List<String>> conditionCtx) {
        if (conditionCtx == null || conditionCtx.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : conditionCtx.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            List<String> values = entry.getValue().stream()
                    .filter(java.util.Objects::nonNull)
                    .toList();
            normalized.putIfAbsent(entry.getKey().toLowerCase(java.util.Locale.ROOT), values);
        }
        return normalized;
    }

    private boolean anyExplicitDeny(List<PolicyStatement> stmts, String action, String resource,
                                     Map<String, List<String>> ctx) {
        for (PolicyStatement stmt : stmts) {
            if (stmt.isDeny() && matchesStatement(stmt, action, resource, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyExplicitAllow(List<PolicyStatement> stmts, String action, String resource,
                                      Map<String, List<String>> ctx) {
        for (PolicyStatement stmt : stmts) {
            if (stmt.isAllow() && matchesStatement(stmt, action, resource, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyResourceExplicitDeny(List<PolicyStatement> stmts, RequestPrincipal principal,
                                            String action, String resource, Map<String, List<String>> ctx) {
        for (PolicyStatement stmt : stmts) {
            if (stmt.isDeny() && matchesResourceStatement(stmt, principal, action, resource, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyResourceExplicitAllow(List<PolicyStatement> stmts, RequestPrincipal principal,
                                             String action, String resource, Map<String, List<String>> ctx) {
        for (PolicyStatement stmt : stmts) {
            if (stmt.isAllow() && matchesResourceStatement(stmt, principal, action, resource, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyResourceDirectIamUserAllow(List<PolicyStatement> stmts, RequestPrincipal principal,
                                                 String action, String resource, Map<String, List<String>> ctx) {
        for (PolicyStatement stmt : stmts) {
            if (stmt.isAllow() && matchesDirectIamUserStatement(stmt, principal, action, resource, ctx)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDirectIamUserStatement(PolicyStatement stmt, RequestPrincipal principal,
                                                  String action, String resource, Map<String, List<String>> ctx) {
        if (principal.type() != RequestPrincipal.Type.IAM
                || principal.arn() == null || !principal.arn().contains(":user/")) {
            return false;
        }
        String principalArn = principal.arn();
        if (stmt.getPrincipals() == null) {
            return false;
        }
        boolean directMatch = false;
        for (Map.Entry<String, List<String>> entry : stmt.getPrincipals().entrySet()) {
            String type = entry.getKey();
            List<String> patterns = entry.getValue();
            if (patterns == null || patterns.isEmpty()) {
                continue;
            }
            if ("AWS".equalsIgnoreCase(type) || "*".equals(type)) {
                for (String pattern : patterns) {
                    if (pattern.equals(principalArn)) {
                        directMatch = true;
                        break;
                    }
                    if ("*".equals(pattern) && conditionDirectlyMatchesPrincipalArn(stmt.getConditions(), principalArn)) {
                        directMatch = true;
                        break;
                    }
                }
            }
            if (directMatch) {
                break;
            }
        }
        if (!directMatch) {
            return false;
        }
        return matchesAction(stmt, action)
                && matchesResource(stmt, resource)
                && matchesConditions(stmt.getConditions(), ctx);
    }

    private boolean conditionDirectlyMatchesPrincipalArn(Map<String, Map<String, List<String>>> conditions, String principalArn) {
        if (conditions == null || conditions.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Map<String, List<String>>> opEntry : conditions.entrySet()) {
            String op = opEntry.getKey();
            if ("StringEquals".equalsIgnoreCase(op) || "ArnEquals".equalsIgnoreCase(op)) {
                Map<String, List<String>> fieldMap = opEntry.getValue();
                if (fieldMap != null) {
                    for (Map.Entry<String, List<String>> field : fieldMap.entrySet()) {
                        if ("aws:PrincipalArn".equalsIgnoreCase(field.getKey())) {
                            List<String> values = field.getValue();
                            if (values != null && values.contains(principalArn)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean matchesResourceStatement(PolicyStatement stmt, RequestPrincipal principal,
                                             String action, String resource, Map<String, List<String>> ctx) {
        // A resource-policy statement with no Principal matches nothing (per AWS specification)
        if (stmt.getPrincipals() == null && stmt.getNotPrincipals() == null) {
            return false;
        }
        if (!matchesPrincipal(stmt, principal)) {
            return false;
        }
        return matchesAction(stmt, action)
                && matchesResource(stmt, resource)
                && matchesConditions(stmt.getConditions(), ctx);
    }

    private boolean matchesPrincipal(PolicyStatement stmt, RequestPrincipal principal) {
        if (stmt.getPrincipals() != null) {
            return matchesAnyPrincipal(stmt.getPrincipals(), principal);
        }
        if (stmt.getNotPrincipals() != null) {
            return !matchesAnyPrincipal(stmt.getNotPrincipals(), principal);
        }
        return false;
    }

    /**
     * Matches a statement's {@code Principal} (or {@code NotPrincipal}) map against a typed caller.
     * {@code "*"} matches anyone. {@code {"AWS": "*"}} matches IAM identities and services alike,
     * which policies AWS writes itself rely on (an FIS Deny pausing DynamoDB replication names the
     * replication service that way). Any other {@code AWS} entry names IAM identities only, and a
     * role session matches the role it assumed through that role's own ARN, path included.
     * {@code Service} names one service exactly; {@code {"Service": "*"}} is not a form AWS accepts
     * and matches nothing. {@code CanonicalUser}, which S3 bucket policies accept, names an account
     * by its S3 canonical user ID, and matches the IAM identities of that account as an account
     * principal does; Floci's canonical ID for an account is the account id, as its S3 ACLs report
     * it. {@code Federated} and any other type name callers this evaluator never sees, and so match
     * nothing, rather than being compared with an IAM ARN.
     */
    private boolean matchesAnyPrincipal(Map<String, List<String>> principals, RequestPrincipal principal) {
        for (Map.Entry<String, List<String>> entry : principals.entrySet()) {
            String type = entry.getKey();
            List<String> patterns = entry.getValue();
            if (patterns == null || patterns.isEmpty()) {
                continue;
            }
            if ("*".equals(type)) {
                for (String pattern : patterns) {
                    if ("*".equals(pattern)) {
                        return true;
                    }
                    if (principal.type() == RequestPrincipal.Type.IAM && principal.arn() != null
                            && globMatches(pattern, principal.arn())) {
                        return true;
                    }
                }
            } else if ("AWS".equalsIgnoreCase(type)) {
                if (patterns.contains("*") && principal.type() != RequestPrincipal.Type.ANONYMOUS) {
                    return true;
                }
                if (principal.type() == RequestPrincipal.Type.IAM && principal.arn() != null
                        && matchesAwsPrincipal(patterns, principal)) {
                    return true;
                }
            } else if ("Service".equalsIgnoreCase(type)) {
                if (principal.type() == RequestPrincipal.Type.SERVICE && principal.servicePrincipal() != null) {
                    String wanted = ServicePrincipals.canonical(principal.servicePrincipal());
                    for (String pattern : patterns) {
                        if (!"*".equals(pattern) && ServicePrincipals.canonical(pattern).equals(wanted)) {
                            return true;
                        }
                    }
                }
            } else if ("CanonicalUser".equalsIgnoreCase(type)) {
                if (principal.type() == RequestPrincipal.Type.IAM && principal.arn() != null) {
                    String accountId = extractAccountId(principal.arn());
                    if (accountId != null && patterns.contains(accountId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean matchesAwsPrincipal(List<String> patterns, RequestPrincipal principal) {
        String principalArn = principal.arn();
        String accountId = extractAccountId(principalArn);
        String roleArn = principal.roleArn() != null
                ? principal.roleArn()
                : extractRoleArnFromAssumedRole(principalArn);
        for (String pattern : patterns) {
            if (pattern.matches("\\d{12}")) {
                if (pattern.equals(accountId)) {
                    return true;
                }
            } else if (ROOT_PRINCIPAL_ARN.matcher(pattern).matches()) {
                // An account id is scoped to its partition: arn:aws-cn:iam::123:root names
                // the China account 123, which is not the commercial account 123.
                AwsArnUtils.Arn root = AwsArnUtils.parse(pattern);
                if (root.accountId().equals(accountId)
                        && root.partition().equals(AwsArnUtils.parse(principalArn).partition())) {
                    return true;
                }
            } else if (globMatches(pattern, principalArn)) {
                return true;
            } else if (roleArn != null && globMatches(pattern, roleArn)) {
                return true;
            }
        }
        return false;
    }

    private static String extractAccountId(String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            return null;
        }
        String account = AwsArnUtils.parse(arn).accountId();
        return account.matches("\\d{12}") ? account : null;
    }

    /**
     * The role an assumed-role session ARN was minted from, in the session's own partition:
     * {@code arn:aws-cn:sts::1:assumed-role/r/s} names {@code arn:aws-cn:iam::1:role/r}.
     */
    private static String extractRoleArnFromAssumedRole(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "sts")) {
            return null;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (parsed.resource().startsWith("assumed-role/")) {
            String sessionPath = parsed.resource().substring("assumed-role/".length());
            int nextSlash = sessionPath.indexOf('/');
            String roleName = nextSlash > 0 ? sessionPath.substring(0, nextSlash) : sessionPath;
            return AwsArnUtils.Arn.global(parsed.partition(), "iam", parsed.accountId(), "role/" + roleName).toString();
        }
        return null;
    }

    private boolean matchesStatement(PolicyStatement stmt, String action, String resource,
                                      Map<String, List<String>> ctx) {
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

    /**
     * Case-sensitive glob over the given patterns. Action names are case-insensitive on AWS, so
     * both sides arrive lowercased: the patterns at parse time and the request action once per
     * evaluation. Resource ARNs are case-sensitive on AWS and are compared as written.
     */
    private boolean matchesAny(List<String> patterns, String value) {
        if (patterns == null || value == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (globMatchesHelper(pattern, value)) {
                return true;
            }
        }
        return false;
    }

    private static String lowercase(String value) {
        return value == null ? null : value.toLowerCase();
    }

    // -----------------------------------------------------------------------
    // Condition evaluation (Phase 4)
    // -----------------------------------------------------------------------

    /**
     * Evaluates one statement's {@code Condition} element against a request context, with the
     * same operator semantics as every other policy this class reads. For policies this class
     * does not match statements for itself: trust policies carry no {@code Resource} element, so
     * {@link AssumeRolePolicyEvaluator} matches their principal and action and asks here for the
     * condition.
     */
    boolean conditionMatches(JsonNode condition, Map<String, List<String>> conditionCtx) {
        return matchesConditions(parseConditions(condition), normalizeConditionContext(conditionCtx));
    }

    /**
     * Evaluates all condition blocks. AND between blocks, OR within each block's value list.
     * Returns true if ALL blocks pass (or there are no conditions).
     */
    private boolean matchesConditions(Map<String, Map<String, List<String>>> conditions,
                                       Map<String, List<String>> ctx) {
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

    /**
     * AWS set-operator quantifier. {@code ForAllValues:} requires every value the request
     * carries for the key to match the policy; {@code ForAnyValue:} requires at least one.
     */
    private enum SetQuantifier { NONE, FOR_ALL_VALUES, FOR_ANY_VALUE }

    private record ParsedOperator(SetQuantifier quantifier, String baseOp, boolean ifExists) {}

    /**
     * Splits a condition operator into its set quantifier, base operator and IfExists flag.
     * The prefix match is case-sensitive on exactly AWS's own spelling, so a mis-cased
     * "forallvalues:" stays an unknown operator instead of silently behaving like the
     * real quantifier. The IfExists strip runs on what is left, so
     * "ForAnyValue:StringEqualsIfExists" composes correctly.
     */
    private static ParsedOperator parseOperator(String operator) {
        SetQuantifier quantifier = SetQuantifier.NONE;
        String rest = operator;
        if (rest.startsWith("ForAllValues:")) {
            quantifier = SetQuantifier.FOR_ALL_VALUES;
            rest = rest.substring("ForAllValues:".length());
        } else if (rest.startsWith("ForAnyValue:")) {
            quantifier = SetQuantifier.FOR_ANY_VALUE;
            rest = rest.substring("ForAnyValue:".length());
        }
        boolean ifExists = rest.endsWith("IfExists");
        String baseOp = ifExists ? rest.substring(0, rest.length() - "IfExists".length()) : rest;
        return new ParsedOperator(quantifier, baseOp, ifExists);
    }

    /**
     * Combines the policy's condition values for a single request value. Positive operators
     * OR — the request value may match any listed value. Negated operators AND — the request
     * value must differ from every listed value, which is AWS's documented rule for a
     * multi-valued negated condition and the deny-list idiom for keys such as
     * {@code dynamodb:Attributes} ({@code ForAllValues:StringNotEquals}).
     */
    private boolean matchesCondValues(String baseOp, String ctxValue, List<String> condValues) {
        if (isNegatedOperator(baseOp)) {
            for (String condValue : condValues) {
                if (!evaluateSingleCondition(baseOp, ctxValue, condValue)) {
                    return false;
                }
            }
            return true;
        }
        for (String condValue : condValues) {
            if (evaluateSingleCondition(baseOp, ctxValue, condValue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNegatedOperator(String baseOp) {
        return switch (baseOp) {
            case "StringNotEquals", "StringNotEqualsIgnoreCase", "StringNotLike",
                 "ArnNotEquals", "ArnNotLike", "NumericNotEquals", "DateNotEquals",
                 "NotIpAddress" -> true;
            default -> false;
        };
    }

    private boolean evaluateConditionBlock(String operator,
                                            Map<String, List<String>> keyValueMap,
                                            Map<String, List<String>> ctx) {
        ParsedOperator parsed = parseOperator(operator);
        String baseOp = parsed.baseOp();
        boolean ifExists = parsed.ifExists();

        for (Map.Entry<String, List<String>> entry : keyValueMap.entrySet()) {
            String condKey = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            List<String> condValues = entry.getValue();
            List<String> ctxValues = ctx.get(condKey);

            if (ctxValues == null) {
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
                // A negated operator asks for the key not to match, and an absent key matches
                // nothing, so the condition holds (IAM User Guide, condition operators). The set
                // operators keep their own rule for an absent key.
                if (parsed.quantifier() == SetQuantifier.NONE && isNegatedOperator(baseOp)) {
                    continue;
                }
                return false; // key missing, no IfExists → fail entire block
            }

            if ("Null".equalsIgnoreCase(baseOp)) {
                // Key is present. An empty set counts as nonexistent for Null, matching AWS,
                // so the Null:{key:"false"} guard that policies pair with ForAllValues still
                // blocks a vacuous match. Null:{key:"true"} passes only when effectively absent.
                boolean expectAbsent = condValues.stream().anyMatch("true"::equalsIgnoreCase);
                boolean effectivelyAbsent = ctxValues.isEmpty();
                if (expectAbsent != effectivelyAbsent) {
                    return false;
                }
                continue;
            }

            boolean keyMatch = switch (parsed.quantifier()) {
                // Every request value must match at least one policy value. An empty set
                // matches vacuously, which is why real policies pair ForAllValues with a
                // Null:{key:"false"} guard.
                case FOR_ALL_VALUES -> ctxValues.stream()
                        .allMatch(ctxValue -> matchesCondValues(baseOp, ctxValue, condValues));
                // At least one request value must match. An empty set never matches.
                case FOR_ANY_VALUE -> ctxValues.stream()
                        .anyMatch(ctxValue -> matchesCondValues(baseOp, ctxValue, condValues));
                // A bare operator against a multi-valued key is a policy authoring error in
                // AWS; mirror that by comparing only the first value.
                case NONE -> !ctxValues.isEmpty()
                        && matchesCondValues(baseOp, ctxValues.getFirst(), condValues);
            };
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
            case "StringLike"                -> caseSensitiveGlobMatches(condValue, ctxValue);
            case "StringNotLike"             -> !caseSensitiveGlobMatches(condValue, ctxValue);
            case "ArnEquals", "ArnLike"      -> matchesArnCondition(condValue, ctxValue);
            case "ArnNotEquals", "ArnNotLike"-> !matchesArnCondition(condValue, ctxValue);
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
            return CidrCanonicalizer.contains(condValue, ctxValue);
        }
        return condValue.equals(ctxValue);
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
        return globMatchesHelper(pattern.toLowerCase(), value.toLowerCase());
    }

    public static boolean caseSensitiveGlobMatches(String pattern, String value) {
        return pattern != null && value != null && globMatchesHelper(pattern, value);
    }

    public static boolean matchesArnCondition(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        // The resource component may itself contain colons (for example Lambda qualifiers).
        String[] patternComponents = pattern.split(":", 6);
        String[] valueComponents = value.split(":", 6);
        if (patternComponents.length != 6 || valueComponents.length != 6) {
            return false;
        }
        for (int i = 0; i < patternComponents.length; i++) {
            if (!caseSensitiveGlobMatches(patternComponents[i], valueComponents[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean globMatchesHelper(String pattern, String value) {
        int patternIndex = 0;
        int valueIndex = 0;
        int starIndex = -1;
        int starValueIndex = -1;

        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                starIndex = patternIndex++;
                starValueIndex = valueIndex;
            } else if (patternIndex < pattern.length()
                    && (pattern.charAt(patternIndex) == '?' || pattern.charAt(patternIndex) == value.charAt(valueIndex))) {
                patternIndex++;
                valueIndex++;
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

    // -----------------------------------------------------------------------
    // Policy document parsing
    // -----------------------------------------------------------------------

    private List<PolicyStatement> parseAll(List<String> documents) {
        return parseAllTracked(documents).statements();
    }

    /**
     * Parses every document, skipping (and logging) any that fails, and reports whether
     * any did. Callers that can safely ignore a broken document use {@link #parseAll};
     * SCP evaluation reads {@code anyFailed} so the ceiling can fail closed.
     */
    private ParsedDocuments parseAllTracked(List<String> documents) {
        List<PolicyStatement> result = new ArrayList<>();
        if (documents == null) {
            return new ParsedDocuments(result, false);
        }
        boolean anyFailed = false;
        for (String doc : documents) {
            CachedDocument parsed = parseDocument(doc);
            result.addAll(parsed.statements());
            anyFailed |= parsed.failed();
        }
        return new ParsedDocuments(result, anyFailed);
    }

    private record ParsedDocuments(List<PolicyStatement> statements, boolean anyFailed) {
    }

    private CachedDocument parseDocument(String document) {
        if (document == null) {
            return parseUncached(null);
        }
        CachedDocument cached = cachedDocuments.get(document);
        if (cached != null) {
            return cached;
        }
        CachedDocument parsed = parseUncached(document);
        if (cachedDocuments.size() >= MAX_CACHED_DOCUMENTS) {
            cachedDocuments.clear();
        }
        cachedDocuments.put(document, parsed);
        return parsed;
    }

    private CachedDocument parseUncached(String document) {
        try {
            return new CachedDocument(List.copyOf(parseStatements(document)), false);
        } catch (Exception e) {
            LOG.warnv("Failed to parse policy document: {0}", e.getMessage());
            return new CachedDocument(List.of(), true);
        }
    }

    private record CachedDocument(List<PolicyStatement> statements, boolean failed) {
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
        Map<String, List<String>> principals = parsePrincipals(stmt.get("Principal"));
        Map<String, List<String>> notPrincipals = parsePrincipals(stmt.get("NotPrincipal"));
        // Action names are case-insensitive on AWS, so they are lowercased once here instead of
        // on every request. Resource ARNs are case-sensitive on AWS and are kept as written.
        List<String> actions     = lowercaseAll(nodeToList(stmt.get("Action")));
        List<String> notActions  = lowercaseAll(nodeToList(stmt.get("NotAction")));
        List<String> resources   = nodeToList(stmt.get("Resource"));
        List<String> notResources= nodeToList(stmt.get("NotResource"));
        Map<String, Map<String, List<String>>> conditions = parseConditions(stmt.get("Condition"));
        return new PolicyStatement(
                effect,
                principals,
                notPrincipals,
                actions.isEmpty()     ? null : actions,
                notActions.isEmpty()  ? null : notActions,
                resources.isEmpty()   ? null : resources,
                notResources.isEmpty()? null : notResources,
                conditions);
    }

    private Map<String, List<String>> parsePrincipals(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (node.isTextual()) {
            result.put("*", List.of(node.asText()));
            return result;
        }
        if (node.isArray()) {
            result.put("*", nodeToList(node));
            return result;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                List<String> values = nodeToList(entry.getValue());
                if (!values.isEmpty()) {
                    result.put(entry.getKey(), values);
                }
            });
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    private Map<String, Map<String, List<String>>> parseConditions(JsonNode condNode) {
        if (condNode == null || condNode.isNull() || !condNode.isObject()) {
            return null;
        }
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        condNode.fields().forEachRemaining(opEntry -> {
            Map<String, List<String>> kvMap = new LinkedHashMap<>();
            boolean boolOperator = "Bool".equals(parseOperator(opEntry.getKey()).baseOp());
            opEntry.getValue().fields().forEachRemaining(kvEntry -> {
                JsonNode value = kvEntry.getValue();
                kvMap.put(kvEntry.getKey(), boolOperator && value.isBoolean()
                        ? List.of(value.asText()) : nodeToList(value));
            });
            result.put(opEntry.getKey(), kvMap);
        });
        return result.isEmpty() ? null : result;
    }

    private static List<String> lowercaseAll(List<String> values) {
        List<String> lowered = new ArrayList<>(values.size());
        for (String value : values) {
            lowered.add(value.toLowerCase());
        }
        return lowered;
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
