package io.github.hectorvent.floci.services.iot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public class IotThingGroup {

    private String thingGroupName;
    private String thingGroupArn;
    private String thingGroupId;
    private String description;
    private Map<String, String> attributes = new HashMap<>();
    private long version = 1L;
    private Instant creationDate;

    public String getThingGroupName() { return thingGroupName; }
    public void setThingGroupName(String thingGroupName) { this.thingGroupName = thingGroupName; }

    public String getThingGroupArn() { return thingGroupArn; }
    public void setThingGroupArn(String thingGroupArn) { this.thingGroupArn = thingGroupArn; }

    public String getThingGroupId() { return thingGroupId; }
    public void setThingGroupId(String thingGroupId) { this.thingGroupId = thingGroupId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes == null ? new HashMap<>() : new HashMap<>(attributes); }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }
}
