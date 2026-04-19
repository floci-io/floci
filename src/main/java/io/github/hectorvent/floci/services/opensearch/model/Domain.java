package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Domain {

    @JsonProperty("DomainName")
    private String domainName;

    @JsonProperty("DomainId")
    private String domainId;

    @JsonProperty("ARN")
    private String arn;

    @JsonProperty("EngineVersion")
    private String engineVersion = "OpenSearch_2.11";

    @JsonProperty("Processing")
    private boolean processing = false;

    @JsonProperty("Deleted")
    private boolean deleted = false;

    @JsonProperty("ClusterConfig")
    private ClusterConfig clusterConfig = new ClusterConfig();

    @JsonProperty("EBSOptions")
    private EbsOptions ebsOptions = new EbsOptions();

    @JsonProperty("Endpoint")
    private String endpoint = "";

    @JsonProperty("Tags")
    private Map<String, String> tags = new HashMap<>();

    @JsonProperty("ContainerId")
    private String containerId;

    @JsonProperty("CreatedAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;

    public Domain() {}

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getEngineVersion() {
        return engineVersion;
    }

    public void setEngineVersion(String engineVersion) {
        this.engineVersion = engineVersion;
    }

    public boolean isProcessing() {
        return processing;
    }

    public void setProcessing(boolean processing) {
        this.processing = processing;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public ClusterConfig getClusterConfig() {
        return clusterConfig;
    }

    public void setClusterConfig(ClusterConfig clusterConfig) {
        this.clusterConfig = clusterConfig;
    }

    public EbsOptions getEbsOptions() {
        return ebsOptions;
    }

    public void setEbsOptions(EbsOptions ebsOptions) {
        this.ebsOptions = ebsOptions;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new HashMap<>(tags) : new HashMap<>();
    }

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
