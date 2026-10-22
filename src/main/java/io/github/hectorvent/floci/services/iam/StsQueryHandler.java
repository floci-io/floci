package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.common.OidcIssuerKeyLookup;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.common.WebIdentityToken;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.iam.model.DelegationRequest;
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
    private static final int ROLE_CHAINING_MAX_SESSION_SECONDS = 3600;

    private static final Logger LOG = Logger.getLogger(StsQueryHandler.class);
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
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
                           DelegationRequestService delegationRequestService) {
        this(iamService, accountResolver, regionResolver, config, trustPolicyEvaluator, webIdentityTrustEvaluator,
                tokenVerifier, oidcIssuerKeys, samlProviderService, samlTrustEvaluator, delegationRequestService,
                Clock.systemUTC());
    }

    StsQueryHandler(IamService iamService, AccountResolver accountResolver, RegionResolver regionResolver,
                    EmulatorConfig config, AssumeRolePolicyEvaluator trustPolicyEvaluator,
                    WebIdentityTrustPolicyEvaluator webIdentityTrustEvaluator,
                    WebIdentityTokenVerifier tokenVerifier,
                    OidcIssuerKeyLookup oidcIssuerKeys,
                    SAMLProviderService samlProviderService,
                    SAMLTrustPolicyEvaluator samlTrustEvaluator,
                    DelegationRequestService delegationRequestService,
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
        Response durationDenied = validateRoleSessionDuration(params, role, true);
        if (durationDenied != null) {
            return durationDenied;
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
        Response durationDenied = validateRoleSessionDuration(params, role, false);
        if (durationDenied != null) {
            return durationDenied;
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
                        "1 validation error detected: Value null at '" + name
                        + "' failed to satisfy constraint: Member must not be null",
                        AwsNamespaces.STS, 400);
            }
        }
        return null;
    }

    /**
     * AWS refuses a session longer than the role's {@code MaxSessionDuration}, and longer than an
     * hour when the caller signs with credentials of another role session (role chaining).
     * Called after {@link #validateDurationSeconds}, so the value parses.
     */
    private Response validateRoleSessionDuration(MultivaluedMap<String, String> params, IamRole role,
                                                 boolean checkRoleChaining) {
        String value = params.getFirst("DurationSeconds");
        if (value == null) {
            return null;
        }
        int durationSeconds = Integer.parseInt(value);
        if (durationSeconds > role.getMaxSessionDuration()) {
            return AwsQueryResponse.error("ValidationError",
                    "The requested DurationSeconds exceeds the MaxSessionDuration set for this role.",
                    AwsNamespaces.STS, 400);
        }
        if (checkRoleChaining && durationSeconds > ROLE_CHAINING_MAX_SESSION_SECONDS
                && iamService.resolveCallerArns(callerAccessKeyId())
                        .filter(caller -> IamService.CallerArns.TYPE_ASSUMED_ROLE.equals(caller.principalType()))
                        .isPresent()) {
            return AwsQueryResponse.error("ValidationError",
                    "The requested DurationSeconds exceeds the 1 hour session limit for roles assumed by role chaining.",
                    AwsNamespaces.STS, 400);
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
