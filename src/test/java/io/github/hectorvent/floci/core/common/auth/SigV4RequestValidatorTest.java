package io.github.hectorvent.floci.core.common.auth;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shared SigV4 crypto primitives against AWS's own published test vector, independent
 * of any one caller's canonical-request shape. SigV4Validator, RdsSigV4Validator, and S3's
 * PreSignedUrlFilter/S3HeaderSignatureFilter each exercise these indirectly through their own
 * request flows; this targets the primitives themselves at their one shared source, and how
 * {@code validate} reads a presigned token's query string: each parameter decoded once, and the
 * canonical query string rebuilt from the decoded values.
 */
class SigV4RequestValidatorTest {

    private static final String SESSION_ACCESS_KEY_ID = "ASIAEXAMPLESESSION01";
    private static final String SESSION_SECRET = "session-secret-key";

    /**
     * Signing-key derivation for AWS's well-known example secret key
     * ("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLE", date 20150830, us-east-1, iam), the same
     * inputs AWS uses throughout its SigV4 documentation. Expected value cross-checked
     * independently with Python's hmac/hashlib, not derived from this code.
     */
    @Test
    void deriveSigningKeyMatchesAnIndependentlyComputedValue() throws Exception {
        byte[] signingKey = SigV4RequestValidator.deriveSigningKey(
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLE", "20150830", "us-east-1", "iam");

        assertEquals("93c91b7c5da17c72120bd321a9833353b5dd75355fe396cc91abc149ad9755b5",
                SigV4RequestValidator.hexEncode(signingKey));
    }

    @Test
    void sha256HexOfEmptyStringIsTheWellKnownConstant() throws Exception {
        // This exact value is also hardcoded in validate()'s canonical request for the empty
        // body ElastiCache/RDS presigned tokens always carry; this pins it independently.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SigV4RequestValidator.sha256Hex(""));
    }

    @Test
    void hmacSha256IsDeterministicForTheSameKeyAndData() throws Exception {
        byte[] key = "a-secret-key".getBytes(StandardCharsets.UTF_8);

        byte[] first = SigV4RequestValidator.hmacSha256(key, "some data");
        byte[] second = SigV4RequestValidator.hmacSha256(key, "some data");

        assertEquals(SigV4RequestValidator.hexEncode(first), SigV4RequestValidator.hexEncode(second));
    }

    @Test
    void hexEncodeLowercasesAndZeroPadsEachByte() {
        assertEquals("00ff0a", SigV4RequestValidator.hexEncode(new byte[]{0x00, (byte) 0xFF, 0x0A}));
    }

    @Test
    void containsHeaderMatchesAnyOfTheSemicolonSeparatedNames() {
        assertTrue(SigV4RequestValidator.containsHeader("host;x-amz-date;x-amz-content-sha256", "host"));
        assertTrue(SigV4RequestValidator.containsHeader("host;x-amz-date", "x-amz-date"));
        assertFalse(SigV4RequestValidator.containsHeader("host;x-amz-date", "authorization"));
    }

    @Test
    void isSha256HexRequiresExactlySixtyFourHexCharacters() {
        assertTrue(SigV4RequestValidator.isSha256Hex(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
        assertFalse(SigV4RequestValidator.isSha256Hex("UNSIGNED-PAYLOAD"));
        assertFalse(SigV4RequestValidator.isSha256Hex("e3b0c442"));
    }

    /**
     * Session tokens are drawn from an alphabet that includes {@code +}, {@code /} and {@code =}
     * (Lambda's execution-role tokens always are), and a presigner writes each of them
     * percent-encoded once. The token has to be decoded exactly once: a second pass turns every
     * {@code +} into a space, and a literal {@code %2B} into a {@code +}, so the issued session no
     * longer matches what the client signed with.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "IQoJb3JpZ2luX2VjEJr+abc/def+ghi/jkl==",
            "a+b",
            "a/b",
            "a=b",
            "a%2Bb",
            "+/=%2B"
    })
    void validateAcceptsASessionTokenThatTheSignerPercentEncoded(String sessionToken) throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(
                SESSION_ACCESS_KEY_ID, SESSION_SECRET, sessionToken, Instant.now().plusSeconds(3600));
        String token = SigV4TokenTestHelper.createRdsToken("db.example.local", 5432, "app_user",
                SESSION_ACCESS_KEY_ID, SESSION_SECRET, Instant.now().minusSeconds(60), 900, sessionToken);

        assertTrue(validate(iamService, token));
    }

    /**
     * Decoding once also means a token only names the session it carries: one that decodes to the
     * issued session token only after a second pass, or that differs from it by a space where the
     * issued one has a {@code +}, is not that session's token.
     */
    @ParameterizedTest
    @CsvSource({
            "a+b, a b",
            "a+b, a%2Bb",
            "a%2Bb, a+b"
    })
    void validateRefusesASessionTokenOtherThanTheIssuedOne(String issued, String presented) throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithSessionCredential(
                SESSION_ACCESS_KEY_ID, SESSION_SECRET, issued, Instant.now().plusSeconds(3600));
        String token = SigV4TokenTestHelper.createRdsToken("db.example.local", 5432, "app_user",
                SESSION_ACCESS_KEY_ID, SESSION_SECRET, Instant.now().minusSeconds(60), 900, presented);

        assertFalse(validate(iamService, token));
    }

    /**
     * The canonical query string is built from the decoded parameters, each name and value
     * URI-encoded again as SigV4 defines it, not from the bytes on the wire: a credential that
     * travels with literal slashes, or with lowercase hex, is still the credential that was signed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/", "%2f"})
    void validateBuildsTheCanonicalQueryFromTheDecodedParameters(String slashOnTheWire) throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");
        String token = SigV4TokenTestHelper.createRdsToken("db.example.local", 5432, "app_user",
                "AKIDRDS", "secret-rds", Instant.now().minusSeconds(60), 900);
        String credential = token.replaceFirst("(?s).*(X-Amz-Credential=[^&]*).*", "$1");
        String rewritten = token.replace(credential, credential.replace("%2F", slashOnTheWire));

        assertTrue(validate(iamService, rewritten));
    }

    @Test
    void validateRefusesATokenWhoseParameterValueChangedAfterSigning() throws Exception {
        IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey("AKIDRDS", "secret-rds");
        String token = SigV4TokenTestHelper.createRdsToken("db.example.local", 5432, "app user",
                "AKIDRDS", "secret-rds", Instant.now().minusSeconds(60), 900);

        assertFalse(validate(iamService, token.replace("DBUser=app%20user", "DBUser=app%2Buser")));
    }

    private static boolean validate(IamService iamService, String token) {
        String rawQuery = token.substring(token.indexOf('?') + 1);
        return new SigV4RequestValidator(iamService).validate(
                rawQuery, "db.example.local:5432", "DBUser", true, null, "RDS IAM token");
    }
}
