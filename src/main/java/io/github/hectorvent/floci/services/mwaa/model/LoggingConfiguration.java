package io.github.hectorvent.floci.services.mwaa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoggingConfiguration {

    @JsonProperty("DagProcessingLogs")
    private ModuleLoggingConfiguration dagProcessingLogs;

    @JsonProperty("SchedulerLogs")
    private ModuleLoggingConfiguration schedulerLogs;

    @JsonProperty("WebserverLogs")
    private ModuleLoggingConfiguration webserverLogs;

    @JsonProperty("WorkerLogs")
    private ModuleLoggingConfiguration workerLogs;

    @JsonProperty("TaskLogs")
    private ModuleLoggingConfiguration taskLogs;

    public LoggingConfiguration() {}

    public ModuleLoggingConfiguration getDagProcessingLogs() { return dagProcessingLogs; }
    public void setDagProcessingLogs(ModuleLoggingConfiguration dagProcessingLogs) { this.dagProcessingLogs = dagProcessingLogs; }

    public ModuleLoggingConfiguration getSchedulerLogs() { return schedulerLogs; }
    public void setSchedulerLogs(ModuleLoggingConfiguration schedulerLogs) { this.schedulerLogs = schedulerLogs; }

    public ModuleLoggingConfiguration getWebserverLogs() { return webserverLogs; }
    public void setWebserverLogs(ModuleLoggingConfiguration webserverLogs) { this.webserverLogs = webserverLogs; }

    public ModuleLoggingConfiguration getWorkerLogs() { return workerLogs; }
    public void setWorkerLogs(ModuleLoggingConfiguration workerLogs) { this.workerLogs = workerLogs; }

    public ModuleLoggingConfiguration getTaskLogs() { return taskLogs; }
    public void setTaskLogs(ModuleLoggingConfiguration taskLogs) { this.taskLogs = taskLogs; }
}
