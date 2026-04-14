package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.Table;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@ApplicationScoped
public class GlueJsonHandler {

    private final GlueService glueService;
    private final ObjectMapper mapper;

    @Inject
    public GlueJsonHandler(GlueService glueService, ObjectMapper mapper) {
        this.glueService = glueService;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateDatabase" -> {
                Database db = mapper.treeToValue(request.get("DatabaseInput"), Database.class);
                glueService.createDatabase(db);
                yield Response.ok().build();
            }
            case "GetDatabase" -> {
                String name = request.get("Name").asText();
                Database db = glueService.getDatabase(name);
                yield Response.ok(Map.of("Database", db)).build();
            }
            case "GetDatabases" -> {
                yield Response.ok(Map.of("DatabaseList", glueService.getDatabases())).build();
            }
            case "CreateTable" -> {
                String dbName = request.get("DatabaseName").asText();
                Table table = mapper.treeToValue(request.get("TableInput"), Table.class);
                glueService.createTable(dbName, table);
                yield Response.ok().build();
            }
            case "GetTable" -> {
                String dbName = request.get("DatabaseName").asText();
                String tableName = request.get("Name").asText();
                Table table = glueService.getTable(dbName, tableName);
                yield Response.ok(Map.of("Table", table)).build();
            }
            case "GetTables" -> {
                String dbName = request.get("DatabaseName").asText();
                yield Response.ok(Map.of("TableList", glueService.getTables(dbName))).build();
            }
            case "DeleteTable" -> {
                String dbName = request.get("DatabaseName").asText();
                String tableName = request.get("Name").asText();
                glueService.deleteTable(dbName, tableName);
                yield Response.ok().build();
            }
            case "CreatePartition" -> {
                String dbName = request.get("DatabaseName").asText();
                String tableName = request.get("TableName").asText();
                Partition partition = mapper.treeToValue(request.get("PartitionInput"), Partition.class);
                glueService.createPartition(dbName, tableName, partition);
                yield Response.ok().build();
            }
            case "GetPartitions" -> {
                String dbName = request.get("DatabaseName").asText();
                String tableName = request.get("TableName").asText();
                yield Response.ok(Map.of("Partitions", glueService.getPartitions(dbName, tableName))).build();
            }
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }
}
