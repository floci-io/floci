package io.github.hectorvent.floci.core.common.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CredentialScopeTest {

    @Test
    void aSigV4ScopeNamesItsRegion() {
        CredentialScope scope = CredentialScope.parse("AKIDEXAMPLE/20261017/us-east-1/Lambda/aws4_request");
        assertEquals("us-east-1", scope.region());
        assertEquals("lambda", scope.service());
        assertEquals("20261017/us-east-1/lambda/aws4_request", scope.credentialScope());
        assertNull(CredentialScope.parse("AKIDEXAMPLE/20261017/lambda/aws4_request"));
    }

    @Test
    void aSigV4aScopeHasNoRegion() {
        CredentialScope scope = CredentialScope.parseSigV4a("AKIDEXAMPLE/20261017/Lambda/aws4_request");
        assertEquals("AKIDEXAMPLE", scope.accessKeyId());
        assertNull(scope.region());
        assertEquals("lambda", scope.service());
        assertEquals("20261017/lambda/aws4_request", scope.credentialScope());
        assertNull(CredentialScope.parseSigV4a("AKIDEXAMPLE/20261017/us-east-1/lambda/aws4_request"));
        assertNull(CredentialScope.parseSigV4a("AKIDEXAMPLE/2026101/lambda/aws4_request"));
        assertNull(CredentialScope.parseSigV4a("AKIDEXAMPLE/20261017/lambda/aws4"));
        assertNull(CredentialScope.parseSigV4a(null));
    }
}
