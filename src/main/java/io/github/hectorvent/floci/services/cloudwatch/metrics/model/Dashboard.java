package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.charset.StandardCharsets;

/**
 * A CloudWatch dashboard. The {@code body} is stored verbatim so that {@code GetDashboard}
 * returns the exact JSON string {@code PutDashboard} received.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Dashboard {

    private String name;
    private String arn;
    private String body;
    private long lastModified;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    /** Dashboard size in bytes, as CloudWatch reports it in {@code ListDashboards}. */
    public long size() {
        return body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
    }
}
