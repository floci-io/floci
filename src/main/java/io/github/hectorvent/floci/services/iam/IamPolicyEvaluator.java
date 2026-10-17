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

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
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

    /**
     * The verb of the stand-in action a wildcard is probed with in
     * {@link #servicesAllowedByScpLevels}. Any string no real action spells out will do, since it
     * stands for "some other action this pattern covers". Lowercase, because action patterns are
     * lowercased at parse time and matched case-sensitively from there on.
     */
    private static final String ANY_ACTION = "flociprobedaction";

    public enum Decision { ALLOW, DENY }

    public enum ResourcePolicyDecision {
        ALLOW,
        ALLOW_DIRECT_IAM_USER,
        /**
         * An Allow that names the caller only through its account: an account ID, the account's
         * root ARN or, in an S3 bucket policy, its canonical user ID. That "delegates authority to
         * the account" (IAM User Guide, "AWS account principals"), so across accounts it is the
         * owner's half of the grant, while within the account it grants nothing on its own and the
         * caller's identity-based policies have to allow the request.
         */
        ALLOW_ACCOUNT,
        EXPLICIT_DENY,
        NEUTRAL
    }

    /** How a resource policy statement's principal names a caller: itself, only its account, or not at all. */
    private enum PrincipalMatch { NONE, ACCOUNT, CALLER }

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

    private static final Pattern EPOCH_SECONDS = Pattern.compile("-?\\d+");
    private static final Pattern YEAR_MONTH = Pattern.compile("\\d{4}-\\d{2}");

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
     * deny, although every explicit deny still wins. A same-account Allow that names only the
     * caller's account ({@link ResourcePolicyDecision#ALLOW_ACCOUNT}) grants nothing on its own.
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
        ResourceAccountRelationship relationship = accountRelationship == null
                ? ResourceAccountRelationship.CROSS_ACCOUNT : accountRelationship;
        return evaluateParsed(
                caller, identityStmts, sessionStmts, boundaryStmts,
                resolvedDecision == ResourcePolicyDecision.EXPLICIT_DENY,
                resolvedDecision == ResourcePolicyDecision.ALLOW
                        || resolvedDecision == ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER
                        || resolvedDecision == ResourcePolicyDecision.ALLOW_ACCOUNT
                                && relationship == ResourceAccountRelationship.CROSS_ACCOUNT,
                resolvedDecision == ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER,
                relationship, lowercase(action), resource, ctx);
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
        return switch (strongestResourceAllow(stmts, principal, loweredAction, resource, ctx)) {
            case CALLER -> ResourcePolicyDecision.ALLOW;
            case ACCOUNT -> ResourcePolicyDecision.ALLOW_ACCOUNT;
            case NONE -> ResourcePolicyDecision.NEUTRAL;
        };
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

    /**
     * The service namespaces an SCP chain leaves reachable, backing the service list in
     * {@code GetOrganizationsAccessReport}.
     *
     * <p>Level conventions are {@link #scpAllows}'s. A {@code null} chain means SCPs do not apply
     * and every published namespace is reachable, which is the management account's case. A level
     * with no document constrains nothing, since AWS keeps FullAWSAccess attached to every target,
     * and a level carrying a document that fails to parse leaves nothing reachable, because the
     * ceiling cannot tell what an unreadable guardrail would have said.
     *
     * <p>Attached SCPs are a union within a level and the levels are intersected. The question is
     * service-level, but it cannot be answered a namespace at a time: a service is reachable only
     * while some action in it survives the whole chain. Two levels allowing single disjoint
     * actions, {@code s3:GetObject} under {@code s3:PutObject}, leave no S3 action at all, and a
     * {@code Deny} on the one action a level allows leaves none either, so neither reports S3.
     * What a namespace-wide reading got right stays right: an SCP denying {@code s3:DeleteBucket}
     * alone keeps S3 on the list, because every other S3 action is still reachable.
     *
     * <p>This is not {@link #scpAllows} asked once per namespace. That question needs a concrete
     * action, and a request action of {@code s3:*} would not match a statement naming
     * {@code s3:GetObject}, because a pattern globs against a request value and never the other
     * way round. The survivors are found by probing instead, with the actions the policies
     * themselves name and one stand-in action per wildcard.
     */
    public List<String> servicesAllowedByScpLevels(List<List<String>> scpLevels) {
        if (scpLevels == null) {
            return List.copyOf(new TreeSet<>(AwsServiceNamespaces.all()));
        }
        Set<String> reachable = null;
        List<List<PolicyStatement>> allowLevels = new ArrayList<>();
        List<PolicyStatement> denials = new ArrayList<>();
        for (List<String> level : scpLevels) {
            ParsedDocuments parsed = parseAllTracked(level);
            if (parsed.anyFailed()) {
                return List.of();
            }
            if (parsed.statements().isEmpty()) {
                continue;
            }
            Set<String> levelAllows = new TreeSet<>(servicesGrantedBy(level));
            if (reachable == null) {
                reachable = levelAllows;
            } else {
                reachable.retainAll(levelAllows);
            }
            List<PolicyStatement> allows = new ArrayList<>();
            for (PolicyStatement stmt : parsed.statements()) {
                if (stmt.isDeny()) {
                    denials.add(stmt);
                } else {
                    allows.add(stmt);
                }
            }
            allowLevels.add(allows);
        }
        if (reachable == null) {
            return List.copyOf(new TreeSet<>(AwsServiceNamespaces.all()));
        }
        // The namespace pass above is the cheap half of the answer and only ever too generous, so
        // what survives it is then asked the question that needs an action.
        reachable.removeIf(namespace -> !anyActionSurvives(allowLevels, denials, namespace));
        return List.copyOf(reachable);
    }

    /**
     * Whether any action in the namespace is allowed by every level and denied by none.
     *
     * <p>The actions worth asking about are the ones the policies name, plus a stand-in for each
     * wildcard: {@code s3:Get*} is probed as an action starting with {@code Get} that nothing else
     * spells out, which is what makes a deny on {@code s3:GetObject} leave the service reachable
     * while a deny on {@code s3:Get*} does not. A pattern whose wildcard is not its last character
     * cannot be probed this way, since the stand-in for {@code s3:*Object} misses the
     * {@code s3:GetObject} that {@code s3:Get*} would allow. Such a pattern leaves the namespace
     * on the list rather than off it, keeping the imprecision on the side the namespace pass was
     * already on.
     */
    private boolean anyActionSurvives(List<List<PolicyStatement>> allowLevels,
                                      List<PolicyStatement> denials, String serviceNamespace) {
        Set<String> probes = new LinkedHashSet<>();
        probes.add(serviceNamespace + ":" + ANY_ACTION);
        boolean decidable = true;
        for (List<PolicyStatement> level : allowLevels) {
            for (PolicyStatement stmt : level) {
                decidable = collectProbes(stmt.getActions(), serviceNamespace, probes) && decidable;
                decidable = collectProbes(stmt.getNotActions(), serviceNamespace, probes) && decidable;
            }
        }
        for (PolicyStatement stmt : denials) {
            decidable = collectProbes(stmt.getActions(), serviceNamespace, probes) && decidable;
            decidable = collectProbes(stmt.getNotActions(), serviceNamespace, probes) && decidable;
        }
        for (String action : probes) {
            if (allowedByEveryLevel(allowLevels, action) && !deniedByAny(denials, action)) {
                return true;
            }
        }
        return !decidable;
    }

    /**
     * Adds one probe action per pattern that reaches the namespace, and reports whether every
     * pattern seen was one a probe decides exactly.
     */
    private static boolean collectProbes(List<String> patterns, String serviceNamespace,
                                         Set<String> probes) {
        if (patterns == null) {
            return true;
        }
        boolean decidable = true;
        for (String pattern : patterns) {
            if (!actionPatternReaches(pattern, serviceNamespace)) {
                continue;
            }
            int colon = pattern.indexOf(':');
            String verb = colon < 0 ? "*" : pattern.substring(colon + 1);
            int star = verb.indexOf('*');
            if (verb.indexOf('?') >= 0 || (star >= 0 && star != verb.length() - 1)) {
                decidable = false;
            }
            probes.add(serviceNamespace + ":" + verb.replace("*", ANY_ACTION).replace("?", "a"));
        }
        return decidable;
    }

    /** Whether one action clears the ceiling at every level, each level being a union. */
    private boolean allowedByEveryLevel(List<List<PolicyStatement>> allowLevels, String action) {
        for (List<PolicyStatement> level : allowLevels) {
            boolean allowed = false;
            for (PolicyStatement stmt : level) {
                if (permits(stmt, action)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an {@code Allow} covers the action. Resources and conditions are left out of the
     * allow side, as everywhere else in this answer: narrowing a grant to one bucket still leaves
     * the service reachable.
     */
    private boolean permits(PolicyStatement stmt, String action) {
        if (stmt.getNotActions() != null) {
            return !matchesAny(stmt.getNotActions(), action);
        }
        return stmt.getActions() != null && matchesAny(stmt.getActions(), action);
    }

    /**
     * Whether a {@code Deny} that bites on every request covers the action. A deny carrying a
     * {@code Condition}, or scoped to particular resources, is not one of those: the
     * region-restricting {@code Deny} on {@code *} that most organizations carry would otherwise
     * empty the report, and a deny on one bucket would drop S3 while every other bucket is still
     * there.
     */
    private boolean deniedByAny(List<PolicyStatement> denials, String action) {
        for (PolicyStatement stmt : denials) {
            if (!deniesEveryRequest(stmt)) {
                continue;
            }
            if (stmt.getNotActions() != null) {
                if (!matchesAny(stmt.getNotActions(), action)) {
                    return true;
                }
            } else if (stmt.getActions() != null && matchesAny(stmt.getActions(), action)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a {@code Deny} applies to every request, rather than to some of them. */
    private static boolean deniesEveryRequest(PolicyStatement stmt) {
        if (stmt.getConditions() != null && !stmt.getConditions().isEmpty()) {
            return false;
        }
        if (stmt.getNotResources() != null && !stmt.getNotResources().isEmpty()) {
            return false;
        }
        List<String> resources = stmt.getResources();
        return resources == null || resources.isEmpty() || resources.contains("*");
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
            if (stmt.isDeny() && resourceStatementMatch(stmt, principal, action, resource, ctx) != PrincipalMatch.NONE) {
                return true;
            }
        }
        return false;
    }

    /** How closely the closest matching Allow names the caller. */
    private PrincipalMatch strongestResourceAllow(List<PolicyStatement> stmts, RequestPrincipal principal,
                                                  String action, String resource, Map<String, List<String>> ctx) {
        PrincipalMatch strongest = PrincipalMatch.NONE;
        for (PolicyStatement stmt : stmts) {
            if (!stmt.isAllow()) {
                continue;
            }
            PrincipalMatch match = resourceStatementMatch(stmt, principal, action, resource, ctx);
            if (match == PrincipalMatch.CALLER) {
                return match;
            }
            if (match == PrincipalMatch.ACCOUNT) {
                strongest = match;
            }
        }
        return strongest;
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
                && matchesResource(stmt, action, resource, ctx)
                && matchesConditions(stmt.getConditions(), ctx, policyVariables(stmt));
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

    private PrincipalMatch resourceStatementMatch(PolicyStatement stmt, RequestPrincipal principal,
                                                  String action, String resource, Map<String, List<String>> ctx) {
        // A resource-policy statement with no Principal matches nothing (per AWS specification)
        if (stmt.getPrincipals() == null && stmt.getNotPrincipals() == null) {
            return PrincipalMatch.NONE;
        }
        PrincipalMatch match = principalMatch(stmt, principal);
        if (match == PrincipalMatch.NONE
                || !matchesAction(stmt, action)
                || !matchesResource(stmt, action, resource, ctx)
                || !matchesConditions(stmt.getConditions(), ctx, policyVariables(stmt))) {
            return PrincipalMatch.NONE;
        }
        return match;
    }

    /** A {@code NotPrincipal} statement applies to every caller it does not spare. */
    private PrincipalMatch principalMatch(PolicyStatement stmt, RequestPrincipal principal) {
        if (stmt.getPrincipals() != null) {
            return matchAnyPrincipal(stmt.getPrincipals(), principal);
        }
        if (stmt.getNotPrincipals() != null) {
            boolean spared = stmt.isDeny()
                    ? sparedFromDeny(stmt.getNotPrincipals(), principal)
                    : matchAnyPrincipal(stmt.getNotPrincipals(), principal) != PrincipalMatch.NONE;
            return spared ? PrincipalMatch.NONE : PrincipalMatch.CALLER;
        }
        return PrincipalMatch.NONE;
    }

    /**
     * Whether a Deny's {@code NotPrincipal} spares the caller. The IAM User Guide ("AWS JSON policy
     * elements: NotPrincipal") says it "will always deny any IAM principal that has a permissions
     * boundary policy attached, regardless of the values specified", and that AWS may check the
     * caller's account before the caller, so a list naming a user or role without its account "might
     * deny access to the entire account". Floci takes that reading: an IAM caller is spared only when
     * the list names its account and the caller itself, user, role or role session, or only its
     * account when the caller is the account root. Anyone else is spared when the list names it.
     */
    private boolean sparedFromDeny(Map<String, List<String>> notPrincipals, RequestPrincipal principal) {
        if (principal.type() != RequestPrincipal.Type.IAM || principal.arn() == null) {
            return matchAnyPrincipal(notPrincipals, principal) != PrincipalMatch.NONE;
        }
        if (principal.permissionsBoundary()) {
            return false;
        }
        boolean accountNamed = false;
        boolean callerNamed = false;
        for (Map.Entry<String, List<String>> entry : notPrincipals.entrySet()) {
            List<String> patterns = entry.getValue() == null ? List.of() : entry.getValue();
            for (String pattern : patterns) {
                PrincipalMatch match = matchAnyPrincipal(Map.of(entry.getKey(), List.of(pattern)), principal);
                if (match == PrincipalMatch.CALLER && "*".equals(pattern)) {
                    return true;
                }
                accountNamed |= match == PrincipalMatch.ACCOUNT;
                callerNamed |= match == PrincipalMatch.CALLER;
            }
        }
        return accountNamed && (callerNamed || ROOT_PRINCIPAL_ARN.matcher(principal.arn()).matches());
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
     * nothing, rather than being compared with an IAM ARN. An entry naming the caller itself wins
     * over one naming only its account.
     */
    private PrincipalMatch matchAnyPrincipal(Map<String, List<String>> principals, RequestPrincipal principal) {
        PrincipalMatch strongest = PrincipalMatch.NONE;
        for (Map.Entry<String, List<String>> entry : principals.entrySet()) {
            String type = entry.getKey();
            List<String> patterns = entry.getValue();
            if (patterns == null || patterns.isEmpty()) {
                continue;
            }
            if ("*".equals(type)) {
                for (String pattern : patterns) {
                    if ("*".equals(pattern)) {
                        return PrincipalMatch.CALLER;
                    }
                    if (principal.type() == RequestPrincipal.Type.IAM && principal.arn() != null
                            && globMatches(pattern, principal.arn())) {
                        return PrincipalMatch.CALLER;
                    }
                }
            } else if ("AWS".equalsIgnoreCase(type)) {
                if (patterns.contains("*") && principal.type() != RequestPrincipal.Type.ANONYMOUS) {
                    return PrincipalMatch.CALLER;
                }
                if (principal.type() == RequestPrincipal.Type.IAM && principal.arn() != null) {
                    PrincipalMatch match = matchAwsPrincipal(patterns, principal);
                    if (match == PrincipalMatch.CALLER) {
                        return match;
                    }
                    if (match == PrincipalMatch.ACCOUNT) {
                        strongest = match;
                    }
                }
            } else if ("Service".equalsIgnoreCase(type)) {
                if (principal.type() == RequestPrincipal.Type.SERVICE && principal.servicePrincipal() != null) {
                    String wanted = ServicePrincipals.canonical(principal.servicePrincipal());
                    for (String pattern : patterns) {
                        if (!"*".equals(pattern) && ServicePrincipals.canonical(pattern).equals(wanted)) {
                            return PrincipalMatch.CALLER;
                        }
                    }
                }
            } else if ("CanonicalUser".equalsIgnoreCase(type)) {
                if (principal.type() == RequestPrincipal.Type.IAM && principal.arn() != null) {
                    String accountId = extractAccountId(principal.arn());
                    if (accountId != null && patterns.contains(accountId)) {
                        strongest = PrincipalMatch.ACCOUNT;
                    }
                }
            }
        }
        return strongest;
    }

    private PrincipalMatch matchAwsPrincipal(List<String> patterns, RequestPrincipal principal) {
        String principalArn = principal.arn();
        String accountId = extractAccountId(principalArn);
        String roleArn = principal.roleArn() != null
                ? principal.roleArn()
                : extractRoleArnFromAssumedRole(principalArn);
        PrincipalMatch strongest = PrincipalMatch.NONE;
        for (String pattern : patterns) {
            if (pattern.matches("\\d{12}")) {
                if (pattern.equals(accountId)) {
                    strongest = PrincipalMatch.ACCOUNT;
                }
            } else if (ROOT_PRINCIPAL_ARN.matcher(pattern).matches()) {
                // An account id is scoped to its partition: arn:aws-cn:iam::123:root names
                // the China account 123, which is not the commercial account 123.
                AwsArnUtils.Arn root = AwsArnUtils.parse(pattern);
                if (root.accountId().equals(accountId)
                        && root.partition().equals(AwsArnUtils.parse(principalArn).partition())) {
                    strongest = PrincipalMatch.ACCOUNT;
                }
            } else if (globMatches(pattern, principalArn)) {
                return PrincipalMatch.CALLER;
            } else if (roleArn != null && globMatches(pattern, roleArn)) {
                return PrincipalMatch.CALLER;
            }
        }
        return strongest;
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
                && matchesResource(stmt, action, resource, ctx)
                && matchesConditions(stmt.getConditions(), ctx, policyVariables(stmt));
    }

    /** Whether this statement's document declares the version that gives {@code ${...}} meaning. */
    private static boolean policyVariables(PolicyStatement stmt) {
        return PolicyVariableResolver.enabledFor(stmt.getVersion());
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
    private boolean matchesResource(PolicyStatement stmt, String action, String resource,
                                     Map<String, List<String>> ctx) {
        List<String> patterns = stmt.getResources() != null ? stmt.getResources() : stmt.getNotResources();
        if (patterns == null) {
            return false;
        }
        String alias = logGroupResourceAlias(action, resource);
        boolean matched = matchesAnyResource(patterns, resource, ctx, policyVariables(stmt))
                || alias != null && matchesAnyResource(patterns, alias, ctx, policyVariables(stmt));
        return stmt.getResources() != null ? matched : !matched;
    }

    /**
     * Group-management policies accept both documented bare group ARNs and legacy :* ARNs.
     * Match them inside each statement so explicit denies, NotResource and caller ceilings
     * retain their normal precedence. Stream actions and the three ARN tag APIs stay distinct.
     */
    private static String logGroupResourceAlias(String action, String resource) {
        if (action == null || !switch (action) {
            case "logs:createloggroup", "logs:deleteloggroup", "logs:putretentionpolicy",
                    "logs:deleteretentionpolicy", "logs:tagloggroup" -> true;
            default -> false;
        } || !AwsArnUtils.isArnFor(resource, "logs")) {
            return null;
        }
        String target = AwsArnUtils.parse(resource).resource();
        if (!target.startsWith("log-group:")) {
            return null;
        }
        String name = target.substring("log-group:".length());
        boolean suffixed = name.endsWith(":*");
        if (suffixed) {
            name = name.substring(0, name.length() - 2);
        }
        if (name.isEmpty() || name.indexOf(':') >= 0 || name.indexOf('*') >= 0 || name.indexOf('?') >= 0) {
            return null;
        }
        return suffixed ? resource.substring(0, resource.length() - 2) : resource + ":*";
    }

    /**
     * Glob over the Resource or NotResource patterns, with each pattern's policy variables
     * resolved first. A pattern holding a variable with no value matches nothing, which the user
     * guide states for both elements: "the resource that includes a policy variable with no value
     * will not match any resource". In NotResource that reads the other way round, since a
     * pattern matching no resource excludes none, so the statement covers every resource.
     */
    private boolean matchesAnyResource(List<String> patterns, String resource,
                                        Map<String, List<String>> ctx, boolean policyVariables) {
        if (!policyVariables) {
            return matchesAny(patterns, resource);
        }
        if (resource == null) {
            return false;
        }
        for (String pattern : patterns) {
            String resolved = PolicyVariableResolver.resolveResourcePattern(pattern, ctx).orElse(null);
            if (resolved != null && globMatchesHelper(resolved, resource, true)) {
                return true;
            }
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
     * condition. {@code policyVersion} is that document's own {@code Version} element, since a
     * {@code ${...}} is only a policy variable under the version that introduced them.
     */
    boolean conditionMatches(JsonNode condition, Map<String, List<String>> conditionCtx,
                             String policyVersion) {
        return matchesConditions(parseConditions(condition), normalizeConditionContext(conditionCtx),
                PolicyVariableResolver.enabledFor(policyVersion));
    }

    /**
     * Evaluates all condition blocks. AND between blocks, OR within each block's value list.
     * Returns true if ALL blocks pass (or there are no conditions).
     */
    private boolean matchesConditions(Map<String, Map<String, List<String>>> conditions,
                                       Map<String, List<String>> ctx, boolean policyVariables) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Map<String, List<String>>> entry : conditions.entrySet()) {
            if (!evaluateConditionBlock(entry.getKey(), entry.getValue(), ctx, policyVariables)) {
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

    private static final Set<String> SUPPORTED_CONDITION_OPERATORS = Set.of(
            "StringEquals", "StringNotEquals", "StringEqualsIgnoreCase", "StringNotEqualsIgnoreCase",
            "StringLike", "StringNotLike", "ArnEquals", "ArnLike", "ArnNotEquals", "ArnNotLike", "Bool",
            "NumericEquals", "NumericNotEquals", "NumericLessThan", "NumericLessThanEquals",
            "NumericGreaterThan", "NumericGreaterThanEquals", "DateEquals", "DateNotEquals",
            "DateLessThan", "DateLessThanEquals", "DateGreaterThan", "DateGreaterThanEquals",
            "IpAddress", "NotIpAddress");

    /**
     * The operators a policy variable may be used with: "Conditions support variables when you
     * use string operators or ARN operators", and not Numeric, Date, Boolean, Binary, IP Address
     * or Null. With one of those, a {@code ${...}} is the text it is written as.
     */
    private static final Set<String> VARIABLE_CONDITION_OPERATORS = Set.of(
            "StringEquals", "StringNotEquals", "StringEqualsIgnoreCase", "StringNotEqualsIgnoreCase",
            "StringLike", "StringNotLike", "ArnEquals", "ArnNotEquals", "ArnLike", "ArnNotLike");

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
     * Returns whether {@link #evaluate} understands a condition operator, including its
     * {@code ForAllValues:}/{@code ForAnyValue:} prefix and {@code IfExists} suffix. An unknown
     * operator never matches, which silently disables a Deny, so callers that must fail closed
     * on a malformed policy check this first.
     */
    public static boolean isSupportedConditionOperator(String operator) {
        ParsedOperator parsed = parseOperator(operator);
        if ("Null".equals(parsed.baseOp())) {
            return !parsed.ifExists() && parsed.quantifier() == SetQuantifier.NONE;
        }
        return SUPPORTED_CONDITION_OPERATORS.contains(parsed.baseOp());
    }

    /**
     * Combines the policy's condition values for a single request value. Positive operators
     * OR — the request value may match any listed value. Negated operators AND — the request
     * value must differ from every listed value, which is AWS's documented rule for a
     * multi-valued negated condition and the deny-list idiom for keys such as
     * {@code dynamodb:Attributes} ({@code ForAllValues:StringNotEquals}).
     */
    private boolean matchesCondValues(String baseOp, String ctxValue, List<String> condValues,
                                       Map<String, List<String>> ctx, boolean policyVariables) {
        if (isNegatedOperator(baseOp)) {
            for (String condValue : condValues) {
                if (!matchesCondValue(baseOp, ctxValue, condValue, ctx, policyVariables)) {
                    return false;
                }
            }
            return true;
        }
        for (String condValue : condValues) {
            if (matchesCondValue(baseOp, ctxValue, condValue, ctx, policyVariables)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One policy value against one request value, with the policy value's variables resolved
     * first. A value holding a variable with nothing to resolve to is effectively null: there is
     * "no equal or like value", so a positive operator does not match, while an inverted one does,
     * the request value being neither equal to nor like a null.
     */
    private boolean matchesCondValue(String baseOp, String ctxValue, String condValue,
                                      Map<String, List<String>> ctx, boolean policyVariables) {
        String resolved = condValue;
        if (policyVariables) {
            resolved = PolicyVariableResolver.resolveValue(condValue, ctx).orElse(null);
            if (resolved == null) {
                return isNegatedOperator(baseOp);
            }
        }
        return evaluateSingleCondition(baseOp, ctxValue, resolved, policyVariables);
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
                                            Map<String, List<String>> ctx,
                                            boolean policyVariables) {
        if (!isSupportedConditionOperator(operator)) {
            return false;
        }
        ParsedOperator parsed = parseOperator(operator);
        String baseOp = parsed.baseOp();
        boolean ifExists = parsed.ifExists();
        boolean substitute = policyVariables && VARIABLE_CONDITION_OPERATORS.contains(baseOp);

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
                if (parsed.quantifier() == SetQuantifier.FOR_ALL_VALUES) {
                    continue;
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
                        .allMatch(ctxValue -> matchesCondValues(baseOp, ctxValue, condValues, ctx, substitute));
                // At least one request value must match. An empty set never matches.
                case FOR_ANY_VALUE -> ctxValues.stream()
                        .anyMatch(ctxValue -> matchesCondValues(baseOp, ctxValue, condValues, ctx, substitute));
                // A bare operator against a multi-valued key is a policy authoring error in
                // AWS; mirror that by comparing only the first value.
                case NONE -> !ctxValues.isEmpty()
                        && matchesCondValues(baseOp, ctxValues.getFirst(), condValues, ctx, substitute);
            };
            if (!keyMatch) {
                return false;
            }
        }
        return true;
    }


    /**
     * One comparison. {@code escapes} says the policy value came back from substitution, which is
     * the only way a {@link PolicyVariableResolver#ESCAPE} marker can be in it: the glob operators
     * then read the marker themselves, while the operators comparing whole strings collapse it
     * first. Without substitution no marker is honoured, so one written into a document is the
     * character it is and cannot narrow the comparison.
     */
    private boolean evaluateSingleCondition(String operator, String ctxValue, String condValue,
                                             boolean escapes) {
        String plain = escapes ? PolicyVariableResolver.literal(condValue) : condValue;
        return switch (operator) {
            case "StringEquals"              -> ctxValue.equals(plain);
            case "StringNotEquals"           -> !ctxValue.equals(plain);
            case "StringEqualsIgnoreCase"    -> ctxValue.equalsIgnoreCase(plain);
            case "StringNotEqualsIgnoreCase" -> !ctxValue.equalsIgnoreCase(plain);
            case "StringLike"                -> globMatches(condValue, ctxValue, true, escapes);
            case "StringNotLike"             -> !globMatches(condValue, ctxValue, true, escapes);
            case "ArnEquals", "ArnLike"      -> matchesArnCondition(condValue, ctxValue, escapes);
            case "ArnNotEquals", "ArnNotLike"-> !matchesArnCondition(condValue, ctxValue, escapes);
            case "Bool"                      -> Boolean.parseBoolean(condValue) == Boolean.parseBoolean(ctxValue);
            case "NumericEquals"             -> compareNumeric(ctxValue, condValue, order -> order == 0);
            case "NumericNotEquals"          -> compareNumeric(ctxValue, condValue, order -> order != 0);
            case "NumericLessThan"           -> compareNumeric(ctxValue, condValue, order -> order < 0);
            case "NumericLessThanEquals"     -> compareNumeric(ctxValue, condValue, order -> order <= 0);
            case "NumericGreaterThan"        -> compareNumeric(ctxValue, condValue, order -> order > 0);
            case "NumericGreaterThanEquals"  -> compareNumeric(ctxValue, condValue, order -> order >= 0);
            case "DateEquals"                -> compareDates(ctxValue, condValue, order -> order == 0);
            case "DateNotEquals"             -> compareDates(ctxValue, condValue, order -> order != 0);
            case "DateLessThan"              -> compareDates(ctxValue, condValue, order -> order < 0);
            case "DateLessThanEquals"        -> compareDates(ctxValue, condValue, order -> order <= 0);
            case "DateGreaterThan"           -> compareDates(ctxValue, condValue, order -> order > 0);
            case "DateGreaterThanEquals"     -> compareDates(ctxValue, condValue, order -> order >= 0);
            case "IpAddress"                 -> matchesIpAddress(condValue, ctxValue);
            case "NotIpAddress"              -> !matchesIpAddress(condValue, ctxValue);
            default -> {
                LOG.warnv("Unsupported condition operator: {0} — treating as no-match", operator);
                yield false;
            }
        };
    }

    /**
     * Compares exactly, so integers beyond a double's precision stay distinct. A value that
     * does not parse never satisfies the operator, so it cannot pass as equal.
     */
    private static boolean compareNumeric(String ctxValue, String condValue, IntPredicate test) {
        if (ctxValue == null || condValue == null) {
            return false;
        }
        try {
            return test.test(new BigDecimal(ctxValue.trim()).compareTo(new BigDecimal(condValue.trim())));
        } catch (NumberFormatException e) {
            LOG.debugv("Numeric condition on non-numeric value {0} vs {1}: no match", ctxValue, condValue);
            return false;
        }
    }

    /** A value that does not parse as a date never satisfies the operator. */
    private static boolean compareDates(String ctxValue, String condValue, IntPredicate test) {
        if (ctxValue == null || condValue == null) {
            return false;
        }
        try {
            return test.test(parseConditionDate(ctxValue).compareTo(parseConditionDate(condValue)));
        } catch (DateTimeException | ArithmeticException | NumberFormatException e) {
            LOG.debugv("Date condition on non-date value {0} vs {1}: no match", ctxValue, condValue);
            return false;
        }
    }

    /**
     * Reads epoch seconds or a W3C ISO 8601 profile date: {@code YYYY-MM}, {@code YYYY-MM-DD},
     * or a date-time with or without seconds and fraction. A bare {@code YYYY} is read as epoch
     * seconds, as the two forms cannot be told apart. Dates without a time start at midnight UTC.
     */
    private static Instant parseConditionDate(String value) {
        String trimmed = value.trim();
        if (EPOCH_SECONDS.matcher(trimmed).matches()) {
            return Instant.ofEpochSecond(Long.parseLong(trimmed));
        }
        if (trimmed.indexOf('T') >= 0 || trimmed.indexOf('t') >= 0) {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        }
        if (YEAR_MONTH.matcher(trimmed).matches()) {
            return YearMonth.parse(trimmed).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant();
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
        return globMatches(pattern, value, false, false);
    }

    public static boolean caseSensitiveGlobMatches(String pattern, String value) {
        return globMatches(pattern, value, true, false);
    }

    /**
     * One glob, with {@code caseSensitive} choosing how the characters compare and
     * {@code escapes} saying whether a {@link PolicyVariableResolver#ESCAPE} marker in the pattern
     * protects the character behind it. Only a pattern that came back from substitution carries a
     * marker, so every other caller leaves escapes off and a marker written into a policy is just
     * a character.
     */
    private static boolean globMatches(String pattern, String value, boolean caseSensitive,
                                        boolean escapes) {
        if (pattern == null || value == null) {
            return false;
        }
        return caseSensitive
                ? globMatchesHelper(pattern, value, escapes)
                : globMatchesHelper(pattern.toLowerCase(), value.toLowerCase(), escapes);
    }

    public static boolean matchesArnCondition(String pattern, String value) {
        return matchesArnCondition(pattern, value, false);
    }

    private static boolean matchesArnCondition(String pattern, String value, boolean escapes) {
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
            if (!globMatches(patternComponents[i], valueComponents[i], true, escapes)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Matches {@code pattern} against {@code value}, where {@code *} is any sequence and
     * {@code ?} any single character. A character behind {@link PolicyVariableResolver#ESCAPE}
     * matches only itself, which is how {@code ${*}} and {@code ${?}} keep standing for an
     * asterisk and a question mark once they are substituted.
     */
    private static boolean globMatchesHelper(String pattern, String value) {
        return globMatchesHelper(pattern, value, false);
    }

    private static boolean globMatchesHelper(String pattern, String value, boolean escapes) {
        int patternIndex = 0;
        int valueIndex = 0;
        int starIndex = -1;
        int starValueIndex = -1;

        while (valueIndex < value.length()) {
            if (escapes && patternIndex + 1 < pattern.length()
                    && pattern.charAt(patternIndex) == PolicyVariableResolver.ESCAPE) {
                if (pattern.charAt(patternIndex + 1) == value.charAt(valueIndex)) {
                    patternIndex += 2;
                    valueIndex++;
                    continue;
                }
                if (starIndex < 0) {
                    return false;
                }
                patternIndex = starIndex + 1;
                valueIndex = ++starValueIndex;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
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
        String version = root.path("Version").asText(null);
        List<PolicyStatement> result = new ArrayList<>();
        if (stmtNode.isArray()) {
            for (JsonNode s : stmtNode) {
                result.add(parseStatement(s, version));
            }
        } else if (stmtNode.isObject()) {
            result.add(parseStatement(stmtNode, version));
        }
        return result;
    }

    private PolicyStatement parseStatement(JsonNode stmt, String version) {
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
                conditions,
                version);
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
            opEntry.getValue().fields().forEachRemaining(kvEntry -> {
                JsonNode value = kvEntry.getValue();
                kvMap.put(kvEntry.getKey(), value.isBoolean() || value.isNumber()
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
