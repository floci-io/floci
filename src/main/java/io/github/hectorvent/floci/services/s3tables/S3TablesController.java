package io.github.hectorvent.floci.services.s3tables;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.s3tables.model.Namespace;
import io.github.hectorvent.floci.services.s3tables.model.S3Table;
import io.github.hectorvent.floci.services.s3tables.model.TableBucket;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Encoded;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class S3TablesController {
    private final S3TablesService service;
    private final RegionResolver regionResolver;

    @Inject
    public S3TablesController(S3TablesService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/buckets")
    public Response createTableBucket(@Context HttpHeaders headers, Map<String, Object> request) {
        TableBucket bucket = service.createTableBucket(string(request, "name"), request.get("encryptionConfiguration"),
                request.get("storageClassConfiguration"), stringMap(request.get("tags")), region(headers));
        return Response.ok(Map.of("arn", bucket.getArn())).build();
    }

    @GET
    @Path("/buckets")
    public Response listTableBuckets(@Context HttpHeaders headers, @QueryParam("prefix") String prefix) {
        List<Map<String, Object>> buckets = service.listTableBuckets(prefix, region(headers)).stream()
                .map(this::bucketRepresentation).toList();
        return Response.ok(Map.of("tableBuckets", buckets)).build();
    }

    @GET
    @Path("/buckets/{tableBucketARN}")
    public Response getTableBucket(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn) {
        return Response.ok(bucketRepresentation(service.getTableBucket(decode(tableBucketArn), region(headers)))).build();
    }

    @DELETE
    @Path("/buckets/{tableBucketARN}")
    public Response deleteTableBucket(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn) {
        service.deleteTableBucket(decode(tableBucketArn), region(headers));
        return Response.noContent().build();
    }

    @PUT
    @Path("/buckets/{tableBucketARN}/policy")
    public Response putTableBucketPolicy(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                         Map<String, Object> request) {
        service.putTableBucketPolicy(decode(tableBucketArn), string(request, "resourcePolicy"), region(headers));
        return Response.ok().build();
    }

    @GET
    @Path("/buckets/{tableBucketARN}/policy")
    public Response getTableBucketPolicy(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn) {
        return Response.ok(Map.of("resourcePolicy", service.getTableBucketPolicy(decode(tableBucketArn), region(headers)))).build();
    }

    @DELETE
    @Path("/buckets/{tableBucketARN}/policy")
    public Response deleteTableBucketPolicy(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn) {
        service.deleteTableBucketPolicy(decode(tableBucketArn), region(headers));
        return Response.noContent().build();
    }

    @PUT
    @Path("/buckets/{tableBucketARN}/maintenance/{type}")
    public Response putTableBucketMaintenanceConfiguration(@Context HttpHeaders headers,
                                                            @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                                            @PathParam("type") String type, Map<String, Object> request) {
        service.putTableBucketMaintenance(decode(tableBucketArn), type, request.get("value"), region(headers));
        return Response.ok().build();
    }

    @GET
    @Path("/buckets/{tableBucketARN}/maintenance")
    public Response getTableBucketMaintenanceConfiguration(@Context HttpHeaders headers,
                                                            @Encoded @PathParam("tableBucketARN") String tableBucketArn) {
        String arn = decode(tableBucketArn);
        return Response.ok(Map.of("tableBucketARN", arn,
                "configuration", service.getTableBucketMaintenanceConfigurations(arn, region(headers)))).build();
    }

    @PUT
    @Path("/namespaces/{tableBucketARN}")
    public Response createNamespace(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                    Map<String, Object> request) {
        String arn = decode(tableBucketArn);
        Namespace namespace = service.createNamespace(arn, stringList(request.get("namespace")), region(headers));
        return Response.ok(namespaceRepresentation(arn, namespace)).build();
    }

    @GET
    @Path("/namespaces/{tableBucketARN}")
    public Response listNamespaces(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                   @QueryParam("prefix") String prefix) {
        String arn = decode(tableBucketArn);
        List<Map<String, Object>> namespaces = service.listNamespaces(arn, prefix, region(headers)).stream()
                .map(namespace -> namespaceRepresentation(arn, namespace)).toList();
        return Response.ok(Map.of("namespaces", namespaces)).build();
    }

    @GET
    @Path("/namespaces/{tableBucketARN}/{namespace}")
    public Response getNamespace(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                 @PathParam("namespace") String namespace) {
        String arn = decode(tableBucketArn);
        return Response.ok(namespaceRepresentation(arn, service.getNamespace(arn, namespace, region(headers)))).build();
    }

    @DELETE
    @Path("/namespaces/{tableBucketARN}/{namespace}")
    public Response deleteNamespace(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                    @PathParam("namespace") String namespace) {
        service.deleteNamespace(decode(tableBucketArn), namespace, region(headers));
        return Response.noContent().build();
    }

    @PUT
    @Path("/tables/{tableBucketARN}/{namespace}")
    public Response createTable(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                @PathParam("namespace") String namespace, Map<String, Object> request) {
        S3Table table = service.createTable(decode(tableBucketArn), namespace, string(request, "name"), string(request, "format"),
                request.get("metadata"), request.get("encryptionConfiguration"), request.get("storageClassConfiguration"),
                stringMap(request.get("tags")), region(headers));
        return Response.ok(Map.of("tableARN", table.getArn(), "versionToken", table.getVersionToken())).build();
    }

    @GET
    @Path("/get-table")
    public Response getTable(@Context HttpHeaders headers, @QueryParam("tableBucketARN") String tableBucketArn,
                             @QueryParam("namespace") String namespace, @QueryParam("name") String name) {
        return Response.ok(tableRepresentation(service.getTable(decode(tableBucketArn), namespace, name, region(headers)))).build();
    }

    @GET
    @Path("/tables/{tableBucketARN}")
    public Response listTables(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                               @QueryParam("namespace") String namespace, @QueryParam("prefix") String prefix) {
        List<Map<String, Object>> tables = service.listTables(decode(tableBucketArn), namespace, prefix, region(headers)).stream()
                .map(this::tableRepresentation).toList();
        return Response.ok(Map.of("tables", tables)).build();
    }

    @DELETE
    @Path("/tables/{tableBucketARN}/{namespace}/{name}")
    public Response deleteTable(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                @PathParam("namespace") String namespace, @PathParam("name") String name) {
        service.deleteTable(decode(tableBucketArn), namespace, name, region(headers));
        return Response.noContent().build();
    }

    @PUT
    @Path("/tables/{tableBucketARN}/{namespace}/{name}/rename")
    public Response renameTable(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                @PathParam("namespace") String namespace, @PathParam("name") String name,
                                Map<String, Object> request) {
        service.renameTable(decode(tableBucketArn), namespace, name, string(request, "newNamespaceName"),
                string(request, "newName"), string(request, "versionToken"), region(headers));
        return Response.noContent().build();
    }

    @GET
    @Path("/tables/{tableBucketARN}/{namespace}/{name}/metadata-location")
    public Response getTableMetadataLocation(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                             @PathParam("namespace") String namespace, @PathParam("name") String name) {
        S3Table table = service.getTable(decode(tableBucketArn), namespace, name, region(headers));
        return Response.ok(metadataLocationRepresentation(table)).build();
    }

    @PUT
    @Path("/tables/{tableBucketARN}/{namespace}/{name}/metadata-location")
    public Response updateTableMetadataLocation(@Context HttpHeaders headers,
                                                @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                                @PathParam("namespace") String namespace, @PathParam("name") String name,
                                                Map<String, Object> request) {
        S3Table table = service.updateTableMetadataLocation(decode(tableBucketArn), namespace, name,
                string(request, "metadataLocation"), string(request, "versionToken"), region(headers));
        Map<String, Object> response = metadataLocationRepresentation(table);
        response.put("name", table.getName());
        response.put("namespace", List.of(table.getNamespace()));
        response.put("tableARN", table.getArn());
        return Response.ok(response).build();
    }

    @PUT
    @Path("/tables/{tableBucketARN}/{namespace}/{name}/policy")
    public Response putTablePolicy(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                   @PathParam("namespace") String namespace, @PathParam("name") String name,
                                   Map<String, Object> request) {
        service.putTablePolicy(decode(tableBucketArn), namespace, name, string(request, "resourcePolicy"), region(headers));
        return Response.ok().build();
    }

    @GET
    @Path("/tables/{tableBucketARN}/{namespace}/{name}/policy")
    public Response getTablePolicy(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                   @PathParam("namespace") String namespace, @PathParam("name") String name) {
        return Response.ok(Map.of("resourcePolicy", service.getTablePolicy(decode(tableBucketArn), namespace, name, region(headers)))).build();
    }

    @DELETE
    @Path("/tables/{tableBucketARN}/{namespace}/{name}/policy")
    public Response deleteTablePolicy(@Context HttpHeaders headers, @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                      @PathParam("namespace") String namespace, @PathParam("name") String name) {
        service.deleteTablePolicy(decode(tableBucketArn), namespace, name, region(headers));
        return Response.noContent().build();
    }

    @PUT
    @Path("/tables/{tableBucketARN}/{namespace}/{name}/maintenance/{type}")
    public Response putTableMaintenanceConfiguration(@Context HttpHeaders headers,
                                                      @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                                      @PathParam("namespace") String namespace, @PathParam("name") String name,
                                                      @PathParam("type") String type, Map<String, Object> request) {
        service.putTableMaintenance(decode(tableBucketArn), namespace, name, type, request.get("value"), region(headers));
        return Response.ok().build();
    }

    @GET
    @Path("/tables/{tableBucketARN}/{namespace}/{name}/maintenance")
    public Response getTableMaintenanceConfiguration(@Context HttpHeaders headers,
                                                      @Encoded @PathParam("tableBucketARN") String tableBucketArn,
                                                      @PathParam("namespace") String namespace, @PathParam("name") String name) {
        String arn = decode(tableBucketArn);
        return Response.ok(Map.of("tableARN", service.getTable(arn, namespace, name, region(headers)).getArn(),
                "configuration", service.getTableMaintenanceConfigurations(arn, namespace, name, region(headers)))).build();
    }

    private Map<String, Object> bucketRepresentation(TableBucket bucket) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("arn", bucket.getArn());
        response.put("name", bucket.getName());
        response.put("createdAt", bucket.getCreatedAt());
        response.put("ownerAccountId", bucket.getOwnerAccountId());
        putIfNotNull(response, "encryptionConfiguration", bucket.getEncryptionConfiguration());
        putIfNotNull(response, "storageClassConfiguration", bucket.getStorageClassConfiguration());
        return response;
    }

    private Map<String, Object> namespaceRepresentation(String tableBucketArn, Namespace namespace) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("namespace", List.of(namespace.getName()));
        response.put("tableBucketARN", tableBucketArn);
        response.put("createdAt", namespace.getCreatedAt());
        return response;
    }

    private Map<String, Object> tableRepresentation(S3Table table) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", table.getName());
        response.put("type", "customer");
        response.put("tableARN", table.getArn());
        response.put("namespace", List.of(table.getNamespace()));
        response.put("versionToken", table.getVersionToken());
        putIfNotNull(response, "metadataLocation", table.getMetadataLocation());
        response.put("createdAt", table.getCreatedAt());
        response.put("modifiedAt", table.getModifiedAt());
        response.put("ownerAccountId", table.getOwnerAccountId());
        response.put("format", table.getFormat());
        return response;
    }

    private Map<String, Object> metadataLocationRepresentation(S3Table table) {
        Map<String, Object> response = new LinkedHashMap<>();
        putIfNotNull(response, "metadataLocation", table.getMetadataLocation());
        response.put("versionToken", table.getVersionToken());
        return response;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String region(HttpHeaders headers) { return regionResolver.resolveRegion(headers); }
    private String decode(String value) { return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private String string(Map<String, Object> value, String key) { return value == null ? null : (String) value.get(key); }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, entry) -> result.put(String.valueOf(key), String.valueOf(entry)));
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        list.forEach(entry -> result.add(String.valueOf(entry)));
        return result;
    }
}
