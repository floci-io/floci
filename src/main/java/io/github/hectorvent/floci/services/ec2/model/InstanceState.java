package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstanceState {

    private int code;
    private String name;

    public InstanceState() {}

    public InstanceState(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public static InstanceState pending() { return new InstanceState(0, "pending"); }
    public static InstanceState running() { return new InstanceState(16, "running"); }
    public static InstanceState shuttingDown() { return new InstanceState(32, "shutting-down"); }
    public static InstanceState terminated() { return new InstanceState(48, "terminated"); }
    public static InstanceState stopping() { return new InstanceState(64, "stopping"); }
    public static InstanceState stopped() { return new InstanceState(80, "stopped"); }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
