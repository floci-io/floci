package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Node-to-node TLS encryption flag. Round-tripped only — Floci runs the
 * domain as a single Docker container, so there are no node-to-node
 * connections to encrypt.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeToNodeEncryptionOptions {

    @JsonProperty("Enabled")
    private boolean enabled = false;

    public NodeToNodeEncryptionOptions() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
