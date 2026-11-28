package io.floci.aws.services.rds.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A zero-ETL integration from an RDS or Aurora database into Amazon Redshift, as
 * {@code CreateIntegration} and {@code DescribeIntegrations} describe it. {@code KMSKeyId} and
 * {@code AdditionalEncryptionContext} are kept as given: Floci encrypts nothing with them.
 */
@RegisterForReflection
public class Integration {

    public static final String STATUS_CREATING = "creating";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_MODIFYING = "modifying";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_DELETING = "deleting";
    public static final String STATUS_SYNCING = "syncing";
    public static final String STATUS_NEEDS_ATTENTION = "needs_attention";

    @JsonProperty("IntegrationArn")
    private String integrationArn;

    @JsonProperty("IntegrationName")
    private String integrationName;

    @JsonProperty("SourceArn")
    private String sourceArn;

    @JsonProperty("TargetArn")
    private String targetArn;

    @JsonProperty("KMSKeyId")
    private String kmsKeyId;

    @JsonProperty("AdditionalEncryptionContext")
    private Map<String, String> additionalEncryptionContext = new LinkedHashMap<>();

    @JsonProperty("Status")
    private String status;

    @JsonProperty("Tags")
    private Map<String, String> tags = new LinkedHashMap<>();

    @JsonProperty("DataFilter")
    private String dataFilter;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("CreateTime")
    private Instant createTime;

    @JsonProperty("Errors")
    private List<IntegrationError> errors = new ArrayList<>();

    @JsonProperty("Region")
    private String region;

    @JsonProperty("AccountId")
    private String accountId;

    /** The Redshift databases created from the integration; Floci's own state, not part of the AWS shape. */
    @JsonProperty("Destinations")
    private List<Destination> destinations = new ArrayList<>();

    public Integration() {}

    public Integration(Integration other) {
        this.integrationArn = other.integrationArn;
        this.integrationName = other.integrationName;
        this.sourceArn = other.sourceArn;
        this.targetArn = other.targetArn;
        this.kmsKeyId = other.kmsKeyId;
        this.additionalEncryptionContext = new LinkedHashMap<>(other.additionalEncryptionContext);
        this.status = other.status;
        this.tags = new LinkedHashMap<>(other.tags);
        this.dataFilter = other.dataFilter;
        this.description = other.description;
        this.createTime = other.createTime;
        this.errors = new ArrayList<>(other.errors);
        this.region = other.region;
        this.accountId = other.accountId;
        this.destinations = new ArrayList<>();
        for (Destination destination : other.destinations) {
            this.destinations.add(new Destination(destination));
        }
    }

    /** The id AWS mints for the integration: the last part of its ARN. */
    public String integrationId() {
        return integrationArn.substring(integrationArn.lastIndexOf(':') + 1);
    }

    public String getIntegrationArn() { return integrationArn; }
    public void setIntegrationArn(String integrationArn) { this.integrationArn = integrationArn; }

    public String getIntegrationName() { return integrationName; }
    public void setIntegrationName(String integrationName) { this.integrationName = integrationName; }

    public String getSourceArn() { return sourceArn; }
    public void setSourceArn(String sourceArn) { this.sourceArn = sourceArn; }

    public String getTargetArn() { return targetArn; }
    public void setTargetArn(String targetArn) { this.targetArn = targetArn; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public Map<String, String> getAdditionalEncryptionContext() { return additionalEncryptionContext; }
    public void setAdditionalEncryptionContext(Map<String, String> additionalEncryptionContext) {
        this.additionalEncryptionContext = additionalEncryptionContext != null
                ? new LinkedHashMap<>(additionalEncryptionContext) : new LinkedHashMap<>();
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }

    public String getDataFilter() { return dataFilter; }
    public void setDataFilter(String dataFilter) { this.dataFilter = dataFilter; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public List<IntegrationError> getErrors() { return errors; }
    public void setErrors(List<IntegrationError> errors) {
        this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
    }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public List<Destination> getDestinations() { return destinations; }
    public void setDestinations(List<Destination> destinations) {
        this.destinations = destinations != null ? new ArrayList<>(destinations) : new ArrayList<>();
    }

    /** The destination created from {@code sourceDatabase}, if there is one. */
    public Optional<Destination> destinationFor(String sourceDatabase) {
        return destinations.stream()
                .filter(destination -> destination.getSourceDatabase().equals(sourceDatabase))
                .findFirst();
    }

    /**
     * A database {@code CREATE DATABASE ... FROM INTEGRATION} created on the target, and how far the initial
     * load of its source database has got.
     */
    @RegisterForReflection
    public static class Destination {

        public static final String STATE_CREATING = "Creating";
        public static final String STATE_SYNCING = "Syncing";
        public static final String STATE_SYNCED = "Synced";
        public static final String STATE_FAILED = "Failed";

        @JsonProperty("DatabaseName")
        private String databaseName;

        @JsonProperty("SourceDatabase")
        private String sourceDatabase;

        @JsonProperty("State")
        private String state;

        @JsonProperty("CreateTime")
        private Instant createTime;

        @JsonProperty("LoadId")
        private String loadId;

        public Destination() {}

        public Destination(String databaseName, String sourceDatabase, String state, Instant createTime) {
            this(databaseName, sourceDatabase, state, createTime, null);
        }

        public Destination(String databaseName, String sourceDatabase, String state, Instant createTime,
                           String loadId) {
            this.databaseName = databaseName;
            this.sourceDatabase = sourceDatabase;
            this.state = state;
            this.createTime = createTime;
            this.loadId = loadId;
        }

        public Destination(Destination other) {
            this(other.databaseName, other.sourceDatabase, other.state, other.createTime, other.loadId);
        }

        public String getDatabaseName() { return databaseName; }
        public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

        public String getSourceDatabase() { return sourceDatabase; }
        public void setSourceDatabase(String sourceDatabase) { this.sourceDatabase = sourceDatabase; }

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        public Instant getCreateTime() { return createTime; }
        public void setCreateTime(Instant createTime) { this.createTime = createTime; }

        public String getLoadId() { return loadId; }
        public void setLoadId(String loadId) { this.loadId = loadId; }
    }

    /** One entry of an integration's {@code Errors}. */
    @RegisterForReflection
    public static class IntegrationError {

        @JsonProperty("ErrorCode")
        private String errorCode;

        @JsonProperty("ErrorMessage")
        private String errorMessage;

        public IntegrationError() {}

        public IntegrationError(String errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }
}
