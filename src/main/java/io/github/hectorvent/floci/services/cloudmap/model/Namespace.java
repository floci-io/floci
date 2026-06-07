package io.github.hectorvent.floci.services.cloudmap.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Cloud Map namespace. {@code type} is one of DNS_PUBLIC, DNS_PRIVATE, HTTP.
 * DNS namespaces carry a synthesized {@code hostedZoneId}; private DNS namespaces
 * also record the {@code vpc} they were created against.
 */
@RegisterForReflection
public class Namespace {

    private String id;
    private String arn;
    private String name;
    private String type;
    private String description;
    private int serviceCount;
    private Instant createDate;
    private String creatorRequestId;
    private String region;
    private String vpc;
    private String hostedZoneId;
    private Map<String, String> tags = new HashMap<>();

    public Namespace() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getServiceCount() { return serviceCount; }
    public void setServiceCount(int serviceCount) { this.serviceCount = serviceCount; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public String getCreatorRequestId() { return creatorRequestId; }
    public void setCreatorRequestId(String creatorRequestId) { this.creatorRequestId = creatorRequestId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getVpc() { return vpc; }
    public void setVpc(String vpc) { this.vpc = vpc; }

    public String getHostedZoneId() { return hostedZoneId; }
    public void setHostedZoneId(String hostedZoneId) { this.hostedZoneId = hostedZoneId; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
