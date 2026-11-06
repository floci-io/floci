package io.floci.aws.services.rekognition;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rejects the images Rekognition rejects, before any response is produced. Measured against
 * Rekognition in us-east-2 for every operation floci emulates, with each image sent as
 * {@code Bytes} and as an {@code S3Object}:
 *
 * <ul>
 *   <li>{@code Bytes} that are not base64: {@code SerializationException}.</li>
 *   <li>{@code Bytes} empty or longer than 5,242,880 bytes: {@code ValidationException}, naming every
 *       such member of the request.</li>
 *   <li>An {@code S3Object} that cannot be read, including one in a bucket of another region:
 *       {@code InvalidS3ObjectException}.</li>
 *   <li>Anything but a complete JPEG or PNG (WebP, GIF, other bytes, a truncated file):
 *       {@code InvalidImageFormatException}.</li>
 *   <li>A side over 10,000 pixels: {@code ImageTooLargeException} from {@code DetectModerationLabels}
 *       and {@code DetectLabels}, {@code InvalidImageFormatException} from the others.</li>
 * </ul>
 *
 * <p>Two documented limits are not enforced because Rekognition does not enforce them: images
 * under 80 pixels were accepted by every operation, and a 17.8 MB S3 object was accepted despite
 * the documented 15 MB limit.
 *
 * <p>Formats are recognised from their structure, not decoded, matching where Rekognition draws
 * the line: a JPEG needs its headers complete through the start-of-scan header, and is accepted
 * however much of the scan after it is missing, even with no end-of-image marker; a PNG needs an
 * {@code IHDR} chunk first, an {@code IDAT} chunk, and an {@code IEND} chunk that its chunk
 * lengths reach.
 */
@ApplicationScoped
public class RekognitionImageValidator {

    private static final Logger LOG = Logger.getLogger(RekognitionImageValidator.class);

    static final int MAX_BYTES = 5_242_880;
    /** The longest base64 that can decode to MAX_BYTES or fewer; anything longer is over the limit. */
    static final int MAX_ENCODED_LENGTH = (MAX_BYTES + 2) / 3 * 4;
    static final int MAX_DIMENSION = 10_000;

    /** The operations whose oversized image fails with ImageTooLargeException rather than an invalid format. */
    private static final Set<String> TOO_LARGE_REPORTED = Set.of("DetectModerationLabels", "DetectLabels");

    private static final String INVALID_FORMAT = "Request has invalid image format";
    private static final String UNREADABLE_OBJECT =
            "Unable to get object metadata from S3. Check object key, region and/or access permissions.";

    private final S3Service s3Service;
    private final EmulatorConfig config;

    @Inject
    public RekognitionImageValidator(S3Service s3Service, EmulatorConfig config) {
        this.s3Service = s3Service;
        this.config = config;
    }

    /**
     * Validates the images in {@code request.<fields>}, each already known to carry {@code Bytes}
     * or an {@code S3Object}, in the order Rekognition does: every {@code Bytes} is decoded, then
     * their lengths are checked together, then each image is read and checked in turn. When an
     * image has both, its bytes are what is checked.
     */
    public void validate(String operation, JsonNode request, String region, String... fields) {
        Map<String, Decoded> bytes = new LinkedHashMap<>();
        for (String field : fields) {
            JsonNode image = request.get(field);
            if (image.hasNonNull("Bytes")) {
                bytes.put(field, decoded(image.get("Bytes").asText()));
            }
        }
        requireLengthsWithinLimits(bytes);
        for (String field : fields) {
            int[] dimensions = bytes.containsKey(field)
                    ? dimensions(bytes.get(field).data())
                    : s3ImageDimensions(request.get(field).get("S3Object"), region);
            requireAcceptedImage(operation, dimensions);
        }
    }

    /** A {@code Bytes} member: its content, or only its length when that is certainly over the limit. */
    private record Decoded(byte[] data, long length) {
    }

