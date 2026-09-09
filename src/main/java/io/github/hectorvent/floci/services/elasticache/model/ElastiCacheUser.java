package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class ElastiCacheUser {

    private String userId;
    private String userName;
    private AuthMode authMode;
    private List<String> passwords;
    private String accessString;
    // "redis" or "valkey"; the initializer keeps users persisted before this field existed on redis.
    private String engine = "redis";
    private String status;
    private Instant createdAt;

    public ElastiCacheUser() {}

    public ElastiCacheUser(String userId, String userName, AuthMode authMode,
                           List<String> passwords, String accessString, String engine,
                           String status, Instant createdAt) {
        this.userId = userId;
        this.userName = userName;
        this.authMode = authMode;
        this.passwords = passwords;
        this.accessString = accessString;
        this.engine = engine;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public AuthMode getAuthMode() { return authMode; }
    public void setAuthMode(AuthMode authMode) { this.authMode = authMode; }

    public List<String> getPasswords() { return passwords; }
    public void setPasswords(List<String> passwords) { this.passwords = passwords; }

    public String getAccessString() { return accessString; }
    public void setAccessString(String accessString) { this.accessString = accessString; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
