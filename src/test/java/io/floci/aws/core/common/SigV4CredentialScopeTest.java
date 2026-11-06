package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the boundary the unknown-service guard relies on (issue #1754): the guard only fires
 * on a scope this parser returns, so anything it declines to parse is left to normal routing.
 */
class SigV4CredentialScopeTest {

    @Test
    void parsesSigV4Scope() {
        assertEquals(Optional.of("s3"), SigV4CredentialScope.serviceName(
                "AWS4-HMAC-SHA256 Credential=AKID/20260707/us-east-1/s3/aws4_request,"
                        + " SignedHeaders=host;x-amz-date, Signature=deadbeef"));
    }

    @Test
    void lowercasesScope() {
        assertEquals(Optional.of("dynamodb"), SigV4CredentialScope.serviceName(
                "AWS4-HMAC-SHA256 Credential=AKID/20260707/us-east-1/DynamoDB/aws4_request,"
                        + " SignedHeaders=host, Signature=deadbeef"));
    }

    @Test
    void parsesPresignedCredentialScope() {
        assertEquals(Optional.of("macie2"), SigV4CredentialScope.serviceNameFromCredential(
                "222222222222/20260707/us-east-1/macie2/aws4_request"));
    }

    @Test
    void readsTheServiceOfASigV4aCredentialThatOmitsTheRegion() {
        // SigV4a carries the region in X-Amz-Region-Set, so its credential is
        // <key>/<date>/<service>/aws4_request, one segment shorter than SigV4.
        assertEquals(Optional.of("s3"), SigV4CredentialScope.serviceName(
                "AWS4-ECDSA-P256-SHA256 Credential=AKID/20260707/s3/aws4_request,"
                        + " SignedHeaders=host;x-amz-region-set, Signature=deadbeef"));
        assertEquals(Optional.of("s3"), SigV4CredentialScope.serviceNameFromCredential(
                "AKID/20260707/s3/aws4_request"));
    }

    @Test
    void parsesNestedCredentials() {
        assertEquals(Optional.of("monitoring"), SigV4CredentialScope.serviceName(
                "AWS4-HMAC-SHA256 Credential=AKID/20261015/us-east-1/20261015/eu-west-1/monitoring/aws4_request,"
                        + " SignedHeaders=host, Signature=deadbeef"));
    }

    @Test
    void parsesFirstCredentialWhenDuplicateCredentialComponentsPresent() {
        assertEquals(Optional.of("s3"), SigV4CredentialScope.serviceName(
                "AWS4-ECDSA-P256-SHA256 Credential=test/20260101/s3/aws4_request, "
                        + "SignedHeaders=host;x-amz-date;x-amz-region-set, Signature=3006020101020101, "
                        + "Credential=test2/20260101/s3/aws4_request"));
    }

    @Test
    void ignoresMissingAndUnsignedHeaders() {
        assertTrue(SigV4CredentialScope.serviceName(null).isEmpty());
        assertTrue(SigV4CredentialScope.serviceName("Bearer token").isEmpty());
        assertTrue(SigV4CredentialScope.serviceName("Basic dXNlcjpwYXNz").isEmpty());
    }

    @Test
    void readsScopeWhenCommasHaveNoSpaceAfterThem() {
        String header = "AWS4-HMAC-SHA256 Credential=AKID/20260707/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;x-amz-date,Signature=deadbeef";
        assertEquals(Optional.of("s3"), SigV4CredentialScope.serviceName(header));
        assertEquals(Optional.of("AKID"), SigV4CredentialScope.accessKeyId(header));
    }

    @Test
    void readsScopeWhenASecondCredentialFollowsABrokenOne() {
        String header = "AWS4-HMAC-SHA256 Credential=broken, Credential=AKID/20260707/us-east-1/sqs/aws4_request,"
                + " SignedHeaders=host, Signature=deadbeef";
        assertEquals(Optional.of("sqs"), SigV4CredentialScope.serviceName(header));
        assertEquals(Optional.of("AKID"), SigV4CredentialScope.accessKeyId(header));
    }

    @Test
    void readsScopeWhenTheKeyContainsASlash() {
        String header = "AWS4-HMAC-SHA256 Credential=a/b/20260707/us-east-1/s3/aws4_request,"
                + " SignedHeaders=host, Signature=deadbeef";
        assertEquals(Optional.of("s3"), SigV4CredentialScope.serviceName(header));
        assertEquals(Optional.of("a"), SigV4CredentialScope.accessKeyId(header));
    }

    @Test
    void readsScopeWhenASlashFollowsInTheSignature() {
        String header = "AWS4-HMAC-SHA256 Credential=AKID/20260707/us-east-1/s3/aws4_request,"
                + " SignedHeaders=host, Signature=ab/cd";
        assertEquals(Optional.of("s3"), SigV4CredentialScope.serviceName(header));
        assertEquals(Optional.of("AKID"), SigV4CredentialScope.accessKeyId(header));
    }

    @Test
    void readsScopeWhenTheRegionIsEightDigits() {
        String header = "AWS4-HMAC-SHA256 Credential=AKID/20260707/12345678/s3/aws4_request,"
                + " SignedHeaders=host, Signature=deadbeef";
        assertEquals(Optional.of("s3"), SigV4CredentialScope.serviceName(header));
        assertEquals(Optional.of("AKID"), SigV4CredentialScope.accessKeyId(header));
    }

    @Test
    void readsScopeWhenAControlCharacterFollowsTheScope() {
        String header = "AWS4-HMAC-SHA256 Credential=AKID/20260707/us-east-1/s3/aws4_request\u0001";
        assertEquals(Optional.of("s3"), SigV4CredentialScope.serviceName(header));
        assertEquals(Optional.of("AKID"), SigV4CredentialScope.accessKeyId(header));
    }

    @Test
    void readsScopeWhenTheDateIsNotEightAsciiDigits() {
        assertTrue(SigV4CredentialScope.serviceName(
                "AWS4-HMAC-SHA256 Credential=AKID/2026070/us-east-1/s3/aws4_request").isEmpty());
        assertTrue(SigV4CredentialScope.serviceName(
                "AWS4-HMAC-SHA256 Credential=AKID/٢٠٢٦٠٧٠٧/us-east-1/s3/aws4_request")
                .isEmpty());
    }

    @Test
    void readsKeyAndServiceOfASigV4aHeaderWithoutSpaces() {
        String header = "AWS4-ECDSA-P256-SHA256 Credential=AKID/20260707/S3/aws4_request,SignedHeaders=host";
        assertEquals(Optional.of("s3"), SigV4CredentialScope.serviceName(header));
        assertEquals(Optional.of("AKID"), SigV4CredentialScope.accessKeyId(header));
    }

    @Test
    void accessKeyIdSkipsAFirstCredentialWithAnEmptyKey() {
        assertEquals(Optional.of("AKID"), SigV4CredentialScope.accessKeyId(
                "AWS4-HMAC-SHA256 Credential=/x, Credential=AKID/20260101/us-east-1/s3/aws4_request"));
    }

    @Test
    void accessKeyIdSkipsAFirstCredentialWhoseKeyEndsAtASpace() {
        assertEquals(Optional.of("AKID"), SigV4CredentialScope.accessKeyId(
                "AWS4-HMAC-SHA256 Credential=a b, Credential=AKID/20260101/us-east-1/s3/aws4_request"));
    }

    @Test
    void accessKeyIdOfACredentialWithoutASlashIsEmpty() {
        assertTrue(SigV4CredentialScope.accessKeyId("Credential=AKID").isEmpty());
    }

    @Test
    void headersWithoutACredentialGiveNothing() {
        String bearer = "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln";
        String sigV2 = "AWS AKIAIOSFODNN7EXAMPLE:frJIUN8DYpKDtOLCwo//yllqDzg=";
        assertTrue(SigV4CredentialScope.serviceName(bearer).isEmpty());
        assertTrue(SigV4CredentialScope.accessKeyId(bearer).isEmpty());
        assertTrue(SigV4CredentialScope.serviceName(sigV2).isEmpty());
        assertTrue(SigV4CredentialScope.accessKeyId(sigV2).isEmpty());
    }
}
