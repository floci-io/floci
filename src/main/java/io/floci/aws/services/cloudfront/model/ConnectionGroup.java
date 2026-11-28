package io.floci.aws.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
public class ConnectionGroup {

    private String id;
    private String arn;
    private String name;
    private String anycastIpListId;
    private Boolean enabled;
    private Boolean ipv6Enabled;
    private Boolean isDefault;
    private String status;
    private String routingEndpoint;
    private Instant createdTime;
    private Instant lastModifiedTime;
    private String etag;
    private String viewerFallbackDistributionId;
    private Map<String, String> tags;

    public ConnectionGroup() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAnycastIpListId() { return anycastIpListId; }
    public void setAnycastIpListId(String anycastIpListId) { this.anycastIpListId = anycastIpListId; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Boolean getIpv6Enabled() { return ipv6Enabled; }
    public void setIpv6Enabled(Boolean ipv6Enabled) { this.ipv6Enabled = ipv6Enabled; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRoutingEndpoint() { return routingEndpoint; }
    public void setRoutingEndpoint(String routingEndpoint) { this.routingEndpoint = routingEndpoint; }

    public Instant getCreatedTime() { return createdTime; }
    public void setCreatedTime(Instant createdTime) { this.createdTime = createdTime; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getViewerFallbackDistributionId() { return viewerFallbackDistributionId; }
    public void setViewerFallbackDistributionId(String viewerFallbackDistributionId) {
        this.viewerFallbackDistributionId = viewerFallbackDistributionId;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
