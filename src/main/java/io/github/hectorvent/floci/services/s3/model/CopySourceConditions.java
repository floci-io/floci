package io.github.hectorvent.floci.services.s3.model;

import java.time.Instant;

/**
 * The {@code x-amz-copy-source-if-*} preconditions of a CopyObject or UploadPartCopy request,
 * evaluated against the source object. Each field is null when its header was absent (or, for the
 * dates, unparseable, which S3 treats the same way).
 */
public record CopySourceConditions(String ifMatch, String ifNoneMatch,
                                   Instant ifModifiedSince, Instant ifUnmodifiedSince) {

    public static final CopySourceConditions NONE = new CopySourceConditions(null, null, null, null);
}
