package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.athena.model.CreateWorkGroupRequest;
import io.github.hectorvent.floci.services.athena.model.QueryExecution;
import io.github.hectorvent.floci.services.athena.model.QueryExecutionContext;
import io.github.hectorvent.floci.services.athena.model.ResultConfiguration;
import io.github.hectorvent.floci.services.athena.model.ResultSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@ApplicationScoped
public class AthenaJsonHandler {

    private final AthenaService athenaService;
    private final ObjectMapper mapper;

    @Inject
    public AthenaJsonHandler(AthenaService athenaService, ObjectMapper mapper) {
        this.athenaService = athenaService;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "StartQueryExecution" -> {
                String query = request.get("QueryString").asText();
                String workGroup = request.has("WorkGroup") ? request.get("WorkGroup").asText() : "primary";

                QueryExecutionContext context = null;
                if (request.has("QueryExecutionContext")) {
                    context = mapper.treeToValue(request.get("QueryExecutionContext"), QueryExecutionContext.class);
                }

                ResultConfiguration resultConfiguration = null;
                if (request.has("ResultConfiguration")) {
                    resultConfiguration = mapper.treeToValue(request.get("ResultConfiguration"), ResultConfiguration.class);
                }

                String id = athenaService.startQueryExecution(query, workGroup, context, resultConfiguration);
                yield Response.ok(Map.of("QueryExecutionId", id)).build();
            }
            case "GetQueryExecution" -> {
                String id = request.get("QueryExecutionId").asText();
                QueryExecution execution = athenaService.getQueryExecution(id);
                yield Response.ok(Map.of("QueryExecution", execution)).build();
            }
            case "GetQueryResults" -> {
                String id = request.get("QueryExecutionId").asText();
                ResultSet results = athenaService.getQueryResults(id);
                yield Response.ok(Map.of("ResultSet", results)).build();
            }
            case "ListQueryExecutions" -> {
                yield Response.ok(Map.of("QueryExecutionIds",
                        athenaService.listQueryExecutions().stream()
                                .map(QueryExecution::getQueryExecutionId).toList())).build();
            }
            case "StopQueryExecution" -> {
                athenaService.stopQueryExecution(request.get("QueryExecutionId").asText());
                yield Response.ok(Map.of()).build();
            }
            case "GetWorkGroup" -> {
                String name = request.has("WorkGroup") ? request.get("WorkGroup").asText() : "primary";
                yield Response.ok(Map.of("WorkGroup", athenaService.getWorkGroup(name))).build();
            }
            case "ListWorkGroups" -> Response.ok(Map.of("WorkGroups", athenaService.listWorkGroups())).build();
            case "CreateWorkGroup" -> {
                CreateWorkGroupRequest createRequest = mapper.treeToValue(request, CreateWorkGroupRequest.class);
                athenaService.createWorkGroup(createRequest, region);
                yield Response.ok(Map.of()).build();
            }
            case "ListDataCatalogs" -> Response.ok(Map.of("DataCatalogsSummary", athenaService.listDataCatalogs())).build();
            case "GetDataCatalog" -> {
                String name = request.has("Name") ? request.get("Name").asText() : AthenaService.DEFAULT_CATALOG;
                yield Response.ok(Map.of("DataCatalog", athenaService.getDataCatalog(name))).build();
            }
            case "ListDatabases" -> {
                String catalog = request.has("CatalogName") ? request.get("CatalogName").asText() : AthenaService.DEFAULT_CATALOG;
                yield Response.ok(Map.of("DatabaseList", athenaService.listDatabases(catalog))).build();
            }
            case "ListTableMetadata" -> {
                String catalog = request.has("CatalogName") ? request.get("CatalogName").asText() : AthenaService.DEFAULT_CATALOG;
                String database = request.path("DatabaseName").asText(request.path("Database").asText(""));
                yield Response.ok(Map.of("TableMetadataList", athenaService.listTableMetadata(catalog, database))).build();
            }
            case "GetTableMetadata" -> {
                String catalog = request.has("CatalogName") ? request.get("CatalogName").asText() : AthenaService.DEFAULT_CATALOG;
                String database = request.path("DatabaseName").asText(request.path("Database").asText(""));
                String tableName = request.get("TableName").asText();
                yield Response.ok(Map.of("TableMetadata", athenaService.getTableMetadata(catalog, database, tableName))).build();
            }
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }
}
