package io.github.hectorvent.floci.services.medialive.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public class MediaLiveMultiplex {

    private String id;
    private String arn;
    private String name;
    private List<String> availabilityZones;
    private JsonNode multiplexSettings;
    private String state;
    private Map<String, String> tags;
    private String accountId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getAvailabilityZones() {
        return availabilityZones;
    }

    public void setAvailabilityZones(List<String> availabilityZones) {
        this.availabilityZones = availabilityZones;
    }

    public JsonNode getMultiplexSettings() {
        return multiplexSettings;
    }

    public void setMultiplexSettings(JsonNode multiplexSettings) {
        this.multiplexSettings = multiplexSettings;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
