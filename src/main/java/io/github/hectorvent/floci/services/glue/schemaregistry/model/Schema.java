package io.github.hectorvent.floci.services.glue.schemaregistry.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Schema {

    @JsonProperty("RegistryName")
    private String registryName;

    @JsonProperty("RegistryArn")
    private String registryArn;

    @JsonProperty("SchemaName")
    private String schemaName;

    @JsonProperty("SchemaArn")
    private String schemaArn;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("DataFormat")
    private String dataFormat;

    @JsonProperty("Compatibility")
    private String compatibility;

    @JsonProperty("SchemaStatus")
    private String schemaStatus;

    @JsonProperty("SchemaCheckpoint")
    private Long schemaCheckpoint;

    @JsonProperty("LatestSchemaVersion")
    private Long latestSchemaVersion;

    @JsonProperty("NextSchemaVersion")
    private Long nextSchemaVersion;

    @JsonProperty("CreatedTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdTime;

    @JsonProperty("UpdatedTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant updatedTime;

    @JsonProperty("Tags")
    private Map<String, String> tags;

    public Schema() {}

    public String getRegistryName() { return registryName; }
    public void setRegistryName(String registryName) { this.registryName = registryName; }

    public String getRegistryArn() { return registryArn; }
    public void setRegistryArn(String registryArn) { this.registryArn = registryArn; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public String getSchemaArn() { return schemaArn; }
    public void setSchemaArn(String schemaArn) { this.schemaArn = schemaArn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDataFormat() { return dataFormat; }
    public void setDataFormat(String dataFormat) { this.dataFormat = dataFormat; }

    public String getCompatibility() { return compatibility; }
    public void setCompatibility(String compatibility) { this.compatibility = compatibility; }

    public String getSchemaStatus() { return schemaStatus; }
    public void setSchemaStatus(String schemaStatus) { this.schemaStatus = schemaStatus; }

    public Long getSchemaCheckpoint() { return schemaCheckpoint; }
    public void setSchemaCheckpoint(Long schemaCheckpoint) { this.schemaCheckpoint = schemaCheckpoint; }

    public Long getLatestSchemaVersion() { return latestSchemaVersion; }
    public void setLatestSchemaVersion(Long latestSchemaVersion) { this.latestSchemaVersion = latestSchemaVersion; }

    public Long getNextSchemaVersion() { return nextSchemaVersion; }
    public void setNextSchemaVersion(Long nextSchemaVersion) { this.nextSchemaVersion = nextSchemaVersion; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }

    public Instant getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(Instant updatedTime) { this.updatedTime = updatedTime; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
