package io.floci.aws.services.rekognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What Rekognition accepts and refuses, as measured in us-east-2 (see {@link RekognitionImageValidator}).
 */
class RekognitionImageValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INVALID_FORMAT = "Request has invalid image format";

    private final S3Service s3 = mock(S3Service.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final RekognitionImageValidator validator = new RekognitionImageValidator(s3, config);

    {
        when(config.defaultRegion()).thenReturn("us-east-1");
    }

    private static ObjectNode bytesImage(byte[] data) {
        ObjectNode image = MAPPER.createObjectNode();
        image.put("Bytes", Base64.getEncoder().encodeToString(data));
        return image;
    }

    private static ObjectNode s3Image(String bucket, String name) {
        ObjectNode image = MAPPER.createObjectNode();
        image.putObject("S3Object").put("Bucket", bucket).put("Name", name);
        return image;
    }

    private static final String REGION = "us-east-1";

    private static ObjectNode request(String field, ObjectNode image) {
        ObjectNode request = MAPPER.createObjectNode();
        request.set(field, image);
        return request;
    }

    private static ObjectNode compareFaces(ObjectNode source, ObjectNode target) {
        ObjectNode request = MAPPER.createObjectNode();
        request.set("SourceImage", source);
        request.set("TargetImage", target);
        return request;
    }

    private void accepted(String operation, ObjectNode image) {
        assertDoesNotThrow(() -> validator.validate(operation, request("Image", image), REGION, "Image"));
    }

    private AwsException refused(String operation, ObjectNode image, String field) {
        return refusedIn(REGION, operation, image, field);
    }

    private AwsException refusedIn(String region, String operation, ObjectNode image, String field) {
        return assertThrows(AwsException.class,
                () -> validator.validate(operation, request(field, image), region, field));
    }

    private static byte[] pngChunk(String type, byte[] content) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(content);
        return ByteBuffer.allocate(12 + content.length).putInt(content.length).put(typeBytes).put(content)
                .putInt((int) crc.getValue()).array();
    }

    private AwsException refusedCompareFaces(ObjectNode source, ObjectNode target) {
        return assertThrows(AwsException.class,
                () -> validator.validate("CompareFaces", compareFaces(source, target), REGION, "SourceImage", "TargetImage"));
    }

    private void storeObject(String bucket, String region, String name, byte[] data) {
        storeObject(bucket, region, name, () -> new ByteArrayInputStream(data));
    }

    private void storeObject(String bucket, String region, String name, Supplier<InputStream> body) {
        when(s3.getBucketRegion(bucket)).thenReturn(region);
        when(s3.openObject(bucket, name, null)).thenAnswer(call -> new S3Service.ObjectRead(new S3Object(), body.get()));
    }

    @Test
    void completeJpegAndPngReportTheirDimensions() {
        assertArrayEquals(new int[] {100, 100}, RekognitionImageValidator.dimensions(TestImages.jpeg(100, 100)));
        assertArrayEquals(new int[] {100, 79}, RekognitionImageValidator.dimensions(TestImages.png(100, 79)));
        assertArrayEquals(new int[] {10001, 100}, RekognitionImageValidator.dimensions(TestImages.jpeg(10001, 100)));
    }

    @Test
    void anythingButACompleteJpegOrPngHasNoDimensions() {
        byte[] jpeg = TestImages.jpeg(100, 100);
        byte[] png = TestImages.png(100, 100);
        assertNull(RekognitionImageValidator.dimensions(TestImages.WEBP));
        assertNull(RekognitionImageValidator.dimensions(TestImages.gif(100, 100)));
        assertNull(RekognitionImageValidator.dimensions(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}));
        assertNull(RekognitionImageValidator.dimensions(new byte[0]));
        assertNull(RekognitionImageValidator.dimensions(TestImages.truncated(jpeg, TestImages.jpegScanStart(jpeg) - 4)),
                "a JPEG cut inside its start-of-scan header");
        assertNull(RekognitionImageValidator.dimensions(TestImages.truncated(png, png.length - 12)), "a PNG without IEND");
    }

    /**
     * Rekognition accepted a JPEG cut off anywhere after its start-of-scan header, even one cut right
     * at the start of the scan or missing only its end-of-image marker.
     */
    @Test
    void aJpegWhoseHeadersAreCompleteIsAcceptedHoweverMuchOfTheScanIsMissing() {
        byte[] jpeg = TestImages.jpeg(100, 100);
        int scanStart = TestImages.jpegScanStart(jpeg);
        for (int length : new int[] {scanStart, scanStart + (jpeg.length - scanStart) / 2, jpeg.length - 2}) {
            assertArrayEquals(new int[] {100, 100},
                    RekognitionImageValidator.dimensions(TestImages.truncated(jpeg, length)), "cut at " + length);
        }
    }

    /** Rekognition refused WebP, GIF, other bytes and a truncated JPEG with this one message. */
    @Test
    void anImageThatIsNotACompleteJpegOrPngIsAnInvalidFormat() {
        byte[] jpeg = TestImages.jpeg(100, 100);
        for (byte[] data : new byte[][] {TestImages.WEBP, TestImages.gif(100, 100), {1, 2, 3},
                TestImages.truncated(jpeg, 300)}) {
            AwsException e = refused("DetectModerationLabels", bytesImage(data), "Image");
            assertEquals("InvalidImageFormatException", e.getErrorCode());
            assertEquals(INVALID_FORMAT, e.getMessage());
        }
    }

    /** The documented 80 pixel minimum is not enforced: 79 px and even 10 x 10 were accepted. */
    @Test
    void smallImagesAreAccepted() {
        accepted("DetectModerationLabels", bytesImage(TestImages.jpeg(10, 10)));
        accepted("DetectFaces", bytesImage(TestImages.png(100, 79)));
    }

    /** 5,062,030 bytes were accepted and 6,421,900 refused: the limit is 5 MiB, 5,242,880 bytes. */
    @Test
    void bytesOverFiveMebibytesAreRefusedNamingTheMember() {
        byte[] over = new byte[RekognitionImageValidator.MAX_BYTES + 1];

        AwsException image = refused("DetectLabels", bytesImage(over), "Image");
        assertEquals("ValidationException", image.getErrorCode());
        assertEquals("1 validation error detected: Value at 'image.bytes' failed to satisfy constraint: "
                + "Member must have length less than or equal to 5242880", image.getMessage());

        AwsException source = refused("CompareFaces", bytesImage(over), "SourceImage");
        assertEquals("1 validation error detected: Value at 'sourceImage.bytes' failed to satisfy constraint: "
                + "Member must have length less than or equal to 5242880", source.getMessage());
        AwsException target = refused("CompareFaces", bytesImage(over), "TargetImage");
        assertEquals("1 validation error detected: Value at 'targetImage.bytes' failed to satisfy constraint: "
                + "Member must have length less than or equal to 5242880", target.getMessage());
    }

    @Test
    void bytesAtTheLimitPassTheSizeCheck() {
        AwsException e = refused("DetectLabels", bytesImage(new byte[RekognitionImageValidator.MAX_BYTES]), "Image");

        assertEquals("InvalidImageFormatException", e.getErrorCode(), "past the size check, refused only as an image");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DetectModerationLabels", "DetectLabels"})
    void aSideOverTenThousandPixelsIsTooLargeForModerationAndLabels(String operation) {
        AwsException wide = refused(operation, bytesImage(TestImages.jpeg(10001, 100)), "Image");
        assertEquals("ImageTooLargeException", wide.getErrorCode());
        assertEquals("Input image dimensions 10001 x 100 exceed maximum dimension size of 10000 pixels", wide.getMessage());

        AwsException tall = refused(operation, bytesImage(TestImages.png(100, 10001)), "Image");
        assertEquals("Input image dimensions 100 x 10001 exceed maximum dimension size of 10000 pixels", tall.getMessage());
    }

    /** The other operations refused the same image as an invalid format instead. */
    @ParameterizedTest
    @ValueSource(strings = {"DetectText", "DetectFaces", "CompareFaces"})
    void aSideOverTenThousandPixelsIsAnInvalidFormatElsewhere(String operation) {
        AwsException e = refused(operation, bytesImage(TestImages.jpeg(10001, 100)), "Image");

        assertEquals("InvalidImageFormatException", e.getErrorCode());
        assertEquals(INVALID_FORMAT, e.getMessage());
    }

    @Test
    void tenThousandPixelsIsAccepted() {
        accepted("DetectModerationLabels", bytesImage(TestImages.jpeg(10000, 100)));
    }

    @Test
    void anS3ObjectIsReadAndCheckedLikeBytes() {
        storeObject("images", REGION, "upload.webp", TestImages.WEBP);
        storeObject("images", REGION, "upload.jpg", TestImages.jpeg(100, 100));

        accepted("DetectModerationLabels", s3Image("images", "upload.jpg"));
        assertEquals("InvalidImageFormatException",
                refused("DetectModerationLabels", s3Image("images", "upload.webp"), "Image").getErrorCode());
    }

    /** A missing object and a missing bucket both answered this. */
    @Test
    void anS3ObjectThatCannotBeReadIsAnInvalidS3Object() {
        when(s3.openObject("images", "gone.jpg", null))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist.", 404));

        for (ObjectNode image : new ObjectNode[] {s3Image("images", "gone.jpg"),
                (ObjectNode) MAPPER.createObjectNode().set("S3Object", MAPPER.createObjectNode().put("Bucket", "images"))}) {
            AwsException e = refused("DetectModerationLabels", image, "Image");
            assertEquals("InvalidS3ObjectException", e.getErrorCode());
            assertEquals("Unable to get object metadata from S3. Check object key, region and/or access permissions.",
                    e.getMessage());
        }
    }

    /** A bucket in us-west-2 named in a us-east-2 call answered the same as a missing object. */
    @Test
    void anS3ObjectInAnotherRegionIsAnInvalidS3Object() {
        storeObject("west", "us-west-2", "upload.jpg", TestImages.jpeg(100, 100));

        AwsException e = refused("DetectModerationLabels", s3Image("west", "upload.jpg"), "Image");

        assertEquals("InvalidS3ObjectException", e.getErrorCode());
    }

    @Test
    void emptyBytesAreRefusedNamingTheMember() {
        AwsException e = refused("DetectLabels", bytesImage(new byte[0]), "Image");

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("1 validation error detected: Value at 'image.bytes' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1", e.getMessage());
    }

    /** Rekognition fails to deserialize such a request, before any constraint is checked. */
    @Test
    void bytesThatAreNotBase64AreASerializationError() {
        String encoded = Base64.getEncoder().encodeToString(TestImages.jpeg(100, 100));
        for (String bytes : new String[] {"not base64!!", encoded.substring(0, 60) + "\n" + encoded.substring(60)}) {
            ObjectNode image = MAPPER.createObjectNode().put("Bytes", bytes);

            assertEquals("SerializationException", refused("DetectLabels", image, "Image").getErrorCode());
        }
    }

    @Test
    void base64WithoutPaddingIsAccepted() {
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(TestImages.jpeg(100, 100));

        accepted("DetectLabels", MAPPER.createObjectNode().put("Bytes", encoded));
    }

    @Test
    void strayBytesBetweenJpegSegmentsAreAccepted() {
        byte[] jpeg = TestImages.jpeg(100, 100);
        int afterApp0 = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        byte[] padded = new byte[jpeg.length + 3];
        System.arraycopy(jpeg, 0, padded, 0, afterApp0);
        System.arraycopy(jpeg, afterApp0, padded, afterApp0 + 3, jpeg.length - afterApp0);

        accepted("DetectLabels", bytesImage(padded));
    }

    /** Rekognition checked both images' lengths before reading either image. */
    @Test
    void compareFacesReportsEveryOversizedImageBeforeAnyFormatError() {
        byte[] over = new byte[RekognitionImageValidator.MAX_BYTES + 1];

        AwsException both = refusedCompareFaces(bytesImage(over), bytesImage(over));
        assertEquals("ValidationException", both.getErrorCode());
        assertEquals("2 validation errors detected: "
                + "Value at 'sourceImage.bytes' failed to satisfy constraint: Member must have length less than or equal to 5242880; "
                + "Value at 'targetImage.bytes' failed to satisfy constraint: Member must have length less than or equal to 5242880",
                both.getMessage());

        AwsException target = refusedCompareFaces(bytesImage(TestImages.WEBP), bytesImage(over));
        assertEquals("1 validation error detected: Value at 'targetImage.bytes' failed to satisfy constraint: "
                + "Member must have length less than or equal to 5242880", target.getMessage());
    }

    @Test
    void compareFacesRefusesAnInvalidTargetImage() {
        AwsException e = refusedCompareFaces(bytesImage(TestImages.jpeg(100, 100)), bytesImage(TestImages.WEBP));

        assertEquals("InvalidImageFormatException", e.getErrorCode());
    }

    /** A bucket created without a location constraint is in the default region, not every region. */
    @Test
    void anS3ObjectInABucketWithoutARegionIsReadOnlyFromTheDefaultRegion() {
        storeObject("unconstrained", null, "upload.jpg", TestImages.jpeg(100, 100));

        accepted("DetectModerationLabels", s3Image("unconstrained", "upload.jpg"));
        assertEquals("InvalidS3ObjectException",
                refusedIn("us-west-2", "DetectModerationLabels", s3Image("unconstrained", "upload.jpg"), "Image").getErrorCode());
    }

    /** Rekognition refused a frame header with no components and a scan header with no fields. */
    @Test
    void aJpegWithIncompleteFrameOrScanHeadersIsAnInvalidFormat() {
        byte[] bare = HexFormat.of().parseHex("FFD8FFC00007080064006400FFDA0002");
        assertNull(RekognitionImageValidator.dimensions(bare));

        byte[] jpeg = TestImages.jpeg(100, 100);
        int scanStart = TestImages.jpegScanStart(jpeg);
        byte[] shortScanHeader = jpeg.clone();
        int componentCount = TestImages.jpegScanHeader(jpeg) + 2;
        shortScanHeader[componentCount] = (byte) (shortScanHeader[componentCount] + 1);
        assertNull(RekognitionImageValidator.dimensions(TestImages.truncated(shortScanHeader, scanStart)),
                "a scan header whose length does not match its component count");

        byte[] shortFrameHeader = jpeg.clone();
        int frameComponents = TestImages.jpegSegment(jpeg, 0xC0) + 7;
        shortFrameHeader[frameComponents] = (byte) (shortFrameHeader[frameComponents] + 1);
        assertNull(RekognitionImageValidator.dimensions(shortFrameHeader),
                "a frame header whose length does not match its component count");
    }

    /** Rekognition refused a PNG whose IHDR is followed directly by IEND. */
    @Test
    void aPngWithoutImageDataIsAnInvalidFormat() {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] header = pngChunk("IHDR", ByteBuffer.allocate(13).putInt(100).putInt(100).put((byte) 8).put((byte) 2).array());
        byte[] end = pngChunk("IEND", new byte[0]);
        byte[] png = ByteBuffer.allocate(signature.length + header.length + end.length).put(signature).put(header).put(end).array();

        assertNull(RekognitionImageValidator.dimensions(png));
    }

    /** Base64 too long to be accepted is counted, not decoded, and still refused as invalid first. */
    @Test
    void base64LongerThanTheLimitIsCountedAndStillCheckedForValidity() {
        byte[] over = new byte[RekognitionImageValidator.MAX_BYTES + 3];
        String encoded = Base64.getEncoder().encodeToString(over);
        assertEquals(RekognitionImageValidator.MAX_ENCODED_LENGTH + 4, encoded.length(), "past the decoded path");

        AwsException tooLong = refused("DetectLabels", MAPPER.createObjectNode().put("Bytes", encoded), "Image");
        assertEquals("ValidationException", tooLong.getErrorCode());
        assertEquals("1 validation error detected: Value at 'image.bytes' failed to satisfy constraint: "
                + "Member must have length less than or equal to 5242880", tooLong.getMessage());

        for (String invalid : new String[] {encoded.substring(0, 100) + "!" + encoded.substring(101),
                encoded.substring(0, 100) + "\u0141" + encoded.substring(101)}) {
            assertEquals("SerializationException",
                    refused("DetectLabels", MAPPER.createObjectNode().put("Bytes", invalid), "Image").getErrorCode());
        }
    }

    /**
     * An S3 object is read only as far as its format needs, so an object of any size is checked
     * without loading it: these are 3 GB, and reading any of their image data fails the test.
     */
    @Test
    void anS3ObjectIsReadOnlyAsFarAsItsFormatNeeds() {
        long imageData = 3_000_000_000L;
        byte[] jpeg = TestImages.jpeg(100, 100);
        byte[] jpegHeaders = TestImages.truncated(jpeg, TestImages.jpegScanStart(jpeg));
        storeObject("images", REGION, "huge.jpg", () -> new UnreadableGapStream(jpegHeaders, imageData, new byte[] {(byte) 0xFF, (byte) 0xD9}));

        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] header = pngChunk("IHDR", ByteBuffer.allocate(13).putInt(100).putInt(100).put((byte) 8).put((byte) 2).array());
        byte[] dataChunkStart = ByteBuffer.allocate(8).putInt((int) imageData).put("IDAT".getBytes(StandardCharsets.US_ASCII)).array();
        byte[] pngHeaders = ByteBuffer.allocate(signature.length + header.length + 8).put(signature).put(header).put(dataChunkStart).array();
        storeObject("images", REGION, "huge.png", () -> new UnreadableGapStream(pngHeaders, imageData + 4, pngChunk("IEND", new byte[0])));

        accepted("DetectModerationLabels", s3Image("images", "huge.jpg"));
        accepted("DetectModerationLabels", s3Image("images", "huge.png"));
    }

    /** Some bytes, then a gap that may be skipped but not read, then more bytes. */
    private static final class UnreadableGapStream extends InputStream {

        private final byte[] head;
        private final long gapEnd;
        private final byte[] tail;
        private long position;

        UnreadableGapStream(byte[] head, long gap, byte[] tail) {
            this.head = head;
            this.gapEnd = head.length + gap;
            this.tail = tail;
        }

        @Override
        public int read() {
            if (position < head.length) {
                return head[(int) position++] & 0xFF;
            }
            if (position < gapEnd) {
                throw new AssertionError("read image data at offset " + position);
            }
            if (position < gapEnd + tail.length) {
                return tail[(int) (position++ - gapEnd)] & 0xFF;
            }
            return -1;
        }

        @Override
        public long skip(long n) {
            long skipped = Math.max(0, Math.min(n, gapEnd + tail.length - position));
            position += skipped;
            return skipped;
        }
    }
}
