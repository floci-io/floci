package io.floci.aws.services.cloudfront;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
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
                             @QueryParam("MaxResults") Integer maxResults) {
        return handle(() -> {
            CloudFrontKeyValueStoreService.KeyPage page = service.listKeys(kvsArn, nextToken, maxResults);
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
            JsonNode request = readBody(requestBody);
            if (request.path("Puts").size() + request.path("Deletes").size()
                    > CloudFrontKeyValueStoreService.MAX_KEY_UPDATES) {
                throw new AwsException("ValidationException",
                        "At most " + CloudFrontKeyValueStoreService.MAX_KEY_UPDATES
                                + " keys can be updated in one request.", 400);
            }
            Map<String, String> puts = new LinkedHashMap<>();
            for (JsonNode put : request.path("Puts")) {
                puts.put(requiredText(put, KEY), requiredText(put, VALUE));
            }
            List<String> deletes = new ArrayList<>();
            for (JsonNode delete : request.path("Deletes")) {
                deletes.add(requiredText(delete, KEY));
            }
            return sizeResponse(service.updateKeys(kvsArn, ifMatch, puts, deletes));
        });
    }

    @GET
    @Path("/{kvsArn}/keys/{key}")
    public Response getKey(@PathParam("kvsArn") String kvsArn, @PathParam("key") String key) {
        return handle(() -> {
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
            JsonNode request = readBody(requestBody);
            if (!request.hasNonNull(VALUE)) {
                throw new AwsException("ValidationException", "Value is required.", 400);
            }
            return sizeResponse(service.putKey(kvsArn, ifMatch, key, request.get(VALUE).asText()));
        });
    }

    @DELETE
    @Path("/{kvsArn}/keys/{key}")
    public Response deleteKey(@PathParam("kvsArn") String kvsArn,
                              @PathParam("key") String key,
                              @HeaderParam("If-Match") String ifMatch) {
        return handle(() -> sizeResponse(service.deleteKey(kvsArn, ifMatch, key)));
    }

    private Response sizeResponse(KeyValueStore kvs) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put(ITEM_COUNT, CloudFrontKeyValueStoreService.itemCount(kvs));
        body.put(TOTAL_SIZE_IN_BYTES, CloudFrontKeyValueStoreService.totalSizeInBytes(kvs));
        return Response.ok(body).header(ETAG, kvs.getDataEtag()).build();
    }

    private JsonNode readBody(String requestBody) {
        try {
            return requestBody == null || requestBody.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(requestBody);
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationException", "Malformed JSON request body.", 400);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return node.get(field).asText();
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
