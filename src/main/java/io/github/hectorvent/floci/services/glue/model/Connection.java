package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Data Catalog connection as GetConnection returns it: the caller's {@link ConnectionInput}
 * plus the members the service owns (timestamps, status, schema version). Credentials never
 * reach this shape; see {@link AuthenticationConfiguration#toOutput()}.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Connection {
    @JsonProperty("Name")
    private String name;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("ConnectionType")
    private String connectionType;

    @JsonProperty("MatchCriteria")
    private List<String> matchCriteria;

    @JsonProperty("ConnectionProperties")
    private Map<String, String> connectionProperties;

    @JsonProperty("SparkProperties")
    private Map<String, String> sparkProperties;

    @JsonProperty("AthenaProperties")
    private Map<String, String> athenaProperties;

    @JsonProperty("PythonProperties")
    private Map<String, String> pythonProperties;

    @JsonProperty("PhysicalConnectionRequirements")
    private PhysicalConnectionRequirements physicalConnectionRequirements;

    @JsonProperty("CreationTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant creationTime;

    @JsonProperty("LastUpdatedTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastUpdatedTime;

    @JsonProperty("LastUpdatedBy")
    private String lastUpdatedBy;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("StatusReason")
    private String statusReason;

    @JsonProperty("LastConnectionValidationTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastConnectionValidationTime;

    @JsonProperty("AuthenticationConfiguration")
    private AuthenticationConfiguration authenticationConfiguration;

    @JsonProperty("ConnectionSchemaVersion")
    private Integer connectionSchemaVersion;

    @JsonProperty("CompatibleComputeEnvironments")
    private List<String> compatibleComputeEnvironments;

    /**
     * A copy with the password members of {@code ConnectionProperties} removed: what
     * GetConnection and GetConnections return for {@code HidePassword=true}. The API reference
     * names {@code PASSWORD} and {@code ENCRYPTED_PASSWORD} as the connection's password
     * fields, the latter being the form stored under catalog password encryption.
     */
    public Connection withoutPassword() {
        Connection copy = copy();
        if (connectionProperties != null) {
            Map<String, String> visible = new LinkedHashMap<>(connectionProperties);
            visible.remove("PASSWORD");
            visible.remove("ENCRYPTED_PASSWORD");
            copy.connectionProperties = visible;
        }
        return copy;
    }

    private Connection copy() {
        Connection copy = new Connection();
        copy.name = name;
        copy.description = description;
        copy.connectionType = connectionType;
        copy.matchCriteria = matchCriteria;
        copy.connectionProperties = connectionProperties;
        copy.sparkProperties = sparkProperties;
        copy.athenaProperties = athenaProperties;
        copy.pythonProperties = pythonProperties;
        copy.physicalConnectionRequirements = physicalConnectionRequirements;
        copy.creationTime = creationTime;
        copy.lastUpdatedTime = lastUpdatedTime;
        copy.lastUpdatedBy = lastUpdatedBy;
        copy.status = status;
        copy.statusReason = statusReason;
        copy.lastConnectionValidationTime = lastConnectionValidationTime;
        copy.authenticationConfiguration = authenticationConfiguration;
        copy.connectionSchemaVersion = connectionSchemaVersion;
        copy.compatibleComputeEnvironments = compatibleComputeEnvironments;
        return copy;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getConnectionType() { return connectionType; }
    public void setConnectionType(String connectionType) { this.connectionType = connectionType; }

    public List<String> getMatchCriteria() { return matchCriteria; }
    public void setMatchCriteria(List<String> matchCriteria) { this.matchCriteria = matchCriteria; }

    public Map<String, String> getConnectionProperties() { return connectionProperties; }
    public void setConnectionProperties(Map<String, String> connectionProperties) {
        this.connectionProperties = connectionProperties;
    }

    public Map<String, String> getSparkProperties() { return sparkProperties; }
    public void setSparkProperties(Map<String, String> sparkProperties) { this.sparkProperties = sparkProperties; }

    public Map<String, String> getAthenaProperties() { return athenaProperties; }
    public void setAthenaProperties(Map<String, String> athenaProperties) { this.athenaProperties = athenaProperties; }

    public Map<String, String> getPythonProperties() { return pythonProperties; }
    public void setPythonProperties(Map<String, String> pythonProperties) { this.pythonProperties = pythonProperties; }

    public PhysicalConnectionRequirements getPhysicalConnectionRequirements() { return physicalConnectionRequirements; }
    public void setPhysicalConnectionRequirements(PhysicalConnectionRequirements physicalConnectionRequirements) {
        this.physicalConnectionRequirements = physicalConnectionRequirements;
    }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Instant getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(Instant lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }

    public String getLastUpdatedBy() { return lastUpdatedBy; }
    public void setLastUpdatedBy(String lastUpdatedBy) { this.lastUpdatedBy = lastUpdatedBy; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public Instant getLastConnectionValidationTime() { return lastConnectionValidationTime; }
    public void setLastConnectionValidationTime(Instant lastConnectionValidationTime) {
        this.lastConnectionValidationTime = lastConnectionValidationTime;
    }

    public AuthenticationConfiguration getAuthenticationConfiguration() { return authenticationConfiguration; }
    public void setAuthenticationConfiguration(AuthenticationConfiguration authenticationConfiguration) {
        this.authenticationConfiguration = authenticationConfiguration;
    }

    public Integer getConnectionSchemaVersion() { return connectionSchemaVersion; }
    public void setConnectionSchemaVersion(Integer connectionSchemaVersion) {
        this.connectionSchemaVersion = connectionSchemaVersion;
    }

    public List<String> getCompatibleComputeEnvironments() { return compatibleComputeEnvironments; }
    public void setCompatibleComputeEnvironments(List<String> compatibleComputeEnvironments) {
        this.compatibleComputeEnvironments = compatibleComputeEnvironments;
    }
}
