package io.floci.aws.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class GetObjectAttributesResult {

    private String eTag;
    private S3Checksum checksum;
    private GetObjectAttributesParts objectParts;
    private String storageClass;
    private Long objectSize;
    private Instant lastModified;
    private String versionId;

    public String getETag() { return eTag; }
    public void setETag(String eTag) { this.eTag = eTag; }

    public S3Checksum getChecksum() { return checksum; }
    public void setChecksum(S3Checksum checksum) { this.checksum = checksum; }

    public GetObjectAttributesParts getObjectParts() { return objectParts; }
    public void setObjectParts(GetObjectAttributesParts objectParts) { this.objectParts = objectParts; }

    public String getStorageClass() { return storageClass; }
    public void setStorageClass(String storageClass) { this.storageClass = storageClass; }

    public Long getObjectSize() { return objectSize; }
    public void setObjectSize(Long objectSize) { this.objectSize = objectSize; }

    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
}
