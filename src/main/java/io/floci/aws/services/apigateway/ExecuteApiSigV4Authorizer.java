package io.floci.aws.services.apigateway;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ResteasyRequests;
import io.floci.aws.core.common.auth.CredentialScope;
import io.floci.aws.core.common.auth.SessionTokenCheck;
import io.floci.aws.core.common.auth.SigV4Canonicalization;
import io.floci.aws.core.common.auth.SigV4RequestValidator;
import io.floci.aws.core.common.auth.SigV4SignedRequest;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.services.iam.model.IamUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;

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
 * <p>A temporary credential must also present the session token it was issued with, and a long-term
 * one must present none. {@link #checkSessionToken} checks this.
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

    private static final String SIGNING_SERVICE = "execute-api";

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
        /** A session token came that was not issued with the access key. */
        INVALID_TOKEN,
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
        MultivaluedMap<String, String> queryParameters = ResteasyRequests.queryParameters(uriInfo);
        String authorization = headers == null ? null : headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        boolean presigned = SigV4SignedRequest.ALGORITHM.equals(queryParameters.getFirst("X-Amz-Algorithm"));

        if (!presigned && (authorization == null || authorization.isBlank())) {
            return Result.rejected(Failure.MISSING, "request is not SigV4-signed");
        }

        SigV4SignedRequest signed = presigned
                ? SigV4SignedRequest.fromQuery(queryParameters::getFirst)
                : SigV4SignedRequest.fromHeader(authorization, amzDate(headers));
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
        if (!SigV4RequestValidator.containsHeader(signed.signedHeaders(), "host")) {
            return Result.rejected(Failure.MALFORMED, "SignedHeaders does not cover host");
        }

        Result expiry = checkExpiry(signed);
        if (expiry != null) {
            return expiry;
        }

        String secretKey = resolveSecretKey(scope.accessKeyId());
        if (secretKey == null) {
            LOG.debugv("execute-api request references unregistered access key={0}",
                    SigV4RequestValidator.sanitizeForLog(scope.accessKeyId()));
            return Result.rejected(Failure.UNKNOWN_KEY, "access key is not registered");
        }

        Result sessionToken = checkSessionToken(scope.accessKeyId(), presigned
                ? queryParameters.getFirst(SECURITY_TOKEN)
                : headers == null ? null : headers.getHeaderString(SECURITY_TOKEN));
        if (sessionToken != null) {
            return sessionToken;
        }

        String payloadHash = SigV4Canonicalization.payloadHash(
                headers == null ? null : headers.getHeaderString("x-amz-content-sha256"),
                signed.signedHeaders(), body, presigned);
        String canonicalHeaders = SigV4Canonicalization.canonicalHeaders(signed.signedHeaders(),
                name -> "host".equals(name) ? hostHeader(headers, uriInfo) : headerValue(headers, name));
        String canonicalQueryString = SigV4Canonicalization.canonicalQueryString(
                uriInfo.getRequestUri().getRawQuery(), presigned);

        String rawPath = signedRequestPath != null
                ? signedRequestPath
                : uriInfo.getRequestUri().getRawPath();
        if (SigV4Canonicalization.signatureMatches(httpMethod, rawPath, canonicalQueryString, canonicalHeaders,
                signed.signedHeaders(), payloadHash, signed.amzDate(), scope, secretKey, signed.signature())) {
            return new Result(null, null, resolveIdentity(scope.accessKeyId()));
        }

        LOG.debugv("execute-api SigV4 signature mismatch for accessKey={0}",
                SigV4RequestValidator.sanitizeForLog(scope.accessKeyId()));
        return Result.rejected(Failure.MISMATCH, "signature does not match");
    }

    /**
     * Real API Gateway rejects a header-signed request whose {@code X-Amz-Date} is more than five
     * minutes from the service's clock. Emulating it keeps a captured request from being replayed
     * indefinitely against a route the caller is testing authorization on.
     */
    private static Result checkExpiry(SigV4SignedRequest signed) {
        return switch (signed.freshness(Instant.now())) {
            case FRESH -> null;
            case MALFORMED_DATE -> Result.rejected(Failure.MALFORMED, "signature could not be verified");
            case BAD_EXPIRES -> Result.rejected(Failure.MALFORMED, "X-Amz-Expires is missing or out of range");
            case EXPIRED -> Result.rejected(Failure.EXPIRED, signed.presigned() ? "presigned request expired"
                    : "signature date " + signed.amzDate() + " is outside the accepted window");
            // A presigned URL signed in the future is still a forgery attempt, not a slow clock.
            case NOT_YET_VALID -> Result.rejected(Failure.EXPIRED, signed.presigned()
                    ? "presigned request is not yet valid"
                    : "signature date " + signed.amzDate() + " is outside the accepted window");
        };
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
        String callerAccount = iamService.resolveAccountId(accessKeyId).orElse(null);
        if (callerAccount == null) {
            LOG.debugv("execute-api request uses an expired or inactive credential: accessKey={0}",
                    SigV4RequestValidator.sanitizeForLog(accessKeyId));
            return null;
        }
        // Dispatch reads integrations in the API owner's account. Credentials remain owned by
        // the caller, who may belong to another account; only this lookup adopts that account.
        return RequestScopes.callAs(callerAccount,
                () -> iamService.findSecretKey(accessKeyId).orElse(null));
    }

    /**
     * Rejects a session token that does not fit the access key, returning {@code null} when there
     * is nothing to object to. AWS reads the token from where the signature is, the header or the
     * presigned query, and ignores it in the other place.
     */
    private Result checkSessionToken(String accessKeyId, String presented) {
        if (LEGACY_ACCESS_KEY_ID.equals(accessKeyId)) {
            return null;
        }
        return switch (SessionTokenCheck.of(iamService, accessKeyId, presented)) {
            case VALID -> null;
            case MISSING -> Result.rejected(Failure.UNKNOWN_KEY, "session token is missing or empty");
            case INVALID -> Result.rejected(Failure.INVALID_TOKEN, "session token was not issued with the access key");
        };
    }

    /**
     * Builds the caller for a signature that already verified. The {@code orElseGet} fallbacks are
     * reachable only for the legacy {@code test} credential: {@link #resolveSecretKey} has proved
     * every other accepted key resolves to a live account.
     */
    private CallerIdentity resolveIdentity(String accessKeyId) {
        String accountId = iamService.resolveAccountId(accessKeyId)
                .orElseGet(regionResolver::getAccountId);
        return RequestScopes.callAs(accountId, () -> {
            String userArn = iamService.resolveCallerArn(accessKeyId)
                    .orElseGet(() -> regionResolver.buildGlobalArn("iam", accountId, "root"));
            String userId = iamService.findAccessKey(accessKeyId)
                    .map(AccessKey::getUserName)
                    .flatMap(iamService::findUser)
                    .map(IamUser::getUserId)
                    .orElse(accessKeyId);
            return new CallerIdentity(accessKeyId, accountId, userArn, userId);
        });
    }

    /** The signing time of a header signature: {@code X-Amz-Date}, or {@code Date} when it is absent. */
    private static String amzDate(HttpHeaders headers) {
        String amzDate = headers.getHeaderString("X-Amz-Date");
        return isBlank(amzDate) ? headers.getHeaderString("Date") : amzDate;
    }

    // ──────────────────────────── Canonical request ────────────────────────────

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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
