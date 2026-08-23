package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.athena.model.CreateWorkGroupRequest;
import io.github.hectorvent.floci.services.athena.model.QueryExecution;
import io.github.hectorvent.floci.services.athena.model.QueryExecutionContext;
import io.github.hectorvent.floci.services.athena.model.ResultConfiguration;
import io.github.hectorvent.floci.services.athena.model.ResultSet;
import io.github.hectorvent.floci.services.athena.model.WorkGroupTag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
                yield Response.ok(Map.of("WorkGroup", athenaService.getWorkGroup(name, region))).build();
            }
            case "ListWorkGroups" -> Response.ok(Map.of("WorkGroups", athenaService.listWorkGroups(region))).build();
            case "CreateWorkGroup" -> {
                CreateWorkGroupRequest createRequest = mapper.treeToValue(request, CreateWorkGroupRequest.class);
                athenaService.createWorkGroup(createRequest, region);
                yield Response.ok(Map.of()).build();
            }
            case "ListDataCatalogs" ->
                    Response.ok(Map.of("DataCatalogsSummary", athenaService.listDataCatalogs(region))).build();
            case "GetDataCatalog" -> {
                String name = request.has("Name") ? request.get("Name").asText() : AthenaService.DEFAULT_CATALOG;
                yield Response.ok(Map.of("DataCatalog", athenaService.getDataCatalog(region, name))).build();
            }
            case "CreateDataCatalog" -> {
                Map<String, Object> catalog = athenaService.createDataCatalog(region,
                        text(request, "Name"),
                        text(request, "Type"),
                        text(request, "Description"),
                        parameters(request),
                        tags(request));
                yield Response.ok(Map.of("DataCatalog", catalog)).build();
            }
            case "UpdateDataCatalog" -> {
                athenaService.updateDataCatalog(region,
                        text(request, "Name"),
                        text(request, "Type"),
                        text(request, "Description"),
                        parameters(request));
                yield Response.ok(Map.of()).build();
            }
            case "DeleteDataCatalog" -> Response.ok(
                    Map.of("DataCatalog", athenaService.deleteDataCatalog(region, text(request, "Name")))).build();
            case "ListTagsForResource" -> Response.ok(Map.of("Tags",
                    athenaService.listTagsForResource(region, text(request, "ResourceARN")))).build();
            case "TagResource" -> {
                athenaService.tagResource(region, text(request, "ResourceARN"), tags(request));
                yield Response.ok(Map.of()).build();
            }
            case "UntagResource" -> {
                athenaService.untagResource(region, text(request, "ResourceARN"), tagKeys(request));
                yield Response.ok(Map.of()).build();
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
            case "DeleteWorkGroup" -> {
                String wg = request.path("WorkGroup").asText(null);
                if (wg == null || !wg.matches("[a-zA-Z0-9._-]{1,128}")) {
                    throw new AwsException("InvalidRequestException", "WorkGroup is required.", 400);
                }
                if ("primary".equals(wg)) {
                    throw new AwsException("InvalidRequestException", "The primary workgroup cannot be deleted.", 400);
                }
                athenaService.deleteWorkGroup(wg, region);
                yield Response.ok(Map.of()).build();
            }
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Map<String, String> parameters(JsonNode request) {
        JsonNode node = request.get("Parameters");
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> parameters.put(entry.getKey(), entry.getValue().asText()));
        return parameters;
    }

    private static List<WorkGroupTag> tags(JsonNode request) {
        JsonNode node = request.get("Tags");
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<WorkGroupTag> tags = new ArrayList<>();
        for (JsonNode tag : node) {
            tags.add(new WorkGroupTag(text(tag, "Key"), text(tag, "Value")));
        }
        return tags;
    }

    private static List<String> tagKeys(JsonNode request) {
        JsonNode node = request.get("TagKeys");
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode key : node) {
            keys.add(key.asText());
        }
        return keys;
    }
}
