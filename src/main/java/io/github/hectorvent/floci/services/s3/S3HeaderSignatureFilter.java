package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Verifies the SigV4 signature carried in the {@code Authorization} header of an S3 request when
 * {@code floci.services.s3.enforce-auth} is enabled.
 *
 * <p>The presigned placements are already verified elsewhere ({@link PreSignedUrlFilter} for the
 * query string, {@link S3PostPolicySigner} for a browser POST). This filter closes the remaining
 * placement, the one every AWS SDK uses for ordinary calls: the canonical request is rebuilt from
 * the request as it arrived (raw path, canonical query string, the headers named in
 * {@code SignedHeaders}, and the {@code x-amz-content-sha256} value the client signed), the
 * signature is derived with the key's secret, and the two are compared in constant time. When the
 * declared content hash is a real digest rather than an {@code UNSIGNED-PAYLOAD} or
 * {@code STREAMING-*} sentinel, the body is hashed too, so a signed request cannot be replayed
 * with a different payload.
 *
 * <p>Error codes, statuses and ordering follow real S3: a structurally bad header is
 * {@code 400 AuthorizationHeaderMalformed}; a request with no date is {@code 403 AccessDenied};
 * a missing {@code x-amz-content-sha256} is {@code 400 InvalidRequest}; an unregistered key is
 * {@code 403 InvalidAccessKeyId}; a request more than fifteen minutes from server time is
 * {@code 403 RequestTimeTooSkewed}; a signature that does not verify is
 * {@code 403 SignatureDoesNotMatch}; and a body that does not match its declared hash is
 * {@code 400 XAmzContentSHA256Mismatch}.
 *
 * <h2>Deliberate deviations from real AWS</h2>
 * <ul>
 *   <li>The credential scope's region is not checked against the bucket's region. Floci resolves
 *       a request's region <em>from</em> that scope, so pinning it would be circular.</li>
 *   <li>The per-chunk signatures of an {@code aws-chunked} upload are not verified; only the seed
 *       signature over the headers is. The controller strips the chunk framing itself.</li>
 *   <li>The well-known local-dev {@code test}/{@code test} credential pair is honoured, mirroring
 *       {@link PreSignedUrlFilter} and {@link S3PostPolicySigner}.</li>
 * </ul>
 *
 * <p>Nothing here runs with the flag off: the filter returns before reading a single header, so
 * the default configuration keeps accepting any well-formed {@code Authorization} header exactly
 * as before.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class S3HeaderSignatureFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(S3HeaderSignatureFilter.class);

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SIGNING_SERVICE = "s3";
    private static final String TERMINATOR = "aws4_request";
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(15);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final S3Service s3Service;
    private final IamService iamService;

    @Context
    ResourceInfo resourceInfo;

    @Inject
    public S3HeaderSignatureFilter(S3Service s3Service, IamService iamService) {
        this.s3Service = s3Service;
        this.iamService = iamService;
    }

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        if (!s3Service.isAuthEnforced() || !routedToS3()) {
            return;
        }
        String authorization = ctx.getHeaderString("Authorization");
        if (authorization == null || !authorization.startsWith(ALGORITHM + " ")) {
            return;
        }

        String credential = component(authorization, "Credential");
        String signedHeaders = component(authorization, "SignedHeaders");
        String signature = component(authorization, "Signature");
        if (credential == null || signedHeaders == null || signature == null) {
            abort(ctx, 400, "AuthorizationHeaderMalformed", "The authorization header is malformed; "
                    + "the authorization header requires three components: "
                    + "Credential, SignedHeaders, and Signature.");
            return;
        }
        String[] scope = credential.split("/");
        if (scope.length != 5 || !TERMINATOR.equals(scope[4])) {
            abort(ctx, 400, "AuthorizationHeaderMalformed", "The authorization header is malformed; "
                    + "the Credential is mal-formed; expecting "
                    + "\"<YOUR-AKID>/YYYYMMDD/REGION/SERVICE/aws4_request\".");
            return;
        }
        String accessKeyId = scope[0];
        String scopeDate = scope[1];
        String region = scope[2];
        if (!SIGNING_SERVICE.equals(scope[3])) {
            abort(ctx, 400, "AuthorizationHeaderMalformed", "The authorization header is malformed; "
                    + "incorrect service \"" + scope[3] + "\". This endpoint belongs to \""
                    + SIGNING_SERVICE + "\".");
            return;
        }

        Instant requestTime = requestTime(ctx);
        if (requestTime == null) {
            abort(ctx, 403, "AccessDenied", "AWS authentication requires a valid Date or x-amz-date header");
            return;
        }
        String amzDate = AMZ_DATE.format(requestTime);
        if (!amzDate.startsWith(scopeDate)) {
            abort(ctx, 400, "AuthorizationHeaderMalformed", "The authorization header is malformed; "
                    + "Date in Credential scope does not match YYYYMMDD from ISO-8601 version of "
                    + "date from HTTP.");
            return;
        }

        String declaredHash = ctx.getHeaderString("x-amz-content-sha256");
        if (declaredHash == null || declaredHash.isBlank()) {
            abort(ctx, 400, "InvalidRequest", "Missing required header for this request: x-amz-content-sha256");
            return;
        }
        declaredHash = declaredHash.trim();

        Optional<String> secretKey = S3PostPolicySigner.resolveSecretKey(
                iamService, accessKeyId, ctx.getHeaderString("X-Amz-Security-Token"));
        if (secretKey.isEmpty()) {
            abort(ctx, 403, "InvalidAccessKeyId",
                    "The AWS Access Key Id you provided does not exist in our records.");
            return;
        }

        if (Duration.between(requestTime, Instant.now()).abs().compareTo(MAX_CLOCK_SKEW) > 0) {
            abort(ctx, 403, "RequestTimeTooSkewed",
                    "The difference between the request time and the server's time is too large.");
            return;
        }

        boolean matches;
        try {
            matches = SigV4RequestValidator.containsHeader(signedHeaders, "host") && signatureMatches(
                    ctx, secretKey.get(), scopeDate, region, amzDate, signedHeaders, declaredHash, signature);
        } catch (Exception e) {
            LOG.debugv(e, "S3 header SigV4 verification failed to complete for accessKey={0}", accessKeyId);
            matches = false;
        }
        if (!matches) {
            abort(ctx, 403, "SignatureDoesNotMatch", "The request signature we calculated does not "
                    + "match the signature you provided. Check your key and signing method.");
            return;
        }

        if (SigV4RequestValidator.isSha256Hex(declaredHash) && !bodyMatches(ctx, declaredHash)) {
            abort(ctx, 400, "XAmzContentSHA256Mismatch",
                    "The provided 'x-amz-content-sha256' header does not match what was computed.");
        }
    }

    private boolean routedToS3() {
        return resourceInfo != null && S3Controller.class.equals(resourceInfo.getResourceClass());
    }

    private boolean signatureMatches(ContainerRequestContext ctx, String secretKey, String scopeDate,
                                     String region, String amzDate, String signedHeaders,
                                     String payloadHash, String signature) throws Exception {
        URI requestUri = ctx.getProperty(S3VirtualHostFilter.ORIGINAL_REQUEST_URI_PROPERTY) instanceof URI uri
                ? uri
                : ctx.getUriInfo().getRequestUri();
        String host = S3VirtualHostFilter.resolveHost(ctx.getHeaderString("Host"),
                ctx.getHeaderString("X-Forwarded-Host"), requestUri);

        StringBuilder canonicalHeaders = new StringBuilder();
        for (String name : signedHeaders.split(";")) {
            String value = "host".equals(name) ? host : ctx.getHeaderString(name);
            canonicalHeaders.append(name).append(':')
                    .append(PreSignedUrlFilter.canonicalizeHeaderValue(value)).append('\n');
        }

        String canonicalRequest = ctx.getMethod() + "\n"
                + requestUri.getRawPath() + "\n"
                + PreSignedUrlFilter.buildCanonicalQueryString(ctx.getUriInfo().getQueryParameters()) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + scopeDate + "/" + region + "/" + SIGNING_SERVICE + "/" + TERMINATOR + "\n"
                + SigV4RequestValidator.sha256Hex(canonicalRequest);
        byte[] signingKey = SigV4RequestValidator.deriveSigningKey(secretKey, scopeDate, region, SIGNING_SERVICE);
        String expected = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(signingKey, stringToSign));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hashes the entity and hands it back to the request untouched. The S3 controllers already
     * receive the body as a {@code byte[]}, so buffering it here costs nothing extra. The stream
     * is read directly rather than gated on {@code hasEntity()}: that flag is false for a request
     * without a {@code Content-Type}, and the AWS CLI sends most bucket configuration bodies
     * (and {@code put-object --body}) without one.
     */
    private static boolean bodyMatches(ContainerRequestContext ctx, String declaredHash) throws IOException {
        byte[] body = ctx.getEntityStream() != null ? ctx.getEntityStream().readAllBytes() : new byte[0];
        ctx.setEntityStream(new ByteArrayInputStream(body));
        try {
            String actual = SigV4RequestValidator.hexEncode(MessageDigest.getInstance("SHA-256").digest(body));
            return MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.UTF_8),
                    declaredHash.toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by SigV4", e);
        }
    }

    /**
     * The request timestamp the client signed: {@code x-amz-date} in ISO 8601 basic form, or the
     * HTTP {@code Date} header when the client signed that instead. Either header may carry the
     * timestamp in SigV4; {@code x-amz-date} wins when both are present.
     */
    private static Instant requestTime(ContainerRequestContext ctx) {
        String amzDate = ctx.getHeaderString("x-amz-date");
        try {
            if (amzDate != null && !amzDate.isBlank()) {
                return Instant.from(AMZ_DATE.parse(amzDate.trim()));
            }
            String httpDate = ctx.getHeaderString("Date");
            if (httpDate != null && !httpDate.isBlank()) {
                return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(httpDate.trim()));
            }
        } catch (DateTimeParseException e) {
            return null;
        }
        return null;
    }

    /** The value of one {@code Name=value} component of a SigV4 {@code Authorization} header. */
    private static String component(String authorization, String name) {
        String parameters = authorization.substring(ALGORITHM.length() + 1);
        for (String part : parameters.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                String value = trimmed.substring(name.length() + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static void abort(ContainerRequestContext ctx, int status, String code, String message) {
        ctx.abortWith(PreSignedUrlFilter.errorResponse(status, code, message));
    }
}
