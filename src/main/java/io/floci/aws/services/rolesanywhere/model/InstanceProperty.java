package io.floci.aws.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The instance properties a subject sent with one temporary credential request, as {@code InstanceProperty} reports them. */
@RegisterForReflection
public class InstanceProperty {

    private Instant seenAt;
    private Map<String, String> properties = new LinkedHashMap<>();
    private boolean failed;

    public Instant getSeenAt() {
        return seenAt;
    }

    public void setSeenAt(Instant seenAt) {
        this.seenAt = seenAt;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
    }

    public boolean isFailed() {
        return failed;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }
}
