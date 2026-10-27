package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages.PolicyType;
import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import io.github.hectorvent.floci.services.iam.IamAuthorizedActions;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.IamService.PresignedScope;
import io.github.hectorvent.floci.services.iam.PassedRoleLookup;
import io.github.hectorvent.floci.services.iam.RequestPrincipal;
import io.github.hectorvent.floci.services.iam.ResourceArnBuilder;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProviders;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.s3.S3ObjectActions;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
 *       carrying no role ARN. An access key that exists nowhere or is inactive, an expired
 *       session, and an {@code Authorization} header with no readable credential are rejected,
 *       not bypassed.</li>
 *   <li>The action cannot be resolved (unknown mapping → permissive)</li>
 *   <li>The action is {@code sts:GetCallerIdentity}, which AWS allows without permissions. The
 *       key itself is still checked.</li>
 *   <li>The action is {@code sts:GetSessionToken}, which AWS also allows without permissions:
 *       policies cannot control authentication operations. The key itself is still checked.</li>
 * </ul>
 *
 * <p>Authenticated temporary callers of {@code sts:GetFederationToken} are refused here with
 * the same operation-specific error as the handler, before policy evaluation.
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

    /** AWS's wording for a credential it does not recognise. */
    private static final String INVALID_SECURITY_TOKEN = "The security token included in the request is invalid.";

    /** S3's wording for an access key it does not recognise. */
    private static final String S3_INVALID_ACCESS_KEY_ID =
            "The AWS Access Key Id you provided does not exist in our records.";

    /**
     * AWS's wording for temporary credentials past their expiration, shared with an operation that
     * finds the session gone after this filter has let the request through.
     */
    public static final String EXPIRED_TOKEN = "The security token included in the request has expired.";

    /** S3's wording for the same. */
    private static final String S3_EXPIRED_TOKEN = "The provided token has expired.";

    /** AWS's wording for a signature that does not follow its format. */
    private static final String INCOMPLETE_SIGNATURE = "The request signature does not conform to AWS standards.";

    /** The IAM User Guide's "Troubleshoot SigV4" wording for an empty header. */
    private static final String EMPTY_AUTHORIZATION = "Authorization header cannot be empty.";

    /** The same guide's wording for a SigV4 header without its credential. */
    private static final String MISSING_CREDENTIAL = "Authorization header requires 'Credential' parameter.";

    /** AWS's wording for a request that carries no credentials at all. */
    private static final String MISSING_AUTHENTICATION_TOKEN =
            "The request must contain a valid AWS access key ID or X.509 certificate.";

    /**
     * Operations AWS itself serves without credentials, so an unsigned call on one is a normal
     * client rather than an unauthenticated caller. Taken from the {@code authtype: none} trait in
     * botocore's service models (Smithy's {@code noAuth}), restricted to the operations Floci
     * serves. Keyed by service because the name alone is not enough: {@code GetUser} needs no
     * credentials on {@code cognito-idp} and requires them on {@code iam}.
     *
     * <p>Add to this when Floci starts serving another operation AWS marks {@code noAuth}.
     * Omitting one makes enforcement refuse a call AWS accepts; adding one that AWS does sign
     * leaves that operation unauthenticated.
     *
     * <p>Only an operation reachable over a checked protocol needs an entry, since
     * {@link WireProtocol#REST} is not checked at all. {@code signin}, {@code sso} and
     * {@code sso-oidc} also carry {@code noAuth} operations and are absent for that reason rather
     * than by oversight: the SSO portal calls are served on REST routes, and the {@code sso} JSON
     * target reaches SSO Admin, whose operations AWS does sign.
     */
    private static final Map<String, Set<String>> NO_AUTH_OPERATIONS = Map.of(
            "cognito-idp", Set.of(
                    "AssociateSoftwareToken",
                    "ChangePassword",
                    "CompleteWebAuthnRegistration",
                    "ConfirmDevice",
                    "ConfirmForgotPassword",
                    "ConfirmSignUp",
                    "DeleteUser",
                    "DeleteUserAttributes",
                    "DeleteWebAuthnCredential",
                    "ForgetDevice",
                    "ForgotPassword",
                    "GetDevice",
                    "GetTokensFromRefreshToken",
                    "GetUser",
                    "GetUserAttributeVerificationCode",
                    "GetUserAuthFactors",
                    "GlobalSignOut",
                    "InitiateAuth",
                    "ListDevices",
                    "ListWebAuthnCredentials",
                    "ResendConfirmationCode",
                    "RespondToAuthChallenge",
                    "RevokeToken",
                    "SetUserMFAPreference",
                    "SetUserSettings",
                    "SignUp",
                    "StartWebAuthnRegistration",
                    "UpdateAuthEventFeedback",
                    "UpdateDeviceStatus",
                    "UpdateUserAttributes",
                    "VerifySoftwareToken",
                    "VerifyUserAttribute"),
            "cognito-identity", Set.of(
                    "GetCredentialsForIdentity",
                    "GetId",
                    "GetOpenIdToken",
                    "UnlinkIdentity"));

    /**
     * The same for the Query protocol, where the claim carries no service: an unsigned Query
     * request has no credential scope to derive one from. Both names are unique across the Query
     * services Floci serves, so the operation alone identifies them.
     */
    private static final Set<String> NO_AUTH_QUERY_OPERATIONS = Set.of(
            "AssumeRoleWithSAML",
            "AssumeRoleWithWebIdentity");

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
    private final AwsQueryServiceResolver queryServiceResolver;
    private final ResourceArnBuilder arnBuilder;
    private final RequestContext requestContext;
    private final IamConditionContextResolver conditionContextResolver;
    private final CloudTrailService cloudTrailService;
    private final CurrentVertxRequest currentVertxRequest;
    private final ResolvedServiceCatalog catalog;
    private final Instance<ScpProvider> scpProvider;
    private final SessionAccountLookup sessionAccountLookup;
    private final Instance<ResourcePolicyProvider> resourcePolicyProviders;
    private final Instance<PassedRoleLookup> passedRoleLookups;
    /** The clock {@link IamService} measures session expiry on, so both read the same time. */
    private final Clock clock;
    private final ResourceInfo resourceInfo;

    @Inject
    public IamEnforcementFilter(EmulatorConfig config,
                                AccountResolver accountResolver,
                                IamService iamService,
                                IamPolicyEvaluator evaluator,
                                IamActionRegistry actionRegistry,
                                AwsQueryServiceResolver queryServiceResolver,
                                ResourceArnBuilder arnBuilder,
                                RequestContext requestContext,
                                IamConditionContextResolver conditionContextResolver,
                                CloudTrailService cloudTrailService,
                                CurrentVertxRequest currentVertxRequest,
                                ResolvedServiceCatalog catalog,
                                Instance<ScpProvider> scpProvider,
                                SessionAccountLookup sessionAccountLookup,
                                Instance<ResourcePolicyProvider> resourcePolicyProviders,
                                Instance<PassedRoleLookup> passedRoleLookups,
                                Clock clock,
                                @Context ResourceInfo resourceInfo) {
        this.config = config;
        this.accountResolver = accountResolver;
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.actionRegistry = actionRegistry;
        this.queryServiceResolver = queryServiceResolver;
        this.arnBuilder = arnBuilder;
        this.requestContext = requestContext;
        this.conditionContextResolver = conditionContextResolver;
        this.cloudTrailService = cloudTrailService;
        this.currentVertxRequest = currentVertxRequest;
        this.catalog = catalog;
        this.scpProvider = scpProvider;
        this.sessionAccountLookup = sessionAccountLookup;
        this.resourcePolicyProviders = resourcePolicyProviders;
        this.passedRoleLookups = passedRoleLookups;
        this.clock = clock;
        this.resourceInfo = resourceInfo;
    }

    /** Package-private constructor for callers predating Query dispatch-aware enforcement. */
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
                         SessionAccountLookup sessionAccountLookup,
                         Instance<ResourcePolicyProvider> resourcePolicyProviders) {
        this(config, accountResolver, iamService, evaluator, actionRegistry,
                new AwsQueryServiceResolver(catalog), arnBuilder, requestContext,
                conditionContextResolver, cloudTrailService, currentVertxRequest, catalog,
                scpProvider, sessionAccountLookup, resourcePolicyProviders, null, Clock.systemUTC(), null);
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
        this(config, accountResolver, iamService, evaluator, actionRegistry,
                new AwsQueryServiceResolver(catalog), arnBuilder, requestContext,
                conditionContextResolver, cloudTrailService, currentVertxRequest, catalog,
                scpProvider, sessionAccountLookup, null, null, Clock.systemUTC(), null);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!config.services().iam().enforcementEnabled()) {
            return;
        }
        ProtocolClaim rpcV2Route = rpcV2RouteClaim(ctx);
        if (rpcV2Route != null) {
            // The claim filter runs before routing and can leave a call this endpoint serves
            // unclaimed, as an unrecognised smithy-protocol value does. Every check below that reads
            // the claim has to see the call that runs: unclaimed, an unsigned one would pass as REST.
            ctx.setProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY, rpcV2Route);
        }

        String auth = ctx.getHeaderString("Authorization");
        boolean emptyHeader = auth != null && auth.isBlank();
        if (auth == null || emptyHeader) {
            auth = requestAuthorization(null, ctx.getUriInfo().getQueryParameters());
        }
        if (auth == null) {
            if (emptyHeader) {
                // An empty header is not a missing one: "Authorization header cannot be empty" is
                // among the failures the IAM User Guide's "Troubleshoot SigV4" lists.
                refuseUnreadableCredential(ctx, "");
            } else {
                refuseUnsignedManagementCall(ctx);
            }
            return;
        }

        String akid = accountResolver.extractAccessKeyId(auth);
        if ("test".equals(akid)) {
            return; // root bypass
        }

        String rawScope = akid == null ? null : extractCredentialScope(auth);
        if (rawScope == null) {
            refuseUnreadableCredential(ctx, auth);
            return;
        }
        // Normalise signing aliases (s3express → s3) before anything keyed by scope runs:
        // action rules, ARN building and condition keys all match the canonical name, so an
        // alias would resolve to no action and be allowed through without any policy check.
        String claimedScope = catalog.canonicalCredentialScope(rawScope);
        ResolvedAuthorization resolvedAuthorization = resolveAuthorization(auth, claimedScope, ctx);
        String credentialScope = resolvedAuthorization.credentialScope();
        String action = resolvedAuthorization.action();
        if (action == null) {
            if (!"OPTIONS".equalsIgnoreCase(ctx.getMethod())
                    && iamService.presignedScope(akid).isPresent()) {
                ctx.abortWith(accessDeniedForRequest("Unknown", credentialScope, ctx));
                return;
            }
            if (!isRpcCall(ctx)) {
                return; // REST route no rule maps → ALLOW, as an unsigned REST request is
            }
        }

        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        String accountId = requestContext.getAccountId() == null
                ? accountResolver.resolve(auth)
                : requestContext.getAccountId();

        // The key is checked before the action is used: an API call needs a usable key whatever it
        // asks for, even one that needs no permission, such as GetCallerIdentity. A bare 12-digit
        // account-id key is floci's account-root principal, never an IAM user's key.
        boolean accountRootKey = akid.equals(accountId);
        if (!accountRootKey && iamService.isInactiveAccessKey(akid)) {
            LOG.debugv("Rejecting request signed with an inactive access key id {0}", akid);
            ctx.abortWith(unrecognizedClientResponse(credentialScope, ctx));
            return;
        }
        // Both lookups are asked about one moment, so a session that expires between them is not
        // deleted and then answered as a key that exists nowhere.
        Instant now = clock.instant();
        if (iamService.isExpiredSession(akid, now)) {
            LOG.debugv("Rejecting request signed with an expired session {0}", akid);
            ctx.abortWith(expiredTokenResponse(credentialScope, ctx));
            return;
        }
        CallerContext caller = iamService.resolveCallerContext(akid, now);
        if (caller == null && !accountRootKey && !iamService.isKnownAccessKey(akid)) {
            // No such credential anywhere. Enforcement is on and the caller is unauthenticated,
            // so allowing it would hand an arbitrary access key id every permission there is.
            LOG.debugv("Rejecting request signed with an unknown access key id {0}", akid);
            ctx.abortWith(unrecognizedClientResponse(credentialScope, ctx));
            return;
        }
        if (action == null) {
            return; // unknown action → ALLOW for ordinary credentials (permissive)
        }
        if ("sts:GetCallerIdentity".equals(action)) {
            return; // AWS returns caller identity even when an identity policy explicitly denies it
        }
        if ("sts:GetSessionToken".equals(action)) {
            // AWS: "No permissions are required for users to perform this operation... You cannot
            // use policies to control authentication operations." An explicit deny does not stop it.
            return;
        }

        if ("sts:GetFederationToken".equals(action) && IamService.isTemporaryAccessKey(akid)) {
            ctx.abortWith(queryXmlAccessDenied(IamService.FEDERATION_TOKEN_SESSION_MESSAGE));
            return;
        }

        // Service control policies from the caller's organization, when the Organizations
        // service is present and SCP enforcement is enabled. Resolved lazily via Instance
        // to avoid a hard IAM → Organizations dependency.
        //
        // Resolved before the account-root branch below, which needs to know whether a ceiling
        // exists in order to decide between enforcing and bypassing. That costs an organization
        // lookup on requests that then bypass; both flags are opt-in, and effectiveScpLevels
        // returns null immediately when SCP enforcement is off.
        List<List<String>> scpLevels = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(accountId)
                : null;

        boolean accountRootPrincipal = false;
        if (caller == null) {
            if (!accountRootKey) {
                // A real credential this filter cannot map to policies, such as a session Floci mints
                // for its own presigned URLs with no policy to scope it. Denying it would reject an
                // authenticated caller.
                return;
            }
            // A bare 12-digit account-id key is floci's account-root principal: not a registered
            // IAM identity (resolveCallerContext → null), but in AWS the account root is still
            // bounded by SCPs. Enforce them when the account actually has an SCP ceiling.
            if (scpLevels == null) {
                return; // account root with no ceiling → nothing to enforce
            }
            caller = CallerContext.of(List.of(ROOT_ALLOW_ALL));
            accountRootPrincipal = true;
        }
        if (scpLevels != null) {
            caller = caller.withScpLevels(scpLevels);
        }

        List<String> resources = resolveResourceArns(credentialScope,
                targetResources(credentialScope, action, resolvedAuthorization.operation(), ctx, region, accountId));

        Optional<PresignedScope> presignedScope = iamService.presignedScope(akid);
        if (presignedScope.isPresent()) {
            PresignedScope scope = presignedScope.get();
            if ("s3".equals(credentialScope)) {
                // UriInfo has normalized consecutive slashes. S3 and the SigV4 verifier use
                // the wire path, where /bucket//key names the distinct object /key.
                RoutingContext routingContext = currentVertxRequest.getCurrent();
                HttpServerRequest request = routingContext == null ? null : routingContext.request();
                // Use the wire path that S3's SigV4 verifier signs. uri() may instead be an
                // absolute-form request target forwarded by a proxy.
                String rawPath = request == null ? null : request.path();
                if (rawPath == null || !rawPath.startsWith("/")) {
                    ctx.abortWith(accessDeniedForRequest(action, credentialScope, ctx));
                    return;
                }
                String decodedPath;
                try {
                    decodedPath = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException invalidEncoding) {
                    ctx.abortWith(accessDeniedForRequest(action, credentialScope, ctx));
                    return;
                }
                resources = List.of(AwsArnUtils.Arn.global(AwsRegions.partitionFor(region),
                        "s3", "", decodedPath.substring(1)).toString());
            }
            // IAM's resource glob treats * and ? in object keys as patterns. An internal URL
            // credential is narrower: it may authorize only its literal object and action. That
            // action is never a version action: a generated URL signs only its X-Amz-* parameters,
            // so it names no versionId, and PreSignedUrlFilter refuses one appended to it as a
            // signature mismatch before this check.
            if (!scope.action().equals(action) || resources.isEmpty()
                    || resources.stream().anyMatch(resource -> !scope.resourceArn().equals(resource))) {
                ctx.abortWith(accessDeniedForRequest(action, credentialScope, ctx));
                return;
            }
            // An object created with an ACL, tags, a retention period or a legal hold also needs
            // that setting's own action. Generated URLs grant only their object operation (and
            // GetObject for conditional writes), so such an unsigned header must not add
            // permissions to the scoped session.
            if ("s3:PutObject".equals(action) && isObjectCreation(ctx)) {
                List<String> creationActions =
                        S3ObjectActions.objectCreationActions(ctx::getHeaderString, requestTagging(ctx),
                                S3ObjectActions.isCopy(ctx.getMethod(), ctx::getHeaderString));
                if (!creationActions.isEmpty()) {
                    ctx.abortWith(accessDeniedForRequest(creationActions.getFirst(), credentialScope, ctx));
                    return;
                }
            }
        }

        Map<String, List<String>> conditionContext = conditionContextResolver.resolve(credentialScope, action, ctx);
        // A request naming several resources is authorized once per resource, as on AWS, so a
        // permitted first target cannot carry later targets that the policy does not allow.
        List<Map<String, List<String>>> remainingTargets =
                conditionContextResolver.resolveRemainingTargets(credentialScope, action, ctx);

        // aws:PrincipalArn is populated for every principal this filter can identify — IAM users,
        // assumed-role sessions (as the ARN of the role that was assumed, as AWS reports it), and now
        // the synthesized account-root principal above, using AWS's own root ARN shape
        // (arn:aws:iam::<account>:root). Real AWS populates this key for the
        // root user, so a DenyRootUser guardrail keyed on it must fire against floci's account-root
        // stand-in the same way it enforces SCPs against it (the account-root SCP change above);
        // leaving it absent here would have made the two forms of root enforcement inconsistent.
        // The session ARN stays the caller's principal for matching a resource policy's Principal.
        Optional<CallerArns> callerArns = accountRootPrincipal
                ? Optional.of(accountRootArns(accountId))
                : iamService.resolveCallerArns(akid);
        if (callerArns.isPresent()) {
            caller = caller.withPrincipalArn(callerArns.get().callerArn());
            conditionContext = conditionContext == null ? new HashMap<>() : new HashMap<>(conditionContext);
            callerArns.get().putPrincipalKeys(conditionContext);
        }
        // A create that carries tags is checked with them where its own action takes them, as the tagging
        // action it also needs is (see TagOnCreate); keys the service's own resolver gives are kept.
        Optional<TagOnCreate.Tagging> tagOnCreate = TagOnCreate.of(resolvedAuthorization.operation(), ctx);
        List<Map<String, List<String>>> requestTags = tagOnCreate.filter(TagOnCreate.Tagging::requestTags)
                .map(TagOnCreate::requestTagContexts).orElse(List.of());
        List<Map<String, List<String>>> targetContexts = withRequestTags(conditionContext, requestTags);
        for (Map<String, List<String>> target : remainingTargets) {
            Map<String, List<String>> targetContext = new HashMap<>(target);
            callerArns.ifPresent(arns -> arns.putPrincipalKeys(targetContext));
            targetContexts.addAll(withRequestTags(targetContext, requestTags));
        }

        RequestPrincipal principal = RequestPrincipal.caller(callerArns.orElse(null));
        List<IamConditionContextResolver.DynamoDbTransactionItem> transactionItems =
                "dynamodb".equals(credentialScope)
                        ? conditionContextResolver.dynamoDbTransactionItems(action, ctx, region, accountId)
                        : List.of();
        if (!transactionItems.isEmpty()) {
            for (IamConditionContextResolver.DynamoDbTransactionItem item : transactionItems) {
                Map<String, List<String>> itemContext = new HashMap<>(item.context());
                callerArns.ifPresent(arns -> arns.putPrincipalKeys(itemContext));
                if (abortIfDenied(ctx, caller, principal, item.action(), credentialScope,
                        resolveResourceArns(credentialScope, List.of(item.resource())), List.of(itemContext),
                        region, accountId, akid)) {
                    return;
                }
            }
            return;
        }
        if (abortIfDenied(ctx, caller, principal, action, credentialScope, resources, targetContexts,
                region, accountId, akid)) {
            return;
        }
        if (abortIfRolePassDenied(ctx, caller, principal, callerArns.orElse(null), action, credentialScope,
                region, accountId, akid)) {
            return;
        }
        if (abortIfConnectorPassDenied(ctx, caller, callerArns.orElse(null), resolvedAuthorization.operation(),
                region, accountId, akid)) {
            return;
        }
        if (tagOnCreate.isPresent() && abortIfTagOnCreateDenied(ctx, caller, principal, callerArns.orElse(null),
                action, resolvedAuthorization.operation(), tagOnCreate.get(), credentialScope, resources, region,
                accountId, akid)) {
            return;
        }

        // Tagged group creation requires either tagging permission, using the same resolved
        // caller (including session, boundary and SCP ceilings) as the primary operation.
        if ("logs:CreateLogGroup".equals(action) && conditionContext != null
                && !conditionContext.getOrDefault("aws:TagKeys", List.of()).isEmpty()) {
            String groupResource = resources.getFirst();
            String taggingResource = groupResource.substring(0, groupResource.length() - 2);
            Map<String, List<String>> taggingContext = IamConditionContextResolver.withGlobalContext(
                    conditionContext, taggingResource, region, accountId, accountId);
            Map<String, List<String>> groupContext = IamConditionContextResolver.withGlobalContext(
                    conditionContext, groupResource, region, accountId, accountId);
            if (evaluator.evaluate(caller, null, "logs:TagResource", taggingResource, taggingContext) == Decision.DENY
                    && evaluator.evaluate(caller, null, "logs:TagLogGroup", groupResource, groupContext) == Decision.DENY) {
                LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}",
                        akid, "logs:TagResource", taggingResource);
                ctx.abortWith(accessDeniedForRequest("logs:TagResource", credentialScope, ctx, taggingResource));
                return;
            }
        }

        // PutAccountPolicy also needs the action for the type of policy it creates, and
        // DescribeAccountPolicies the one for the type it lists, as their API references say. An
        // unknown type needs nothing more: the handler refuses it.
        if ("logs:PutAccountPolicy".equals(action) || "logs:DescribeAccountPolicies".equals(action)) {
            String typeAction = accountPolicyTypeAction(action, arnBuilder.readJsonBody(ctx));
            if (typeAction != null && abortIfDenied(ctx, caller, principal, typeAction, action, credentialScope,
                    resources, targetContexts, region, accountId, akid)) {
                return;
            }
        }

        // A PutObject carrying If-Match compares against the object it replaces, and S3 authorizes
        // that read as s3:GetObject, WITHOUT the object's tags in the request context. Measured
        // against real AWS: under a GetObject allow conditioned on s3:ExistingObjectTag the
        // conditional write is AccessDenied, under a GetObject allow scoped by prefix alone it
        // succeeds, and with no GetObject at all it is denied. If-None-Match needs no such
        // permission. The user guide's conditional writes page lists PutObject, CopyObject and
        // CompleteMultipartUpload as the operations that take If-Match; CreateMultipartUpload and
        // UploadPart, also checked as s3:PutObject, ignore it, so it adds nothing to them.
        if ("s3:PutObject".equals(action) && evaluatesIfMatch(ctx) && ctx.getHeaderString("If-Match") != null
                && abortIfDenied(ctx, caller, principal, "s3:GetObject", action, credentialScope, resources,
                        withoutObjectTags(targetContexts), region, accountId, akid)) {
            return;
        }
        // A DeleteObject conditioned on an ETag reveals whether the object still has it, and the
        // conditional-deletes guide requires s3:GetObject for it; If-Match: * (existence only)
        // needs just the delete. A delete naming a version, checked as s3:DeleteObjectVersion, is
        // checked the same way: the guide says the condition is evaluated against the current
        // version only, so the read is of that one, s3:GetObject. The read is checked the way the
        // PutObject case above is.
        if (("s3:DeleteObject".equals(action) || "s3:DeleteObjectVersion".equals(action))
                && isPlainDeleteObject(ctx)
                && isETagCondition(ctx.getHeaderString("If-Match"))
                && abortIfDenied(ctx, caller, principal, "s3:GetObject", action, credentialScope, resources,
                        withoutObjectTags(targetContexts), region, accountId, akid)) {
            return;
        }
        // A retention change or a delete that overrides governance-mode retention takes
        // s3:BypassGovernanceRetention as well. The user guide's permissions table requires it "if you
        // want to delete an object that's protected by governance mode" and to "bypass the governance
        // mode of an Object Lock retention configuration", so the header on a request that overrides
        // no lock asks for nothing more. The user guide says the permission does not apply to
        // annotation operations. The decision is recorded for S3Controller, which lets the operation
        // bypass a lock only when this approved it, so a lock set after this check is not bypassed.
        if (GOVERNANCE_BYPASS_ACTIONS.contains(action)
                && "true".equalsIgnoreCase(ctx.getHeaderString("x-amz-bypass-governance-retention"))) {
            boolean overrides = conditionContextResolver.s3OverridesGovernanceRetention(action, ctx);
            if (overrides && abortIfDenied(ctx, caller, principal, "s3:BypassGovernanceRetention", action,
                    credentialScope, resources, targetContexts, region, accountId, akid)) {
                return;
            }
            requestContext.setIamGovernanceBypass(overrides);
        }
        // An object created with an ACL, tags, a retention period or a legal hold also needs that
        // setting's own action, as the permissions table says for PutObject, CopyObject and
        // CreateMultipartUpload. Each is checked with the PutObject context, which carries the
        // request's tags and Object Lock settings.
        if ("s3:PutObject".equals(action) && isObjectCreation(ctx)) {
            for (String creationAction
                    : S3ObjectActions.objectCreationActions(ctx::getHeaderString, requestTagging(ctx),
                                S3ObjectActions.isCopy(ctx.getMethod(), ctx::getHeaderString))) {
                if (abortIfDenied(ctx, caller, principal, creationAction, action, credentialScope, resources,
                        targetContexts, region, accountId, akid)) {
                    return;
                }
            }
        }
        // GetObjectAttributes needs s3:GetObjectAttributes beside s3:GetObject, and for a version
        // s3:GetObjectVersionAttributes beside s3:GetObjectVersion, as its API reference says. Both
        // carry the object's tags and the version id, so the read's context serves for either. A
        // denial is recorded as GetObjectAttributes, the operation it refuses.
        if (("s3:GetObject".equals(action) || "s3:GetObjectVersion".equals(action))
                && "GET".equalsIgnoreCase(ctx.getMethod())
                && ctx.getUriInfo().getQueryParameters().containsKey("attributes")) {
            String attributesAction = S3ObjectActions.forVersion("s3:GetObjectAttributes",
                    ctx.getUriInfo().getQueryParameters().getFirst("versionId"));
            abortIfDenied(ctx, caller, principal, attributesAction, attributesAction, credentialScope, resources,
                    targetContexts, region, accountId, akid);
        }
    }

    // The DELETE subresources S3Controller.deleteObject routes away from DeleteObject (?uploadId= is
    // AbortMultipartUpload). Everything else, including the x-id and X-Amz-* parameters SDKs and
    // presigned URLs add, is a DeleteObject.
    private static final Set<String> NON_DELETE_OBJECT_SUBRESOURCES = Set.of("uploadId", "tagging", "annotation");

    private static final Set<String> GOVERNANCE_BYPASS_ACTIONS =
            Set.of("s3:PutObjectRetention", "s3:DeleteObject", "s3:DeleteObjectVersion");

    private static String accountPolicyTypeAction(String action, JsonNode body) {
        if (body == null) {
            return null;
        }
        String policyType = body.path("policyType").asText();
        if ("logs:DescribeAccountPolicies".equals(action)) {
            // The reference names no read action for metric extraction policies.
            return switch (policyType) {
                case "DATA_PROTECTION_POLICY" -> "logs:GetDataProtectionPolicy";
                case "SUBSCRIPTION_FILTER_POLICY" -> "logs:DescribeSubscriptionFilters";
                case "TRANSFORMER_POLICY" -> "logs:GetTransformer";
                case "FIELD_INDEX_POLICY" -> "logs:DescribeIndexPolicies";
                default -> null;
            };
        }
        return switch (policyType) {
            case "DATA_PROTECTION_POLICY" -> "logs:PutDataProtectionPolicy";
            case "SUBSCRIPTION_FILTER_POLICY" -> "logs:PutSubscriptionFilter";
            case "TRANSFORMER_POLICY" -> "logs:PutTransformer";
            case "FIELD_INDEX_POLICY" -> "logs:PutIndexPolicy";
            case "METRIC_EXTRACTION_POLICY" -> "logs:PutMetricExtractionPolicy";
            default -> null;
        };
    }

    // PutObject and CopyObject are any PUT S3Controller does not run as UploadPart or UploadPartCopy,
    // which name both an uploadId and a partNumber; CompleteMultipartUpload is a POST naming its
    // upload, without ?uploads.
    private static boolean evaluatesIfMatch(ContainerRequestContext ctx) {
        MultivaluedMap<String, String> params = ctx.getUriInfo().getQueryParameters();
        boolean namesUpload = params.containsKey("uploadId");
        if ("PUT".equalsIgnoreCase(ctx.getMethod())) {
            return !(namesUpload && params.containsKey("partNumber"));
        }
        return "POST".equalsIgnoreCase(ctx.getMethod()) && namesUpload && !params.containsKey("uploads");
    }

    /**
     * PutObject and CopyObject (a PUT naming no uploadId, which would make it UploadPart) and
     * CreateMultipartUpload (a POST naming {@code uploads}): the operations checked as
     * {@code s3:PutObject} that build a new object from the request's headers.
     */
    private static boolean isObjectCreation(ContainerRequestContext ctx) {
        MultivaluedMap<String, String> params = ctx.getUriInfo().getQueryParameters();
        if ("PUT".equalsIgnoreCase(ctx.getMethod())) {
            return !params.containsKey("uploadId");
        }
        return "POST".equalsIgnoreCase(ctx.getMethod()) && params.containsKey("uploads");
    }

    /** The request's {@code x-amz-tagging}: the header, or the query parameter a presigner may place it in. */
    private static String requestTagging(ContainerRequestContext ctx) {
        String header = ctx.getHeaderString("x-amz-tagging");
        return header != null ? header : ctx.getUriInfo().getQueryParameters().getFirst("x-amz-tagging");
    }

    private static boolean isPlainDeleteObject(ContainerRequestContext ctx) {
        return "DELETE".equalsIgnoreCase(ctx.getMethod())
                && ctx.getUriInfo().getQueryParameters().keySet().stream()
                        .noneMatch(NON_DELETE_OBJECT_SUBRESOURCES::contains);
    }

    private static boolean isETagCondition(String ifMatch) {
        if (ifMatch == null) {
            return false;
        }
        String value = ifMatch.trim();
        return !value.equals("*") && !value.equals("\"*\"");
    }

    /**
     * Resolves both the IAM namespace and action from the service that will actually handle the
     * request. Query traffic shares the dispatch resolver with {@link AwsQueryController}; an RPC v2
     * call takes the service and operation its route dispatches; REST traffic uses the JAX-RS
     * resource class that won route matching.
     */
    private ResolvedAuthorization resolveAuthorization(String auth, String claimedScope,
                                                        ContainerRequestContext ctx) {
        Object claimValue = ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY);
        // Form POSTs are also recognized without a claim; GET dispatch requires the Query claim.
        if (IamActionRegistry.isQueryRequest(ctx)) {
            String queryAction = actionRegistry.queryAction(ctx);
            if (queryAction == null || queryAction.isBlank()) {
                return new ResolvedAuthorization(claimedScope, null);
            }
            String service = queryServiceResolver.resolve(auth, queryAction, actionRegistry.queryVersion(ctx));
            String servingScope = catalog.byExternalKey(service)
                    .map(descriptor -> iamServiceScope(descriptor, claimedScope))
                    .orElseGet(() -> catalog.canonicalCredentialScope(service));
            return new ResolvedAuthorization(servingScope,
                    IamAuthorizedActions.actionFor(servingScope, queryAction), servingScope + ":" + queryAction);
        }

        // Read from the route rather than the claim: a path the claim filter takes for RPC v2 can
        // reach another route, and an X-Amz-Target header names an operation this endpoint ignores.
        ProtocolClaim rpcV2Route = rpcV2RouteClaim(ctx);
        if (rpcV2Route != null) {
            if (rpcV2Route.service() == null) {
                return new ResolvedAuthorization(claimedScope, null); // no service: the endpoint answers 404
            }
            String servingScope = iamServiceScope(rpcV2Route.service(), claimedScope);
            return new ResolvedAuthorization(servingScope,
                    actionRegistry.resolveOperation(servingScope, rpcV2Route.operation(), ctx),
                    servingScope + ":" + rpcV2Route.operation());
        }

        ResolvedAuthorization routeAuthorization = restRouteAuthorization(ctx);
        if (routeAuthorization != null) {
            return routeAuthorization;
        }

        // A REST request is authorized by its route alone, never by an X-Amz-Target it carries.
        boolean rest = claimValue instanceof ProtocolClaim claim && claim.protocol() == WireProtocol.REST;
        String servingScope = servingCredentialScope(claimedScope, ctx);
        if (rest) {
            return new ResolvedAuthorization(servingScope, actionRegistry.resolveRoute(servingScope, ctx));
        }
        String targetOperation = IamActionRegistry.targetOperation(ctx);
        String action = actionRegistry.resolve(servingScope, ctx);
        return targetOperation == null ? new ResolvedAuthorization(servingScope, action)
                : new ResolvedAuthorization(servingScope, action, servingScope + ":" + targetOperation);
    }

    /**
     * The claim of a call JAX-RS routed to the Smithy RPC v2 endpoint, made from the service and
     * operation in the route's path parameters, which are what that endpoint dispatches. Null for
     * any other request.
     */
    private ProtocolClaim rpcV2RouteClaim(ContainerRequestContext ctx) {
        if (resourceInfo == null || !AwsJsonCborController.class.equals(resourceInfo.getResourceClass())) {
            return null;
        }
        return ProtocolClaimer.rpcV2RouteClaim(catalog, ctx.getUriInfo().getPathParameters());
    }

    /**
     * The action a REST request's matched route maps to, read from the route alone. Null for any
     * other request, and for a route no rule maps.
     */
    private ResolvedAuthorization restRouteAuthorization(ContainerRequestContext ctx) {
        if (!(ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim)
                || claim.protocol() != WireProtocol.REST
                || resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return null;
        }
        Class<?> resourceClass = resourceInfo.getResourceClass();
        if (SharedTagsController.class.equals(resourceClass) || V1TagsController.class.equals(resourceClass)) {
            return taggedServiceAuthorization(ctx);
        }
        return catalog.byResourceClass(resourceClass)
                .map(descriptor -> resolveRestAuthorization(descriptor, ctx))
                .orElse(null);
    }

    /**
     * A shared tagging route hands the request to the service its ARN names, so it is checked as that
     * service whatever scope it is signed with. A service with no rule for the route leaves it unchecked, as
     * any route no rule maps; an ARN naming no service Floci serves, which the controller refuses, is left
     * to the signing scope.
     */
    private ResolvedAuthorization taggedServiceAuthorization(ContainerRequestContext ctx) {
        String arn = arnBuilder.taggedArn(ctx);
        if (!AwsArnUtils.isArn(arn)) {
            return null;
        }
        String service = AwsArnUtils.parse(arn).service();
        return catalog.byCredentialScope(service)
                .map(descriptor -> {
                    ResolvedAuthorization authorization = resolveRestAuthorization(descriptor, ctx);
                    return authorization != null
                            ? authorization
                            : new ResolvedAuthorization(catalog.canonicalCredentialScope(service), null);
                })
                .orElse(null);
    }

    /**
     * Refuses a request whose {@code Authorization} header this filter cannot read a credential
     * from: an empty one, one naming no access key (a Bearer token, SigV2, a credential without the
     * SigV4 scheme or without its key, see {@link AccountResolver#extractAccessKeyId}), or a SigV4
     * credential scope missing a part, such as its {@code aws4_request} terminator. With no key or
     * no scope there is nothing to check, so letting it through would skip enforcement, the key
     * checks included, for any action. The IAM User Guide's "Troubleshoot SigV4" lists these
     * failures (an empty header, a missing credential, a header that does not start with an
     * algorithm name) under {@code IncompleteSignatureException}. The other services' common errors
     * call it {@code IncompleteSignature}, and S3's error list {@code AuthorizationHeaderMalformed},
     * or {@code AuthorizationQueryParametersError} for a presigned URL, all 400.
     *
     * <p>Otherwise it is treated as an unsigned request is. An operation AWS serves without
     * credentials is left alone. A REST request is refused only when it claims SigV4 and its route
     * maps to an action: AppSync, CodeArtifact's package endpoints, Identity Store's SCIM endpoint
     * and API Gateway's authorizers take Bearer and Basic tokens of their own, and this filter
     * cannot tell the API Gateway execute path, Lambda function URLs and the other paths it sees
     * that are unsigned by design from the rest. A SigV4a scope has no region segment; every
     * service takes its region from the signed set (see {@link AccountContextFilter}), and the
     * shared {@link SigV4HeaderSignatureFilter} turns non-S3 ones away when signature validation is on.
     */
    private void refuseUnreadableCredential(ContainerRequestContext ctx, String auth) {
        if (ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                && claim.protocol() != WireProtocol.REST
                && servesWithoutCredentials(claim, ctx)) {
            return;
        }
        ResolvedAuthorization routeAuthorization = claimsSigV4(auth) ? restRouteAuthorization(ctx) : null;
        if (routeAuthorization == null && !isRpcCall(ctx)) {
            return;
        }
        LOG.debugv("Rejecting a {0} request whose Authorization header carries no readable credential",
                ctx.getMethod());
        ctx.abortWith(incompleteSignatureResponse(
                routeAuthorization == null ? null : routeAuthorization.credentialScope(), ctx,
                incompleteSignatureMessage(auth)));
    }

    /**
     * The guide's own wording where it gives one: for an empty header, and for a SigV4 header
     * without its credential. It gives none for the other shapes, which get the common errors' text.
     */
    private static String incompleteSignatureMessage(String auth) {
        if (auth.isEmpty()) {
            return EMPTY_AUTHORIZATION;
        }
        if (auth.startsWith("AWS4-") && !auth.contains("Credential=")) {
            return MISSING_CREDENTIAL;
        }
        return INCOMPLETE_SIGNATURE;
    }

    /**
     * A SigV4 {@code Authorization} header, or the {@code Credential=} string
     * {@link #requestAuthorization} builds from a presigned URL.
     */
    private static boolean claimsSigV4(String auth) {
        return auth.startsWith("AWS4-") || auth.contains("Credential=");
    }

    /**
     * Whether this is a Query, JSON or CBOR call. Only an AWS API client sends one, whereas a REST
     * request may be one of the unsigned-by-design paths this filter also sees.
     */
    private static boolean isRpcCall(ContainerRequestContext ctx) {
        return IamActionRegistry.isQueryRequest(ctx)
                || ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                        && claim.protocol() != WireProtocol.REST;
    }

    private ResolvedAuthorization resolveRestAuthorization(ServiceDescriptor descriptor,
                                                            ContainerRequestContext ctx) {
        return descriptor.credentialScopes().stream()
                .map(catalog::canonicalCredentialScope)
                .distinct()
                .sorted()
                .map(scope -> new ResolvedAuthorization(scope, actionRegistry.resolveRoute(scope, ctx)))
                .filter(authorization -> authorization.action() != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * The scope and action a request is authorized as, and the operation it calls as
     * {@code <namespace>:<Operation>}, which differs from the action for one AWS authorizes under another name.
     * A REST route maps straight to its action, which names the operation.
     */
    private record ResolvedAuthorization(String credentialScope, String action, String operation) {
        ResolvedAuthorization(String credentialScope, String action) {
            this(credentialScope, action, action);
        }
    }

    /** Floci's account-root principal, which is both the caller and the request's aws:PrincipalArn. */
    private CallerArns accountRootArns(String accountId) {
        String rootArn = AwsArnUtils.Arn.global(requestPartition(), "iam", accountId, "root").toString();
        return CallerArns.accountRoot(rootArn, accountId);
    }

    /**
     * A request that passes a role to a service also needs {@code iam:PassRole} on that role (see
     * {@link PassedRoles}), checked as the caller with {@code iam:PassedToService} naming the
     * service, when AWS's Service Reference names one, and the keys that say who is calling, which
     * {@code callerArns} writes when it is known. What a request names that holds roles, an instance
     * profile, a launch template or a task definition, is looked up in the request's account, as the
     * service resolves it; one that does not exist passes nothing, and the operation reports it. Returns
     * true when the request was aborted.
     */
    private boolean abortIfRolePassDenied(ContainerRequestContext ctx, CallerContext caller, RequestPrincipal principal,
                                          CallerArns callerArns, String action, String credentialScope,
                                          String region, String accountId, String akid) {
        for (PassedRoles.PassedRole passed : PassedRoles.of(action, ctx, lookupsFor(accountId, region))) {
            Map<String, List<String>> context = new HashMap<>();
            if (callerArns != null) {
                callerArns.putPrincipalKeys(context);
            }
            if (passed.passedToService() != null) {
                context.put("iam:PassedToService", List.of(passed.passedToService()));
            }
            if (abortIfDenied(ctx, caller, principal, "iam:PassRole", action, credentialScope,
                    List.of(passed.roleArn()), List.of(context), region, accountId, akid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A request that names a Lambda network connector also needs {@code lambda:PassNetworkConnector} on it (see
     * {@link PassedNetworkConnectors}), checked as the caller on the connector as the request names it, with the
     * keys that say who is calling. A connector has no resource policy. The refusal is Lambda's, which names the
     * policy type that refused it and the ARN of a managed policy or boundary that denies. Returns true when the
     * request was aborted.
     */
    private boolean abortIfConnectorPassDenied(ContainerRequestContext ctx, CallerContext caller,
                                               CallerArns callerArns, String operation, String region,
                                               String accountId, String akid) {
        String action = PassedNetworkConnectors.ACTION;
        for (String connector : PassedNetworkConnectors.of(operation, ctx, region)) {
            Map<String, List<String>> context = new HashMap<>();
            if (callerArns != null) {
                callerArns.putPrincipalKeys(context);
            }
            Map<String, List<String>> effectiveContext = IamConditionContextResolver.withGlobalContext(
                    context, connector, region, accountId, accountId);
            if (evaluator.evaluate(caller, null, action, connector, effectiveContext) != Decision.DENY) {
                continue;
            }
            LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}", akid, action, connector);
            String user = callerArns != null ? callerArns.callerArn()
                    : AwsArnUtils.Arn.global(requestPartition(), "iam", accountId, "user/" + akid).toString();
            String reason = AccessDeniedMessages.firstRefusal(evaluator, caller,
                    iamService.heldPolicies(akid).orElse(null), action, connector, effectiveContext);
            ctx.abortWith(Response.status(403).type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", "AccessDeniedException")
                    .entity(Map.of("Message", AccessDeniedMessages.notAuthorized(user, action, connector, reason)))
                    .build());
            return true;
        }
        return false;
    }

    /**
     * The context once for each set of tags the request carries, with that set's keys where the context has
     * none of its own, so each resource's tags are checked apart; the context alone when it carries none.
     */
    private static List<Map<String, List<String>>> withRequestTags(Map<String, List<String>> context,
                                                                  List<Map<String, List<String>>> requestTags) {
        List<Map<String, List<String>>> contexts = new ArrayList<>();
        if (requestTags.isEmpty()) {
            contexts.add(context);
        }
        for (Map<String, List<String>> tags : requestTags) {
            Map<String, List<String>> withTags = context == null ? new HashMap<>() : new HashMap<>(context);
            tags.forEach(withTags::putIfAbsent);
            contexts.add(withTags);
        }
        return contexts;
    }

    /**
     * A request that tags what it creates also needs the service's tagging action (see {@link TagOnCreate}),
     * checked as the caller for each set of tags, under any spelling a handler might read: on the resource a
     * tag specification names, else on the resource a create makes under a parent its action acts on, else on the
     * resources the operation is checked on, with the set's tags as {@code aws:RequestTag/<key>} and
     * {@code aws:TagKeys}, the operation as the namespace's {@code CreateAction} key where the tagging action
     * takes one, and the keys that say who is calling. Returns true when the request was aborted.
     */
    private boolean abortIfTagOnCreateDenied(ContainerRequestContext ctx, CallerContext caller,
                                             RequestPrincipal principal, CallerArns callerArns, String action,
                                             String operation, TagOnCreate.Tagging tagging,
                                             String credentialScope, List<String> resources,
                                             String region, String accountId, String akid) {
        // A create of a resource under a parent its action acts on tags the new one (see RequestResources).
        List<String> created = RequestResources.created(operation, ctx, AwsRegions.partitionFor(region), region,
                accountId);
        List<String> made = created.isEmpty() ? resources : created;
        for (TagOnCreate.TagSet set : tagging.checked()) {
            Map<String, List<String>> context = TagOnCreate.conditionContext(tagging, set, operation);
            if (callerArns != null) {
                callerArns.putPrincipalKeys(context);
            }
            List<String> tagged = TagOnCreate.resources(operation, set, requestPartition(), region, accountId, made);
            if (abortIfDenied(ctx, caller, principal, tagging.action(), action, credentialScope, tagged,
                    List.of(context), region, accountId, akid)) {
                return true;
            }
        }
        return false;
    }

    /** What {@link PassedRoles} looks up, in the request's account and Region. */
    private PassedRoles.Lookups lookupsFor(String accountId, String region) {
        return new PassedRoles.Lookups() {
            @Override
            public List<String> instanceProfileRoleArns(String profileName, String profileArn) {
                return IamEnforcementFilter.this.instanceProfileRoleArns(accountId, profileName, profileArn);
            }

            @Override
            public List<String> taskDefinitionRoles(String reference) {
                List<String> roles = new ArrayList<>();
                for (PassedRoleLookup lookup : roleLookups()) {
                    roles.addAll(lookup.taskDefinitionRoles(reference, region));
                }
                return roles;
            }

            @Override
            public Optional<String> stackSetPermissionModel(String stackSetName) {
                return firstFound(lookup -> lookup.stackSetPermissionModel(stackSetName));
            }

            @Override
            public Optional<String> launchTemplateInstanceProfile(String templateId, String templateName,
                                                                  String version) {
                return firstFound(lookup -> lookup.launchTemplateInstanceProfile(templateId, templateName, version,
                        region));
            }

            @Override
            public Optional<String> launchConfigurationInstanceProfile(String instanceId) {
                return firstFound(lookup -> lookup.launchConfigurationInstanceProfile(instanceId, region));
            }

            @Override
            public Optional<String> instanceRefreshInstanceProfile(String groupName, String templateId,
                                                                   String templateName, String version) {
                return firstFound(lookup -> lookup.instanceRefreshInstanceProfile(groupName, templateId, templateName,
                        version, region));
            }

            @Override
            public String roleArn(String roleName) {
                return AwsArnUtils.Arn.global(requestPartition(), "iam", accountId, "role/" + roleName).toString();
            }

            @Override
            public Optional<String> heldRoleArn(String roleName) {
                return iamService.findRole(accountId, roleName).map(IamRole::getArn);
            }

            @Override
            public Optional<String> serviceCatalogConstraintType(String constraintId) {
                return firstFound(lookup -> lookup.serviceCatalogConstraintType(constraintId));
            }
        };
    }

    private Iterable<PassedRoleLookup> roleLookups() {
        return passedRoleLookups == null || passedRoleLookups.isUnsatisfied() ? List.of() : passedRoleLookups;
    }

    /** The first answer a {@link PassedRoleLookup} gives, each answering only for what its service owns. */
    private Optional<String> firstFound(Function<PassedRoleLookup, Optional<String>> question) {
        for (PassedRoleLookup lookup : roleLookups()) {
            Optional<String> answer = question.apply(lookup);
            if (answer.isPresent()) {
                return answer;
            }
        }
        return Optional.empty();
    }

    /** The ARNs of the roles the instance profile, named by {@code name} or {@code arn}, holds. */
    private List<String> instanceProfileRoleArns(String accountId, String name, String arn) {
        String profileName = arn != null ? arn.substring(arn.lastIndexOf('/') + 1) : name;
        return iamService.findInstanceProfile(accountId, profileName)
                .filter(profile -> arn == null || arn.equals(profile.getArn()))
                .map(profile -> profile.getRoleNames().stream()
                        .flatMap(roleName -> iamService.findRole(accountId, roleName).stream())
                        .map(IamRole::getArn)
                        .toList())
                .orElse(List.of());
    }

    /**
     * Evaluates one action against every resource and target context, aborting the request with
     * AccessDenied on the first DENY. Returns true when the request was aborted.
     */
    private boolean abortIfDenied(ContainerRequestContext ctx, CallerContext caller, RequestPrincipal principal,
                                  String action, String credentialScope, List<String> resources,
                                  List<Map<String, List<String>>> targetContexts,
                                  String region, String accountId, String akid) {
        return abortIfDenied(ctx, caller, principal, action, action, credentialScope, resources, targetContexts,
                region, accountId, akid);
    }

    /**
     * Checks {@code action}, which may be one the operation needs besides its own. A denial names
     * {@code action}, the permission that is missing, while the CloudTrail event records
     * {@code operationAction}, the operation that was refused.
     */
    private boolean abortIfDenied(ContainerRequestContext ctx, CallerContext caller, RequestPrincipal principal,
                                  String action, String operationAction, String credentialScope,
                                  List<String> resources, List<Map<String, List<String>>> targetContexts,
                                  String region, String accountId, String akid) {
        for (String resource : resources) {
            List<ResourcePolicyProvider.ResourcePolicy> resourcePolicies =
                    resolveResourcePolicies(credentialScope, operationAction, resource);
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

            List<Map<String, List<String>>> batchContexts = "dynamodb".equals(credentialScope)
                    ? conditionContextResolver.dynamoDbBatchConditionContexts(action, ctx, resource) : null;
            List<Map<String, List<String>>> resourceContexts = targetContexts;
            if (batchContexts != null && !batchContexts.isEmpty()) {
                resourceContexts = new ArrayList<>();
                for (Map<String, List<String>> targetContext : targetContexts) {
                    for (Map<String, List<String>> batchContext : batchContexts) {
                        Map<String, List<String>> entryContext = targetContext == null
                                ? new HashMap<>() : new HashMap<>(targetContext);
                        entryContext.remove("dynamodb:LeadingKeys");
                        entryContext.remove("dynamodb:Attributes");
                        entryContext.remove("dynamodb:Select");
                        entryContext.putAll(batchContext);
                        resourceContexts.add(entryContext);
                    }
                }
            }
            for (Map<String, List<String>> targetContext : resourceContexts) {
                Map<String, List<String>> effectiveContext = IamConditionContextResolver.withGlobalContext(
                        targetContext, resource, region, accountId, resourceOwnerAccountId);
                ResourcePolicyDecision resourcePolicyDecision = evaluator.evaluateResourcePolicyFor(
                        effectiveResourcePolicies, principal, action, resource, effectiveContext);
                Decision decision = evaluator.evaluateResolvedResourcePolicy(
                        caller, resourcePolicyDecision, accountRelationship, action, resource, effectiveContext);
                if (decision != Decision.DENY) {
                    continue;
                }
                LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}", akid, action, resource);
                String denyMessage = "User: " + AwsArnUtils.Arn.global(requestPartition(), "iam", accountId, "user/" + akid)
                        + " is not authorized to perform: " + action
                        + " on resource: \"" + resource + "\""
                        + " " + PolicyType.IDENTITY_BASED_POLICY.noAllow(action);
                emitS3DenialIfApplicable(akid, operationAction, resource, ctx, region, denyMessage);
                ctx.abortWith(accessDeniedForRequest(action, credentialScope, ctx, resource));
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
     * <p>{@link WireProtocol#AWS_QUERY} is resolved before this method by
     * {@link #resolveAuthorization(String, String, ContainerRequestContext)}, which shares
     * {@link AwsQueryServiceResolver} with the controller so IAM enforcement follows the service
     * that will handle the request.
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
            return catalog.canonicalCredentialScope(descriptor.externalKey());
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
                            || key.startsWith(IamConditionContextResolver.REQUEST_OBJECT_TAG_PREFIX)
                            || key.equals(IamConditionContextResolver.REQUEST_OBJECT_TAG_KEYS));
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
     * shape as {@link #filter} when the caller's policies deny the action, and with the
     * codes {@link #filter} answers when the access key is inactive or the session has expired.
     *
     * <p>The account used for policy evaluation is always re-resolved from {@code akid} here
     * (see {@link #resolveCredentialAccountId}) rather than trusted from {@link RequestContext},
     * because a presigned POST's credential is invisible to {@code AccountContextFilter} - it
     * arrives only in the multipart body, parsed well after that filter already set the ambient
     * account to the configured default. The resolved account is pushed onto {@link RequestContext}
     * for the duration of this call so that {@link IamService#resolveCallerContext} and
     * {@link IamService#resolveCallerArn}, which both key their per-account lookups off the
     * ambient account, resolve the credential's actual owner instead of the default account.
     *
     * <p>The caller supplies no resource-policy decision here, so this overload resolves the
     * applicable resource policies itself through {@link ResourcePolicyProvider}, exactly as
     * {@link #filter} does for the request's primary resource. Skipping that resolution would
     * make the resource policy invisible on this path and leave a same-account principal that
     * only a resource policy names without any grant at all.
     */
    public void authorizeAdditionalResource(String authorizationHeader, String action, String resource) {
        authorizeAdditionalResource(authorizationHeader, action, resource, null, null);
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
     *
     * <p>A {@code null} {@code resourcePolicyDecision} means the caller has no decision of its
     * own and the registered {@link ResourcePolicyProvider}s are consulted here instead, under
     * the credential's own account context.
     *
     * <p>The condition context built here never includes per-action, header-derived keys (such as
     * {@code s3:RequestObjectTag/*} from an {@code x-amz-tagging} header) the way {@link #filter}
     * builds for the request's primary resource. A presigned POST's outer HTTP headers are not
     * covered by its signed policy document and do not correspond to what {@code S3Controller}
     * actually applies to the uploaded object, so trusting one here would let an attacker satisfy a
     * tag-scoped grant condition with a header the object is never actually tagged with. Only the
     * caller-identity {@code aws:PrincipalArn} and the server-derived global keys (via {@link
     * IamConditionContextResolver#withGlobalContext}) are safe to add.
     */
    public void authorizeAdditionalResource(
            String authorizationHeader,
            String action,
            String resource,
            ResourcePolicyDecision resourcePolicyDecision,
            String resourceOwnerAccountId) {
        authorizeAdditionalResource(authorizationHeader, action, resource, resourcePolicyDecision,
                resourceOwnerAccountId, null);
    }

    /**
     * Authorizes a browser POST upload, a PostObject, as {@code s3:PutObject} on {@code resource}.
     * Its credential is only in the form body, so {@link #filter} never sees it, and a denial is
     * recorded in CloudTrail here, under {@code PostObject}, as the denials {@link #filter} refuses
     * are recorded under their operation. AWS's S3 developer guide lists PostObject among "the
     * object-level API actions that are logged as CloudTrail events". The denial is recorded in
     * {@code region}, the one the form's credential names, as a presigned URL's is in the one its
     * query credential names.
     */
    public void authorizePostObject(String authorizationHeader, String resource, String region) {
        authorizeAdditionalResource(authorizationHeader, "s3:PutObject", resource, null, null,
                new DeniedEvent("PostObject", region));
    }

    /** A CloudTrail record to write when IAM refuses a request: its event name and its Region. */
    private record DeniedEvent(String name, String region) {
    }

    /** As the public overload; a denial is also recorded in CloudTrail when {@code deniedEvent} is given. */
    private void authorizeAdditionalResource(
            String authorizationHeader,
            String action,
            String resource,
            ResourcePolicyDecision resourcePolicyDecision,
            String resourceOwnerAccountId,
            DeniedEvent deniedEvent) {
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
        String rawScope = extractCredentialScope(authorizationHeader);
        if (rawScope == null) {
            return;
        }
        // Canonicalised as filter() does, so a signing alias (s3express) is refused below with its
        // canonical service's (s3) error codes.
        String credentialScope = catalog.canonicalCredentialScope(rawScope);

        String accountId = resolveCredentialAccountId(akid, authorizationHeader);
        String previousAccountId = requestContext.getAccountId();
        requestContext.setAccountId(accountId);
        try {
            // A key IAM holds but that can no longer be used is refused here as filter() refuses it,
            // for the requests whose key filter() has not checked: a presigned POST, whose credential
            // is only in the form body, and DeleteObjects, a route no action maps, where filter()
            // returns before its key checks. Otherwise an inactive key would act with its user's
            // policies, and resolveCallerContext would delete an expired session, which would then
            // read as a key that exists nowhere. For a second resource of a request filter() has
            // authorized, the key has already passed these checks.
            boolean accountRootKey = akid.equals(accountId);
            if (!accountRootKey && iamService.isInactiveAccessKey(akid)) {
                throw unrecognizedClientException(credentialScope);
            }
            // One moment for both lookups, as in filter(): a session expiring between them would
            // otherwise be deleted and then allowed below as a credential with no context.
            Instant now = clock.instant();
            if (iamService.isExpiredSession(akid, now)) {
                throw expiredTokenException(credentialScope);
            }

            List<List<String>> scpLevels = scpProvider.isResolvable()
                    ? scpProvider.get().effectiveScpLevels(accountId) : null;

            boolean accountRootPrincipal = false;
            CallerContext caller = iamService.resolveCallerContext(akid, now);
            if (caller == null) {
                // No unknown-key rejection here, unlike filter(). For a second resource of a request
                // filter() has authorized (CopyObject's source, RotateSecret's rotation function, the
                // secrets behind SSM GetParameter) the credential is the one filter() evaluated, and
                // filter() refuses an unknown key before the handler runs. Two callers reach here with
                // a key filter() has not checked: a presigned POST, whose credential is only in the
                // form body, and DeleteObjects, where filter() returns before its key checks because
                // no action maps the route. For both, S3's own request authentication refuses an
                // unknown key when S3 auth enforcement is on; with it off, the request proceeds as an
                // unsigned one would.
                if (scpLevels == null || !accountRootKey) {
                    return;
                }
                caller = CallerContext.of(List.of(ROOT_ALLOW_ALL));
                accountRootPrincipal = true;
            }
            if (scpLevels != null) {
                caller = caller.withScpLevels(scpLevels);
            }

            Map<String, List<String>> conditionContext = null;
            Optional<CallerArns> callerArns = accountRootPrincipal
                    ? Optional.of(accountRootArns(accountId))
                    : iamService.resolveCallerArns(akid);
            if (callerArns.isPresent()) {
                caller = caller.withPrincipalArn(callerArns.get().callerArn());
                conditionContext = new HashMap<>();
                callerArns.get().putPrincipalKeys(conditionContext);
            }

            ResourcePolicyDecision effectiveDecision = resourcePolicyDecision;
            String effectiveOwnerAccountId = resourceOwnerAccountId;
            List<String> policyDocs = null;
            if (effectiveDecision == null) {
                // Resource policies belong to the service that owns the resource, which is the action's
                // service, not whichever service the caller signed for. filter() picks them by the route's
                // serving service for the same reason; a credential scoped to another service, or to a
                // signing alias such as s3express, would otherwise see no bucket policy at all.
                String resourcePolicyScope = catalog.canonicalCredentialScope(action.split(":", 2)[0]);
                List<ResourcePolicyProvider.ResourcePolicy> resourcePolicies = resolveResourcePolicies(
                        resourcePolicyScope, action, resource);
                effectiveOwnerAccountId = resourcePolicies.isEmpty()
                        ? null : resourcePolicies.getFirst().ownerAccountId();
                policyDocs = resourcePolicies.stream()
                        .map(ResourcePolicyProvider.ResourcePolicy::policyDocument)
                        .filter(doc -> doc != null && !doc.isBlank())
                        .toList();
            }
            String region = requestContext.getRegion() == null
                    ? config.defaultRegion() : requestContext.getRegion();
            Map<String, List<String>> effectiveContext = IamConditionContextResolver.withGlobalContext(
                    conditionContext, resource, region, accountId, effectiveOwnerAccountId);
            if (effectiveDecision == null) {
                effectiveDecision = evaluator.evaluateResourcePolicyFor(
                        policyDocs.isEmpty() ? null : policyDocs,
                        RequestPrincipal.caller(callerArns.orElse(null)), action, resource, effectiveContext);
            }

            ResourceAccountRelationship accountRelationship = effectiveOwnerAccountId == null
                    || accountId.equals(effectiveOwnerAccountId)
                    ? ResourceAccountRelationship.SAME_ACCOUNT
                    : ResourceAccountRelationship.CROSS_ACCOUNT;
            Decision decision = evaluator.evaluateResolvedResourcePolicy(
                    caller, effectiveDecision, accountRelationship,
                    action, resource, effectiveContext);
            if (decision != Decision.DENY) {
                return;
            }
            LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}", akid, action, resource);
            String denyMessage = "User: " + AwsArnUtils.Arn.global(requestPartition(), "iam", accountId, "user/" + akid)
                    + " is not authorized to perform: " + action
                    + " on resource: \"" + resource + "\""
                    + " " + PolicyType.IDENTITY_BASED_POLICY.noAllow(action);
            if (deniedEvent != null) {
                emitS3Denial(akid, deniedEvent.name(), resource, deniedEvent.region(), denyMessage);
            }
            throw new AwsException("AccessDenied", denyMessage, 403);
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
            String eventName = mapS3ActionToEventName(action, ctx.getMethod(),
                    ctx.getUriInfo().getQueryParameters().keySet());
            if (eventName != null) {
                emitS3Denial(akid, eventName, resource, region, denyMessage);
            }
        } catch (RuntimeException e) {
            // The request is refused either way; a record that could not be written is logged, not raised.
            LOG.warnv(e, "CloudTrail denial emission failed for {0} on {1}", action, resource);
        }
    }

    /** Records an S3 request IAM refused as a CloudTrail data event named {@code eventName}. */
    private void emitS3Denial(String akid, String eventName, String resource, String region, String denyMessage) {
        try {
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
                LOG.tracev(e, "CloudTrail: could not extract request context for IAM denial {0} on {1}", eventName, resource);
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
            LOG.warnv(e, "CloudTrail denial emission failed for {0} on {1}", eventName, resource);
        }
    }

    // Package-private for unit testing.
    static String mapS3ActionToEventName(String action, String httpMethod, Set<String> queryParameters) {
        if (action == null) return null;
        // Action set sourced from IamActionRegistry — see that file for any
        // additions. HEAD on an object is bucketed under s3:GetObject by the
        // registry, so we distinguish via httpMethod. The GetObjectAttributes actions
        // are the operation its own check refuses.
        if ("POST".equalsIgnoreCase(httpMethod)) {
            return postObjectEventName(action, queryParameters);
        }
        return switch (action) {
            case "s3:GetObject", "s3:GetObjectVersion" ->
                    "HEAD".equalsIgnoreCase(httpMethod) ? "HeadObject" : "GetObject";
            case "s3:PutObject" -> "PutObject";
            case "s3:PutObjectRetention" -> "PutObjectRetention";
            case "s3:GetObjectAttributes", "s3:GetObjectVersionAttributes" -> "GetObjectAttributes";
            case "s3:DeleteObject", "s3:DeleteObjectVersion" -> "DeleteObject";
            case "s3:ListBucket" -> "ListObjects";
            case "s3:ListAllMyBuckets" -> "ListBuckets";
            case "s3:GetObjectAcl", "s3:GetObjectVersionAcl" -> "GetObjectAcl";
            case "s3:PutObjectAcl", "s3:PutObjectVersionAcl" -> "PutObjectAcl";
            case "s3:GetObjectTagging", "s3:GetObjectVersionTagging" -> "GetObjectTagging";
            case "s3:PutObjectTagging", "s3:PutObjectVersionTagging" -> "PutObjectTagging";
            case "s3:DeleteObjectTagging", "s3:DeleteObjectVersionTagging" -> "DeleteObjectTagging";
            default -> null;
        };
    }

    /**
     * The operation a denied POST on an object names. Its action alone is not enough: CreateMultipartUpload
     * and CompleteMultipartUpload are both checked as {@code s3:PutObject}, and SelectObjectContent as
     * {@code s3:GetObject}. The sub-resource that chose the action in {@code IamActionRegistry} names the
     * operation, and {@code ?uploads}, which S3Controller runs ahead of an {@code uploadId}, makes a
     * request naming both a create. A POST naming none gets no name rather than a wrong one.
     */
    private static String postObjectEventName(String action, Set<String> queryParameters) {
        return switch (action) {
            case "s3:PutObject" -> {
                if (queryParameters.contains("uploads")) {
                    yield "CreateMultipartUpload";
                }
                yield queryParameters.contains("uploadId") ? "CompleteMultipartUpload" : null;
            }
            case "s3:RestoreObject" -> queryParameters.contains("restore") ? "RestoreObject" : null;
            case "s3:GetObject", "s3:GetObjectVersion" ->
                    queryParameters.contains("select") ? "SelectObjectContent" : null;
            default -> null;
        };
    }

    /**
     * Logs, Secrets Manager and KMS targets depend on the serving operation and the request's partition, STS
     * targets on the serving operation;
     * Scheduler and Pipes targets on the request's partition, which those services mint their ARNs in; and the
     * targets of a namespace the table of request resources reads on {@code operation}, the one the request is
     * dispatched as (see {@link RequestResources}).
     */
    private List<String> targetResources(String credentialScope, String action, String operation,
                                         ContainerRequestContext ctx, String region, String accountId) {
        if ("logs".equals(credentialScope)) {
            return arnBuilder.buildLogResources(action, ctx, region, accountId, requestPartition());
        }
        if ("secretsmanager".equals(credentialScope)) {
            return arnBuilder.buildSecretsManagerResources(action, ctx, region, accountId, requestPartition());
        }
        if ("kms".equals(credentialScope)) {
            return arnBuilder.buildKmsResources(action, ctx, region, accountId, requestPartition());
        }
        if ("sts".equals(credentialScope)) {
            return arnBuilder.buildStsResources(action, ctx);
        }
        if ("scheduler".equals(credentialScope) || "pipes".equals(credentialScope) || "dsql".equals(credentialScope)) {
            return arnBuilder.buildResources(credentialScope, ctx, region, accountId, requestPartition());
        }
        return arnBuilder.buildResources(credentialScope, operation, ctx, region, accountId,
                AwsRegions.partitionFor(region));
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
        return SigV4CredentialScope.serviceName(auth).orElse(null);
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
     *
     * <p>Public so that a handler authorizing a secondary resource through
     * {@link #authorizeAdditionalResource} hands it the same caller this filter evaluated, whichever
     * way the request was signed.
     *
     * @return the {@code Authorization} header, else a {@code Credential=...} string built from
     *         {@code X-Amz-Credential}, else {@code null}
     */
    public static String requestAuthorization(String authorizationHeader,
                                              MultivaluedMap<String, String> queryParameters) {
        if (authorizationHeader != null) {
            return authorizationHeader;
        }
        String credential = queryParameters == null ? null : queryParameters.getFirst("X-Amz-Credential");
        if (credential == null || credential.isBlank()) {
            return null;
        }
        // The scheme makes AccountResolver recognise a credential that did not come from the header.
        return AccountResolver.SIGV4_SCHEME + " Credential=" + credential;
    }

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

    private List<ResourcePolicyProvider.ResourcePolicy> resolveResourcePolicies(
            String credentialScope, String action, String resourceArn) {
        return ResourcePolicyProviders.policiesFor(resourcePolicyProviders, credentialScope, action, resourceArn);
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
     *       {@code <ErrorResponse>...</ErrorResponse>} (IAM/STS/EC2/SQS/SNS/...), with the code
     *       {@link #queryAccessDeniedCode} gives the service that serves the request</li>
     *   <li>JSON 1.x: JSON with HTTP 400; REST-JSON: JSON with HTTP 403</li>
     *   <li>CBOR: the request's own encoding with HTTP 403, from {@link #accessDeniedForRequest},
     *       which has the request to read it from</li>
     * </ul>
     */
    // Package-private for unit testing.
    static Response accessDeniedResponse(String action, String credentialScope, MediaType requestMediaType) {
        return accessDeniedResponse(action, credentialScope, requestMediaType, null);
    }

    static Response accessDeniedResponse(String action, String credentialScope, MediaType requestMediaType, String resourceArn) {
        return accessDeniedResponse(action, credentialScope, requestMediaType, resourceArn, WireProtocol.REST);
    }

    private static Response accessDeniedForRequest(String action, String credentialScope, ContainerRequestContext ctx) {
        return accessDeniedForRequest(action, credentialScope, ctx, null);
    }

    private static Response accessDeniedForRequest(String action, String credentialScope,
                                                 ContainerRequestContext ctx, String resourceArn) {
        WireProtocol protocol = claimedProtocol(ctx);
        if (isCbor(protocol)) {
            // 403, the status the Kinesis and CloudWatch common errors give AccessDeniedException.
            return cborError(ctx, "AccessDeniedException", accessDeniedMessage(action), 403);
        }
        // The route decides the envelope, not the resolved action: a request on an S3 Control
        // route is S3 Control's however its action reads, and only its ErrorResponse wrapper
        // reaches the s3control SDK deserializers (issue #557).
        if (IamActionRegistry.isS3ControlRequest(credentialScope, ctx)) {
            return s3ControlXmlAccessDenied(accessDeniedMessage(action));
        }
        return accessDeniedResponse(action, credentialScope, ctx.getMediaType(), resourceArn, protocol);
    }

    private static String accessDeniedMessage(String action) {
        return "User is not authorized to perform: " + action;
    }

    static Response accessDeniedResponse(String action, String credentialScope, MediaType requestMediaType,
                                         String resourceArn, WireProtocol protocol) {
        String message = accessDeniedMessage(action);
        if ("s3".equals(credentialScope)) {
            String resourcePath = formatS3ResourcePath(resourceArn);
            return s3XmlAccessDenied(message, resourcePath);
        }
        if (protocol == WireProtocol.AWS_QUERY || isFormEncoded(requestMediaType)) {
            return queryXmlError(queryAccessDeniedCode(credentialScope), message);
        }
        int status = protocol == WireProtocol.AWS_JSON_1_0 || protocol == WireProtocol.AWS_JSON_1_1 ? 400 : 403;
        return jsonAccessDenied(message, status);
    }

    /**
     * The code a Query service refuses a request it does not authorize with, whichever action was denied:
     * SNS's {@code AuthorizationError}, a 403 every SNS operation lists ("Indicates that the user has been
     * denied access to the requested resource") and the one SDKs raise as {@code AuthorizationErrorException},
     * and {@code AccessDenied} for every other service.
     */
    private static String queryAccessDeniedCode(String credentialScope) {
        return "sns".equals(credentialScope) ? "AuthorizationError" : "AccessDenied";
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
        return queryXmlError(code, message, 403);
    }

    private static Response queryXmlError(String code, String message, int status) {
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
        return Response.status(status).type(MediaType.APPLICATION_XML).entity(xml).build();
    }

    private static Response s3XmlAccessDenied(String message) {
        return s3XmlAccessDenied(message, null);
    }

    private static Response s3XmlError(String code, String message, int status) {
        return s3Xml(code, message, null, status);
    }

    private static Response s3XmlAccessDenied(String message, String resourcePath) {
        return s3Xml("AccessDenied", message, resourcePath, 403);
    }

    /**
     * S3 Control's own access-denied answer, which unlike S3's error body wraps the error in an
     * {@code <ErrorResponse>} the s3control SDK deserializers read (issue #557): the bare
     * {@code <Error>} S3 answers with collapses to {@code UnknownError} there, hiding the message
     * that names the action a caller is missing, which Terraform's AWS provider matches to fall
     * back from {@code ListTagsForResource} to {@code GetBucketTagging}. The same envelope as
     * {@code S3ControlController}'s, and no {@code Resource} element: the ARN a tagging route
     * names is the resource, not a bucket and key.
     */
    private static Response s3ControlXmlAccessDenied(String message) {
        String requestId = UUID.randomUUID().toString();
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ErrorResponse", AwsNamespaces.S3_CONTROL)
                .start("Error")
                .elem("Code", "AccessDenied")
                .elem("Message", message)
                .elem("RequestId", requestId)
                .end("Error")
                .elem("RequestId", requestId)
                .end("ErrorResponse")
                .build();
        return Response.status(403).type(MediaType.APPLICATION_XML).entity(xml).build();
    }

    private static Response s3Xml(String code, String message, String resourcePath, int status) {
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
        return Response.status(status).type(MediaType.APPLICATION_XML).entity(xml.build()).build();
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
    static Response unrecognizedClientResponse(String credentialScope, ContainerRequestContext ctx) {
        if ("s3".equals(credentialScope)) {
            return s3XmlError("InvalidAccessKeyId", S3_INVALID_ACCESS_KEY_ID, 403);
        }
        return protocolError(claimedProtocol(ctx), ctx, "InvalidClientTokenId", "UnrecognizedClientException",
                INVALID_SECURITY_TOKEN, 403);
    }

    /** {@link #unrecognizedClientResponse} for a caller that renders the error itself. */
    private static AwsException unrecognizedClientException(String credentialScope) {
        return "s3".equals(credentialScope)
                ? new AwsException("InvalidAccessKeyId", S3_INVALID_ACCESS_KEY_ID, 403)
                : new AwsException("UnrecognizedClientException", INVALID_SECURITY_TOKEN, 403);
    }

    /**
     * The response for a session whose temporary credentials have expired. S3's error list names it
     * {@code ExpiredToken}, 400. The other services' common errors name it
     * {@code ExpiredTokenException}, 403, under the same name for Query services as for JSON ones.
     */
    static Response expiredTokenResponse(String credentialScope, ContainerRequestContext ctx) {
        if ("s3".equals(credentialScope)) {
            return s3XmlError("ExpiredToken", S3_EXPIRED_TOKEN, 400);
        }
        return protocolError(claimedProtocol(ctx), ctx, "ExpiredTokenException", "ExpiredTokenException",
                EXPIRED_TOKEN, 403);
    }

    /** {@link #expiredTokenResponse} for a caller that renders the error itself. */
    private static AwsException expiredTokenException(String credentialScope) {
        return "s3".equals(credentialScope)
                ? new AwsException("ExpiredToken", S3_EXPIRED_TOKEN, 400)
                : new AwsException("ExpiredTokenException", EXPIRED_TOKEN, 403);
    }

    /**
     * The response for a signature that does not follow AWS's format, from
     * {@link #refuseUnreadableCredential}. S3 answers with {@code AuthorizationHeaderMalformed}
     * and the wording {@code S3HeaderSignatureFilter} uses for a mal-formed credential, so S3 answers
     * the same whichever check catches it. A presigned URL sends no header: its credential is the
     * {@code X-Amz-Credential} parameter, and S3 answers a mal-formed one with
     * {@code AuthorizationQueryParametersError}. Other services answer with
     * {@code IncompleteSignature}, spelled with the {@code Exception} suffix outside Query as the
     * other codes here are, and the {@code message} the caller picked. All 400.
     */
    static Response incompleteSignatureResponse(String credentialScope, ContainerRequestContext ctx,
                                                String message) {
        if ("s3".equals(credentialScope)) {
            String header = ctx.getHeaderString("Authorization");
            if (header == null || header.isBlank()) {
                return s3XmlError("AuthorizationQueryParametersError", "Error parsing the X-Amz-Credential "
                        + "parameter; the Credential is mal-formed; expecting "
                        + "\"<YOUR-AKID>/YYYYMMDD/REGION/SERVICE/aws4_request\".", 400);
            }
            return s3XmlError("AuthorizationHeaderMalformed", "The authorization header is malformed; "
                    + "the Credential is mal-formed; expecting "
                    + "\"<YOUR-AKID>/YYYYMMDD/REGION/SERVICE/aws4_request\".", 400);
        }
        return protocolError(claimedProtocol(ctx), ctx, "IncompleteSignature", "IncompleteSignatureException",
                message, 400);
    }

    /**
     * Refuses an unsigned request whose wire shape makes it a management API call. A JSON, CBOR or
     * Query claim exists only for a request carrying {@code X-Amz-Target}, an rpcv2 path or an
     * {@code Action} parameter, and no public data plane speaks any of those, so an unsigned one is
     * an unauthenticated call on a control-plane API.
     *
     * <p>REST is deliberately left alone. This single filter also sees the API Gateway execute
     * path, Lambda function URLs, CloudFront serving, the Cognito OIDC endpoints and Floci's own
     * health endpoint, all unsigned by design, and a {@code rest()} claim cannot tell those from an
     * unsigned S3 call. Separating them needs the route to name its service, which the catalog does
     * not yet carry for the REST services; that half is tracked with the other route-derived gap.
     */
    private void refuseUnsignedManagementCall(ContainerRequestContext ctx) {
        if (!(ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim)
                || claim.protocol() == WireProtocol.REST
                || servesWithoutCredentials(claim, ctx)) {
            return;
        }
        LOG.debugv("Refusing unsigned {0} request under IAM enforcement", claim.protocol());
        ctx.abortWith(missingAuthenticationTokenResponse(claim.protocol(), ctx));
    }

    /** Whether the operation this request names is one AWS serves without credentials. */
    private boolean servesWithoutCredentials(ProtocolClaim claim, ContainerRequestContext ctx) {
        if (claim.protocol() == WireProtocol.AWS_QUERY) {
            String operation = queryOperation(ctx);
            return operation != null && NO_AUTH_QUERY_OPERATIONS.contains(operation);
        }
        if (claim.service() == null || claim.operation() == null) {
            // Nothing names the operation, so there is nothing to match against the list. Refusing
            // is the safe side of that: an unsigned request whose shape cannot even be read is not
            // one of the public flows.
            return false;
        }
        return NO_AUTH_OPERATIONS
                .getOrDefault(claim.service().externalKey(), Set.of())
                .contains(claim.operation());
    }

    /**
     * The {@code Action} a Query request names, via the registry so the form body is read and put
     * back the one way this codebase already does it.
     */
    private String queryOperation(ContainerRequestContext ctx) {
        return actionRegistry.queryAction(ctx);
    }

    /**
     * AWS answers a request carrying no credentials with {@code MissingAuthenticationToken} at 403,
     * "The request must contain a valid AWS access key ID or X.509 certificate." The JSON form of
     * the name carries the {@code Exception} suffix, as {@code ApiGatewayExecuteController} already
     * returns for the same failure on its own path.
     */
    static Response missingAuthenticationTokenResponse(WireProtocol protocol, ContainerRequestContext ctx) {
        return protocolError(protocol, ctx, "MissingAuthenticationToken", "MissingAuthenticationTokenException",
                MISSING_AUTHENTICATION_TOKEN, 403);
    }

    private static WireProtocol claimedProtocol(ContainerRequestContext ctx) {
        return ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                ? claim.protocol() : WireProtocol.REST;
    }

    /**
     * An authentication failure outside S3, in the shape the caller's protocol reads: a Query
     * {@code ErrorResponse} for a form-encoded request, the request's own encoding for a CBOR
     * call, and JSON otherwise. Query names some failures without the {@code Exception} suffix, and
     * some differently altogether, so it takes its own code.
     */
    private static Response protocolError(WireProtocol protocol, ContainerRequestContext ctx, String queryCode,
                                          String code, String message, int status) {
        if (isCbor(protocol)) {
            return cborError(ctx, code, message, status);
        }
        if (protocol == WireProtocol.AWS_QUERY || isFormEncoded(ctx.getMediaType())) {
            return queryXmlError(queryCode, message, status);
        }
        return jsonError(code, message, status);
    }

    private static boolean isCbor(WireProtocol protocol) {
        return protocol == WireProtocol.RPCV2_CBOR || protocol == WireProtocol.AWS_CBOR_TARGET;
    }

    /**
     * A CBOR client cannot read a JSON body, so a rejection has to arrive in the encoding the
     * request used, the same shape the rpcv2 controller returns for its own errors.
     */
    private static Response cborError(ContainerRequestContext ctx, String code, String message, int status) {
        String requestContentType = ctx.getHeaderString(AwsCborContentTypeFilter.ORIGINAL_CONTENT_TYPE_HEADER);
        if (requestContentType == null) {
            requestContentType = ctx.getHeaderString("Content-Type");
        }
        return CborErrorResponses.of(new AwsException(code, message, status),
                CborErrorResponses.mediaTypeFor(requestContentType));
    }

    private static Response jsonAccessDenied(String message, int status) {
        return jsonError("AccessDeniedException", message, status);
    }

    /** A JSON error in the shape {@link AwsExceptionMapper} gives every other JSON failure. */
    private static Response jsonError(String code, String message, int status) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(new AwsErrorResponse(code, message)).build();
    }
}
