package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
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

    private final IamService iamService;
    private final AccountResolver accountResolver;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final AssumeRolePolicyEvaluator trustPolicyEvaluator;

    @Context
    HttpHeaders headers;

    @Inject
    public StsQueryHandler(IamService iamService, AccountResolver accountResolver, RegionResolver regionResolver,
                           EmulatorConfig config, AssumeRolePolicyEvaluator trustPolicyEvaluator) {
        this.iamService = iamService;
        this.accountResolver = accountResolver;
        this.regionResolver = regionResolver;
        this.config = config;
        this.trustPolicyEvaluator = trustPolicyEvaluator;
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
        String provider = providerId != null && !providerId.isBlank() ? providerId : "accounts.google.com";

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
                .elem("Audience", "sts.amazonaws.com")
                .elem("SubjectFromWebIdentityToken", "web-identity-subject")
                .build();
        return Response.ok(AwsQueryResponse.envelope("AssumeRoleWithWebIdentity", AwsNamespaces.STS, result)).build();
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
