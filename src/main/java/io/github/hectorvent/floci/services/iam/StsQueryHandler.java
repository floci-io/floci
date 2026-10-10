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
import io.github.hectorvent.floci.core.common.WebIdentityToken;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import io.github.hectorvent.floci.core.common.XmlBuilder;
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

/**
 * Query-protocol handler for STS (Security Token Service) actions.
 * Receives pre-dispatched calls from {@link AwsQueryController}.
 * All responses use the STS XML namespace {@code https://sts.amazonaws.com/doc/2011-06-15/}.
 */
@ApplicationScoped
public class StsQueryHandler {

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
                           SAMLTrustPolicyEvaluator samlTrustEvaluator) {
        this(iamService, accountResolver, regionResolver, config, trustPolicyEvaluator, webIdentityTrustEvaluator,
                tokenVerifier, oidcIssuerKeys, samlProviderService, samlTrustEvaluator, Clock.systemUTC());
    }

    StsQueryHandler(IamService iamService, AccountResolver accountResolver, RegionResolver regionResolver,
                    EmulatorConfig config, AssumeRolePolicyEvaluator trustPolicyEvaluator,
                    WebIdentityTrustPolicyEvaluator webIdentityTrustEvaluator,
                    WebIdentityTokenVerifier tokenVerifier,
                    OidcIssuerKeyLookup oidcIssuerKeys,
                    SAMLProviderService samlProviderService,
                    SAMLTrustPolicyEvaluator samlTrustEvaluator,
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
        this.clock = clock;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        LOG.debugv("STS action: {0}", action);

        return switch (action) {
            case "AssumeRole"                  -> handleAssumeRole(params);
            case "GetCallerIdentity"           -> handleGetCallerIdentity(params);
            case "GetSessionToken"             -> handleGetSessionToken(params);
            case "AssumeRoleWithWebIdentity"   -> handleAssumeRoleWithWebIdentity(params);
            case "AssumeRoleWithSAML"          -> handleAssumeRoleWithSAML(params);
            case "GetFederationToken"          -> handleGetFederationToken(params);
            case "DecodeAuthorizationMessage"  -> handleDecodeAuthorizationMessage(params);
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
     * the request's keys: the global keys, {@code aws:PrincipalArn}, {@code sts:RoleSessionName},
     * and {@code sts:ExternalId} when the caller sends one.
     */
    private Response enforceTrustPolicy(String roleArn, IamRole role, String roleAccountId,
                                        MultivaluedMap<String, String> params) {
        String callerAccount = regionResolver.getAccountId();
        Optional<IamService.CallerArns> callerArns = iamService.resolveCallerArns(callerAccessKeyId());
        String callerArn = callerArns.map(IamService.CallerArns::callerArn)
                .orElse(regionResolver.buildGlobalArn("iam", callerAccount, "root"));
        String principalArn = callerArns.map(IamService.CallerArns::principalArn).orElse(callerArn);

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
        if (trustPolicyEvaluator.allows(role.getAssumeRolePolicyDocument(), callerArn, principalArn,
                callerAccount, requestContext)) {
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
        String authorization = headers == null ? null : headers.getHeaderString("Authorization");
        String accessKeyId = authorization == null ? null : accountResolver.extractAccessKeyId(authorization);
        String sessionToken = headers == null ? null : headers.getHeaderString("X-Amz-Security-Token");
        if (sessionToken == null && params != null) {
            sessionToken = getParam(params, "X-Amz-Security-Token");
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

    private Response handleGetSessionToken(MultivaluedMap<String, String> params) {
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
        String auth = IamEnforcementFilter.requestAuthorization(
                headers == null ? null : headers.getHeaderString("Authorization"),
                uriInfo == null ? null : uriInfo.getQueryParameters());
        return auth == null ? null : accountResolver.extractAccessKeyId(auth);
    }

    private Response handleGetFederationToken(MultivaluedMap<String, String> params) {
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
