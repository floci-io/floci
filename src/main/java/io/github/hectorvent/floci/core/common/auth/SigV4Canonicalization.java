package io.github.hectorvent.floci.core.common.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The canonical request of a header- or query-signed SigV4 request outside S3, and the signature
 * check over it. Shared by {@code ExecuteApiSigV4Authorizer} (API Gateway) and
 * {@code SigV4HeaderSignatureFilter} (every other service under {@code validate-signatures}); the
 * first carried these as private helpers before the second needed the same rules.
 */
public final class SigV4Canonicalization {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private SigV4Canonicalization() {
    }

    /**
     * Whether {@code signature} is the one {@code secretKey} produces for this request under any of
     * {@link #canonicalUriCandidates(String) the canonical URIs} a signer could have produced for
     * {@code rawPath}, compared in constant time.
     */
    public static boolean signatureMatches(String method, String rawPath, String canonicalQueryString,
                                           String canonicalHeaders, String signedHeaders, String payloadHash,
                                           String amzDate, CredentialScope scope, String secretKey,
                                           String signature) throws Exception {
        byte[] signingKey = SigV4RequestValidator.deriveSigningKey(
                secretKey, scope.date(), scope.region(), scope.service());
        for (String canonicalUri : canonicalUriCandidates(rawPath)) {
            String canonicalRequest = method + "\n"
                    + canonicalUri + "\n"
                    + canonicalQueryString + "\n"
                    + canonicalHeaders + "\n"
                    + signedHeaders + "\n"
                    + payloadHash;
            String stringToSign = ALGORITHM + "\n"
                    + amzDate + "\n"
                    + scope.credentialScope() + "\n"
                    + SigV4RequestValidator.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            String expected = SigV4RequestValidator.hexEncode(
                    SigV4RequestValidator.hmacSha256(signingKey, stringToSign));
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The canonical URI candidates a signer could have produced for this raw path. Non-S3 SigV4
     * URI-encodes each path segment a second time on top of the encoding already on the wire; for
     * an all-ASCII path the two forms are identical, so only paths carrying escapes produce a
     * second candidate. Trying both keeps a legitimately signed request from being rejected over
     * which convention its signer follows.
     */
    public static List<String> canonicalUriCandidates(String rawPath) {
        String raw = isBlank(rawPath) ? "/" : rawPath;
        List<String> candidates = new ArrayList<>(2);
        candidates.add(raw);
        String[] segments = raw.split("/", -1);
        StringBuilder doubleEncoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                doubleEncoded.append('/');
            }
            doubleEncoded.append(uriEncode(segments[i]));
        }
        if (!candidates.contains(doubleEncoded.toString())) {
            candidates.add(doubleEncoded.toString());
        }
        return candidates;
    }

    /**
     * The canonical query string: every pair decoded, re-encoded per SigV4 and sorted. A presigned
     * request drops its own {@code X-Amz-Signature} ({@code dropSignature}).
     */
    public static String canonicalQueryString(String rawQuery, boolean dropSignature) {
        if (isBlank(rawQuery)) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = decodeQueryComponent(equals >= 0 ? pair.substring(0, equals) : pair);
            String value = decodeQueryComponent(equals >= 0 ? pair.substring(equals + 1) : "");
            if (dropSignature && "X-Amz-Signature".equals(name)) {
                continue;
            }
            pairs.add(new String[]{uriEncode(name), uriEncode(value)});
        }
        pairs.sort(Comparator.<String[], String>comparing(pair -> pair[0]).thenComparing(pair -> pair[1]));
        StringBuilder canonical = new StringBuilder();
        for (String[] pair : pairs) {
            if (!canonical.isEmpty()) {
                canonical.append('&');
            }
            canonical.append(pair[0]).append('=').append(pair[1]);
        }
        return canonical.toString();
    }

    /**
     * The canonical headers block for {@code signedHeaders}, each value read through
     * {@code valueOf} (given the lowercase name) and normalized.
     */
    public static String canonicalHeaders(String signedHeaders, UnaryOperator<String> valueOf) {
        StringBuilder canonical = new StringBuilder();
        for (String name : signedHeaders.split(";")) {
            canonical.append(name).append(':')
                    .append(SigV4RequestValidator.normalizeHeaderValue(valueOf.apply(name))).append('\n');
        }
        return canonical.toString();
    }

    /**
     * The payload hash the canonical request must carry.
     *
     * <p>A literal {@code x-amz-content-sha256} is deliberately <em>not</em> taken on trust: the
     * body is hashed instead. For an honest request the two are equal, and for a tampered one they
     * are not, which turns a swapped body into a signature mismatch. Trusting the header would let
     * a caller keep a valid signature over a body they had replaced, since the header value the
     * signer hashed would still be the one presented.
     *
     * <p>The sentinels ({@code UNSIGNED-PAYLOAD}, the {@code STREAMING-*} forms) mean the caller
     * chose not to sign the body, and are honoured only when {@code x-amz-content-sha256} is itself
     * in {@code SignedHeaders}: that is, when the choice is covered by the signature. A presigned
     * request is unsigned-payload by convention; every AWS presigner emits it that way.
     */
    public static String payloadHash(String declaredContentSha256, String signedHeaders, byte[] body,
                                     boolean presigned) throws Exception {
        if (declaredContentSha256 != null && !declaredContentSha256.isBlank()
                && SigV4RequestValidator.containsHeader(signedHeaders, "x-amz-content-sha256")
                && !SigV4RequestValidator.isSha256Hex(declaredContentSha256.trim())) {
            return declaredContentSha256.trim();
        }
        if (presigned) {
            return "UNSIGNED-PAYLOAD";
        }
        return SigV4RequestValidator.sha256Hex(body == null ? new byte[0] : body);
    }

    /** RFC 3986 percent-encoding as SigV4 defines it: {@code /} is escaped, {@code -._~} are not. */
    public static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xFF;
            char ch = (char) b;
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                encoded.append(ch);
            } else {
                encoded.append('%')
                        .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    private static String decodeQueryComponent(String value) {
        if (value == null) {
            return "";
        }
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
