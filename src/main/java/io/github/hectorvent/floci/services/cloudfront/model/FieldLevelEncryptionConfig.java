package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class FieldLevelEncryptionConfig {

    private String id;
    private Instant lastModifiedTime;
    private String etag;
    private String callerReference;
    private String comment;

    public FieldLevelEncryptionConfig() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getCallerReference() { return callerReference; }
    public void setCallerReference(String callerReference) { this.callerReference = callerReference; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
