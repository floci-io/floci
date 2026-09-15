package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** One key of a partition index, as returned by {@code GetPartitionIndexes}. */
@RegisterForReflection
public class KeySchemaElement {
    @JsonProperty("Name")
    private String name;
    @JsonProperty("Type")
    private String type;

    public KeySchemaElement() {}
    public KeySchemaElement(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
