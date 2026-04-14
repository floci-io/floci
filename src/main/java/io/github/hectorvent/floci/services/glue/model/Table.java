package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class Table {
    @JsonProperty("Name")
    private String name;
    @JsonProperty("DatabaseName")
    private String databaseName;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("CreateTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createTime;
    @JsonProperty("UpdateTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant updateTime;
    @JsonProperty("LastAccessTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastAccessTime;
    @JsonProperty("PartitionKeys")
    private List<Column> partitionKeys;
    @JsonProperty("StorageDescriptor")
    private StorageDescriptor storageDescriptor;
    @JsonProperty("TableType")
    private String tableType;
    @JsonProperty("Parameters")
    private Map<String, String> parameters;

    public Table() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }
    public Instant getUpdateTime() { return updateTime; }
    public void setUpdateTime(Instant updateTime) { this.updateTime = updateTime; }
    public Instant getLastAccessTime() { return lastAccessTime; }
    public void setLastAccessTime(Instant lastAccessTime) { this.lastAccessTime = lastAccessTime; }
    public List<Column> getPartitionKeys() { return partitionKeys; }
    public void setPartitionKeys(List<Column> partitionKeys) { this.partitionKeys = partitionKeys; }
    public StorageDescriptor getStorageDescriptor() { return storageDescriptor; }
    public void setStorageDescriptor(StorageDescriptor storageDescriptor) { this.storageDescriptor = storageDescriptor; }
    public String getTableType() { return tableType; }
    public void setTableType(String tableType) { this.tableType = tableType; }
    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
}
