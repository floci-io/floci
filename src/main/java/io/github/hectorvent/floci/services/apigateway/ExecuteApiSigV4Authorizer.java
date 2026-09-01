package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verifies the SigV4 signature on an execute-api data-plane request, so a route or method whose
 * {@code authorizationType} is {@code AWS_IAM} actually requires a signed caller instead of merely
 * recording that it does.
 *
 * <p>Both signing placements real API Gateway accepts are handled: an {@code Authorization} header
 * and a presigned query string ({@code X-Amz-Algorithm=AWS4-HMAC-SHA256}). The canonical request is
 * rebuilt from the request exactly as it arrived (the raw path the client signed, its raw query
 * string, the headers named in {@code SignedHeaders}, and the SHA-256 of the body), and the derived
 * signature is compared in constant time.
 *
 * <p>A temporary credential must additionally present the session token it was issued with, which
 * is checked by {@link #checkSessionToken}.
 *
 * <h2>Deliberate deviations from real AWS</h2>
 * <ul>
 *   <li><strong>No authorization, only authentication.</strong> A valid signature from any known
 *       access key is accepted; {@code execute-api:Invoke} is not evaluated against the caller's
 *       IAM policies or a resource policy. Floci's IAM policy evaluation is opt-in and off by
 *       default, so gating the data plane on it would make the common case unusable.</li>
 *   <li><strong>The credential scope's region is not pinned</strong> to the API's region. Floci
 *       resolves a request's region <em>from</em> that scope, so pinning it would be circular.</li>
 *   <li>The well-known local-dev {@code test}/{@code test} credential pair is honoured, mirroring
 *       the identical fallback in {@code S3Service}, {@code PreSignedUrlFilter} and the
 *       ElastiCache/RDS validators. No other unregistered access key is accepted.</li>
 * </ul>
 */
@ApplicationScoped
public class ExecuteApiSigV4Authorizer {

    private static final Logger LOG = Logger.getLogger(ExecuteApiSigV4Authorizer.class);

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SIGNING_SERVICE = "execute-api";
    private static final String TERMINATOR = "aws4_request";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Real API Gateway rejects a header-signed request whose {@code X-Amz-Date} is more than five
     * minutes from the service's clock. Emulating it keeps a captured request from being replayed
     * indefinitely against a route the caller is testing authorization on.
     */
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    /** Longest {@code X-Amz-Expires} AWS accepts on a presigned request (7 days). */
    private static final long MAX_PRESIGNED_EXPIRY_SECONDS = 604800;

    /** Where a temporary credential's session token rides: a header, or a presigned query parameter. */
    private static final String SECURITY_TOKEN = "X-Amz-Security-Token";

    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";

    private final IamService iamService;
    private final RegionResolver regionResolver;

    @Inject
    public ExecuteApiSigV4Authorizer(IamService iamService, RegionResolver regionResolver) {
        this.iamService = iamService;
        this.regionResolver = regionResolver;
    }

    /** Why a request was rejected. Dispatch maps this onto the wire error its API type uses. */
    public enum Failure {
        /** Nothing on the request even claims to be SigV4-signed. */
        MISSING,
        /** SigV4 credentials are present but unparsable, incomplete, or not scoped to execute-api. */
        MALFORMED,
        /** The credential names an access key this emulator has never issued. */
        UNKNOWN_KEY,
        /** The signing timestamp is outside the accepted window, or a presigned URL has expired. */
        EXPIRED,
        /** The signature does not match the one derived from the caller's secret key. */
        MISMATCH
    }

    /**
     * The caller behind a verified signature, as API Gateway surfaces it on the proxy event
     * ({@code requestContext.identity} on REST, {@code requestContext.authorizer.iam} on HTTP).
     */
    public record CallerIdentity(String accessKey, String accountId, String userArn, String userId) {}

    /** A {@code null} failure means authorized, mirroring the authorizer results in dispatch. */
    public record Result(Failure failure, String detail, CallerIdentity identity) {

        public boolean authorized() {
            return failure == null;
        }

        static Result rejected(Failure failure, String detail) {
            return new Result(failure, detail, null);
        }
    }

    public Result authorize(String httpMethod, HttpHeaders headers, UriInfo uriInfo, byte[] body,
                            String signedRequestPath) {
        try {
            return verify(httpMethod, headers, uriInfo, body, signedRequestPath);
        } catch (Exception e) {
            // A malformed credential, an unparsable date, an unsupported charset: every one of
            // these is a request we could not verify, and an unverifiable request is not
            // authorized. Logged rather than swallowed so an operational fault is diagnosable.
            LOG.debugv(e, "execute-api SigV4 verification failed to complete: {0}", e.getMessage());
            return Result.rejected(Failure.MALFORMED, "signature could not be verified");
        }
    }

    private Result verify(String httpMethod, HttpHeaders headers, UriInfo uriInfo, byte[] body,
                          String signedRequestPath) throws Exception {
        MultivaluedMap<String, String> queryParameters = uriInfo.getQueryParameters();
        String authorization = headers == null ? null : headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        boolean presigned = ALGORITHM.equals(queryParameters.getFirst("X-Amz-Algorithm"));

        if (!presigned && (authorization == null || authorization.isBlank())) {
            return Result.rejected(Failure.MISSING, "request is not SigV4-signed");
        }

        SignedRequest signed = presigned
                ? presignedRequest(queryParameters)
                : headerSignedRequest(authorization, headers);
        if (signed == null) {
            return Result.rejected(Failure.MALFORMED, "SigV4 credentials are incomplete");
        }

        CredentialScope scope = CredentialScope.parse(signed.credential());
        if (scope == null) {
            return Result.rejected(Failure.MALFORMED, "credential scope is malformed");
        }
        if (!SIGNING_SERVICE.equals(scope.service())) {
            return Result.rejected(Failure.MALFORMED,
                    "credential is scoped to service " + scope.service() + ", not " + SIGNING_SERVICE);
        }
        if (!signed.amzDate().startsWith(scope.date())) {
            return Result.rejected(Failure.MALFORMED, "credential scope date does not match X-Amz-Date");
        }

        // SigV4 requires host to be signed, and here it is what binds a signature to one API.
        // A virtual-host request carries its apiId only in the Host header: the path the client
        // signed is /{stage}/{route}, identical across every API on this emulator. Without host in
        // the canonical request, a signature minted for one API replays against any other API that
        // happens to expose the same stage and route.
        if (!containsHeader(signed.signedHeaders(), "host")) {
            return Result.rejected(Failure.MALFORMED, "SignedHeaders does not cover host");
        }

        Instant signedAt = Instant.from(AMZ_DATE.parse(signed.amzDate()));
        Result expiry = checkExpiry(signedAt, signed.expiresSeconds(), presigned);
        if (expiry != null) {
            return expiry;
        }

        String secretKey = resolveSecretKey(scope.accessKeyId());
        if (secretKey == null) {
            LOG.debugv("execute-api request references unregistered access key={0}",
                    sanitizeForLog(scope.accessKeyId()));
            return Result.rejected(Failure.UNKNOWN_KEY, "access key is not registered");
        }

        Result sessionToken = checkSessionToken(scope.accessKeyId(), headers, queryParameters);
        if (sessionToken != null) {
            return sessionToken;
        }

        String payloadHash = payloadHash(headers, body, presigned, signed.signedHeaders());
        String canonicalHeaders = canonicalHeaders(signed.signedHeaders(), headers, uriInfo);
        String canonicalQueryString = canonicalQueryString(uriInfo.getRequestUri().getRawQuery(), presigned);
        byte[] signingKey = deriveSigningKey(secretKey, scope.date(), scope.region(), scope.service());

        String rawPath = signedRequestPath != null
                ? signedRequestPath
                : uriInfo.getRequestUri().getRawPath();
        for (String canonicalUri : canonicalUriCandidates(rawPath)) {
            String canonicalRequest = httpMethod + "\n"
                    + canonicalUri + "\n"
                    + canonicalQueryString + "\n"
                    + canonicalHeaders + "\n"
                    + signed.signedHeaders() + "\n"
                    + payloadHash;
            String stringToSign = ALGORITHM + "\n"
                    + signed.amzDate() + "\n"
                    + scope.credentialScope() + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            String expected = hexEncode(hmacSha256(signingKey, stringToSign));
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signed.signature().getBytes(StandardCharsets.UTF_8))) {
                return new Result(null, null, resolveIdentity(scope.accessKeyId()));
            }
        }

        LOG.debugv("execute-api SigV4 signature mismatch for accessKey={0}",
                sanitizeForLog(scope.accessKeyId()));
        return Result.rejected(Failure.MISMATCH, "signature does not match");
    }

    private Result checkExpiry(Instant signedAt, Long expiresSeconds, boolean presigned) {
        Instant now = Instant.now();
        if (presigned) {
            if (expiresSeconds == null || expiresSeconds < 1 || expiresSeconds > MAX_PRESIGNED_EXPIRY_SECONDS) {
                return Result.rejected(Failure.MALFORMED, "X-Amz-Expires is missing or out of range");
            }
            if (now.isAfter(signedAt.plusSeconds(expiresSeconds))) {
                return Result.rejected(Failure.EXPIRED, "presigned request expired");
            }
            // A presigned URL signed in the future is still a forgery attempt, not a slow clock.
            if (signedAt.isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))) {
                return Result.rejected(Failure.EXPIRED, "presigned request is not yet valid");
            }
            return null;
        }
        if (Math.abs(now.getEpochSecond() - signedAt.getEpochSecond()) > MAX_CLOCK_SKEW_SECONDS) {
            return Result.rejected(Failure.EXPIRED,
                    "signature date " + AMZ_DATE.format(signedAt) + " is outside the accepted window");
        }
        return null;
    }

    /**
     * Resolves the secret backing an access key, or {@code null} when the credential is one this
     * emulator will not authenticate. Failing closed here is the point: an earlier revision of the
     * ElastiCache and RDS validators fell back to the access key id as its own secret, which let
     * any caller self-sign a request.
     *
     * <p>{@code findSecretKey} screens out an expired session and an inactive access key on its
     * own. {@code resolveAccountId} is required on top of it because a verified caller has to be
     * attributed to an account to reach the integration at all: a credential this emulator cannot
     * place in one is not something to authenticate. It rejects the same expired AssumeRole
     * credential and deactivated long-term key a second time, which is why those cases stay
     * covered whichever of the two gates moves.
     *
     * <p>The session token a temporary credential carries is checked separately, by
     * {@link #checkSessionToken}.
     */
    private String resolveSecretKey(String accessKeyId) {
        if (LEGACY_ACCESS_KEY_ID.equals(accessKeyId)) {
            return LEGACY_SECRET_KEY;
        }
        String secretKey = iamService.findSecretKey(accessKeyId).orElse(null);
        if (secretKey == null) {
            return null;
        }
        if (iamService.resolveAccountId(accessKeyId).isEmpty()) {
            LOG.debugv("execute-api request uses an expired or inactive credential: accessKey={0}",
                    sanitizeForLog(accessKeyId));
            return null;
        }
        return secretKey;
    }

    /**
     * Rejects a temporary credential that does not present the session token Floci issued with it,
     * returning {@code null} when there is nothing to object to.
     *
     * <p>The secret alone is not the whole credential. AWS requires the session token on every
     * request made with temporary credentials precisely because it is what confirms the credential
     * is live and genuinely STS-issued, so accepting an {@code ASIA...} key on its secret alone
     * lets an incomplete credential through a route that is supposed to demand a complete one.
     *
     * <p>The token is compared against the value recorded at mint time rather than merely required
     * to be present, so a fabricated token fails as well as a missing one. Presence is all that can
     * be demanded of a session stored before Floci recorded tokens: {@code findSessionToken} is
     * empty there, and rejecting it would lock out a credential that is otherwise perfectly valid.
     * {@code findSecretKey(accessKeyId, sessionToken)} is the stricter one-step alternative, which
     * this deliberately does not use because it cannot tell that case apart from a real mismatch.
     *
     * <p>Folding the token into the canonical request is deliberately not required. Whether a
     * service covers it by {@code SignedHeaders} or appends it after signing is service-specific in
     * SigV4, and comparing against the issued value binds the token regardless of where it rode.
     */
    private Result checkSessionToken(String accessKeyId, HttpHeaders headers,
                                     MultivaluedMap<String, String> queryParameters) {
        if (!IamService.isTemporaryAccessKey(accessKeyId)) {
            return null;
        }
        String presented = queryParameters.getFirst(SECURITY_TOKEN);
        if (isBlank(presented) && headers != null) {
            presented = headers.getHeaderString(SECURITY_TOKEN);
        }
        if (isBlank(presented)) {
            LOG.debugv("execute-api request uses temporary credential accessKey={0} with no {1}",
                    sanitizeForLog(accessKeyId), SECURITY_TOKEN);
            return Result.rejected(Failure.UNKNOWN_KEY, "temporary credential presents no session token");
        }
        String issued = iamService.findSessionToken(accessKeyId).orElse(null);
        if (issued == null) {
            return null;
        }
        if (!MessageDigest.isEqual(issued.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            LOG.debugv("execute-api request presents a session token that does not match the one"
                    + " issued for accessKey={0}", sanitizeForLog(accessKeyId));
            return Result.rejected(Failure.UNKNOWN_KEY, "session token does not match the issued credential");
        }
        return null;
    }

    /**
     * Builds the caller for a signature that already verified. The {@code orElseGet} fallbacks are
     * reachable only for the legacy {@code test} credential: {@link #resolveSecretKey} has proved
     * every other accepted key resolves to a live account.
     */
    private CallerIdentity resolveIdentity(String accessKeyId) {
        String accountId = iamService.resolveAccountId(accessKeyId)
                .orElseGet(regionResolver::getAccountId);
        String userArn = iamService.resolveCallerArn(accessKeyId)
                .orElseGet(() -> "arn:aws:iam::" + accountId + ":root");
        String userId = iamService.findAccessKey(accessKeyId)
                .map(AccessKey::getUserName)
                .flatMap(iamService::findUser)
                .map(IamUser::getUserId)
                .orElse(accessKeyId);
        return new CallerIdentity(accessKeyId, accountId, userArn, userId);
    }

    // ──────────────────────────── Signed-request parsing ────────────────────────────

    private record SignedRequest(String credential, String signedHeaders, String signature,
                                 String amzDate, Long expiresSeconds) {}

    private static SignedRequest headerSignedRequest(String authorization, HttpHeaders headers) {
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
        String amzDate = headers.getHeaderString("X-Amz-Date");
        if (amzDate == null || amzDate.isBlank()) {
            amzDate = headers.getHeaderString("Date");
        }
        if (isBlank(credential) || isBlank(signedHeaders) || isBlank(signature) || isBlank(amzDate)) {
            return null;
        }
        return new SignedRequest(credential, signedHeaders.toLowerCase(Locale.ROOT), signature, amzDate, null);
    }

    private static SignedRequest presignedRequest(MultivaluedMap<String, String> queryParameters) {
        String credential = queryParameters.getFirst("X-Amz-Credential");
        String signedHeaders = queryParameters.getFirst("X-Amz-SignedHeaders");
        String signature = queryParameters.getFirst("X-Amz-Signature");
        String amzDate = queryParameters.getFirst("X-Amz-Date");
        String expires = queryParameters.getFirst("X-Amz-Expires");
        if (isBlank(credential) || isBlank(signedHeaders) || isBlank(signature) || isBlank(amzDate)) {
            return null;
        }
        Long expiresSeconds;
        try {
            expiresSeconds = expires == null ? null : Long.valueOf(expires.trim());
        } catch (NumberFormatException e) {
            LOG.debugv("execute-api presigned request carries a non-numeric X-Amz-Expires: {0}",
                    sanitizeForLog(expires));
            expiresSeconds = null;
        }
        return new SignedRequest(credential, signedHeaders.toLowerCase(Locale.ROOT), signature,
                amzDate, expiresSeconds);
    }

    private record CredentialScope(String accessKeyId, String date, String region, String service) {

        String credentialScope() {
            return date + "/" + region + "/" + service + "/" + TERMINATOR;
        }

        /** {@code credential} is expected already percent-decoded: a header credential is never
         *  encoded, and JAX-RS decodes the presigned {@code X-Amz-Credential} before we see it. */
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

    // ──────────────────────────── Canonical request ────────────────────────────

    /**
     * The canonical URI candidates a signer could have produced for this raw path. Non-S3 SigV4
     * URI-encodes each path segment a second time on top of the encoding already on the wire; for
     * an all-ASCII path the two forms are identical, so only paths carrying escapes produce a
     * second candidate. Trying both keeps a legitimately signed request from being rejected over
     * which convention its signer follows.
     */
    private static List<String> canonicalUriCandidates(String rawPath) {
        String raw = isBlank(rawPath) ? "/" : rawPath;
        List<String> candidates = new ArrayList<>(2);
        candidates.add(raw);
        String[] segments = raw.split("/", -1);
        StringBuilder doubleEncoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                doubleEncoded.append('/');
            }
            doubleEncoded.append(uriEncode(segments[i]));
        }
        if (!candidates.contains(doubleEncoded.toString())) {
            candidates.add(doubleEncoded.toString());
        }
        return candidates;
    }

    private static String canonicalQueryString(String rawQuery, boolean dropSignature) {
        if (isBlank(rawQuery)) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = decodeQueryComponent(equals >= 0 ? pair.substring(0, equals) : pair);
            String value = decodeQueryComponent(equals >= 0 ? pair.substring(equals + 1) : "");
            if (dropSignature && "X-Amz-Signature".equals(name)) {
                continue;
            }
            pairs.add(new String[]{uriEncode(name), uriEncode(value)});
        }
        pairs.sort(Comparator.<String[], String>comparing(pair -> pair[0]).thenComparing(pair -> pair[1]));
        StringBuilder canonical = new StringBuilder();
        for (String[] pair : pairs) {
            if (!canonical.isEmpty()) {
                canonical.append('&');
            }
            canonical.append(pair[0]).append('=').append(pair[1]);
        }
        return canonical.toString();
    }

    private static String canonicalHeaders(String signedHeaders, HttpHeaders headers, UriInfo uriInfo) {
        StringBuilder canonical = new StringBuilder();
        for (String name : signedHeaders.split(";")) {
            String value = "host".equals(name) ? hostHeader(headers, uriInfo) : headerValue(headers, name);
            canonical.append(name).append(':').append(normalizeHeaderValue(value)).append('\n');
        }
        return canonical.toString();
    }

    private static String headerValue(HttpHeaders headers, String name) {
        String value = headers == null ? null : headers.getHeaderString(name);
        return value == null ? "" : value;
    }

    /**
     * The {@code host} value the client signed. Taken from the {@code Host} header the request
     * carried rather than from the resolved URI, because that is what the signer hashed; the URI
     * is only a fallback for callers (notably unit tests) that build a request without one.
     */
    private static String hostHeader(HttpHeaders headers, UriInfo uriInfo) {
        String host = headers == null ? null : headers.getHeaderString("Host");
        if (host != null && !host.isBlank()) {
            return host;
        }
        URI requestUri = uriInfo.getRequestUri();
        int port = requestUri.getPort();
        return port > 0 && port != 80 && port != 443
                ? requestUri.getHost() + ":" + port
                : String.valueOf(requestUri.getHost());
    }

    private static String normalizeHeaderValue(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    /**
     * The payload hash the canonical request must carry.
     *
     * <p>A literal {@code x-amz-content-sha256} is deliberately <em>not</em> taken on trust: the
     * body is hashed instead. For an honest request the two are equal, and for a tampered one they
     * are not, which turns a swapped body into a signature mismatch. Trusting the header would let
     * a caller keep a valid signature over a body they had replaced, since the header value the
     * signer hashed would still be the one presented.
     *
     * <p>The sentinels ({@code UNSIGNED-PAYLOAD}, the {@code STREAMING-*} forms) mean the caller
     * chose not to sign the body, and are honoured only when {@code x-amz-content-sha256} is itself
     * in {@code SignedHeaders}: that is, when the choice is covered by the signature. A presigned
     * request is unsigned-payload by convention; every AWS presigner emits it that way.
     */
    private String payloadHash(HttpHeaders headers, byte[] body, boolean presigned,
                               String signedHeaders) throws Exception {
        String declared = headers == null ? null : headers.getHeaderString("x-amz-content-sha256");
        if (declared != null && !declared.isBlank()
                && containsHeader(signedHeaders, "x-amz-content-sha256")
                && !isSha256Hex(declared.trim())) {
            return declared.trim();
        }
        if (presigned) {
            return "UNSIGNED-PAYLOAD";
        }
        return sha256Hex(body == null ? new byte[0] : body);
    }

    private static boolean containsHeader(String signedHeaders, String name) {
        for (String header : signedHeaders.split(";")) {
            if (name.equals(header)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSha256Hex(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    // ──────────────────────────── Crypto and encoding helpers ────────────────────────────

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

    /** RFC 3986 percent-encoding as SigV4 defines it: {@code /} is escaped, {@code -._~} are not. */
    private static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xFF;
            char ch = (char) b;
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                encoded.append(ch);
            } else {
                encoded.append('%')
                        .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    /**
     * Percent-decodes a query-string component without {@code URLDecoder}'s form-encoding rule that
     * a literal {@code +} means a space: SigV4 signers escape a space as {@code %20}, so a raw
     * {@code +} on the wire is a plus sign and re-encoding it as a space would break the signature.
     */
    private static String decodeQueryComponent(String value) {
        if (value == null) {
            return "";
        }
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Strips control characters from attacker-controlled values before they reach a log line. */
    private static String sanitizeForLog(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "");
    }
}
