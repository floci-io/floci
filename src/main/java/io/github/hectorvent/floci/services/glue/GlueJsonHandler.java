package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.Registry;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.RegistryId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.Schema;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class GlueJsonHandler {

    private final GlueService glueService;
    private final GlueSchemaRegistryService schemaRegistryService;
    private final ObjectMapper mapper;

    @Inject
    public GlueJsonHandler(GlueService glueService,
                           GlueSchemaRegistryService schemaRegistryService,
                           ObjectMapper mapper) {
        this.glueService = glueService;
        this.schemaRegistryService = schemaRegistryService;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateDatabase" -> {
                Database db = mapper.treeToValue(request.get("DatabaseInput"), Database.class);
                glueService.createDatabase(db);
                yield Response.ok().build();
            }
            case "GetDatabase" -> {
                String name = request.get("Name").asText();
                Database db = glueService.getDatabase(name);
                yield Response.ok(Map.of("Database", db)).build();
            }
            case "GetDatabases" -> {
                yield Response.ok(Map.of("DatabaseList", glueService.getDatabases())).build();
            }
            case "CreateTable" -> {
                String dbName = request.get("DatabaseName").asText();
                Table table = mapper.treeToValue(request.get("TableInput"), Table.class);
                glueService.createTable(dbName, table);
                yield Response.ok().build();
            }
            case "GetTable" -> {
                String dbName = request.get("DatabaseName").asText();
                String tableName = request.get("Name").asText();
                Table table = glueService.getTable(dbName, tableName);
                yield Response.ok(Map.of("Table", table)).build();
            }
            case "GetTables" -> {
                String dbName = request.get("DatabaseName").asText();
                yield Response.ok(Map.of("TableList", glueService.getTables(dbName))).build();
            }
            case "DeleteTable" -> {
                String dbName = request.get("DatabaseName").asText();
                String tableName = request.get("Name").asText();
                glueService.deleteTable(dbName, tableName);
                yield Response.ok().build();
            }
            case "CreatePartition" -> {
                String dbName = request.get("DatabaseName").asText();
                String tableName = request.get("TableName").asText();
                Partition partition = mapper.treeToValue(request.get("PartitionInput"), Partition.class);
                glueService.createPartition(dbName, tableName, partition);
                yield Response.ok().build();
            }
            case "GetPartitions" -> {
                String dbName = request.get("DatabaseName").asText();
                String tableName = request.get("TableName").asText();
                yield Response.ok(Map.of("Partitions", glueService.getPartitions(dbName, tableName))).build();
            }
            case "CreateRegistry" -> handleCreateRegistry(request, region);
            case "GetRegistry" -> handleGetRegistry(request, region);
            case "ListRegistries" -> handleListRegistries(request);
            case "UpdateRegistry" -> handleUpdateRegistry(request, region);
            case "DeleteRegistry" -> handleDeleteRegistry(request, region);
            case "CreateSchema" -> handleCreateSchema(request, region);
            case "RegisterSchemaVersion" -> handleRegisterSchemaVersion(request, region);
            case "GetSchemaByDefinition" -> handleGetSchemaByDefinition(request, region);
            case "GetSchemaVersion" -> handleGetSchemaVersion(request, region);
            case "GetSchema" -> handleGetSchema(request, region);
            case "UpdateSchema" -> handleUpdateSchema(request, region);
            case "ListSchemas" -> handleListSchemas(request, region);
            case "ListSchemaVersions" -> handleListSchemaVersions(request, region);
            case "DeleteSchema" -> handleDeleteSchema(request, region);
            case "DeleteSchemaVersions" -> handleDeleteSchemaVersions(request, region);
            case "GetSchemaVersionsDiff" -> handleGetSchemaVersionsDiff(request, region);
            case "CheckSchemaVersionValidity" -> handleCheckSchemaVersionValidity(request);
            case "PutSchemaVersionMetadata" -> handlePutSchemaVersionMetadata(request);
            case "RemoveSchemaVersionMetadata" -> handleRemoveSchemaVersionMetadata(request);
            case "QuerySchemaVersionMetadata" -> handleQuerySchemaVersionMetadata(request);
            case "TagResource" -> handleTagResource(request);
            case "UntagResource" -> handleUntagResource(request);
            case "GetTags" -> handleGetTags(request);
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }

    private Response handleCreateRegistry(JsonNode request, String region) {
        String name = request.path("RegistryName").asText(null);
        String description = request.path("Description").asText(null);
        @SuppressWarnings("unchecked")
        Map<String, String> tags = request.has("Tags")
                ? mapper.convertValue(request.get("Tags"), Map.class)
                : null;
        Registry registry = schemaRegistryService.createRegistry(name, description, tags, region);
        return Response.ok(registry).build();
    }

    private Response handleGetRegistry(JsonNode request, String region) throws Exception {
        RegistryId registryId = readRegistryId(request);
        Registry registry = schemaRegistryService.getRegistry(registryId, region);
        return Response.ok(registry).build();
    }

    private Response handleListRegistries(JsonNode request) {
        var page = schemaRegistryService.listRegistries(readMaxResults(request), readNextToken(request));
        return Response.ok(pageResponse("Registries", page)).build();
    }

    private Response handleUpdateRegistry(JsonNode request, String region) throws Exception {
        RegistryId registryId = readRegistryId(request);
        String description = request.path("Description").asText(null);
        Registry registry = schemaRegistryService.updateRegistry(registryId, description, region);
        return Response.ok(registry).build();
    }

    private Response handleDeleteRegistry(JsonNode request, String region) throws Exception {
        RegistryId registryId = readRegistryId(request);
        Registry registry = schemaRegistryService.deleteRegistry(registryId, region);
        return Response.ok(Map.of(
                "RegistryName", registry.getRegistryName(),
                "RegistryArn", registry.getRegistryArn(),
                "Status", registry.getStatus()
        )).build();
    }

    private RegistryId readRegistryId(JsonNode request) throws Exception {
        JsonNode node = request.get("RegistryId");
        if (node == null || node.isNull()) {
            return null;
        }
        return mapper.treeToValue(node, RegistryId.class);
    }

    private SchemaId readSchemaId(JsonNode request) throws Exception {
        JsonNode node = request.get("SchemaId");
        if (node == null || node.isNull()) {
            return null;
        }
        return mapper.treeToValue(node, SchemaId.class);
    }

    @SuppressWarnings("unchecked")
    private Response handleCreateSchema(JsonNode request, String region) throws Exception {
        RegistryId registryId = readRegistryId(request);
        String schemaName = request.path("SchemaName").asText(null);
        String dataFormat = request.path("DataFormat").asText(null);
        String compatibility = request.path("Compatibility").asText(null);
        String description = request.path("Description").asText(null);
        String definition = request.path("SchemaDefinition").asText(null);
        Map<String, String> tags = request.has("Tags")
                ? mapper.convertValue(request.get("Tags"), Map.class)
                : null;
        var result = schemaRegistryService.createSchema(
                registryId, schemaName, dataFormat, compatibility, description, definition, tags, region);
        Schema schema = result.schema();
        SchemaVersion version = result.firstVersion();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("RegistryName", schema.getRegistryName());
        response.put("RegistryArn", schema.getRegistryArn());
        response.put("SchemaName", schema.getSchemaName());
        response.put("SchemaArn", schema.getSchemaArn());
        if (schema.getDescription() != null) response.put("Description", schema.getDescription());
        response.put("DataFormat", schema.getDataFormat());
        response.put("Compatibility", schema.getCompatibility());
        response.put("SchemaCheckpoint", schema.getSchemaCheckpoint());
        response.put("LatestSchemaVersion", schema.getLatestSchemaVersion());
        response.put("NextSchemaVersion", schema.getNextSchemaVersion());
        response.put("SchemaStatus", schema.getSchemaStatus());
        if (schema.getTags() != null) response.put("Tags", schema.getTags());
        response.put("SchemaVersionId", version.getSchemaVersionId());
        response.put("SchemaVersionStatus", version.getStatus());
        return Response.ok(response).build();
    }

    private Response handleRegisterSchemaVersion(JsonNode request, String region) throws Exception {
        SchemaId schemaId = readSchemaId(request);
        String definition = request.path("SchemaDefinition").asText(null);
        SchemaVersion version = schemaRegistryService.registerSchemaVersion(schemaId, definition, region);
        return Response.ok(Map.of(
                "SchemaVersionId", version.getSchemaVersionId(),
                "VersionNumber", version.getVersionNumber(),
                "Status", version.getStatus()
        )).build();
    }

    private Response handleGetSchemaByDefinition(JsonNode request, String region) throws Exception {
        SchemaId schemaId = readSchemaId(request);
        String definition = request.path("SchemaDefinition").asText(null);
        SchemaVersion version = schemaRegistryService.getSchemaByDefinition(schemaId, definition, region);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("SchemaVersionId", version.getSchemaVersionId());
        response.put("SchemaArn", version.getSchemaArn());
        response.put("DataFormat", version.getDataFormat());
        response.put("Status", version.getStatus());
        response.put("CreatedTime", version.getCreatedTime());
        return Response.ok(response).build();
    }

    private Response handleGetSchemaVersion(JsonNode request, String region) throws Exception {
        String schemaVersionId = request.path("SchemaVersionId").asText(null);
        SchemaId schemaId = readSchemaId(request);
        Long versionNumber = null;
        boolean latestVersion = false;
        JsonNode svn = request.get("SchemaVersionNumber");
        if (svn != null && !svn.isNull()) {
            JsonNode vn = svn.get("VersionNumber");
            if (vn != null && !vn.isNull()) {
                versionNumber = vn.asLong();
            }
            JsonNode lv = svn.get("LatestVersion");
            if (lv != null && !lv.isNull()) {
                latestVersion = lv.asBoolean();
            }
        }
        SchemaVersion version = schemaRegistryService.getSchemaVersion(
                schemaId, schemaVersionId, versionNumber, latestVersion, region);
        return Response.ok(version).build();
    }

    private Response handleGetSchema(JsonNode request, String region) throws Exception {
        SchemaId schemaId = readSchemaId(request);
        Schema schema = schemaRegistryService.getSchema(schemaId, region);
        return Response.ok(schema).build();
    }

    private Response handleUpdateSchema(JsonNode request, String region) throws Exception {
        SchemaId schemaId = readSchemaId(request);
        String compatibility = request.path("Compatibility").asText(null);
        String description = request.path("Description").asText(null);
        Long checkpointVersion = readVersionNumber(request, "SchemaVersionNumber");
        Schema schema = schemaRegistryService.updateSchema(
                schemaId, compatibility, description, checkpointVersion, region);
        return Response.ok(Map.of(
                "SchemaArn", schema.getSchemaArn(),
                "SchemaName", schema.getSchemaName(),
                "RegistryName", schema.getRegistryName()
        )).build();
    }

    private Response handleListSchemas(JsonNode request, String region) throws Exception {
        RegistryId registryId = readRegistryId(request);
        var page = schemaRegistryService.listSchemas(
                registryId, region, readMaxResults(request), readNextToken(request));
        return Response.ok(pageResponse("Schemas", page)).build();
    }

    private Response handleListSchemaVersions(JsonNode request, String region) throws Exception {
        SchemaId schemaId = readSchemaId(request);
        var page = schemaRegistryService.listSchemaVersions(
                schemaId, region, readMaxResults(request), readNextToken(request));
        return Response.ok(pageResponse("Schemas", page)).build();
    }

    private Response handleDeleteSchema(JsonNode request, String region) throws Exception {
        SchemaId schemaId = readSchemaId(request);
        Schema schema = schemaRegistryService.deleteSchema(schemaId, region);
        return Response.ok(Map.of(
                "SchemaArn", schema.getSchemaArn(),
                "SchemaName", schema.getSchemaName(),
                "Status", schema.getSchemaStatus()
        )).build();
    }

    private Response handleDeleteSchemaVersions(JsonNode request, String region) throws Exception {
        SchemaId schemaId = readSchemaId(request);
        String versions = request.path("Versions").asText(null);
        var results = schemaRegistryService.deleteSchemaVersions(schemaId, versions, region);
        Schema schema = schemaRegistryService.getSchema(schemaId, region);

        java.util.List<Map<String, Object>> errors = new java.util.ArrayList<>();
        for (var r : results) {
            if (r.errorCode() != null) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("VersionNumber", r.versionNumber());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("ErrorCode", r.errorCode());
                details.put("ErrorMessage", r.errorMessage());
                err.put("ErrorDetails", details);
                errors.add(err);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("SchemaArn", schema.getSchemaArn());
        response.put("SchemaVersionErrors", errors);
        return Response.ok(response).build();
    }

    private Response handleGetSchemaVersionsDiff(JsonNode request, String region) throws Exception {
        SchemaId schemaId = readSchemaId(request);
        Long first = readVersionNumber(request, "FirstSchemaVersionNumber");
        Long second = readVersionNumber(request, "SecondSchemaVersionNumber");
        String diff = schemaRegistryService.getSchemaVersionsDiff(schemaId, first, second, region);
        return Response.ok(Map.of("Diff", diff)).build();
    }

    private Response handleCheckSchemaVersionValidity(JsonNode request) {
        String dataFormat = request.path("DataFormat").asText(null);
        String definition = request.path("SchemaDefinition").asText(null);
        var result = schemaRegistryService.checkSchemaVersionValidity(dataFormat, definition);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("Valid", result.valid());
        if (result.error() != null) {
            response.put("Error", result.error());
        }
        return Response.ok(response).build();
    }

    private Long readVersionNumber(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode vn = node.get("VersionNumber");
        if (vn == null || vn.isNull()) {
            return null;
        }
        return vn.asLong();
    }

    private Integer readMaxResults(JsonNode request) {
        JsonNode node = request.get("MaxResults");
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asInt();
    }

    private String readNextToken(JsonNode request) {
        JsonNode node = request.get("NextToken");
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText(null);
    }

    private Map<String, Object> pageResponse(String field, GlueSchemaRegistryService.Page<?> page) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(field, page.items());
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    private Response handlePutSchemaVersionMetadata(JsonNode request) {
        String svId = request.path("SchemaVersionId").asText(null);
        JsonNode kv = request.get("MetadataKeyValue");
        String key = kv != null ? kv.path("MetadataKey").asText(null) : null;
        String value = kv != null ? kv.path("MetadataValue").asText(null) : null;
        var result = schemaRegistryService.putSchemaVersionMetadata(svId, key, value);
        return Response.ok(buildMetadataPutResponse(result)).build();
    }

    private Response handleRemoveSchemaVersionMetadata(JsonNode request) {
        String svId = request.path("SchemaVersionId").asText(null);
        JsonNode kv = request.get("MetadataKeyValue");
        String key = kv != null ? kv.path("MetadataKey").asText(null) : null;
        String value = kv != null ? kv.path("MetadataValue").asText(null) : null;
        var result = schemaRegistryService.removeSchemaVersionMetadata(svId, key, value);
        return Response.ok(buildMetadataPutResponse(result)).build();
    }

    private Response handleQuerySchemaVersionMetadata(JsonNode request) {
        String svId = request.path("SchemaVersionId").asText(null);
        java.util.List<GlueSchemaRegistryService.MetadataKeyValueFilter> filters = new java.util.ArrayList<>();
        JsonNode list = request.get("MetadataList");
        if (list != null && list.isArray()) {
            for (JsonNode item : list) {
                String k = item.path("MetadataKey").asText(null);
                String v = item.path("MetadataValue").asText(null);
                filters.add(new GlueSchemaRegistryService.MetadataKeyValueFilter(k, v));
            }
        }
        Map<String, ?> infoMap = schemaRegistryService.querySchemaVersionMetadata(svId, filters);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("MetadataInfoMap", infoMap);
        response.put("SchemaVersionId", svId);
        return Response.ok(response).build();
    }

    private Map<String, Object> buildMetadataPutResponse(GlueSchemaRegistryService.MetadataPutResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("SchemaArn", result.version().getSchemaArn());
        response.put("SchemaVersionId", result.version().getSchemaVersionId());
        response.put("VersionNumber", result.version().getVersionNumber());
        response.put("MetadataKey", result.metadataKey());
        response.put("MetadataValue", result.metadataValue());
        return response;
    }

    @SuppressWarnings("unchecked")
    private Response handleTagResource(JsonNode request) {
        String arn = request.path("ResourceArn").asText(null);
        Map<String, String> tagsToAdd = request.has("TagsToAdd")
                ? mapper.convertValue(request.get("TagsToAdd"), Map.class)
                : null;
        schemaRegistryService.tagResource(arn, tagsToAdd);
        return Response.ok(Map.of()).build();
    }

    @SuppressWarnings("unchecked")
    private Response handleUntagResource(JsonNode request) {
        String arn = request.path("ResourceArn").asText(null);
        java.util.List<String> tagsToRemove = request.has("TagsToRemove")
                ? mapper.convertValue(request.get("TagsToRemove"), java.util.List.class)
                : null;
        schemaRegistryService.untagResource(arn, tagsToRemove);
        return Response.ok(Map.of()).build();
    }

    private Response handleGetTags(JsonNode request) {
        String arn = request.path("ResourceArn").asText(null);
        Map<String, String> tags = schemaRegistryService.getTags(arn);
        return Response.ok(Map.of("Tags", tags)).build();
    }
}
