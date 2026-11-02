package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A Redshift Serverless snapshot. A cross-region copy made for a {@code SnapshotCopyConfiguration}
 * is a snapshot of its own in the destination Region, immediately {@code AVAILABLE} there.
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
    private String adminUserPassword;
    private String kmsKeyId;
    private Integer retentionPeriod;
    private String sqlDump;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String copiedFromSnapshotArn;
    private Instant copiedFromSnapshotCreateTime;
    private Set<String> copiedToRegions = new LinkedHashSet<>();

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
        this.adminUserPassword = other.adminUserPassword;
        this.kmsKeyId = other.kmsKeyId;
        this.retentionPeriod = other.retentionPeriod;
        this.sqlDump = other.sqlDump;
        this.tags = new LinkedHashMap<>(other.tags);
        this.copiedFromSnapshotArn = other.copiedFromSnapshotArn;
        this.copiedFromSnapshotCreateTime = other.copiedFromSnapshotCreateTime;
        this.copiedToRegions = new LinkedHashSet<>(other.copiedToRegions);
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

    public String getAdminUserPassword() {
        return adminUserPassword;
    }

    public void setAdminUserPassword(String adminUserPassword) {
        this.adminUserPassword = adminUserPassword;
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

    /** ARN of the snapshot this one was copied from, or {@code null} for a snapshot taken here. Never in a response. */
    public String getCopiedFromSnapshotArn() {
        return copiedFromSnapshotArn;
    }

    public void setCopiedFromSnapshotArn(String copiedFromSnapshotArn) {
        this.copiedFromSnapshotArn = copiedFromSnapshotArn;
    }

    /**
     * Creation time of the source snapshot at the moment of the copy. A snapshot ARN is built from
     * the name alone, so this is what tells a copy of the current source from a copy of an earlier
     * snapshot that had the same name.
     */
    public Instant getCopiedFromSnapshotCreateTime() {
        return copiedFromSnapshotCreateTime;
    }

    public void setCopiedFromSnapshotCreateTime(Instant copiedFromSnapshotCreateTime) {
        this.copiedFromSnapshotCreateTime = copiedFromSnapshotCreateTime;
    }

    /** Regions this snapshot has already been copied to, so a deleted or expired copy is not made again. */
    public Set<String> getCopiedToRegions() {
        return copiedToRegions;
    }

    public void setCopiedToRegions(Set<String> copiedToRegions) {
        this.copiedToRegions = copiedToRegions == null ? new LinkedHashSet<>() : new LinkedHashSet<>(copiedToRegions);
    }
}
