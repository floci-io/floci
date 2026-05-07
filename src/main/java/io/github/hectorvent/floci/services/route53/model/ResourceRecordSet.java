package io.github.hectorvent.floci.services.route53.model;

import java.util.List;

public class ResourceRecordSet {

    private String name;
    private String type;
    private Long ttl;
    private List<ResourceRecord> records;
    private AliasTarget aliasTarget;
    private Long weight;
    private String region;
    private String setIdentifier;
    private String failover;
    private String healthCheckId;

    public ResourceRecordSet() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getTtl() { return ttl; }
    public void setTtl(Long ttl) { this.ttl = ttl; }

    public List<ResourceRecord> getRecords() { return records; }
    public void setRecords(List<ResourceRecord> records) { this.records = records; }

    public AliasTarget getAliasTarget() { return aliasTarget; }
    public void setAliasTarget(AliasTarget aliasTarget) { this.aliasTarget = aliasTarget; }

    public Long getWeight() { return weight; }
    public void setWeight(Long weight) { this.weight = weight; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getSetIdentifier() { return setIdentifier; }
    public void setSetIdentifier(String setIdentifier) { this.setIdentifier = setIdentifier; }

    public String getFailover() { return failover; }
    public void setFailover(String failover) { this.failover = failover; }

    public String getHealthCheckId() { return healthCheckId; }
    public void setHealthCheckId(String healthCheckId) { this.healthCheckId = healthCheckId; }
}
