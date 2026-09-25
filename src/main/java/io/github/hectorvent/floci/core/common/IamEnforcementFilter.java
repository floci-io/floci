package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourceArnBuilder;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JAX-RS filter that enforces IAM policies on every incoming request when
 * {@code floci.services.iam.enforcement-enabled = true}
 * ({@code FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true} in the environment).
 *
 * <p>Bypass rules (request is always allowed through):
 * <ul>
 *   <li>Enforcement is disabled (default)</li>
 *   <li>Access key is {@code "test"} (root/admin stand-in)</li>
 *   <li>Access key is a real credential this filter cannot map to policies, such as a session
 *       carrying no role ARN. An access key that exists nowhere is rejected, not bypassed.</li>
 *   <li>The action cannot be resolved (unknown mapping → permissive)</li>
 *   <li>The action is {@code sts:GetCallerIdentity}, which AWS allows without permissions</li>
 * </ul>
 *
 * <p>Evaluates the caller's identity policies, optional session policy, and optional
 * permissions boundary, as well as applicable resource policies via {@link ResourcePolicyProvider}.
 *
 * <p>Reads the signing credential from either the {@code Authorization} header or, for a
 * presigned URL, the {@code X-Amz-Credential} query parameter - both request shapes get the
 * same policy evaluation. A presigned POST form carries its credential in the multipart body,
 * which is unavailable at this JAX-RS filter stage; that shape is authorized separately via
 * {@link #authorizeAdditionalResource} once {@code S3Controller} has parsed the form fields.
 */
@Provider
@ApplicationScoped
public class IamEnforcementFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(IamEnforcementFilter.class);

    /** Extracts the credential-scope service name (e.g. "s3", "lambda"). */
    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    /** AWS's wording for a credential it does not recognise. */
    private static final String INVALID_SECURITY_TOKEN = "The security token included in the request is invalid.";

    /**
     * Implicit identity policy for the account-root principal: full access, bounded only by SCPs.
     * The account root is not a registered IAM identity, so it has no stored identity policy.
     */
    private static final String ROOT_ALLOW_ALL =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";

    private final EmulatorConfig config;
    private final AccountResolver accountResolver;
    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final IamActionRegistry actionRegistry;
    private final ResourceArnBuilder arnBuilder;
    private final RequestContext requestContext;
    private final IamConditionContextResolver conditionContextResolver;
    private final CloudTrailService cloudTrailService;
    private final CurrentVertxRequest currentVertxRequest;
    private final ResolvedServiceCatalog catalog;
    private final Instance<ScpProvider> scpProvider;
    private final SessionAccountLookup sessionAccountLookup;
    private final Instance<ResourcePolicyProvider> resourcePolicyProviders;

    @Inject
    public IamEnforcementFilter(EmulatorConfig config,
                                AccountResolver accountResolver,
                                IamService iamService,
                                IamPolicyEvaluator evaluator,
                                IamActionRegistry actionRegistry,
                                ResourceArnBuilder arnBuilder,
                                RequestContext requestContext,
                                IamConditionContextResolver conditionContextResolver,
                                CloudTrailService cloudTrailService,
                                CurrentVertxRequest currentVertxRequest,
                                ResolvedServiceCatalog catalog,
                                Instance<ScpProvider> scpProvider,
                                SessionAccountLookup sessionAccountLookup,
                                Instance<ResourcePolicyProvider> resourcePolicyProviders) {
        this.config = config;
        this.accountResolver = accountResolver;
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.actionRegistry = actionRegistry;
        this.arnBuilder = arnBuilder;
        this.requestContext = requestContext;
        this.conditionContextResolver = conditionContextResolver;
        this.cloudTrailService = cloudTrailService;
        this.currentVertxRequest = currentVertxRequest;
        this.catalog = catalog;
        this.scpProvider = scpProvider;
        this.sessionAccountLookup = sessionAccountLookup;
        this.resourcePolicyProviders = resourcePolicyProviders;
    }

    /** Package-private constructor for callers predating resourcePolicyProviders. */
    IamEnforcementFilter(EmulatorConfig config,
                         AccountResolver accountResolver,
                         IamService iamService,
                         IamPolicyEvaluator evaluator,
                         IamActionRegistry actionRegistry,
                         ResourceArnBuilder arnBuilder,
                         RequestContext requestContext,
                         IamConditionContextResolver conditionContextResolver,
                         CloudTrailService cloudTrailService,
                         CurrentVertxRequest currentVertxRequest,
                         ResolvedServiceCatalog catalog,
                         Instance<ScpProvider> scpProvider,
                         SessionAccountLookup sessionAccountLookup) {
        this(config, accountResolver, iamService, evaluator, actionRegistry, arnBuilder,
                requestContext, conditionContextResolver, cloudTrailService, currentVertxRequest,
                catalog, scpProvider, sessionAccountLookup, null);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!config.services().iam().enforcementEnabled()) {
            return;
        }

        String auth = ctx.getHeaderString("Authorization");
        if (auth == null) {
            auth = presignedCredentialAsAuthorization(ctx);
        }
        if (auth == null) {
            return;
        }

        String akid = accountResolver.extractAccessKeyId(auth);
        if (akid == null || "test".equals(akid)) {
            return; // root bypass
        }

        String rawScope = extractCredentialScope(auth);
        if (rawScope == null) {
            return;
        }
        // Normalise signing aliases (s3express → s3) before anything keyed by scope runs:
        // action rules, ARN building and condition keys all match the canonical name, so an
        // alias would resolve to no action and be allowed through without any policy check.
        String credentialScope = servingCredentialScope(catalog.canonicalCredentialScope(rawScope), ctx);

        String action = actionRegistry.resolve(credentialScope, ctx);
        if (action == null) {
            return; // unknown action → ALLOW (permissive)
        }
        if ("sts:GetCallerIdentity".equals(action)) {
            return; // AWS returns caller identity even when an identity policy explicitly denies it
        }

        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        String accountId = requestContext.getAccountId() == null
                ? accountResolver.resolve(auth)
                : requestContext.getAccountId();

        // Service control policies from the caller's organization, when the Organizations
        // service is present and SCP enforcement is enabled. Resolved lazily via Instance
        // to avoid a hard IAM → Organizations dependency.
        //
        // Resolved before resolveCallerContext because the account-root branch below needs to
        // know whether a ceiling exists in order to decide between enforcing and bypassing. That
        // costs an organization lookup on requests that then bypass; both flags are opt-in, and
        // effectiveScpLevels returns null immediately when SCP enforcement is off.
        List<List<String>> scpLevels = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(accountId)
                : null;

        boolean accountRootPrincipal = false;
        CallerContext caller = iamService.resolveCallerContext(akid);
        if (caller == null) {
            // A bare 12-digit account-id key is floci's account-root principal: not a registered
            // IAM identity (resolveCallerContext → null), but in AWS the account root is still
            // bounded by SCPs. Enforce them when the account actually has an SCP ceiling.
            if (akid.equals(accountId)) {
                if (scpLevels == null) {
                    return; // account root with no ceiling → nothing to enforce
                }
                caller = CallerContext.of(List.of(ROOT_ALLOW_ALL));
                accountRootPrincipal = true;
            } else if (iamService.isKnownAccessKey(akid)) {
                // A real credential this filter cannot map to policies, such as a session with no
                // role ARN. Denying it would reject an authenticated caller, so it stays allowed.
                return;
            } else {
                // No such credential anywhere. Enforcement is on and the caller is unauthenticated,
                // so allowing it would hand an arbitrary access key id every permission there is.
                LOG.debugv("Rejecting request signed with an unknown access key id {0}", akid);
                ctx.abortWith(unrecognizedClientResponse(credentialScope, ctx.getMediaType()));
                return;
            }
        }
        if (scpLevels != null) {
            caller = caller.withScpLevels(scpLevels);
        }

        List<String> resources = resolveResourceArns(credentialScope,
                arnBuilder.buildResources(credentialScope, ctx, region, accountId));

        Map<String, List<String>> conditionContext = conditionContextResolver.resolve(credentialScope, action, ctx);
        // A request naming several resources is authorized once per resource, as on AWS, so a
        // permitted first target cannot carry later targets that the policy does not allow.
        List<Map<String, List<String>>> remainingTargets =
                conditionContextResolver.resolveRemainingTargets(credentialScope, action, ctx);

        // aws:PrincipalArn is populated for every principal this filter can identify — IAM users,
        // assumed-role sessions, and now the synthesized account-root principal above, using AWS's
        // own root ARN shape (arn:aws:iam::<account>:root). Real AWS populates this key for the
        // root user, so a DenyRootUser guardrail keyed on it must fire against floci's account-root
        // stand-in the same way it enforces SCPs against it (the account-root SCP change above);
        // leaving it absent here would have made the two forms of root enforcement inconsistent.
        Optional<String> principalArn = accountRootPrincipal
                ? Optional.of(AwsArnUtils.Arn.global(requestPartition(), "iam", accountId, "root").toString())
                : iamService.resolveCallerArn(akid);
        if (principalArn.isPresent()) {
            caller = caller.withPrincipalArn(principalArn.get());
            conditionContext = conditionContext == null ? new HashMap<>() : new HashMap<>(conditionContext);
            conditionContext.put("aws:PrincipalArn", List.of(principalArn.get()));
        }
        List<Map<String, List<String>>> targetContexts = new ArrayList<>();
        targetContexts.add(conditionContext);
        for (Map<String, List<String>> target : remainingTargets) {
            Map<String, List<String>> targetContext = new HashMap<>(target);
            principalArn.ifPresent(arn -> targetContext.put("aws:PrincipalArn", List.of(arn)));
            targetContexts.add(targetContext);
        }

        if (abortIfDenied(ctx, caller, action, credentialScope, resources, targetContexts,
                region, accountId, akid)) {
            return;
        }

        // A PutObject carrying If-Match compares against the object it replaces, and S3 authorizes
        // that read as s3:GetObject, WITHOUT the object's tags in the request context. Measured
        // against real AWS: under a GetObject allow conditioned on s3:ExistingObjectTag the
        // conditional write is AccessDenied, under a GetObject allow scoped by prefix alone it
        // succeeds, and with no GetObject at all it is denied. If-None-Match needs no such
        // permission.
        if ("s3:PutObject".equals(action) && ctx.getHeaderString("If-Match") != null) {
            abortIfDenied(ctx, caller, "s3:GetObject", credentialScope, resources,
                    withoutObjectTags(targetContexts), region, accountId, akid);
        }
    }

    /**
     * Evaluates one action against every resource and target context, aborting the request with
     * AccessDenied on the first DENY. Returns true when the request was aborted.
     */
    private boolean abortIfDenied(ContainerRequestContext ctx, CallerContext caller, String action,
                                  String credentialScope, List<String> resources,
                                  List<Map<String, List<String>>> targetContexts,
                                  String region, String accountId, String akid) {
        for (String resource : resources) {
            List<ResourcePolicyProvider.ResourcePolicy> resourcePolicies = resolveResourcePolicies(credentialScope, resource);
            String resourceOwnerAccountId = resourcePolicies.isEmpty() ? null : resourcePolicies.getFirst().ownerAccountId();
            List<String> policyDocs = resourcePolicies.stream()
                    .map(ResourcePolicyProvider.ResourcePolicy::policyDocument)
                    .filter(doc -> doc != null && !doc.isBlank())
                    .toList();
            List<String> effectiveResourcePolicies = policyDocs.isEmpty() ? null : policyDocs;

            ResourceAccountRelationship accountRelationship = resourceOwnerAccountId == null
                    || accountId.equals(resourceOwnerAccountId)
                    ? ResourceAccountRelationship.SAME_ACCOUNT
                    : ResourceAccountRelationship.CROSS_ACCOUNT;

            for (Map<String, List<String>> targetContext : targetContexts) {
                Map<String, List<String>> effectiveContext = IamConditionContextResolver.withGlobalContext(
                        targetContext, resource, region, accountId, resourceOwnerAccountId);
                ResourcePolicyDecision resourcePolicyDecision = evaluator.evaluateResourcePolicy(
                        effectiveResourcePolicies, caller.principalArn(), action, resource, effectiveContext);
                Decision decision = evaluator.evaluateResolvedResourcePolicy(
                        caller, resourcePolicyDecision, accountRelationship, action, resource, effectiveContext);
                if (decision != Decision.DENY) {
                    continue;
                }
                LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}", akid, action, resource);
                String denyMessage = "User: " + AwsArnUtils.Arn.global(requestPartition(), "iam", accountId, "user/" + akid)
                        + " is not authorized to perform: " + action
                        + " on resource: \"" + resource + "\""
                        + " because no identity-based policy allows the " + action + " action";
                emitS3DenialIfApplicable(akid, action, resource, ctx, region, denyMessage);
                ctx.abortWith(accessDeniedResponse(action, credentialScope, ctx.getMediaType(), resource));
                return true;
            }
        }
        return false;
    }

    /**
     * The scope of the service that will actually serve this request, which is not always the one
     * the caller signed for. Everything keyed by scope (the action, its ARNs, its condition keys)
     * has to describe the service that runs, or a policy naming that service never matches.
     *
     * <p>Only the claim decides this, never {@code X-Amz-Target} read directly: the target routes
     * a request solely under the conditions {@link ProtocolClaimer} applies, and reading it here
     * would let a header attached to, say, an S3 request move the authorization to another
     * service while S3 still served it.
     *
     * <p>{@link WireProtocol#AWS_QUERY} is out of scope for this method, not solved by it. A Query
     * claim carries the credential-scope service, which restates the caller whenever that service
     * serves Query at all; when it does not, {@code AwsQueryController} falls through to inferring
     * the service from the action name and can dispatch somewhere else entirely. Closing that needs
     * the controller's inference shared rather than duplicated here, and is tracked in
     * <a href="https://github.com/floci-io/floci/issues/4296">#4296</a>.
     */
    private String servingCredentialScope(String claimedScope, ContainerRequestContext ctx) {
        if (ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                && claim.service() != null
                && claim.protocol() != WireProtocol.AWS_QUERY) {
            return iamServiceScope(claim.service(), claimedScope);
        }
        return claimedScope;
    }

    /** The descriptor's own scope, keeping {@code claimedScope} when the descriptor accepts it. */
    private String iamServiceScope(ServiceDescriptor descriptor, String claimedScope) {
        if (descriptor.credentialScopes().contains(claimedScope)) {
            return claimedScope;
        }
        // The service's own key, not just any scope it signs under: pricing accepts both
        // "pricing" and "api.pricing", and only the former is the namespace its policies name.
        // Sorting the rest keeps the choice stable, since credentialScopes is a Set.of whose
        // iteration order changes per JVM run.
        if (descriptor.credentialScopes().contains(descriptor.externalKey())) {
            return descriptor.externalKey();
        }
        return descriptor.credentialScopes().stream()
                .filter(scope -> scope.equals(catalog.canonicalCredentialScope(scope)))
                .sorted()
                .findFirst()
                .orElse(descriptor.externalKey());
    }

    /** The same contexts with every object-tag key removed, keeping the principal and global keys. */
    private static List<Map<String, List<String>>> withoutObjectTags(
            List<Map<String, List<String>>> targetContexts) {
        List<Map<String, List<String>>> stripped = new ArrayList<>();
        for (Map<String, List<String>> targetContext : targetContexts) {
            if (targetContext == null) {
                stripped.add(null);
                continue;
            }
            Map<String, List<String>> copy = new HashMap<>(targetContext);
            copy.keySet().removeIf(key ->
                    key.startsWith(IamConditionContextResolver.EXISTING_OBJECT_TAG_PREFIX)
                            || key.startsWith(IamConditionContextResolver.REQUEST_OBJECT_TAG_PREFIX));
            stripped.add(copy.isEmpty() ? null : copy);
        }
        return stripped;
    }

    /**
     * Authorizes a single (action, resource) pair for the caller identified by an
     * Authorization header, following the same identity resolution and bypass rules
     * as {@link #filter}. Callers use this for a secondary resource that never appears
     * in the request URL and so is invisible to {@link ResourceArnBuilder} - such as
     * the CopyObject/UploadPartCopy source object, which arrives only in the
     * {@code x-amz-copy-source} header.
     *
     * <p>Returns normally when the action is allowed, or when enforcement does not
     * apply to this request (enforcement disabled, no Authorization header, root or
     * unknown access key). Throws {@link AwsException} with the same AccessDenied
     * shape as {@link #filter} when the caller's policies deny the action.
     *
     * <p>The account used for policy evaluation is always re-resolved from {@code akid} here
     * (see {@link #resolveCredentialAccountId}) rather than trusted from {@link RequestContext},
     * because a presigned POST's credential is invisible to {@code AccountContextFilter} - it
     * arrives only in the multipart body, parsed well after that filter already set the ambient
     * account to the configured default. The resolved account is pushed onto {@link RequestContext}
     * for the duration of this call so that {@link IamService#resolveCallerContext} and
     * {@link IamService#resolveCallerArn}, which both key their per-account lookups off the
     * ambient account, resolve the credential's actual owner instead of the default account.
     */
    public void authorizeAdditionalResource(String authorizationHeader, String action, String resource) {
        authorizeAdditionalResource(
                authorizationHeader, action, resource, ResourcePolicyDecision.NEUTRAL, null);
    }

    /**
     * Authorizes a secondary resource using an already principal-filtered resource-policy
     * decision. This preserves explicit-deny precedence while allowing either the identity or
     * resource policy to provide the base grant.
     */
    public void authorizeAdditionalResource(
            String authorizationHeader,
            String action,
            String resource,
            ResourcePolicyDecision resourcePolicyDecision) {
        authorizeAdditionalResource(
                authorizationHeader, action, resource, resourcePolicyDecision, null);
    }

    /**
     * Authorizes a secondary resource whose owning account is known. Resource-policy grants
     * crossing an account boundary require a matching identity-policy grant as well.
     */
    public void authorizeAdditionalResource(
            String authorizationHeader,
            String action,
            String resource,
            ResourcePolicyDecision resourcePolicyDecision,
            String resourceOwnerAccountId) {
        if (!config.services().iam().enforcementEnabled()) {
            return;
        }
        if (authorizationHeader == null) {
            return;
        }
        String akid = accountResolver.extractAccessKeyId(authorizationHeader);
        if (akid == null || "test".equals(akid)) {
            return;
        }
        if (extractCredentialScope(authorizationHeader) == null) {
            return;
        }

        String accountId = resolveCredentialAccountId(akid, authorizationHeader);
        String previousAccountId = requestContext.getAccountId();
        requestContext.setAccountId(accountId);
        try {
            List<List<String>> scpLevels = scpProvider.isResolvable()
                    ? scpProvider.get().effectiveScpLevels(accountId) : null;

            boolean accountRootPrincipal = false;
            CallerContext caller = iamService.resolveCallerContext(akid);
            if (caller == null) {
                // No unknown-key rejection here, unlike filter(): this path runs only for a
                // presigned POST, whose form signature S3PostPolicySigner has already verified
                // against the key's secret, so an unknown key never reaches it.
                if (scpLevels == null || !akid.equals(accountId)) {
                    return;
                }
                caller = CallerContext.of(List.of(ROOT_ALLOW_ALL));
                accountRootPrincipal = true;
            }
            if (scpLevels != null) {
                caller = caller.withScpLevels(scpLevels);
            }

            Map<String, List<String>> conditionContext = null;
            Optional<String> principalArn = accountRootPrincipal
                    ? Optional.of(AwsArnUtils.Arn.global(requestPartition(), "iam", accountId, "root").toString())
                    : iamService.resolveCallerArn(akid);
            if (principalArn.isPresent()) {
                caller = caller.withPrincipalArn(principalArn.get());
                conditionContext = new HashMap<>();
                conditionContext.put("aws:PrincipalArn", List.of(principalArn.get()));
            }

            ResourceAccountRelationship accountRelationship = resourceOwnerAccountId == null
                    || accountId.equals(resourceOwnerAccountId)
                    ? ResourceAccountRelationship.SAME_ACCOUNT
                    : ResourceAccountRelationship.CROSS_ACCOUNT;
            Decision decision = evaluator.evaluateResolvedResourcePolicy(
                    caller, resourcePolicyDecision, accountRelationship,
                    action, resource, conditionContext);
            if (decision != Decision.DENY) {
                return;
            }
            LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}", akid, action, resource);
            throw new AwsException("AccessDenied",
                    "User: " + AwsArnUtils.Arn.global(requestPartition(), "iam", accountId, "user/" + akid)
                            + " is not authorized to perform: " + action
                            + " on resource: \"" + resource + "\""
                            + " because no identity-based policy allows the " + action + " action",
                    403);
        } finally {
            requestContext.setAccountId(previousAccountId);
        }
    }

    /**
     * Resolves the account that owns {@code akid} directly from the credential, following the
     * same precedence {@link AccountContextFilter} applies to a header or presigned-URL request:
     * a 12-digit access key ID is the account itself, otherwise {@link SessionAccountLookup}
     * looks up the owning account for an IAM or session credential, falling back to the configured
     * default account when neither resolves.
     */
    private String resolveCredentialAccountId(String akid, String authorizationHeader) {
        if (akid != null && !akid.matches("\\d{12}")) {
            Optional<String> credentialAccount = sessionAccountLookup.resolveAccountId(akid);
            if (credentialAccount.isPresent()) {
                return credentialAccount.get();
            }
        }
        return accountResolver.resolve(authorizationHeader);
    }

    /**
     * Best-effort CloudTrail emission for S3 access denials. Without this hook,
     * denied requests get aborted before {@code S3Controller}'s try/catch sees
     * them, so denials would never appear in CloudTrail logs — leaving a major
     * gap vs. real AWS for downstream audit ingestion. Failures here never
     * propagate (the deny response is the load-bearing behavior).
     */
    private void emitS3DenialIfApplicable(String akid, String action, String resource,
                                          ContainerRequestContext ctx, String region,
                                          String denyMessage) {
        try {
            if (action == null || !action.startsWith("s3:")) {
                return;
            }
            String eventName = mapS3ActionToEventName(action, ctx.getMethod());
            if (eventName == null) {
                return;
            }
            String[] bk = parseS3Resource(resource);
            String bucket = bk[0];
            String key = bk[1];

            String userAgent = null;
            String sourceIp = null;
            try {
                var rc = currentVertxRequest.getCurrent();
                if (rc != null) {
                    var req = rc.request();
                    if (req != null) {
                        userAgent = req.getHeader("User-Agent");
                        String fwd = req.getHeader("X-Forwarded-For");
                        if (fwd != null && !fwd.isBlank()) {
                            int comma = fwd.indexOf(',');
                            sourceIp = (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
                        } else if (req.remoteAddress() != null) {
                            sourceIp = req.remoteAddress().host();
                        }
                    }
                }
            } catch (Exception e) {
                LOG.tracev(e, "CloudTrail: could not extract request context for IAM denial {0} on {1}", action, resource);
            }

            cloudTrailService.emitS3DataEvent(CloudTrailService.S3EventInput.builder()
                    .region(region)
                    .eventName(eventName)
                    .bucketName(bucket)
                    .key(key)
                    .accessKeyId(akid)
                    .sourceIp(sourceIp)
                    .userAgent(userAgent)
                    .errorCode("AccessDenied")
                    .errorMessage(denyMessage)
                    .eventTimeMillis(System.currentTimeMillis())
                    .build());
        } catch (RuntimeException e) {
            LOG.tracev(e, "CloudTrail denial emission failed for {0} on {1}", action, resource);
        }
    }

    // Package-private for unit testing.
    static String mapS3ActionToEventName(String action, String httpMethod) {
        if (action == null) return null;
        // Action set sourced from IamActionRegistry — see that file for any
        // additions. HEAD on an object is bucketed under s3:GetObject by the
        // registry, so we distinguish via httpMethod.
        return switch (action) {
            case "s3:GetObject" -> "HEAD".equalsIgnoreCase(httpMethod) ? "HeadObject" : "GetObject";
            case "s3:PutObject" -> "PutObject";
            case "s3:DeleteObject" -> "DeleteObject";
            case "s3:ListBucket" -> "ListObjects";
            case "s3:ListAllMyBuckets" -> "ListBuckets";
            case "s3:GetObjectAcl" -> "GetObjectAcl";
            case "s3:PutObjectAcl" -> "PutObjectAcl";
            case "s3:GetObjectTagging" -> "GetObjectTagging";
            case "s3:PutObjectTagging" -> "PutObjectTagging";
            case "s3:DeleteObjectTagging" -> "DeleteObjectTagging";
            default -> null;
        };
    }

    /**
     * The partition the request belongs to, for the principals this filter synthesizes. Set by
     * {@link AccountContextFilter}; the deployment partition covers a call that reaches here first.
     */
    private String requestPartition() {
        String partition = requestContext.getPartition();
        return partition != null
                ? partition
                : RegionResolver.effectivePartition(config.defaultRegion(), config.partitions().id());
    }

    /** Returns [bucket, key] (key may be null if the resource is a bucket-level ARN). */
    // Package-private for unit testing.
    static String[] parseS3Resource(String resource) {
        String tail = AwsArnUtils.resourceIfArnFor(resource, "s3").orElse(null);
        if (tail == null || tail.isEmpty() || "*".equals(tail)) {
            return new String[] { null, null };
        }
        int slash = tail.indexOf('/');
        if (slash < 0) {
            return new String[] { tail, null };
        }
        String bucket = tail.substring(0, slash);
        String key = tail.substring(slash + 1);
        return new String[] { bucket, key.isEmpty() ? null : key };
    }

    private String extractCredentialScope(String auth) {
        Matcher m = SERVICE_PATTERN.matcher(auth);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Presigned URLs (and presigned POST forms, handled separately via
     * {@link #authorizeAdditionalResource}) sign via the {@code X-Amz-Credential} query
     * parameter instead of the {@code Authorization} header, so {@code ctx.getHeaderString}
     * alone misses them and this filter would silently skip IAM identity-policy evaluation for
     * every presigned request. {@link AccountContextFilter} already resolves account/region the
     * same way for the same reason. Synthesizing a {@code Credential=...} string from the query
     * parameter lets every downstream step here - access key extraction, credential scope,
     * action resolution, resource ARNs - run unchanged for both signing styles.
     */
    private static String presignedCredentialAsAuthorization(ContainerRequestContext ctx) {
        String credential = ctx.getUriInfo().getQueryParameters().getFirst("X-Amz-Credential");
        return credential == null || credential.isBlank() ? null : "Credential=" + credential;
    }

    /**
     * Builds a 403 Access Denied response in the wire format the calling SDK
     * expects. AWS SDKs hard-fail when they receive the wrong shape: an XML
     * parser blows up on a leading {@code {}, and a JSON parser blows up on
     * {@code <}. Pick the shape from request signals:
     *
     * <ul>
     *   <li>S3 → S3-flavored XML {@code <Error>...</Error>}</li>
     *   <li>{@code application/x-www-form-urlencoded} body → AWS Query
     *       {@code <ErrorResponse>...</ErrorResponse>} (IAM/STS/EC2/SQS/SNS/...)</li>
     *   <li>everything else (JSON 1.x, REST-JSON) → keep the historical JSON shape</li>
     * </ul>
     */
    /**
     * The request's resource ARNs as the resources are actually named, so that a policy written
     * against a resource living in another partition than the request's still matches it.
     */
    private List<String> resolveResourceArns(String credentialScope, List<String> resourceArns) {
        if (resourcePolicyProviders == null || resourcePolicyProviders.isUnsatisfied()) {
            return resourceArns;
        }
        List<String> resolved = new ArrayList<>(resourceArns.size());
        for (String resourceArn : resourceArns) {
            String arn = resourceArn;
            for (ResourcePolicyProvider provider : resourcePolicyProviders) {
                arn = provider.resolveResourceArn(credentialScope, arn);
            }
            resolved.add(arn);
        }
        return resolved;
    }

    private List<ResourcePolicyProvider.ResourcePolicy> resolveResourcePolicies(String credentialScope, String resourceArn) {
        if (resourcePolicyProviders == null || resourcePolicyProviders.isUnsatisfied()) {
            return List.of();
        }
        List<ResourcePolicyProvider.ResourcePolicy> policies = new ArrayList<>();
        for (ResourcePolicyProvider provider : resourcePolicyProviders) {
            List<ResourcePolicyProvider.ResourcePolicy> providerPolicies = provider.getResourcePolicies(credentialScope, resourceArn);
            if (providerPolicies != null && !providerPolicies.isEmpty()) {
                policies.addAll(providerPolicies);
            }
        }
        return policies;
    }

    // Package-private for unit testing.
    static Response accessDeniedResponse(String action, String credentialScope, MediaType requestMediaType) {
        return accessDeniedResponse(action, credentialScope, requestMediaType, null);
    }

    static Response accessDeniedResponse(String action, String credentialScope, MediaType requestMediaType, String resourceArn) {
        String message = "User is not authorized to perform: " + action;
        if ("s3".equals(credentialScope)) {
            String resourcePath = formatS3ResourcePath(resourceArn);
            return s3XmlAccessDenied(message, resourcePath);
        }
        if (isFormEncoded(requestMediaType)) {
            return queryXmlAccessDenied(message);
        }
        return jsonAccessDenied(message);
    }

    private static String formatS3ResourcePath(String resourceArn) {
        String tail = AwsArnUtils.resourceIfArnFor(resourceArn, "s3").orElse(null);
        if (tail == null || tail.isEmpty() || "*".equals(tail)) {
            return null;
        }
        return "/" + tail;
    }

    private static boolean isFormEncoded(MediaType mt) {
        return mt != null
                && "application".equalsIgnoreCase(mt.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mt.getSubtype());
    }

    private static Response queryXmlAccessDenied(String message) {
        return queryXmlError("AccessDenied", message);
    }

    private static Response queryXmlError(String code, String message) {
        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", code)
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(403).type(MediaType.APPLICATION_XML).entity(xml).build();
    }

    private static Response s3XmlAccessDenied(String message) {
        return s3XmlAccessDenied(message, null);
    }

    private static Response s3XmlError(String code, String message) {
        return s3Xml(code, message, null);
    }

    private static Response s3XmlAccessDenied(String message, String resourcePath) {
        return s3Xml("AccessDenied", message, resourcePath);
    }

    private static Response s3Xml(String code, String message, String resourcePath) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                  .elem("Code", code)
                  .elem("Message", message);
        if (resourcePath != null && !resourcePath.isBlank()) {
            xml.elem("Resource", resourcePath);
        }
        xml.elem("RequestId", UUID.randomUUID().toString())
           .end("Error");
        return Response.status(403).type(MediaType.APPLICATION_XML).entity(xml.build()).build();
    }

    /**
     * The response for an access key that exists nowhere, in each protocol's own vocabulary for
     * the same failure. S3 answers with {@code InvalidAccessKeyId}, as
     * {@code S3HeaderSignatureFilter} already does. Query services answer with
     * {@code InvalidClientTokenId}: neither code is modelled by sts, iam or sqs, but botocore's
     * own integration tests retry on {@code InvalidClientTokenId} from {@code sts:AssumeRole} and
     * {@code iam:ListGroups} while credentials propagate. JSON services answer with
     * {@code UnrecognizedClientException}, the only place botocore models it (CloudWatch Logs)
     * and what the API Gateway execute path already returns.
     */
    static Response unrecognizedClientResponse(String credentialScope, MediaType requestMediaType) {
        if ("s3".equals(credentialScope)) {
            return s3XmlError("InvalidAccessKeyId",
                    "The AWS Access Key Id you provided does not exist in our records.");
        }
        if (isFormEncoded(requestMediaType)) {
            return queryXmlError("InvalidClientTokenId", INVALID_SECURITY_TOKEN);
        }
        String body = "{\"__type\":\"UnrecognizedClientException\",\"message\":\"" + INVALID_SECURITY_TOKEN + "\"}";
        return Response.status(403).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    private static Response jsonAccessDenied(String message) {
        String body = "{\"__type\":\"AccessDeniedException\",\"message\":\"" + message + "\"}";
        return Response.status(403).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}
