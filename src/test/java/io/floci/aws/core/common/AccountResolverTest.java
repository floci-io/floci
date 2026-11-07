package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountResolverTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private final AccountResolver resolver = new AccountResolver(DEFAULT_ACCOUNT);

    // --- resolve(String authorizationHeader) tests ---

    @Test
    void resolvesFrom12DigitAkidInAuthHeader() {
        String auth = "AWS4-HMAC-SHA256 Credential=000000000001/20260617/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";
        assertEquals("000000000001", resolver.resolve(auth));
    }

    @Test
    void fallsBackToDefaultForNon12DigitAkidInAuthHeader() {
        String auth = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20260617/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";
        assertEquals(DEFAULT_ACCOUNT, resolver.resolve(auth));
    }

    @Test
    void fallsBackToDefaultWhenNullAuthHeader() {
        assertEquals(DEFAULT_ACCOUNT, resolver.resolve(null));
    }

    @Test
    void fallsBackToDefaultWhenEmptyAuthHeader() {
        assertEquals(DEFAULT_ACCOUNT, resolver.resolve(""));
    }

    @Test
    void extractAccessKeyIdReturnsNullForBareCredentialHeader() {
        String auth = "Credential=111122223333/20260617/us-east-1/s3/aws4_request";
        assertNull(resolver.extractAccessKeyId(auth));
        assertEquals(DEFAULT_ACCOUNT, resolver.resolve(auth));
    }

    @Test
    void extractAccessKeyIdReturnsNullForNonSigV4Header() {
        String auth = "X Credential=111122223333/20260617/us-east-1/s3/aws4_request";
        assertNull(resolver.extractAccessKeyId(auth));
    }

    @Test
    void resolvesFallbackToDefaultForMalformedAuthHeader() {
        String auth = "X Credential=111122223333/20260617/us-east-1/s3/aws4_request";
        assertEquals(DEFAULT_ACCOUNT, resolver.resolve(auth));
    }

    @Test
    void extractsAccessKeyIdFromSigV4AHeader() {
        String auth = "AWS4-ECDSA-P256-SHA256 Credential=000000000001/20260617/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";
        assertEquals("000000000001", resolver.extractAccessKeyId(auth));
        assertEquals("000000000001", resolver.resolve(auth));
    }

    @ParameterizedTest
    @ValueSource(strings = {"000000000001", "AKIAEXAMPLE", "ASIAEXAMPLE"})
    void legacyS3ExtractionDoesNotChangeSharedAuthenticationParsing(String accessKeyId) {
        String header = "AWS " + accessKeyId + ":signature=";
        assertEquals(accessKeyId, resolver.extractS3AccessKeyId(header));
        assertNull(resolver.extractAccessKeyId(header));
        assertEquals(DEFAULT_ACCOUNT, resolver.resolve(header));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "AWS key:", "AWS :signature", "AWS key:signature extra",
            "AWS key:signature:extra", "AWS key :signature", "Basic key:signature", "aws key:signature"})
    void malformedLegacyHeadersDoNotSelectAnAccount(String header) {
        assertNull(resolver.extractS3AccessKeyId(header));
    }

    @Test
    void legacyS3ExtractionRetainsModernAndAbsentHeaders() {
        String header = "AWS4-HMAC-SHA256 Credential=ASIAEXAMPLE/20260617/us-west-2/s3/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        assertEquals("ASIAEXAMPLE", resolver.extractS3AccessKeyId(header));
        assertNull(resolver.extractS3AccessKeyId(null));
    }

    // --- resolveFromPresignedCredential(String credentialValue) tests ---

    @Test
    void resolvesFromPresigned12DigitAkid() {
        assertEquals("000000000001",
                resolver.resolveFromPresignedCredential("000000000001/20260617/us-east-1/s3/aws4_request"));
    }

    @Test
    void fallsBackToDefaultForPresignedNon12DigitAkid() {
        assertEquals(DEFAULT_ACCOUNT,
                resolver.resolveFromPresignedCredential("AKIAIOSFODNN7EXAMPLE/20260617/us-east-1/s3/aws4_request"));
    }

    @Test
    void fallsBackToDefaultWhenPresignedCredentialNull() {
        assertEquals(DEFAULT_ACCOUNT, resolver.resolveFromPresignedCredential(null));
    }

    @Test
    void fallsBackToDefaultWhenPresignedCredentialEmpty() {
        assertEquals(DEFAULT_ACCOUNT, resolver.resolveFromPresignedCredential(""));
    }

    // --- extractPresignedAccessKeyId(String credentialValue) tests ---

    @Test
    void extractsAccessKeyIdFromPresignedCredential() {
        assertEquals("ASIAEXAMPLE",
                resolver.extractPresignedAccessKeyId("ASIAEXAMPLE/20260617/us-east-1/s3/aws4_request"));
    }

    @Test
    void extractPresignedAccessKeyIdReturnsNullWhenAbsent() {
        assertNull(resolver.extractPresignedAccessKeyId(null));
        assertNull(resolver.extractPresignedAccessKeyId(""));
    }

    @Test
    void extractAccessKeyIdReadsHeadersWithoutSpacesAfterCommas() {
        assertEquals("000000000001", resolver.extractAccessKeyId(
                "AWS4-HMAC-SHA256 Credential=000000000001/20260617/us-east-1/s3/aws4_request,SignedHeaders=host,Signature=a"));
    }

    @Test
    void extractAccessKeyIdWithTwoCredentialsUsesTheFirstKey() {
        assertEquals("first", resolver.extractAccessKeyId(
                "AWS4-HMAC-SHA256 Credential=first/20260617/us-east-1/s3/aws4_request, "
                        + "Credential=second/20260617/us-east-1/s3/aws4_request"));
    }

    @Test
    void extractAccessKeyIdStopsAtTheFirstSlashOfAKeyWithSlashes() {
        assertEquals("a", resolver.extractAccessKeyId(
                "AWS4-HMAC-SHA256 Credential=a/b/20260617/us-east-1/s3/aws4_request, SignedHeaders=host"));
    }

    @Test
    void extractAccessKeyIdWithASlashLaterInTheHeader() {
        assertEquals("AKID", resolver.extractAccessKeyId(
                "AWS4-HMAC-SHA256 Credential=AKID/20260617/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host, Signature=a/b"));
    }

    @Test
    void extractAccessKeyIdWithAnEightDigitRegion() {
        assertEquals("AKID", resolver.extractAccessKeyId(
                "AWS4-HMAC-SHA256 Credential=AKID/20260617/12345678/s3/aws4_request, SignedHeaders=host"));
    }

    @Test
    void extractAccessKeyIdOfASigV4aHeader() {
        assertEquals("AKID", resolver.extractAccessKeyId(
                "AWS4-ECDSA-P256-SHA256 Credential=AKID/20260617/s3/aws4_request, SignedHeaders=host"));
    }

    @Test
    void extractAccessKeyIdKeepsSpacesAndCommasInsideTheKey() {
        assertEquals("AK ID", resolver.extractAccessKeyId(
                "AWS4-HMAC-SHA256 Credential=AK ID/20260617/us-east-1/s3/aws4_request"));
        assertEquals("A,B", resolver.extractAccessKeyId(
                "AWS4-HMAC-SHA256 Credential=A,B/20260617/us-east-1/s3/aws4_request"));
    }

    @Test
    void extractAccessKeyIdWithLeadingSpaceAndTrailingControlCharacter() {
        assertEquals("AKID", resolver.extractAccessKeyId(
                "  AWS4-HMAC-SHA256 Credential=AKID/20260617/us-east-1/s3/aws4_request\u0001"));
    }

    @Test
    void isAccountIdNeedsExactlyTwelveAsciiDigits() {
        assertTrue(AccountResolver.isAccountId("000000000001"));
        assertFalse(AccountResolver.isAccountId(null));
        assertFalse(AccountResolver.isAccountId(""));
        assertFalse(AccountResolver.isAccountId("00000000000"));
        assertFalse(AccountResolver.isAccountId("0000000000012"));
        assertFalse(AccountResolver.isAccountId("00000000000a"));
        assertFalse(AccountResolver.isAccountId("00000000000\n"));
        assertFalse(AccountResolver.isAccountId("٠٠٠٠٠٠٠٠٠٠٠٠"));
    }

    @Test
    void extractAccessKeyIdSkipsAFirstCredentialFollowedDirectlyBySlash() {
        assertEquals("AKID", resolver.extractAccessKeyId(
                "AWS4-HMAC-SHA256 Credential=/x Credential=AKID/20260101/us-east-1/s3/aws4_request"));
    }

    @Test
    void extractAccessKeyIdWithoutASlashIsNull() {
        assertNull(resolver.extractAccessKeyId("AWS4-HMAC-SHA256 Credential=AKID"));
    }
}
