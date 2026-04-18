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
    private String serverSideEncryption;
    private String acl;
    private Map<String, String> metadata;
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

    public String getServerSideEncryption() { return serverSideEncryption; }
    public void setServerSideEncryption(String serverSideEncryption) { this.serverSideEncryption = serverSideEncryption; }

    public String getAcl() { return acl; }
    public void setAcl(String acl) { this.acl = acl; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    public Instant getInitiated() { return initiated; }
    public void setInitiated(Instant initiated) { this.initiated = initiated; }

    public Map<Integer, Part> getParts() { return parts; }
}
