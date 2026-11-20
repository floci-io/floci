package io.floci.aws.core.common.auth;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code Credential}/{@code SignedHeaders}/{@code Signature} parameters carried by an
 * {@code AWS4-HMAC-SHA256 Authorization} header, split out of its raw comma-separated value.
 * Shared by every SigV4 verifier in this codebase that accepts a header-signed request:
 * {@code ExecuteApiSigV4Authorizer} (API Gateway) and {@code IamAuthValidator} (AppSync) both
 * parsed this independently before this class existed.
 *
 * <p>{@code missingEqualSign} is true when a part has no {@code =}. AppSync refuses such a header.
 */
public record SigV4AuthorizationHeader(String credential, String signedHeaders, String signature,
                                       boolean missingEqualSign) {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    /**
     * Returns null when {@code authorization} is null or does not start with the expected
     * algorithm. A parameter missing from an otherwise well-formed header comes back null on the
     * record instead, left for the caller to reject alongside whatever else it requires (e.g. an
     * {@code X-Amz-Date} it reads from a different source than this header).
     * Duplicate parameters and conflicting or incomplete repeated headers return a record with
     * null parameters. Equivalent complete repeated headers resolve to the same parameters.
     */
    public static SigV4AuthorizationHeader parse(String authorization) {
        return parse(authorization, ALGORITHM);
    }

    /** The same parameters from a header of {@code algorithm}, such as SigV4a's. */
    public static SigV4AuthorizationHeader parse(String authorization, String algorithm) {
        if (authorization == null) {
            return null;
        }
        String trimmed = authorization.trim();
        if (!trimmed.regionMatches(true, 0, algorithm, 0, algorithm.length())) {
            return null;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        Map<String, String> firstHeader = null;
        boolean missingEqualSign = false;
        for (String part : trimmed.substring(algorithm.length()).split(",")) {
            part = part.trim();
            if (startsHeader(part, algorithm)) {
                if (missingEqualSign || !complete(parameters)
                        || firstHeader != null && !firstHeader.equals(parameters)) {
                    return incomplete(missingEqualSign);
                }
                firstHeader = parameters;
                parameters = new LinkedHashMap<>();
                part = part.substring(algorithm.length()).trim();
            }
            int equals = part.indexOf('=');
            if (equals > 0) {
                String name = part.substring(0, equals).trim();
                if (parameters.putIfAbsent(name, part.substring(equals + 1).trim()) != null) {
                    return incomplete(missingEqualSign);
                }
            } else if (equals < 0 && !part.isBlank()) {
                missingEqualSign = true;
            }
        }
        if (firstHeader != null && (missingEqualSign || !complete(parameters) || !firstHeader.equals(parameters))) {
            return incomplete(missingEqualSign);
        }
        return new SigV4AuthorizationHeader(parameters.get("Credential"), parameters.get("SignedHeaders"),
                parameters.get("Signature"), missingEqualSign);
    }

    private static boolean startsHeader(String part, String algorithm) {
        return part.regionMatches(true, 0, algorithm, 0, algorithm.length())
                && (part.length() == algorithm.length() || Character.isWhitespace(part.charAt(algorithm.length())));
    }

    private static boolean complete(Map<String, String> parameters) {
        return !parameters.getOrDefault("Credential", "").isBlank()
                && !parameters.getOrDefault("SignedHeaders", "").isBlank()
                && !parameters.getOrDefault("Signature", "").isBlank();
    }

    private static SigV4AuthorizationHeader incomplete(boolean missingEqualSign) {
        return new SigV4AuthorizationHeader(null, null, null, missingEqualSign);
    }
}
