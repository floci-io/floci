package io.github.hectorvent.floci.core.common;

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
    void ignoresSigV4aBecauseItsCredentialOmitsTheRegion() {
        // SigV4a carries the region in X-Amz-Region-Set, so its credential is
        // <key>/<date>/<service>/aws4_request — one segment shorter than SigV4.
        assertTrue(SigV4CredentialScope.serviceName(
                "AWS4-ECDSA-P256-SHA256 Credential=AKID/20260707/s3/aws4_request,"
                        + " SignedHeaders=host;x-amz-region-set, Signature=deadbeef").isEmpty());
    }

    @Test
    void ignoresMissingAndUnsignedHeaders() {
        assertTrue(SigV4CredentialScope.serviceName(null).isEmpty());
        assertTrue(SigV4CredentialScope.serviceName("Bearer token").isEmpty());
        assertTrue(SigV4CredentialScope.serviceName("Basic dXNlcjpwYXNz").isEmpty());
    }
}
