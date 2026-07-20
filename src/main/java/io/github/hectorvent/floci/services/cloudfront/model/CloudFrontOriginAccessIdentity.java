package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class CloudFrontOriginAccessIdentity {

    private String id;
    private String s3CanonicalUserId;
    private String callerReference;
    private String comment;
    private String etag;

    public CloudFrontOriginAccessIdentity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getS3CanonicalUserId() { return s3CanonicalUserId; }
    public void setS3CanonicalUserId(String s3CanonicalUserId) { this.s3CanonicalUserId = s3CanonicalUserId; }

    public String getCallerReference() { return callerReference; }
    public void setCallerReference(String callerReference) { this.callerReference = callerReference; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
}
