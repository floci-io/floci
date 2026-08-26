package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class GetCrawlerRequest {
    @JsonProperty("Name")
    private String name;

    public GetCrawlerRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
