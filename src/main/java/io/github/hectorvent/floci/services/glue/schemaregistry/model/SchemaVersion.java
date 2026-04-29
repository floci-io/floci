package io.github.hectorvent.floci.services.glue.schemaregistry.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchemaVersion {

    @JsonProperty("SchemaVersionId")
    private String schemaVersionId;

    @JsonProperty("SchemaArn")
    private String schemaArn;

    @JsonProperty("VersionNumber")
    private Long versionNumber;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("SchemaDefinition")
    private String schemaDefinition;

    @JsonProperty("DataFormat")
    private String dataFormat;

    @JsonProperty("CreatedTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdTime;

    public SchemaVersion() {}

    public String getSchemaVersionId() { return schemaVersionId; }
    public void setSchemaVersionId(String schemaVersionId) { this.schemaVersionId = schemaVersionId; }

    public String getSchemaArn() { return schemaArn; }
    public void setSchemaArn(String schemaArn) { this.schemaArn = schemaArn; }

    public Long getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Long versionNumber) { this.versionNumber = versionNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSchemaDefinition() { return schemaDefinition; }
    public void setSchemaDefinition(String schemaDefinition) { this.schemaDefinition = schemaDefinition; }

    public String getDataFormat() { return dataFormat; }
    public void setDataFormat(String dataFormat) { this.dataFormat = dataFormat; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }
}
