package io.floci.aws.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TargetDescription {

    private String id;
    private Integer port;
    private String availabilityZone;

    public TargetDescription() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }
}
