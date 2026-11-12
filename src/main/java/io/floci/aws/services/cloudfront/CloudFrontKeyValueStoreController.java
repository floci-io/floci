package io.floci.aws.services.cloudfront;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.services.cloudfront.model.KeyValueStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code cloudfront-keyvaluestore} data plane: reads and writes the keys of a key value store
 * that was created through the CloudFront control plane. Requests name the store by its ARN in the path.
 */
@Path("/key-value-stores")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class CloudFrontKeyValueStoreController {

    private static final Logger LOG = Logger.getLogger(CloudFrontKeyValueStoreController.class);

    private static final String ETAG = "ETag";
    private static final String KVS_ARN = "KvsARN";
    private static final String KEY = "Key";
    private static final String VALUE = "Value";
    private static final String ITEM_COUNT = "ItemCount";
    private static final String TOTAL_SIZE_IN_BYTES = "TotalSizeInBytes";

    private final CloudFrontKeyValueStoreService service;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudFrontKeyValueStoreController(CloudFrontKeyValueStoreService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/{kvsArn}")
    public Response describeKeyValueStore(@PathParam("kvsArn") String kvsArn) {
        return handle(() -> {
            ObjectNode request = objectMapper.createObjectNode().put(KVS_ARN, kvsArn);
            CloudFrontKeyValueStoreRequestShapes.check("DescribeKeyValueStore", request);
            KeyValueStore kvs = service.describeByArn(kvsArn);
            ObjectNode body = objectMapper.createObjectNode();
            body.put(ITEM_COUNT, CloudFrontKeyValueStoreService.itemCount(kvs));
            body.put(TOTAL_SIZE_IN_BYTES, CloudFrontKeyValueStoreService.totalSizeInBytes(kvs));
            body.put("KvsARN", kvs.getArn());
            body.put("Created", epochSeconds(kvs.getCreated()));
            body.put("LastModified", epochSeconds(kvs.getLastModifiedTime()));
            body.put("Status", kvs.getStatus());
            return Response.ok(body).header(ETAG, kvs.getDataEtag()).build();
        });
    }

    @GET
    @Path("/{kvsArn}/keys")
    public Response listKeys(@PathParam("kvsArn") String kvsArn,
                             @QueryParam("NextToken") String nextToken,
                             @QueryParam("MaxResults") String maxResults) {
        return handle(() -> {
            Integer pageSize = Pagination.parseMaxResults(maxResults, "ValidationException");
            ObjectNode request = objectMapper.createObjectNode().put(KVS_ARN, kvsArn);
            if (nextToken != null) {
                request.put("NextToken", nextToken);
            }
            if (pageSize != null) {
                request.put("MaxResults", pageSize);
            }
            CloudFrontKeyValueStoreRequestShapes.check("ListKeys", request);
            CloudFrontKeyValueStoreService.KeyPage page = service.listKeys(kvsArn, nextToken, pageSize);
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode items = body.putArray("Items");
            for (Map.Entry<String, String> entry : page.items()) {
                items.addObject().put(KEY, entry.getKey()).put(VALUE, entry.getValue());
            }
            if (page.nextToken() != null) {
                body.put("NextToken", page.nextToken());
            }
            return Response.ok(body).build();
        });
    }

    @POST
    @Path("/{kvsArn}/keys")
    public Response updateKeys(@PathParam("kvsArn") String kvsArn,
                               @HeaderParam("If-Match") String ifMatch,
                               String requestBody) {
        return handle(() -> {
            if (requestBody != null && requestBody.getBytes(StandardCharsets.UTF_8).length
                    > CloudFrontKeyValueStoreService.MAX_UPDATE_PAYLOAD_BYTES) {
                throw new AwsException("ValidationException", "The request payload is too large.", 400);
            }
            ObjectNode request = operationRequest(requestBody, kvsArn, null, ifMatch);
            CloudFrontKeyValueStoreRequestShapes.check("UpdateKeys", request);
            if (request.path("Puts").size() + request.path("Deletes").size()
                    > CloudFrontKeyValueStoreService.MAX_KEY_UPDATES) {
                throw new AwsException("ValidationException",
                        "At most " + CloudFrontKeyValueStoreService.MAX_KEY_UPDATES
                                + " keys can be updated in one request.", 400);
            }
            Map<String, String> puts = new LinkedHashMap<>();
            for (JsonNode put : request.path("Puts")) {
                puts.put(put.get(KEY).textValue(), put.get(VALUE).textValue());
            }
            List<String> deletes = new ArrayList<>();
            for (JsonNode delete : request.path("Deletes")) {
                deletes.add(delete.get(KEY).textValue());
            }
            return sizeResponse(service.updateKeys(kvsArn, ifMatch, puts, deletes));
        });
    }

    @GET
    @Path("/{kvsArn}/keys/{key}")
    public Response getKey(@PathParam("kvsArn") String kvsArn, @PathParam("key") String key) {
        return handle(() -> {
            ObjectNode request = objectMapper.createObjectNode().put(KVS_ARN, kvsArn).put(KEY, key);
            CloudFrontKeyValueStoreRequestShapes.check("GetKey", request);
            CloudFrontKeyValueStoreService.KeyLookup lookup = service.getKey(kvsArn, key);
            KeyValueStore kvs = lookup.store();
            ObjectNode body = objectMapper.createObjectNode();
            body.put(KEY, lookup.key());
            body.put(VALUE, lookup.value());
            body.put(ITEM_COUNT, CloudFrontKeyValueStoreService.itemCount(kvs));
            body.put(TOTAL_SIZE_IN_BYTES, CloudFrontKeyValueStoreService.totalSizeInBytes(kvs));
            return Response.ok(body).build();
        });
    }

    @PUT
    @Path("/{kvsArn}/keys/{key}")
    public Response putKey(@PathParam("kvsArn") String kvsArn,
                           @PathParam("key") String key,
                           @HeaderParam("If-Match") String ifMatch,
                           String requestBody) {
        return handle(() -> {
            ObjectNode request = operationRequest(requestBody, kvsArn, key, ifMatch);
            CloudFrontKeyValueStoreRequestShapes.check("PutKey", request);
            return sizeResponse(service.putKey(kvsArn, ifMatch, key, request.get(VALUE).textValue()));
        });
    }

    @DELETE
    @Path("/{kvsArn}/keys/{key}")
    public Response deleteKey(@PathParam("kvsArn") String kvsArn,
                              @PathParam("key") String key,
                              @HeaderParam("If-Match") String ifMatch) {
        return handle(() -> {
            ObjectNode request = operationRequest(null, kvsArn, key, ifMatch);
            CloudFrontKeyValueStoreRequestShapes.check("DeleteKey", request);
            return sizeResponse(service.deleteKey(kvsArn, ifMatch, key));
        });
    }

    private Response sizeResponse(KeyValueStore kvs) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put(ITEM_COUNT, CloudFrontKeyValueStoreService.itemCount(kvs));
        body.put(TOTAL_SIZE_IN_BYTES, CloudFrontKeyValueStoreService.totalSizeInBytes(kvs));
        return Response.ok(body).header(ETAG, kvs.getDataEtag()).build();
    }

    private ObjectNode readBody(String requestBody) {
        try {
            JsonNode body = requestBody == null || requestBody.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(requestBody);
            if (!body.isObject()) {
                throw new AwsException("ValidationException", "The request body must be a JSON object.", 400);
            }
            return (ObjectNode) body;
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationException", "Malformed JSON request body.", 400);
        }
    }

    /**
     * The request as the model reads it: the members of the body, then those the path and the {@code If-Match}
     * header carry, which a body cannot override.
     */
    private ObjectNode operationRequest(String requestBody, String kvsArn, String key, String ifMatch) {
        ObjectNode request = readBody(requestBody).deepCopy();
        request.remove("IfMatch");
        request.put(KVS_ARN, kvsArn);
        if (key != null) {
            request.put(KEY, key);
        }
        if (ifMatch != null) {
            request.put("IfMatch", ifMatch);
        }
        return request;
    }
    private static BigDecimal epochSeconds(Instant instant) {
        return BigDecimal.valueOf(instant == null ? 0 : instant.toEpochMilli(), 3);
    }

    private Response handle(Handler handler) {
        try {
            return handler.handle();
        } catch (AwsException e) {
            return error(e.getHttpStatus(), e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Error processing CloudFront key value store request", e);
            return error(500, "InternalServerException", e.getMessage());
        }
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", code)
                .entity(new AwsErrorResponse(code, message))
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle();
    }
}
