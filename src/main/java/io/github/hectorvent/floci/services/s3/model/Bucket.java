package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Bucket {

    private String name;
    private Instant creationDate;
    private String versioningStatus; // null (never enabled), "Enabled", "Suspended"
    private String loggingConfiguration;
    private Map<String, String> tags;
    private NotificationConfiguration notificationConfiguration;
    private boolean objectLockEnabled;
    private ObjectLockRetention defaultRetention; // null if no default rule
    private String policy;
    private String corsConfiguration;
    private String lifecycleConfiguration;
    private String transitionDefaultMinimumObjectSize; // x-amz-transition-default-minimum-object-size header value
    private String acl; // XML representation or JSON stub
    private String encryptionConfiguration; // XML string
    private String publicAccessBlockConfiguration; // XML string
    private String ownershipControlsConfiguration; // XML string
    private String requestPaymentPayer; // "BucketOwner" (default) or "Requester"; null until first PUT
    private String region;
    private WebsiteConfiguration websiteConfiguration;
    // Per-bucket configuration sub-resources, keyed by their configuration id.
    // Values are the raw XML documents supplied on PUT so they round-trip verbatim.
    private Map<String, String> inventoryConfigurations = new LinkedHashMap<>();
    private Map<String, String> analyticsConfigurations = new LinkedHashMap<>();
    private Map<String, String> metricsConfigurations = new LinkedHashMap<>();
    private Map<String, String> intelligentTieringConfigurations = new LinkedHashMap<>();

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

    public String getLoggingConfiguration() {
        return loggingConfiguration;
    }

    public void setLoggingConfiguration(String loggingConfiguration) {
        this.loggingConfiguration = loggingConfiguration;
    }

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

    public String getTransitionDefaultMinimumObjectSize() { return transitionDefaultMinimumObjectSize; }
    public void setTransitionDefaultMinimumObjectSize(String transitionDefaultMinimumObjectSize) {
        this.transitionDefaultMinimumObjectSize = transitionDefaultMinimumObjectSize;
    }

    public String getAcl() { return acl; }
    public void setAcl(String acl) { this.acl = acl; }

    public String getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(String encryptionConfiguration) { this.encryptionConfiguration = encryptionConfiguration; }

    public String getPublicAccessBlockConfiguration() { return publicAccessBlockConfiguration; }
    public void setPublicAccessBlockConfiguration(String publicAccessBlockConfiguration) {
        this.publicAccessBlockConfiguration = publicAccessBlockConfiguration;
    }

    public String getOwnershipControlsConfiguration() { return ownershipControlsConfiguration; }
    public void setOwnershipControlsConfiguration(String ownershipControlsConfiguration) {
        this.ownershipControlsConfiguration = ownershipControlsConfiguration;
    }

    public String getRequestPaymentPayer() { return requestPaymentPayer; }
    public void setRequestPaymentPayer(String requestPaymentPayer) { this.requestPaymentPayer = requestPaymentPayer; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public WebsiteConfiguration getWebsiteConfiguration() { return websiteConfiguration; }
    public void setWebsiteConfiguration(WebsiteConfiguration websiteConfiguration) { this.websiteConfiguration = websiteConfiguration; }

    public Map<String, String> getInventoryConfigurations() { return inventoryConfigurations; }
    public void setInventoryConfigurations(Map<String, String> inventoryConfigurations) {
        this.inventoryConfigurations = inventoryConfigurations != null ? inventoryConfigurations : new LinkedHashMap<>();
    }

    public Map<String, String> getAnalyticsConfigurations() { return analyticsConfigurations; }
    public void setAnalyticsConfigurations(Map<String, String> analyticsConfigurations) {
        this.analyticsConfigurations = analyticsConfigurations != null ? analyticsConfigurations : new LinkedHashMap<>();
    }

    public Map<String, String> getMetricsConfigurations() { return metricsConfigurations; }
    public void setMetricsConfigurations(Map<String, String> metricsConfigurations) {
        this.metricsConfigurations = metricsConfigurations != null ? metricsConfigurations : new LinkedHashMap<>();
    }

    public Map<String, String> getIntelligentTieringConfigurations() { return intelligentTieringConfigurations; }
    public void setIntelligentTieringConfigurations(Map<String, String> intelligentTieringConfigurations) {
        this.intelligentTieringConfigurations =
                intelligentTieringConfigurations != null ? intelligentTieringConfigurations : new LinkedHashMap<>();
    }
}
