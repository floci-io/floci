package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class ConnectionsList {
    @JsonProperty("Connections")
    private List<String> connections;

    public ConnectionsList() {}

    public List<String> getConnections() { return connections; }
    public void setConnections(List<String> connections) { this.connections = connections; }
}
