package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigurationSession {
    private String id;
    private String applicationId;
    private String environmentId;
    private String configurationProfileId;
    private int requiredMinimumPollIntervalInSeconds = 15;
    private String currentToken;
    private String lastConfigurationVersion;

    public ConfigurationSession() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }

    public String getConfigurationProfileId() { return configurationProfileId; }
    public void setConfigurationProfileId(String configurationProfileId) { this.configurationProfileId = configurationProfileId; }

    public int getRequiredMinimumPollIntervalInSeconds() { return requiredMinimumPollIntervalInSeconds; }
    public void setRequiredMinimumPollIntervalInSeconds(int requiredMinimumPollIntervalInSeconds) { this.requiredMinimumPollIntervalInSeconds = requiredMinimumPollIntervalInSeconds; }

    public String getCurrentToken() { return currentToken; }
    public void setCurrentToken(String currentToken) { this.currentToken = currentToken; }

    public String getLastConfigurationVersion() { return lastConfigurationVersion; }
    public void setLastConfigurationVersion(String lastConfigurationVersion) { this.lastConfigurationVersion = lastConfigurationVersion; }
}
