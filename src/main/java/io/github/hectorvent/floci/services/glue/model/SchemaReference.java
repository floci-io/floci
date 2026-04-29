package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchemaReference {

    @JsonProperty("SchemaId")
    private SchemaId schemaId;

    @JsonProperty("SchemaVersionId")
    private String schemaVersionId;

    @JsonProperty("SchemaVersionNumber")
    private Long schemaVersionNumber;

    public SchemaReference() {}

    public SchemaId getSchemaId() { return schemaId; }
    public void setSchemaId(SchemaId schemaId) { this.schemaId = schemaId; }

    public String getSchemaVersionId() { return schemaVersionId; }
    public void setSchemaVersionId(String schemaVersionId) { this.schemaVersionId = schemaVersionId; }

    public Long getSchemaVersionNumber() { return schemaVersionNumber; }
    public void setSchemaVersionNumber(Long schemaVersionNumber) { this.schemaVersionNumber = schemaVersionNumber; }
}
