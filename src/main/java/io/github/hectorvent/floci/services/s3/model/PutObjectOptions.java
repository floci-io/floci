package io.github.hectorvent.floci.services.s3.model;

import java.time.Instant;

public class PutObjectOptions {
    private String storageClass;
    private String contentEncoding;
    private String objectLockMode;
    private Instant retainUntilDate;
    private String legalHoldStatus;
    private String contentDisposition;
    private String cacheControl;
    private String serverSideEncryption;
    private String acl;
    private String checksumAlgorithm;

    public String getStorageClass() { return storageClass; }
    public PutObjectOptions withStorageClass(String storageClass) { this.storageClass = storageClass; return this; }

    public String getContentEncoding() { return contentEncoding; }
    public PutObjectOptions withContentEncoding(String contentEncoding) { this.contentEncoding = contentEncoding; return this; }

    public String getObjectLockMode() { return objectLockMode; }
    public PutObjectOptions withObjectLockMode(String objectLockMode) { this.objectLockMode = objectLockMode; return this; }

    public Instant getRetainUntilDate() { return retainUntilDate; }
    public PutObjectOptions withRetainUntilDate(Instant retainUntilDate) { this.retainUntilDate = retainUntilDate; return this; }

    public String getLegalHoldStatus() { return legalHoldStatus; }
    public PutObjectOptions withLegalHoldStatus(String legalHoldStatus) { this.legalHoldStatus = legalHoldStatus; return this; }

    public String getContentDisposition() { return contentDisposition; }
    public PutObjectOptions withContentDisposition(String contentDisposition) { this.contentDisposition = contentDisposition; return this; }

    public String getCacheControl() { return cacheControl; }
    public PutObjectOptions withCacheControl(String cacheControl) { this.cacheControl = cacheControl; return this; }

    public String getServerSideEncryption() { return serverSideEncryption; }
    public PutObjectOptions withServerSideEncryption(String serverSideEncryption) { this.serverSideEncryption = serverSideEncryption; return this; }

    public String getAcl() { return acl; }
    public PutObjectOptions withAcl(String acl) { this.acl = acl; return this; }

    public String getChecksumAlgorithm() { return checksumAlgorithm; }
    public PutObjectOptions withChecksumAlgorithm(String checksumAlgorithm) { this.checksumAlgorithm = checksumAlgorithm; return this; }

    private S3Checksum clientChecksum;
    public S3Checksum getClientChecksum() { return clientChecksum; }
    public PutObjectOptions withClientChecksum(S3Checksum clientChecksum) { this.clientChecksum = clientChecksum; return this; }
}
