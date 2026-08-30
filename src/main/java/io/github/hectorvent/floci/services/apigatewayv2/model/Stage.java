package io.github.hectorvent.floci.services.apigatewayv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Stage {
    private String stageName;
    private String deploymentId;
    private boolean autoDeploy;
    private long createdDate;
    private long lastUpdatedDate;
    private Map<String, String> stageVariables;
    private Map<String, String> tags = new HashMap<>();
    private String description;
    private AccessLogSettings accessLogSettings;
    private RouteSettings defaultRouteSettings;
    /** Per-route overrides, keyed by route key (e.g. "POST /orders"). */
    private Map<String, RouteSettings> routeSettings;

    public Stage() {}

    public String getStageName() { return stageName; }
    public void setStageName(String stageName) { this.stageName = stageName; }

    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }

    public boolean isAutoDeploy() { return autoDeploy; }
    public void setAutoDeploy(boolean autoDeploy) { this.autoDeploy = autoDeploy; }

    public long getCreatedDate() { return createdDate; }
    public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

    public long getLastUpdatedDate() { return lastUpdatedDate; }
    public void setLastUpdatedDate(long lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }

    public Map<String, String> getStageVariables() { return stageVariables; }
    public void setStageVariables(Map<String, String> stageVariables) { this.stageVariables = stageVariables; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }


    public AccessLogSettings getAccessLogSettings() { return accessLogSettings; }
    public void setAccessLogSettings(AccessLogSettings accessLogSettings) { this.accessLogSettings = accessLogSettings; }

    public RouteSettings getDefaultRouteSettings() { return defaultRouteSettings; }
    public void setDefaultRouteSettings(RouteSettings defaultRouteSettings) { this.defaultRouteSettings = defaultRouteSettings; }

    public Map<String, RouteSettings> getRouteSettings() { return routeSettings; }
    public void setRouteSettings(Map<String, RouteSettings> routeSettings) { this.routeSettings = routeSettings; }

    /** CloudWatch Logs destination and format for stage access logging. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessLogSettings(String destinationArn, String format) {}

    /**
     * Throttling and logging knobs, used both as a stage's defaults and as a per-route override.
     * All fields are boxed: AWS omits unset ones rather than defaulting them, and Terraform treats
     * an absent value differently from an explicit zero.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteSettings(
            Boolean detailedMetricsEnabled,
            Boolean dataTraceEnabled,
            String loggingLevel,
            Integer throttlingBurstLimit,
            Double throttlingRateLimit) {}
}
