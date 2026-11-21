package io.floci.aws.services.iam;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsNamespaces;
import io.floci.aws.core.common.AwsQueryController;
import io.floci.aws.core.common.AwsQueryLists;
import io.floci.aws.core.common.AwsQueryResponse;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.IamConditionContextResolver;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.OidcIssuerKeyLookup;
import io.floci.aws.core.common.QueryConstraintCheck;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestMfaClaim;
import io.floci.aws.core.common.ResteasyRequests;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.common.WebIdentityToken;
import io.floci.aws.core.common.WebIdentityTokenVerifier;
import io.floci.aws.core.common.XmlBuilder;
import io.floci.aws.services.iam.model.DelegationRequest;
import io.floci.aws.services.iam.model.IamPolicy;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.OutboundWebIdentityFederation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Query-protocol handler for STS (Security Token Service) actions.
 * Receives pre-dispatched calls from {@link AwsQueryController}.
 * All responses use the STS XML namespace {@code https://sts.amazonaws.com/doc/2011-06-15/}.
 */
@ApplicationScoped
public class StsQueryHandler {

    private static final Logger LOG = Logger.getLogger(StsQueryHandler.class);
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    /** Where the five task policies the guide names for a privileged session live. */
    private static final String ROOT_TASK_POLICY_PATH = "/root-task/";
    /** "the maximum session duration of 900 seconds (15 minutes)", which is also the default here. */
    private static final int MAX_ROOT_SESSION_SECONDS = 900;
    /** "If not specified, the default duration is 300 seconds (5 minutes)." */
    private static final int DEFAULT_TOKEN_SECONDS = 300;
    /** The session token this emulator has always minted, and the floor a request cannot go under. */
    private static final int SESSION_TOKEN_LENGTH = 200;
    private static final String STS_AUDIENCE = "sts.amazonaws.com"; // partition-literal: web-identity audience; no source outside the commercial partition (P9)

    private final IamService iamService;
    private final AccountResolver accountResolver;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final AssumeRolePolicyEvaluator trustPolicyEvaluator;
    private final WebIdentityTrustPolicyEvaluator webIdentityTrustEvaluator;
    private final WebIdentityTokenVerifier tokenVerifier;
    private final OidcIssuerKeyLookup oidcIssuerKeys;
    private final SAMLProviderService samlProviderService;
    private final SAMLTrustPolicyEvaluator samlTrustEvaluator;
    private final DelegationRequestService delegationRequestService;
    private final CentralRootAccess centralRootAccess;
    private final WebIdentityTokenIssuer tokenIssuer;
    private final RequestContext requestContext;
    private final Clock clock;

    /** CSPRNG for session secret keys and session tokens; ordinary IDs keep using {@link ThreadLocalRandom}. */
    private final SecureRandom secureRandom = new SecureRandom();

    @Context
    HttpHeaders headers;

    @Context
    UriInfo uriInfo;

    @Inject
    public StsQueryHandler(IamService iamService, AccountResolver accountResolver, RegionResolver regionResolver,
                           EmulatorConfig config, AssumeRolePolicyEvaluator trustPolicyEvaluator,
                           WebIdentityTrustPolicyEvaluator webIdentityTrustEvaluator,
                           WebIdentityTokenVerifier tokenVerifier,
                           OidcIssuerKeyLookup oidcIssuerKeys,
                           SAMLProviderService samlProviderService,
                           SAMLTrustPolicyEvaluator samlTrustEvaluator,
                           DelegationRequestService delegationRequestService,
                           CentralRootAccess centralRootAccess,
                           WebIdentityTokenIssuer tokenIssuer,
                           RequestContext requestContext) {
        this(iamService, accountResolver, regionResolver, config, trustPolicyEvaluator, webIdentityTrustEvaluator,
                tokenVerifier, oidcIssuerKeys, samlProviderService, samlTrustEvaluator, delegationRequestService,
                centralRootAccess, tokenIssuer, requestContext, Clock.systemUTC());
    }

    StsQueryHandler(IamService iamService, AccountResolver accountResolver, RegionResolver regionResolver,
                    EmulatorConfig config, AssumeRolePolicyEvaluator trustPolicyEvaluator,
                    WebIdentityTrustPolicyEvaluator webIdentityTrustEvaluator,
                    WebIdentityTokenVerifier tokenVerifier,
                    OidcIssuerKeyLookup oidcIssuerKeys,
                    SAMLProviderService samlProviderService,
                    SAMLTrustPolicyEvaluator samlTrustEvaluator,
                    DelegationRequestService delegationRequestService,
                    CentralRootAccess centralRootAccess,
                    WebIdentityTokenIssuer tokenIssuer,
                    RequestContext requestContext,
                    Clock clock) {
        this.iamService = iamService;
        this.accountResolver = accountResolver;
        this.regionResolver = regionResolver;
        this.config = config;
        this.trustPolicyEvaluator = trustPolicyEvaluator;
        this.webIdentityTrustEvaluator = webIdentityTrustEvaluator;
        this.tokenVerifier = tokenVerifier;
        this.oidcIssuerKeys = oidcIssuerKeys;
        this.samlProviderService = samlProviderService;
        this.samlTrustEvaluator = samlTrustEvaluator;
        this.delegationRequestService = delegationRequestService;
        this.centralRootAccess = centralRootAccess;
        this.tokenIssuer = tokenIssuer;
        this.requestContext = requestContext;
        this.clock = clock;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        LOG.debugv("STS action: {0}", action);

        // A session whose token will not fit is refused before anything is minted, on every
        // operation that carries state into the token it mints.
        if (PACKED_SESSION_OPERATIONS.contains(action)) {
            AssembledSession session = assembledSession(params);
            if (session.tooLarge()) {
                return session.refusal();
            }
        }

        return switch (action) {
            case "AssumeRole"                  -> handleAssumeRole(params);
            case "GetCallerIdentity"           -> handleGetCallerIdentity(params);
            case "GetAccessKeyInfo"            -> handleGetAccessKeyInfo(params);
            case "GetSessionToken"             -> handleGetSessionToken(params);
            case "AssumeRoleWithWebIdentity"   -> handleAssumeRoleWithWebIdentity(params);
            case "AssumeRoleWithSAML"          -> handleAssumeRoleWithSAML(params);
            case "GetFederationToken"          -> handleGetFederationToken(params);
            case "DecodeAuthorizationMessage"  -> handleDecodeAuthorizationMessage(params);
            case "GetDelegatedAccessToken"     -> handleGetDelegatedAccessToken(params);
            case "AssumeRoot"                  -> handleAssumeRoot(params);
            case "GetWebIdentityToken"         -> handleGetWebIdentityToken(params);
            default -> AwsQueryResponse.error("UnsupportedOperation",
                    "Operation " + action + " is not supported by STS.", AwsNamespaces.STS, 400);
        };
    }

    private Response handleAssumeRole(MultivaluedMap<String, String> params) {
        Response integerMembers = validateIntegerMembers(params, "DurationSeconds", "MinimumSessionTokenSize");
        if (integerMembers != null) {
            return integerMembers;
        }
        Response validation = validateDeclaredConstraints("AssumeRole", params);
        if (validation != null) {
            return validation;
        }
        MfaOutcome mfa = authenticateMfa(params);
        if (mfa.denial() != null) {
            return mfa.denial();
        }
        String roleArn = getParam(params, "RoleArn");
        String sessionName = getParam(params, "RoleSessionName");
        int durationSeconds = getIntParam(params, "DurationSeconds", 3600);

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = sessionToken(params);
        Instant expiration = clock.instant().plusSeconds(durationSeconds);

        String roleName = roleArn != null && roleArn.contains("/")
                ? roleArn.substring(roleArn.lastIndexOf('/') + 1)
                : "UnknownRole";
        String callerAccountId = regionResolver.getAccountId();
        String accountId = AwsArnUtils.accountOrDefault(roleArn, callerAccountId);

        IamRole role = iamService.findRole(accountId, roleName).orElse(null);
        Response trustDenied = enforceTrustPolicy(roleArn, role, accountId, params, mfa);
        if (trustDenied != null) {
            return trustDenied;
        }

        Response roleDurationError = validateMaxSessionDuration(durationSeconds, role);
        if (roleDurationError != null) {
            return roleDurationError;
        }

        if (durationSeconds > 3600 && iamService.isRoleSession(callerAccessKeyId(), callerSessionToken(params))) {
            return AwsQueryResponse.error("ValidationError",
                    "The requested DurationSeconds exceeds the 1 hour session limit for roles assumed by role chaining.",
                    AwsNamespaces.STS, 400);
        }

        String sessionRoleArn = canonicalRoleArn(role, accountId, roleName);
        String assumedRoleArn = assumedRoleArn(sessionRoleArn, accountId, roleName, sessionName);
        String assumedRoleId = assumedRoleId(role, sessionName);

        // Register session so IAM enforcement can resolve the role's policies, RDS/ElastiCache
        // IAM token validation can find the temporary secret key, and account routing can map
        // these temporary credentials to the assumed role's account. The session records the
        // role's own ARN, path included: it is the session's aws:PrincipalArn, and it keeps naming
        // the role that issued the session even if a role of the same name replaces it later.
        String sessionPolicy = getParam(params, "Policy");
        iamService.registerSession(
                accessKeyId, secretKey, sessionToken, sessionRoleArn, expiration, sessionPolicy, callerAccountId,
                sessionName, assumedRoleId);

        String result = new XmlBuilder()
                .raw(credentialsXml(accessKeyId, secretKey, sessionToken, expiration))
                .start("AssumedRoleUser")
                  .elem("Arn", assumedRoleArn)
                  .elem("AssumedRoleId", assumedRoleId)
                .end("AssumedRoleUser")
                .elem("PackedPolicySize", assembledSession(params).utilization())
                .build();
        return Response.ok(AwsQueryResponse.envelope("AssumeRole", AwsNamespaces.STS, result)).build();
    }

    /**
     * The one limit AWS enforces on a session token, which its refusal names as well. AWS "has
     * replaced the packed policy size and the overall session token size limits with a single
     * token size limit of 4,096 bytes": "the assembled session token must fit within 4,096 bytes",
     * and "the separate packed policy limit, which made failures hard to predict, has been
     * removed".
     */
    private static final int SESSION_TOKEN_LIMIT = 4096;

    /** The operations that carry state into the token they mint and model the refusal for it. */
    private static final Set<String> PACKED_SESSION_OPERATIONS = Set.of(
            "AssumeRole", "AssumeRoleWithSAML", "AssumeRoleWithWebIdentity", "GetFederationToken");

    /**
     * What a request's session token comes to, and what that costs against the limit it is held
     * to.
     *
     * <p>AWS "serializes and compresses your session policies and tags when assembling the token",
     * refuses a token that will not fit with {@code PackedPolicyTooLarge} 400, whose "error
     * message reports the session token size and the maximum allowed size, both in bytes", and
     * reports what a session that does fit consumed: {@code SessionTokenUtilization} is "the
     * percentage of the 4,096-byte limit consumed", and {@code PackedPolicySize} "now reports the
     * same value as SessionTokenUtilization", kept "for backward compatibility" where the API
     * reference calls it deprecated. This emulator refused nothing and published that member as a
     * flat zero on every session, so a caller could neither see what its session cost nor be told
     * it was over.
     *
     * <p>The measure is this emulator's own: the token it will mint for the request, whose length
     * {@code MinimumSessionTokenSize} can ask to be raised, plus the state the request asks to
     * carry into it, in UTF-8 bytes. AWS's assembly is unpublished and its size does not read off
     * the input in either direction, since "compression results vary based on the actual content,
     * not just its length" and "two sets of tags with identical character counts can produce
     * different token sizes", while the one size recorded here ran larger than the bytes going in,
     * 26092 for a request carrying about 19000. So this number will differ from AWS's, and
     * {@code docs/services/iam.md} records the figure that was recorded so a later one can
     * calibrate the reading.
     */
    private record AssembledSession(int bytes) {

        boolean tooLarge() {
            return bytes > SESSION_TOKEN_LIMIT;
        }

        /**
         * The member's own unit, truncated as AWS's own recorded pair reports it: a token of 2532
         * bytes is published as 61.
         */
        String utilization() {
            return Integer.toString(bytes * 100 / SESSION_TOKEN_LIMIT);
        }

        Response refusal() {
            return AwsQueryResponse.error("PackedPolicyTooLarge",
                    "Serialized token too large for session. Token size of " + bytes
                            + " bytes exceeds the maximum allowed limit of "
                            + SESSION_TOKEN_LIMIT + " bytes.", AwsNamespaces.STS, 400);
        }
    }

    /**
     * What the request's token comes to: the length this emulator will mint for it, and the state
     * it asks to carry, which is the inline document, every policy ARN, and every tag key and
     * value. {@code TransitiveTagKeys} names tags already counted, so it adds nothing of its own,
     * which is what AWS says of its own packing as well: "the transitive status of a session tag
     * does not impact its packed binary size".
     */
    private AssembledSession assembledSession(MultivaluedMap<String, String> params) {
        StringBuilder state = new StringBuilder();
        String document = params.getFirst("Policy");
        if (document != null) {
            state.append(document);
        }
        for (AwsQueryLists.IndexedMember arn : AwsQueryLists.members(params, "PolicyArns", null)) {
            String value = params.getFirst(arn.prefix() + ".arn");
            if (value != null) {
                state.append(value);
            }
        }
        tagClaims(params).forEach((key, value) -> state.append(key).append(value));
        return new AssembledSession(sessionTokenLength(params)
                + state.toString().getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * What a call's MFA parameters authenticated: the instant the device was used, or the denial
     * that answers a device the caller cannot present. Both {@code GetSessionToken} and
     * {@code AssumeRole} take the same pair, {@code SerialNumber} naming "the identification
     * number of the MFA device that is associated with the IAM user who is making the
     * GetSessionToken call" and {@code TokenCode} carrying "the value provided by the MFA device".
     *
     * <p>A call that names neither claims nothing, and takes whatever its own credentials already
     * carry, which is how a session minted behind a device assumes a role that tests for MFA
     * without presenting the device again: "because temporary credentials returned by
     * GetSessionToken include MFA information, you can check for MFA in individual API operations
     * made by the credentials". A call that names either member is a claim of MFA and has to make
     * it good; what answers a claim that fails is the access denied AWS documents for the case,
     * "an incorrect MFA code causes the API to return an access denied error", in the two
     * sentences {@link #mfaRefusal} carries, one for a serial that is not the caller's and one for
     * a code that does not match the device it names.
     *
     * <p>The claim is read through the request's own holder rather than taken here, because the
     * policy for this call was evaluated before the operation ran and had to see the same device,
     * and because a code is spent when it authenticates: one request is one claim.
     */
    private MfaOutcome authenticateMfa(MultivaluedMap<String, String> params) {
        String serialNumber = getParam(params, "SerialNumber");
        String tokenCode = getParam(params, "TokenCode");
        if (serialNumber == null && tokenCode == null) {
            return new MfaOutcome(iamService.callerMfaAuthenticatedAt(callerAccessKeyId()).orElse(null), null);
        }
        RequestMfaClaim claim =
                iamService.requestMfaClaim(requestContext, callerAccessKeyId(), serialNumber, tokenCode);
        if (claim.failed()) {
            return new MfaOutcome(null, AwsQueryResponse.error("AccessDenied",
                    mfaRefusal(claim.failure()), AwsNamespaces.STS, 403));
        }
        return new MfaOutcome(claim.authenticatedAt(), null);
    }

    /**
     * The sentence a failed MFA claim earns, which AWS words by which half of the claim failed.
     * Both are 403 {@code AccessDenied} with a sender fault, both are wholly fixed text, and
     * neither echoes the serial or the code: a caller is told which half to look at and nothing
     * about the values it sent. Recorded on {@code GetSessionToken} against a real account, the
     * serial half on 2026-10-29 and the code half on 2026-11-13, and each carries whitespace AWS
     * itself emits, two spaces inside the first and a trailing space on the second.
     *
     * <p>{@code AssumeRole} takes the same pair through this seam and carries no recording of its
     * own, so it answers the same two sentences, which is a reading rather than a measurement.
     */
    private static String mfaRefusal(RequestMfaClaim.Failure failure) {
        return failure == RequestMfaClaim.Failure.SERIAL
                ? "MultiFactorAuthentication failed, unable to validate MFA code.  "
                        + "Please verify your MFA serial number is valid and associated with this user."
                : "MultiFactorAuthentication failed with invalid MFA one time pass code. ";
    }

    /** The MFA a call authenticated, or the refusal it earned. */
    private record MfaOutcome(Instant authenticatedAt, Response denial) {
    }

    /**
     * Hands a {@code GetSessionToken} session the device behind it, so its own requests publish the
     * two keys: "because temporary credentials returned by GetSessionToken include MFA
     * information, you can check for MFA in individual API operations made by the credentials".
     *
     * <p>An assumed-role session is not given one, and this is not called there. AWS draws the line
     * itself: "the temporary credentials returned by AssumeRole do not include MFA information in
     * the context, so you cannot check individual API operations for MFA", and "once the user has
     * assumed the role, no further MFA checks are made". The device decides the assume and stops
     * there.
     */
    private void recordIssuedSessionMfa(String sessionAccessKeyId, MfaOutcome mfa) {
        if (mfa.authenticatedAt() != null) {
            iamService.recordSessionMfa(sessionAccessKeyId, mfa.authenticatedAt());
        }
    }

    /**
     * When IAM enforcement is enabled, denies AssumeRole if the target role's trust policy does not
     * permit the caller. Returns {@code null} to allow when enforcement is disabled or the caller is
     * permitted. Roles absent from Floci are always denied.
     *
     * <p>The caller's account is the one the request was resolved to from its credentials, signed
     * in the header or presigned in the query, which for an IAM user or a session is the account
     * that owns the key rather than the default one. A role session is matched as the role that
     * issued it, whose ARN keeps the path the session ARN drops. The trust policy's conditions see
     * the request's keys: the global keys, {@code sts:RoleSessionName}, {@code sts:ExternalId}
     * when the caller sends one, and the keys that say who is calling, which the resolved caller
     * writes itself so that a trust policy reads the same principal as any other policy. A
     * credential that resolves to no identity is the account root stand-in, carrying the account
     * id as its {@code aws:userid} and {@code Account} as its {@code aws:PrincipalType}, as that
     * principal does wherever else Floci builds it.
     */
    private Response enforceTrustPolicy(String roleArn, IamRole role, String roleAccountId,
                                        MultivaluedMap<String, String> params, MfaOutcome mfa) {
        String callerAccount = regionResolver.getAccountId();
        IamService.CallerArns caller = iamService.resolveCallerArns(callerAccessKeyId())
                .orElseGet(() -> IamService.CallerArns.accountRoot(
                                regionResolver.buildGlobalArn("iam", callerAccount, "root"), callerAccount)
                        .withOrganization(iamService.organizationContextOf(callerAccount).orElse(null)));
        if (mfa.authenticatedAt() != null) {
            // A device this call presented is this request's own authentication, which is how a
            // long-term key reaches a role whose trust policy "includes a condition that tests for
            // MFA authentication". It rides on the caller rather than onto the context, because the
            // caller is what writes those keys wherever a policy is evaluated: written onto the
            // context, a session that presented a fresh device would still be described by what it
            // was minted with.
            caller = caller.withSessionMfa(new IamService.CallerArns.SessionMfa(true,
                    Math.max(0, Duration.between(mfa.authenticatedAt(), clock.instant()).toSeconds())));
        }
        String callerArn = caller.callerArn();

        if (role == null || !IamService.roleArnMatches(roleArn, role)) {
            return AwsQueryResponse.error("AccessDenied",
                    "User: " + callerArn + " is not authorized to perform: sts:AssumeRole on resource: " + roleArn,
                    AwsNamespaces.STS, 403);
        }
        if (!config.services().iam().enforcementEnabled()) {
            return null;
        }

        Map<String, List<String>> trustContext = IamConditionContextResolver.withGlobalContext(
                null, roleArn, regionResolver.getRegion(), callerAccount, roleAccountId);
        trustContext.put("sts:RoleSessionName", List.of(getParam(params, "RoleSessionName")));
        String externalId = getParam(params, "ExternalId");
        if (externalId != null) {
            trustContext.put("sts:ExternalId", List.of(externalId));
        }
        if (trustPolicyEvaluator.allows(role.getAssumeRolePolicyDocument(), caller, callerAccount,
                trustContext)) {
            return null;
        }
        return AwsQueryResponse.error("AccessDenied",
                "User: " + callerArn + " is not authorized to perform: sts:AssumeRole on resource: " + roleArn,
                AwsNamespaces.STS, 403);
    }

    private String canonicalRoleArn(IamRole role, String accountId, String roleName) {
        if (role.getArn() != null && AwsArnUtils.isArn(role.getArn())) {
            return role.getArn();
        }
        return regionResolver.buildGlobalArn("iam", accountId,
                "role" + IamService.normalizePath(role.getPath()) + roleName);
    }

    private String callerSessionToken(MultivaluedMap<String, String> params) {
        String token = headers == null ? null : headers.getHeaderString("X-Amz-Security-Token");
        if (token == null && params != null) {
            token = getParam(params, "X-Amz-Security-Token");
        }
        if (token == null && uriInfo != null) {
            token = ResteasyRequests.queryParameters(uriInfo).getFirst("X-Amz-Security-Token");
        }
        return token;
    }

    private Response handleGetCallerIdentity(MultivaluedMap<String, String> params) {
        String accountId = regionResolver.getAccountId();
        String accessKeyId = callerAccessKeyId();
        String sessionToken = callerSessionToken(params);
        String arn = iamService.resolveCallerArn(accessKeyId)
                .orElse(regionResolver.buildGlobalArn("iam", accountId, "root"));
        String userId = iamService.resolveCallerUserId(accessKeyId, sessionToken).orElse(accountId);
        String result = new XmlBuilder()
                .elem("UserId", userId)
                .elem("Account", accountId)
                .elem("Arn", arn)
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetCallerIdentity", AwsNamespaces.STS, result)).build();
    }

    /**
     * The account an access key id belongs to. AWS answers one member and models no errors at all,
     * and the page is explicit that the key's state is not part of the answer: "This operation does
     * not indicate the state of the access key. The key might be active, inactive, or deleted." So
     * the lookup takes the key whatever state it is in, which is why it is not
     * {@code resolveAccountId}, the authentication path, that answers here.
     *
     * <p>A key Floci does not hold is the one answer the reference leaves open: it models no error
     * for it and says only that "providing a deleted access key might return an error that the key
     * doesn't exist". A capture from a real account answers {@code ValidationError} 400, "Access
     * key ID is not valid.", for an {@code AKIA} and an {@code ASIA} id alike, which settles the
     * shape of the operation as a refusal rather than a decode of the account out of the id.
     * {@code docs/services/sts.md} carries the capture.
     */
    private Response handleGetAccessKeyInfo(MultivaluedMap<String, String> params) {
        Response validation = validateDeclaredConstraints("GetAccessKeyInfo", params);
        if (validation != null) {
            return validation;
        }
        String accessKeyId = getParam(params, "AccessKeyId");
        Optional<String> account = iamService.findAccountForAccessKeyId(accessKeyId);
        if (account.isEmpty()) {
            // Captured from a real account against an AKIA and an ASIA id alike: AWS refuses an id
            // it never issued rather than decoding the account out of it, and the refusal carries
            // neither the id nor a validation-error count.
            return AwsQueryResponse.error("ValidationError",
                    "Access key ID is not valid.", AwsNamespaces.STS, 400);
        }
        String result = new XmlBuilder().elem("Account", account.get()).build();
        return Response.ok(AwsQueryResponse.envelope("GetAccessKeyInfo", AwsNamespaces.STS, result)).build();
    }

    private Response handleGetSessionToken(MultivaluedMap<String, String> params) {
        if (IamService.isTemporaryAccessKey(callerAccessKeyId())) {
            return AwsQueryResponse.error("AccessDenied", IamService.sessionCredentialRefusal("GetSessionToken"),
                    AwsNamespaces.STS, 403);
        }
        Response integerMembers =
                validateIntegerMembers(params, "DurationSeconds", "MinimumSessionTokenSize");
        if (integerMembers != null) {
            return integerMembers;
        }
        Response validation = validateDeclaredConstraints("GetSessionToken", params);
        if (validation != null) {
            return validation;
        }
        MfaOutcome mfa = authenticateMfa(params);
        if (mfa.denial() != null) {
            return mfa.denial();
        }
        int durationSeconds = longTermTokenDuration(params);
        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = sessionToken(params);
        Instant expiration = clock.instant().plusSeconds(durationSeconds);

        String result = credentialsXml(accessKeyId, secretKey, sessionToken, expiration);
        // No role ARN: the credentials route back to the caller's account and act as the caller.
        iamService.registerIssuedSession(accessKeyId, secretKey, sessionToken, null, expiration, null,
                regionResolver.getAccountId(), callerAccessKeyId());
        recordIssuedSessionMfa(accessKeyId, mfa);
        return Response.ok(AwsQueryResponse.envelope("GetSessionToken", AwsNamespaces.STS, result)).build();
    }

    private Response handleAssumeRoleWithWebIdentity(MultivaluedMap<String, String> params) {
        Response integerMembers = validateIntegerMembers(params, "DurationSeconds", "MinimumSessionTokenSize");
        if (integerMembers != null) {
            return integerMembers;
        }
        Response validation = validateDeclaredConstraints("AssumeRoleWithWebIdentity", params);
        if (validation != null) {
            return validation;
        }
        Response roleArnShape = validateIamRoleArn(getParam(params, "RoleArn"));
        if (roleArnShape != null) {
            return roleArnShape;
        }
        String roleArn = getParam(params, "RoleArn");
        String sessionName = getParam(params, "RoleSessionName");
        String providerId = getParam(params, "ProviderId");
        String webIdentityToken = getParam(params, "WebIdentityToken");
        int durationSeconds = getIntParam(params, "DurationSeconds", 3600);

        String roleName = roleArn.contains("/") ? roleArn.substring(roleArn.lastIndexOf('/') + 1) : "UnknownRole";
        String callerAccountId = regionResolver.getAccountId();
        String accountId = AwsArnUtils.accountOrDefault(roleArn, callerAccountId);

        WebIdentityOutcome outcome = verifyWebIdentityToken(webIdentityToken, roleName, accountId, roleArn,
                sessionName);
        if (outcome.denial() != null) {
            return outcome.denial();
        }
        VerifiedWebIdentity verified = outcome.verified();
        IamRole role = outcome.role();
        if (role == null) {
            role = iamService.findRole(accountId, roleName).orElse(null);
            if (role == null || !IamService.roleArnMatches(roleArn, role)) {
                return accessDenied(roleArn);
            }
        }

        Response roleDurationError = validateMaxSessionDuration(durationSeconds, role);
        if (roleDurationError != null) {
            return roleDurationError;
        }

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = sessionToken(params);
        Instant expiration = clock.instant().plusSeconds(durationSeconds);

        String sessionRoleArn = canonicalRoleArn(role, accountId, roleName);
        String assumedRoleArn = assumedRoleArn(sessionRoleArn, accountId, roleName, sessionName);
        String assumedRoleId = assumedRoleId(role, sessionName);

        String provider = verified != null ? verified.issuer()
                : (providerId != null && !providerId.isBlank() ? providerId : "accounts.google.com");
        String audience = verified != null ? verified.audience() : STS_AUDIENCE;
        String subject = verified != null ? verified.subject() : "web-identity-subject";

        String sessionPolicy = getParam(params, "Policy");
        iamService.registerSession(
                accessKeyId, secretKey, sessionToken, sessionRoleArn, expiration, sessionPolicy, callerAccountId,
                sessionName, assumedRoleId);

        String result = new XmlBuilder()
                .raw(credentialsXml(accessKeyId, secretKey, sessionToken, expiration))
                .start("AssumedRoleUser")
                  .elem("Arn", assumedRoleArn)
                  .elem("AssumedRoleId", assumedRoleId)
                .end("AssumedRoleUser")
                .elem("PackedPolicySize", assembledSession(params).utilization())
                .elem("Provider", provider)
                .elem("Audience", audience)
                .elem("SubjectFromWebIdentityToken", subject)
                .build();
        return Response.ok(AwsQueryResponse.envelope("AssumeRoleWithWebIdentity", AwsNamespaces.STS, result)).build();
    }

    private Response validateIamRoleArn(String roleArn) {
        if (!AwsArnUtils.isArn(roleArn)) {
            return AwsQueryResponse.error("ValidationError", "RoleArn must be a valid IAM role ARN.",
                    AwsNamespaces.STS, 400);
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        boolean validRoleArn = arn.partition().matches(AwsArnUtils.PARTITION_REGEX)
                && "iam".equals(arn.service())
                && arn.region().isEmpty()
                && arn.accountId().matches("[0-9]{12}")
                && arn.resource().startsWith("role/")
                && arn.resource().length() > "role/".length();
        if (!validRoleArn) {
            return AwsQueryResponse.error("ValidationError", "RoleArn must be a valid IAM role ARN.",
                    AwsNamespaces.STS, 400);
        }
        return null;
    }

    /** The claims of a token Floci issued and verified, used to fill the response accurately. */
    private record VerifiedWebIdentity(String issuer, String subject, String audience) {}

    private record WebIdentityOutcome(VerifiedWebIdentity verified, IamRole role, Response denial) {

        static WebIdentityOutcome unverifiable() {
            return new WebIdentityOutcome(null, null, null);
        }
        static WebIdentityOutcome allow(VerifiedWebIdentity verified, IamRole role) {
            return new WebIdentityOutcome(verified, role, null);
        }

        static WebIdentityOutcome deny(Response denial) {
            return new WebIdentityOutcome(null, null, denial);
        }
    }

    /**
     * Inspects {@code token} and decides whether it may assume {@code roleArn} as the session
     * {@code sessionName}, which the trust policy reads as {@code sts:RoleSessionName}.
     */
    private WebIdentityOutcome verifyWebIdentityToken(String token, String roleName, String roleAccountId,
                                                      String roleArn, String sessionName) {
        Optional<String> issuer = tokenVerifier.peekIssuer(token);
        if (issuer.isEmpty()) {
            return config.services().iam().enforcementEnabled()
                    ? WebIdentityOutcome.deny(AwsQueryResponse.error("InvalidIdentityToken",
                    "The web identity token does not identify a trusted issuer.", AwsNamespaces.STS, 400))
                    : WebIdentityOutcome.unverifiable();
        }
        Optional<RSAPublicKey> key = oidcIssuerKeys.findVerificationKey(issuer.get());
        if (key.isEmpty()) {
            return config.services().iam().enforcementEnabled()
                    ? WebIdentityOutcome.deny(AwsQueryResponse.error("InvalidIdentityToken",
                    "The web identity token issuer is not trusted.", AwsNamespaces.STS, 400))
                    : WebIdentityOutcome.unverifiable();
        }

        WebIdentityToken claims;
        try {
            claims = tokenVerifier.verify(token, key.get(), issuer.get(), STS_AUDIENCE);
        } catch (WebIdentityTokenVerifier.ExpiredTokenException e) {
            LOG.debugv("Rejecting web identity token for role {0}: {1}", roleArn, e.getMessage());
            return WebIdentityOutcome.deny(AwsQueryResponse.error("ExpiredTokenException",
                    "The web identity token that was passed is expired or is not valid. Get a new "
                            + "identity token from the identity provider and then retry the request.",
                    AwsNamespaces.STS, 400));
        } catch (WebIdentityTokenVerifier.InvalidTokenException e) {
            LOG.debugv("Rejecting web identity token for role {0}: {1}", roleArn, e.getMessage());
            return WebIdentityOutcome.deny(AwsQueryResponse.error("InvalidIdentityToken",
                    e.getMessage(), AwsNamespaces.STS, 400));
        }

        Optional<IamRole> role = iamService.findRole(roleAccountId, roleName);
        if (role.isEmpty() || !IamService.roleArnMatches(roleArn, role.get())) {
            return WebIdentityOutcome.deny(accessDenied(roleArn));
        }

        String issuerKeyPrefix = stripScheme(issuer.get());
        // The provider is an IAM resource of the role's account, so it shares the role's partition.
        String oidcProviderArn = AwsArnUtils.Arn.global(
                AwsArnUtils.partitionOrDefault(role.get().getArn(), regionResolver.getPartition()),
                "iam", roleAccountId, "oidc-provider/" + issuerKeyPrefix).toString();
        Map<String, List<String>> conditionClaims = Map.of(
                "sub", List.of(claims.subject()),
                "aud", claims.audiences());

        if (!webIdentityTrustEvaluator.allows(role.get().getAssumeRolePolicyDocument(),
                oidcProviderArn, issuerKeyPrefix, conditionClaims, sessionName)) {
            LOG.debugv("Trust policy on role {0} denies web identity subject {1}",
                    roleArn, claims.subject());
            return WebIdentityOutcome.deny(accessDenied(roleArn));
        }

        // verify() already required the audience list to contain STS_AUDIENCE.
        return WebIdentityOutcome.allow(
                new VerifiedWebIdentity(claims.issuer(), claims.subject(), STS_AUDIENCE), role.get());
    }

    private static String assumedRoleId(IamRole role, String sessionName) {
        String roleId = (role != null && role.getRoleId() != null) ? role.getRoleId() : ("AROA" + randomId(16));
        return roleId + ":" + sessionName;
    }

    /**
     * A session on a role lives in the role's partition, whatever region AssumeRole is signed
     * for. Callers pass the stored role's ARN when the role exists, not the RoleArn the request
     * named, and register the session under the same ARN, so {@link IamService#resolveCallerArn}
     * always agrees with the ARN returned here.
     */
    private String assumedRoleArn(String roleArn, String accountId, String roleName, String sessionName) {
        return AwsArnUtils.Arn.global(AwsArnUtils.partitionOrDefault(roleArn, regionResolver.getPartition()),
                "sts", accountId, "assumed-role/" + roleName + "/" + sessionName).toString();
    }

    private Response accessDenied(String roleArn) {
        return AwsQueryResponse.error("AccessDenied",
                "Not authorized to perform sts:AssumeRoleWithWebIdentity on resource: " + roleArn,
                AwsNamespaces.STS, 403);
    }

    /**
     * Strips the URL scheme from an issuer. IAM renders an OIDC provider ARN and its condition keys
     * from the host-and-path form ({@code oidc.eks.<region>.amazonaws.com/id/<id>}), not the full URL.
     */
    private static String stripScheme(String issuer) {
        int schemeEnd = issuer.indexOf("://");
        return schemeEnd < 0 ? issuer : issuer.substring(schemeEnd + 3);
    }

    private Response handleAssumeRoleWithSAML(MultivaluedMap<String, String> params) {
        Response integerMembers = validateIntegerMembers(params, "DurationSeconds", "MinimumSessionTokenSize");
        if (integerMembers != null) {
            return integerMembers;
        }
        Response validation = validateDeclaredConstraints("AssumeRoleWithSAML", params);
        if (validation != null) {
            return validation;
        }
        String roleArn = getParam(params, "RoleArn");
        String principalArn = getParam(params, "PrincipalArn");
        int durationSeconds = getIntParam(params, "DurationSeconds", 3600);

        var provider = samlProviderService.find(principalArn).orElseThrow(() ->
                new AwsException("InvalidIdentityToken", "The SAML provider is not trusted.", 400));
        SAMLAssertionVerifier.Verified verified;
        try {
            verified = SAMLAssertionVerifier.verify(getParam(params, "SAMLAssertion"), provider, clock.instant());
        } catch (SAMLAssertionVerifier.InvalidAssertionException e) {
            throw new AwsException("InvalidIdentityToken", e.awsMessage(), 400);
        }
        boolean rolePair = verified.roles().stream().anyMatch(pair ->
                roleArn.equals(pair.roleArn()) && principalArn.equals(pair.principalArn()));
        if (!rolePair) {
            throw new AwsException("InvalidIdentityToken",
                    "The SAML assertion does not contain the requested role and principal.", 400);
        }

        String callerAccountId = regionResolver.getAccountId();
        String accountId = AwsArnUtils.accountOrDefault(roleArn, callerAccountId);
        String roleName = roleArn.contains("/") ? roleArn.substring(roleArn.lastIndexOf('/') + 1) : "UnknownRole";
        IamRole role = iamService.findRole(accountId, roleName).orElseThrow(() ->
                new AwsException("AccessDenied", "Not authorized to perform sts:AssumeRoleWithSAML on resource: " + roleArn, 403));
        if (!samlTrustEvaluator.allows(role.getAssumeRolePolicyDocument(), principalArn, Map.of(
                "aud", List.of(STS_AUDIENCE),
                "iss", List.of(verified.issuer()),
                "sub", List.of(verified.subject()),
                "namequalifier", List.of(verified.nameQualifier())), verified.roleSessionName())) {
            throw new AwsException("AccessDenied", "Not authorized to perform sts:AssumeRoleWithSAML on resource: " + roleArn, 403);
        }

        Response roleDurationError = validateMaxSessionDuration(durationSeconds, role);
        if (roleDurationError != null) {
            return roleDurationError;
        }

        String sessionName = verified.roleSessionName();
        // AWS refuses an assertion without the attribute; Floci has always accepted one, naming the
        // session after its subject, and keeps doing so.
        if (sessionName == null) {
            sessionName = verified.subject().replaceAll("[^A-Za-z0-9+=,.@_-]", "_");
            if (sessionName.length() > 64) {
                sessionName = sessionName.substring(0, 64);
            }
        }
        Instant requestedExpiration = clock.instant().plusSeconds(durationSeconds);
        Instant expiration = verified.expiration().isBefore(requestedExpiration) ? verified.expiration() : requestedExpiration;
        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = sessionToken(params);
        String sessionRoleArn = canonicalRoleArn(role, accountId, roleName);
        String assumedRoleArn = assumedRoleArn(sessionRoleArn, accountId, roleName, sessionName);
        String assumedRoleId = assumedRoleId(role, sessionName);

        iamService.registerSession(accessKeyId, secretKey, sessionToken, sessionRoleArn, expiration, null,
                callerAccountId, sessionName, assumedRoleId);
        String result = new XmlBuilder()
                .raw(credentialsXml(accessKeyId, secretKey, sessionToken, expiration))
                .start("AssumedRoleUser").elem("Arn", assumedRoleArn).elem("AssumedRoleId", assumedRoleId).end("AssumedRoleUser")
                .elem("PackedPolicySize", assembledSession(params).utilization()).elem("Issuer", verified.issuer()).elem("Audience", "urn:amazon:webservices")
                .elem("NameQualifier", verified.nameQualifier()).elem("SubjectType", verified.subjectType()).elem("Subject", verified.subject()).build();
        return Response.ok(AwsQueryResponse.envelope("AssumeRoleWithSAML", AwsNamespaces.STS, result)).build();
    }

    /** The access key that signed this request, in the header or presigned in the query. */
    private String callerAccessKeyId() {
        String authorization = headers == null ? null : headers.getHeaderString("Authorization");
        String auth = IamEnforcementFilter.requestAuthorization(
                authorization == null || authorization.isBlank() ? null : authorization,
                uriInfo == null ? null : ResteasyRequests.queryParameters(uriInfo));
        return auth == null ? null : accountResolver.extractAccessKeyId(auth);
    }

    private int longTermTokenDuration(MultivaluedMap<String, String> params) {
        int requested = getIntParam(params, "DurationSeconds", 43200);
        // Session registration treats an unheld long-term key as Floci's account-root credential.
        return iamService.isKnownAccessKey(callerAccessKeyId()) ? requested : Math.min(requested, 3600);
    }

    private Response handleGetFederationToken(MultivaluedMap<String, String> params) {
        if (IamService.isTemporaryAccessKey(callerAccessKeyId())) {
            return AwsQueryResponse.error("AccessDenied", IamService.FEDERATION_TOKEN_SESSION_MESSAGE,
                    AwsNamespaces.STS, 403);
        }
        Response integerMembers = validateIntegerMembers(params, "DurationSeconds", "MinimumSessionTokenSize");
        if (integerMembers != null) {
            return integerMembers;
        }
        Response validation = validateDeclaredConstraints("GetFederationToken", params);
        if (validation != null) {
            return validation;
        }
        String name = getParam(params, "Name");
        int durationSeconds = longTermTokenDuration(params);

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = sessionToken(params);
        Instant expiration = clock.instant().plusSeconds(durationSeconds);
        String accountId = regionResolver.getAccountId();
        String federatedUserId = accountId + ":" + name;
        String federatedUserArn = regionResolver.buildGlobalArn("sts", accountId, "federated-user/" + name);

        String sessionPolicy = getParam(params, "Policy");
        // The session is scoped by its session policy within the permissions of the caller that
        // minted it, so the caller is recorded with it.
        iamService.registerIssuedSession(accessKeyId, secretKey, sessionToken, federatedUserArn, expiration,
                sessionPolicy, accountId, callerAccessKeyId());

        String result = new XmlBuilder()
                .raw(credentialsXml(accessKeyId, secretKey, sessionToken, expiration))
                .start("FederatedUser")
                  .elem("FederatedUserId", federatedUserId)
                  .elem("Arn", federatedUserArn)
                .end("FederatedUser")
                .elem("PackedPolicySize", assembledSession(params).utilization())
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetFederationToken", AwsNamespaces.STS, result)).build();
    }

    /**
     * Exchanges a trade-in token for credentials in the customer's account.
     *
     * <p>The credentials expire at the moment the customer released the token plus the request's
     * {@code SessionDuration}, not at redemption: "the delegation period starts when customer
     * releases the exchange token, not when the product provider redeems it", and "all credentials
     * obtained from the same exchange token expire at the same time". A token can therefore be
     * exchanged repeatedly, each exchange yielding a shorter session than the one before.
     *
     * <p>{@code AssumedPrincipal} is the request's approver rather than its owner. Accepting a
     * request "records the details and the state of the identity that called this API", and "this
     * is the identity that gets mapped to the delegated credential", which the user guide says the
     * same way: the provider obtains the "approver's temporary credentials". The two differ in the
     * case {@code OnlySendByOwner} is for, where the owner could not approve and forwarded the
     * request on. {@code PackedPolicySize} is left out: the model deprecates it and states it is
     * not populated for this operation.
     */
    private Response handleGetDelegatedAccessToken(MultivaluedMap<String, String> params) {
        Response validation = validateDeclaredConstraints("GetDelegatedAccessToken", params);
        if (validation != null) {
            return validation;
        }
        DelegationRequestService.Redeemed redeemed = delegationRequestService.redeem(
                regionResolver.getAccountId(), getParam(params, "TradeInToken"));
        DelegationRequest request = redeemed.request();

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        // The operation models no size to ask for, so the token is the length this mints.
        String sessionToken = randomSecret(SESSION_TOKEN_LENGTH);
        // The session acts in the customer's account as the identity that approved the request,
        // bounded by the rendered permission policy wherever the request carries one and by that
        // identity's own permissions either way. The account is the owner's: a request is visible
        // only from the account that claimed it, so the approver is an identity there too. It is
        // registered as a delegated session rather than an issued one, the approver being whatever
        // accepted the request: a role ARN slot is read by name, so an approver named like a role
        // would act with that role's permissions. No issuing key is recorded, the permissions
        // coming from the customer's side of the delegation rather than from the partner key that
        // redeemed the token.
        iamService.registerDelegatedSession(accessKeyId, secretKey, sessionToken, request.getApproverId(),
                redeemed.credentialExpiry(), request.getPermissionPolicy(),
                request.getOwnerAccountId(), request.getRolePermissionRestrictionArns());

        String result = new XmlBuilder()
                .raw(credentialsXml(accessKeyId, secretKey, sessionToken, redeemed.credentialExpiry()))
                .elem("AssumedPrincipal", request.getApproverId())
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetDelegatedAccessToken",
                AwsNamespaces.STS, result)).build();
    }

    /**
     * A privileged session on a member account: "Returns a set of short term credentials you can
     * use to perform privileged tasks on a member account in your organization." The session acts
     * as that account's root user, scoped by the task policy the request names, which is what the
     * guide means by "you must define TaskPolicyArn to scope the session to the privileged action
     * to be performed during the session".
     *
     * <p>Four refusals, each from a published sentence and each {@code AccessDenied} 403 because
     * the operation models only {@code RegionDisabled} and {@code ExpiredToken}: the caller must
     * be the management account or a delegated administrator for IAM, must not be root ("You
     * cannot use root user credentials to call sts:AssumeRoot"), must have {@code RootSessions}
     * enabled for the organization, and the target must be a member account other than the
     * management account, privileged tasks being what the management account does to members. Both
     * credentials this emulator reads as the account root are refused, so the caller is an IAM
     * user or role of that account. The
     * task policy is held to the five AWS publishes for it, by the {@code /root-task/} path of an
     * AWS managed policy rather than by a list of names, and anything else is a
     * {@code ValidationError}: no source gives a code for that one, so it is the shape every other
     * member violation answers here.
     *
     * <p>The global endpoint is refused when the request arrives on it, "The global endpoint is
     * not supported for AssumeRoot. You must send this request to a Regional AWS STS endpoint." A
     * request to a local host carries no such name, so there is nothing to refuse there.
     */
    private Response handleAssumeRoot(MultivaluedMap<String, String> params) {
        if (arrivedOnTheGlobalEndpoint()) {
            return AwsQueryResponse.error("ValidationError", "The global endpoint is not supported for"
                    + " AssumeRoot. You must send this request to a Regional AWS STS endpoint.",
                    AwsNamespaces.STS, 400);
        }
        Response integerMembers = validateIntegerMembers(params, "DurationSeconds", "MinimumSessionTokenSize");
        if (integerMembers != null) {
            return integerMembers;
        }
        Response validation = validateDeclaredConstraints("AssumeRoot", params);
        if (validation != null) {
            return validation;
        }
        String targetPrincipal = getParam(params, "TargetPrincipal");
        String callerAccountId = regionResolver.getAccountId();
        Response rootCaller = refuseRootCredentials(callerAccountId);
        if (rootCaller != null) {
            return rootCaller;
        }
        RootAccessOrganization.Membership organization =
                centralRootAccess.privilegedSessionOrganization(callerAccountId);
        String targetAccountId = accountOf(targetPrincipal);
        if (targetAccountId == null || !organization.memberAccountIds().contains(targetAccountId)
                || organization.isManagementAccount(targetAccountId)) {
            return AwsQueryResponse.error("AccessDenied", "User: " + callerAccountId + " is not authorized to"
                    + " perform: sts:AssumeRoot on " + targetPrincipal + " because it is not a member account"
                    + " of the organization.", AwsNamespaces.STS, 403);
        }
        String taskPolicyArn = getParam(params, "TaskPolicyArn.arn");
        if (!namesARootTaskPolicy(taskPolicyArn)) {
            return AwsQueryResponse.error("ValidationError", "The task policy "
                    + taskPolicyArn + " cannot scope a privileged session. Use one of the AWS managed"
                    + " policies under " + ROOT_TASK_POLICY_PATH + ".", AwsNamespaces.STS, 400);
        }
        IamPolicy taskPolicy = iamService.getPolicy(taskPolicyArn);

        int durationSeconds = getIntParam(params, "DurationSeconds", MAX_ROOT_SESSION_SECONDS);
        Instant expiration = clock.instant().plusSeconds(durationSeconds);
        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = sessionToken(params);
        // The session is the member account's root user, bounded by the task policy. The origin is
        // the member account too: the privileged task is performed there, not where it was asked for.
        iamService.registerSession(accessKeyId, secretKey, sessionToken,
                regionResolver.buildGlobalArn("iam", targetAccountId, "root"), expiration,
                taskPolicy.getVersions().get(taskPolicy.getDefaultVersionId()).getDocument(),
                targetAccountId);
        LOG.debugv("Privileged session for {0} opened by {1} under {2}", targetAccountId, callerAccountId,
                taskPolicy.getArn());

        String result = new XmlBuilder()
                .raw(credentialsXml(accessKeyId, secretKey, sessionToken, expiration))
                .elem("SessionTokenSize", String.valueOf(sessionToken.length()))
                .build();
        return Response.ok(AwsQueryResponse.envelope("AssumeRoot", AwsNamespaces.STS, result)).build();
    }

    /**
     * True when the ARN names one of the AWS managed policies a privileged session may be scoped
     * by. The member's own documentation is the list: "You must use one of following Amazon Web
     * Services managed policies to scope root session actions", naming the five that sit under
     * {@code /root-task/}. Whose policy it is counts as much as the path: a customer-managed
     * policy at the same path is the caller's own document, and installing one would leave the
     * session bounded by whatever that document says rather than by a privileged task.
     */
    private static boolean namesARootTaskPolicy(String arn) {
        if (!AwsManagedPolicies.isManagedPolicyArn(arn) || !AwsArnUtils.isArn(arn)) {
            return false;
        }
        return AwsArnUtils.parse(arn).resource().startsWith("policy" + ROOT_TASK_POLICY_PATH);
    }

    /** The account a {@code TargetPrincipal} names, whether it is an account id or a principal ARN. */
    private static String accountOf(String targetPrincipal) {
        if (targetPrincipal.matches("\\d{12}")) {
            return targetPrincipal;
        }
        return AwsArnUtils.isArn(targetPrincipal) ? AwsArnUtils.parse(targetPrincipal).accountId() : null;
    }

    /**
     * "You cannot use root user credentials to call sts:AssumeRoot." Two credentials are the
     * account root here and neither may open a session. One resolves to a root ARN, which is how a
     * privileged session opening another is caught. The other is the bare 12-digit account-id key,
     * which {@code IamEnforcementFilter} reads as "floci's account-root principal, never an IAM
     * user's key": it resolves to no ARN at all, so the first test cannot see it, and refusing it
     * is what leaves the operation to an IAM user or role of the management account, which is the
     * caller AWS asks for.
     */
    private Response refuseRootCredentials(String callerAccountId) {
        String accessKeyId = callerAccessKeyId();
        boolean accountRootKey = accessKeyId != null && accessKeyId.equals(callerAccountId);
        String callerArn = accountRootKey ? null : iamService.resolveCallerArn(accessKeyId).orElse(null);
        if (!accountRootKey && (callerArn == null || !callerArn.endsWith(":root"))) {
            return null;
        }
        return AwsQueryResponse.error("AccessDenied", "Root user credentials cannot call sts:AssumeRoot."
                + " Call it as an IAM user or role with the sts:AssumeRoot permission.", AwsNamespaces.STS, 403);
    }

    /** True when the request arrived on the partition's global STS host rather than a Regional one. */
    private boolean arrivedOnTheGlobalEndpoint() {
        String host = headers == null ? null : headers.getHeaderString(HttpHeaders.HOST);
        if (host == null || host.isBlank()) {
            return false;
        }
        String name = host.split(":", 2)[0];
        return name.equalsIgnoreCase("sts." + AwsRegions.dnsSuffixFor(regionResolver.getRegion()));
    }

    private Response handleDecodeAuthorizationMessage(MultivaluedMap<String, String> params) {
        Response validation = validateDeclaredConstraints("DecodeAuthorizationMessage", params);
        if (validation != null) {
            return validation;
        }
        String encodedMessage = getParam(params, "EncodedMessage");
        String result = new XmlBuilder().elem("DecodedMessage", encodedMessage).build();
        return Response.ok(AwsQueryResponse.envelope("DecodeAuthorizationMessage", AwsNamespaces.STS, result)).build();
    }

    private static Response validateMaxSessionDuration(int durationSeconds, IamRole role) {
        if (durationSeconds > role.getMaxSessionDuration()) {
            return AwsQueryResponse.error("ValidationError",
                    "The requested DurationSeconds exceeds the MaxSessionDuration set for this role.",
                    AwsNamespaces.STS, 400);
        }
        return null;
    }

    /**
     * The constraints the model declares for {@code action}, answered the way this handler answers
     * every other refusal: one {@code ValidationError} naming every violation it found, in the
     * order the model lists the members. A member the request names at all carries a value, so an
     * empty one breaks its minimum length where an absent one is the refusal that names it null,
     * and the member is named as a constraint message spells it, lower camel case with the
     * offending value quoted beside it.
     */
    private static Response validateDeclaredConstraints(String action,
                                                        MultivaluedMap<String, String> params) {
        return validateDeclaredConstraints(action, params, Map.of());
    }

    /** The same, with a constraint the reference documents where the model declares none. */
    private static Response validateDeclaredConstraints(String action,
                                                        MultivaluedMap<String, String> params,
                                                        Map<String, String> documented) {
        List<String> violations = StsRequestShapes.violations(action, params, documented);
        if (violations.isEmpty()) {
            return null;
        }
        return AwsQueryResponse.error("ValidationError", SmithyValidationErrors.message(violations),
                AwsNamespaces.STS, 400);
    }

    /**
     * The members the model declares as numbers, held to being numbers at all. Their ranges are
     * read from the model, which says nothing about a value that is no number: this package answers
     * one with {@code InvalidParameterValue}, which is what {@code DurationSeconds} answered before
     * any of it was read from the model, and a digit string of any width is a number whatever it
     * overflows.
     */
    private static Response validateIntegerMembers(MultivaluedMap<String, String> params,
                                                   String... names) {
        for (String name : names) {
            String value = params.getFirst(name);
            if (value != null && !QueryConstraintCheck.isInteger(value)) {
                return notANumber(name, value);
            }
        }
        return null;
    }

    /**
     * The refusal for a member the protocol cannot read as the number its shape declares, which is
     * {@code InvalidParameterValue} rather than a constraint violation: no range of the model's is
     * what such a value breaks.
     */
    private static Response notANumber(String name, String value) {
        return AwsQueryResponse.error("InvalidParameterValue", "Value " + value + " for parameter "
                + name + " is invalid. Reason: Must be an integer.", AwsNamespaces.STS, 400);
    }

    /**
     * {@code GetWebIdentityToken} answers "a signed JSON Web Token (JWT) that represents the
     * calling Amazon Web Services identity", signed by STS and verifiable "using the verification
     * keys published at the issuer's JWKS endpoint". The issuer is the one IAM mints for the
     * account when outbound web identity federation is enabled, so this reads the state that
     * switch keeps and refuses while it is off, with
     * {@code OutboundWebIdentityFederationDisabledException} rather than IAM's own
     * {@code FeatureDisabled}.
     *
     * <p>The declared constraints are read ahead of all of it, from the model rather than from a
     * check written here: {@code Audience} is 1 to 10 members of 1 to 1000 characters each and
     * required, {@code SigningAlgorithm} is a fixed length of 5 and required,
     * {@code DurationSeconds} is 60 to 3600 and defaults to 300, and {@code Tags} is at most 50
     * tags whose keys and values carry their own lengths and pattern. A member sent empty is a
     * member that is present, so it breaks its length rather than reading as absent. Every
     * violation is reported together, in the order the model lists the members, with the two
     * signing algorithms the reference names reported in that member's own place. A
     * {@code DurationSeconds} that is no number at all is this file's own
     * {@code InvalidParameterValue}, answered before the constraints: the model declares the range,
     * not what counts as a number.
     *
     * <p>Three things here are readings rather than published behaviour, and the docs say so. The
     * model publishes no enum for {@code SigningAlgorithm}, only the fixed length, so a five
     * character value that is neither {@code RS256} nor {@code ES384} is answered with the enum
     * violation this package renders for a value outside a documented set. The global endpoint is
     * refused because the Reference says "The GetWebIdentityToken API is not available on the STS
     * Global endpoint" without giving the refusal, so it borrows the wording AWS publishes for
     * {@code AssumeRoot}, and a request to a local host names no such endpoint and is not
     * refused. {@code JWTPayloadSizeExceededException} is modelled and nothing published says what
     * the maximum payload is, so no size is invented here to refuse.
     *
     * <p>A temporary credential with no live session behind it is refused as expired rather than
     * read as a caller that cannot be identified. {@link IamEnforcementFilter} answers
     * {@code ExpiredTokenException} for one and it never reaches here, but with enforcement off,
     * or in the window between that check and this one, the session lookup comes back empty and
     * the caller would fall to the account root ARN this file hands an unresolvable caller. A
     * token is not a request: its subject is signed and outlives the call, so a session already
     * gone does not get one that speaks for the account. An unknown long-term key keeps the
     * stand-in, which is what {@code GetCallerIdentity} answers it.
     */
    private Response handleGetWebIdentityToken(MultivaluedMap<String, String> params) {
        if (arrivedOnTheGlobalEndpoint()) {
            return AwsQueryResponse.error("ValidationError", "The global endpoint is not supported"
                    + " for GetWebIdentityToken. You must send this request to a Regional AWS STS"
                    + " endpoint.", AwsNamespaces.STS, 400);
        }
        Response integerMembers = validateIntegerMembers(params, "DurationSeconds");
        if (integerMembers != null) {
            return integerMembers;
        }
        Response validation = validateDeclaredConstraints("GetWebIdentityToken", params,
                documentedAlgorithms(params));
        if (validation != null) {
            return validation;
        }

        String accessKeyId = callerAccessKeyId();
        Optional<Instant> sessionEnds = accessKeyId == null
                ? Optional.empty()
                : iamService.findSessionExpiration(accessKeyId);
        if (sessionEnds.isEmpty() && IamService.isTemporaryAccessKey(accessKeyId)) {
            return AwsQueryResponse.error("ExpiredTokenException",
                    IamEnforcementFilter.EXPIRED_TOKEN, AwsNamespaces.STS, 403);
        }

        Optional<OutboundWebIdentityFederation> federation =
                iamService.findEnabledOutboundWebIdentityFederation();
        if (federation.isEmpty()) {
            return AwsQueryResponse.error("OutboundWebIdentityFederationDisabledException",
                    "The outbound web identity federation feature is not enabled for this account."
                            + " To use this feature, you must first enable it through the AWS"
                            + " Management Console or API.", AwsNamespaces.STS, 403);
        }
        Instant issuedAt = clock.instant();
        Instant expiration = issuedAt.plusSeconds(
                getIntParam(params, "DurationSeconds", DEFAULT_TOKEN_SECONDS));
        if (sessionEnds.filter(expiration::isAfter).isPresent()) {
            return AwsQueryResponse.error("SessionDurationEscalationException",
                    "The requested token duration would extend the session beyond its original"
                            + " expiration time. You cannot use this operation to extend the"
                            + " lifetime of a session beyond what was granted when the session was"
                            + " originally created.", AwsNamespaces.STS, 403);
        }
        String subject = iamService.resolveCallerArn(accessKeyId).orElseGet(() ->
                regionResolver.buildGlobalArn("iam", regionResolver.getAccountId(), "root"));
        String token = tokenIssuer.mint(
                WebIdentityTokenIssuer.Algorithm.valueOf(params.getFirst("SigningAlgorithm")),
                federation.get().getIssuerIdentifier(), subject, audiences(params),
                new WebIdentityTokenIssuer.CustomClaims(regionResolver.getAccountId(),
                        regionResolver.getRegion(), tagClaims(params)),
                issuedAt, expiration);
        LOG.debugv("Issued a web identity token for {0} expiring at {1}", subject, expiration);
        String result = new XmlBuilder()
                .elem("WebIdentityToken", token)
                .elem("Expiration", isoDate(expiration))
                .build();
        return Response.ok(
                AwsQueryResponse.envelope("GetWebIdentityToken", AwsNamespaces.STS, result)).build();
    }

    /**
     * The audiences the request carries, in the order their indexes read, each as it was sent: the
     * model holds every one of them to a minimum length of 1, so by here none is absent.
     */
    private static List<String> audiences(MultivaluedMap<String, String> params) {
        return AwsQueryLists.members(params, "Audience", null).stream()
                .map(member -> Objects.requireNonNullElse(params.getFirst(member.prefix()), ""))
                .toList();
    }

    /**
     * The tags the request turns into claims, the last value winning for a key sent twice, which is
     * how a Query map's duplicate key resolves elsewhere in this package. {@code Tag} declares both
     * its fields required, so a member reaching here carries them.
     */
    private static Map<String, String> tagClaims(MultivaluedMap<String, String> params) {
        Map<String, String> claims = new LinkedHashMap<>();
        for (AwsQueryLists.IndexedMember tag : AwsQueryLists.members(params, "Tags", null)) {
            String key = params.getFirst(tag.prefix() + ".Key");
            String value = params.getFirst(tag.prefix() + ".Value");
            if (key != null && value != null) {
                claims.put(key, value);
            }
        }
        return claims;
    }

    /**
     * {@code jwtAlgorithmType} publishes a fixed length of 5 and no enum, and the reference names
     * the two values that length is for, so the value set is documented where the length is
     * declared. It is read against a value the model's own length accepts, and reported in that
     * member's place rather than after the members the model lists below it.
     */
    private static Map<String, String> documentedAlgorithms(MultivaluedMap<String, String> params) {
        String algorithm = params.getFirst("SigningAlgorithm");
        if (algorithm == null || WebIdentityTokenIssuer.Algorithm.names().contains(algorithm)) {
            return Map.of();
        }
        return Map.of("SigningAlgorithm", SmithyValidationErrors.notInEnum(algorithm,
                "signingAlgorithm", WebIdentityTokenIssuer.Algorithm.names()));
    }

    private String credentialsXml(String accessKeyId, String secretKey, String sessionToken, Instant expiration) {
        return new XmlBuilder()
                .start("Credentials")
                  .elem("AccessKeyId", accessKeyId)
                  .elem("SecretAccessKey", secretKey)
                  .elem("SessionToken", sessionToken)
                  .elem("Expiration", isoDate(expiration))
                .end("Credentials")
                .build();
    }

    private String getParam(MultivaluedMap<String, String> params, String name) {
        return params.getFirst(name);
    }

    /**
     * The number as the check that let it through read it. {@link QueryConstraintCheck} takes a
     * Query number with the whitespace around it off, so a padded value satisfies its range, and
     * the read behind it has to take it off too: parsing the value as the request spelled it would
     * throw here and fall back to the default, handing out a session or a token of a length nothing
     * asked for. IAM reads its own `maxItems` the same way.
     */
    private int getIntParam(MultivaluedMap<String, String> params, String name, int defaultValue) {
        String value = params.getFirst(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String isoDate(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    private static String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < length; i++) {
            sb.append(upper.charAt(ThreadLocalRandom.current().nextInt(upper.length())));
        }
        return sb.toString();
    }

    /**
     * The session token for a request, the length {@link #sessionTokenLength} gives it. Every
     * operation that models the member answers it from here, so the floor is written once.
     */
    private String sessionToken(MultivaluedMap<String, String> params) {
        return randomSecret(sessionTokenLength(params));
    }

    /**
     * How long the token for a request will be: the length its {@code MinimumSessionTokenSize}
     * asks for, never under the 200 characters this emulator mints by default, which is what a
     * minimum asks for, and never over the limit a token is held to, so a value outside the 0 to
     * 4096 the model declares is answered by the shape rather than read as a size here.
     */
    private int sessionTokenLength(MultivaluedMap<String, String> params) {
        return Math.max(SESSION_TOKEN_LENGTH,
                Math.min(getIntParam(params, "MinimumSessionTokenSize", 0), SESSION_TOKEN_LIMIT));
    }

    private String randomSecret(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(secureRandom.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
