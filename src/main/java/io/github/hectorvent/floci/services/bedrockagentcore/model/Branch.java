package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The branch an event belongs to. AWS defaults a new event to the {@code main} branch. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Branch {
    @JsonProperty("name")
    private String name;
    @JsonProperty("rootEventId")
    private String rootEventId;

    public Branch() {}
    public Branch(String name) { this.name = name; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRootEventId() { return rootEventId; }
    public void setRootEventId(String rootEventId) { this.rootEventId = rootEventId; }
}
