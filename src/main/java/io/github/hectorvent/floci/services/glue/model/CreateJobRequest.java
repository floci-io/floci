package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public class CreateJobRequest {
    @JsonProperty("AllocatedCapacity")
    private Integer allocatedCapacity;

    @JsonProperty("CodeGenConfigurationNodes")
    private Map<String, JsonNode> codeGenConfigurationNodes;

    @JsonProperty("Command")
    private JobCommand command;

    @JsonProperty("Connections")
    private ConnectionsList connections;

    @JsonProperty("DefaultArguments")
    private Map<String, String> defaultArguments;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("ExecutionClass")
    private String executionClass;

    @JsonProperty("ExecutionProperty")
    private ExecutionProperty executionProperty;

    @JsonProperty("GlueVersion")
    private String glueVersion;

    @JsonProperty("JobMode")
    private String jobMode;

    @JsonProperty("JobRunQueuingEnabled")
    private Boolean jobRunQueuingEnabled;

    @JsonProperty("LogUri")
    private String logUri;

    @JsonProperty("MaintenanceWindow")
    private String maintenanceWindow;

    @JsonProperty("MaxCapacity")
    private Double maxCapacity;

    @JsonProperty("MaxRetries")
    private Integer maxRetries;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("NonOverridableArguments")
    private Map<String, String> nonOverridableArguments;

    @JsonProperty("NotificationProperty")
    private NotificationProperty notificationProperty;

    @JsonProperty("NumberOfWorkers")
    private Integer numberOfWorkers;

    @JsonProperty("Role")
    private String role;

    @JsonProperty("SecurityConfiguration")
    private String securityConfiguration;

    @JsonProperty("SourceControlDetails")
    private SourceControlDetails sourceControlDetails;

    @JsonProperty("Tags")
    private Map<String, String> tags;

    @JsonProperty("Timeout")
    private Integer timeout;

    @JsonProperty("WorkerType")
    private String workerType;

    public CreateJobRequest() {}

    public Integer getAllocatedCapacity() { return allocatedCapacity; }
    public void setAllocatedCapacity(Integer allocatedCapacity) { this.allocatedCapacity = allocatedCapacity; }

    public Map<String, JsonNode> getCodeGenConfigurationNodes() { return codeGenConfigurationNodes; }
    public void setCodeGenConfigurationNodes(Map<String, JsonNode> codeGenConfigurationNodes) { this.codeGenConfigurationNodes = codeGenConfigurationNodes; }

    public JobCommand getCommand() { return command; }
    public void setCommand(JobCommand command) { this.command = command; }

    public ConnectionsList getConnections() { return connections; }
    public void setConnections(ConnectionsList connections) { this.connections = connections; }

    public Map<String, String> getDefaultArguments() { return defaultArguments; }
    public void setDefaultArguments(Map<String, String> defaultArguments) { this.defaultArguments = defaultArguments; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExecutionClass() { return executionClass; }
    public void setExecutionClass(String executionClass) { this.executionClass = executionClass; }

    public ExecutionProperty getExecutionProperty() { return executionProperty; }
    public void setExecutionProperty(ExecutionProperty executionProperty) { this.executionProperty = executionProperty; }

    public String getGlueVersion() { return glueVersion; }
    public void setGlueVersion(String glueVersion) { this.glueVersion = glueVersion; }

    public String getJobMode() { return jobMode; }
    public void setJobMode(String jobMode) { this.jobMode = jobMode; }

    public Boolean getJobRunQueuingEnabled() { return jobRunQueuingEnabled; }
    public void setJobRunQueuingEnabled(Boolean jobRunQueuingEnabled) { this.jobRunQueuingEnabled = jobRunQueuingEnabled; }

    public String getLogUri() { return logUri; }
    public void setLogUri(String logUri) { this.logUri = logUri; }

    public String getMaintenanceWindow() { return maintenanceWindow; }
    public void setMaintenanceWindow(String maintenanceWindow) { this.maintenanceWindow = maintenanceWindow; }

    public Double getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(Double maxCapacity) { this.maxCapacity = maxCapacity; }

    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Map<String, String> getNonOverridableArguments() { return nonOverridableArguments; }
    public void setNonOverridableArguments(Map<String, String> nonOverridableArguments) { this.nonOverridableArguments = nonOverridableArguments; }

    public NotificationProperty getNotificationProperty() { return notificationProperty; }
    public void setNotificationProperty(NotificationProperty notificationProperty) { this.notificationProperty = notificationProperty; }

    public Integer getNumberOfWorkers() { return numberOfWorkers; }
    public void setNumberOfWorkers(Integer numberOfWorkers) { this.numberOfWorkers = numberOfWorkers; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getSecurityConfiguration() { return securityConfiguration; }
    public void setSecurityConfiguration(String securityConfiguration) { this.securityConfiguration = securityConfiguration; }

    public SourceControlDetails getSourceControlDetails() { return sourceControlDetails; }
    public void setSourceControlDetails(SourceControlDetails sourceControlDetails) { this.sourceControlDetails = sourceControlDetails; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Integer getTimeout() { return timeout; }
    public void setTimeout(Integer timeout) { this.timeout = timeout; }

    public String getWorkerType() { return workerType; }
    public void setWorkerType(String workerType) { this.workerType = workerType; }
}
