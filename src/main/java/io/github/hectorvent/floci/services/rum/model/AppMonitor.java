package io.github.hectorvent.floci.services.rum.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A CloudWatch RUM app monitor (minimal in-memory model). Serialized PascalCase to match the RUM API. */
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class AppMonitor {
    private String id;
    private String name;
    private String domain;
    private String state;
    private long created;

    public AppMonitor() {
    }

    public AppMonitor(String id, String name, String domain, String state, long created) {
        this.id = id;
        this.name = name;
        this.domain = domain;
        this.state = state;
        this.created = created;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }
}
