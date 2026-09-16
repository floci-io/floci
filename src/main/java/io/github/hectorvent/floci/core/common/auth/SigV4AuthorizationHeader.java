package io.github.hectorvent.floci.core.common.auth;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code Credential}/{@code SignedHeaders}/{@code Signature} parameters carried by an
 * {@code AWS4-HMAC-SHA256 Authorization} header, split out of its raw comma-separated value.
 * Shared by every SigV4 verifier in this codebase that accepts a header-signed request:
 * {@code ExecuteApiSigV4Authorizer} (API Gateway) and {@code IamAuthValidator} (AppSync) both
 * parsed this independently before this class existed.
 */
public record SigV4AuthorizationHeader(String credential, String signedHeaders, String signature) {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    /**
     * Returns null when {@code authorization} is null or does not start with the expected
     * algorithm. A parameter missing from an otherwise well-formed header comes back null on the
     * record instead, left for the caller to reject alongside whatever else it requires (e.g. an
     * {@code X-Amz-Date} it reads from a different source than this header).
     */
    public static SigV4AuthorizationHeader parse(String authorization) {
        if (authorization == null) {
            return null;
        }
        String trimmed = authorization.trim();
        if (!trimmed.regionMatches(true, 0, ALGORITHM, 0, ALGORITHM.length())) {
            return null;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String part : trimmed.substring(ALGORITHM.length()).split(",")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                parameters.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
            }
        }
        return new SigV4AuthorizationHeader(
                parameters.get("Credential"), parameters.get("SignedHeaders"), parameters.get("Signature"));
    }
}
