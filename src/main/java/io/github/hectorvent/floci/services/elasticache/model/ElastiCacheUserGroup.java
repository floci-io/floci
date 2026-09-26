package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@RegisterForReflection
public class ElastiCacheUserGroup {

    private String userGroupId;
    // "redis" or "valkey"
    private String engine = "redis";
    private String status;
    private Set<String> userIds = new LinkedHashSet<>();
    private Instant createdAt;
    private String region;

    public ElastiCacheUserGroup() {}

    public ElastiCacheUserGroup(String userGroupId, String engine, Set<String> userIds,
                                String status, Instant createdAt) {
        this.userGroupId = userGroupId;
        this.engine = engine;
        this.userIds = userIds != null ? userIds : new LinkedHashSet<>();
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getUserGroupId() { return userGroupId; }
    public void setUserGroupId(String userGroupId) { this.userGroupId = userGroupId; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Set<String> getUserIds() { return userIds; }
    public void setUserIds(Set<String> userIds) {
        this.userIds = userIds != null ? userIds : new LinkedHashSet<>();
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
