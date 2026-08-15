package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class MskConfiguration {

    @JsonProperty("arn")
    private String arn;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("kafkaVersions")
    private List<String> kafkaVersions;

    @JsonProperty("state")
    private ConfigurationState state;

    @JsonProperty("creationTime")
    private Instant creationTime;

    @JsonProperty("latestRevision")
    private ConfigurationRevision latestRevision;

    // Decoded server.properties content. Must round-trip through persistent/hybrid/wal
    // storage (StorageBackend serializes this same model via Jackson), so it cannot be
    // @JsonIgnore - the controller builds explicit response views instead to keep it out
    // of Create/List/Describe responses, since AWS only returns it via
    // DescribeConfigurationRevision (a separate follow-up issue, out of scope here).
    @JsonProperty("serverProperties")
    private String serverProperties;

    @JsonIgnore
    private String accountId;

    public MskConfiguration() {}

    public MskConfiguration(String arn, String name, String description,
                             List<String> kafkaVersions, String serverProperties) {
        this.arn = arn;
        this.name = name;
        this.description = description;
        this.kafkaVersions = kafkaVersions;
        this.serverProperties = serverProperties;
        this.state = ConfigurationState.ACTIVE;
        this.creationTime = Instant.now();
        this.latestRevision = new ConfigurationRevision(1L, this.creationTime, description);
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getKafkaVersions() { return kafkaVersions; }
    public void setKafkaVersions(List<String> kafkaVersions) { this.kafkaVersions = kafkaVersions; }

    public ConfigurationState getState() { return state; }
    public void setState(ConfigurationState state) { this.state = state; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public ConfigurationRevision getLatestRevision() { return latestRevision; }
    public void setLatestRevision(ConfigurationRevision latestRevision) { this.latestRevision = latestRevision; }

    public String getServerProperties() { return serverProperties; }
    public void setServerProperties(String serverProperties) { this.serverProperties = serverProperties; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
}
