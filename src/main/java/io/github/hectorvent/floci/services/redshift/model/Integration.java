package io.github.hectorvent.floci.services.redshift.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A zero-ETL integration between a source (for example a DynamoDB table) and a Redshift target.
 *
 * <p>Field set captured from a live integration in us-west-2. {@code status} is lower case there
 * ({@code active}), and {@code errors} is present but empty on a healthy integration.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Integration {
    private String integrationArn;
    private String accountId;
    private String integrationName;
    private String sourceArn;
    private String targetArn;
    private String sourceStreamArn;
    private String targetClusterIdentifier;
    private String landingTableName;
    /**
     * The newest DynamoDB Stream sequence written to the landing table, by shard. Concurrent because
     * a store flush can serialize it while the consumer commits.
     */
    private Map<String, String> shardSequenceNumbers = new ConcurrentHashMap<>();
    private String backfillLastEvaluatedKey;
    private boolean backfillCompleted;
    private int retryCount;
    private String lastError;
    private boolean pollingEnabled;
    private String description;
    private String status;
    private String kmsKeyId;
    private String createTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, String> additionalEncryptionContext = new LinkedHashMap<>();
    private List<String> errors = new ArrayList<>();

    public Integration() {}

    public String getIntegrationArn() { return integrationArn; }
    public void setIntegrationArn(String integrationArn) { this.integrationArn = integrationArn; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getIntegrationName() { return integrationName; }
    public void setIntegrationName(String integrationName) { this.integrationName = integrationName; }
    public String getSourceArn() { return sourceArn; }
    public void setSourceArn(String sourceArn) { this.sourceArn = sourceArn; }
    public String getTargetArn() { return targetArn; }
    public void setTargetArn(String targetArn) { this.targetArn = targetArn; }
    public String getSourceStreamArn() { return sourceStreamArn; }
    public void setSourceStreamArn(String sourceStreamArn) { this.sourceStreamArn = sourceStreamArn; }
    public String getTargetClusterIdentifier() { return targetClusterIdentifier; }
    public void setTargetClusterIdentifier(String targetClusterIdentifier) { this.targetClusterIdentifier = targetClusterIdentifier; }
    public String getLandingTableName() { return landingTableName; }
    public void setLandingTableName(String landingTableName) { this.landingTableName = landingTableName; }
    public Map<String, String> getShardSequenceNumbers() { return shardSequenceNumbers; }
    public void setShardSequenceNumbers(Map<String, String> shardSequenceNumbers) {
        this.shardSequenceNumbers = shardSequenceNumbers == null
                ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(shardSequenceNumbers);
    }
    public String getBackfillLastEvaluatedKey() { return backfillLastEvaluatedKey; }
    public void setBackfillLastEvaluatedKey(String backfillLastEvaluatedKey) { this.backfillLastEvaluatedKey = backfillLastEvaluatedKey; }
    public boolean isBackfillCompleted() { return backfillCompleted; }
    public void setBackfillCompleted(boolean backfillCompleted) { this.backfillCompleted = backfillCompleted; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public boolean isPollingEnabled() { return pollingEnabled; }
    public void setPollingEnabled(boolean pollingEnabled) { this.pollingEnabled = pollingEnabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public Map<String, String> getTags() { return tags == null ? null : new LinkedHashMap<>(tags); }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags); }
    public Map<String, String> getAdditionalEncryptionContext() {
        return additionalEncryptionContext == null ? null : new LinkedHashMap<>(additionalEncryptionContext);
    }
    public void setAdditionalEncryptionContext(Map<String, String> context) {
        this.additionalEncryptionContext = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
    }
    public List<String> getErrors() { return errors == null ? null : new ArrayList<>(errors); }
    public void setErrors(List<String> errors) { this.errors = errors == null ? new ArrayList<>() : new ArrayList<>(errors); }
}
