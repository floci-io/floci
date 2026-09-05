package io.github.hectorvent.floci.services.s3.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checksum algorithms Floci supports, with the multipart checksum types AWS allows for each one:
 * SHA1 and SHA256 are always {@code COMPOSITE}, CRC64NVME is always {@code FULL_OBJECT}, and
 * CRC32 and CRC32C accept both, defaulting to {@code COMPOSITE}.
 */
@RegisterForReflection
public enum ChecksumAlgorithm {
    CRC32(Set.of(ChecksumType.COMPOSITE, ChecksumType.FULL_OBJECT)),
    CRC32C(Set.of(ChecksumType.COMPOSITE, ChecksumType.FULL_OBJECT)),
    CRC64NVME(Set.of(ChecksumType.FULL_OBJECT)),
    SHA1(Set.of(ChecksumType.COMPOSITE)),
    SHA256(Set.of(ChecksumType.COMPOSITE));

    /** Algorithms AWS accepts but Floci does not implement yet. */
    private static final Set<String> KNOWN_UNSUPPORTED = Set.of("SHA512", "MD5", "XXHASH3", "XXHASH64", "XXHASH128");

    private final Set<ChecksumType> multipartTypes;

    ChecksumAlgorithm(Set<ChecksumType> multipartTypes) {
        this.multipartTypes = multipartTypes;
    }

    /** Lowercase name, as AWS spells the algorithm in error messages. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean supports(ChecksumType type) {
        return multipartTypes.contains(type);
    }

    /**
     * The checksum type a multipart upload with this algorithm ends up with: the requested one when
     * the algorithm allows it, otherwise the default (COMPOSITE where supported, else FULL_OBJECT).
     */
    public ChecksumType multipartType(ChecksumType requested) {
        if (requested == null) {
            return supports(ChecksumType.COMPOSITE) ? ChecksumType.COMPOSITE : ChecksumType.FULL_OBJECT;
        }
        if (!supports(requested)) {
            throw new AwsException("InvalidRequest", "The " + requested + " checksum type cannot be used with the "
                    + wireValue() + " checksum algorithm.", 400);
        }
        return requested;
    }

    /** Base64 checksum of {@code data} with this algorithm. */
    public String compute(byte[] data) {
        return switch (this) {
            case CRC32 -> S3Checksum.crc32Base64(data);
            case CRC32C -> S3Checksum.crc32cBase64(data);
            case CRC64NVME -> S3Checksum.crc64NvmeBase64(data);
            case SHA1 -> S3Checksum.sha1Base64(data);
            case SHA256 -> S3Checksum.sha256Base64(data);
        };
    }

    /**
     * Composite checksum of a multipart object: this algorithm applied to the concatenated binary
     * part checksums, Base64 encoded and suffixed with the part count (for example {@code ...=-3}).
     */
    public String composite(List<String> partChecksums) {
        ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
        for (String partChecksum : partChecksums) {
            concatenated.writeBytes(Base64.getDecoder().decode(partChecksum));
        }
        return compute(concatenated.toByteArray()) + "-" + partChecksums.size();
    }

    /**
     * Parses an {@code x-amz-checksum-algorithm} value. Returns {@code null} when the header is absent.
     */
    public static ChecksumAlgorithm fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ChecksumAlgorithm algorithm : values()) {
            if (algorithm.name().equals(normalized)) {
                return algorithm;
            }
        }
        if (KNOWN_UNSUPPORTED.contains(normalized)) {
            throw new AwsException("InvalidRequest", "The checksum algorithm you specified is a valid AWS checksum "
                    + "algorithm, but is not currently supported by Floci (supported: CRC32, CRC32C, CRC64NVME, SHA1, SHA256).", 400);
        }
        throw new AwsException("InvalidArgument", "The checksum algorithm you specified is not supported.", 400);
    }
}
