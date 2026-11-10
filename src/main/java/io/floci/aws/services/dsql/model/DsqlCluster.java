package io.floci.aws.services.dsql.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Aurora DSQL cluster as Floci stores it. The wire shapes are built from it by the controller. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DsqlCluster {

    private String identifier;
    private String arn;
    private String accountId;
    private String region;
    private String status;
    private Instant creationTime;
    private boolean deletionProtectionEnabled;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String kmsEncryptionKey;
    private String endpoint;
    private String clientToken;
    private String clientRequest;
    private String policy;
    private String policyVersion;
    private String witnessRegion;
    private List<String> linkedClusterArns;
    private boolean databaseReady;

    public String getIdentifier() { return identifier; }
    public void setIdentifier(String identifier) { this.identifier = identifier; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public boolean isDeletionProtectionEnabled() { return deletionProtectionEnabled; }
    public void setDeletionProtectionEnabled(boolean deletionProtectionEnabled) {
        this.deletionProtectionEnabled = deletionProtectionEnabled;
    }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new LinkedHashMap<>() : tags; }
    public String getKmsEncryptionKey() { return kmsEncryptionKey; }
    public void setKmsEncryptionKey(String kmsEncryptionKey) { this.kmsEncryptionKey = kmsEncryptionKey; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public String getClientRequest() { return clientRequest; }
    public void setClientRequest(String clientRequest) { this.clientRequest = clientRequest; }
    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getWitnessRegion() { return witnessRegion; }
    public void setWitnessRegion(String witnessRegion) { this.witnessRegion = witnessRegion; }
    public List<String> getLinkedClusterArns() { return linkedClusterArns; }
    public void setLinkedClusterArns(List<String> linkedClusterArns) { this.linkedClusterArns = linkedClusterArns; }
    /** Whether the cluster's database answers, which a multi-Region cluster waits for after peering. */
    public boolean isDatabaseReady() { return databaseReady; }
    public void setDatabaseReady(boolean databaseReady) { this.databaseReady = databaseReady; }
}
