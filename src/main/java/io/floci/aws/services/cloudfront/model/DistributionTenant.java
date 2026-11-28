package io.floci.aws.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DistributionTenant {

    private String id;
    private String arn;
    private String name;
    private String distributionId;
    private String connectionGroupId;
    private List<String> domains;
    private TenantCustomizations customizations;
    private Map<String, String> parameters;
    private Boolean enabled;
    private String status;
    private Instant createdTime;
    private Instant lastModifiedTime;
    private String etag;

    public DistributionTenant() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDistributionId() { return distributionId; }
    public void setDistributionId(String distributionId) { this.distributionId = distributionId; }

    public String getConnectionGroupId() { return connectionGroupId; }
    public void setConnectionGroupId(String connectionGroupId) { this.connectionGroupId = connectionGroupId; }

    public List<String> getDomains() { return domains; }
    public void setDomains(List<String> domains) { this.domains = domains; }

    public TenantCustomizations getCustomizations() { return customizations; }
    public void setCustomizations(TenantCustomizations customizations) { this.customizations = customizations; }

    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
}
