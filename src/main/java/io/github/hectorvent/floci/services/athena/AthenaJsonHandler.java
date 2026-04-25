package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
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
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }
}
