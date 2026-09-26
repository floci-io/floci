package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Call;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * The public DynamoDB Streams entry point, for requests with X-Amz-Target prefix
 * {@code DynamoDBStreams_20120810.}. It resolves the caller's account and region and dispatches
 * through the selected backend.
 */
@ApplicationScoped
public class DynamoDbStreamsJsonHandler {

    private final DynamoDbOperations operations;
    private final RegionResolver regionResolver;

    @Inject
    public DynamoDbStreamsJsonHandler(DynamoDbOperations operations, RegionResolver regionResolver) {
        this.operations = operations;
        this.regionResolver = regionResolver;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        Scope scope = new Scope(regionResolver.getAccountId(),
                region != null ? region : regionResolver.getDefaultRegion());
        return DynamoDbJsonHandler.toResponse(
                operations.execute(new Call(scope, Api.DYNAMODB_STREAMS, action, request)));
    }
}
