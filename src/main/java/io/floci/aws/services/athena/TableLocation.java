package io.floci.aws.services.athena;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;

import java.util.Arrays;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A table's {@code s3://bucket/prefix/} location split into its bucket and its key prefix, ending in a slash. */
record TableLocation(String bucket, String prefix) {

    private static final int LIST_PAGE_SIZE = 1000;
    /** The glob characters DuckDB reads in a path besides {@code *}. */
    private static final Pattern UNMODELED_GLOB = Pattern.compile("[?\\[\\]{}\\\\]");

    /** The location {@code location} names, or null when it is not an {@code s3://} location. */
    static TableLocation of(String location) {
        if (location == null || !location.startsWith("s3://")) {
            return null;
        }
        String withoutScheme = location.substring("s3://".length());
        int slash = withoutScheme.indexOf('/');
        String bucket = slash < 0 ? withoutScheme : withoutScheme.substring(0, slash);
        String prefix = slash < 0 ? "" : withoutScheme.substring(slash + 1);
        return new TableLocation(bucket, prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/");
    }

    /**
     * Whether {@code accountId} owns the location's bucket, and so may delete what lies under it: Floci deletes a
     * table's files itself, where Athena deletes them as the caller, so a table placed in another account's bucket
     * keeps them. False when the bucket does not exist.
     */
    boolean ownedBy(S3Service s3, String accountId) {
        try {
            return accountId.equals(s3.getBucketOwnerAccountId(bucket));
        } catch (AwsException e) {
            if ("NoSuchBucket".equals(e.getErrorCode())) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Whether no object the read of {@code <location>/**} reaches holds data: there is none, or only the zero-byte
     * folder markers (keys ending in a slash) the S3 console creates. A location with {@code *} in it, a projected
     * table's read path, is listed from the literal prefix before its first {@code *}, an object counting when its
     * key matches, each {@code *} taking part or all of one folder name. False when the location holds a glob
     * character this does not model ({@code ?}, a bracket or brace expression, an escape), which the read keeps, and
     * when the bucket does not exist, which reading the location reports as it did. The location is listed in one
     * scan, as every page of a listing scans the whole prefix.
     */
    boolean holdsNoData(S3Service s3) {
        if (UNMODELED_GLOB.matcher(prefix).find()) {
            return false;
        }
        int wildcard = prefix.indexOf('*');
        String listed = wildcard < 0 ? prefix : prefix.substring(0, prefix.lastIndexOf('/', wildcard) + 1);
        Pattern reached = wildcard < 0 ? null : Pattern.compile(Arrays.stream(prefix.split("\\*", -1))
                .map(Pattern::quote).collect(Collectors.joining("[^/]*")) + ".+");
        try {
            return s3.listObjects(bucket, listed, null, Integer.MAX_VALUE).stream().map(S3Object::getKey)
                    .noneMatch(key -> !key.endsWith("/") && (reached == null || reached.matcher(key).matches()));
        } catch (AwsException e) {
            if ("NoSuchBucket".equals(e.getErrorCode())) {
                return false;
            }
            throw e;
        }
    }

    /** Whether {@code other} lies at or under this location, in the same bucket. */
    boolean covers(TableLocation other) {
        return bucket.equals(other.bucket) && other.prefix.startsWith(prefix);
    }

    /**
     * The keys under the location that hold data, folder markers left out, from one scan, so the locations it covers
     * can be told apart without a listing each. Null when the location holds a glob character or its bucket does not
     * exist, which reading it reports as it did.
     */
    NavigableSet<String> keysHoldingData(S3Service s3) {
        if (UNMODELED_GLOB.matcher(prefix).find() || prefix.indexOf('*') >= 0) {
            return null;
        }
        try {
            return s3.listObjects(bucket, prefix, null, Integer.MAX_VALUE).stream().map(S3Object::getKey)
                    .filter(key -> !key.endsWith("/")).collect(Collectors.toCollection(TreeSet::new));
        } catch (AwsException e) {
            if ("NoSuchBucket".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    /** Hands each object under the location to {@code action}, a page at a time; the action may delete it. */
    void forEachObject(S3Service s3, Consumer<S3Object> action) {
        String continuation = null;
        do {
            S3Service.ListObjectsResult page = s3.listObjectsWithPrefixes(bucket, prefix, null, LIST_PAGE_SIZE,
                    continuation, null);
            page.objects().forEach(action);
            continuation = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (continuation != null);
    }
}
