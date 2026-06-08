package io.github.hectorvent.floci.services.cloudmap.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

/**
 * A registered Cloud Map instance. {@code attributes} hold reserved keys
 * (AWS_INSTANCE_IPV4, AWS_INSTANCE_PORT, …) plus arbitrary custom keys.
 * {@code healthStatus} is HEALTHY by default and only changes via
 * UpdateInstanceCustomHealthStatus (Phase 2) for custom-health services.
 */
@RegisterForReflection
public class Instance {

    private String instanceId;
    private String serviceId;
    private String creatorRequestId;
    private String healthStatus = "HEALTHY";
    private Map<String, String> attributes = new HashMap<>();

    public Instance() {}

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }

    public String getCreatorRequestId() { return creatorRequestId; }
    public void setCreatorRequestId(String creatorRequestId) { this.creatorRequestId = creatorRequestId; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
}
