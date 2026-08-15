package io.github.hectorvent.floci.services.datasync.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A DataSync transfer location. {@code configuration} holds the create request with its
 * credential members stripped and the service's documented defaults applied; every
 * {@code DescribeLocation*} answer is projected out of it.
 */
public class DataSyncLocation implements DataSyncTaggable {

    private String locationArn;
    private DataSyncLocationType locationType;
    private String locationUri;
    private String region;
    private JsonNode configuration;
    private Instant creationTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getLocationArn() {
        return locationArn;
    }

    public void setLocationArn(String locationArn) {
        this.locationArn = locationArn;
    }

    public DataSyncLocationType getLocationType() {
        return locationType;
    }

    public void setLocationType(DataSyncLocationType locationType) {
        this.locationType = locationType;
    }

    public String getLocationUri() {
        return locationUri;
    }

    public void setLocationUri(String locationUri) {
        this.locationUri = locationUri;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public JsonNode getConfiguration() {
        return configuration;
    }

    public void setConfiguration(JsonNode configuration) {
        this.configuration = configuration;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Instant creationTime) {
        this.creationTime = creationTime;
    }

    @Override
    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    @Override
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
