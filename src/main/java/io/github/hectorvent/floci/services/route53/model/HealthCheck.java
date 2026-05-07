package io.github.hectorvent.floci.services.route53.model;

public class HealthCheck {

    private String id;
    private String callerReference;
    private HealthCheckConfig config;
    private long healthCheckVersion;

    public HealthCheck() {}

    public HealthCheck(String id, String callerReference, HealthCheckConfig config) {
        this.id = id;
        this.callerReference = callerReference;
        this.config = config;
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
}
