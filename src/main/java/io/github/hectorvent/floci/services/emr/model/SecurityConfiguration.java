package io.github.hectorvent.floci.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** An EMR security configuration: a named JSON document. */
@RegisterForReflection
public class SecurityConfiguration {

    private String name;
    private String securityConfiguration;
    private Instant creationDateTime;

    public SecurityConfiguration() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSecurityConfiguration() { return securityConfiguration; }
    public void setSecurityConfiguration(String securityConfiguration) {
        this.securityConfiguration = securityConfiguration;
    }

    public Instant getCreationDateTime() { return creationDateTime; }
    public void setCreationDateTime(Instant creationDateTime) { this.creationDateTime = creationDateTime; }
}
