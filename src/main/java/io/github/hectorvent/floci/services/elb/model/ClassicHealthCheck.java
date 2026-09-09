package io.github.hectorvent.floci.services.elb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code HealthCheck} shape of the Classic (2012-06-01) load balancing API.
 *
 * <p>Every member is required by the model, so a stored health check is always complete. The
 * defaults applied on {@code CreateLoadBalancer} are AWS's own documented defaults, which is
 * what a Classic load balancer reports before {@code ConfigureHealthCheck} is ever called.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClassicHealthCheck {

    /** AWS's default health check for a newly created Classic load balancer. */
    public static ClassicHealthCheck defaults() {
        ClassicHealthCheck hc = new ClassicHealthCheck();
        hc.setTarget("TCP:80");
        hc.setInterval(30);
        hc.setTimeout(5);
        hc.setUnhealthyThreshold(2);
        hc.setHealthyThreshold(10);
        return hc;
    }

    private String target;
    private Integer interval;
    private Integer timeout;
    private Integer unhealthyThreshold;
    private Integer healthyThreshold;

    public ClassicHealthCheck() {}

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public Integer getInterval() { return interval; }
    public void setInterval(Integer interval) { this.interval = interval; }

    public Integer getTimeout() { return timeout; }
    public void setTimeout(Integer timeout) { this.timeout = timeout; }

    public Integer getUnhealthyThreshold() { return unhealthyThreshold; }
    public void setUnhealthyThreshold(Integer unhealthyThreshold) { this.unhealthyThreshold = unhealthyThreshold; }

    public Integer getHealthyThreshold() { return healthyThreshold; }
    public void setHealthyThreshold(Integer healthyThreshold) { this.healthyThreshold = healthyThreshold; }
}
