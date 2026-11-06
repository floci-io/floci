package io.floci.aws.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An on-demand backup: its details, the source table as it was when the backup was taken, and
 * the items it holds.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableBackup {

    private String backupArn;
    private String backupName;
    private String backupType;
    private Instant creationDateTime;
    private Instant expiryDateTime;
    private long sizeBytes;
    private String region;
    private TableDefinition table;
    private List<JsonNode> items;

    public TableBackup() {
        this.items = new ArrayList<>();
    }

    public String getBackupArn() { return backupArn; }
    public void setBackupArn(String backupArn) { this.backupArn = backupArn; }

    public String getBackupName() { return backupName; }
    public void setBackupName(String backupName) { this.backupName = backupName; }

    public String getBackupType() { return backupType; }
    public void setBackupType(String backupType) { this.backupType = backupType; }

    public Instant getCreationDateTime() { return creationDateTime; }
    public void setCreationDateTime(Instant creationDateTime) { this.creationDateTime = creationDateTime; }

    public Instant getExpiryDateTime() { return expiryDateTime; }
    public void setExpiryDateTime(Instant expiryDateTime) { this.expiryDateTime = expiryDateTime; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public TableDefinition getTable() { return table; }
    public void setTable(TableDefinition table) { this.table = table; }

    public List<JsonNode> getItems() { return items; }
    public void setItems(List<JsonNode> items) { this.items = items != null ? items : new ArrayList<>(); }
}
