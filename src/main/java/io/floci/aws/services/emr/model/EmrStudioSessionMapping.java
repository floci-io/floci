package io.floci.aws.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** A user or group of IAM Identity Center mapped to a Studio, with the session policy that refines its permissions. */
@RegisterForReflection
public class EmrStudioSessionMapping {

    private String identityId;
    private String identityName;
    private String identityType;
    private String sessionPolicyArn;
    private Instant creationTime;
    private Instant lastModifiedTime;
    // Its place among its Studio's mappings, 1 for the first, which orders ListStudioSessionMappings.
    private long sequence;

    public EmrStudioSessionMapping() {}

    public String getIdentityId() { return identityId; }
    public void setIdentityId(String identityId) { this.identityId = identityId; }
    public String getIdentityName() { return identityName; }
    public void setIdentityName(String identityName) { this.identityName = identityName; }
    public String getIdentityType() { return identityType; }
    public void setIdentityType(String identityType) { this.identityType = identityType; }
    public String getSessionPolicyArn() { return sessionPolicyArn; }
    public void setSessionPolicyArn(String sessionPolicyArn) { this.sessionPolicyArn = sessionPolicyArn; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }
    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
}
