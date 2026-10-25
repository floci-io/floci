package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.common.OidcIssuerKeyLookup;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.common.WebIdentityToken;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.iam.model.DelegationRequest;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Query-protocol handler for STS (Security Token Service) actions.
 * Receives pre-dispatched calls from {@link AwsQueryController}.
 * All responses use the STS XML namespace {@code https://sts.amazonaws.com/doc/2011-06-15/}.
 */
@ApplicationScoped
public class StsQueryHandler {

    /** accessKeyIdType, the only member GetAccessKeyInfo takes: 16 to 128 characters of {@code [\w]}. */
    private static final Pattern ACCESS_KEY_ID_PATTERN = Pattern.compile("[\\w]*");
    private static final int ACCESS_KEY_ID_MIN_LENGTH = 16;
    private static final int ACCESS_KEY_ID_MAX_LENGTH = 128;

    private static final Logger LOG = Logger.getLogger(StsQueryHandler.class);
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    /** Where the five task policies the guide names for a privileged session live. */
    private static final String ROOT_TASK_POLICY_PATH = "/root-task/";
    /** "the maximum session duration of 900 seconds (15 minutes)", which is also the default here. */
    private static final int MAX_ROOT_SESSION_SECONDS = 900;
    private static final int MAX_SESSION_TOKEN_SIZE = 4096;
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
                           CentralRootAccess centralRootAccess) {
        this(iamService, accountResolver, regionResolver, config, trustPolicyEvaluator, webIdentityTrustEvaluator,
                tokenVerifier, oidcIssuerKeys, samlProviderService, samlTrustEvaluator, delegationRequestService,
                centralRootAccess, Clock.systemUTC());
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
        this.clock = clock;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        LOG.debugv("STS action: {0}", action);

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
            default -> AwsQueryResponse.error("UnsupportedOperation",
                    "Operation " + action + " is not supported by STS.", AwsNamespaces.STS, 400);
        };
    }

    private Response handleAssumeRole(MultivaluedMap<String, String> params) {
        Response validation = validateRequired(params, "RoleArn", "RoleSessionName");
        if (validation != null) {
            return validation;
        }
        Response durationValidation = validateDurationSeconds(params, 900, 43200);
        if (durationValidation != null) {
            return durationValidation;
        }
        String roleArn = getParam(params, "RoleArn");
        String sessionName = getParam(params, "RoleSessionName");
        int durationSeconds = getIntParam(params, "DurationSeconds", 3600);

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
        Instant expiration = clock.instant().plusSeconds(durationSeconds);

        String roleName = roleArn != null && roleArn.contains("/")
                ? roleArn.substring(roleArn.lastIndexOf('/') + 1)
                : "UnknownRole";
        String callerAccountId = regionResolver.getAccountId();
        String accountId = AwsArnUtils.accountOrDefault(roleArn, callerAccountId);

        IamRole role = iamService.findRole(accountId, roleName).orElse(null);
        Response trustDenied = enforceTrustPolicy(roleArn, role, accountId, params);
        if (trustDenied != null) {
            return trustDenied;
        }

        String sessionRoleArn = canonicalRoleArn(role, accountId, roleName);
        String assumedRoleArn = assumedRoleArn(sessionRoleArn, accountId, roleName, sessionName);
        String roleId = (role != null && role.getRoleId() != null) ? role.getRoleId() : ("AROA" + randomId(16));
        String assumedRoleId = roleId + ":" + sessionName;

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
                .elem("PackedPolicySize", "0")
                .build();
        return Response.ok(AwsQueryResponse.envelope("AssumeRole", AwsNamespaces.STS, result)).build();
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
                                        MultivaluedMap<String, String> params) {
        String callerAccount = regionResolver.getAccountId();
        IamService.CallerArns caller = iamService.resolveCallerArns(callerAccessKeyId())
                .orElseGet(() -> IamService.CallerArns.accountRoot(
                        regionResolver.buildGlobalArn("iam", callerAccount, "root"), callerAccount));
        String callerArn = caller.callerArn();

        if (role == null || !IamService.roleArnMatches(roleArn, role)) {
            return AwsQueryResponse.error("AccessDenied",
                    "User: " + callerArn + " is not authorized to perform: sts:AssumeRole on resource: " + roleArn,
                    AwsNamespaces.STS, 403);
        }
        if (!config.services().iam().enforcementEnabled()) {
            return null;
        }

        Map<String, List<String>> requestContext = IamConditionContextResolver.withGlobalContext(
                null, roleArn, regionResolver.getRegion(), callerAccount, roleAccountId);
        requestContext.put("sts:RoleSessionName", List.of(getParam(params, "RoleSessionName")));
        String externalId = getParam(params, "ExternalId");
        if (externalId != null) {
            requestContext.put("sts:ExternalId", List.of(externalId));
        }
        if (trustPolicyEvaluator.allows(role.getAssumeRolePolicyDocument(), caller, callerAccount,
                requestContext)) {
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

    private Response handleGetCallerIdentity(MultivaluedMap<String, String> params) {
        String accountId = regionResolver.getAccountId();
        String accessKeyId = callerAccessKeyId();
        String sessionToken = headers == null ? null : headers.getHeaderString("X-Amz-Security-Token");
        if (sessionToken == null && params != null) {
            sessionToken = getParam(params, "X-Amz-Security-Token");
        }
        if (sessionToken == null && uriInfo != null) {
            sessionToken = uriInfo.getQueryParameters().getFirst("X-Amz-Security-Token");
        }
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
     * <p>A key Floci does not hold is the one answer the reference leaves open. It models no error
     * for it and says only that "providing a deleted access key might return an error that the key
     * doesn't exist", so this answers {@code InvalidParameterValue}, the published common error
     * nearest that sentence, and {@code docs/services/sts.md} records it as the deviation it is.
     */
    private Response handleGetAccessKeyInfo(MultivaluedMap<String, String> params) {
        Response validation = validateAccessKeyId(params);
        if (validation != null) {
            return validation;
        }
        String accessKeyId = getParam(params, "AccessKeyId");
        Optional<String> account = iamService.findAccountForAccessKeyId(accessKeyId);
        if (account.isEmpty()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "The access key " + accessKeyId + " does not exist.", AwsNamespaces.STS, 400);
        }
        String result = new XmlBuilder().elem("Account", account.get()).build();
        return Response.ok(AwsQueryResponse.envelope("GetAccessKeyInfo", AwsNamespaces.STS, result)).build();
    }

    /**
     * accessKeyIdType: required, 16 to 128 characters of {@code [\w]*}, which the reference
     * describes as "any upper- or lowercase letter or digit". Read before the lookup, so a
     * malformed id answers for its shape rather than for a key that could not exist.
     *
     * <p>An absent member and a present empty one are different answers: only the absent one is
     * "Member must not be null", where {@code AccessKeyId=} is a value that fails the minimum. That
     * is what IAM answers for an empty {@code RoleName} on this same wire.
     *
     * <p>The member is named the way AWS names it in a constraint message, lower camel case, and
     * the offending value is quoted beside it, which is what the one recorded rendering Floci
     * follows elsewhere does: {@link IamService} reads {@code roleName} that way, and so does this
     * handler's own {@code DurationSeconds} check. The older STS operations name the parameter as
     * written instead, which is a drift of its own rather than something to copy here.
     */
    private Response validateAccessKeyId(MultivaluedMap<String, String> params) {
        String accessKeyId = params.getFirst("AccessKeyId");
        String constraint;
        if (accessKeyId == null) {
            constraint = "Member must not be null";
        } else if (accessKeyId.length() < ACCESS_KEY_ID_MIN_LENGTH) {
            constraint = "Member must have length greater than or equal to " + ACCESS_KEY_ID_MIN_LENGTH;
        } else if (accessKeyId.length() > ACCESS_KEY_ID_MAX_LENGTH) {
            constraint = "Member must have length less than or equal to " + ACCESS_KEY_ID_MAX_LENGTH;
        } else if (!ACCESS_KEY_ID_PATTERN.matcher(accessKeyId).matches()) {
            constraint = "Member must satisfy regular expression pattern: "
                    + ACCESS_KEY_ID_PATTERN.pattern();
        } else {
            return null;
        }
        String value = accessKeyId == null ? "null" : "'" + accessKeyId + "'";
        return AwsQueryResponse.error("ValidationError",
                SmithyValidationErrors.message(List.of("Value " + value
                        + " at 'accessKeyId' failed to satisfy constraint: " + constraint)),
                AwsNamespaces.STS, 400);
    }

    private Response handleGetSessionToken(MultivaluedMap<String, String> params) {
        if (IamService.isTemporaryAccessKey(callerAccessKeyId())) {
            return AwsQueryResponse.error("AccessDenied", "Cannot call GetSessionToken with session credentials",
                    AwsNamespaces.STS, 403);
        }
        Response durationValidation = validateDurationSeconds(params, 900, 129600);
        if (durationValidation != null) {
            return durationValidation;
        }
        int durationSeconds = getIntParam(params, "DurationSeconds", 43200);
        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
        Instant expiration = clock.instant().plusSeconds(durationSeconds);

        String result = credentialsXml(accessKeyId, secretKey, sessionToken, expiration);
        // No role ARN: the credentials route back to the caller's account and act as the caller.
        iamService.registerIssuedSession(accessKeyId, secretKey, sessionToken, null, expiration, null,
                regionResolver.getAccountId(), callerAccessKeyId());
        return Response.ok(AwsQueryResponse.envelope("GetSessionToken", AwsNamespaces.STS, result)).build();
    }

    private Response handleAssumeRoleWithWebIdentity(MultivaluedMap<String, String> params) {
        Response validation = validateRequired(params, "RoleArn", "RoleSessionName", "WebIdentityToken");
        if (validation != null) {
            return validation;
        }
        validation = validateIamRoleArn(getParam(params, "RoleArn"));
        if (validation != null) {
            return validation;
        }
        Response durationValidation = validateDurationSeconds(params, 900, 43200);
        if (durationValidation != null) {
            return durationValidation;
        }
        String roleArn = getParam(params, "RoleArn");
        String sessionName = getParam(params, "RoleSessionName");
        String providerId = getParam(params, "ProviderId");
        String webIdentityToken = getParam(params, "WebIdentityToken");
        int durationSeconds = getIntParam(params, "DurationSeconds", 3600);

        String roleName = roleArn.contains("/") ? roleArn.substring(roleArn.lastIndexOf('/') + 1) : "UnknownRole";
        String callerAccountId = regionResolver.getAccountId();
        String accountId = AwsArnUtils.accountOrDefault(roleArn, callerAccountId);

        WebIdentityOutcome outcome = verifyWebIdentityToken(webIdentityToken, roleName, accountId, roleArn);
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

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
        Instant expiration = clock.instant().plusSeconds(durationSeconds);

        String sessionRoleArn = canonicalRoleArn(role, accountId, roleName);
        String assumedRoleArn = assumedRoleArn(sessionRoleArn, accountId, roleName, sessionName);
        String assumedRoleId = "AROA" + randomId(16) + ":" + sessionName;

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
                .elem("PackedPolicySize", "0")
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

    /** Inspects {@code token} and decides whether it may assume {@code roleArn}. */
    private WebIdentityOutcome verifyWebIdentityToken(String token, String roleName, String roleAccountId,
                                                      String roleArn) {
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
                oidcProviderArn, issuerKeyPrefix, conditionClaims)) {
            LOG.debugv("Trust policy on role {0} denies web identity subject {1}",
                    roleArn, claims.subject());
            return WebIdentityOutcome.deny(accessDenied(roleArn));
        }

        // verify() already required the audience list to contain STS_AUDIENCE.
        return WebIdentityOutcome.allow(
                new VerifiedWebIdentity(claims.issuer(), claims.subject(), STS_AUDIENCE), role.get());
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
        Response validation = validateRequired(params, "RoleArn", "PrincipalArn", "SAMLAssertion");
        if (validation != null) {
            return validation;
        }
        Response durationValidation = validateDurationSeconds(params, 900, 43200);
        if (durationValidation != null) {
            return durationValidation;
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
                "namequalifier", List.of(verified.nameQualifier())))) {
            throw new AwsException("AccessDenied", "Not authorized to perform sts:AssumeRoleWithSAML on resource: " + roleArn, 403);
        }

        String sessionName = verified.subject().replaceAll("[^A-Za-z0-9+=,.@_-]", "_");
        if (sessionName.length() > 64) {
            sessionName = sessionName.substring(0, 64);
        }
        Instant requestedExpiration = clock.instant().plusSeconds(durationSeconds);
        Instant roleExpiration = clock.instant().plusSeconds(role.getMaxSessionDuration());
        Instant expiration = verified.expiration().isBefore(requestedExpiration) ? verified.expiration() : requestedExpiration;
        if (roleExpiration.isBefore(expiration)) {
            expiration = roleExpiration;
        }
        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
        String sessionRoleArn = canonicalRoleArn(role, accountId, roleName);
        String assumedRoleArn = assumedRoleArn(sessionRoleArn, accountId, roleName, sessionName);
        String assumedRoleId = "AROA" + randomId(16) + ":" + sessionName;

        iamService.registerSession(accessKeyId, secretKey, sessionToken, sessionRoleArn, expiration, null,
                callerAccountId, sessionName, assumedRoleId);
        String result = new XmlBuilder()
                .raw(credentialsXml(accessKeyId, secretKey, sessionToken, expiration))
                .start("AssumedRoleUser").elem("Arn", assumedRoleArn).elem("AssumedRoleId", assumedRoleId).end("AssumedRoleUser")
                .elem("PackedPolicySize", "0").elem("Issuer", verified.issuer()).elem("Audience", "urn:amazon:webservices")
                .elem("NameQualifier", verified.nameQualifier()).elem("SubjectType", verified.subjectType()).elem("Subject", verified.subject()).build();
        return Response.ok(AwsQueryResponse.envelope("AssumeRoleWithSAML", AwsNamespaces.STS, result)).build();
    }

    /** The access key that signed this request, in the header or presigned in the query. */
    private String callerAccessKeyId() {
        String authorization = headers == null ? null : headers.getHeaderString("Authorization");
        String auth = IamEnforcementFilter.requestAuthorization(
                authorization == null || authorization.isBlank() ? null : authorization,
                uriInfo == null ? null : uriInfo.getQueryParameters());
        return auth == null ? null : accountResolver.extractAccessKeyId(auth);
    }

    private Response handleGetFederationToken(MultivaluedMap<String, String> params) {
        if (IamService.isTemporaryAccessKey(callerAccessKeyId())) {
            return AwsQueryResponse.error("AccessDenied", IamService.FEDERATION_TOKEN_SESSION_MESSAGE,
                    AwsNamespaces.STS, 403);
        }
        Response validation = validateRequired(params, "Name");
        if (validation != null) {
            return validation;
        }
        Response durationValidation = validateDurationSeconds(params, 900, 129600);
        if (durationValidation != null) {
            return durationValidation;
        }
        String name = getParam(params, "Name");
        int durationSeconds = getIntParam(params, "DurationSeconds", 43200);

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
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
                .elem("PackedPolicySize", "0")
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
        Response validation = validateRequired(params, "TradeInToken");
        if (validation != null) {
            return validation;
        }
        DelegationRequestService.Redeemed redeemed = delegationRequestService.redeem(
                regionResolver.getAccountId(), getParam(params, "TradeInToken"));
        DelegationRequest request = redeemed.request();

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
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
                request.getOwnerAccountId());

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
        Response validation = validateRequired(params, "TargetPrincipal", "TaskPolicyArn.arn");
        if (validation != null) {
            return validation;
        }
        String targetPrincipal = getParam(params, "TargetPrincipal");
        if (targetPrincipal.length() < 12 || targetPrincipal.length() > 2048) {
            String constraint = targetPrincipal.length() < 12
                    ? "Member must have length greater than or equal to 12"
                    : "Member must have length less than or equal to 2048";
            return AwsQueryResponse.error("ValidationError",
                    SmithyValidationErrors.message(List.of("Value '" + targetPrincipal
                            + "' at 'targetPrincipal' failed to satisfy constraint: " + constraint)),
                    AwsNamespaces.STS, 400);
        }
        Response durationValidation = validateDurationSeconds(params, 0, 900);
        if (durationValidation != null) {
            return durationValidation;
        }
        Response tokenSizeValidation = validateMinimumSessionTokenSize(params);
        if (tokenSizeValidation != null) {
            return tokenSizeValidation;
        }
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
        String sessionToken = randomSecret(Math.max(200,
                getIntParam(params, "MinimumSessionTokenSize", 0)));
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

    /**
     * {@code MinimumSessionTokenSize} is 0 to 4096 in the model, "The value must not exceed 4,096
     * bytes", and the size it asks for is materialized, so the bound is enforced rather than
     * clamped: a value outside it is the same {@code ValidationError} every other member violation
     * answers, and one that is no integer at all the same {@code InvalidParameterValue}
     * {@code DurationSeconds} gives.
     */
    private Response validateMinimumSessionTokenSize(MultivaluedMap<String, String> params) {
        String value = params.getFirst("MinimumSessionTokenSize");
        if (value == null) {
            return null;
        }
        int size;
        try {
            size = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return AwsQueryResponse.error("InvalidParameterValue", "Value " + value + " for parameter"
                    + " MinimumSessionTokenSize is invalid. Reason: Must be an integer.",
                    AwsNamespaces.STS, 400);
        }
        if (size < 0 || size > MAX_SESSION_TOKEN_SIZE) {
            String constraint = size < 0
                    ? "Member must have value greater than or equal to 0"
                    : "Member must have value less than or equal to " + MAX_SESSION_TOKEN_SIZE;
            return AwsQueryResponse.error("ValidationError",
                    SmithyValidationErrors.message(List.of("Value '" + size
                            + "' at 'minimumSessionTokenSize' failed to satisfy constraint: " + constraint)),
                    AwsNamespaces.STS, 400);
        }
        return null;
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
        Response validation = validateRequired(params, "EncodedMessage");
        if (validation != null) {
            return validation;
        }
        String encodedMessage = getParam(params, "EncodedMessage");
        String result = new XmlBuilder().elem("DecodedMessage", encodedMessage).build();
        return Response.ok(AwsQueryResponse.envelope("DecodeAuthorizationMessage", AwsNamespaces.STS, result)).build();
    }

    private Response validateRequired(MultivaluedMap<String, String> params, String... names) {
        for (String name : names) {
            String value = params.getFirst(name);
            if (value == null || value.isBlank()) {
                return AwsQueryResponse.error("ValidationError",
                        SmithyValidationErrors.message(List.of(SmithyValidationErrors.notNull(name))),
                        AwsNamespaces.STS, 400);
            }
        }
        return null;
    }

    /**
     * Validates the optional {@code DurationSeconds} parameter against {@code minSeconds}/{@code maxSeconds}.
     * Returns {@code null} when the parameter is absent or valid; otherwise a {@code ValidationError} (out of
     * range) or {@code InvalidParameterValue} (not an integer) response, matching AWS's own wire behavior.
     */
    private Response validateDurationSeconds(MultivaluedMap<String, String> params, int minSeconds, int maxSeconds) {
        String value = params.getFirst("DurationSeconds");
        if (value == null) {
            return null;
        }
        int durationSeconds;
        try {
            durationSeconds = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "Value " + value + " for parameter DurationSeconds is invalid. Reason: Must be an integer.",
                    AwsNamespaces.STS, 400);
        }
        if (durationSeconds < minSeconds) {
            return AwsQueryResponse.error("ValidationError",
                    "1 validation error detected: Value '" + durationSeconds + "' at 'durationSeconds' failed to "
                            + "satisfy constraint: Member must have value greater than or equal to " + minSeconds,
                    AwsNamespaces.STS, 400);
        }
        if (durationSeconds > maxSeconds) {
            return AwsQueryResponse.error("ValidationError",
                    "1 validation error detected: Value '" + durationSeconds + "' at 'durationSeconds' failed to "
                            + "satisfy constraint: Member must have value less than or equal to " + maxSeconds,
                    AwsNamespaces.STS, 400);
        }
        return null;
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

    private int getIntParam(MultivaluedMap<String, String> params, String name, int defaultValue) {
        String value = params.getFirst(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
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

    private String randomSecret(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(secureRandom.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
