package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigurationInfo {

    @JsonProperty("arn")
    private String arn;

    @JsonProperty("revision")
    private Long revision;

    public ConfigurationInfo() {}

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
}