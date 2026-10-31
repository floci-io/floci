package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.auth.SessionTokenCheck;
import io.github.hectorvent.floci.core.common.auth.SigV4AuthorizationHeader;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.core.common.auth.SigV4SignedRequest;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verifies the SigV4 signature on an AppSync GraphQL data-plane request, so {@code AWS_IAM} auth
 * actually requires a signed caller rather than merely a well-formed {@code Credential=} value.
 *
 * <p>Scoped narrowly to this one endpoint: header-signed only (AppSync's SDKs never presign this
 * call), signing service {@code "appsync"} (not {@code execute-api}), and a fixed canonical path of
 * {@code /v1/apis/{apiId}/graphql} with no query string, since that is the endpoint's entire shape.
 * Modeled on {@code ExecuteApiSigV4Authorizer} and {@code SigV4Validator}, which cannot be reused
 * directly: both hardcode a different signing service name.
 *
 * <p>The well-known local-dev {@code test}/{@code test} credential pair is honoured as a known
 * secret, exactly like every other SigV4 validator in this codebase: it still has to produce a
 * valid signature, it is not a bypass of the signature check itself. No other unregistered access
 * key is ever accepted, and an unresolvable key never falls back to a default secret or an
 * account-root identity.
 */
@ApplicationScoped
public class IamAuthValidator {

    private static final Logger LOG = Logger.getLogger(IamAuthValidator.class);

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SIGNING_SERVICE = "appsync";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String TERMINATOR = "aws4_request";
    private static final String SIGNATURE_MISMATCH = "The request signature we calculated does not match the signature"
            + " you provided. Check your AWS Secret Access Key and signing method. Consult the service documentation"
            + " for details.";
    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";
    private static final String SECURITY_TOKEN = "X-Amz-Security-Token";

    private final IamService iamService;
    private final IamPolicyEvaluator iamPolicyEvaluator;
    private final Clock clock;

    @Inject
    public IamAuthValidator(IamService iamService, IamPolicyEvaluator iamPolicyEvaluator) {
        this(iamService, iamPolicyEvaluator, Clock.systemUTC());
    }

    IamAuthValidator(IamService iamService, IamPolicyEvaluator iamPolicyEvaluator, Clock clock) {
        this.iamService = iamService;
        this.iamPolicyEvaluator = iamPolicyEvaluator;
        this.clock = clock;
    }

    public Map<String, Object> validateRequest(String authorization, String apiId, AuthRequestInfo info) {
        if (authorization == null || !authorization.trim().startsWith(ALGORITHM)) {
            throw AppSyncAuth.unauthorized();
        }
        String accessKeyId = verifySignature(authorization.trim(), apiId, info);
        if (!isEmulatorAllow(accessKeyId)) {
            CallerContext caller = iamService.resolveCallerContext(accessKeyId);
            if (caller != null) {
                String resource = requestArn(info.region(), info.accountId(), apiId);
                IamPolicyEvaluator.Decision decision = iamPolicyEvaluator.evaluate(
                        caller, null, "appsync:GraphQL", resource, null);
                if (decision == IamPolicyEvaluator.Decision.DENY) {
                    throw AppSyncAuth.permissionDenied();
                }
            }
        }
        String userArn = iamService.resolveCallerArn(accessKeyId).orElseGet(
                () -> isEmulatorAllow(accessKeyId)
                        ? AwsArnUtils.Arn.global(AwsRegions.partitionFor(info.region()), "iam", nullToEmpty(info.accountId()), "root").toString()
                        : null);
        if (userArn == null) {
            // resolveSecretKey already proved the key is registered, so this is unreachable for any
            // real access key - only the legacy test/test pair has no IamService entry to resolve.
            throw AppSyncAuth.unauthorized();
        }
        String username = usernameFromArn(userArn, accessKeyId);
        return IdentityBuilder.iam(info.accountId(), accessKeyId, username, userArn, info.sourceIp());
    }

