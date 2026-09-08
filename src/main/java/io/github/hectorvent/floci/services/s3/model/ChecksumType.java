package io.github.hectorvent.floci.services.s3.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;

/** The two ways S3 derives an object checksum from a multipart upload. */
@RegisterForReflection
public enum ChecksumType {
    /** The algorithm applied to the concatenated part checksums, suffixed with the part count. */
    COMPOSITE,
    /** The algorithm applied to the whole object content. */
    FULL_OBJECT;

    /**
     * Parses an {@code x-amz-checksum-type} value, case-insensitively like S3. Returns {@code null} when the
     * header is absent; an unknown value is the {@code InvalidRequest} S3 returns for it.
     */
    public static ChecksumType fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ChecksumType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new AwsException("InvalidRequest", "Value for x-amz-checksum-type header is invalid.", 400);
    }
}
