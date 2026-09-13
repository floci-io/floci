package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
    private static final String TERMINATOR = "aws4_request";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";
    private static final String SECURITY_TOKEN = "X-Amz-Security-Token";

    private final AccountResolver accountResolver;
    private final IamService iamService;
    private final IamPolicyEvaluator iamPolicyEvaluator;

    @Inject
    public IamAuthValidator(AccountResolver accountResolver,
                            IamService iamService,
                            IamPolicyEvaluator iamPolicyEvaluator) {
        this.accountResolver = accountResolver;
        this.iamService = iamService;
        this.iamPolicyEvaluator = iamPolicyEvaluator;
    }

    public Map<String, Object> validateRequest(String authorization, String apiId, AuthRequestInfo info) {
        String accessKeyId = accountResolver.extractAccessKeyId(authorization);
        if (accessKeyId == null || accessKeyId.isBlank()) {
            throw AppSyncAuth.unauthorized();
        }
        verifySignature(authorization, apiId, info, accessKeyId);
        if (!isEmulatorAllow(accessKeyId)) {
            CallerContext caller = iamService.resolveCallerContext(accessKeyId);
            if (caller != null) {
                String resource = requestArn(info.region(), info.accountId(), apiId);
                IamPolicyEvaluator.Decision decision = iamPolicyEvaluator.evaluate(
                        caller, null, "appsync:GraphQL", resource, null);
                if (decision == IamPolicyEvaluator.Decision.DENY) {
                    throw AppSyncAuth.unauthorized();
                }
            }
        }
        String userArn = iamService.resolveCallerArn(accessKeyId).orElseGet(
                () -> isEmulatorAllow(accessKeyId) ? "arn:aws:iam::" + nullToEmpty(info.accountId()) + ":root"
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
     * Fails closed: a missing/unparsable {@code Authorization} header, a credential not scoped to
     * {@code appsync}, a signing timestamp outside the accepted clock skew, an access key this
     * emulator has never registered, or a signature that does not match are all rejected. There is
     * no default secret and no path that authenticates an unregistered key.
     */
    private void verifySignature(String authorization, String apiId, AuthRequestInfo info, String accessKeyId) {
        SignedRequest signed = headerSignedRequest(authorization, info.requestHeaders());
        if (signed == null) {
            throw AppSyncAuth.unauthorized();
        }
        CredentialScope scope = CredentialScope.parse(signed.credential());
        if (scope == null || !SIGNING_SERVICE.equals(scope.service()) || !accessKeyId.equals(scope.accessKeyId())) {
            throw AppSyncAuth.unauthorized();
        }
        Instant signedAt;
        try {
            signedAt = Instant.from(AMZ_DATE.parse(signed.amzDate()));
        } catch (Exception e) {
            throw AppSyncAuth.unauthorized();
        }
        if (Math.abs(Instant.now().getEpochSecond() - signedAt.getEpochSecond()) > MAX_CLOCK_SKEW_SECONDS) {
            throw AppSyncAuth.unauthorized();
        }
        String secretKey = resolveSecretKey(accessKeyId);
        if (secretKey == null) {
            LOG.debugv("AppSync SigV4 request references unregistered access key={0}", sanitizeForLog(accessKeyId));
            throw AppSyncAuth.unauthorized();
        }
        checkSessionToken(accessKeyId, info.requestHeaders());
        try {
            String canonicalUri = "/v1/apis/" + apiId + "/graphql";
            String payloadHash = sha256Hex(info.rawBody().getBytes(StandardCharsets.UTF_8));
            String canonicalHeaders = canonicalHeaders(signed.signedHeaders(), info.requestHeaders());
            String canonicalRequest = "POST\n" + canonicalUri + "\n\n"
                    + canonicalHeaders + "\n" + signed.signedHeaders() + "\n" + payloadHash;
            String stringToSign = ALGORITHM + "\n" + signed.amzDate() + "\n" + scope.credentialScope() + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey = deriveSigningKey(secretKey, scope.date(), scope.region(), scope.service());
            String expected = hexEncode(hmacSha256(signingKey, stringToSign));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signed.signature().getBytes(StandardCharsets.UTF_8))) {
                LOG.debugv("AppSync SigV4 signature mismatch for accessKey={0}", sanitizeForLog(accessKeyId));
                throw AppSyncAuth.unauthorized();
            }
        } catch (AppSyncTransportException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugv(e, "AppSync SigV4 verification failed to complete: {0}", e.getMessage());
            throw AppSyncAuth.unauthorized();
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

    /**
     * Rejects a temporary ({@code ASIA...}) credential that does not present the session token
     * Floci issued with it. Mirrors {@code ExecuteApiSigV4Authorizer.checkSessionToken}: a matching
     * secret alone is not the whole credential, since AWS requires the session token on every
     * request made with temporary credentials to confirm it is live and genuinely STS-issued.
     *
     * <p>The token is compared against the value recorded at mint time, not merely required to be
     * present, so a fabricated token is rejected along with a missing one. A session recorded
     * before Floci tracked tokens has no issued value to compare against ({@code findSessionToken}
     * is empty there), so presence is all that can be demanded of it: rejecting that case would
     * lock out an otherwise valid credential.
     *
     * <p>The token does not need to ride in {@code SignedHeaders}: comparing it against the issued
     * value binds it regardless of where it rode, the same way the execute-api authorizer does.
     */
    private void checkSessionToken(String accessKeyId, Map<String, String> requestHeaders) {
        if (!IamService.isTemporaryAccessKey(accessKeyId)) {
            return;
        }
        String presented = header(requestHeaders, SECURITY_TOKEN);
        if (isBlank(presented)) {
            LOG.debugv("AppSync SigV4 request uses temporary credential accessKey={0} with no {1}",
                    sanitizeForLog(accessKeyId), SECURITY_TOKEN);
            throw AppSyncAuth.unauthorized();
        }
        String issued = iamService.findSessionToken(accessKeyId).orElse(null);
        if (issued == null) {
            return;
        }
        if (!MessageDigest.isEqual(issued.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            LOG.debugv("AppSync SigV4 request presents a session token that does not match the one"
                    + " issued for accessKey={0}", sanitizeForLog(accessKeyId));
            throw AppSyncAuth.unauthorized();
        }
    }

    private record SignedRequest(String credential, String signedHeaders, String signature, String amzDate) {}

    /**
     * {@code X-Amz-Date} is a real HTTP header on a header-signed request, never a field inside the
     * {@code Authorization} value itself (that only ever carries {@code Credential}/
     * {@code SignedHeaders}/{@code Signature}), so it is read from {@code requestHeaders} - the same
     * source {@code ExecuteApiSigV4Authorizer} reads it from. There is no fallback to the plain
     * {@code Date} header: unlike {@code X-Amz-Date}, it is RFC 1123-formatted, which {@link
     * #AMZ_DATE} can never parse, so a request that only sets {@code Date} is rejected below
     * regardless.
     */
    private static SignedRequest headerSignedRequest(String authorization, Map<String, String> requestHeaders) {
        if (authorization == null) {
            return null;
        }
        String trimmed = authorization.trim();
        if (!trimmed.regionMatches(true, 0, ALGORITHM, 0, ALGORITHM.length())) {
            return null;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String part : trimmed.substring(ALGORITHM.length()).split(",")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                parameters.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
            }
        }
        String credential = parameters.get("Credential");
        String signedHeaders = parameters.get("SignedHeaders");
        String signature = parameters.get("Signature");
        String amzDate = header(requestHeaders, "X-Amz-Date");
        if (isBlank(credential) || isBlank(signedHeaders) || isBlank(signature) || isBlank(amzDate)) {
            return null;
        }
        return new SignedRequest(credential, signedHeaders.toLowerCase(Locale.ROOT), signature, amzDate);
    }

    private record CredentialScope(String accessKeyId, String date, String region, String service) {

        String credentialScope() {
            return date + "/" + region + "/" + service + "/" + TERMINATOR;
        }

        static CredentialScope parse(String credential) {
            if (credential == null) {
                return null;
            }
            String[] parts = credential.split("/");
            if (parts.length != 5 || !TERMINATOR.equals(parts[4])) {
                return null;
            }
            if (parts[0].isBlank() || parts[1].length() != 8 || parts[2].isBlank() || parts[3].isBlank()) {
                return null;
            }
            return new CredentialScope(parts[0], parts[1], parts[2], parts[3].toLowerCase(Locale.ROOT));
        }
    }

    private static String canonicalHeaders(String signedHeaders, Map<String, String> requestHeaders) {
        StringBuilder canonical = new StringBuilder();
        for (String name : signedHeaders.split(";")) {
            canonical.append(name).append(':').append(normalizeHeaderValue(header(requestHeaders, name))).append('\n');
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

    private static String normalizeHeaderValue(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static byte[] deriveSigningKey(String secretKey, String date, String region, String service)
            throws Exception {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, TERMINATOR);
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] input) throws Exception {
        return hexEncode(MessageDigest.getInstance("SHA-256").digest(input));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Strips control characters from attacker-controlled values before they reach a log line. */
    private static String sanitizeForLog(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "");
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

    /**
     * The resource these two build is matched against a policy the customer wrote using the ARN
     * AppSync handed them, and that ARN carries the region's partition. Pinning {@code aws} here
     * meant a GovCloud policy naming its own API never matched, and the request was denied.
     */
    private static String appsyncArnPrefix(String region) {
        return "arn:" + AwsRegions.partitionFor(region) + ":appsync:";
    }

    static String requestArn(String region, String accountId, String apiId) {
        return appsyncArnPrefix(region) + nullToEmpty(region) + ":" + nullToEmpty(accountId)
                + ":apis/" + apiId + "/*";
    }

    static String fieldArn(String region, String accountId, String apiId, String typeName, String fieldName) {
        return appsyncArnPrefix(region) + nullToEmpty(region) + ":" + nullToEmpty(accountId)
                + ":apis/" + apiId + "/types/" + typeName + "/fields/" + fieldName;
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
