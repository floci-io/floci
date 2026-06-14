package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class Column {
    @JsonProperty("Name")
    private String name;
    @JsonProperty("Type")
    private String type;
    @JsonProperty("Comment")
    private String comment;
    @JsonProperty("Parameters")
    private Map<String, String> parameters;

    public Column() {}
    public Column(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Map<String, String> getParameters() { return parameters == null ? null : new LinkedHashMap<>(parameters); }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters == null ? null : new LinkedHashMap<>(parameters); }
}
