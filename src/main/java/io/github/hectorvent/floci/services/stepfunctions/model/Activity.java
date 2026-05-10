package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Activity {
    private String activityArn;
    private String name;
    private double creationDate;
    private Map<String, String> tags = new HashMap<>();

    public Activity() {
        this.creationDate = System.currentTimeMillis() / 1000.0;
    }

    public String getActivityArn() { return activityArn; }
    public void setActivityArn(String activityArn) { this.activityArn = activityArn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getCreationDate() { return creationDate; }
    public void setCreationDate(double creationDate) { this.creationDate = creationDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
