package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.model.ChecksumAlgorithm;
import io.github.hectorvent.floci.services.s3.model.ChecksumType;
import io.github.hectorvent.floci.services.s3.model.Part;
import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Composite vectors were captured from real Amazon S3 (eu-west-1, September 2026): the part checksums are
 * what UploadPart returned and the composite values are what CompleteMultipartUpload and HeadObject reported.
 */
class S3ChecksumTest {

    @Test
    void compositeSha256MatchesAws() {
        assertEquals("rVWtNbadtsaKXJAcgWPWjifHzJaXde/iV4jaJAOdivc=-3", ChecksumAlgorithm.SHA256.composite(List.of(
                "lcKa6Boe7a167tUCN5C4OaFygGSYD8MBnBARTrdL+1w=",
                "gHjhbUT4oo2tSCtSZJdlKi+jeNXJT+VImqdPXsXvkOc=",
                "4l9PQ3zOPZ0oGFvknFX+J4zZwE4WMzO+l3r8XrbWOeM=")));
    }

    @Test
    void compositeOfSinglePartStillCarriesPartCount() {
        assertEquals("D0xEU2q/FgypQljU/eaDWTSRcnDG3KQGOtevJWcmMRY=-1",
                ChecksumAlgorithm.SHA256.composite(List.of("n7gWa0Gp88JMie9iljKcv731WbPhWtFVibhxsVB8FAw=")));
    }

    @Test
    void compositeSha1MatchesAws() {
        assertEquals("CBB1y8xhBCot1HoCMQCx18NR2tU=-2", ChecksumAlgorithm.SHA1.composite(List.of(
                "1/0x5kkt8+9IA/j+B6LiWtFTdrY=", "H2o2aM+t5OzS5CuqgWtQ90pWCpo=")));
    }

    @Test
    void compositeCrc32MatchesAws() {
        assertEquals("R/nORQ==-2", ChecksumAlgorithm.CRC32.composite(List.of("W0QpDQ==", "anCi7Q==")));
    }

    @Test
    void compositeCrc32cMatchesAws() {
        assertEquals("ROC8NA==-2", ChecksumAlgorithm.CRC32C.composite(List.of("wMKxiw==", "myWdrQ==")));
    }

    @Test
    void withoutPartCountStripsOnlyTheSuffix() {
        assertEquals("rVWtNbadtsaKXJAcgWPWjifHzJaXde/iV4jaJAOdivc=",
                S3Checksum.withoutPartCount("rVWtNbadtsaKXJAcgWPWjifHzJaXde/iV4jaJAOdivc=-3"));
        assertEquals("+SDIDA==", S3Checksum.withoutPartCount("+SDIDA=="));
        assertNull(S3Checksum.withoutPartCount(null));
    }

