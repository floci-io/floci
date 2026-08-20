package io.github.hectorvent.floci.services.s3.model;

import java.util.Map;

public class CopyObjectOptions {
    private String metadataDirective;
    private Map<String, String> replacementMetadata;
    private String taggingDirective;
    private Map<String, String> replacementTagging;
    private String storageClass;
    private String contentType;
    private String contentEncoding;
    private String contentDisposition;
    private String cacheControl;
    private String serverSideEncryption;
    private String sseCustomerAlgorithm;
    private String sseCustomerKey;
    private String sseCustomerKeyMd5;
    private String copySourceSseCustomerAlgorithm;
    private String copySourceSseCustomerKey;
    private String copySourceSseCustomerKeyMd5;
    private String acl;
    private String grantRead;
    private String grantWrite;
    private String grantFullControl;
    private String grantReadAcp;
    private String grantWriteAcp;
    private String checksumAlgorithm;

    public String getMetadataDirective() { return metadataDirective; }
    public CopyObjectOptions withMetadataDirective(String metadataDirective) { this.metadataDirective = metadataDirective; return this; }

    public Map<String, String> getReplacementMetadata() { return replacementMetadata; }
    public CopyObjectOptions withReplacementMetadata(Map<String, String> replacementMetadata) { this.replacementMetadata = replacementMetadata; return this; }

    public String getTaggingDirective() { return taggingDirective; }
    public CopyObjectOptions withTaggingDirective(String taggingDirective) { this.taggingDirective = taggingDirective; return this; }

    public Map<String, String> getReplacementTagging() { return replacementTagging; }
    public CopyObjectOptions withReplacementTagging(Map<String, String> replacementTagging) { this.replacementTagging = replacementTagging; return this; }

    public String getStorageClass() { return storageClass; }
    public CopyObjectOptions withStorageClass(String storageClass) { this.storageClass = storageClass; return this; }

    public String getContentType() { return contentType; }
    public CopyObjectOptions withContentType(String contentType) { this.contentType = contentType; return this; }

    public String getContentEncoding() { return contentEncoding; }
    public CopyObjectOptions withContentEncoding(String contentEncoding) { this.contentEncoding = contentEncoding; return this; }

    public String getContentDisposition() { return contentDisposition; }
    public CopyObjectOptions withContentDisposition(String contentDisposition) { this.contentDisposition = contentDisposition; return this; }

    public String getCacheControl() { return cacheControl; }
    public CopyObjectOptions withCacheControl(String cacheControl) { this.cacheControl = cacheControl; return this; }

    public String getServerSideEncryption() { return serverSideEncryption; }
    public CopyObjectOptions withServerSideEncryption(String serverSideEncryption) { this.serverSideEncryption = serverSideEncryption; return this; }

    public String getSseCustomerAlgorithm() { return sseCustomerAlgorithm; }
    public CopyObjectOptions withSseCustomerAlgorithm(String sseCustomerAlgorithm) { this.sseCustomerAlgorithm = sseCustomerAlgorithm; return this; }

    public String getSseCustomerKey() { return sseCustomerKey; }
    public CopyObjectOptions withSseCustomerKey(String sseCustomerKey) { this.sseCustomerKey = sseCustomerKey; return this; }

    public String getSseCustomerKeyMd5() { return sseCustomerKeyMd5; }
    public CopyObjectOptions withSseCustomerKeyMd5(String sseCustomerKeyMd5) { this.sseCustomerKeyMd5 = sseCustomerKeyMd5; return this; }

    public String getCopySourceSseCustomerAlgorithm() { return copySourceSseCustomerAlgorithm; }
    public CopyObjectOptions withCopySourceSseCustomerAlgorithm(String copySourceSseCustomerAlgorithm) { this.copySourceSseCustomerAlgorithm = copySourceSseCustomerAlgorithm; return this; }

    public String getCopySourceSseCustomerKey() { return copySourceSseCustomerKey; }
    public CopyObjectOptions withCopySourceSseCustomerKey(String copySourceSseCustomerKey) { this.copySourceSseCustomerKey = copySourceSseCustomerKey; return this; }

    public String getCopySourceSseCustomerKeyMd5() { return copySourceSseCustomerKeyMd5; }
    public CopyObjectOptions withCopySourceSseCustomerKeyMd5(String copySourceSseCustomerKeyMd5) { this.copySourceSseCustomerKeyMd5 = copySourceSseCustomerKeyMd5; return this; }

    public String getAcl() { return acl; }
    public CopyObjectOptions withAcl(String acl) { this.acl = acl; return this; }

    public String getGrantRead() { return grantRead; }
    public CopyObjectOptions withGrantRead(String grantRead) { this.grantRead = grantRead; return this; }

    public String getGrantWrite() { return grantWrite; }
    public CopyObjectOptions withGrantWrite(String grantWrite) { this.grantWrite = grantWrite; return this; }

    public String getGrantFullControl() { return grantFullControl; }
    public CopyObjectOptions withGrantFullControl(String grantFullControl) { this.grantFullControl = grantFullControl; return this; }

    public String getGrantReadAcp() { return grantReadAcp; }
    public CopyObjectOptions withGrantReadAcp(String grantReadAcp) { this.grantReadAcp = grantReadAcp; return this; }

    public String getGrantWriteAcp() { return grantWriteAcp; }
    public CopyObjectOptions withGrantWriteAcp(String grantWriteAcp) { this.grantWriteAcp = grantWriteAcp; return this; }

    public String getChecksumAlgorithm() { return checksumAlgorithm; }
    public CopyObjectOptions withChecksumAlgorithm(String checksumAlgorithm) { this.checksumAlgorithm = checksumAlgorithm; return this; }
}
