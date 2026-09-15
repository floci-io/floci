package io.github.hectorvent.floci.core.common.auth;

import java.util.Locale;

/**
 * A SigV4 credential scope, the {@code accessKeyId/date/region/service/aws4_request} value
 * carried by a {@code Credential} parameter, whether that parameter arrived in an
 * {@code Authorization} header or an {@code X-Amz-Credential} query/token component. Shared by
 * every SigV4 verifier in this codebase that checks an Authorization-header-signed request
 * against this exact five-part shape: {@code ExecuteApiSigV4Authorizer} (API Gateway) and
 * {@code IamAuthValidator} (AppSync) both parsed and rebuilt this independently before this class
 * existed.
 */
public record CredentialScope(String accessKeyId, String date, String region, String service) {

    private static final String TERMINATOR = "aws4_request";

    public String credentialScope() {
        return date + "/" + region + "/" + service + "/" + TERMINATOR;
    }

    /**
     * {@code credential} is expected already percent-decoded: a header credential is never
     * encoded, and JAX-RS decodes a presigned {@code X-Amz-Credential} before this sees it.
     */
    public static CredentialScope parse(String credential) {
        if (credential == null) {
            return null;
        }
        String[] parts = credential.split("/");
        if (parts.length != 5 || !TERMINATOR.equals(parts[4])) {
            return null;
        }
        if (parts[0].isBlank() || parts[1].length() != 8 || parts[2].isBlank() || parts[3].isBlank()) {
            return null;
        }
        return new CredentialScope(parts[0], parts[1], parts[2], parts[3].toLowerCase(Locale.ROOT));
    }
}