    @Test
    void computeDispatchesOnAlgorithm() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals(S3Checksum.sha256Base64(data), ChecksumAlgorithm.SHA256.compute(data));
        assertEquals(S3Checksum.sha1Base64(data), ChecksumAlgorithm.SHA1.compute(data));
        assertEquals(S3Checksum.crc32Base64(data), ChecksumAlgorithm.CRC32.compute(data));
        assertEquals(S3Checksum.crc32cBase64(data), ChecksumAlgorithm.CRC32C.compute(data));
        assertEquals(S3Checksum.crc64NvmeBase64(data), ChecksumAlgorithm.CRC64NVME.compute(data));
    }

    @Test
    void crc64NvmeMatchesTheCatalogueCheckValue() {
        assertEquals("rosUhgp5mIg=", S3Checksum.crc64NvmeBase64("123456789".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void crc64NvmeMatchesABitwiseReferenceAtEveryLength() {
        Random random = new Random(64);
        List<Integer> lengths = new ArrayList<>();
        for (int length = 0; length <= 64; length++) {
            lengths.add(length);
        }
        lengths.addAll(List.of(1023, 1024, 1025, 65537, 1048579));
        for (int length : lengths) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            assertEquals(bitwiseCrc64Nvme(data), S3Checksum.crc64NvmeBase64(data), "length " + length);
        }
    }

    private static String bitwiseCrc64Nvme(byte[] data) {
        long crc = ~0L;
        for (byte b : data) {
            crc ^= b & 0xFFL;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x9a6c9329ac4bc9b5L : crc >>> 1;
            }
        }
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(Long.BYTES).putLong(~crc).array());
    }

    @Test
    void objectWithoutDeclaredAlgorithmGetsCrc64Nvme() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        S3Checksum checksum = S3Checksum.fullObject(null, data);
        assertEquals(ChecksumAlgorithm.CRC64NVME, checksum.algorithm());
        assertEquals(S3Checksum.crc64NvmeBase64(data), checksum.getChecksumCRC64NVME());
        assertEquals(ChecksumType.FULL_OBJECT, checksum.getChecksumType());
    }

    @Test
    void fullObjectCombinedFromPartsMatchesTheChecksumOfTheWholeObject() {
        Random random = new Random(4145);
        List<int[]> splits = List.of(
                new int[]{0},
                new int[]{1},
                new int[]{5 * 1024 * 1024, 17},
                new int[]{0, 9, 0, 64, 1},
                new int[]{1023, 1024, 1025, 65537, 3});
        for (ChecksumAlgorithm algorithm : List.of(ChecksumAlgorithm.CRC32, ChecksumAlgorithm.CRC32C,
                ChecksumAlgorithm.CRC64NVME)) {
            for (int[] sizes : splits) {
                ByteArrayOutputStream whole = new ByteArrayOutputStream();
                List<Part> parts = new ArrayList<>();
                for (int i = 0; i < sizes.length; i++) {
                    byte[] data = new byte[sizes[i]];
                    random.nextBytes(data);
                    whole.writeBytes(data);
                    parts.add(part(i + 1, algorithm, data));
                }

                S3Checksum combined = S3Checksum.fullObject(algorithm, parts);

                assertEquals(algorithm.compute(whole.toByteArray()), combined.valueFor(algorithm),
                        algorithm + " " + Arrays.toString(sizes));
                assertEquals(ChecksumType.FULL_OBJECT, combined.getChecksumType());
            }
        }
    }

    @Test
    void fullObjectCombinesPartsLongerThanAnArrayCanHold() {
        long zeroRunLength = (1L << 31) + 13;
        byte[] head = "head".getBytes(StandardCharsets.UTF_8);
        byte[] tail = "tail".getBytes(StandardCharsets.UTF_8);
        CRC32C zeroRun = new CRC32C();
        CRC32C whole = new CRC32C();
        whole.update(head);
        byte[] zeros = new byte[1 << 20];
        for (long remaining = zeroRunLength; remaining > 0; remaining -= zeros.length) {
            int chunk = (int) Math.min(zeros.length, remaining);
            zeroRun.update(zeros, 0, chunk);
            whole.update(zeros, 0, chunk);
        }
        whole.update(tail);
        Part middle = new Part(2, "\"etag\"", zeroRunLength);
        middle.getChecksum().setChecksumCRC32C(crc32Base64(zeroRun.getValue()));

        S3Checksum combined = S3Checksum.fullObject(ChecksumAlgorithm.CRC32C, List.of(
                part(1, ChecksumAlgorithm.CRC32C, head), middle, part(3, ChecksumAlgorithm.CRC32C, tail)));

        assertEquals(crc32Base64(whole.getValue()), combined.getChecksumCRC32C());
    }

    @Test
    void fullObjectFromPartsWithoutDeclaredAlgorithmGetsCrc64Nvme() {
        byte[] first = "part1".getBytes(StandardCharsets.UTF_8);
        byte[] second = "part2".getBytes(StandardCharsets.UTF_8);

        S3Checksum combined = S3Checksum.fullObject(null, List.of(part(1, null, first), part(2, null, second)));

        assertEquals(S3Checksum.crc64NvmeBase64("part1part2".getBytes(StandardCharsets.UTF_8)),
                combined.getChecksumCRC64NVME());
    }

    @Test
    void fullObjectFromPartsRejectsShaAlgorithms() {
        List<Part> parts = List.of(part(1, ChecksumAlgorithm.SHA256, "data".getBytes(StandardCharsets.UTF_8)));

        assertThrows(IllegalArgumentException.class, () -> S3Checksum.fullObject(ChecksumAlgorithm.SHA256, parts));
    }

    private static Part part(int partNumber, ChecksumAlgorithm algorithm, byte[] data) {
        Part part = new Part(partNumber, "\"etag\"", data.length);
        part.setChecksum(S3Checksum.of(algorithm, data));
        return part;
    }

    private static String crc32Base64(long value) {
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(Integer.BYTES).putInt((int) value).array());
    }

    @Test
    void calculatorFedInPiecesMatchesTheOneShotChecksumForEveryAlgorithm() {
        Random random = new Random(5064);
        byte[] data = new byte[300_007];
        random.nextBytes(data);
        int[] pieces = {1, 7, 8, 9, 1023, 65_537, 3, 0, 233_419};
        for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
            S3Checksum.Calculator calculator = S3Checksum.calculator(algorithm);
            int offset = 0;
            for (int piece : pieces) {
                calculator.update(data, offset, piece);
                offset += piece;
            }
            assertEquals(data.length, offset, "test setup: the pieces cover the data");

            S3Checksum fullObject = calculator.fullObject();

            assertEquals(algorithm.compute(data), fullObject.valueFor(algorithm), algorithm.name());
            assertEquals(ChecksumType.FULL_OBJECT, fullObject.getChecksumType());
        }
    }

    @Test
    void calculatorWithoutADeclaredAlgorithmIsCrc64Nvme() {
        assertEquals(ChecksumAlgorithm.CRC64NVME, S3Checksum.calculator(null).algorithm());
    }

    @Test
    void objectAttributesViewDropsTheSuffixOfCompositeValuesOnly() {
        S3Checksum composite = S3Checksum.composite(ChecksumAlgorithm.CRC32, List.of("W0QpDQ==", "anCi7Q=="));
        assertEquals("R/nORQ==-2", composite.getChecksumCRC32());
        assertEquals("R/nORQ==", composite.forObjectAttributes().getChecksumCRC32());
        assertEquals("R/nORQ==-2", composite.getChecksumCRC32(), "the stored checksum is left untouched");

        S3Checksum fullObject = S3Checksum.fullObject(ChecksumAlgorithm.CRC32, new byte[0]);
        assertEquals(fullObject.getChecksumCRC32(), fullObject.forObjectAttributes().getChecksumCRC32());
    }

    @Test
    void valueAccessorsFollowTheAlgorithm() {
        S3Checksum checksum = new S3Checksum();
        assertNull(checksum.algorithm());
        checksum.setValueFor(ChecksumAlgorithm.CRC32C, "wMKxiw==");
        assertEquals(ChecksumAlgorithm.CRC32C, checksum.algorithm());
        assertEquals("wMKxiw==", checksum.getChecksumCRC32C());
        assertEquals("wMKxiw==", checksum.valueFor(ChecksumAlgorithm.CRC32C));
        assertNull(checksum.valueFor(ChecksumAlgorithm.SHA256));
    }

    @Test
    void algorithmHeaderParsingIsCaseInsensitiveAndRejectsUnknownValues() {
        assertEquals(ChecksumAlgorithm.SHA256, ChecksumAlgorithm.fromWireValue("sha256"));
        assertEquals(ChecksumAlgorithm.CRC64NVME, ChecksumAlgorithm.fromWireValue(" CRC64NVME "));
        assertNull(ChecksumAlgorithm.fromWireValue(null));
        assertNull(ChecksumAlgorithm.fromWireValue(" "));

        AwsException knownButUnsupported = assertThrows(AwsException.class, () -> ChecksumAlgorithm.fromWireValue("SHA512"));
        assertEquals("InvalidRequest", knownButUnsupported.getErrorCode());
        AwsException unknown = assertThrows(AwsException.class, () -> ChecksumAlgorithm.fromWireValue("MURMUR3"));
        assertEquals("InvalidArgument", unknown.getErrorCode());
    }

    @Test
    void multipartTypeFollowsTheAwsAlgorithmTable() {
        assertEquals(ChecksumType.COMPOSITE, ChecksumAlgorithm.SHA256.multipartType(null));
        assertEquals(ChecksumType.COMPOSITE, ChecksumAlgorithm.SHA1.multipartType(null));
        assertEquals(ChecksumType.COMPOSITE, ChecksumAlgorithm.CRC32.multipartType(null));
        assertEquals(ChecksumType.COMPOSITE, ChecksumAlgorithm.CRC32C.multipartType(null));
        assertEquals(ChecksumType.FULL_OBJECT, ChecksumAlgorithm.CRC64NVME.multipartType(null));

        assertEquals(ChecksumType.FULL_OBJECT, ChecksumAlgorithm.CRC32.multipartType(ChecksumType.FULL_OBJECT));
        assertEquals(ChecksumType.COMPOSITE, ChecksumAlgorithm.CRC32C.multipartType(ChecksumType.COMPOSITE));

        AwsException shaFullObject = assertThrows(AwsException.class,
                () -> ChecksumAlgorithm.SHA256.multipartType(ChecksumType.FULL_OBJECT));
        assertEquals("The FULL_OBJECT checksum type cannot be used with the sha256 checksum algorithm.",
                shaFullObject.getMessage());
        AwsException crc64Composite = assertThrows(AwsException.class,
                () -> ChecksumAlgorithm.CRC64NVME.multipartType(ChecksumType.COMPOSITE));
        assertEquals("The COMPOSITE checksum type cannot be used with the crc64nvme checksum algorithm.",
                crc64Composite.getMessage());
    }

    @Test
    void typeHeaderParsingIsCaseInsensitiveAndRejectsUnknownValues() {
        assertEquals(ChecksumType.FULL_OBJECT, ChecksumAlgorithm.CRC32.multipartType(ChecksumType.fromWireValue("full_object")));
        assertEquals(ChecksumType.COMPOSITE, ChecksumType.fromWireValue("COMPOSITE"));
        assertNull(ChecksumType.fromWireValue(null));
        AwsException unknown = assertThrows(AwsException.class, () -> ChecksumType.fromWireValue("PARTIAL"));
        assertEquals("InvalidRequest", unknown.getErrorCode());
        assertEquals("Value for x-amz-checksum-type header is invalid.", unknown.getMessage());
    }
}
