package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3Object {

    private String bucketName;
    private String key;
    @JsonIgnore
    private byte[] data;
    private Map<String, String> metadata;
    private String contentType;
    private String contentEncoding;
    private long size;
    private Instant lastModified;
    private String eTag;
    private String storageClass;
    private S3Checksum checksum;
    private List<Part> parts;
    private String versionId;
    private boolean deleteMarker;
    private boolean isLatest = true;

    private Map<String, String> tags;
    private String objectLockMode;       // "GOVERNANCE" | "COMPLIANCE" | null
    private Instant retainUntilDate;     // null if no retention
    private String legalHoldStatus;      // "ON" | "OFF" | null
    private String acl;

    public S3Object() {
        this.metadata = new HashMap<>();
        this.storageClass = "STANDARD";
        this.checksum = new S3Checksum();
        this.parts = new ArrayList<>();
        this.tags = new HashMap<>();
    }

    public S3Object(String bucketName, String key, byte[] data, String contentType) {
        this.bucketName = bucketName;
        this.key = key;
        this.data = data;
        this.contentType = contentType != null ? contentType : "application/octet-stream";
        this.size = data.length;
        this.lastModified = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        this.eTag = computeETag(data);
        this.metadata = new HashMap<>();
        this.storageClass = "STANDARD";
        this.checksum = new S3Checksum();
        this.checksum.setChecksumSHA256(S3Checksum.sha256Base64(data));
        this.checksum.setChecksumType("FULL_OBJECT");
        this.parts = new ArrayList<>();
        this.tags = new HashMap<>();
    }

    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getContentEncoding() { return contentEncoding; }
    public void setContentEncoding(String contentEncoding) { this.contentEncoding = contentEncoding; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }

    public String getETag() { return eTag; }
    public void setETag(String eTag) { this.eTag = eTag; }

    public String getStorageClass() { return storageClass; }
    public void setStorageClass(String storageClass) { this.storageClass = storageClass; }

    public S3Checksum getChecksum() { return checksum; }
    public void setChecksum(S3Checksum checksum) { this.checksum = checksum; }

    public List<Part> getParts() { return parts; }
    public void setParts(List<Part> parts) { this.parts = parts; }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    public boolean isDeleteMarker() { return deleteMarker; }
    public void setDeleteMarker(boolean deleteMarker) { this.deleteMarker = deleteMarker; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public boolean isLatest() { return isLatest; }
    public void setLatest(boolean latest) { this.isLatest = latest; }

    public String getObjectLockMode() { return objectLockMode; }
    public void setObjectLockMode(String objectLockMode) { this.objectLockMode = objectLockMode; }

    public Instant getRetainUntilDate() { return retainUntilDate; }
    public void setRetainUntilDate(Instant retainUntilDate) { this.retainUntilDate = retainUntilDate; }

    public String getLegalHoldStatus() { return legalHoldStatus; }
    public void setLegalHoldStatus(String legalHoldStatus) { this.legalHoldStatus = legalHoldStatus; }

    public String getAcl() { return acl; }
    public void setAcl(String acl) { this.acl = acl; }

    private static String computeETag(byte[] data) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            var sb = new StringBuilder("\"");
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            sb.append("\"");
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "\"\"";
        }
    }
}
