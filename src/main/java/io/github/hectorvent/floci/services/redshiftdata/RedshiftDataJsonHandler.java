package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class RedshiftDataJsonHandler {

    private final RedshiftDataService service;

    @Inject
    public RedshiftDataJsonHandler(RedshiftDataService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "ExecuteStatement" -> Response.ok(service.executeStatement(request, region)).build();
            case "BatchExecuteStatement" -> Response.ok(service.batchExecuteStatement(request, region)).build();
            case "DescribeStatement" -> Response.ok(service.describeStatement(request)).build();
            case "GetStatementResult" -> Response.ok(service.getStatementResult(request)).build();
            case "GetStatementResultV2" -> Response.ok(service.getStatementResultV2(request)).build();
            case "ListStatements" -> Response.ok(service.listStatements(request)).build();
            case "CancelStatement" -> Response.ok(service.cancelStatement(request)).build();
            case "ListDatabases" -> Response.ok(service.listDatabases(request, region)).build();
            case "ListSchemas" -> Response.ok(service.listSchemas(request, region)).build();
            case "ListTables" -> Response.ok(service.listTables(request, region)).build();
            case "DescribeTable" -> Response.ok(service.describeTable(request, region)).build();
            case "ExecuteSql", "BatchExecuteSql" -> throw new AwsException("ValidationException",
                    action + " is a deprecated operation and is not supported.", 400);
            default -> throw new AwsException("ValidationException",
                    "Operation " + action + " is not supported by the Floci Redshift Data API.", 400);
        };
    }
}
