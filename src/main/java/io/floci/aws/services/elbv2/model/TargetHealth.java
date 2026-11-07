package io.floci.aws.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TargetHealth {

    private TargetDescription target;
    private String healthCheckPort;
    private String state;
    private String reason;
    private String description;

    public TargetHealth() {}

    public TargetDescription getTarget() { return target; }
    public void setTarget(TargetDescription target) { this.target = target; }

    public String getHealthCheckPort() { return healthCheckPort; }
    public void setHealthCheckPort(String healthCheckPort) { this.healthCheckPort = healthCheckPort; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
