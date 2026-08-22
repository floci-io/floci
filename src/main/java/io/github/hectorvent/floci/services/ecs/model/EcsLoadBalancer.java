package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * A load balancer association on an ECS service ({@code CreateService}'s
 * {@code loadBalancers} block). Links a container port on the service's tasks
 * to an ELBv2 target group so running task containers can be registered as targets.
 */
@RegisterForReflection
public class EcsLoadBalancer {

    private String targetGroupArn;
    private String loadBalancerName;
    private String containerName;
    private Integer containerPort;
    /** Raw passthrough of the blue/green target-group-switchover block (alternateTargetGroupArn,
     *  productionListenerRule, testListenerRule, roleArn) — stored and echoed back verbatim. */
    private Map<String, Object> advancedConfiguration;

    public EcsLoadBalancer() {}

    public String getTargetGroupArn() { return targetGroupArn; }
    public void setTargetGroupArn(String targetGroupArn) { this.targetGroupArn = targetGroupArn; }

    public String getLoadBalancerName() { return loadBalancerName; }
    public void setLoadBalancerName(String loadBalancerName) { this.loadBalancerName = loadBalancerName; }

    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }

    public Integer getContainerPort() { return containerPort; }
    public void setContainerPort(Integer containerPort) { this.containerPort = containerPort; }

    public Map<String, Object> getAdvancedConfiguration() { return advancedConfiguration; }
    public void setAdvancedConfiguration(Map<String, Object> advancedConfiguration) {
        this.advancedConfiguration = advancedConfiguration;
    }
}
