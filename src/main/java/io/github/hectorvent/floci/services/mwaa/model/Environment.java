package io.github.hectorvent.floci.services.mwaa.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Environment {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Arn")
    private String arn;

    @JsonProperty("CreatedAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;

    @JsonProperty("LastUpdate")
    private LastUpdate lastUpdate;

    @JsonProperty("Status")
    private EnvironmentStatus status;

    @JsonProperty("ExecutionRoleArn")
    private String executionRoleArn;

    @JsonProperty("AirflowVersion")
    private String airflowVersion;

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

    @JsonProperty("WebserverAccessMode")
    private String webserverAccessMode;

    @JsonProperty("WebserverUrl")
    private String webserverUrl;

    @JsonProperty("EnvironmentClass")
    private String environmentClass;

    @JsonProperty("AirflowConfigurationOptions")
    private Map<String, String> airflowConfigurationOptions;

    @JsonProperty("Tags")
    private Map<String, String> tags;

    // Echo-only fields for SDK compatibility; meaningless under LocalExecutor (single worker,
    // no autoscaling), but round-tripped so SDKs/CLIs that read them back don't choke.
    @JsonProperty("MaxWorkers")
    private Integer maxWorkers;

    @JsonProperty("MinWorkers")
    private Integer minWorkers;

    @JsonProperty("Schedulers")
    private Integer schedulers;

    @JsonIgnore
    private String accountId;

    @JsonIgnore
    private String dbContainerId;

    @JsonIgnore
    private String airflowContainerId;

    @JsonIgnore
    private String airflowInternalHost;

    @JsonIgnore
    private int airflowInternalPort;

    @JsonIgnore
    private int proxyPort;

    @JsonIgnore
    private String dbPassword;

    public Environment() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public LastUpdate getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(LastUpdate lastUpdate) { this.lastUpdate = lastUpdate; }

    public EnvironmentStatus getStatus() { return status; }
    public void setStatus(EnvironmentStatus status) { this.status = status; }

    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }

    public String getAirflowVersion() { return airflowVersion; }
    public void setAirflowVersion(String airflowVersion) { this.airflowVersion = airflowVersion; }

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

    public String getWebserverAccessMode() { return webserverAccessMode; }
    public void setWebserverAccessMode(String webserverAccessMode) { this.webserverAccessMode = webserverAccessMode; }

    public String getWebserverUrl() { return webserverUrl; }
    public void setWebserverUrl(String webserverUrl) { this.webserverUrl = webserverUrl; }

    public String getEnvironmentClass() { return environmentClass; }
    public void setEnvironmentClass(String environmentClass) { this.environmentClass = environmentClass; }

    public Map<String, String> getAirflowConfigurationOptions() { return airflowConfigurationOptions; }
    public void setAirflowConfigurationOptions(Map<String, String> airflowConfigurationOptions) { this.airflowConfigurationOptions = airflowConfigurationOptions; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Integer getMaxWorkers() { return maxWorkers; }
    public void setMaxWorkers(Integer maxWorkers) { this.maxWorkers = maxWorkers; }

    public Integer getMinWorkers() { return minWorkers; }
    public void setMinWorkers(Integer minWorkers) { this.minWorkers = minWorkers; }

    public Integer getSchedulers() { return schedulers; }
    public void setSchedulers(Integer schedulers) { this.schedulers = schedulers; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getDbContainerId() { return dbContainerId; }
    public void setDbContainerId(String dbContainerId) { this.dbContainerId = dbContainerId; }

    public String getAirflowContainerId() { return airflowContainerId; }
    public void setAirflowContainerId(String airflowContainerId) { this.airflowContainerId = airflowContainerId; }

    public String getAirflowInternalHost() { return airflowInternalHost; }
    public void setAirflowInternalHost(String airflowInternalHost) { this.airflowInternalHost = airflowInternalHost; }

    public int getAirflowInternalPort() { return airflowInternalPort; }
    public void setAirflowInternalPort(int airflowInternalPort) { this.airflowInternalPort = airflowInternalPort; }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    public String getDbPassword() { return dbPassword; }
    public void setDbPassword(String dbPassword) { this.dbPassword = dbPassword; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LastUpdate {

        @JsonProperty("Status")
        private String status;

        @JsonProperty("CreatedAt")
        @JsonFormat(shape = JsonFormat.Shape.NUMBER)
        private Instant createdAt;

        public LastUpdate() {}

        public LastUpdate(String status, Instant createdAt) {
            this.status = status;
            this.createdAt = createdAt;
        }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    }
}
