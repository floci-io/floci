package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.model.S3Object;

import java.time.Instant;
import java.util.Map;

/** A read that reached a delete marker, answered as a missing key unless the request named the marker. */
final class S3DeleteMarkerException extends AwsException {

    private final String versionId;
    private final Instant lastModified;

    S3DeleteMarkerException(S3Object marker) {
        super("NoSuchKey", "The specified key does not exist.", 404, Map.of("Key", marker.getKey()));
        this.versionId = marker.getVersionId() != null ? marker.getVersionId() : "null";
        this.lastModified = marker.getLastModified();
    }

    private S3DeleteMarkerException(S3DeleteMarkerException current, String method) {
        super("MethodNotAllowed", "The specified method is not allowed against this resource.", 405,
                Map.of("Method", method, "ResourceType", "DeleteMarker"));
        this.versionId = current.versionId;
        this.lastModified = current.lastModified;
    }

    /** The refusal S3 gives a {@code method} request that names this delete marker by its version id. */
    S3DeleteMarkerException named(String method) {
        return new S3DeleteMarkerException(this, method);
    }

    String versionId() {
        return versionId;
    }

    Instant lastModified() {
        return lastModified;
    }
}
