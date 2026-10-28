package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KinesisParameters {

    private String partitionKeyPath;

    public KinesisParameters() {}

    public String getPartitionKeyPath() { return partitionKeyPath; }
    public void setPartitionKeyPath(String partitionKeyPath) { this.partitionKeyPath = partitionKeyPath; }

    /** {@code null} when the node is absent or has no partition key path. */
    public static KinesisParameters fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String path = node.path("PartitionKeyPath").asText(null);
        if (path == null || path.isBlank()) {
            return null;
        }
        KinesisParameters parameters = new KinesisParameters();
        parameters.setPartitionKeyPath(path);
        return parameters;
    }
}