    /**
     * Returns the access key that signed the request. The checks run in AppSync's order with its
     * wording. AppSync names the scope region it refuses, but Floci takes the request's region from
     * that scope, so it is not checked. There is no default secret and no path that authenticates
     * an unregistered key.
     */
    private String verifySignature(String authorization, String apiId, AuthRequestInfo info) {
        Map<String, String> headers = info.requestHeaders();
        SigV4AuthorizationHeader parsed = SigV4AuthorizationHeader.parse(authorization);
        if (parsed.missingEqualSign()) {
            throw AppSyncAuth.incompleteSignature("Invalid key=value pair (missing equal-sign) in Authorization"
                    + " header (hashed with SHA-256 and encoded with Base64): '" + hashed(authorization) + "'.");
        }
        String credential = parsed.credential();
        String signedHeaders = parsed.signedHeaders();
        String signature = parsed.signature();
        String date = hasHeader(headers, "X-Amz-Date") ? header(headers, "X-Amz-Date")
                : hasHeader(headers, "Date") ? header(headers, "Date") : null;
        List<String> missing = new ArrayList<>();
        if (isBlank(credential)) {
            missing.add("Authorization header requires 'Credential' parameter.");
        }
        if (isBlank(signature)) {
            missing.add("Authorization header requires 'Signature' parameter.");
        }
        if (isBlank(signedHeaders)) {
            missing.add("Authorization header requires 'SignedHeaders' parameter.");
        }
        if (date == null) {
            missing.add("Authorization header requires existence of either a 'X-Amz-Date' or a 'Date' header.");
        }
        if (!missing.isEmpty()) {
            throw AppSyncAuth.incompleteSignature(String.join(" ", missing)
                    + " (Hashed with SHA-256 and encoded with Base64) Authorization=" + hashed(authorization));
        }
        Instant signedAt = signedAt(date);
        if (signedAt == null) {
            throw AppSyncAuth.incompleteSignature("Date must be in ISO-8601 'basic format'. Got '" + date
                    + "'. See http://en.wikipedia.org/wiki/ISO_8601");
        }
        checkSigningTime(signedAt);
        for (String name : signedHeaders.split(";")) {
            if (!hasHeader(headers, name)) {
                throw AppSyncAuth.incompleteSignature("'" + name
                        + "' is named as a SignedHeader, but it does not exist in the HTTP request.");
            }
        }
        String names = signedHeaders.toLowerCase(Locale.ROOT);
        if (!SigV4RequestValidator.containsHeader(names, "host")
                && !SigV4RequestValidator.containsHeader(names, ":authority")) {
            throw AppSyncAuth.badRequest("'Host' or ':authority' must be a 'SignedHeader' in the AWS Authorization.");
        }
        String[] scope = credential.split("/", -1);
        if (scope.length != 5) {
            throw AppSyncAuth.incompleteSignature("Credential must have exactly 5 slash-delimited elements,"
                    + " e.g. keyid/date/region/service/term, got '" + credential + "'");
        }
        String amzDate = AMZ_DATE.format(signedAt);
        checkScope(scope, amzDate);
        String accessKeyId = scope[0];
        String secretKey = resolveSecretKey(accessKeyId);
        if (secretKey == null) {
            LOG.debugv("AppSync SigV4 request references unregistered access key={0}",
                    SigV4RequestValidator.sanitizeForLog(accessKeyId));
            throw AppSyncAuth.unrecognizedClient();
        }
        String canonicalRequest = "POST\n/v1/apis/" + apiId + "/graphql\n\n" + canonicalHeaders(names, headers)
                + "\n" + names + "\n" + Digests.sha256Hex(info.rawBody().getBytes(StandardCharsets.UTF_8));
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + String.join("/", scope[1], scope[2], scope[3], scope[4])
                + "\n" + Digests.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String expected;
        try {
            byte[] signingKey = SigV4RequestValidator.deriveSigningKey(secretKey, scope[1], scope[2], scope[3]);
            expected = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(signingKey, stringToSign));
        } catch (Exception e) {
            LOG.debugv(e, "AppSync SigV4 verification failed to complete: {0}", e.getMessage());
            throw AppSyncAuth.unauthorized();
        }
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            LOG.debugv("AppSync SigV4 signature mismatch for accessKey={0}",
                    SigV4RequestValidator.sanitizeForLog(accessKeyId));
            throw AppSyncAuth.badRequest(SIGNATURE_MISMATCH + "\n\nThe Canonical String for this request should have been\n'"
                    + canonicalRequest + "'\n\nThe String-to-Sign should have been\n'" + stringToSign + "'\n");
        }
        checkSessionToken(accessKeyId, headers);
        return accessKeyId;
    }

    private static String hashed(String authorization) {
        return Base64.getEncoder().encodeToString(Digests.sha256(authorization.getBytes(StandardCharsets.UTF_8)));
    }

    /** AppSync reads the basic format, and the extended one too. */
    private static Instant signedAt(String date) {
        try {
            return Instant.from(AMZ_DATE.parse(date));
        } catch (DateTimeException notBasic) {
            try {
                return Instant.parse(date);
            } catch (DateTimeException expected) {
                // Refused with the basic format message.
                return null;
            }
        }
    }

    private void checkSigningTime(Instant signedAt) {
        Instant now = clock.instant();
        Instant earliest = now.minusSeconds(SigV4SignedRequest.MAX_CLOCK_SKEW_SECONDS);
        Instant latest = now.plusSeconds(SigV4SignedRequest.MAX_CLOCK_SKEW_SECONDS);
        if (signedAt.isBefore(earliest)) {
            throw AppSyncAuth.badRequest("Signature expired: " + AMZ_DATE.format(signedAt) + " is now earlier than "
                    + AMZ_DATE.format(earliest) + " (" + AMZ_DATE.format(now) + " - 5 min.)");
        }
        if (signedAt.isAfter(latest)) {
            throw AppSyncAuth.badRequest("Signature not yet current: " + AMZ_DATE.format(signedAt)
                    + " is still later than " + AMZ_DATE.format(latest) + " (" + AMZ_DATE.format(now) + " + 5 min.)");
        }
    }

    /** {@code scope} is key, date, region, service and terminator. AppSync reports every fault at once. */
    private static void checkScope(String[] scope, String amzDate) {
        List<String> faults = new ArrayList<>();
        if (!SIGNING_SERVICE.equals(scope[3])) {
            faults.add("Credential should be scoped to correct service: '" + SIGNING_SERVICE + "'.");
        }
        if (!TERMINATOR.equals(scope[4])) {
            faults.add("Credential should be scoped with a valid terminator: '" + TERMINATOR + "'.");
        }
        if (!amzDate.substring(0, 8).equals(scope[1])) {
            faults.add("Date in Credential scope does not match YYYYMMDD from ISO-8601 version of date from HTTP.");
        }
        if (!faults.isEmpty()) {
            throw AppSyncAuth.badRequest(String.join(" ", faults) + " ");
        }
    }

    /**
     * Resolves the secret backing an access key, or {@code null} for a credential this emulator
     * will not authenticate. Never falls back to the access key id as its own secret and never
     * mints a secret for a key {@link IamService} does not know: that is exactly what let an
     * unregistered key sign its own requests before this fix.
     */
    private String resolveSecretKey(String accessKeyId) {
        if (LEGACY_ACCESS_KEY_ID.equals(accessKeyId)) {
            return LEGACY_SECRET_KEY;
        }
        return iamService.findSecretKey(accessKeyId).orElse(null);
    }

    /** Rejects a session token that does not fit the access key. The local {@code test} key takes any token. */
    private void checkSessionToken(String accessKeyId, Map<String, String> requestHeaders) {
        if (LEGACY_ACCESS_KEY_ID.equals(accessKeyId)) {
            return;
        }
        String presented = hasHeader(requestHeaders, SECURITY_TOKEN) ? header(requestHeaders, SECURITY_TOKEN) : null;
        AppSyncTransportException refusal = switch (SessionTokenCheck.of(iamService, accessKeyId, presented)) {
            case VALID -> null;
            case MISSING -> AppSyncAuth.unrecognizedClient();
            case INVALID -> AppSyncAuth.invalidSessionToken();
        };
        if (refusal != null) {
            throw refusal;
        }
    }

    private static String canonicalHeaders(String signedHeaders, Map<String, String> requestHeaders) {
        StringBuilder canonical = new StringBuilder();
        for (String name : signedHeaders.split(";")) {
            canonical.append(name).append(':')
                    .append(SigV4RequestValidator.normalizeHeaderValue(header(requestHeaders, name))).append('\n');
        }
        return canonical.toString();
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return "";
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue() == null ? "" : entry.getValue();
            }
        }
        return "";
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        return headers != null && headers.keySet().stream().anyMatch(name::equalsIgnoreCase);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isFieldDenied(String accessKeyId, String fieldArn) {
        if (accessKeyId == null || isEmulatorAllow(accessKeyId)) {
            return false;
        }
        CallerContext caller = iamService.resolveCallerContext(accessKeyId);
        if (caller == null) {
            return false;
        }
        return iamPolicyEvaluator.evaluate(caller, null, "appsync:GraphQL", fieldArn, null)
                == IamPolicyEvaluator.Decision.DENY;
    }

    static boolean isEmulatorAllow(String accessKeyId) {
        return "test".equals(accessKeyId);
    }

    static String requestArn(String region, String accountId, String apiId) {
        return appsyncArn(region, accountId, "apis/" + apiId + "/*");
    }

    static String fieldArn(String region, String accountId, String apiId, String typeName, String fieldName) {
        return appsyncArn(region, accountId, "apis/" + apiId + "/types/" + typeName + "/fields/" + fieldName);
    }

    /**
     * The resource these two build is matched against a policy the customer wrote using the ARN
     * AppSync handed them, and that ARN carries the region's partition. Pinning {@code aws} here
     * meant a GovCloud policy naming its own API never matched, and the request was denied.
     */
    private static String appsyncArn(String region, String accountId, String resource) {
        return new AwsArnUtils.Arn(AwsRegions.partitionFor(region), "appsync", nullToEmpty(region),
                nullToEmpty(accountId), resource).toString();
    }

    static String usernameFromArn(String userArn, String accessKeyId) {
        if (userArn == null) {
            return accessKeyId;
        }
        int slash = userArn.lastIndexOf('/');
        if (slash >= 0 && slash < userArn.length() - 1) {
            return userArn.substring(slash + 1);
        }
        return accessKeyId;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
