package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the cases the end-to-end AWS_IAM tests cannot reach through RestAssured: the
 * presigned query-string form, a credential scoped to another service, a registered IAM user's
 * identity, and the virtual-host path rewrite that would otherwise make every signature mismatch.
 */
class ExecuteApiSigV4AuthorizerTest {

    private static final String HOST = "localhost:4566";
    private static final String PATH = "/execute-api/api1/test/iam";
    private static final String REGION = "us-east-1";
    private static final String ISSUED_TOKEN = "issued-session-token";

    private IamService iamService;
    private ExecuteApiSigV4Authorizer authorizer;

    @BeforeEach
    void setUp() {
        iamService = mock(IamService.class);
        when(iamService.findSecretKey(anyString())).thenReturn(Optional.empty());
        when(iamService.resolveAccountId(anyString())).thenReturn(Optional.empty());
        when(iamService.resolveCallerArn(anyString())).thenReturn(Optional.empty());
        when(iamService.findAccessKey(anyString())).thenReturn(Optional.empty());
        when(iamService.findSessionToken(anyString())).thenReturn(Optional.empty());

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        authorizer = new ExecuteApiSigV4Authorizer(iamService, regionResolver);
    }

    @Test
    void unsignedRequestIsReportedAsMissingRatherThanMismatched() {
        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(Map.of()), uriInfo(PATH, null), null, null);

