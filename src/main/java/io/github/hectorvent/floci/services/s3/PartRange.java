package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.model.Part;
import io.github.hectorvent.floci.services.s3.model.S3Object;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The bytes of one part of an object, which GetObject and HeadObject serve for a {@code partNumber}
 * as they would a range covering that part. {@code part} is the part's record when the object was
 * completed from a multipart upload, and null for an object stored in one request, which is its own
 * only part.
 */
record PartRange(long first, long length, Part part) {

    /** The message of S3's {@code InvalidPartNumber} error, for a part the object does not have. */
    static final String NOT_SATISFIABLE_MESSAGE = "The requested partnumber is not satisfiable";

    /** The message of S3's {@code InvalidRequest} error for a request that names a range and a part. */
    static final String WITH_RANGE_MESSAGE = "Cannot specify both Range header and partNumber query parameter";

    /**
     * The part a {@code partNumber} query parameter names, failing with the InvalidArgument S3 gives a part
     * number below 1. One above the object's part count, past 10,000 or past what a long holds, names a part
     * the object does not have, which is S3's 416 rather than a 400.
     */
    static BigInteger parsePartNumber(String value) {
        try {
            BigInteger partNumber = new BigInteger(value.trim());
            if (partNumber.signum() > 0) {
                return partNumber;
            }
        } catch (NumberFormatException ignored) {
            // What is not a number is no part number either, so it is rejected the same way.
        }
        throw invalidPartNumber(value);
    }

    /** Parses an UploadPart or UploadPartCopy number, whose valid range is bounded by S3. */
    static int parseUploadPartNumber(String value) {
        BigInteger partNumber = parsePartNumber(value);
        if (partNumber.compareTo(BigInteger.valueOf(10000)) > 0) {
            throw invalidPartNumber(value);
        }
        return partNumber.intValue();
    }

    static AwsException invalidPartNumber(String value) {
        return new AwsException("InvalidArgument", "Part number must be an integer between 1 and 10000, inclusive",
                400, Map.of("ArgumentName", "partNumber", "ArgumentValue", value));
    }

    /** Part {@code partNumber} of {@code obj}, or empty when the object has no such part. */
    static Optional<PartRange> of(S3Object obj, BigInteger partNumber) {
        if (!isMultipart(obj)) {
            return partNumber.equals(BigInteger.ONE)
                    ? Optional.of(new PartRange(0, obj.getSize(), null))
                    : Optional.empty();
        }
        // The parts are kept in the order they were assembled in, so each starts where the last ended.
        long first = 0;
        for (Part part : obj.getParts()) {
            if (partNumber.equals(BigInteger.valueOf(part.getPartNumber()))) {
                return Optional.of(new PartRange(first, part.getSize(), part));
            }
            first += part.getSize();
        }
        return Optional.empty();
    }

    /** How many parts {@code obj} has; an object stored in one request has one. */
    static int partCount(S3Object obj) {
        return isMultipart(obj) ? obj.getParts().size() : 1;
    }

    private static boolean isMultipart(S3Object obj) {
        List<Part> parts = obj.getParts();
        return parts != null && !parts.isEmpty();
    }

    /** Whether this part holds all of an object of {@code size} bytes. */
    boolean coversObject(long size) {
        return first == 0 && length == size;
    }

    /**
     * The {@code Content-Range} of this part of an object of {@code size} bytes, or null for a part of no
     * bytes, which no byte range describes.
     */
    String contentRange(long size) {
        return length == 0 ? null : new ByteRange(first, first + length - 1).contentRange(size);
    }

    /** This part's bytes, read from a stream over the whole object as they are needed. */
    InputStream slice(InputStream whole) {
        return new ByteRangeInputStream(whole, first, length);
    }
}
