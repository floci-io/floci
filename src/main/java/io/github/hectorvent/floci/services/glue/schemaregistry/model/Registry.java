package io.github.hectorvent.floci.services.glue.schemaregistry.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Registry {

    @JsonProperty("RegistryName")
    private String registryName;

    @JsonProperty("RegistryArn")
    private String registryArn;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("CreatedTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdTime;

    @JsonProperty("UpdatedTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant updatedTime;

    @JsonProperty("Tags")
    private Map<String, String> tags;

    public Registry() {}

    public Registry(String registryName) {
        this.registryName = registryName;
        this.createdTime = Instant.now();
        this.updatedTime = this.createdTime;
        this.status = "AVAILABLE";
    }

    public String getRegistryName() { return registryName; }
    public void setRegistryName(String registryName) { this.registryName = registryName; }

    public String getRegistryArn() { return registryArn; }
    public void setRegistryArn(String registryArn) { this.registryArn = registryArn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }

    public Instant getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(Instant updatedTime) { this.updatedTime = updatedTime; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
