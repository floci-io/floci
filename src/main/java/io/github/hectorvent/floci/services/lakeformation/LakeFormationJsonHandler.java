package io.github.hectorvent.floci.services.lakeformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lakeformation.model.DataCellsFilter;
import io.github.hectorvent.floci.services.lakeformation.model.DataLakeSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class LakeFormationJsonHandler {
    private final LakeFormationService service;
    private final ObjectMapper objectMapper;

    @Inject
    public LakeFormationJsonHandler(LakeFormationService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "GetDataLakeSettings" -> getDataLakeSettings(request, region);
            case "PutDataLakeSettings" -> putDataLakeSettings(request, region);
            case "TagResource" -> tagResource(request, region);
            case "UntagResource" -> untagResource(request, region);
            case "ListTagsForResource" -> listTagsForResource(request, region);
            case "PutDataCellsFilter" -> putDataCellsFilter(request, region);
            case "GetDataCellsFilter" -> getDataCellsFilter(request, region);
            case "DeleteDataCellsFilter" -> deleteDataCellsFilter(request, region);
            case "GrantPermissions" -> grantPermissions(request, region);
            case "RevokePermissions" -> revokePermissions(request, region);
            case "ListPermissions" -> listPermissions(request, region);
            case "GetDataLakePrincipal" -> getDataLakePrincipal(request, region);
            case "CreateLakeFormationIdentityCenterConfiguration" -> createIdentityCenterConfiguration(request, region);
            case "DescribeLakeFormationIdentityCenterConfiguration" -> describeIdentityCenterConfiguration(request, region);
            case "UpdateLakeFormationIdentityCenterConfiguration" -> updateIdentityCenterConfiguration(request, region);
            case "DeleteLakeFormationIdentityCenterConfiguration" -> deleteIdentityCenterConfiguration(request, region);
            case "CreateLakeFormationOptIn" -> createHybridOptIn(request, region);
            case "ListLakeFormationOptIns" -> listHybridOptIns(request, region);
            case "DeleteLakeFormationOptIn" -> deleteHybridOptIn(request, region);
            case "RegisterResource" -> registerResource(request, region);
            case "DeregisterResource" -> deregisterResource(request, region);
            case "ListResources" -> listResources(request, region);
            case "UpdateResource" -> updateResource(request, region);
            case "GetTemporaryGlueTableCredentials" -> getTemporaryGlueTableCredentials(request, region);
            case "GetTemporaryGluePartitionCredentials" -> getTemporaryGluePartitionCredentials(request, region);
            case "CreateLFTag" -> createLFTag(request, region);
            case "GetLFTag" -> getLFTag(request, region);
            case "UpdateLFTag" -> updateLFTag(request, region);
            case "DeleteLFTag" -> deleteLFTag(request, region);
            case "ListLFTags" -> listLFTags(request, region);
            case "CreateLFTagExpression" -> createLFTagExpression(request, region);
            case "GetLFTagExpression" -> getLFTagExpression(request, region);
            case "UpdateLFTagExpression" -> updateLFTagExpression(request, region);
            case "DeleteLFTagExpression" -> deleteLFTagExpression(request, region);
            case "ListLFTagExpressions" -> listLFTagExpressions(request, region);
            case "AddLFTagsToResource" -> addLFTagsToResource(request, region);
            case "RemoveLFTagsFromResource" -> removeLFTagsFromResource(request, region);
            case "GetResourceLFTags" -> getResourceLFTags(request, region);
            case "SearchTablesByLFTags" -> searchTablesByLFTags(request, region);
            case "SearchDatabasesByLFTags" -> searchDatabasesByLFTags(request, region);
            default -> throw new AwsException("UnsupportedOperation",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    private Response getDataLakeSettings(JsonNode request, String region) {
        DataLakeSettings settings = service.getDataLakeSettings(region);
        return Response.ok(Map.of("DataLakeSettings", settings)).build();
    }

    private Response putDataLakeSettings(JsonNode request, String region) {
        DataLakeSettings settings = objectMapper.convertValue(request.path("DataLakeSettings"), DataLakeSettings.class);
        service.putDataLakeSettings(region, settings);
        return Response.ok().build();
    }

    private Response tagResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText(null);
        JsonNode tagsNode = request.path("Tags");
        service.tagResource(region, resourceArn, objectMapper.convertValue(tagsNode, Map.class));
        return Response.ok().build();
    }

    private Response untagResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText(null);
        service.untagResource(region, resourceArn, objectMapper.convertValue(request.path("TagKeys"), java.util.List.class));
        return Response.ok().build();
    }

    private Response listTagsForResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText(null);
        Map<String, String> tags = service.listTagsForResource(region, resourceArn);
        return Response.ok(Map.of("Tags", tags)).build();
    }

    private Response putDataCellsFilter(JsonNode request, String region) {
        DataCellsFilter dataCellsFilter = objectMapper.convertValue(request, DataCellsFilter.class);
        service.putDataCellsFilter(region, dataCellsFilter);
        return Response.ok().build();
    }

    private Response getDataCellsFilter(JsonNode request, String region) {
        DataCellsFilter dataCellsFilter = service.getDataCellsFilter(region, request.path("DatabaseName").asText(null), request.path("Name").asText(null), request.path("TableCatalogId").asText(null), request.path("TableName").asText(null));
        return Response.ok(Map.of("DataCellsFilter", dataCellsFilter == null ? new DataCellsFilter() : dataCellsFilter)).build();
    }

    private Response deleteDataCellsFilter(JsonNode request, String region) {
        service.deleteDataCellsFilter(region, request.path("DatabaseName").asText(null), request.path("Name").asText(null), request.path("TableCatalogId").asText(null), request.path("TableName").asText(null));
        return Response.ok().build();
    }

    private Response grantPermissions(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        List<Map<String, Object>> permissions = service.grantPermissions(region, payload);
        return Response.ok(Map.of("PrincipalResourcePermissions", permissions)).build();
    }

    private Response revokePermissions(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        List<Map<String, Object>> permissions = service.revokePermissions(region, payload);
        return Response.ok(Map.of("PrincipalResourcePermissions", permissions)).build();
    }

    private Response listPermissions(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        List<Map<String, Object>> permissions = service.listPermissions(region, payload);
        return Response.ok(Map.of("PrincipalResourcePermissions", permissions)).build();
    }

    private Response getDataLakePrincipal(JsonNode request, String region) {
        Map<String, Object> principal = service.getDataLakePrincipal(region, request.path("DataLakePrincipalIdentifier").asText(null));
        return Response.ok(Map.of("DataLakePrincipal", principal)).build();
    }

    private Response createIdentityCenterConfiguration(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(service.createIdentityCenterConfiguration(region, payload)).build();
    }

    private Response describeIdentityCenterConfiguration(JsonNode request, String region) {
        return Response.ok(service.describeIdentityCenterConfiguration(region)).build();
    }

    private Response updateIdentityCenterConfiguration(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(service.updateIdentityCenterConfiguration(region, payload)).build();
    }

    private Response deleteIdentityCenterConfiguration(JsonNode request, String region) {
        service.deleteIdentityCenterConfiguration(region);
        return Response.ok().build();
    }

    private Response createHybridOptIn(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(service.createHybridOptIn(region, payload)).build();
    }

    private Response listHybridOptIns(JsonNode request, String region) {
        return Response.ok(Map.of("LakeFormationOptInsInfo", Map.of("OptIns", service.listHybridOptIns(region)))).build();
    }

    private Response deleteHybridOptIn(JsonNode request, String region) {
        service.deleteHybridOptIn(region, request.path("PrincipalIdentifier").asText(null));
        return Response.ok().build();
    }

    private Response registerResource(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(Map.of("ResourceInfo", service.registerResource(region, payload))).build();
    }

    private Response deregisterResource(JsonNode request, String region) {
        service.deregisterResource(region, request.path("ResourceArn").asText(null));
        return Response.ok().build();
    }

    private Response listResources(JsonNode request, String region) {
        return Response.ok(Map.of("ResourceInfoList", service.listResources(region))).build();
    }

    private Response updateResource(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(Map.of("ResourceInfo", service.updateResource(region, payload))).build();
    }

    private Response getTemporaryGlueTableCredentials(JsonNode request, String region) {
        return Response.ok(Map.of("Credentials", service.getTemporaryGlueTableCredentials(region))).build();
    }

    private Response getTemporaryGluePartitionCredentials(JsonNode request, String region) {
        return Response.ok(Map.of("Credentials", service.getTemporaryGluePartitionCredentials(region))).build();
    }

    private Response createLFTag(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(Map.of("LFTag", service.createLFTag(region, payload))).build();
    }

    private Response getLFTag(JsonNode request, String region) {
        return Response.ok(Map.of("LFTag", service.getLFTag(region, request.path("TagKey").asText(null)))).build();
    }

    private Response updateLFTag(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(Map.of("LFTag", service.updateLFTag(region, payload))).build();
    }

    private Response deleteLFTag(JsonNode request, String region) {
        service.deleteLFTag(region, request.path("TagKey").asText(null));
        return Response.ok().build();
    }

    private Response listLFTags(JsonNode request, String region) {
        return Response.ok(Map.of("LFTags", service.listLFTags(region))).build();
    }

    private Response createLFTagExpression(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(Map.of("LFTagExpression", service.createLFTagExpression(region, payload))).build();
    }

    private Response getLFTagExpression(JsonNode request, String region) {
        return Response.ok(Map.of("LFTagExpression", service.getLFTagExpression(region, request.path("Name").asText(null)))).build();
    }

    private Response updateLFTagExpression(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(Map.of("LFTagExpression", service.updateLFTagExpression(region, payload))).build();
    }

    private Response deleteLFTagExpression(JsonNode request, String region) {
        service.deleteLFTagExpression(region, request.path("Name").asText(null));
        return Response.ok().build();
    }

    private Response listLFTagExpressions(JsonNode request, String region) {
        return Response.ok(Map.of("LFTagExpressions", service.listLFTagExpressions(region))).build();
    }

    private Response addLFTagsToResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText(null);
        List<Map<String, Object>> tags = objectMapper.convertValue(request.path("LFTags"), java.util.List.class);
        return Response.ok(Map.of("LFTags", service.addLFTagsToResource(region, resourceArn, tags))).build();
    }

    private Response removeLFTagsFromResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText(null);
        List<String> tagKeys = objectMapper.convertValue(request.path("TagKeys"), java.util.List.class);
        return Response.ok(Map.of("LFTags", service.removeLFTagsFromResource(region, resourceArn, tagKeys))).build();
    }

    private Response getResourceLFTags(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText(null);
        return Response.ok(Map.of("LFTags", service.getResourceLFTags(region, resourceArn))).build();
    }

    private Response searchTablesByLFTags(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(service.searchTablesByLFTags(region, payload)).build();
    }

    private Response searchDatabasesByLFTags(JsonNode request, String region) {
        Map<String, Object> payload = objectMapper.convertValue(request, Map.class);
        return Response.ok(service.searchDatabasesByLFTags(region, payload)).build();
    }
}
