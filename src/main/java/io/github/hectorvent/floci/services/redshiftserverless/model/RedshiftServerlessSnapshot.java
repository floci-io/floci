package io.github.hectorvent.floci.services.redshiftserverless.model;

import java.time.Instant;

/**
 * A Redshift Serverless snapshot. Floci does not model real async cross-region copy
 * (SPE-71256 design doc §floci: "pre-seed state rather than modelling the full lifecycle") —
 * a snapshot created via {@code CreateSnapshot} in the standby region is immediately
 * {@code AVAILABLE} there, standing in for what a real {@code SnapshotCopyConfiguration}
 * would eventually produce.
 */
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
}
