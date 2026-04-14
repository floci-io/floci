package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class Partition {
    @JsonProperty("Values")
    private List<String> values;
    @JsonProperty("DatabaseName")
    private String databaseName;
    @JsonProperty("TableName")
    private String tableName;
    @JsonProperty("CreationTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant creationTime;
    @JsonProperty("LastAccessTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastAccessTime;
    @JsonProperty("StorageDescriptor")
    private StorageDescriptor storageDescriptor;
    @JsonProperty("Parameters")
    private Map<String, String> parameters;

    public Partition() {}

    public List<String> getValues() { return values; }
    public void setValues(List<String> values) { this.values = values; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastAccessTime() { return lastAccessTime; }
    public void setLastAccessTime(Instant lastAccessTime) { this.lastAccessTime = lastAccessTime; }
    public StorageDescriptor getStorageDescriptor() { return storageDescriptor; }
    public void setStorageDescriptor(StorageDescriptor storageDescriptor) { this.storageDescriptor = storageDescriptor; }
    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
}
