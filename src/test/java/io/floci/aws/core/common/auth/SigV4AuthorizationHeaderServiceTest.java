package io.floci.aws.core.common.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SigV4AuthorizationHeaderServiceTest {
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String CREDENTIAL = "FIRST/20261113/us-east-1/sqs/aws4_request";
    private static final String HEADERS = "host;x-amz-date";
    private static final String SIGNATURE = "a".repeat(64);
    private static final String AUTHORIZATION = ALGORITHM + " Credential=" + CREDENTIAL
            + ", SignedHeaders=" + HEADERS + ", Signature=" + SIGNATURE;

    @Test
    void singleCompleteHeaderKeepsItsParameters() {
        assertEquals(new SigV4AuthorizationHeader(CREDENTIAL, HEADERS, SIGNATURE, false),
                SigV4AuthorizationHeader.parse(AUTHORIZATION));
    }

    @Test
    void identicalCompleteHeaderRepetitionsKeepOneIdentity() {
        assertEquals(SigV4AuthorizationHeader.parse(AUTHORIZATION),
                SigV4AuthorizationHeader.parse(AUTHORIZATION + ", " + AUTHORIZATION + ", " + AUTHORIZATION));
    }

    @Test
    void equivalentCompleteHeadersMayOrderTheirParametersDifferently() {
        String reordered = ALGORITHM + " Signature=" + SIGNATURE
                + ", Credential=" + CREDENTIAL + ", SignedHeaders=" + HEADERS;
        assertEquals(SigV4AuthorizationHeader.parse(AUTHORIZATION),
                SigV4AuthorizationHeader.parse(AUTHORIZATION + ", " + reordered));
    }

    @ParameterizedTest
    @CsvSource({
            "Credential, FIRST/20261113/us-east-1/sqs/aws4_request",
            "Credential, SECOND/20261113/us-east-1/sqs/aws4_request",
            "SignedHeaders, host;x-amz-date",
            "SignedHeaders, host",
            "Signature, aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "Signature, bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    })
    void duplicateParametersCannotReplaceEarlierValues(String name, String value) {
        assertIncomplete(AUTHORIZATION + ", " + name + "=" + value, ALGORITHM);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Credential", "SignedHeaders", "Signature"})
    void differentCompleteHeaderCannotSupplyAnotherIdentityOrSignature(String parameter) {
        String second = switch (parameter) {
            case "Credential" -> AUTHORIZATION.replace("FIRST/", "SECOND/");
            case "SignedHeaders" -> AUTHORIZATION.replace(HEADERS, "host");
            default -> AUTHORIZATION.replace(SIGNATURE, "b".repeat(64));
        };
        assertIncomplete(AUTHORIZATION + ", " + second, ALGORITHM);
        assertIncomplete(second + ", " + AUTHORIZATION, ALGORITHM);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Credential=FIRST/20261113/us-east-1/sqs/aws4_request",
            "SignedHeaders=host;x-amz-date",
            "Signature=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ""
    })
    void partialHeadersCannotBorrowParametersFromAnotherHeader(String parameters) {
        String partial = ALGORITHM + " " + parameters;
        assertIncomplete(partial + ", " + AUTHORIZATION, ALGORITHM);
        assertIncomplete(AUTHORIZATION + ", " + partial, ALGORITHM);
    }

    @Test
    void matchingOuterHeadersCannotHideAConflictingMiddleHeader() {
        String conflicting = AUTHORIZATION.replace("FIRST/", "SECOND/");
        assertIncomplete(AUTHORIZATION + ", " + conflicting + ", " + AUTHORIZATION, ALGORITHM);
    }

    @Test
    void sigV4aUsesTheSameDuplicateParameterBoundary() {
        String algorithm = "AWS4-ECDSA-P256-SHA256";
        String value = AUTHORIZATION.replace(ALGORITHM, algorithm).replace("/us-east-1/", "/");
        assertEquals(SigV4AuthorizationHeader.parse(value, algorithm),
                SigV4AuthorizationHeader.parse(value + ", " + value, algorithm));
        assertIncomplete(value + ", Signature=" + SIGNATURE, algorithm);
    }

    @Test
    void anUnrecognizedAuthenticationSchemeRemainsUnrecognized() {
        assertNull(SigV4AuthorizationHeader.parse(null));
        assertNull(SigV4AuthorizationHeader.parse("Bearer example"));
    }

    private static void assertIncomplete(String value, String algorithm) {
        SigV4AuthorizationHeader parsed = SigV4AuthorizationHeader.parse(value, algorithm);
        // A recognized malformed scheme must reach rejection, not an unsigned fallback.
        assertNotNull(parsed);
        assertNull(parsed.credential());
        assertNull(parsed.signedHeaders());
        assertNull(parsed.signature());
    }
}
