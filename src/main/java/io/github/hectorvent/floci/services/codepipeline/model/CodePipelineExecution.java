package io.github.hectorvent.floci.services.codepipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodePipelineExecution {
    public CodePipelineExecution() {
    }

    private String accountId;
    private String region;
    private String pipelineExecutionId;
    private String pipelineName;
    private Integer pipelineVersion;
    private String status;
    private String statusSummary;
    private String executionMode;
    private String executionType;
    private Double startTime;
    private Double lastUpdateTime;
    private List<Map<String, Object>> artifactRevisions = new ArrayList<>();
    private List<Map<String, Object>> sourceRevisions = new ArrayList<>();
    private List<Map<String, String>> variables = new ArrayList<>();
    private Map<String, String> trigger = new LinkedHashMap<>();
    private List<ActionExecution> actionExecutions = new ArrayList<>();
    private String currentStage;
    private boolean stopRequested;
    private boolean abandon;
    private String rollbackTargetPipelineExecutionId;

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getPipelineExecutionId() {
        return pipelineExecutionId;
    }

    public void setPipelineExecutionId(String pipelineExecutionId) {
        this.pipelineExecutionId = pipelineExecutionId;
    }

    public String getPipelineName() {
        return pipelineName;
    }

    public void setPipelineName(String pipelineName) {
        this.pipelineName = pipelineName;
    }

    public Integer getPipelineVersion() {
        return pipelineVersion;
    }

    public void setPipelineVersion(Integer pipelineVersion) {
        this.pipelineVersion = pipelineVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusSummary() {
        return statusSummary;
    }

    public void setStatusSummary(String statusSummary) {
        this.statusSummary = statusSummary;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getExecutionType() {
        return executionType;
    }

    public void setExecutionType(String executionType) {
        this.executionType = executionType;
    }

    public Double getStartTime() {
        return startTime;
    }

    public void setStartTime(Double startTime) {
        this.startTime = startTime;
    }

    public Double getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(Double lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    public List<Map<String, Object>> getArtifactRevisions() {
        return artifactRevisions;
    }

    public void setArtifactRevisions(List<Map<String, Object>> artifactRevisions) {
        this.artifactRevisions = artifactRevisions;
    }

    public List<Map<String, Object>> getSourceRevisions() {
        return sourceRevisions;
    }

    public void setSourceRevisions(List<Map<String, Object>> sourceRevisions) {
        this.sourceRevisions = sourceRevisions;
    }

    public List<Map<String, String>> getVariables() {
        return variables;
    }

    public void setVariables(List<Map<String, String>> variables) {
        this.variables = variables;
    }

    public Map<String, String> getTrigger() {
        return trigger;
    }

    public void setTrigger(Map<String, String> trigger) {
        this.trigger = trigger;
    }

    public List<ActionExecution> getActionExecutions() {
        return actionExecutions;
    }

    public void setActionExecutions(List<ActionExecution> actionExecutions) {
        this.actionExecutions = actionExecutions;
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(String currentStage) {
        this.currentStage = currentStage;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    public void setStopRequested(boolean stopRequested) {
        this.stopRequested = stopRequested;
    }

    public boolean isAbandon() {
        return abandon;
    }

    public void setAbandon(boolean abandon) {
        this.abandon = abandon;
    }

    public String getRollbackTargetPipelineExecutionId() {
        return rollbackTargetPipelineExecutionId;
    }

    public void setRollbackTargetPipelineExecutionId(String rollbackTargetPipelineExecutionId) {
        this.rollbackTargetPipelineExecutionId = rollbackTargetPipelineExecutionId;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ActionExecution {
        public ActionExecution() {
        }

        private String actionExecutionId;
        private String stageName;
        private String actionName;
        private String category;
        private String owner;
        private String provider;
        private Integer runOrder;
        private String status;
        private Double startTime;
        private Double lastUpdateTime;
        private String summary;
        private String externalExecutionId;
        private String externalExecutionUrl;
        private String token;
        private Map<String, String> outputVariables = new LinkedHashMap<>();
        private Map<String, Object> errorDetails;

        public String getActionExecutionId() {
            return actionExecutionId;
        }

        public void setActionExecutionId(String actionExecutionId) {
            this.actionExecutionId = actionExecutionId;
        }

        public String getStageName() {
            return stageName;
        }

        public void setStageName(String stageName) {
            this.stageName = stageName;
        }

        public String getActionName() {
            return actionName;
        }

        public void setActionName(String actionName) {
            this.actionName = actionName;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public Integer getRunOrder() {
            return runOrder;
        }

        public void setRunOrder(Integer runOrder) {
            this.runOrder = runOrder;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Double getStartTime() {
            return startTime;
        }

        public void setStartTime(Double startTime) {
            this.startTime = startTime;
        }

        public Double getLastUpdateTime() {
            return lastUpdateTime;
        }

        public void setLastUpdateTime(Double lastUpdateTime) {
            this.lastUpdateTime = lastUpdateTime;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public String getExternalExecutionId() {
            return externalExecutionId;
        }

        public void setExternalExecutionId(String externalExecutionId) {
            this.externalExecutionId = externalExecutionId;
        }

        public String getExternalExecutionUrl() {
            return externalExecutionUrl;
        }

        public void setExternalExecutionUrl(String externalExecutionUrl) {
            this.externalExecutionUrl = externalExecutionUrl;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public Map<String, String> getOutputVariables() {
            return outputVariables;
        }

        public void setOutputVariables(Map<String, String> outputVariables) {
            this.outputVariables = outputVariables;
        }

        public Map<String, Object> getErrorDetails() {
            return errorDetails;
        }

        public void setErrorDetails(Map<String, Object> errorDetails) {
            this.errorDetails = errorDetails;
        }
    }
}
