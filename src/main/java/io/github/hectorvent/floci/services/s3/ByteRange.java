package io.github.hectorvent.floci.services.s3;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One byte range of an object, from a {@code Range} header that asks for a single range, as RFC 9110
 * section 14.1.2 defines it. {@code last} is inclusive. S3 serves one range per GET request.
 */
public record ByteRange(long first, long last) {

    /**
     * The message of S3's {@code InvalidRange} error as S3 sends it, which is not the wording of the
     * error code table's description.
     */
    public static final String NOT_SATISFIABLE_MESSAGE = "The requested range is not satisfiable";

    private static final String UNIT = "bytes=";
    private static final Pattern SPEC = Pattern.compile("(\\d*)-(\\d*)");

    /**
     * What a {@code Range} header asks of an object: a part of it, the whole of it (no header, a
     * unit other than bytes, or a suffix of an empty object), a range that starts past its end, or
     * something that is not one valid range, such as several ranges or a last byte before the first.
     */
    public enum Outcome { PART, WHOLE, NOT_SATISFIABLE, INVALID }

    /** A resolved {@code Range} header; {@code range} is set only for {@link Outcome#PART}. */
    public record Resolution(Outcome outcome, ByteRange range) {
    }

    public ByteRange {
        if (first < 0 || last < first) {
            throw new IllegalArgumentException("Not a byte range: " + first + "-" + last);
        }
    }

    public long length() {
        return last - first + 1;
    }

    /** The {@code Content-Range} value of this range of an object of {@code size} bytes. */
    public String contentRange(long size) {
        return "bytes " + first + "-" + last + "/" + size;
    }

    /** The {@code Content-Range} value of a 416 response for an object of {@code size} bytes. */
    public static String unsatisfiedContentRange(long size) {
        return "bytes */" + size;
    }

    /**
     * Whether a {@code Range} header asks for bytes, the one unit S3 serves. Range unit names are
     * case-insensitive (RFC 9110, section 14.1).
     */
    public static boolean inBytes(String header) {
        return header != null && header.regionMatches(true, 0, UNIT, 0, UNIT.length());
    }

    /**
     * Whether a request's {@code Range} applies to an object, given the request's {@code If-Range}:
     * always without one, and otherwise when its validator still describes the object, a quoted
     * entity tag equal to the object's ETag, or an HTTP-date equal to its Last-Modified.
     * Any other validator, a weak or malformed one included, means the whole object is served.
     */
    public static boolean rangeApplies(String ifRange, String etag, Instant lastModified) {
        if (ifRange == null) {
            return true;
        }
        String validator = ifRange.trim();
        if (validator.startsWith("\"")) {
            return validator.length() > 1 && validator.endsWith("\"") && validator.equals(etag);
        }
        Instant date;
        try {
            date = ZonedDateTime.parse(validator, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException expected) {
            // Neither a quoted entity tag nor an HTTP-date, such as a weak W/ tag: it cannot match.
            return false;
        }
        // RFC 9110 section 13.1.5 matches a date exactly, not as the "not modified since" of
        // If-Unmodified-Since, so a later date gets the whole object. Last-Modified is sent to the
        // second, so it is compared to the second.
        return lastModified != null && lastModified.truncatedTo(ChronoUnit.SECONDS).equals(date);
    }

    /** This range's bytes, read from {@code whole}, a stream over the whole object, as they are needed. */
    public InputStream slice(InputStream whole) {
        return new ByteRangeInputStream(whole, first, length());
    }

    /** Resolves a {@code Range} header against an object of {@code size} bytes. */
    public static Resolution resolve(String header, long size) {
        if (!inBytes(header)) {
            return new Resolution(Outcome.WHOLE, null);
        }
        Matcher matcher = SPEC.matcher(header.substring(UNIT.length()).trim());
        if (!matcher.matches() || (matcher.group(1).isEmpty() && matcher.group(2).isEmpty())) {
            return new Resolution(Outcome.INVALID, null);
        }
        long first;
        long last;
        if (matcher.group(1).isEmpty()) {
            long suffix = position(matcher.group(2));
            if (suffix == 0) {
                return new Resolution(Outcome.NOT_SATISFIABLE, null);
            }
            if (size == 0) {
                return new Resolution(Outcome.WHOLE, null);
            }
            first = Math.max(0, size - suffix);
            last = size - 1;
        } else {
            first = position(matcher.group(1));
            last = matcher.group(2).isEmpty() ? Long.MAX_VALUE : position(matcher.group(2));
            if (last < first) {
                return new Resolution(Outcome.INVALID, null);
            }
            last = Math.min(last, size - 1);
        }
        if (first >= size) {
            return new Resolution(Outcome.NOT_SATISFIABLE, null);
        }
        return new Resolution(Outcome.PART, new ByteRange(first, last));
    }

    private static long position(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            // The pattern admits digits alone, so only a position too large for a long gets here, and
            // that is past the end of any object.
            return Long.MAX_VALUE;
        }
    }
}
