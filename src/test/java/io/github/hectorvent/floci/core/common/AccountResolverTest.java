package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
