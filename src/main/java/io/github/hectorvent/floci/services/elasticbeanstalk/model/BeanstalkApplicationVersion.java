package io.github.hectorvent.floci.services.elasticbeanstalk.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class BeanstalkApplicationVersion {

    private String applicationName;
    private String versionLabel;
    private String description;
    private String sourceBundleBucket;
    private String sourceBundleKey;
    private Instant dateCreated;
    private Instant dateUpdated;
    private String status;
    private final Map<String, String> tags = new LinkedHashMap<>();

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getVersionLabel() {
        return versionLabel;
    }

    public void setVersionLabel(String versionLabel) {
        this.versionLabel = versionLabel;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSourceBundleBucket() {
        return sourceBundleBucket;
    }

    public void setSourceBundleBucket(String sourceBundleBucket) {
        this.sourceBundleBucket = sourceBundleBucket;
    }

    public String getSourceBundleKey() {
        return sourceBundleKey;
    }

    public void setSourceBundleKey(String sourceBundleKey) {
        this.sourceBundleKey = sourceBundleKey;
    }

    public Instant getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Instant dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Instant getDateUpdated() {
        return dateUpdated;
    }

    public void setDateUpdated(Instant dateUpdated) {
        this.dateUpdated = dateUpdated;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, String> getTags() {
        return tags;
    }
}
