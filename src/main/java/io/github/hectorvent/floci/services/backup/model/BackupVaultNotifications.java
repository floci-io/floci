package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The notification configuration attached to a backup vault.
 *
 * <p>Held in its own store rather than as a field on {@link BackupVault} because
 * {@code DescribeBackupVault} serialises the vault POJO directly, and AWS does not
 * return the notification configuration there -- it is reachable only through
 * {@code GetBackupVaultNotifications}. Keeping it separate is what stops the vault
 * description growing members real AWS does not send.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BackupVaultNotifications {

    @JsonProperty("SNSTopicArn")
    private String snsTopicArn;

    @JsonProperty("BackupVaultEvents")
    private List<String> backupVaultEvents = new ArrayList<>();

    public BackupVaultNotifications() {}

    public BackupVaultNotifications(String snsTopicArn, List<String> backupVaultEvents) {
        this.snsTopicArn = snsTopicArn;
        setBackupVaultEvents(backupVaultEvents);
    }

    public String getSnsTopicArn() { return snsTopicArn; }
    public void setSnsTopicArn(String snsTopicArn) { this.snsTopicArn = snsTopicArn; }

    public List<String> getBackupVaultEvents() { return backupVaultEvents; }

    public void setBackupVaultEvents(List<String> backupVaultEvents) {
        this.backupVaultEvents = backupVaultEvents != null ? backupVaultEvents : new ArrayList<>();
    }
}
