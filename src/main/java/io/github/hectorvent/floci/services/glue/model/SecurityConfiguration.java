package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class SecurityConfiguration {

    private String name;
    private Instant createdTimeStamp;
    private JsonNode encryptionConfiguration;

    public SecurityConfiguration() {}

    @JsonProperty("Name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @JsonProperty("CreatedTimeStamp")
    public Instant getCreatedTimeStamp() {
        return createdTimeStamp;
    }

    public void setCreatedTimeStamp(Instant createdTimeStamp) {
        this.createdTimeStamp = createdTimeStamp;
    }

    @JsonProperty("EncryptionConfiguration")
    public JsonNode getEncryptionConfiguration() {
        return encryptionConfiguration;
    }

    public void setEncryptionConfiguration(JsonNode encryptionConfiguration) {
        this.encryptionConfiguration = encryptionConfiguration;
    }
}
