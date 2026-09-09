package io.github.hectorvent.floci.services.elb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code LoadBalancerAttributes} shape of the Classic (2012-06-01) load balancing API.
 *
 * <p>Classic attributes are a fixed structure rather than the ELBv2 key/value list, so they are
 * modelled as fields. Only the four documented sub-structures plus {@code AdditionalAttributes}
 * exist; anything else a caller sends is carried in {@code additionalAttributes} unchanged.
 *
 * <p>The initial values are AWS's own defaults for a freshly created Classic load balancer:
 * cross-zone off, access logging off, connection draining off with a 300 second timeout, and a
 * 60 second idle timeout.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClassicLoadBalancerAttributes {

    private boolean crossZoneLoadBalancingEnabled = false;

    private boolean accessLogEnabled = false;
    private String accessLogS3BucketName;
    private String accessLogS3BucketPrefix;
    private Integer accessLogEmitInterval = 60;

    private boolean connectionDrainingEnabled = false;
    private Integer connectionDrainingTimeout = 300;

    private Integer idleTimeout = 60;

    private Map<String, String> additionalAttributes = new LinkedHashMap<>();

    public ClassicLoadBalancerAttributes() {}

    public boolean isCrossZoneLoadBalancingEnabled() { return crossZoneLoadBalancingEnabled; }
    public void setCrossZoneLoadBalancingEnabled(boolean v) { this.crossZoneLoadBalancingEnabled = v; }

    public boolean isAccessLogEnabled() { return accessLogEnabled; }
    public void setAccessLogEnabled(boolean v) { this.accessLogEnabled = v; }

    public String getAccessLogS3BucketName() { return accessLogS3BucketName; }
    public void setAccessLogS3BucketName(String v) { this.accessLogS3BucketName = v; }

    public String getAccessLogS3BucketPrefix() { return accessLogS3BucketPrefix; }
    public void setAccessLogS3BucketPrefix(String v) { this.accessLogS3BucketPrefix = v; }

    public Integer getAccessLogEmitInterval() { return accessLogEmitInterval; }
    public void setAccessLogEmitInterval(Integer v) { this.accessLogEmitInterval = v; }

    public boolean isConnectionDrainingEnabled() { return connectionDrainingEnabled; }
    public void setConnectionDrainingEnabled(boolean v) { this.connectionDrainingEnabled = v; }

    public Integer getConnectionDrainingTimeout() { return connectionDrainingTimeout; }
    public void setConnectionDrainingTimeout(Integer v) { this.connectionDrainingTimeout = v; }

    public Integer getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Integer v) { this.idleTimeout = v; }

    public Map<String, String> getAdditionalAttributes() { return additionalAttributes; }
    public void setAdditionalAttributes(Map<String, String> v) { this.additionalAttributes = v; }
}
