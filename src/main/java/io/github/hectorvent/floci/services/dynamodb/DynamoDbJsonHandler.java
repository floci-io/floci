package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Call;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Reply;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * The public DynamoDB entry point used by the JSON and CBOR controllers, API Gateway AWS
 * integrations, AppSync and Step Functions. It resolves the caller's account and region and
 * dispatches through the selected backend. CRC32, JSON 1.0 and CBOR conversion stay in the controllers.
 */
@ApplicationScoped
public class DynamoDbJsonHandler {

    private final DynamoDbOperations operations;
    private final RegionResolver regionResolver;

    @Inject
    public DynamoDbJsonHandler(DynamoDbOperations operations, RegionResolver regionResolver) {
        this.operations = operations;
        this.regionResolver = regionResolver;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        Scope scope = new Scope(regionResolver.getAccountId(),
                region != null ? region : regionResolver.getDefaultRegion());
        return toResponse(operations.execute(new Call(scope, Api.DYNAMODB, action, request)));
    }

    static Response toResponse(Reply reply) {
        Response.ResponseBuilder builder = Response.status(reply.status()).entity(reply.body());
        for (Map.Entry<String, List<String>> header : reply.headers().entrySet()) {
            for (String value : header.getValue()) {
                builder.header(header.getKey(), value);
            }
        }
        return builder.build();
    }
}
