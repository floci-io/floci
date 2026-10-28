package io.github.hectorvent.floci.core.common;

/**
 * An {@code s3://bucket/key} URI, split into its bucket and its key. The key is empty when the URI names
 * only a bucket, and keeps a trailing slash, as a key prefix does.
 */
public record S3Uri(String bucket, String key) {

    private static final String SCHEME = "s3://";

    /** Splits {@code uri}; one that is not {@code s3://} or names no bucket is an IllegalArgumentException. */
    public static S3Uri parse(String uri) {
        if (uri == null || !uri.startsWith(SCHEME)) {
            throw new IllegalArgumentException("Not an s3:// URI: " + uri);
        }
        String rest = uri.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException("No bucket in " + uri);
        }
        return new S3Uri(bucket, slash < 0 ? "" : rest.substring(slash + 1));
    }
}
