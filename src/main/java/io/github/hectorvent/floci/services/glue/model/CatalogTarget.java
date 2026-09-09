package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class CatalogTarget {
    @JsonProperty("ConnectionName")
    private String connectionName;

    @JsonProperty("DatabaseName")
    private String databaseName;

    @JsonProperty("DlqEventQueueArn")
    private String dlqEventQueueArn;

    @JsonProperty("EventQueueArn")
    private String eventQueueArn;

    @JsonProperty("Tables")
    private List<String> tables;

    public CatalogTarget() {}

    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String connectionName) { this.connectionName = connectionName; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getDlqEventQueueArn() { return dlqEventQueueArn; }
    public void setDlqEventQueueArn(String dlqEventQueueArn) { this.dlqEventQueueArn = dlqEventQueueArn; }

    public String getEventQueueArn() { return eventQueueArn; }
    public void setEventQueueArn(String eventQueueArn) { this.eventQueueArn = eventQueueArn; }

    public List<String> getTables() { return tables; }
    public void setTables(List<String> tables) { this.tables = tables; }
}
