package io.floci.aws.core.common.auth;

import io.floci.aws.core.common.Digests;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signatures below were produced by aws-crt 0.38.0 ({@code aws_sign_request} with
 * {@code V4_ASYMMETRIC}), the signer botocore and the Java SDK use for SigV4A, so a pass shows the
 * key derivation and the ECDSA check agree with a real client rather than with this class.
 */
class SigV4aSignatureTest {

    private static final String ACCESS_KEY = "AKIDEXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    private static final String HEADER_SIGNATURE =
            "3046022100ec92e3c742fbe8094521b20b589ae7dfc1a6c5fc2d3523b7fdeee15f198b63400221009f5650d65aad41ea98a8436959e99de1092f36397ce451d4f71cc4bd9d8e7e81";
    private static final String QUERY_SIGNATURE =
            "3044022056e9132df415ef4f4baa299e3bbee5ea6ec30c13001774bd081d7b93933880160220342a98931bb4ff05da584e7dafa82dc772a4c3f1a21c2e4cabf3fc13799561c6";

    private static String headerStringToSign() {
        String canonicalRequest = "GET\n/bucket/key.txt\nx=1\n"
                + "host:localhost:4566\nx-amz-content-sha256:UNSIGNED-PAYLOAD\n"
                + "x-amz-date:20261020T120000Z\nx-amz-region-set:*\n\n"
                + "host;x-amz-content-sha256;x-amz-date;x-amz-region-set\nUNSIGNED-PAYLOAD";
        return stringToSign(canonicalRequest);
    }

    private static String queryStringToSign() {
        String canonicalRequest = "GET\n/bucket/key.txt\n"
                + "X-Amz-Algorithm=AWS4-ECDSA-P256-SHA256"
                + "&X-Amz-Credential=AKIDEXAMPLE%2F20261020%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20261020T120000Z&X-Amz-Expires=900"
                + "&X-Amz-Region-Set=us-east-1%2Ceu-west-1&X-Amz-SignedHeaders=host\n"
                + "host:localhost:4566\n\nhost\nUNSIGNED-PAYLOAD";
        return stringToSign(canonicalRequest);
    }

    private static String stringToSign(String canonicalRequest) {
        return "AWS4-ECDSA-P256-SHA256\n20261020T120000Z\n20261020/s3/aws4_request\n"
                + Digests.sha256Hex(canonicalRequest);
    }

    @Test
    void verifiesAnAuthorizationHeaderSignatureFromAwsCrt() {
        assertTrue(SigV4aSignature.verify(SECRET_KEY, ACCESS_KEY, headerStringToSign(), HEADER_SIGNATURE));
    }

    @Test
    void verifiesAPresignedQuerySignatureFromAwsCrt() {
        assertTrue(SigV4aSignature.verify(SECRET_KEY, ACCESS_KEY, queryStringToSign(), QUERY_SIGNATURE));
    }

    @Test
    void acceptsAnUpperCaseHexSignature() {
        assertTrue(SigV4aSignature.verify(SECRET_KEY, ACCESS_KEY, headerStringToSign(),
                HEADER_SIGNATURE.toUpperCase()));
    }

    @Test
    void rejectsAWrongSecret() {
        assertFalse(SigV4aSignature.verify("another-secret", ACCESS_KEY, headerStringToSign(), HEADER_SIGNATURE));
    }

    @Test
    void rejectsAWrongAccessKeyBecauseTheKeyPairIsBoundToIt() {
        assertFalse(SigV4aSignature.verify(SECRET_KEY, "AKIDOTHER", headerStringToSign(), HEADER_SIGNATURE));
    }

    @Test
    void rejectsATamperedStringToSign() {
        assertFalse(SigV4aSignature.verify(SECRET_KEY, ACCESS_KEY,
                headerStringToSign().replace("20261020T120000Z", "20261020T120001Z"), HEADER_SIGNATURE));
    }

    @Test
    void rejectsASignatureThatIsNotHexDerOrIsTruncated() {
        assertFalse(SigV4aSignature.verify(SECRET_KEY, ACCESS_KEY, headerStringToSign(), "not-hex"));
        assertFalse(SigV4aSignature.verify(SECRET_KEY, ACCESS_KEY, headerStringToSign(), "3046"));
        assertFalse(SigV4aSignature.verify(SECRET_KEY, ACCESS_KEY, headerStringToSign(), ""));
        assertFalse(SigV4aSignature.verify(SECRET_KEY, ACCESS_KEY, headerStringToSign(), null));
        assertFalse(SigV4aSignature.verify(SECRET_KEY, ACCESS_KEY, headerStringToSign(),
                HEADER_SIGNATURE.substring(0, HEADER_SIGNATURE.length() - 2)));
    }

    @Test
    void rejectsAnHmacSigV4Signature() {
        assertFalse(SigV4aSignature.verify(SECRET_KEY, ACCESS_KEY, headerStringToSign(),
                "5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7"));
    }
}
