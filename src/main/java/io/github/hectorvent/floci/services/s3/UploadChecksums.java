package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.model.ChecksumAlgorithm;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The integrity headers an upload body is checked against: Content-MD5 and the x-amz-checksum-*
 * values. A malformed Content-MD5 is rejected before any of the body is read; the digests are
 * compared once it has been. {@code trailerAlgorithm} is the checksum named by {@code x-amz-trailer}
 * when that header names an {@code x-amz-checksum-*} value.
 */
record UploadChecksums(String contentMd5, Map<ChecksumAlgorithm, String> claimed,
                       ChecksumAlgorithm trailerAlgorithm) {

    /** No integrity headers, as for the part an UploadPartCopy reads from another object. */
    static final UploadChecksums NONE = new UploadChecksums(null, Map.of(), null);

    // The order the checksums have always been checked in, so a body failing several reports the same one.
    private static final List<ChecksumAlgorithm> CHECK_ORDER = List.of(ChecksumAlgorithm.SHA1,
            ChecksumAlgorithm.SHA256, ChecksumAlgorithm.CRC32, ChecksumAlgorithm.CRC32C, ChecksumAlgorithm.CRC64NVME);

    /** A request that does not declare a trailing checksum. */
    UploadChecksums(String contentMd5, Map<ChecksumAlgorithm, String> claimed) {
        this(contentMd5, claimed, null);
    }

    UploadChecksums {
        claimed = Map.copyOf(claimed);
    }

    /** The algorithms the request claims a checksum for, which the body has to be hashed with. */
    Set<ChecksumAlgorithm> algorithms() {
        return claimed.keySet();
    }

    // S3 answers InvalidDigest for a Content-MD5 that is not the base64 of a 16-byte digest, and
    // BadDigest for a well-formed one that does not match the payload.
    void requireWellFormedContentMd5() {
        if (contentMd5 != null) {
            expectedMd5();
        }
    }

    void verify(byte[] actualMd5, Function<ChecksumAlgorithm, String> actualChecksum) {
        if (contentMd5 != null && !MessageDigest.isEqual(expectedMd5(), actualMd5)) {
            throw new AwsException("BadDigest", "The Content-MD5 you specified did not match what we received.", 400);
        }
        for (ChecksumAlgorithm algorithm : CHECK_ORDER) {
            String claimedValue = claimed.get(algorithm);
            if (claimedValue != null && !claimedValue.equals(actualChecksum.apply(algorithm))) {
                throw checksumMismatch(algorithm);
            }
        }
    }

    /**
     * Compares the trailing checksum named by {@code x-amz-trailer} to the decoded body.
     * {@code x-amz-trailer-signature} is not a checksum and is ignored. A declared trailer whose
     * checksum line is missing, or any other {@code x-amz-checksum-*} line, fails the same way as
     * a value that does not match.
     */
    void verifyTrailers(AwsChunkedInputStream chunked, Function<ChecksumAlgorithm, String> actualChecksum) {
        String claimedValue = chunked.trailerChecksum();
        if (trailerAlgorithm != null
                && (claimedValue == null || !claimedValue.equals(actualChecksum.apply(trailerAlgorithm)))) {
            throw checksumMismatch(trailerAlgorithm);
        }
        if (chunked.hasUndeclaredChecksum()) {
            ChecksumAlgorithm reported = chunked.undeclaredTrailer() != null
                    ? chunked.undeclaredTrailer() : trailerAlgorithm;
            throw checksumMismatch(reported);
        }
    }

    private static AwsException checksumMismatch(ChecksumAlgorithm algorithm) {
        String name = algorithm != null ? algorithm.name() : "checksum";
        return new AwsException("BadDigest",
                "The " + name + " you specified did not match the calculated checksum.", 400);
    }

    private byte[] expectedMd5() {
        byte[] expected;
        try {
            expected = Base64.getDecoder().decode(contentMd5.trim());
        } catch (IllegalArgumentException e) {
            expected = null;
        }
        if (expected == null || expected.length != 16) {
            throw new AwsException("InvalidDigest", "The Content-MD5 you specified is not valid.", 400);
        }
        return expected;
    }
}
