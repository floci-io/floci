package io.github.hectorvent.floci.services.s3.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class S3ObjectCopyTest {

    @Test
    void copyConstructorCopiesEveryField() {
        S3Object source = new S3Object();
        source.setBucketName("bucket");
        source.setKey("key");
        source.setData(new byte[] {1, 2, 3});
        Map<String, String> metadata = new HashMap<>();
        metadata.put("a", "b");
        source.setMetadata(metadata);
        source.setContentType("text/plain");
        source.setContentEncoding("gzip");
        source.setContentDisposition("attachment");
        source.setCacheControl("no-cache");
        source.setServerSideEncryption("aws:kms");
        source.setSseKmsKeyId("key-id");
        source.setSseCustomerAlgorithm("AES256");
        source.setSseCustomerKeyMd5("md5");
        source.setSize(3);
        source.setLastModified(Instant.parse("2024-01-02T03:04:05Z"));
        source.setETag("\"etag\"");
        source.setStorageClass("GLACIER");
        S3Checksum checksum = new S3Checksum();
        checksum.setChecksumCRC32("crc");
        checksum.setChecksumType(ChecksumType.COMPOSITE);
        source.setChecksum(checksum);
        Part part = new Part(1, "\"p\"", 3);
        part.setChecksum(new S3Checksum());
        List<Part> parts = new ArrayList<>();
        parts.add(part);
        source.setParts(parts);
        source.setVersionId("v1");
        source.setDeleteMarker(true);
        source.setLatest(false);
        Map<String, String> tags = new HashMap<>();
        tags.put("t", "u");
        source.setTags(tags);
        source.setObjectLockMode("GOVERNANCE");
        source.setRetainUntilDate(Instant.parse("2030-01-01T00:00:00Z"));
        source.setLegalHoldStatus("ON");
        source.setAcl("private");
        source.setOwnerAccountId("111111111111");
        source.setDataGeneration("7");
        source.setEventSequencer("00000000000000ABCD");

        S3Object copy = new S3Object(source);

        assertEquals(source.getBucketName(), copy.getBucketName());
        assertEquals(source.getKey(), copy.getKey());
        assertArrayEquals(source.getData(), copy.getData());
        assertNotSame(source.getData(), copy.getData());
        assertEquals(source.getMetadata(), copy.getMetadata());
        assertNotSame(source.getMetadata(), copy.getMetadata());
        assertEquals(source.getContentType(), copy.getContentType());
        assertEquals(source.getContentEncoding(), copy.getContentEncoding());
        assertEquals(source.getContentDisposition(), copy.getContentDisposition());
        assertEquals(source.getCacheControl(), copy.getCacheControl());
        assertEquals(source.getServerSideEncryption(), copy.getServerSideEncryption());
        assertEquals(source.getSseKmsKeyId(), copy.getSseKmsKeyId());
        assertEquals(source.getSseCustomerAlgorithm(), copy.getSseCustomerAlgorithm());
        assertEquals(source.getSseCustomerKeyMd5(), copy.getSseCustomerKeyMd5());
        assertEquals(source.getSize(), copy.getSize());
        assertEquals(source.getLastModified(), copy.getLastModified());
        assertEquals(source.getETag(), copy.getETag());
        assertEquals(source.getStorageClass(), copy.getStorageClass());
        assertNotSame(source.getChecksum(), copy.getChecksum());
        assertEquals("crc", copy.getChecksum().getChecksumCRC32());
        assertEquals(ChecksumType.COMPOSITE, copy.getChecksum().getChecksumType());
        assertNotSame(source.getParts(), copy.getParts());
        assertEquals(1, copy.getParts().size());
        assertNotSame(source.getParts().get(0), copy.getParts().get(0));
        assertEquals(1, copy.getParts().get(0).getPartNumber());
        assertEquals("\"p\"", copy.getParts().get(0).getETag());
        assertEquals(3, copy.getParts().get(0).getSize());
        assertEquals(source.getParts().get(0).getLastModified(), copy.getParts().get(0).getLastModified());
        assertNotSame(source.getParts().get(0).getChecksum(), copy.getParts().get(0).getChecksum());
        assertEquals(source.getVersionId(), copy.getVersionId());
        assertEquals(source.isDeleteMarker(), copy.isDeleteMarker());
        assertEquals(source.isLatest(), copy.isLatest());
        assertEquals(source.getTags(), copy.getTags());
        assertNotSame(source.getTags(), copy.getTags());
        assertEquals(source.getObjectLockMode(), copy.getObjectLockMode());
        assertEquals(source.getRetainUntilDate(), copy.getRetainUntilDate());
        assertEquals(source.getLegalHoldStatus(), copy.getLegalHoldStatus());
        assertEquals(source.getAcl(), copy.getAcl());
        assertEquals(source.getOwnerAccountId(), copy.getOwnerAccountId());
        assertEquals(source.getDataGeneration(), copy.getDataGeneration());
        assertEquals(source.getEventSequencer(), copy.getEventSequencer());
    }
}
