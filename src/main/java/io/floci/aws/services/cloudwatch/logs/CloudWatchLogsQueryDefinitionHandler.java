package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinition;
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinition.QueryParameter;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

class CloudWatchLogsQueryDefinitionHandler {

    private final CloudWatchLogsQueryDefinitionService service;
    private final ObjectMapper mapper;

    CloudWatchLogsQueryDefinitionHandler(CloudWatchLogsQueryDefinitionService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    Response handle(String action, JsonNode request, String region) {
        CloudWatchLogsRequestShapes.check(action, request);
        return switch (action) {
            case "PutQueryDefinition" -> put(request, region);
            case "DescribeQueryDefinitions" -> describe(request, region);
            case "DeleteQueryDefinition" -> Response.ok(mapper.createObjectNode().put("success",
                    service.deleteQueryDefinition(text(request, "queryDefinitionId"), region))).build();
            default -> throw new IllegalStateException("Not a query definition action: " + action);
        };
    }

    private Response put(JsonNode request, String region) {
        List<String> groups = new ArrayList<>();
        for (JsonNode group : request.path("logGroupNames")) {
            groups.add(group.textValue());
        }
        List<QueryParameter> parameters = new ArrayList<>();
        for (JsonNode parameter : request.path("parameters")) {
            parameters.add(new QueryParameter(text(parameter, "name"), text(parameter, "defaultValue"),
                    text(parameter, "description")));
        }
        QueryDefinition definition = new QueryDefinition(text(request, "queryDefinitionId"), text(request, "name"),
                text(request, "queryString"), groups, text(request, "queryLanguage"), parameters, 0,
                text(request, "clientToken"));
        return Response.ok(mapper.createObjectNode().put("queryDefinitionId",
                service.putQueryDefinition(definition, region))).build();
    }

    private Response describe(JsonNode request, String region) {
        PaginatedResult<QueryDefinition> page = service.describeQueryDefinitions(
                text(request, "queryDefinitionNamePrefix"), text(request, "queryLanguage"),
                request.hasNonNull("maxResults") ? request.get("maxResults").intValue() : null,
                text(request, "nextToken"), region);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode definitions = response.putArray("queryDefinitions");
        for (QueryDefinition definition : page.items()) {
            ObjectNode node = definitions.addObject();
            node.put("queryDefinitionId", definition.queryDefinitionId());
            node.put("name", definition.name());
            node.put("queryString", definition.queryString());
            node.put("queryLanguage", definition.queryLanguage());
            node.put("lastModified", definition.lastModified());
            ArrayNode groups = node.putArray("logGroupNames");
            definition.logGroupNames().forEach(groups::add);
            ArrayNode parameters = node.putArray("parameters");
            for (QueryParameter parameter : definition.parameters()) {
                ObjectNode value = parameters.addObject().put("name", parameter.name());
                if (parameter.defaultValue() != null) {
                    value.put("defaultValue", parameter.defaultValue());
                }
                if (parameter.description() != null) {
                    value.put("description", parameter.description());
                }
            }
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        return value == null || value.isNull() ? null : value.textValue();
    }
}
