package io.github.hectorvent.floci.services.swf.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A registered workflow type and its registration-time defaults.
 *
 * <p>The {@code default*} fields are echoed back verbatim by
 * DescribeWorkflowType and supply the fallback for any field a
 * StartWorkflowExecution call omits.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SwfWorkflowType {

    private String domain;
    private String name;
    private String version;
    private String description;
    private String status = SwfConstants.STATUS_REGISTERED;
    private double creationDate;
    private Double deprecationDate;

    private String defaultTaskStartToCloseTimeout;
    private String defaultExecutionStartToCloseTimeout;
    private String defaultTaskList;
    private String defaultTaskPriority;
    private String defaultChildPolicy;
    private String defaultLambdaRole;

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

    public String getDefaultExecutionStartToCloseTimeout() {
        return defaultExecutionStartToCloseTimeout;
    }

    public void setDefaultExecutionStartToCloseTimeout(String defaultExecutionStartToCloseTimeout) {
        this.defaultExecutionStartToCloseTimeout = defaultExecutionStartToCloseTimeout;
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

    public String getDefaultChildPolicy() {
        return defaultChildPolicy;
    }

    public void setDefaultChildPolicy(String defaultChildPolicy) {
        this.defaultChildPolicy = defaultChildPolicy;
    }

    public String getDefaultLambdaRole() {
        return defaultLambdaRole;
    }

    public void setDefaultLambdaRole(String defaultLambdaRole) {
        this.defaultLambdaRole = defaultLambdaRole;
    }

    public boolean isDeprecated() {
        return SwfConstants.STATUS_DEPRECATED.equals(status);
    }
}
