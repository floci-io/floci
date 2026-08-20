package io.github.hectorvent.floci.services.mwaa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateEnvironmentRequest {

    @JsonProperty("ExecutionRoleArn")
    private String executionRoleArn;

    @JsonProperty("SourceBucketArn")
    private String sourceBucketArn;

    @JsonProperty("DagS3Path")
    private String dagS3Path;

    @JsonProperty("PluginsS3Path")
    private String pluginsS3Path;

    @JsonProperty("RequirementsS3Path")
    private String requirementsS3Path;

    @JsonProperty("StartupScriptS3Path")
    private String startupScriptS3Path;

    @JsonProperty("NetworkConfiguration")
    private NetworkConfiguration networkConfiguration;

    @JsonProperty("LoggingConfiguration")
    private LoggingConfiguration loggingConfiguration;

    @JsonProperty("AirflowConfigurationOptions")
    private Map<String, String> airflowConfigurationOptions;

    @JsonProperty("EnvironmentClass")
    private String environmentClass;

    @JsonProperty("AirflowVersion")
    private String airflowVersion;

    @JsonProperty("WebserverAccessMode")
    private String webserverAccessMode;

    @JsonProperty("MaxWorkers")
    private Integer maxWorkers;

    @JsonProperty("MinWorkers")
    private Integer minWorkers;

    @JsonProperty("Schedulers")
    private Integer schedulers;

    public UpdateEnvironmentRequest() {}

    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }

    public String getSourceBucketArn() { return sourceBucketArn; }
    public void setSourceBucketArn(String sourceBucketArn) { this.sourceBucketArn = sourceBucketArn; }

    public String getDagS3Path() { return dagS3Path; }
    public void setDagS3Path(String dagS3Path) { this.dagS3Path = dagS3Path; }

    public String getPluginsS3Path() { return pluginsS3Path; }
    public void setPluginsS3Path(String pluginsS3Path) { this.pluginsS3Path = pluginsS3Path; }

    public String getRequirementsS3Path() { return requirementsS3Path; }
    public void setRequirementsS3Path(String requirementsS3Path) { this.requirementsS3Path = requirementsS3Path; }

    public String getStartupScriptS3Path() { return startupScriptS3Path; }
    public void setStartupScriptS3Path(String startupScriptS3Path) { this.startupScriptS3Path = startupScriptS3Path; }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) { this.networkConfiguration = networkConfiguration; }

    public LoggingConfiguration getLoggingConfiguration() { return loggingConfiguration; }
    public void setLoggingConfiguration(LoggingConfiguration loggingConfiguration) { this.loggingConfiguration = loggingConfiguration; }

    public Map<String, String> getAirflowConfigurationOptions() { return airflowConfigurationOptions; }
    public void setAirflowConfigurationOptions(Map<String, String> airflowConfigurationOptions) { this.airflowConfigurationOptions = airflowConfigurationOptions; }

    public String getEnvironmentClass() { return environmentClass; }
    public void setEnvironmentClass(String environmentClass) { this.environmentClass = environmentClass; }

    public String getAirflowVersion() { return airflowVersion; }
    public void setAirflowVersion(String airflowVersion) { this.airflowVersion = airflowVersion; }

    public String getWebserverAccessMode() { return webserverAccessMode; }
    public void setWebserverAccessMode(String webserverAccessMode) { this.webserverAccessMode = webserverAccessMode; }

    public Integer getMaxWorkers() { return maxWorkers; }
    public void setMaxWorkers(Integer maxWorkers) { this.maxWorkers = maxWorkers; }

    public Integer getMinWorkers() { return minWorkers; }
    public void setMinWorkers(Integer minWorkers) { this.minWorkers = minWorkers; }

    public Integer getSchedulers() { return schedulers; }
    public void setSchedulers(Integer schedulers) { this.schedulers = schedulers; }
}
