package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.model.ChecksumAlgorithm;
import io.github.hectorvent.floci.services.s3.model.ChecksumType;
import io.github.hectorvent.floci.services.s3.model.S3Checksum;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
    void objectWithoutDeclaredAlgorithmGetsCrc64Nvme() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        S3Checksum checksum = S3Checksum.fullObject(null, data);
        assertEquals(ChecksumAlgorithm.CRC64NVME, checksum.algorithm());
        assertEquals(S3Checksum.crc64NvmeBase64(data), checksum.getChecksumCRC64NVME());
        assertEquals(ChecksumType.FULL_OBJECT, checksum.getChecksumType());
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
