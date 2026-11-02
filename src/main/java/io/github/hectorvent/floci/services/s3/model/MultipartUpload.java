package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
public class MultipartUpload {

    private String uploadId;
    private String bucket;
    private String key;
    private String contentType;
    private String storageClass;
    private String contentDisposition;
    private String contentEncoding;
    private String cacheControl;
    private String serverSideEncryption;
    private String sseKmsKeyId;
    private String sseCustomerAlgorithm;
    private String sseCustomerKeyMd5;
    private String acl;
    // The grants and Object Lock settings CreateMultipartUpload asks for, applied on completion.
    private String grantRead;
    private String grantWrite;
    private String grantFullControl;
    private String grantReadAcp;
    private String grantWriteAcp;
    private String objectLockMode;
    private Instant retainUntilDate;
    private String legalHoldStatus;
    private String ownerAccountId;
    private String initiatorAccountId;
    private ChecksumAlgorithm checksumAlgorithm;
    private ChecksumType checksumType;
    private Map<String, String> metadata;
    private Map<String, String> tagging;
    private Instant initiated;
    private final Map<Integer, Part> parts = new ConcurrentHashMap<>();

    public MultipartUpload() {
        this.storageClass = "STANDARD";
        this.metadata = new HashMap<>();
    }

    public MultipartUpload(String bucket, String key, String contentType) {
        this.uploadId = UUID.randomUUID().toString();
        this.bucket = bucket;
        this.key = key;
        this.contentType = contentType != null ? contentType : "application/octet-stream";
        this.storageClass = "STANDARD";
        this.metadata = new HashMap<>();
        this.initiated = Instant.now();
    }

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getStorageClass() { return storageClass; }
    public void setStorageClass(String storageClass) { this.storageClass = storageClass; }

    public String getContentDisposition() { return contentDisposition; }
    public void setContentDisposition(String contentDisposition) { this.contentDisposition = contentDisposition; }

    public String getContentEncoding() { return contentEncoding; }
    public void setContentEncoding(String contentEncoding) { this.contentEncoding = contentEncoding; }

    public String getCacheControl() { return cacheControl; }
    public void setCacheControl(String cacheControl) { this.cacheControl = cacheControl; }

    public String getServerSideEncryption() { return serverSideEncryption; }
    public void setServerSideEncryption(String serverSideEncryption) { this.serverSideEncryption = serverSideEncryption; }

    public String getSseKmsKeyId() { return sseKmsKeyId; }
    public void setSseKmsKeyId(String sseKmsKeyId) { this.sseKmsKeyId = sseKmsKeyId; }

    public String getSseCustomerAlgorithm() { return sseCustomerAlgorithm; }
    public void setSseCustomerAlgorithm(String sseCustomerAlgorithm) { this.sseCustomerAlgorithm = sseCustomerAlgorithm; }

    public String getSseCustomerKeyMd5() { return sseCustomerKeyMd5; }
    public void setSseCustomerKeyMd5(String sseCustomerKeyMd5) { this.sseCustomerKeyMd5 = sseCustomerKeyMd5; }

    public String getAcl() { return acl; }
    public void setAcl(String acl) { this.acl = acl; }

    public String getGrantRead() { return grantRead; }
    public void setGrantRead(String grantRead) { this.grantRead = grantRead; }

    public String getGrantWrite() { return grantWrite; }
    public void setGrantWrite(String grantWrite) { this.grantWrite = grantWrite; }

    public String getGrantFullControl() { return grantFullControl; }
    public void setGrantFullControl(String grantFullControl) { this.grantFullControl = grantFullControl; }

    public String getGrantReadAcp() { return grantReadAcp; }
    public void setGrantReadAcp(String grantReadAcp) { this.grantReadAcp = grantReadAcp; }

    public String getGrantWriteAcp() { return grantWriteAcp; }
    public void setGrantWriteAcp(String grantWriteAcp) { this.grantWriteAcp = grantWriteAcp; }

    public String getObjectLockMode() { return objectLockMode; }
    public void setObjectLockMode(String objectLockMode) { this.objectLockMode = objectLockMode; }

    public Instant getRetainUntilDate() { return retainUntilDate; }
    public void setRetainUntilDate(Instant retainUntilDate) { this.retainUntilDate = retainUntilDate; }

    public String getLegalHoldStatus() { return legalHoldStatus; }
    public void setLegalHoldStatus(String legalHoldStatus) { this.legalHoldStatus = legalHoldStatus; }

    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String ownerAccountId) { this.ownerAccountId = ownerAccountId; }

    public String getInitiatorAccountId() { return initiatorAccountId; }
    public void setInitiatorAccountId(String initiatorAccountId) { this.initiatorAccountId = initiatorAccountId; }

    public ChecksumAlgorithm getChecksumAlgorithm() { return checksumAlgorithm; }
    public void setChecksumAlgorithm(ChecksumAlgorithm checksumAlgorithm) { this.checksumAlgorithm = checksumAlgorithm; }

    public ChecksumType getChecksumType() { return checksumType; }
    public void setChecksumType(ChecksumType checksumType) { this.checksumType = checksumType; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    public Map<String, String> getTagging() { return tagging; }
    public void setTagging(Map<String, String> tagging) { this.tagging = tagging; }

    public Instant getInitiated() { return initiated; }
    public void setInitiated(Instant initiated) { this.initiated = initiated; }

    public Map<Integer, Part> getParts() { return parts; }
}
