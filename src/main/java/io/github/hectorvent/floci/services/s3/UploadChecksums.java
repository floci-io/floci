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
 * compared once it has been.
 */
record UploadChecksums(String contentMd5, Map<ChecksumAlgorithm, String> claimed) {

    /** No integrity headers, as for the part an UploadPartCopy reads from another object. */
    static final UploadChecksums NONE = new UploadChecksums(null, Map.of());

    // The order the checksums have always been checked in, so a body failing several reports the same one.
    private static final List<ChecksumAlgorithm> CHECK_ORDER = List.of(ChecksumAlgorithm.SHA1,
            ChecksumAlgorithm.SHA256, ChecksumAlgorithm.CRC32, ChecksumAlgorithm.CRC32C, ChecksumAlgorithm.CRC64NVME);

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
                throw new AwsException("BadDigest", "The " + algorithm.name()
                        + " checksum you specified did not match the payload.", 400);
            }
        }
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