    private static Decoded decoded(String encoded) {
        try {
            if (encoded.length() <= MAX_ENCODED_LENGTH) {
                byte[] data = Base64.getDecoder().decode(encoded);
                return new Decoded(data, data.length);
            }
            return new Decoded(null, decodedLength(encoded));
        } catch (IllegalArgumentException | IOException e) {
            throw new AwsException("SerializationException", "Image bytes are not valid base64.", 400);
        }
    }

    /**
     * Counts what base64 too long to be accepted decodes to, still refusing invalid base64 first,
     * without holding the decoded bytes: the request body may be far larger than 5 MiB.
     */
    private static long decodedLength(String encoded) throws IOException {
        long length = 0;
        byte[] buffer = new byte[8192];
        try (InputStream in = Base64.getDecoder().wrap(new AsciiInputStream(encoded))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                length += read;
            }
        }
        return length;
    }

    /** Rekognition reports every out-of-range {@code Bytes} member in one ValidationException. */
    private static void requireLengthsWithinLimits(Map<String, Decoded> bytes) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Decoded> entry : bytes.entrySet()) {
            String field = entry.getKey();
            String member = Character.toLowerCase(field.charAt(0)) + field.substring(1) + ".bytes";
            long length = entry.getValue().length();
            if (length < 1) {
                violations.add("Value at '" + member + "' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1");
            } else if (length > MAX_BYTES) {
                violations.add("Value at '" + member + "' failed to satisfy constraint: "
                        + "Member must have length less than or equal to " + MAX_BYTES);
            }
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
    }

    private static void requireAcceptedImage(String operation, int[] dimensions) {
        if (dimensions == null) {
            throw new AwsException("InvalidImageFormatException", INVALID_FORMAT, 400);
        }
        int width = dimensions[0];
        int height = dimensions[1];
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            if (TOO_LARGE_REPORTED.contains(operation)) {
                throw new AwsException("ImageTooLargeException", "Input image dimensions " + width + " x " + height
                        + " exceed maximum dimension size of " + MAX_DIMENSION + " pixels", 400);
            }
            throw new AwsException("InvalidImageFormatException", INVALID_FORMAT, 400);
        }
    }

    /**
     * The dimensions of the object, when it exists in a bucket of the request's region. The object
     * is streamed, reading only as far as its format needs, so its size does not matter.
     */
    private int[] s3ImageDimensions(JsonNode s3Object, String region) {
        String bucket = s3Object.path("Bucket").asText(null);
        String name = s3Object.path("Name").asText(null);
        String version = s3Object.path("Version").asText(null);
        if (bucket == null || name == null) {
            throw new AwsException("InvalidS3ObjectException", UNREADABLE_OBJECT, 400);
        }
        String bucketRegion;
        try {
            bucketRegion = s3Service.getBucketRegion(bucket);
        } catch (AwsException e) {
            throw unreadable(bucket, name, e.getErrorCode());
        }
        if (bucketRegion == null || bucketRegion.isBlank()) {
            // A bucket created without a location constraint is in the default region.
            bucketRegion = config.defaultRegion();
        }
        if (!bucketRegion.equalsIgnoreCase(region)) {
            throw unreadable(bucket, name, "bucket is in " + bucketRegion + ", not " + region);
        }
        S3Service.ObjectRead read;
        try {
            read = s3Service.openObject(bucket, name, version);
        } catch (AwsException e) {
            throw unreadable(bucket, name, e.getErrorCode());
        }
        try (read) {
            return dimensions(read.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close S3 object s3://" + bucket + "/" + name, e);
        }
    }

    private static AwsException unreadable(String bucket, String name, String reason) {
        LOG.debugv("Rekognition image s3://{0}/{1} is unreadable: {2}", bucket, name, reason);
        return new AwsException("InvalidS3ObjectException", UNREADABLE_OBJECT, 400);
    }

    /** The image's width and height, or null when it is not a complete JPEG or PNG. */
    static int[] dimensions(byte[] data) {
        return data == null ? null : dimensions(new ByteArrayInputStream(data));
    }

    static int[] dimensions(InputStream in) {
        try {
            byte[] start = in.readNBytes(2);
            if (start.length == 2 && (start[0] & 0xFF) == 0xFF && (start[1] & 0xFF) == 0xD8) {
                return jpegDimensions(in);
            }
            byte[] rest = in.readNBytes(6);
            if (start.length == 2 && rest.length == 6 && (start[0] & 0xFF) == 0x89 && start[1] == 'P'
                    && rest[0] == 'N' && rest[1] == 'G' && rest[2] == 0x0D && rest[3] == 0x0A
                    && rest[4] == 0x1A && rest[5] == 0x0A) {
                return pngDimensions(in);
            }
            return null;
        } catch (EOFException expected) {
            // A segment or chunk runs past the end of the data: the file is cut off.
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Rekognition image", e);
        }
    }

    private static int[] jpegDimensions(InputStream in) throws IOException {
        int[] frame = null;
        while (true) {
            int b = in.read();
            // Rekognition accepted stray bytes between segments, as libjpeg does with a warning.
            while (b != -1 && b != 0xFF) {
                b = in.read();
            }
            while (b == 0xFF) {
                b = in.read();
            }
            if (b == -1) {
                return null;
            }
            int marker = b;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            if (marker == 0xD8 || marker == 0xD9) {
                return null;
            }
            int length = readShort(in);
            if (length < 2) {
                return null;
            }
            boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (!startOfFrame && marker != 0xDA) {
                in.skipNBytes(length - 2);
                continue;
            }
            byte[] segment = in.readNBytes(length - 2);
            if (segment.length < length - 2) {
                return null;
            }
            if (startOfFrame) {
                int components = length < 8 ? 0 : segment[5] & 0xFF;
                if (components == 0 || length != 8 + 3 * components) {
                    return null;
                }
                int height = ((segment[1] & 0xFF) << 8) | (segment[2] & 0xFF);
                int width = ((segment[3] & 0xFF) << 8) | (segment[4] & 0xFF);
                frame = new int[] {width, height};
            } else {
                // A complete start-of-scan header is all Rekognition needs: it accepted JPEGs cut
                // off anywhere in the scan that follows, and one without an end-of-image marker.
                int components = length < 3 ? 0 : segment[0] & 0xFF;
                if (components < 1 || components > 4 || length != 6 + 2 * components) {
                    return null;
                }
                return frame != null && frame[0] > 0 && frame[1] > 0 ? frame : null;
            }
        }
    }

    private static int[] pngDimensions(InputStream in) throws IOException {
        int[] header = null;
        boolean imageData = false;
        while (true) {
            byte[] chunk = in.readNBytes(8);
            if (chunk.length < 8) {
                return null;
            }
            long length = readInt(chunk, 0) & 0xFFFFFFFFL;
            String type = new String(chunk, 4, 4, StandardCharsets.US_ASCII);
            if (header == null) {
                if (!"IHDR".equals(type) || length != 13) {
                    return null;
                }
                byte[] content = in.readNBytes(13);
                if (content.length < 13) {
                    return null;
                }
                header = new int[] {readInt(content, 0), readInt(content, 4)};
                in.skipNBytes(4);
                continue;
            }
            in.skipNBytes(length + 4);
            if ("IDAT".equals(type)) {
                imageData = true;
            }
            if ("IEND".equals(type)) {
                return imageData && header[0] > 0 && header[1] > 0 ? header : null;
            }
        }
    }

    private static int readShort(InputStream in) throws IOException {
        int high = in.read();
        int low = in.read();
        if (low == -1) {
            throw new EOFException();
        }
        return (high << 8) | low;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    /** A string's characters as bytes; a character base64 cannot contain fails the read. */
    private static final class AsciiInputStream extends InputStream {

        private final String text;
        private int position;

        AsciiInputStream(String text) {
            this.text = text;
        }

        @Override
        public int read() throws IOException {
            if (position >= text.length()) {
                return -1;
            }
            char c = text.charAt(position++);
            if (c > 0x7F) {
                throw new IOException("Non-ASCII character in base64");
            }
            return c;
        }
    }
}
