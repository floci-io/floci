package io.github.hectorvent.floci.services.dms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An endpoint, replication instance or replication task. {@code attributes} holds the resource
 * in the wire shape DMS returns it in ({@code Endpoint}, {@code ReplicationInstance},
 * {@code ReplicationTask}), so a describe echoes exactly what was stored. Secret members such as
 * {@code Password} are never stored, because DMS never returns them.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmsResource {

    private ObjectNode attributes = JsonNodeFactory.instance.objectNode();
    private Map<String, String> tags = new LinkedHashMap<>();

    public DmsResource() {
    }

    public ObjectNode getAttributes() {
        return attributes;
    }

    public void setAttributes(ObjectNode attributes) {
        this.attributes = attributes != null ? attributes : JsonNodeFactory.instance.objectNode();
    }

    public Map<String, String> getTags() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