        assertFalse(result.authorized());
        assertEquals(ExecuteApiSigV4Authorizer.Failure.MISSING, result.failure());
    }

    @Test
    void presignedRequestIsAccepted() throws Exception {
        String query = ExecuteApiRequestSigner.presignedQueryString(
                "GET", PATH, HOST, "test", "test", REGION, Instant.now(), 900);

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(Map.of()), uriInfo(PATH, query), null, null);

        assertTrue(result.authorized(), String.valueOf(result.detail()));
        assertEquals("test", result.identity().accessKey());
    }

    @Test
    void expiredPresignedRequestIsRejected() throws Exception {
        String query = ExecuteApiRequestSigner.presignedQueryString(
                "GET", PATH, HOST, "test", "test", REGION,
                Instant.now().minus(1, ChronoUnit.HOURS), 60);

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(Map.of()), uriInfo(PATH, query), null, null);

        assertEquals(ExecuteApiSigV4Authorizer.Failure.EXPIRED, result.failure());
    }

    @Test
    void credentialScopedToAnotherServiceIsRejected() throws Exception {
        // A signature the caller legitimately produced for S3 must not authorize execute-api.
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                "GET", PATH, Map.of(), HOST, null, "test", "test", REGION, Instant.now());
        String s3Scoped = signed.get("Authorization").replace("/execute-api/", "/s3/");

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(Map.of("X-Amz-Date", signed.get("X-Amz-Date"),
                        HttpHeaders.AUTHORIZATION, s3Scoped)),
                uriInfo(PATH, null), null, null);

        assertEquals(ExecuteApiSigV4Authorizer.Failure.MALFORMED, result.failure());
    }

    @Test
    void unregisteredAccessKeyCannotSignForItself() throws Exception {
        // The self-signing bypass fixed in the ElastiCache and RDS validators: an access key that
        // was never issued must not be usable as its own secret.
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                "GET", PATH, Map.of(), HOST, null,
                "AKIAUNKNOWN", "AKIAUNKNOWN", REGION, Instant.now());

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(signed), uriInfo(PATH, null), null, null);

        assertEquals(ExecuteApiSigV4Authorizer.Failure.UNKNOWN_KEY, result.failure());
    }

    @Test
    void signatureThatDoesNotCoverHostIsRejected() throws Exception {
        // A virtual-host request carries its apiId only in the Host header, and the path it signs
        // (/{stage}/{route}) is identical across every API here. A signature that leaves host out
        // of SignedHeaders is therefore bound to no particular API and would replay across them.
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                "GET", PATH, Map.of(), HOST, null, "test", "test", REGION, Instant.now(), false);

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(signed), uriInfo(PATH, null), null, null);

        assertEquals(ExecuteApiSigV4Authorizer.Failure.MALFORMED, result.failure());
    }

    @Test
    void expiredSessionCredentialIsRejected() throws Exception {
        // findSecretKey hands back an STS session's secret without consulting its expiration, so
        // the liveness check has to come from resolveAccountId, which returns empty once the
        // session has lapsed. Without it an expired AssumeRole credential still authenticates.
        when(iamService.findSecretKey("ASIAEXPIRED")).thenReturn(Optional.of("session-secret"));
        when(iamService.resolveAccountId("ASIAEXPIRED")).thenReturn(Optional.empty());

        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                "GET", PATH, Map.of(), HOST, null,
                "ASIAEXPIRED", "session-secret", REGION, Instant.now());

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(signed), uriInfo(PATH, null), null, null);

        assertEquals(ExecuteApiSigV4Authorizer.Failure.UNKNOWN_KEY, result.failure());
    }

    @Test
    void liveSessionCredentialIsAccepted() throws Exception {
        // The mirror of the case above: a session that has not lapsed still authenticates, so the
        // liveness check does not lock out every temporary credential.
        liveSession("ASIALIVE", ISSUED_TOKEN);
        when(iamService.resolveCallerArn("ASIALIVE")).thenReturn(
                Optional.of("arn:aws:sts::111122223333:assumed-role/app/floci-session"));

        Map<String, String> signed = signedWithSessionToken("ASIALIVE", ISSUED_TOKEN);

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(signed), uriInfo(PATH, null), null, null);

        assertTrue(result.authorized(), String.valueOf(result.detail()));
        assertEquals("arn:aws:sts::111122223333:assumed-role/app/floci-session",
                result.identity().userArn());
    }

    @Test
    void temporaryCredentialWithoutASessionTokenIsRejected() throws Exception {
        // The null issued token stands for a session stored before Floci recorded them: presence
        // is required even where the value cannot be compared.
        liveSession("ASIANOTOKEN", null);

        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                "GET", PATH, Map.of(), HOST, null,
                "ASIANOTOKEN", "session-secret", REGION, Instant.now());

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(signed), uriInfo(PATH, null), null, null);

        assertEquals(ExecuteApiSigV4Authorizer.Failure.UNKNOWN_KEY, result.failure());
    }

    @Test
    void fabricatedSessionTokenIsRejected() throws Exception {
        // Presence alone would let a caller holding the key and secret invent a token.
        liveSession("ASIAFORGED", ISSUED_TOKEN);

        Map<String, String> signed = signedWithSessionToken("ASIAFORGED", "forged-session-token");

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(signed), uriInfo(PATH, null), null, null);

        assertEquals(ExecuteApiSigV4Authorizer.Failure.UNKNOWN_KEY, result.failure());
    }

    @Test
    void presignedTemporaryCredentialIsCheckedAgainstItsIssuedSessionToken() throws Exception {
        // A presigned URL carries the token as a query parameter rather than a header.
        liveSession("ASIAPRESIGNED", ISSUED_TOKEN);

        String signed = ExecuteApiRequestSigner.presignedQueryString(
                "GET", PATH, HOST, "ASIAPRESIGNED", "session-secret", REGION,
                Instant.now(), 900, ISSUED_TOKEN);
        assertTrue(authorizer.authorize("GET", headers(Map.of()), uriInfo(PATH, signed), null, null)
                .authorized());

        String forged = ExecuteApiRequestSigner.presignedQueryString(
                "GET", PATH, HOST, "ASIAPRESIGNED", "session-secret", REGION,
                Instant.now(), 900, "forged-session-token");
        assertEquals(ExecuteApiSigV4Authorizer.Failure.UNKNOWN_KEY,
                authorizer.authorize("GET", headers(Map.of()), uriInfo(PATH, forged), null, null)
                        .failure());
    }

    private void liveSession(String accessKeyId, String issuedToken) {
        when(iamService.findSecretKey(accessKeyId)).thenReturn(Optional.of("session-secret"));
        when(iamService.resolveAccountId(accessKeyId)).thenReturn(Optional.of("111122223333"));
        when(iamService.findSessionToken(accessKeyId)).thenReturn(Optional.ofNullable(issuedToken));
    }

    /**
     * Signs a request with a temporary credential, presenting the session token the way an SDK
     * does: as a header outside {@code SignedHeaders}. Nothing binds it to the signature, which is
     * the point of comparing it against the issued value instead.
     */
    private static Map<String, String> signedWithSessionToken(String accessKeyId, String sessionToken)
            throws Exception {
        Map<String, String> signed = new java.util.LinkedHashMap<>(ExecuteApiRequestSigner.signedHeaders(
                "GET", PATH, Map.of(), HOST, null, accessKeyId, "session-secret", REGION, Instant.now()));
        signed.put("X-Amz-Security-Token", sessionToken);
        return signed;
    }

    @Test
    void deactivatedLongTermKeyIsRejected() throws Exception {
        // resolveAccountId filters on status Active, so a key deactivated through UpdateAccessKey
        // stops authenticating even though its secret is still stored.
        when(iamService.findSecretKey("AKIADEACTIVATED")).thenReturn(Optional.of("s3cr3t"));
        when(iamService.resolveAccountId("AKIADEACTIVATED")).thenReturn(Optional.empty());

        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                "GET", PATH, Map.of(), HOST, null,
                "AKIADEACTIVATED", "s3cr3t", REGION, Instant.now());

        assertEquals(ExecuteApiSigV4Authorizer.Failure.UNKNOWN_KEY,
                authorizer.authorize("GET", headers(signed), uriInfo(PATH, null), null, null)
                        .failure());
    }

    @Test
    void registeredIamUserIsResolvedToItsArnAndUserId() throws Exception {
        when(iamService.findSecretKey("AKIAREGISTERED")).thenReturn(Optional.of("s3cr3t"));
        when(iamService.resolveAccountId("AKIAREGISTERED")).thenReturn(Optional.of("111122223333"));
        when(iamService.resolveCallerArn("AKIAREGISTERED"))
                .thenReturn(Optional.of("arn:aws:iam::111122223333:user/alice"));
        when(iamService.findAccessKey("AKIAREGISTERED"))
                .thenReturn(Optional.of(new AccessKey("AKIAREGISTERED", "s3cr3t", "alice")));
        when(iamService.findUser("alice")).thenReturn(Optional.of(
                new IamUser("AIDAALICE", "alice", "/", "arn:aws:iam::111122223333:user/alice")));

        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                "GET", PATH, Map.of(), HOST, null, "AKIAREGISTERED", "s3cr3t", REGION, Instant.now());

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(signed), uriInfo(PATH, null), null, null);

        assertTrue(result.authorized(), String.valueOf(result.detail()));
        assertEquals("111122223333", result.identity().accountId());
        assertEquals("arn:aws:iam::111122223333:user/alice", result.identity().userArn());
        assertEquals("AIDAALICE", result.identity().userId());
    }

    @Test
    void bodyIsCoveredBySoATamperedPayloadIsRejected() throws Exception {
        byte[] body = "{\"amount\":1}".getBytes();
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                "POST", PATH, Map.of(), HOST, body, "test", "test", REGION, Instant.now());

        assertTrue(authorizer.authorize("POST", headers(signed), uriInfo(PATH, null), body, null)
                .authorized());
        assertEquals(ExecuteApiSigV4Authorizer.Failure.MISMATCH,
                authorizer.authorize("POST", headers(signed), uriInfo(PATH, null),
                        "{\"amount\":9999}".getBytes(), null).failure());
    }

    @Test
    void declaredContentSha256CannotStandInForATamperedBody() throws Exception {
        // The signature covers SHA-256(body). Re-presenting the ORIGINAL body's hash in an
        // x-amz-content-sha256 header alongside a replaced body must not restore the match:
        // the header is not in SignedHeaders, and a literal hash is never taken on trust.
        byte[] signedBody = "{\"amount\":1}".getBytes();
        Map<String, String> signed = new java.util.LinkedHashMap<>(ExecuteApiRequestSigner.signedHeaders(
                "POST", PATH, Map.of(), HOST, signedBody, "test", "test", REGION, Instant.now()));
        signed.put("x-amz-content-sha256", sha256Hex(signedBody));

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "POST", headers(signed), uriInfo(PATH, null), "{\"amount\":9999}".getBytes(), null);

        assertEquals(ExecuteApiSigV4Authorizer.Failure.MISMATCH, result.failure());
    }

    @Test
    void queryStringIsCoveredBySoAnAddedParameterIsRejected() throws Exception {
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                "GET", PATH, Map.of("limit", "10"), HOST, null, "test", "test", REGION, Instant.now());

        assertTrue(authorizer.authorize("GET", headers(signed), uriInfo(PATH, "limit=10"), null, null)
                .authorized());
        assertEquals(ExecuteApiSigV4Authorizer.Failure.MISMATCH,
                authorizer.authorize("GET", headers(signed), uriInfo(PATH, "limit=10&admin=true"),
                        null, null).failure());
    }

    @Test
    void virtualHostRequestIsVerifiedAgainstThePathTheClientSigned() throws Exception {
        // ApiGatewayExecuteApiHostFilter rewrites api1.execute-api.localhost/test/iam to
        // /execute-api/api1/test/iam before dispatch sees it. Verifying against the rewritten path
        // would reject every correctly signed virtual-host request.
        String clientPath = "/test/iam";
        Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                "GET", clientPath, Map.of(), "api1.execute-api.localhost:4566", null,
                "test", "test", REGION, Instant.now());

        ExecuteApiSigV4Authorizer.Result result = authorizer.authorize(
                "GET", headers(signed, "api1.execute-api.localhost:4566"),
                uriInfo(PATH, null), null, clientPath);

        assertTrue(result.authorized(), String.valueOf(result.detail()));
    }

    private static String sha256Hex(byte[] input) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(input);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static HttpHeaders headers(Map<String, String> values) {
        return headers(values, HOST);
    }

    private static HttpHeaders headers(Map<String, String> values, String host) {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if ("Host".equalsIgnoreCase(name)) {
                return host;
            }
            return values.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        });
        return headers;
    }

    private static UriInfo uriInfo(String rawPath, String rawQuery) {
        UriInfo uriInfo = mock(UriInfo.class);
        URI requestUri = URI.create("http://" + HOST + rawPath + (rawQuery == null ? "" : "?" + rawQuery));
        when(uriInfo.getRequestUri()).thenReturn(requestUri);

        MultivaluedMap<String, String> queryParameters = new MultivaluedHashMap<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int equals = pair.indexOf('=');
                queryParameters.add(equals >= 0 ? pair.substring(0, equals) : pair,
                        equals >= 0 ? java.net.URLDecoder.decode(pair.substring(equals + 1),
                                java.nio.charset.StandardCharsets.UTF_8) : "");
            }
        }
        when(uriInfo.getQueryParameters()).thenReturn(queryParameters);
        return uriInfo;
    }
}
