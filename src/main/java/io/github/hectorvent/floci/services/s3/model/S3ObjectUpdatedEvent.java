package io.github.hectorvent.floci.services.s3.model;

/**
 * Internal event fired when an S3 object is created or updated.
 * Observed by other services (like Lambda) to trigger reactive behaviors.
 *
 * @param bucketName the name of the bucket
 * @param key the object key
 */
public record S3ObjectUpdatedEvent(String bucketName, String key) {
}
