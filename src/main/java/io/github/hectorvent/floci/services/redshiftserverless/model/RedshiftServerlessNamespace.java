package io.github.hectorvent.floci.services.redshiftserverless.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Redshift Serverless namespace. Namespace names are scoped per region (verified against
 * {@code aws-sdk-go-v2/service/redshiftserverless} {@code types.Namespace}), so the standing
 * DR pair can legally give both regions' namespaces the same {@code namespaceName} — the
 * invariant the switchover design relies on.
 */
public class RedshiftServerlessNamespace {

    private String namespaceId;
    private String namespaceName;
    private String namespaceArn;
    private String region;
    private String accountId;
    private String adminUsername;
    private String dbName;
    private String defaultIamRoleArn;
    private List<String> iamRoles = new ArrayList<>();
    private String kmsKeyId;
    private List<String> logExports = new ArrayList<>();
    private String status = "AVAILABLE";
    private Instant creationDate;

    public String getNamespaceId() {
        return namespaceId;
    }

    public void setNamespaceId(String namespaceId) {
        this.namespaceId = namespaceId;
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

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getDefaultIamRoleArn() {
        return defaultIamRoleArn;
    }

    public void setDefaultIamRoleArn(String defaultIamRoleArn) {
        this.defaultIamRoleArn = defaultIamRoleArn;
    }

    public List<String> getIamRoles() {
        return iamRoles;
    }

    public void setIamRoles(List<String> iamRoles) {
        this.iamRoles = iamRoles != null ? iamRoles : new ArrayList<>();
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public List<String> getLogExports() {
        return logExports;
    }

    public void setLogExports(List<String> logExports) {
        this.logExports = logExports != null ? logExports : new ArrayList<>();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(Instant creationDate) {
        this.creationDate = creationDate;
    }
}
