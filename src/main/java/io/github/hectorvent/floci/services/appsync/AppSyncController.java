package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appsync.model.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppSyncController {
    private static final Logger LOG = Logger.getLogger(AppSyncController.class);

    private final AppSyncService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AppSyncController(AppSyncService service,
                              RegionResolver regionResolver,
                              ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── GraphQL API ────────────────────────────

    @POST
    @Path("/v1/apis")
    public Response createGraphqlApi(@Context HttpHeaders headers, String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        GraphqlApi api = service.createGraphqlApi(request, region);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("graphqlApi", objectMapper.valueToTree(api));
        return Response.status(200).entity(root).build();
    }

    @GET
    @Path("/v1/apis/{apiId}")
    public Response getGraphqlApi(@PathParam("apiId") String apiId) {
        GraphqlApi api = service.getGraphqlApi(apiId);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("graphqlApi", objectMapper.valueToTree(api));
        return Response.ok(root).build();
    }

    @POST
    @Path("/v1/apis/{apiId}")
    public Response updateGraphqlApi(@Context HttpHeaders headers,
                                     @PathParam("apiId") String apiId,
                                     String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        GraphqlApi api = service.updateGraphqlApi(apiId, request, region);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("graphqlApi", objectMapper.valueToTree(api));
        return Response.ok(root).build();
    }

    @DELETE
    @Path("/v1/apis/{apiId}")
    public Response deleteGraphqlApi(@PathParam("apiId") String apiId) {
        service.deleteGraphqlApi(apiId);
        return Response.noContent().build();
    }

    @GET
    @Path("/v1/apis")
    public Response listGraphqlApis(@QueryParam("maxResults") Integer maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        var page = service.listGraphqlApis(maxResults, nextToken);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("graphqlApis");
        page.items().forEach(items::addPOJO);
        if (page.nextToken() != null) {
            root.put("nextToken", page.nextToken());
        } else {
            root.putNull("nextToken");
        }
        return Response.ok(root).build();
    }

    // ──────────────────────────── Schema ────────────────────────────

    @POST
    @Path("/v1/apis/{apiId}/schemacreation")
    public Response startSchemaCreation(@PathParam("apiId") String apiId, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        String definition = (String) request.get("definition");
        if (definition != null) {
            try {
                definition = new String(java.util.Base64.getDecoder().decode(definition), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // Not base64, use as-is
            }
        }
        service.startSchemaCreation(apiId, definition);
        SchemaCreationStatus status = new SchemaCreationStatus();
        status.setStatus(SchemaCreationStatusType.ACTIVE);
        return Response.ok(status).build();
    }

    @GET
    @Path("/v1/apis/{apiId}/schemacreation")
    public Response getSchemaCreationStatus(@PathParam("apiId") String apiId) {
        return Response.ok(service.getSchemaCreationStatus(apiId)).build();
    }

    @GET
    @Path("/v1/apis/{apiId}/schema")
    public Response getIntrospectionSchema(@PathParam("apiId") String apiId) {
        String schema = service.getIntrospectionSchema(apiId);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema", schema);
        return Response.ok(root).build();
    }

    // ──────────────────────────── Data Sources ────────────────────────────

    @POST
    @Path("/v1/apis/{apiId}/datasources")
    public Response createDataSource(@PathParam("apiId") String apiId, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        DataSource ds = service.createDataSource(apiId, request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("dataSource", objectMapper.valueToTree(ds));
        return Response.status(200).entity(root).build();
    }

    @GET
    @Path("/v1/apis/{apiId}/datasources/{name}")
    public Response getDataSource(@PathParam("apiId") String apiId, @PathParam("name") String name) {
        DataSource ds = service.getDataSource(apiId, name);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("dataSource", objectMapper.valueToTree(ds));
        return Response.ok(root).build();
    }

    @POST
    @Path("/v1/apis/{apiId}/datasources/{name}")
    public Response updateDataSource(@PathParam("apiId") String apiId,
                                     @PathParam("name") String name,
                                     String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        DataSource ds = service.updateDataSource(apiId, name, request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("dataSource", objectMapper.valueToTree(ds));
        return Response.ok(root).build();
    }

    @DELETE
    @Path("/v1/apis/{apiId}/datasources/{name}")
    public Response deleteDataSource(@PathParam("apiId") String apiId,
                                     @PathParam("name") String name) {
        service.deleteDataSource(apiId, name);
        return Response.noContent().build();
    }

    @GET
    @Path("/v1/apis/{apiId}/datasources")
    public Response listDataSources(@PathParam("apiId") String apiId,
                                    @QueryParam("maxResults") Integer maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        var page = service.listDataSources(apiId, maxResults, nextToken);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("dataSources");
        page.items().forEach(items::addPOJO);
        if (page.nextToken() != null) {
            root.put("nextToken", page.nextToken());
        } else {
            root.putNull("nextToken");
        }
        return Response.ok(root).build();
    }

    // ──────────────────────────── Resolvers ────────────────────────────

    @POST
    @Path("/v1/apis/{apiId}/types/{typeName}/resolvers")
    public Response createResolver(@PathParam("apiId") String apiId,
                                   @PathParam("typeName") String typeName,
                                   String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        request.put("typeName", typeName);
        Resolver resolver = service.createResolver(apiId, request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("resolver", objectMapper.valueToTree(resolver));
        return Response.status(200).entity(root).build();
    }

    @GET
    @Path("/v1/apis/{apiId}/resolvers")
    public Response listResolvers(@PathParam("apiId") String apiId,
                                  @QueryParam("maxResults") Integer maxResults,
                                  @QueryParam("nextToken") String nextToken) {
        var page = service.listResolvers(apiId, maxResults, nextToken);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("resolvers");
        page.items().forEach(items::addPOJO);
        if (page.nextToken() != null) {
            root.put("nextToken", page.nextToken());
        } else {
            root.putNull("nextToken");
        }
        return Response.ok(root).build();
    }

    @GET
    @Path("/v1/apis/{apiId}/types/{typeName}/resolvers")
    public Response listResolversByType(@PathParam("apiId") String apiId,
                                        @PathParam("typeName") String typeName,
                                        @QueryParam("maxResults") Integer maxResults,
                                        @QueryParam("nextToken") String nextToken) {
        var page = service.listResolversByType(apiId, typeName, maxResults, nextToken);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("resolvers");
        page.items().forEach(items::addPOJO);
        if (page.nextToken() != null) {
            root.put("nextToken", page.nextToken());
        } else {
            root.putNull("nextToken");
        }
        return Response.ok(root).build();
    }

    @GET
    @Path("/v1/apis/{apiId}/types/{typeName}/resolvers/{fieldName}")
    public Response getResolver(@PathParam("apiId") String apiId,
                                @PathParam("typeName") String typeName,
                                @PathParam("fieldName") String fieldName) {
        Resolver resolver = service.getResolver(apiId, typeName, fieldName);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("resolver", objectMapper.valueToTree(resolver));
        return Response.ok(root).build();
    }

    @POST
    @Path("/v1/apis/{apiId}/types/{typeName}/resolvers/{fieldName}")
    public Response updateResolver(@PathParam("apiId") String apiId,
                                   @PathParam("typeName") String typeName,
                                   @PathParam("fieldName") String fieldName,
                                   String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        Resolver resolver = service.updateResolver(apiId, typeName, fieldName, request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("resolver", objectMapper.valueToTree(resolver));
        return Response.ok(root).build();
    }

    @DELETE
    @Path("/v1/apis/{apiId}/types/{typeName}/resolvers/{fieldName}")
    public Response deleteResolver(@PathParam("apiId") String apiId,
                                   @PathParam("typeName") String typeName,
                                   @PathParam("fieldName") String fieldName) {
        service.deleteResolver(apiId, typeName, fieldName);
        return Response.noContent().build();
    }

    @GET
    @Path("/v1/apis/{apiId}/functions/{functionId}/resolvers")
    public Response listResolversByFunction(@PathParam("apiId") String apiId,
                                            @PathParam("functionId") String functionId,
                                            @QueryParam("maxResults") Integer maxResults,
                                            @QueryParam("nextToken") String nextToken) {
        var page = service.listResolversByFunction(apiId, functionId, maxResults, nextToken);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("resolvers");
        page.items().forEach(items::addPOJO);
        if (page.nextToken() != null) {
            root.put("nextToken", page.nextToken());
        } else {
            root.putNull("nextToken");
        }
        return Response.ok(root).build();
    }

    // ──────────────────────────── Functions ────────────────────────────

    @POST
    @Path("/v1/apis/{apiId}/functions")
    public Response createFunction(@Context HttpHeaders headers,
                                   @PathParam("apiId") String apiId, String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        FunctionConfiguration fn = service.createFunction(apiId, request, region);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("functionConfiguration", objectMapper.valueToTree(fn));
        return Response.status(200).entity(root).build();
    }

    @GET
    @Path("/v1/apis/{apiId}/functions/{functionId}")
    public Response getFunction(@PathParam("apiId") String apiId, @PathParam("functionId") String functionId) {
        FunctionConfiguration fn = service.getFunction(apiId, functionId);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("functionConfiguration", objectMapper.valueToTree(fn));
        return Response.ok(root).build();
    }

    @POST
    @Path("/v1/apis/{apiId}/functions/{functionId}")
    public Response updateFunction(@PathParam("apiId") String apiId,
                                   @PathParam("functionId") String functionId,
                                   String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        FunctionConfiguration fn = service.updateFunction(apiId, functionId, request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("functionConfiguration", objectMapper.valueToTree(fn));
        return Response.ok(root).build();
    }

    @DELETE
    @Path("/v1/apis/{apiId}/functions/{functionId}")
    public Response deleteFunction(@PathParam("apiId") String apiId,
                                   @PathParam("functionId") String functionId) {
        service.deleteFunction(apiId, functionId);
        return Response.noContent().build();
    }

    @GET
    @Path("/v1/apis/{apiId}/functions")
    public Response listFunctions(@PathParam("apiId") String apiId,
                                  @QueryParam("maxResults") Integer maxResults,
                                  @QueryParam("nextToken") String nextToken) {
        var page = service.listFunctions(apiId, maxResults, nextToken);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("functions");
        page.items().forEach(items::addPOJO);
        if (page.nextToken() != null) {
            root.put("nextToken", page.nextToken());
        } else {
            root.putNull("nextToken");
        }
        return Response.ok(root).build();
    }

    // ──────────────────────────── Types ────────────────────────────

    @POST
    @Path("/v1/apis/{apiId}/types")
    public Response createType(@PathParam("apiId") String apiId, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        AppSyncType type = service.createType(apiId, request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("type", objectMapper.valueToTree(type));
        return Response.status(200).entity(root).build();
    }

    @GET
    @Path("/v1/apis/{apiId}/types/{typeName}")
    public Response getType(@PathParam("apiId") String apiId, @PathParam("typeName") String typeName) {
        AppSyncType type = service.getType(apiId, typeName);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("type", objectMapper.valueToTree(type));
        return Response.ok(root).build();
    }

    @POST
    @Path("/v1/apis/{apiId}/types/{typeName}")
    public Response updateType(@PathParam("apiId") String apiId,
                               @PathParam("typeName") String typeName,
                               String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        AppSyncType type = service.updateType(apiId, typeName, request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("type", objectMapper.valueToTree(type));
        return Response.ok(root).build();
    }

    @DELETE
    @Path("/v1/apis/{apiId}/types/{typeName}")
    public Response deleteType(@PathParam("apiId") String apiId,
                               @PathParam("typeName") String typeName) {
        service.deleteType(apiId, typeName);
        return Response.noContent().build();
    }

    @GET
    @Path("/v1/apis/{apiId}/types")
    public Response listTypes(@PathParam("apiId") String apiId,
                              @QueryParam("maxResults") Integer maxResults,
                              @QueryParam("nextToken") String nextToken) {
        var page = service.listTypes(apiId, maxResults, nextToken);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("types");
        page.items().forEach(items::addPOJO);
        if (page.nextToken() != null) {
            root.put("nextToken", page.nextToken());
        } else {
            root.putNull("nextToken");
        }
        return Response.ok(root).build();
    }

    // ──────────────────────────── API Keys ────────────────────────────

    @POST
    @Path("/v1/apis/{apiId}/apikeys")
    public Response createApiKey(@PathParam("apiId") String apiId, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        ApiKey key = service.createApiKey(apiId, request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("apiKey", objectMapper.valueToTree(key));
        return Response.status(200).entity(root).build();
    }

    @GET
    @Path("/v1/apis/{apiId}/apikeys")
    public Response listApiKeys(@PathParam("apiId") String apiId,
                                @QueryParam("maxResults") Integer maxResults,
                                @QueryParam("nextToken") String nextToken) {
        var page = service.listApiKeys(apiId, maxResults, nextToken);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("apiKeys");
        page.items().forEach(items::addPOJO);
        if (page.nextToken() != null) {
            root.put("nextToken", page.nextToken());
        } else {
            root.putNull("nextToken");
        }
        return Response.ok(root).build();
    }

    @GET
    @Path("/v1/apis/{apiId}/apikeys/{keyId}")
    public Response getApiKey(@PathParam("apiId") String apiId, @PathParam("keyId") String keyId) {
        ApiKey key = service.getApiKey(apiId, keyId);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("apiKey", objectMapper.valueToTree(key));
        return Response.ok(root).build();
    }

    @POST
    @Path("/v1/apis/{apiId}/apikeys/{keyId}")
    public Response updateApiKey(@PathParam("apiId") String apiId,
                                 @PathParam("keyId") String keyId,
                                 String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        ApiKey key = service.updateApiKey(apiId, keyId, request);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("apiKey", objectMapper.valueToTree(key));
        return Response.ok(root).build();
    }

    @DELETE
    @Path("/v1/apis/{apiId}/apikeys/{keyId}")
    public Response deleteApiKey(@PathParam("apiId") String apiId, @PathParam("keyId") String keyId) {
        service.deleteApiKey(apiId, keyId);
        return Response.noContent().build();
    }

    // ──────────────────────────── Tags ────────────────────────────

    @POST
    @Path("/v1/tags/{resourceArn: .+}")
    public Response tagResource(@PathParam("resourceArn") String resourceArn, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> tags = (Map<String, String>) request.get("tags");
        service.tagResource(resourceArn, tags);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/v1/tags/{resourceArn: .+}")
    public Response untagResource(@PathParam("resourceArn") String resourceArn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        service.untagResource(resourceArn, tagKeys);
        return Response.noContent().build();
    }

    @GET
    @Path("/v1/tags/{resourceArn: .+}")
    public Response listTagsForResource(@PathParam("resourceArn") String resourceArn) {
        Map<String, String> tags = service.getTags(resourceArn);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode tagsNode = root.putObject("tags");
        tags.forEach(tagsNode::put);
        return Response.ok(root).build();
    }

    // ──────────────────────────── Environment Variables ────────────────────────────

    @GET
    @Path("/v1/apis/{apiId}/environmentvariables")
    public Response getEnvironmentVariables(@PathParam("apiId") String apiId) {
        Map<String, String> envVars = service.getEnvironmentVariables(apiId);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode envNode = root.putObject("environmentVariables");
        envVars.forEach(envNode::put);
        return Response.ok(root).build();
    }

    @PUT
    @Path("/v1/apis/{apiId}/environmentvariables")
    public Response putEnvironmentVariables(@PathParam("apiId") String apiId,
                                            String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> envVars = (Map<String, String>) request.get("environmentVariables");
        if (envVars == null) {
            envVars = Map.of();
        }
        Map<String, String> result = service.putEnvironmentVariables(apiId, envVars);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode envNode = root.putObject("environmentVariables");
        result.forEach(envNode::put);
        return Response.ok(root).build();
    }

}
