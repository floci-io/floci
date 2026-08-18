package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PublicAccess {

    @JsonProperty("type")
    private String type;

    public PublicAccess() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}