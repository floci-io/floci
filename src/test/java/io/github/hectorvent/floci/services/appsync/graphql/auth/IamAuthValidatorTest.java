package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.testutil.AppSyncRequestSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IamAuthValidatorTest {

    private static final String ALLOW = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"appsync:GraphQL","Resource":"*"}]}
            """;
    private static final String DENY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Action":"appsync:GraphQL","Resource":"*"}]}
            """;
    private static final String FIELD_DENY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"appsync:GraphQL","Resource":"arn:aws:appsync:us-east-1:000000000000:apis/api-1/*"},
              {"Effect":"Deny","Action":"appsync:GraphQL","Resource":"arn:aws:appsync:us-east-1:000000000000:apis/api-1/types/Query/fields/secret"}
            ]}
            """;

    private static final String HOST = "appsync.us-east-1.amazonaws.com";
    private static final String REGION = "us-east-1";
    private static final String BODY = "{ hello }";
    private static final Instant NOW = Instant.parse("2026-10-17T22:16:18Z");

    @Mock
    IamService iamService;

    private IamAuthValidator validator;

    @BeforeEach
    void setUp() {
        validator = new IamAuthValidator(
                iamService,
                new IamPolicyEvaluator(new ObjectMapper()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(iamService.findSecretKey("AKIAGOOD")).thenReturn(Optional.of("good-secret"));
        lenient().when(iamService.findSecretKey("AKIDDENY")).thenReturn(Optional.of("deny-secret"));
    }

    private AuthRequestInfo infoWith(Map<String, String> signedHeaders) {
        return new AuthRequestInfo("{ hello }", null, Map.of(), List.of("10.0.0.1"),
                "req-1", "000000000000", "us-east-1", signedHeaders, BODY);
    }

    private static String authorization(Map<String, String> signedHeaders) {
        return signedHeaders.get("Authorization");
    }

    @Test
    void knownAllowBuildsIdentity() throws Exception {
        when(iamService.resolveCallerContext("AKIAGOOD")).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(iamService.resolveCallerArn("AKIAGOOD"))
                .thenReturn(Optional.of("arn:aws:iam::000000000000:user/alice"));
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION, NOW);

        Map<String, Object> identity = validator.validateRequest(
                authorization(signed), "api-1", infoWith(signed));

        assertEquals("AKIAGOOD", identity.get("user"));
        assertEquals("alice", identity.get("username"));
        assertInstanceOf(List.class, identity.get("sourceIp"));
        assertEquals(List.of("10.0.0.1"), identity.get("sourceIp"));
    }

    @Test
    void knownRequestDenyThrows401() throws Exception {
        when(iamService.resolveCallerContext("AKIDDENY")).thenReturn(CallerContext.of(List.of(DENY)));
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIDDENY", "deny-secret", REGION, NOW);

        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization(signed), "api-1", infoWith(signed)));
        assertEquals(401, ex.getHttpStatus());
        assertEquals("UnauthorizedException", ex.getErrorType());
        assertEquals("Permission denied", ex.getMessage());
    }

    @Test
    void knownKeyWithBadSignatureIsABadRequestNamingTheCanonicalRequest() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "wrong-secret", REGION, NOW);

        AppSyncTransportException ex = assertRejected(signed, 400, "BadRequestException");
        assertTrue(ex.getMessage().startsWith("The request signature we calculated does not match the signature you"
                + " provided. Check your AWS Secret Access Key and signing method. Consult the service documentation"
                + " for details.\n\nThe Canonical String for this request should have been\n'POST\n"
                + "/v1/apis/api-1/graphql\n\nhost:" + HOST + "\nx-amz-date:" + signed.get("X-Amz-Date")
                + "\n\nhost;x-amz-date\n"), ex.getMessage());
        assertTrue(ex.getMessage().contains("'\n\nThe String-to-Sign should have been\n'AWS4-HMAC-SHA256\n"
                + signed.get("X-Amz-Date") + "\n"), ex.getMessage());
    }

    @Test
    void tamperedBodyAfterSigningIsABadRequest() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION, NOW);
        AuthRequestInfo tampered = new AuthRequestInfo("{ hello }", null, Map.of(), List.of("10.0.0.1"),
                "req-1", "000000000000", "us-east-1", signed, "{ hello, tampered }");

        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization(signed), "api-1", tampered));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void missingPartsAreNamedTogetherWithTheHeaderHash() throws Exception {
        String authorization = "AWS4-HMAC-SHA256 Credential=AKIAGOOD/20260205/us-east-1/appsync/aws4_request";
        AppSyncTransportException ex = assertRejected(authorization, Map.of(), 403, "IncompleteSignatureException");
        assertEquals("Authorization header requires 'Signature' parameter."
                + " Authorization header requires 'SignedHeaders' parameter."
                + " Authorization header requires existence of either a 'X-Amz-Date' or a 'Date' header."
                + " (Hashed with SHA-256 and encoded with Base64) Authorization=" + hashed(authorization),
                ex.getMessage());

        assertEquals("Authorization header requires 'Credential' parameter."
                + " Authorization header requires 'Signature' parameter."
                + " Authorization header requires 'SignedHeaders' parameter."
                + " (Hashed with SHA-256 and encoded with Base64) Authorization=" + hashed("AWS4-HMAC-SHA256"),
                assertRejected("AWS4-HMAC-SHA256", Map.of("X-Amz-Date", "20260205T000000Z"), 403,
                        "IncompleteSignatureException").getMessage());
    }

    @Test
    void aPartWithoutAnEqualsSignRefusesTheHeader() {
        String authorization = "AWS4-HMAC-SHA256 garbage";
        assertEquals("Invalid key=value pair (missing equal-sign) in Authorization header (hashed with SHA-256 and"
                + " encoded with Base64): '" + hashed(authorization) + "'.",
                assertRejected(authorization, Map.of(), 403, "IncompleteSignatureException").getMessage());
    }

    @Test
    void theDateMustBeInTheBasicFormatAndTheCredentialHaveFiveParts() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION, NOW);
        Map<String, String> dated = new LinkedHashMap<>(signed);
        dated.remove("X-Amz-Date");
        dated.put("Date", "Sat, 17 Oct 2026 22:15:13 GMT");
        assertEquals("Date must be in ISO-8601 'basic format'. Got 'Sat, 17 Oct 2026 22:15:13 GMT'."
                + " See http://en.wikipedia.org/wiki/ISO_8601",
                assertRejected(dated, 403, "IncompleteSignatureException").getMessage());

        String credential = "AKIAGOOD/x/y";
        assertEquals("Credential must have exactly 5 slash-delimited elements, e.g. keyid/date/region/service/term,"
                + " got 'AKIAGOOD/x/y'", assertRejected(withCredential(signed, credential), 403,
                "IncompleteSignatureException").getMessage());
    }

    @Test
    void aSignatureFromTheFutureIsNotYetCurrent() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION, NOW.plusSeconds(600));

        assertEquals("Signature not yet current: 20261017T222618Z is still later than 20261017T222118Z"
                + " (20261017T221618Z + 5 min.)", assertRejected(signed, 400, "BadRequestException").getMessage());
    }

    @Test
    void theHostMustBeSignedBeforeTheScopeIsChecked() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION, NOW);
        Map<String, String> unsignedHost = withCredential(signed,
                credential(signed).replace("/appsync/", "/execute-api/"));
        unsignedHost.put("Authorization", unsignedHost.get("Authorization").replace("host;x-amz-date", "x-amz-date"));

        assertEquals("'Host' or ':authority' must be a 'SignedHeader' in the AWS Authorization.",
                assertRejected(unsignedHost, 400, "BadRequestException").getMessage());
    }

    @Test
    void everyScopeFaultIsNamedAtOnce() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION, NOW);
        String[] scope = credential(signed).split("/");
        String faulty = String.join("/", scope[0], "20200101", scope[2], "execute-api", "aws5_request");

        assertEquals("Credential should be scoped to correct service: 'appsync'."
                + " Credential should be scoped with a valid terminator: 'aws4_request'."
                + " Date in Credential scope does not match YYYYMMDD from ISO-8601 version of date from HTTP. ",
                assertRejected(withCredential(signed, faulty), 400, "BadRequestException").getMessage());
    }

    @Test
    void aSignedHeaderMustBeInTheRequestBeforeTheHostOrTheCredentialIsChecked() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION, NOW);
        Map<String, String> missing = withCredential(signed, "AKIAGOOD/x/y");
        missing.put("Authorization", missing.get("Authorization").replace("host;x-amz-date", "x-amz-date;X-Extra"));

        assertEquals("'X-Extra' is named as a SignedHeader, but it does not exist in the HTTP request.",
                assertRejected(missing, 403, "IncompleteSignatureException").getMessage());
    }

    @Test
    void theHostIsCheckedBeforeTheCredentialParts() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION, NOW);
        Map<String, String> unsignedHost = withCredential(signed, "AKIAGOOD/x/y");
        unsignedHost.put("Authorization", unsignedHost.get("Authorization").replace("host;x-amz-date", "x-amz-date"));

        assertEquals("'Host' or ':authority' must be a 'SignedHeader' in the AWS Authorization.",
                assertRejected(unsignedHost, 400, "BadRequestException").getMessage());
    }

    private AppSyncTransportException assertRejected(Map<String, String> signed, int status, String errorType) {
        return assertRejected(authorization(signed), signed, status, errorType);
    }

    private AppSyncTransportException assertRejected(String authorization, Map<String, String> headers, int status,
                                                     String errorType) {
        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization, "api-1", infoWith(headers)));
        assertEquals(status, ex.getHttpStatus(), ex.getMessage());
        assertEquals(errorType, ex.getErrorType());
        return ex;
    }

    private static String credential(Map<String, String> signed) {
        return authorization(signed).split("Credential=")[1].split(",")[0];
    }

    private static Map<String, String> withCredential(Map<String, String> signed, String credential) {
        Map<String, String> changed = new LinkedHashMap<>(signed);
        changed.put("Authorization", authorization(signed).replace(credential(signed), credential));
        return changed;
    }

    private static String hashed(String authorization) {
        return Base64.getEncoder().encodeToString(Digests.sha256(authorization.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unknownAccessKeyNeverResolvesToRoot() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAUNKNOWN", "whatever-secret", REGION, NOW);

        assertRefused(signed, "The security token included in the request is invalid.");
    }

    @Test
    void expiredSignatureIsRejected() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION,
                NOW.minusSeconds(3600));

        assertEquals("Signature expired: 20261017T211618Z is now earlier than 20261017T221118Z"
                + " (20261017T221618Z - 5 min.)", assertRejected(signed, 400, "BadRequestException").getMessage());
    }

    @Test
    void legacyTestKeyMustStillBeSigned() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "test", "test", REGION, NOW);

        Map<String, Object> identity = validator.validateRequest(
                authorization(signed), "api-1", infoWith(signed));

        assertEquals("test", identity.get("user"));
    }

    @Test
    void unsignedLegacyTestKeyIsRejected() {
        assertThrows(AppSyncTransportException.class, () -> validator.validateRequest(
                "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request",
                "api-1", infoWith(Map.of())));
    }

    /**
     * These two build the resource the policy evaluator matches against, and the ARN a customer
     * writes their policy from is the one AppSync minted, which carries the region's partition.
     * A pinned {@code aws} here meant a GovCloud policy naming its own API never matched and the
     * request was denied with nothing saying why.
     */
    @ParameterizedTest
    @CsvSource({
            "us-east-1,      arn:aws:appsync:",
            "us-gov-west-1,  arn:aws-us-gov:appsync:",
            "cn-north-1,     arn:aws-cn:appsync:",
            "us-isob-east-1, arn:aws-iso-b:appsync:"})
    void resourceArnsCarryTheRegionsPartition(String region, String expectedPrefix) {
        assertTrue(IamAuthValidator.requestArn(region, "000000000000", "api-1").startsWith(expectedPrefix),
                IamAuthValidator.requestArn(region, "000000000000", "api-1"));
        assertTrue(IamAuthValidator.fieldArn(region, "000000000000", "api-1", "Query", "hello")
                        .startsWith(expectedPrefix),
                IamAuthValidator.fieldArn(region, "000000000000", "api-1", "Query", "hello"));
    }

    /** A null or blank region keeps the commercial partition, as every global ARN does. */
    @Test
    void aBlankRegionStaysCommercial() {
        assertEquals("arn:aws:appsync::000000000000:apis/api-1/*",
                IamAuthValidator.requestArn(null, "000000000000", "api-1"));
    }

    @Test
    void temporaryCredentialSignedButNoSessionTokenIsRefused() throws Exception {
        when(iamService.findSecretKey("ASIANOTOKEN")).thenReturn(Optional.of("session-secret"));
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "ASIANOTOKEN", "session-secret", REGION, NOW);

        assertRefused(signed, "The security token included in the request is invalid.");
    }

    @Test
    void temporaryCredentialWithMismatchedSessionTokenIsRefused() throws Exception {
        when(iamService.findSecretKey("ASIAFORGED")).thenReturn(Optional.of("session-secret"));
        when(iamService.findSessionToken("ASIAFORGED")).thenReturn(Optional.of("issued-token"));
        Map<String, String> signed = signedWithSessionToken("ASIAFORGED", "session-secret", "forged-token");

        assertRefused(signed, "The security token included in the request is invalid");
    }

    @Test
    void longTermKeyWithASessionTokenIsRefused() throws Exception {
        assertRefused(signedWithSessionToken("AKIAGOOD", "good-secret", "bogus"),
                "The security token included in the request is invalid");
        assertRefused(signedWithSessionToken("AKIAGOOD", "good-secret", ""),
                "The security token included in the request is invalid.");
    }

    @Test
    void legacyTestKeyTakesAnySessionToken() throws Exception {
        Map<String, String> signed = signedWithSessionToken("test", "test", "bogus");

        assertEquals("test", validator.validateRequest(authorization(signed), "api-1", infoWith(signed)).get("user"));
    }

    private void assertRefused(Map<String, String> signed, String message) {
        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization(signed), "api-1", infoWith(signed)));
        assertEquals(403, ex.getHttpStatus());
        assertEquals("UnrecognizedClientException", ex.getErrorType());
        assertEquals(message, ex.getMessage());
    }

    @Test
    void temporaryCredentialWithIssuedSessionTokenIsAllowed() throws Exception {
        when(iamService.findSecretKey("ASIALIVE")).thenReturn(Optional.of("session-secret"));
        when(iamService.findSessionToken("ASIALIVE")).thenReturn(Optional.of("issued-token"));
        when(iamService.resolveCallerArn("ASIALIVE")).thenReturn(
                Optional.of("arn:aws:sts::000000000000:assumed-role/app/floci-session"));
        Map<String, String> signed = signedWithSessionToken("ASIALIVE", "session-secret", "issued-token");

        Map<String, Object> identity = validator.validateRequest(
                authorization(signed), "api-1", infoWith(signed));

        assertEquals("ASIALIVE", identity.get("user"));
        assertEquals("floci-session", identity.get("username"));
    }

    /**
     * Presents the session token the way an SDK does: as a header outside {@code SignedHeaders}.
     * Nothing binds it to the signature, which is the point of comparing it against the issued
     * value instead.
     */
    private static Map<String, String> signedWithSessionToken(String accessKeyId, String secretKey, String token)
            throws Exception {
        Map<String, String> signed = new LinkedHashMap<>(AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, accessKeyId, secretKey, REGION, NOW));
        signed.put("X-Amz-Security-Token", token);
        return signed;
    }

    @Test
    void fieldArnDenyDetected() {
        when(iamService.resolveCallerContext("AKIAGOOD")).thenReturn(CallerContext.of(List.of(FIELD_DENY)));
        String fieldArn = IamAuthValidator.fieldArn("us-east-1", "000000000000", "api-1", "Query", "secret");
        assertTrue(validator.isFieldDenied("AKIAGOOD", fieldArn));
        assertFalse(validator.isFieldDenied("AKIAGOOD",
                IamAuthValidator.fieldArn("us-east-1", "000000000000", "api-1", "Query", "hello")));
        assertFalse(validator.isFieldDenied("test", fieldArn));
    }
}
