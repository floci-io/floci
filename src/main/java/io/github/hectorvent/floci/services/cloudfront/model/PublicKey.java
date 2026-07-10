package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class PublicKey {

    private String id;
    private Instant createdTime;
    private String etag;
    private String callerReference;
    private String name;
    private String encodedKey;
    private String comment;

    public PublicKey() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getCallerReference() { return callerReference; }
    public void setCallerReference(String callerReference) { this.callerReference = callerReference; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEncodedKey() { return encodedKey; }
    public void setEncodedKey(String encodedKey) { this.encodedKey = encodedKey; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
