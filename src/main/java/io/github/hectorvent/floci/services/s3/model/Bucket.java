package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Bucket {

    private String name;
    private Instant creationDate;
    private String versioningStatus; // null (never enabled), "Enabled", "Suspended"
    private Map<String, String> tags;
    private NotificationConfiguration notificationConfiguration;
    private boolean objectLockEnabled;
    private ObjectLockRetention defaultRetention; // null if no default rule
    private String policy;
    private String corsConfiguration;
    private String lifecycleConfiguration;
    private String acl; // XML representation or JSON stub
    private String encryptionConfiguration; // XML string
    private String publicAccessBlockConfiguration; // XML string
    private String region;

    public Bucket() {
        this.tags = new HashMap<>();
    }

    public Bucket(String name) {
        this.name = name;
        this.creationDate = Instant.now();
        this.tags = new HashMap<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }

    public String getVersioningStatus() { return versioningStatus; }
    public void setVersioningStatus(String versioningStatus) { this.versioningStatus = versioningStatus; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public boolean isVersioningEnabled() { return "Enabled".equals(versioningStatus); }

    public NotificationConfiguration getNotificationConfiguration() { return notificationConfiguration; }
    public void setNotificationConfiguration(NotificationConfiguration notificationConfiguration) {
        this.notificationConfiguration = notificationConfiguration;
    }

    public boolean isObjectLockEnabled() { return objectLockEnabled; }
    public void setObjectLockEnabled(boolean objectLockEnabled) { this.objectLockEnabled = objectLockEnabled; }
    public void setBucketObjectLockEnabled() { this.objectLockEnabled = true; }

    public ObjectLockRetention getDefaultRetention() { return defaultRetention; }
    public void setDefaultRetention(ObjectLockRetention defaultRetention) { this.defaultRetention = defaultRetention; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public String getCorsConfiguration() { return corsConfiguration; }
    public void setCorsConfiguration(String corsConfiguration) { this.corsConfiguration = corsConfiguration; }

    public String getLifecycleConfiguration() { return lifecycleConfiguration; }
    public void setLifecycleConfiguration(String lifecycleConfiguration) { this.lifecycleConfiguration = lifecycleConfiguration; }

    public String getAcl() { return acl; }
    public void setAcl(String acl) { this.acl = acl; }

    public String getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(String encryptionConfiguration) { this.encryptionConfiguration = encryptionConfiguration; }

    public String getPublicAccessBlockConfiguration() { return publicAccessBlockConfiguration; }
    public void setPublicAccessBlockConfiguration(String publicAccessBlockConfiguration) {
        this.publicAccessBlockConfiguration = publicAccessBlockConfiguration;
    }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
