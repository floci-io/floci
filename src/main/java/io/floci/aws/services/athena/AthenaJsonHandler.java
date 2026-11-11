package io.floci.aws.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.services.athena.model.CreateWorkGroupRequest;
import io.floci.aws.services.athena.model.NamedQuery;
import io.floci.aws.services.athena.model.QueryExecution;
import io.floci.aws.services.athena.model.QueryExecutionContext;
import io.floci.aws.services.athena.model.ResultConfiguration;
import io.floci.aws.services.athena.model.ResultSet;
import io.floci.aws.services.athena.model.UpdateWorkGroupRequest;
import io.floci.aws.services.athena.model.WorkGroupTag;
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
    private final AthenaNamedQueryService namedQueryService;
    private final ObjectMapper mapper;

    @Inject
    public AthenaJsonHandler(AthenaService athenaService, AthenaNamedQueryService namedQueryService,
                              ObjectMapper mapper) {
        this.athenaService = athenaService;
        this.namedQueryService = namedQueryService;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        AthenaRequestShapes.check(action, request);
        return switch (action) {
            case "CreateNamedQuery" -> {
                String id = namedQueryService.createNamedQuery(region, text(request, "Name"),
                        text(request, "Database"), text(request, "QueryString"),
                        text(request, "Description"), text(request, "WorkGroup"),
                        text(request, "ClientRequestToken"));
                yield Response.ok(Map.of("NamedQueryId", id)).build();
            }
            case "GetNamedQuery" -> Response.ok(Map.of("NamedQuery",
                    namedQueryService.getNamedQuery(region, text(request, "NamedQueryId")))).build();
            case "BatchGetNamedQuery" -> {
                JsonNode ids = request.get("NamedQueryIds");
                List<String> queryIds = new ArrayList<>();
                for (JsonNode id : ids) {
                    queryIds.add(id.textValue());
                }
                yield Response.ok(namedQueryService.batchGetNamedQuery(region, queryIds)).build();
            }
            case "ListNamedQueries" -> {
                PaginatedResult<NamedQuery> page = namedQueryService.listNamedQueries(region,
                        text(request, "WorkGroup"),
                        Pagination.parseMaxResults(request, "MaxResults", "InvalidRequestException"),
                        Pagination.parseNextToken(request, "NextToken", "InvalidRequestException"));
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("NamedQueryIds", page.items().stream().map(NamedQuery::namedQueryId).toList());
                if (page.nextToken() != null) {
                    response.put("NextToken", page.nextToken());
                }
                yield Response.ok(response).build();
            }
            case "UpdateNamedQuery" -> {
                namedQueryService.updateNamedQuery(region, text(request, "NamedQueryId"),
                        text(request, "Name"), text(request, "QueryString"),
                        text(request, "Description"));
                yield Response.ok().build();
            }
            case "DeleteNamedQuery" -> {
                namedQueryService.deleteNamedQuery(region, text(request, "NamedQueryId"));
                yield Response.ok().build();
            }
            case "StartQueryExecution" -> {
                String query = request.get("QueryString").asText();
                String named = text(request, "WorkGroup");
                String workGroup = named != null ? named : "primary";

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
                Integer maxResults = Pagination.parseMaxResults(request, "MaxResults", "InvalidRequestException");
                String nextToken = Pagination.parseNextToken(request, "NextToken", "InvalidRequestException");
                JsonNode workGroup = request.get("WorkGroup");
                if (workGroup != null && !workGroup.isNull() && !workGroup.isTextual()) {
                    throw new AwsException("InvalidRequestException", "WorkGroup must be a string.", 400);
                }
                PaginatedResult<QueryExecution> page = athenaService.listQueryExecutionsPage(
                        region, text(request, "WorkGroup"), maxResults, nextToken);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("QueryExecutionIds", page.items().stream().map(QueryExecution::getQueryExecutionId).toList());
                if (page.nextToken() != null) {
                    response.put("NextToken", page.nextToken());
                }
                yield Response.ok(response).build();
            }
            case "StopQueryExecution" -> {
                athenaService.stopQueryExecution(request.get("QueryExecutionId").asText());
                yield Response.ok(Map.of()).build();
            }
            case "GetWorkGroup" -> {
                String named = text(request, "WorkGroup");
                String name = named != null ? named : "primary";
                yield Response.ok(Map.of("WorkGroup", athenaService.getWorkGroup(name, region))).build();
            }
            case "ListWorkGroups" -> {
                JsonNode maxResults = request.get("MaxResults");
                if (maxResults != null && (!maxResults.isIntegralNumber() || !maxResults.canConvertToInt())) {
                    throw new AwsException("InvalidRequestException", "Invalid MaxResults", 400);
                }
                int pageSize = maxResults == null ? 50 : maxResults.asInt();
                String nextToken = request.hasNonNull("NextToken") ? request.get("NextToken").asText() : null;
                PaginatedResult<Map<String, Object>> page = athenaService.listWorkGroupsPage(region, pageSize, nextToken);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("WorkGroups", page.items());
                if (page.nextToken() != null) {
                    response.put("NextToken", page.nextToken());
                }
                yield Response.ok(response).build();
            }
            case "CreateWorkGroup" -> {
                CreateWorkGroupRequest createRequest = mapper.treeToValue(request, CreateWorkGroupRequest.class);
                athenaService.createWorkGroup(createRequest, region);
                yield Response.ok(Map.of()).build();
            }
            case "UpdateWorkGroup" -> {
                UpdateWorkGroupRequest updateRequest = mapper.treeToValue(request, UpdateWorkGroupRequest.class);
                athenaService.updateWorkGroup(updateRequest, region);
                yield Response.ok(Map.of()).build();
            }
            case "ListDataCatalogs" -> {
                Integer maxResults = Pagination.parseMaxResults(request, "MaxResults", "InvalidRequestException");
                String nextToken = Pagination.parseNextToken(request, "NextToken", "InvalidRequestException");
                PaginatedResult<Map<String, Object>> page = athenaService.listDataCatalogsPage(region, maxResults, nextToken);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("DataCatalogsSummary", page.items());
                if (page.nextToken() != null) {
                    response.put("NextToken", page.nextToken());
                }
                yield Response.ok(response).build();
            }
            case "GetDataCatalog" -> Response.ok(
                    Map.of("DataCatalog", athenaService.getDataCatalog(region, text(request, "Name")))).build();
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
            case "TagResource" -> {
                athenaService.tagResource(text(request, "ResourceARN"), tags(request));
                yield Response.ok(Map.of()).build();
            }
            case "UntagResource" -> {
                athenaService.untagResource(text(request, "ResourceARN"), tagKeys(request));
                yield Response.ok(Map.of()).build();
            }
            case "ListDatabases" -> {
                String catalog = catalogName(request);
                yield Response.ok(Map.of("DatabaseList", athenaService.listDatabases(catalog))).build();
            }
            case "ListTableMetadata" -> {
                String catalog = catalogName(request);
                String database = request.path("DatabaseName").asText(request.path("Database").asText(""));
                yield Response.ok(Map.of("TableMetadataList", athenaService.listTableMetadata(catalog, database))).build();
            }
            case "GetTableMetadata" -> {
                String catalog = catalogName(request);
                String database = request.path("DatabaseName").asText(request.path("Database").asText(""));
                String tableName = request.get("TableName").asText();
                yield Response.ok(Map.of("TableMetadata", athenaService.getTableMetadata(catalog, database, tableName))).build();
            }
            case "ListTagsForResource" -> {
                String resourceArn = request.path("ResourceARN").asText(null);
                yield Response.ok(Map.of("Tags", athenaService.listTagsForResource(resourceArn))).build();
            }
            case "DeleteWorkGroup" -> {
                String wg = text(request, "WorkGroup");
                namedQueryService.deleteWorkGroup(region, wg, request.path("RecursiveDeleteOption").asBoolean(false));
                yield Response.ok(Map.of()).build();
            }
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String catalogName(JsonNode request) {
        String named = text(request, "CatalogName");
        return named != null ? named : AthenaService.DEFAULT_CATALOG;
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
