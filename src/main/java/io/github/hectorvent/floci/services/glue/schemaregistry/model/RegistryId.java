package io.github.hectorvent.floci.services.glue.schemaregistry.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RegistryId {

    @JsonProperty("RegistryName")
    private String registryName;

    @JsonProperty("RegistryArn")
    private String registryArn;

    public RegistryId() {}

    public RegistryId(String registryName, String registryArn) {
        this.registryName = registryName;
        this.registryArn = registryArn;
    }

    public String getRegistryName() { return registryName; }
    public void setRegistryName(String registryName) { this.registryName = registryName; }

    public String getRegistryArn() { return registryArn; }
    public void setRegistryArn(String registryArn) { this.registryArn = registryArn; }
}
