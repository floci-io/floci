package io.github.hectorvent.floci.services.cloudtrail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudTrailTrail {
    private String name;
    private String trailArn;
    private String s3BucketName;
    private boolean includeGlobalServiceEvents;
    private boolean multiRegionTrail;
    private boolean organizationTrail;
    private boolean logging;
    private String homeRegion;
    private Instant created;
    private Instant updated;
    private Map<String, String> tags;

    public CloudTrailTrail() {
        this.tags = new HashMap<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTrailArn() {
        return trailArn;
    }

    public void setTrailArn(String trailArn) {
        this.trailArn = trailArn;
    }

    public String getS3BucketName() {
        return s3BucketName;
    }

    public void setS3BucketName(String s3BucketName) {
        this.s3BucketName = s3BucketName;
    }

    public boolean isIncludeGlobalServiceEvents() {
        return includeGlobalServiceEvents;
    }

    public void setIncludeGlobalServiceEvents(boolean includeGlobalServiceEvents) {
        this.includeGlobalServiceEvents = includeGlobalServiceEvents;
    }

    public boolean isMultiRegionTrail() {
        return multiRegionTrail;
    }

    public void setMultiRegionTrail(boolean multiRegionTrail) {
        this.multiRegionTrail = multiRegionTrail;
    }

    public boolean isOrganizationTrail() {
        return organizationTrail;
    }

    public void setOrganizationTrail(boolean organizationTrail) {
        this.organizationTrail = organizationTrail;
    }

    public boolean isLogging() {
        return logging;
    }

    public void setLogging(boolean logging) {
        this.logging = logging;
    }

    public String getHomeRegion() {
        return homeRegion;
    }

    public void setHomeRegion(String homeRegion) {
        this.homeRegion = homeRegion;
    }

    public Instant getCreated() {
        return created;
    }

    public void setCreated(Instant created) {
        this.created = created;
    }

    public Instant getUpdated() {
        return updated;
    }

    public void setUpdated(Instant updated) {
        this.updated = updated;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new HashMap<>() : new HashMap<>(tags);
    }
}
