package io.github.hectorvent.floci.services.elasticbeanstalk.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BeanstalkEnvironment {

    private String applicationName;
    private String environmentId;
    private String environmentName;
    private String environmentArn;
    private String description;
    private String cname;
    private String endpointUrl;
    private String solutionStackName;
    private String platformArn;
    private String templateName;
    private String versionLabel;
    private String status;
    private String health;
    private String healthStatus;
    private Instant dateCreated;
    private Instant dateUpdated;
    private final List<ConfigurationOptionSetting> optionSettings = new ArrayList<>();
    private final Map<String, String> tags = new LinkedHashMap<>();

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getEnvironmentName() {
        return environmentName;
    }

    public void setEnvironmentName(String environmentName) {
        this.environmentName = environmentName;
    }

    public String getEnvironmentArn() {
        return environmentArn;
    }

    public void setEnvironmentArn(String environmentArn) {
        this.environmentArn = environmentArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCname() {
        return cname;
    }

    public void setCname(String cname) {
        this.cname = cname;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getSolutionStackName() {
        return solutionStackName;
    }

    public void setSolutionStackName(String solutionStackName) {
        this.solutionStackName = solutionStackName;
    }

    public String getPlatformArn() {
        return platformArn;
    }

    public void setPlatformArn(String platformArn) {
        this.platformArn = platformArn;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getVersionLabel() {
        return versionLabel;
    }

    public void setVersionLabel(String versionLabel) {
        this.versionLabel = versionLabel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getHealth() {
        return health;
    }

    public void setHealth(String health) {
        this.health = health;
    }

    public String getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(String healthStatus) {
        this.healthStatus = healthStatus;
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

    public List<ConfigurationOptionSetting> getOptionSettings() {
        return optionSettings;
    }

    public Map<String, String> getTags() {
        return tags;
    }
}
