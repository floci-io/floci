package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.AccountResolver;
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
import org.jboss.logging.Logger;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
    private static final String STS_AUDIENCE = "sts.amazonaws.com";

    private final IamService iamService;
    private final AccountResolver accountResolver;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final AssumeRolePolicyEvaluator trustPolicyEvaluator;
    private final WebIdentityTrustPolicyEvaluator webIdentityTrustEvaluator;
    private final WebIdentityTokenVerifier tokenVerifier;
    private final OidcIssuerKeyLookup oidcIssuerKeys;

    @Context
    HttpHeaders headers;

    @Inject
    public StsQueryHandler(IamService iamService, AccountResolver accountResolver, RegionResolver regionResolver,
                           EmulatorConfig config, AssumeRolePolicyEvaluator trustPolicyEvaluator,
                           WebIdentityTrustPolicyEvaluator webIdentityTrustEvaluator,
                           WebIdentityTokenVerifier tokenVerifier,
                           OidcIssuerKeyLookup oidcIssuerKeys) {
        this.iamService = iamService;
        this.accountResolver = accountResolver;
        this.regionResolver = regionResolver;
        this.config = config;
        this.trustPolicyEvaluator = trustPolicyEvaluator;
        this.webIdentityTrustEvaluator = webIdentityTrustEvaluator;
        this.tokenVerifier = tokenVerifier;
        this.oidcIssuerKeys = oidcIssuerKeys;
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
        String roleArn = getParam(params, "RoleArn");
        String sessionName = getParam(params, "RoleSessionName");
        int durationSeconds = getIntParam(params, "DurationSeconds", 3600);

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
        Instant expiration = Instant.now().plusSeconds(durationSeconds);

        String roleName = roleArn != null && roleArn.contains("/")
                ? roleArn.substring(roleArn.lastIndexOf('/') + 1)
                : "UnknownRole";
        String callerAccountId = regionResolver.getAccountId();
        String accountId = AwsArnUtils.accountOrDefault(roleArn, callerAccountId);

        Response trustDenied = enforceTrustPolicy(roleArn, roleName, accountId);
        if (trustDenied != null) {
            return trustDenied;
        }

        String assumedRoleArn = AwsArnUtils.Arn.of("sts", "", accountId, "assumed-role/" + roleName + "/" + sessionName).toString();
        String assumedRoleId = "AROA" + randomId(16) + ":" + sessionName;

        // Register session so IAM enforcement can resolve the role's policies, RDS/ElastiCache
        // IAM token validation can find the temporary secret key, and account routing can map
        // these temporary credentials to the assumed role's account.
        String sessionPolicy = getParam(params, "Policy");
        iamService.registerSession(accessKeyId, secretKey, roleArn, expiration, sessionPolicy, callerAccountId);

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
     * permit the caller. Returns {@code null} to allow — enforcement disabled, the role is unknown
     * to Floci (permissive, backward-compatible), or the caller is permitted.
     */
    private Response enforceTrustPolicy(String roleArn, String roleName, String roleAccountId) {
        if (!config.services().iam().enforcementEnabled()) {
            return null;
        }
        Optional<IamRole> role = iamService.findRole(roleAccountId, roleName);
        if (role.isEmpty()) {
            return null;
        }
        String auth = headers == null ? null : headers.getHeaderString("Authorization");
        String callerAccount = accountResolver.resolve(auth);
        String callerArn = iamService.resolveCallerArn(
                        auth == null ? null : accountResolver.extractAccessKeyId(auth))
                .orElse(AwsArnUtils.Arn.of("iam", "", callerAccount, "root").toString());
        if (trustPolicyEvaluator.allows(role.get().getAssumeRolePolicyDocument(), callerArn, callerAccount)) {
            return null;
        }
        return AwsQueryResponse.error("AccessDenied",
                "User: " + callerArn + " is not authorized to perform: sts:AssumeRole on resource: " + roleArn,
                AwsNamespaces.STS, 403);
    }

    private Response handleGetCallerIdentity(MultivaluedMap<String, String> params) {
        String accountId = regionResolver.getAccountId();
        String authorization = headers == null ? null : headers.getHeaderString("Authorization");
        String accessKeyId = authorization == null ? null : accountResolver.extractAccessKeyId(authorization);
        String arn = iamService.resolveCallerArn(accessKeyId)
                .orElse(AwsArnUtils.Arn.of("iam", "", accountId, "root").toString());
        String result = new XmlBuilder()
                .elem("UserId", accountId)
                .elem("Account", accountId)
                .elem("Arn", arn)
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetCallerIdentity", AwsNamespaces.STS, result)).build();
    }

    private Response handleGetSessionToken(MultivaluedMap<String, String> params) {
        int durationSeconds = getIntParam(params, "DurationSeconds", 43200);
        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
        Instant expiration = Instant.now().plusSeconds(durationSeconds);

        String result = credentialsXml(accessKeyId, secretKey, sessionToken, expiration);
        // No role ARN — route these credentials back to the caller's account.
        iamService.registerSession(accessKeyId, secretKey, null, expiration, null, regionResolver.getAccountId());
        return Response.ok(AwsQueryResponse.envelope("GetSessionToken", AwsNamespaces.STS, result)).build();
    }

    private Response handleAssumeRoleWithWebIdentity(MultivaluedMap<String, String> params) {
        Response validation = validateRequired(params, "RoleArn", "RoleSessionName", "WebIdentityToken");
        if (validation != null) {
            return validation;
        }
        String roleArn = getParam(params, "RoleArn");
        String sessionName = getParam(params, "RoleSessionName");
        String providerId = getParam(params, "ProviderId");
        String webIdentityToken = getParam(params, "WebIdentityToken");
        int durationSeconds = getIntParam(params, "DurationSeconds", 3600);

        String roleName = roleArn.contains("/") ? roleArn.substring(roleArn.lastIndexOf('/') + 1) : "UnknownRole";
        String callerAccountId = regionResolver.getAccountId();
        String accountId = AwsArnUtils.accountOrDefault(roleArn, callerAccountId);

        // Only tokens naming an issuer Floci itself hosts (an EKS cluster's IRSA OIDC provider) are
        // verifiable, so only those are enforced. An opaque or third-party token keeps the historical
        // permissive behaviour rather than failing a workflow Floci cannot adjudicate.
        WebIdentityOutcome outcome = verifyWebIdentityToken(webIdentityToken, roleName, accountId, roleArn);
        if (outcome.denial() != null) {
            return outcome.denial();
        }
        VerifiedWebIdentity verified = outcome.verified();

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
        Instant expiration = Instant.now().plusSeconds(durationSeconds);

        String assumedRoleArn = AwsArnUtils.Arn.of("sts", "", accountId, "assumed-role/" + roleName + "/" + sessionName).toString();
        String assumedRoleId = "AROA" + randomId(16) + ":" + sessionName;

        String provider = verified != null ? verified.issuer()
                : (providerId != null && !providerId.isBlank() ? providerId : "accounts.google.com");
        String audience = verified != null ? verified.audience() : "sts.amazonaws.com";
        String subject = verified != null ? verified.subject() : "web-identity-subject";

        String sessionPolicy = getParam(params, "Policy");
        iamService.registerSession(accessKeyId, secretKey, roleArn, expiration, sessionPolicy, callerAccountId);

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

    /** The claims of a token Floci issued and verified, used to fill the response accurately. */
    private record VerifiedWebIdentity(String issuer, String subject, String audience) {}

    /**
     * The result of inspecting a web identity token: at most one field is non-null. Both null means
     * the issuer is unknown to Floci, so the token is treated as opaque and accepted.
     */
    private record WebIdentityOutcome(VerifiedWebIdentity verified, Response denial) {

        static WebIdentityOutcome unverifiable() {
            return new WebIdentityOutcome(null, null);
        }

        static WebIdentityOutcome allow(VerifiedWebIdentity verified) {
            return new WebIdentityOutcome(verified, null);
        }

        static WebIdentityOutcome deny(Response denial) {
            return new WebIdentityOutcome(null, denial);
        }
    }

    /**
     * Inspects {@code token} and decides whether it may assume {@code roleArn}. Returns an outcome
     * carrying the verified claims, a denial response, or neither when the token's issuer is not one
     * Floci hosts (preserving the historical permissive behaviour for third-party providers).
     */
    private WebIdentityOutcome verifyWebIdentityToken(String token, String roleName, String roleAccountId,
                                                      String roleArn) {
        Optional<String> issuer = tokenVerifier.peekIssuer(token);
        if (issuer.isEmpty()) {
            return WebIdentityOutcome.unverifiable();
        }
        Optional<RSAPublicKey> key = oidcIssuerKeys.findVerificationKey(issuer.get());
        if (key.isEmpty()) {
            return WebIdentityOutcome.unverifiable();
        }

        WebIdentityToken claims;
        try {
            claims = tokenVerifier.verify(token, key.get(), issuer.get(), STS_AUDIENCE);
        } catch (WebIdentityTokenVerifier.InvalidTokenException e) {
            LOG.debugv("Rejecting web identity token for role {0}: {1}", roleArn, e.getMessage());
            return WebIdentityOutcome.deny(AwsQueryResponse.error("InvalidIdentityToken",
                    e.getMessage(), AwsNamespaces.STS, 400));
        }

        Optional<IamRole> role = iamService.findRole(roleAccountId, roleName);
        if (role.isEmpty()) {
            return WebIdentityOutcome.deny(accessDenied(roleArn));
        }

        String issuerKeyPrefix = stripScheme(issuer.get());
        String oidcProviderArn = AwsArnUtils.Arn.of("iam", "", roleAccountId,
                "oidc-provider/" + issuerKeyPrefix).toString();
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
                new VerifiedWebIdentity(claims.issuer(), claims.subject(), STS_AUDIENCE));
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
        String roleArn = getParam(params, "RoleArn");
        String sessionName = "saml-session";
        int durationSeconds = getIntParam(params, "DurationSeconds", 3600);

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
        Instant expiration = Instant.now().plusSeconds(durationSeconds);

        String roleName = roleArn.contains("/") ? roleArn.substring(roleArn.lastIndexOf('/') + 1) : "UnknownRole";
        String callerAccountId = regionResolver.getAccountId();
        String accountId = AwsArnUtils.accountOrDefault(roleArn, callerAccountId);
        String assumedRoleArn = AwsArnUtils.Arn.of("sts", "", accountId, "assumed-role/" + roleName + "/" + sessionName).toString();
        String assumedRoleId = "AROA" + randomId(16) + ":" + sessionName;

        iamService.registerSession(accessKeyId, secretKey, roleArn, expiration, null, callerAccountId);

        String result = new XmlBuilder()
                .raw(credentialsXml(accessKeyId, secretKey, sessionToken, expiration))
                .start("AssumedRoleUser")
                  .elem("Arn", assumedRoleArn)
                  .elem("AssumedRoleId", assumedRoleId)
                .end("AssumedRoleUser")
                .elem("PackedPolicySize", "0")
                .elem("Issuer", "https://saml.example.com")
                .elem("Audience", "urn:amazon:webservices")
                .elem("NameQualifier", "saml-qualifier")
                .elem("SubjectType", "persistent")
                .elem("Subject", "saml-subject")
                .build();
        return Response.ok(AwsQueryResponse.envelope("AssumeRoleWithSAML", AwsNamespaces.STS, result)).build();
    }

    private Response handleGetFederationToken(MultivaluedMap<String, String> params) {
        Response validation = validateRequired(params, "Name");
        if (validation != null) {
            return validation;
        }
        String name = getParam(params, "Name");
        int durationSeconds = getIntParam(params, "DurationSeconds", 43200);

        String accessKeyId = "ASIA" + randomId(16);
        String secretKey = randomSecret(40);
        String sessionToken = randomSecret(200);
        Instant expiration = Instant.now().plusSeconds(durationSeconds);
        String accountId = regionResolver.getAccountId();
        String federatedUserId = accountId + ":" + name;
        String federatedUserArn = AwsArnUtils.Arn.of("sts", "", accountId, "federated-user/" + name).toString();

        String sessionPolicy = getParam(params, "Policy");
        // Register federation token so enforcement can scope its policies via session policy.
        // The federated-user ARN already carries the caller's account, so reuse it as the origin.
        iamService.registerSession(accessKeyId, secretKey, federatedUserArn, expiration, sessionPolicy, accountId);

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
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private static String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < length; i++) {
            sb.append(upper.charAt(ThreadLocalRandom.current().nextInt(upper.length())));
        }
        return sb.toString();
    }

    private static String randomSecret(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(ThreadLocalRandom.current().nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
