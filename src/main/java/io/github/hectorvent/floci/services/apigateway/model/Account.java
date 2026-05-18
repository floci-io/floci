package io.github.hectorvent.floci.services.apigateway.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Account {

    private String apiKeyVersion = "4";
    private String cloudwatchRoleArn;
    private List<String> features = new ArrayList<>(List.of("UsagePlans"));
    private ThrottleSettings throttleSettings = new ThrottleSettings();

    public String getApiKeyVersion() {
        return apiKeyVersion;
    }

    public void setApiKeyVersion(String apiKeyVersion) {
        this.apiKeyVersion = apiKeyVersion;
    }

    public String getCloudwatchRoleArn() {
        return cloudwatchRoleArn;
    }

    public void setCloudwatchRoleArn(String cloudwatchRoleArn) {
        this.cloudwatchRoleArn = cloudwatchRoleArn;
    }

    public List<String> getFeatures() {
        return features;
    }

    public void setFeatures(List<String> features) {
        this.features = features != null ? features : new ArrayList<>();
    }

    public ThrottleSettings getThrottleSettings() {
        return throttleSettings;
    }

    public void setThrottleSettings(ThrottleSettings throttleSettings) {
        this.throttleSettings = throttleSettings != null ? throttleSettings : new ThrottleSettings();
    }
}
