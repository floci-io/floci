package io.github.hectorvent.floci.services.route53.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class HealthCheck {

    private String id;
    private String callerReference;
    private HealthCheckConfig config;
    private long healthCheckVersion;
    private String requestSettings;

    public HealthCheck() {}

    public HealthCheck(String id, String callerReference, HealthCheckConfig config, String requestSettings) {
        this.id = id;
        this.callerReference = callerReference;
        this.config = config;
        this.requestSettings = requestSettings;
        this.healthCheckVersion = 1;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCallerReference() { return callerReference; }
    public void setCallerReference(String callerReference) { this.callerReference = callerReference; }

    public HealthCheckConfig getConfig() { return config; }
    public void setConfig(HealthCheckConfig config) { this.config = config; }

    public long getHealthCheckVersion() { return healthCheckVersion; }
    public void setHealthCheckVersion(long healthCheckVersion) { this.healthCheckVersion = healthCheckVersion; }

    /** The create request's settings in canonical form, which a retry with the same reference must match. */
    public String getRequestSettings() { return requestSettings; }
    public void setRequestSettings(String requestSettings) { this.requestSettings = requestSettings; }
}
