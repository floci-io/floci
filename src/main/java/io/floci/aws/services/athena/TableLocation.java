package io.floci.aws.services.athena;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;

import java.util.function.Consumer;

/** A table's {@code s3://bucket/prefix/} location split into its bucket and its key prefix, ending in a slash. */
record TableLocation(String bucket, String prefix) {

    private static final int LIST_PAGE_SIZE = 1000;

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
