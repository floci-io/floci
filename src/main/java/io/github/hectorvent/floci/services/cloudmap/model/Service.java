package io.github.hectorvent.floci.services.cloudmap.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Cloud Map service. {@code dnsConfig}, {@code healthCheckConfig} and
 * {@code healthCheckCustomConfig} are stored as raw JSON strings and re-emitted
 * verbatim as JSON objects so SDK clients round-trip the exact structure.
 * {@code revision} is the monotonic InstancesRevision bumped on every
 * register/deregister of an instance in this service.
 */
@RegisterForReflection
public class Service {

    private String id;
    private String arn;
    private String name;
    private String namespaceId;
    private String description;
    private int instanceCount;
    private String type;
    private String dnsConfig;
    private String healthCheckConfig;
    private String healthCheckCustomConfig;
    private Instant createDate;
    private String creatorRequestId;
    private String region;
    private long revision;
    private Map<String, String> tags = new HashMap<>();

    public Service() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getNamespaceId() { return namespaceId; }
    public void setNamespaceId(String namespaceId) { this.namespaceId = namespaceId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getInstanceCount() { return instanceCount; }
    public void setInstanceCount(int instanceCount) { this.instanceCount = instanceCount; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDnsConfig() { return dnsConfig; }
    public void setDnsConfig(String dnsConfig) { this.dnsConfig = dnsConfig; }

    public String getHealthCheckConfig() { return healthCheckConfig; }
    public void setHealthCheckConfig(String healthCheckConfig) { this.healthCheckConfig = healthCheckConfig; }

    public String getHealthCheckCustomConfig() { return healthCheckCustomConfig; }
    public void setHealthCheckCustomConfig(String healthCheckCustomConfig) {
        this.healthCheckCustomConfig = healthCheckCustomConfig;
    }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public String getCreatorRequestId() { return creatorRequestId; }
    public void setCreatorRequestId(String creatorRequestId) { this.creatorRequestId = creatorRequestId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
