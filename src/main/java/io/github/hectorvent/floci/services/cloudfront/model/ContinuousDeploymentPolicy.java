package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class ContinuousDeploymentPolicy {

    private String id;
    private Instant lastModifiedTime;
    private String etag;
    private boolean enabled;
    private List<String> stagingDistributionDnsNames;
    private String trafficConfig;

    public ContinuousDeploymentPolicy() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getStagingDistributionDnsNames() { return stagingDistributionDnsNames; }
    public void setStagingDistributionDnsNames(List<String> stagingDistributionDnsNames) {
        this.stagingDistributionDnsNames = stagingDistributionDnsNames;
    }

    public String getTrafficConfig() { return trafficConfig; }
    public void setTrafficConfig(String trafficConfig) { this.trafficConfig = trafficConfig; }
}