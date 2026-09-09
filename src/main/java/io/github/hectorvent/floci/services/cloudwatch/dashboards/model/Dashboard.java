package io.github.hectorvent.floci.services.cloudwatch.dashboards.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A CloudWatch dashboard. AWS stores the body as an opaque JSON document and returns it
 * verbatim, so nothing here interprets the widget structure.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Dashboard {

    private String dashboardName;
    private String dashboardArn;
    private String dashboardBody;
    private long lastModified;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Dashboard() {
        this.lastModified = Instant.now().getEpochSecond();
    }

    public Dashboard(String dashboardName, String dashboardArn, String dashboardBody) {
        this();
        this.dashboardName = dashboardName;
        this.dashboardArn = dashboardArn;
        this.dashboardBody = dashboardBody;
    }

    public String getDashboardName() { return dashboardName; }
    public void setDashboardName(String dashboardName) { this.dashboardName = dashboardName; }

    public String getDashboardArn() { return dashboardArn; }
    public void setDashboardArn(String dashboardArn) { this.dashboardArn = dashboardArn; }

    public String getDashboardBody() { return dashboardBody; }
    public void setDashboardBody(String dashboardBody) { this.dashboardBody = dashboardBody; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new LinkedHashMap<>(); }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    /** Byte length of the body, which is what ListDashboards reports as Size. */
    public long getSize() {
        return dashboardBody == null ? 0 : dashboardBody.getBytes(StandardCharsets.UTF_8).length;
    }
}
