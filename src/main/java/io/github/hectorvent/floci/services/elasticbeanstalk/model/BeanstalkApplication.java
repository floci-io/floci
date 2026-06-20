package io.github.hectorvent.floci.services.elasticbeanstalk.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BeanstalkApplication {

    private String applicationName;
    private String applicationArn;
    private String description;
    private Instant dateCreated;
    private Instant dateUpdated;
    private final List<String> versions = new ArrayList<>();
    private final List<String> configurationTemplates = new ArrayList<>();
    private final Map<String, String> tags = new LinkedHashMap<>();

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getApplicationArn() {
        return applicationArn;
    }

    public void setApplicationArn(String applicationArn) {
        this.applicationArn = applicationArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public List<String> getVersions() {
        return versions;
    }

    public List<String> getConfigurationTemplates() {
        return configurationTemplates;
    }

    public Map<String, String> getTags() {
        return tags;
    }
}
