package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** The resource policy attached to a Redshift Serverless snapshot. Floci stores it but does not evaluate it. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourcePolicy {

    private String resourceArn;
    private String policy;
    private Instant snapshotCreateTime;

    public ResourcePolicy() {
    }

    public ResourcePolicy(String resourceArn, String policy) {
        this.resourceArn = resourceArn;
        this.policy = policy;
    }

    /** The creation time of the snapshot the policy was put on, which tells a recreated snapshot apart. */
    public Instant getSnapshotCreateTime() {
        return snapshotCreateTime;
    }

    public void setSnapshotCreateTime(Instant snapshotCreateTime) {
        this.snapshotCreateTime = snapshotCreateTime;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }
}
