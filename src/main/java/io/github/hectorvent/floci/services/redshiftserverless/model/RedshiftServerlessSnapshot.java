package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Redshift Serverless snapshot. Floci stores snapshot metadata only and does not model async
 * cross-region copy: a snapshot created via {@code CreateSnapshot} in the standby region is
 * immediately {@code AVAILABLE} there, standing in for what a real
 * {@code SnapshotCopyConfiguration} would eventually produce.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RedshiftServerlessSnapshot {

    private String snapshotName;
    private String snapshotArn;
    private String namespaceName;
    private String namespaceArn;
    private String region;
    private String accountId;
    private String ownerAccount;
    private String status = "AVAILABLE";
    private Instant snapshotCreateTime;
    private String adminUsername;
    private String kmsKeyId;
    private Integer retentionPeriod;
    private String sqlDump;
    private Map<String, String> tags = new LinkedHashMap<>();

    public RedshiftServerlessSnapshot() {
    }

    public RedshiftServerlessSnapshot(RedshiftServerlessSnapshot other) {
        this.snapshotName = other.snapshotName;
        this.snapshotArn = other.snapshotArn;
        this.namespaceName = other.namespaceName;
        this.namespaceArn = other.namespaceArn;
        this.region = other.region;
        this.accountId = other.accountId;
        this.ownerAccount = other.ownerAccount;
        this.status = other.status;
        this.snapshotCreateTime = other.snapshotCreateTime;
        this.adminUsername = other.adminUsername;
        this.kmsKeyId = other.kmsKeyId;
        this.retentionPeriod = other.retentionPeriod;
        this.sqlDump = other.sqlDump;
        this.tags = new LinkedHashMap<>(other.tags);
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public void setSnapshotName(String snapshotName) {
        this.snapshotName = snapshotName;
    }

    public String getSnapshotArn() {
        return snapshotArn;
    }

    public void setSnapshotArn(String snapshotArn) {
        this.snapshotArn = snapshotArn;
    }

    public String getNamespaceName() {
        return namespaceName;
    }

    public void setNamespaceName(String namespaceName) {
        this.namespaceName = namespaceName;
    }

    public String getNamespaceArn() {
        return namespaceArn;
    }

    public void setNamespaceArn(String namespaceArn) {
        this.namespaceArn = namespaceArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getOwnerAccount() {
        return ownerAccount;
    }

    public void setOwnerAccount(String ownerAccount) {
        this.ownerAccount = ownerAccount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getSnapshotCreateTime() {
        return snapshotCreateTime;
    }

    public void setSnapshotCreateTime(Instant snapshotCreateTime) {
        this.snapshotCreateTime = snapshotCreateTime;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    /** Days the snapshot is kept, or {@code null} for indefinitely. */
    public Integer getRetentionPeriod() {
        return retentionPeriod;
    }

    public void setRetentionPeriod(Integer retentionPeriod) {
        this.retentionPeriod = retentionPeriod;
    }

    /** Path of the pg_dump file, or {@code null} for a metadata-only snapshot. */
    public String getSqlDump() {
        return sqlDump;
    }

    public void setSqlDump(String sqlDump) {
        this.sqlDump = sqlDump;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
