package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class StreamingDistribution {

    private String id;
    private String arn;
    private String status;
    private Instant lastModifiedTime;
    private String domainName;
    private String etag;
    private String callerReference;
    private boolean enabled;
    private String comment;
    private String s3Bucket;
    private String s3OriginAccessIdentity;
    private List<String> aliases;
    private String priceClass;

    public StreamingDistribution() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getCallerReference() { return callerReference; }
    public void setCallerReference(String callerReference) { this.callerReference = callerReference; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }

    public String getS3OriginAccessIdentity() { return s3OriginAccessIdentity; }
    public void setS3OriginAccessIdentity(String s3OriginAccessIdentity) {
        this.s3OriginAccessIdentity = s3OriginAccessIdentity;
    }

    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> aliases) { this.aliases = aliases; }

    public String getPriceClass() { return priceClass; }
    public void setPriceClass(String priceClass) { this.priceClass = priceClass; }
}
