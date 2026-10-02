package io.github.hectorvent.floci.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** The block public access configuration of an account in one Region, and when and by whom it was set. */
@RegisterForReflection
public class EmrBlockPublicAccess {

    /** The BlockPublicAccessConfiguration as given, raw JSON. */
    private String configuration;
    private Instant creationDateTime;
    private String createdByArn;

    public EmrBlockPublicAccess() {}

    public String getConfiguration() { return configuration; }
    public void setConfiguration(String configuration) { this.configuration = configuration; }

    public Instant getCreationDateTime() { return creationDateTime; }
    public void setCreationDateTime(Instant creationDateTime) { this.creationDateTime = creationDateTime; }

    public String getCreatedByArn() { return createdByArn; }
    public void setCreatedByArn(String createdByArn) { this.createdByArn = createdByArn; }
}
