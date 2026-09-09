package io.github.hectorvent.floci.services.swf.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A registered activity type and its registration-time defaults.
 *
 * <p>Each {@code default*} timeout supplies the fallback for the matching field
 * a ScheduleActivityTask decision omits; when neither is present the decision
 * fails with the corresponding {@code DEFAULT_*_UNDEFINED} cause.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SwfActivityType {

    private String domain;
    private String name;
    private String version;
    private String description;
    private String status = SwfConstants.STATUS_REGISTERED;
    private double creationDate;
    private Double deprecationDate;

    private String defaultTaskStartToCloseTimeout;
    private String defaultTaskHeartbeatTimeout;
    private String defaultTaskList;
    private String defaultTaskPriority;
    private String defaultTaskScheduleToStartTimeout;
    private String defaultTaskScheduleToCloseTimeout;

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public double getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(double creationDate) {
        this.creationDate = creationDate;
    }

    public Double getDeprecationDate() {
        return deprecationDate;
    }

    public void setDeprecationDate(Double deprecationDate) {
        this.deprecationDate = deprecationDate;
    }

    public String getDefaultTaskStartToCloseTimeout() {
        return defaultTaskStartToCloseTimeout;
    }

    public void setDefaultTaskStartToCloseTimeout(String defaultTaskStartToCloseTimeout) {
        this.defaultTaskStartToCloseTimeout = defaultTaskStartToCloseTimeout;
    }

    public String getDefaultTaskHeartbeatTimeout() {
        return defaultTaskHeartbeatTimeout;
    }

    public void setDefaultTaskHeartbeatTimeout(String defaultTaskHeartbeatTimeout) {
        this.defaultTaskHeartbeatTimeout = defaultTaskHeartbeatTimeout;
    }

    public String getDefaultTaskList() {
        return defaultTaskList;
    }

    public void setDefaultTaskList(String defaultTaskList) {
        this.defaultTaskList = defaultTaskList;
    }

    public String getDefaultTaskPriority() {
        return defaultTaskPriority;
    }

    public void setDefaultTaskPriority(String defaultTaskPriority) {
        this.defaultTaskPriority = defaultTaskPriority;
    }

    public String getDefaultTaskScheduleToStartTimeout() {
        return defaultTaskScheduleToStartTimeout;
    }

    public void setDefaultTaskScheduleToStartTimeout(String defaultTaskScheduleToStartTimeout) {
        this.defaultTaskScheduleToStartTimeout = defaultTaskScheduleToStartTimeout;
    }

    public String getDefaultTaskScheduleToCloseTimeout() {
        return defaultTaskScheduleToCloseTimeout;
    }

    public void setDefaultTaskScheduleToCloseTimeout(String defaultTaskScheduleToCloseTimeout) {
        this.defaultTaskScheduleToCloseTimeout = defaultTaskScheduleToCloseTimeout;
    }

    public boolean isDeprecated() {
        return SwfConstants.STATUS_DEPRECATED.equals(status);
    }
}
