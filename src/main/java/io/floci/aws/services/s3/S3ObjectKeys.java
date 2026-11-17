package io.floci.aws.services.s3;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Reads the object key a request names from its path as it arrived. JAX-RS collapses consecutive
 * slashes in {@code UriInfo}, so a key that starts with a slash ({@code /email.txt}, sent as
 * {@code /bucket//email.txt}) would otherwise be read as another key; {@link S3Controller} and the
 * IAM condition resolver both read it here, so they look at the same object.
 */
public final class S3ObjectKeys {

    private S3ObjectKeys() {
    }

    /**
     * The decoded key in {@code rawPath}, the path as the client sent it, for a request on
     * {@code bucket}. {@code requestUriPath} is the request URI's decoded path, which
     * {@link S3VirtualHostFilter} rewrites to {@code /<bucket>} followed by the raw path for a
     * virtual-hosted request, whose raw path is then the key alone. Returns null when the raw path
     * does not carry the bucket, or does not decode.
     */
    public static String fromRawPath(String rawPath, String requestUriPath, String bucket) {
        String rawKey;
        if (isVirtualHostedRawPath(rawPath, requestUriPath, bucket)) {
            rawKey = rawPath.substring(1);
        } else {
            String bucketPrefix = "/" + bucket + "/";
            int prefixIndex = rawPath.indexOf(bucketPrefix);
            if (prefixIndex < 0) {
                return null;
            }
            rawKey = rawPath.substring(prefixIndex + bucketPrefix.length());
        }
        try {
            return decodePath(rawKey);
        } catch (IllegalArgumentException ignored) {
            // A malformed escape names no key; S3Controller refuses the request as BadRequest.
            return null;
        }
    }

    /**
     * The decoded key an S3 request on {@code bucket} names, or null for a bucket-level request.
     * {@code sentPath} is the path as the client sent it, where a key's own slashes survive, and
     * {@code normalizedPath} the path JAX-RS matched, which stands in when the sent path carries no
     * bucket, as for a request no Vert.x request backs. {@link S3Controller}, the IAM condition
     * resolver and the resource ARN a request is checked against all read the key here, so the
     * object stored, the object the conditions describe and the object the policy is matched
     * against are the same one.
     */
    public static String objectKey(String sentPath, String requestUriPath, String normalizedPath,
                                   String bucket) {
        String key = sentPath == null ? null : fromRawPath(sentPath, requestUriPath, bucket);
        if (key == null) {
            key = fromNormalizedPath(normalizedPath);
        }
        return key == null || key.isEmpty() ? null : key;
    }

    /** The object key of a path-style (or filter-rewritten) S3 path, null for a bucket-level one. */
    static String fromNormalizedPath(String path) {
        if (path == null) {
            return null;
        }
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int slash = stripped.indexOf('/');
        if (slash < 0 || slash == stripped.length() - 1) {
            return null;
        }
        return stripped.substring(slash + 1);
    }

    /** Decodes a URL path, skipping the decoder when it holds no escape and no plus. */
    static String decodePath(String raw) {
        return raw.indexOf('%') < 0 && raw.indexOf('+') < 0
                ? raw : URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * True when the raw request path carries no bucket prefix because the bucket came from the
     * Host header: the rewritten request URI is then exactly "/" + bucket + rawPath, which is
     * what {@link S3VirtualHostFilter} produces and what a path-style request never is. JAX-RS
     * collapses consecutive slashes before the filter sees the path, so a leading-slash key
     * ({@code //file.txt} on the wire) is rewritten to {@code /<bucket>/file.txt}; the collapsed
     * form is compared as well so that key keeps its slash.
     */
    static boolean isVirtualHostedRawPath(String rawPath, String requestUriPath, String bucket) {
        if (rawPath.isEmpty() || rawPath.charAt(0) != '/') {
            return false;
        }
        if (requestUriPath == null || requestUriPath.equals(rawPath)) {
            return false;
        }
        return requestUriPath.equals("/" + bucket + rawPath)
                || requestUriPath.equals("/" + bucket + rawPath.replaceAll("/{2,}", "/"));
    }
}
