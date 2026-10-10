package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class ServerlessCache {
    private String serverlessCacheName;
    private String engine;
    private String majorEngineVersion;
    private String fullEngineVersion;
    private String description;
    private String status;
    private String arn;
    private Instant createTime;
    private Endpoint endpoint;
    private Endpoint readerEndpoint;
    private String region;
    private String accountId;
    private String backingCacheClusterId;
    private String userGroupId;
    private String kmsKeyId;
    private Integer snapshotRetentionLimit;
    private String dailySnapshotTime;
    private CacheUsageLimits cacheUsageLimits;
    private List<String> subnetIds = new ArrayList<>();
    private List<String> securityGroupIds = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ServerlessCache() {}

    public String getServerlessCacheName() { return serverlessCacheName; }
    public void setServerlessCacheName(String serverlessCacheName) { this.serverlessCacheName = serverlessCacheName; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getMajorEngineVersion() { return majorEngineVersion; }
    public void setMajorEngineVersion(String majorEngineVersion) { this.majorEngineVersion = majorEngineVersion; }

    public String getFullEngineVersion() { return fullEngineVersion; }
    public void setFullEngineVersion(String fullEngineVersion) { this.fullEngineVersion = fullEngineVersion; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public Endpoint getEndpoint() { return endpoint; }
    public void setEndpoint(Endpoint endpoint) { this.endpoint = endpoint; }

    public Endpoint getReaderEndpoint() { return readerEndpoint; }
    public void setReaderEndpoint(Endpoint readerEndpoint) { this.readerEndpoint = readerEndpoint; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getBackingCacheClusterId() { return backingCacheClusterId; }
    public void setBackingCacheClusterId(String backingCacheClusterId) { this.backingCacheClusterId = backingCacheClusterId; }

    public String getUserGroupId() { return userGroupId; }
    public void setUserGroupId(String userGroupId) { this.userGroupId = userGroupId; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public Integer getSnapshotRetentionLimit() { return snapshotRetentionLimit; }
    public void setSnapshotRetentionLimit(Integer snapshotRetentionLimit) { this.snapshotRetentionLimit = snapshotRetentionLimit; }

    public String getDailySnapshotTime() { return dailySnapshotTime; }
    public void setDailySnapshotTime(String dailySnapshotTime) { this.dailySnapshotTime = dailySnapshotTime; }

    public CacheUsageLimits getCacheUsageLimits() { return cacheUsageLimits; }
    public void setCacheUsageLimits(CacheUsageLimits cacheUsageLimits) { this.cacheUsageLimits = cacheUsageLimits; }

    public List<String> getSubnetIds() { return new ArrayList<>(subnetIds); }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds == null ? new ArrayList<>() : new ArrayList<>(subnetIds); }

    public List<String> getSecurityGroupIds() { return new ArrayList<>(securityGroupIds); }
    public void setSecurityGroupIds(List<String> securityGroupIds) { this.securityGroupIds = securityGroupIds == null ? new ArrayList<>() : new ArrayList<>(securityGroupIds); }

    public Map<String, String> getTags() { return new LinkedHashMap<>(tags); }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags); }

    @RegisterForReflection
    public record CacheUsageLimits(DataStorage dataStorage, EcpuPerSecond ecpuPerSecond) {}

    @RegisterForReflection
    public record DataStorage(Integer minimum, Integer maximum, String unit) {}

    @RegisterForReflection
    public record EcpuPerSecond(Integer minimum, Integer maximum) {}
}

