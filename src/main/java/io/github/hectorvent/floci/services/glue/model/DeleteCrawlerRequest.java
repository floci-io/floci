package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class DeleteCrawlerRequest {
    @JsonProperty("Name")
    private String name;

    public DeleteCrawlerRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
