package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Prometheus {

    @JsonProperty("jmxExporter")
    private JmxExporter jmxExporter;

    @JsonProperty("nodeExporter")
    private NodeExporter nodeExporter;

    public Prometheus() {}

    public JmxExporter getJmxExporter() { return jmxExporter; }
    public void setJmxExporter(JmxExporter jmxExporter) { this.jmxExporter = jmxExporter; }

    public NodeExporter getNodeExporter() { return nodeExporter; }
    public void setNodeExporter(NodeExporter nodeExporter) { this.nodeExporter = nodeExporter; }
}
