package io.github.hectorvent.floci.services.iot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class IotShadow {
    private String thingName;
    private String shadowName;
    private String document;
    private long version;
    /** Set on a deleted shadow, which is kept only so a shadow created again continues its version. */
    private Instant deletedAt;

    public String getThingName() { return thingName; }
    public void setThingName(String thingName) { this.thingName = thingName; }
    public String getShadowName() { return shadowName; }
    public void setShadowName(String shadowName) { this.shadowName = shadowName; }
    public String getDocument() { return document; }
    public void setDocument(String document) { this.document = document; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
