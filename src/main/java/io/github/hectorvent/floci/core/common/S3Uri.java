package io.github.hectorvent.floci.core.common;

import java.util.regex.Pattern;

/**
 * An {@code s3://bucket/key} URI, split into its bucket and its key. The key is empty when the URI names
 * only a bucket, and keeps a trailing slash, as a key prefix does.
 */
public record S3Uri(String bucket, String key) {

    private static final String SCHEME = "s3://";
    // Hadoop's s3n:// and s3a://, in which an EMR cluster's paths are often written, name the same buckets.
    private static final Pattern ANY_S3_SCHEME = Pattern.compile("^s3[an]?://");

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

    /** Whether {@code uri} is in {@code s3://}, or in Hadoop's {@code s3n://} or {@code s3a://}. */
    public static boolean isS3(String uri) {
        return uri != null && ANY_S3_SCHEME.matcher(uri).find();
    }

    /** As {@link #parse}, taking Hadoop's {@code s3n://} and {@code s3a://} for {@code s3://}. */
    public static S3Uri parseAnyScheme(String uri) {
        return parse(uri == null ? null : ANY_S3_SCHEME.matcher(uri).replaceFirst(SCHEME));
    }
}
