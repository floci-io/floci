package io.floci.aws.services.s3;

import io.floci.aws.core.common.Digests;

import java.util.function.Function;

/** The canonical request and string-to-sign used by both S3 URL issuance and verification. */
final class S3PresignedCanonicalRequest {

    private S3PresignedCanonicalRequest() {
    }

    static String build(String method, String signedPath, String canonicalQuery,
                        String signedHeaders, Function<String, String> headerValue, String payloadHash) {
        StringBuilder canonicalHeaders = new StringBuilder();
        for (String header : signedHeaders.split(";")) {
            canonicalHeaders.append(header).append(':')
                    .append(PreSignedUrlFilter.canonicalizeHeaderValue(headerValue.apply(header)))
                    .append('\n');
        }
        return method + "\n" + signedPath + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
    }

    static String stringToSign(String amzDate, String credentialScope, String canonicalRequest) throws Exception {
        return stringToSign("AWS4-HMAC-SHA256", amzDate, credentialScope, canonicalRequest);
    }

    static String stringToSign(String algorithm, String amzDate, String credentialScope,
                               String canonicalRequest) throws Exception {
        return algorithm + "\n" + amzDate + "\n" + credentialScope + "\n"
                + Digests.sha256Hex(canonicalRequest);
    }
}
