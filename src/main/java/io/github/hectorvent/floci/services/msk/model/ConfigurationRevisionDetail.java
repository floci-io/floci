package io.github.hectorvent.floci.services.msk.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Full shape of DescribeConfigurationRevision - the only MSK configuration action that
 * returns serverProperties. Transient: never serialized directly, only assembled by a
 * service method and read field-by-field by the controller.
 */
@RegisterForReflection
public class ConfigurationRevisionDetail {

    private final String arn;
    private final Instant creationTime;
    private final String description;
    private final long revision;
    private final String serverProperties;

    public ConfigurationRevisionDetail(String arn, Instant creationTime, String description,
                                        long revision, String serverProperties) {
        this.arn = arn;
        this.creationTime = creationTime;
        this.description = description;
        this.revision = revision;
        this.serverProperties = serverProperties;
    }

    public String getArn() { return arn; }
    public Instant getCreationTime() { return creationTime; }
    public String getDescription() { return description; }
    public long getRevision() { return revision; }
    public String getServerProperties() { return serverProperties; }
}
