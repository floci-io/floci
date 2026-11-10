package io.floci.aws.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Project {
    public Project() {}

    private String name;
    private String arn;
    private String description;
    private ProjectSource source;
    private List<ProjectSource> secondarySources;
    private String sourceVersion;
    private ProjectArtifacts artifacts;
    private List<ProjectArtifacts> secondaryArtifacts;
    private ProjectEnvironment environment;
    private String serviceRole;
    private Integer timeoutInMinutes;
    private Integer queuedTimeoutInMinutes;
    private String encryptionKey;
    private List<Map<String, String>> tags;
    private Double created;
    private Double lastModified;
    private Map<String, Object> logsConfig;
    private Map<String, Object> vpcConfig;
    private Integer concurrentBuildLimit;
    private String projectVisibility;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ProjectSource getSource() { return source; }
    public void setSource(ProjectSource source) { this.source = source; }

    public List<ProjectSource> getSecondarySources() { return secondarySources; }
    public void setSecondarySources(List<ProjectSource> secondarySources) { this.secondarySources = secondarySources; }

    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }

    public ProjectArtifacts getArtifacts() { return artifacts; }
    public void setArtifacts(ProjectArtifacts artifacts) { this.artifacts = artifacts; }

    public List<ProjectArtifacts> getSecondaryArtifacts() { return secondaryArtifacts; }
    public void setSecondaryArtifacts(List<ProjectArtifacts> secondaryArtifacts) { this.secondaryArtifacts = secondaryArtifacts; }

    public ProjectEnvironment getEnvironment() { return environment; }
    public void setEnvironment(ProjectEnvironment environment) { this.environment = environment; }

    public String getServiceRole() { return serviceRole; }
    public void setServiceRole(String serviceRole) { this.serviceRole = serviceRole; }

    public Integer getTimeoutInMinutes() { return timeoutInMinutes; }
    public void setTimeoutInMinutes(Integer timeoutInMinutes) { this.timeoutInMinutes = timeoutInMinutes; }

    public Integer getQueuedTimeoutInMinutes() { return queuedTimeoutInMinutes; }
    public void setQueuedTimeoutInMinutes(Integer queuedTimeoutInMinutes) { this.queuedTimeoutInMinutes = queuedTimeoutInMinutes; }

    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }

    public List<Map<String, String>> getTags() { return tags; }
    public void setTags(List<Map<String, String>> tags) { this.tags = tags; }

    public Double getCreated() { return created; }
    public void setCreated(Double created) { this.created = created; }

    public Double getLastModified() { return lastModified; }
    public void setLastModified(Double lastModified) { this.lastModified = lastModified; }

    public Map<String, Object> getLogsConfig() { return logsConfig; }
    public void setLogsConfig(Map<String, Object> logsConfig) { this.logsConfig = logsConfig; }

    public Map<String, Object> getVpcConfig() { return vpcConfig; }
    public void setVpcConfig(Map<String, Object> vpcConfig) { this.vpcConfig = vpcConfig; }

    public Integer getConcurrentBuildLimit() { return concurrentBuildLimit; }
    public void setConcurrentBuildLimit(Integer concurrentBuildLimit) { this.concurrentBuildLimit = concurrentBuildLimit; }

    public String getProjectVisibility() { return projectVisibility; }
    public void setProjectVisibility(String projectVisibility) { this.projectVisibility = projectVisibility; }
}
