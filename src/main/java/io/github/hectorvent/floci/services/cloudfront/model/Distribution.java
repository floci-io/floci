package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
public class Distribution {

    private String id;
    private String arn;
    private String domainName;
    private String status;
    private Instant lastModifiedTime;
    private String etag;
    private Map<String, String> tags;
    private DistributionConfig config;

    public Distribution() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public DistributionConfig getConfig() { return config; }
    public void setConfig(DistributionConfig config) { this.config = config; }
}
