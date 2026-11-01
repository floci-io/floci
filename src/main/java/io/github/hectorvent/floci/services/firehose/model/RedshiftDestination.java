package io.github.hectorvent.floci.services.firehose.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ProcessingConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One class for the Configuration, Update and Description shapes of a Redshift destination,
 * the way {@link S3Destination} serves all of its shapes. The intermediate S3 location is read
 * from {@code S3Configuration} or {@code S3Update} and written as {@code S3DestinationDescription}.
 * The password is kept because delivery needs it; the describe handler strips it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RedshiftDestination {

    @JsonProperty("RoleARN")
    private String roleArn;
    @JsonProperty("ClusterJDBCURL")
    private String clusterJdbcUrl;
    @JsonProperty("Username")
    private String username;
    @JsonProperty("Password")
    private String password;
    @JsonProperty("CopyCommand")
    private CopyCommand copyCommand;
    @JsonProperty("RetryOptions")
    private RetryOptions retryOptions;
    @JsonProperty("S3BackupMode")
    private String s3BackupMode;
    @JsonProperty("S3DestinationDescription")
    @JsonAlias({"S3Configuration", "S3Update"})
    private S3Destination s3;
    @JsonProperty("S3BackupDescription")
    @JsonAlias({"S3BackupConfiguration", "S3BackupUpdate"})
    private S3Destination s3Backup;
    @JsonProperty("SecretsManagerConfiguration")
    private SecretsManagerConfiguration secretsManagerConfiguration;
    @JsonProperty("ProcessingConfiguration")
    private ProcessingConfiguration processingConfiguration;
    @JsonProperty("CloudWatchLoggingOptions")
    private JsonNode cloudWatchLoggingOptions;

    public RedshiftDestination() {}

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
    public String getClusterJdbcUrl() { return clusterJdbcUrl; }
    public void setClusterJdbcUrl(String clusterJdbcUrl) { this.clusterJdbcUrl = clusterJdbcUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public CopyCommand getCopyCommand() { return copyCommand; }
    public void setCopyCommand(CopyCommand copyCommand) { this.copyCommand = copyCommand; }
    public RetryOptions getRetryOptions() { return retryOptions; }
    public void setRetryOptions(RetryOptions retryOptions) { this.retryOptions = retryOptions; }
    public String getS3BackupMode() { return s3BackupMode; }
    public void setS3BackupMode(String s3BackupMode) { this.s3BackupMode = s3BackupMode; }
    public S3Destination getS3() { return s3; }
    public void setS3(S3Destination s3) { this.s3 = s3; }
    public S3Destination getS3Backup() { return s3Backup; }
    public void setS3Backup(S3Destination s3Backup) { this.s3Backup = s3Backup; }
    public SecretsManagerConfiguration getSecretsManagerConfiguration() { return secretsManagerConfiguration; }
    public void setSecretsManagerConfiguration(SecretsManagerConfiguration configuration) {
        this.secretsManagerConfiguration = configuration;
    }
    public ProcessingConfiguration getProcessingConfiguration() { return processingConfiguration; }
    public void setProcessingConfiguration(ProcessingConfiguration processingConfiguration) {
        this.processingConfiguration = processingConfiguration;
    }
    public JsonNode getCloudWatchLoggingOptions() { return cloudWatchLoggingOptions; }
    public void setCloudWatchLoggingOptions(JsonNode cloudWatchLoggingOptions) {
        this.cloudWatchLoggingOptions = cloudWatchLoggingOptions;
    }

    /**
     * Fills the defaults AWS describes a Lambda processor with, as the destination is written: the role comes from
     * this destination's own {@code RoleARN}. Applied as an S3 destination's ProcessingConfiguration is, since both
     * carry the same shape; AWS has not been probed for a Redshift destination specifically.
     */
    public void canonicalizeProcessors() {
        S3Destination.canonicalizeProcessors(processingConfiguration, roleArn);
    }

    /** Shallow copy without the password, for responses. Nested objects are shared and must not be mutated. */
    public RedshiftDestination copyWithoutPassword() {
        RedshiftDestination copy = new RedshiftDestination();
        copy.roleArn = roleArn;
        copy.clusterJdbcUrl = clusterJdbcUrl;
        copy.username = username;
        copy.copyCommand = copyCommand;
        copy.retryOptions = retryOptions;
        copy.s3BackupMode = s3BackupMode;
        copy.s3 = s3;
        copy.s3Backup = s3Backup;
        copy.secretsManagerConfiguration = secretsManagerConfiguration;
        copy.processingConfiguration = processingConfiguration;
        copy.cloudWatchLoggingOptions = cloudWatchLoggingOptions;
        return copy;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CopyCommand {
        @JsonProperty("DataTableName")
        private String dataTableName;
        @JsonProperty("CopyOptions")
        private String copyOptions;
        @JsonProperty("DataTableColumns")
        private String dataTableColumns;

        public CopyCommand() {}

        public String getDataTableName() { return dataTableName; }
        public void setDataTableName(String dataTableName) { this.dataTableName = dataTableName; }
        public String getCopyOptions() { return copyOptions; }
        public void setCopyOptions(String copyOptions) { this.copyOptions = copyOptions; }
        public String getDataTableColumns() { return dataTableColumns; }
        public void setDataTableColumns(String dataTableColumns) { this.dataTableColumns = dataTableColumns; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RetryOptions {
        @JsonProperty("DurationInSeconds")
        private Integer durationInSeconds;

        public RetryOptions() {}

        public Integer getDurationInSeconds() { return durationInSeconds; }
        public void setDurationInSeconds(Integer durationInSeconds) { this.durationInSeconds = durationInSeconds; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SecretsManagerConfiguration {
        @JsonProperty("Enabled")
        private Boolean enabled;
        @JsonProperty("SecretARN")
        private String secretArn;
        @JsonProperty("RoleARN")
        private String roleArn;

        public SecretsManagerConfiguration() {}

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public String getSecretArn() { return secretArn; }
        public void setSecretArn(String secretArn) { this.secretArn = secretArn; }
        public String getRoleArn() { return roleArn; }
        public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
    }
}
