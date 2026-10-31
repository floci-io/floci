package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/2019-09-30/functions/{functionName}/provisioned-concurrency")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaProvisionedConcurrencyController {
    private final ProvisionedConcurrencyPool pool;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public LambdaProvisionedConcurrencyController(ProvisionedConcurrencyPool pool,
                                                 RegionResolver regions, ObjectMapper mapper) {
        this.pool = pool;
        this.regions = regions;
        this.mapper = mapper;
    }

    @PUT
    public Response put(@Context HttpHeaders headers, @PathParam("functionName") String name,
                        @QueryParam("Qualifier") String qualifier, String body) {
        int count;
        try {
            JsonNode request = mapper.readTree(body);
            JsonNode raw = request == null ? null : request.get("ProvisionedConcurrentExecutions");
            if (raw == null || raw.isNull()) {
                throw ProvisionedConcurrencyPool.invalidCount(null);
            }
            if (raw.isTextual() || raw.isBoolean()) {
                String type = raw.isTextual() ? "STRING_VALUE" : raw.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE";
                throw new AwsException("SerializationException", type + " can not be converted to an Integer", 400);
            }
            if (!raw.isNumber() || !raw.canConvertToInt()) {
                throw new IllegalArgumentException();
            }
            count = raw.intValue();
            if (count < 1) {
                throw ProvisionedConcurrencyPool.invalidCount(count);
            }
        } catch (AwsException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AwsException("InvalidParameterValueException",
                    "ProvisionedConcurrentExecutions must be a positive integer", 400);
        }
        return Response.status(202).entity(wire(pool.put(regions.resolveRegion(headers), name,
                qualifier, count, regions.getAccountId()))).build();
    }

    @GET
    public Response get(@Context HttpHeaders headers, @PathParam("functionName") String name,
                        @QueryParam("Qualifier") String qualifier, @QueryParam("List") String list,
                        @QueryParam("MaxItems") String maxItems, @QueryParam("Marker") String marker) {
        if ("ALL".equals(list)) {
            PaginatedResult<ProvisionedConcurrencyPool.ListedConfiguration> page = pool.list(
                    regions.resolveRegion(headers), name, regions.getAccountId(), maxItems, marker);
            ObjectNode result = mapper.createObjectNode();
            ArrayNode configurations = result.putArray("ProvisionedConcurrencyConfigs");
            for (ProvisionedConcurrencyPool.ListedConfiguration entry : page.items()) {
                configurations.add(wire(entry.status()).put("FunctionArn", entry.functionArn()));
            }
            if (page.nextToken() != null) {
                result.put("NextMarker", page.nextToken());
            }
            return Response.ok(result).build();
        }
        return Response.ok(wire(pool.get(regions.resolveRegion(headers), name, qualifier,
                regions.getAccountId()))).build();
    }

    @DELETE
    public Response delete(@Context HttpHeaders headers, @PathParam("functionName") String name,
                           @QueryParam("Qualifier") String qualifier) {
        pool.delete(regions.resolveRegion(headers), name, qualifier, regions.getAccountId());
        return Response.noContent().build();
    }

    private ObjectNode wire(ProvisionedConcurrencyPool.Status value) {
        ObjectNode result = mapper.createObjectNode();
        result.put("RequestedProvisionedConcurrentExecutions", value.requested());
        result.put("AllocatedProvisionedConcurrentExecutions", value.allocated());
        result.put("AvailableProvisionedConcurrentExecutions", value.available());
        result.put("Status", value.status());
        result.put("LastModified", value.lastModified());
        if (value.reason() != null) {
            result.put("StatusReason", value.reason());
        }
        return result;
    }
}
