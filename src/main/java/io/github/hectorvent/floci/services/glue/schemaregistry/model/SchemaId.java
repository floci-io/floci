package io.github.hectorvent.floci.services.glue.schemaregistry.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchemaId {

    @JsonProperty("SchemaArn")
    private String schemaArn;

    @JsonProperty("SchemaName")
    private String schemaName;

    @JsonProperty("RegistryName")
    private String registryName;

    public SchemaId() {}

    public SchemaId(String registryName, String schemaName, String schemaArn) {
        this.registryName = registryName;
        this.schemaName = schemaName;
        this.schemaArn = schemaArn;
    }

    public String getSchemaArn() { return schemaArn; }
    public void setSchemaArn(String schemaArn) { this.schemaArn = schemaArn; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public String getRegistryName() { return registryName; }
    public void setRegistryName(String registryName) { this.registryName = registryName; }
}
