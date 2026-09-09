package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * An S3 object annotation: a named payload (1 byte to 1 MiB of UTF-8 text) attached to a specific
 * object version. Only the metadata lives here; the payload bytes are stored outside the
 * annotation store, exactly the way {@code S3Object.data} is kept out of {@code s3-objects.json}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ObjectAnnotation {

    private String bucketName;
    private String key;
    // Parent object's versionId; null for objects in non-versioned buckets.
    private String versionId;
    private String annotationName;
    private long size;
    private String eTag;
    private Instant lastModified;
    // Wire name (e.g. "CRC64NVME") and Base64 value, kept as plain strings rather than S3Checksum
    // so the annotation algorithm set stays independent of the object checksum machinery.
    private String checksumAlgorithm;
    private String checksumValue;
    // Reserved for annotation replication; always null until that feature exists.
    private String replicationStatus;
    // Inherited from the parent object at write time; annotations cannot use SSE-C.
    private String serverSideEncryption;

    public ObjectAnnotation() {
    }

    public ObjectAnnotation(String bucketName, String key, String versionId, String annotationName,
                            long size, String eTag, Instant lastModified,
                            String checksumAlgorithm, String checksumValue) {
        this.bucketName = bucketName;
        this.key = key;
        this.versionId = versionId;
        this.annotationName = annotationName;
        this.size = size;
        this.eTag = eTag;
        this.lastModified = lastModified != null ? lastModified.truncatedTo(ChronoUnit.MILLIS) : null;
        this.checksumAlgorithm = checksumAlgorithm;
        this.checksumValue = checksumValue;
    }

    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    public String getAnnotationName() { return annotationName; }
    public void setAnnotationName(String annotationName) { this.annotationName = annotationName; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getETag() { return eTag; }
    public void setETag(String eTag) { this.eTag = eTag; }

    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }

    public String getChecksumAlgorithm() { return checksumAlgorithm; }
    public void setChecksumAlgorithm(String checksumAlgorithm) { this.checksumAlgorithm = checksumAlgorithm; }

    public String getChecksumValue() { return checksumValue; }
    public void setChecksumValue(String checksumValue) { this.checksumValue = checksumValue; }

    public String getReplicationStatus() { return replicationStatus; }
    public void setReplicationStatus(String replicationStatus) { this.replicationStatus = replicationStatus; }

    public String getServerSideEncryption() { return serverSideEncryption; }
    public void setServerSideEncryption(String serverSideEncryption) { this.serverSideEncryption = serverSideEncryption; }

    public static String checksumHeaderName(String algorithm) {
        return "x-amz-checksum-" + algorithm.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidUtf8(byte[] data) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}