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
    private String acl;

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

    public String getAcl() { return acl; }
    public CopyObjectOptions withAcl(String acl) { this.acl = acl; return this; }
}
