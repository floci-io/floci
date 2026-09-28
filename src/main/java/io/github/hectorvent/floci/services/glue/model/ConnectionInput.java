package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * The {@code ConnectionInput} structure of CreateConnection and UpdateConnection: the caller's
 * full definition of a connection. On Update it redefines the connection rather than patching
 * it, which is why it carries the required members again instead of an optional subset.
 */
@RegisterForReflection
public class ConnectionInput {
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

    @JsonProperty("AuthenticationConfiguration")
    private AuthenticationConfiguration authenticationConfiguration;

    @JsonProperty("ValidateCredentials")
    private Boolean validateCredentials;

    @JsonProperty("ValidateForComputeEnvironments")
    private List<String> validateForComputeEnvironments;

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

    public AuthenticationConfiguration getAuthenticationConfiguration() { return authenticationConfiguration; }
    public void setAuthenticationConfiguration(AuthenticationConfiguration authenticationConfiguration) {
        this.authenticationConfiguration = authenticationConfiguration;
    }

    public Boolean getValidateCredentials() { return validateCredentials; }
    public void setValidateCredentials(Boolean validateCredentials) { this.validateCredentials = validateCredentials; }

    public List<String> getValidateForComputeEnvironments() { return validateForComputeEnvironments; }
    public void setValidateForComputeEnvironments(List<String> validateForComputeEnvironments) {
        this.validateForComputeEnvironments = validateForComputeEnvironments;
    }
}
