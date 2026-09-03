package io.github.hectorvent.floci.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Build {
    public Build() {}

    private String id;
    private String arn;
    private Long buildNumber;
    private String buildStatus;
    private Boolean buildComplete;
    private String currentPhase;
    private String projectName;
    private String initiator;
    private Double startTime;
    private Double endTime;
    private ProjectSource source;
    private List<ProjectSource> secondarySources;
    private ProjectArtifacts artifacts;
    private ProjectEnvironment environment;
    private Map<String, Object> logs;
    private List<BuildPhase> phases;
    private Integer timeoutInMinutes;
    private Integer queuedTimeoutInMinutes;
    private String encryptionKey;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Long getBuildNumber() { return buildNumber; }
    public void setBuildNumber(Long buildNumber) { this.buildNumber = buildNumber; }

    public String getBuildStatus() { return buildStatus; }
    public void setBuildStatus(String buildStatus) { this.buildStatus = buildStatus; }

    public Boolean getBuildComplete() { return buildComplete; }
    public void setBuildComplete(Boolean buildComplete) { this.buildComplete = buildComplete; }

    public String getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(String currentPhase) { this.currentPhase = currentPhase; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getInitiator() { return initiator; }
    public void setInitiator(String initiator) { this.initiator = initiator; }

    public Double getStartTime() { return startTime; }
    public void setStartTime(Double startTime) { this.startTime = startTime; }

    public Double getEndTime() { return endTime; }
    public void setEndTime(Double endTime) { this.endTime = endTime; }

    public ProjectSource getSource() { return source; }
    public void setSource(ProjectSource source) { this.source = source; }

    public List<ProjectSource> getSecondarySources() { return secondarySources; }
    public void setSecondarySources(List<ProjectSource> secondarySources) { this.secondarySources = secondarySources; }

    public ProjectArtifacts getArtifacts() { return artifacts; }
    public void setArtifacts(ProjectArtifacts artifacts) { this.artifacts = artifacts; }

    public ProjectEnvironment getEnvironment() { return environment; }
    public void setEnvironment(ProjectEnvironment environment) { this.environment = environment; }

    public Map<String, Object> getLogs() { return logs; }
    public void setLogs(Map<String, Object> logs) { this.logs = logs; }

    public List<BuildPhase> getPhases() { return phases; }
    public void setPhases(List<BuildPhase> phases) { this.phases = phases; }

    public Integer getTimeoutInMinutes() { return timeoutInMinutes; }
    public void setTimeoutInMinutes(Integer timeoutInMinutes) { this.timeoutInMinutes = timeoutInMinutes; }

    public Integer getQueuedTimeoutInMinutes() { return queuedTimeoutInMinutes; }
    public void setQueuedTimeoutInMinutes(Integer queuedTimeoutInMinutes) { this.queuedTimeoutInMinutes = queuedTimeoutInMinutes; }

    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
}
