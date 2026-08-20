package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The account/region-level EC2 instance metadata default settings that
 * {@code ModifyInstanceMetadataDefaults}/{@code GetInstanceMetadataDefaults} manage
 * ({@code aws_ec2_instance_metadata_defaults}). One per region, never per instance: a new
 * instance that does not set its own metadata options inherits these.
 *
 * <p>Every field defaults to AWS's own "no preference recorded" value, matching a region that
 * has never had {@code ModifyInstanceMetadataDefaults} called against it. {@code managedBy}
 * flips from {@code "none"} to {@code "account"} the first time a modify call is made -
 * mirroring what {@code GetInstanceMetadataDefaults} reports for a region an account has
 * actually configured, versus a fresh one.</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceMetadataDefaults {

    private String httpTokens = "no-preference";
    private int httpPutResponseHopLimit = -1;
    private String httpEndpoint = "no-preference";
    private String instanceMetadataTags = "no-preference";
    private String managedBy = "none";

    public String getHttpTokens() { return httpTokens; }
    public void setHttpTokens(String httpTokens) { this.httpTokens = httpTokens; }

    public int getHttpPutResponseHopLimit() { return httpPutResponseHopLimit; }
    public void setHttpPutResponseHopLimit(int httpPutResponseHopLimit) { this.httpPutResponseHopLimit = httpPutResponseHopLimit; }

    public String getHttpEndpoint() { return httpEndpoint; }
    public void setHttpEndpoint(String httpEndpoint) { this.httpEndpoint = httpEndpoint; }

    public String getInstanceMetadataTags() { return instanceMetadataTags; }
    public void setInstanceMetadataTags(String instanceMetadataTags) { this.instanceMetadataTags = instanceMetadataTags; }

    public String getManagedBy() { return managedBy; }
    public void setManagedBy(String managedBy) { this.managedBy = managedBy; }
}
