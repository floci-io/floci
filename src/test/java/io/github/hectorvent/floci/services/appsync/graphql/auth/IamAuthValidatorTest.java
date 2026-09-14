package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.testutil.AppSyncRequestSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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

    @Mock
    IamService iamService;

    private IamAuthValidator validator;

    @BeforeEach
    void setUp() {
        validator = new IamAuthValidator(
                new AccountResolver("000000000000"),
                iamService,
                new IamPolicyEvaluator(new ObjectMapper()));
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
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION, Instant.now());

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
                "api-1", HOST, BODY, "AKIDDENY", "deny-secret", REGION, Instant.now());

        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization(signed), "api-1", infoWith(signed)));
        assertEquals(401, ex.getHttpStatus());
    }

    @Test
    void knownKeyWithBadSignatureIs401() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "wrong-secret", REGION, Instant.now());

        assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization(signed), "api-1", infoWith(signed)));
    }

    @Test
    void tamperedBodyAfterSigningIs401() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION, Instant.now());
        AuthRequestInfo tampered = new AuthRequestInfo("{ hello }", null, Map.of(), List.of("10.0.0.1"),
                "req-1", "000000000000", "us-east-1", signed, "{ hello, tampered }");

        assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization(signed), "api-1", tampered));
    }

    @Test
    void unsignedRequestIsRejected() {
        assertThrows(AppSyncTransportException.class, () -> validator.validateRequest(
                "AWS4-HMAC-SHA256 Credential=AKIAGOOD/20260205/us-east-1/appsync/aws4_request",
                "api-1", infoWith(Map.of())));
    }

    @Test
    void unknownAccessKeyNeverResolvesToRoot() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAUNKNOWN", "whatever-secret", REGION, Instant.now());

        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization(signed), "api-1", infoWith(signed)));
        assertEquals(401, ex.getHttpStatus());
    }

    @Test
    void expiredSignatureIsRejected() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "AKIAGOOD", "good-secret", REGION,
                Instant.now().minusSeconds(3600));

        assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization(signed), "api-1", infoWith(signed)));
    }

    @Test
    void legacyTestKeyMustStillBeSigned() throws Exception {
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "test", "test", REGION, Instant.now());

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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
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
    void temporaryCredentialSignedButNoSessionTokenIs401() throws Exception {
        when(iamService.findSecretKey("ASIANOTOKEN")).thenReturn(Optional.of("session-secret"));
        Map<String, String> signed = AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, "ASIANOTOKEN", "session-secret", REGION, Instant.now());

        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization(signed), "api-1", infoWith(signed)));
        assertEquals(401, ex.getHttpStatus());
    }

    @Test
    void temporaryCredentialWithMismatchedSessionTokenIs401() throws Exception {
        when(iamService.findSecretKey("ASIAFORGED")).thenReturn(Optional.of("session-secret"));
        when(iamService.findSessionToken("ASIAFORGED")).thenReturn(Optional.of("issued-token"));
        Map<String, String> signed = signedWithSessionToken("ASIAFORGED", "session-secret", "forged-token");

        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> validator.validateRequest(authorization(signed), "api-1", infoWith(signed)));
        assertEquals(401, ex.getHttpStatus());
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
        Map<String, String> signed = new java.util.LinkedHashMap<>(AppSyncRequestSigner.signedHeaders(
                "api-1", HOST, BODY, accessKeyId, secretKey, REGION, Instant.now()));
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
