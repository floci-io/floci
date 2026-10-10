package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the canonical request against signatures an independent signer produced, so the verifier
 * is not only ever compared with a signer written alongside it. Every expected signature below was
 * computed by botocore 1.43.108 ({@code SigV4Auth.add_auth}) for the same method, URL, headers and
 * body, with the clock pinned to 2026-01-01T00:00:00Z and the AWS documentation's example key pair.
 */
class SigV4HeaderSignatureFilterTest {

    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String AMZ_DATE = "20260101T000000Z";
    private static final String SCOPE_DATE = "20260101";
    private static final String REGION = "us-east-1";
    private static final String HOST = "localhost:4566";

    @Test
    void queryRequestVerifies() throws Exception {
        assertTrue(matches("POST", "/", null,
                "content-type;host;x-amz-date",
                Map.of("content-type", "application/x-www-form-urlencoded; charset=utf-8"),
                "Action=GetCallerIdentity&Version=2011-06-15", SECRET, "sts",
                "5df4b2dd1edf5b1846612118237772601820753b9d87d8214afbad7e2d70d522"));
    }

    @Test
    void jsonRequestVerifies() throws Exception {
        assertTrue(matches("POST", "/", null,
                "content-type;host;x-amz-date;x-amz-target",
                Map.of("content-type", "application/x-amz-json-1.0", "x-amz-target", "AmazonSQS.ListQueues"),
                "{}", SECRET, "sqs",
                "969501491602b1e686863668c36b1049fb733134484fe49487e1f6a8e0b2dc27"));
    }

    @Test
    void restPathWithEscapesIsDoubleEncoded() throws Exception {
        assertTrue(matches("GET",
                "/2015-03-31/functions/arn%3Aaws%3Alambda%3Aus-east-1%3A000000000000%3Afunction%3Amy-fn/configuration",
                "Qualifier=v1", "host;x-amz-date", Map.of(), "", SECRET, "lambda",
                "52dde5d043128b9bef236aeea1158ab5ee3712d75e436b01fff35b3ed0dbc962"));
    }

    @Test
    void restQueryIsCanonicalized() throws Exception {
        assertTrue(matches("GET", "/schedule-groups", "NamePrefix=a%20b%2Bc&MaxResults=5",
                "host;x-amz-date", Map.of(), "", SECRET, "scheduler",
                "6882cb650943c3c5770a1b6fe60091fa72f5a628948df4c7aae4fa4da5772da8"));
    }

    @Test
    void sessionTokenHeaderIsPartOfTheSignature() throws Exception {
        assertTrue(matches("POST", "/", null,
                "content-type;host;x-amz-date;x-amz-security-token;x-amz-target",
                Map.of("content-type", "application/x-amz-json-1.1",
                        "x-amz-target", "TrentService.ListKeys",
                        "x-amz-security-token", "session-token-value"),
                "{\"Limit\":10}", SECRET, "kms",
                "5feaffc90be139e5f4288e442918fb838d1ef12f334d84ab292491f27535e800"));
    }

    @Test
    void wrongSecretDoesNotVerify() throws Exception {
        assertFalse(matches("POST", "/", null,
                "content-type;host;x-amz-date;x-amz-target",
                Map.of("content-type", "application/x-amz-json-1.0", "x-amz-target", "AmazonSQS.ListQueues"),
                "{}", "not-the-secret", "sqs",
                "969501491602b1e686863668c36b1049fb733134484fe49487e1f6a8e0b2dc27"));
    }

    @Test
    void replacedBodyDoesNotVerify() throws Exception {
        assertFalse(matches("POST", "/", null,
                "content-type;host;x-amz-date",
                Map.of("content-type", "application/x-www-form-urlencoded; charset=utf-8"),
                "Action=DeleteUser&UserName=admin", SECRET, "sts",
                "5df4b2dd1edf5b1846612118237772601820753b9d87d8214afbad7e2d70d522"));
    }

    @Test
    void signatureForAnotherServiceDoesNotVerify() throws Exception {
        assertFalse(matches("POST", "/", null,
                "content-type;host;x-amz-date;x-amz-target",
                Map.of("content-type", "application/x-amz-json-1.0", "x-amz-target", "AmazonSQS.ListQueues"),
                "{}", SECRET, "kms",
                "969501491602b1e686863668c36b1049fb733134484fe49487e1f6a8e0b2dc27"));
    }

    @Test
    void canonicalUriKeepsThePlainPathWhenNothingIsEscaped() {
        assertEquals(List.of("/"), SigV4Canonicalization.canonicalUriCandidates(""));
        assertEquals(List.of("/schedule-groups/default"),
                SigV4Canonicalization.canonicalUriCandidates("/schedule-groups/default"));
        assertEquals(List.of("/functions/a%3Ab", "/functions/a%253Ab"),
                SigV4Canonicalization.canonicalUriCandidates("/functions/a%3Ab"));
    }

    private static boolean matches(String method, String rawPath, String rawQuery, String signedHeaders,
                                   Map<String, String> headers, String body, String secret, String service,
                                   String signature) throws Exception {
        return SigV4HeaderSignatureFilter.signatureMatches(method, rawPath, rawQuery, signedHeaders,
                name -> switch (name) {
                    case "host" -> HOST;
                    case "x-amz-date" -> AMZ_DATE;
                    default -> headers.get(name);
                },
                body.getBytes(StandardCharsets.UTF_8), secret, AMZ_DATE,
                new CredentialScope("AKIDEXAMPLE", SCOPE_DATE, REGION, service), signature);
    }
}
