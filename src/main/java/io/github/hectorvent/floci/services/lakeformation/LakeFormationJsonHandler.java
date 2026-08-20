package io.github.hectorvent.floci.services.lakeformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJson11Controller;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.lakeformation.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class LakeFormationJsonHandler {

    private final LakeFormationService service;
    private final ObjectMapper mapper;

    @Inject
    public LakeFormationJsonHandler(LakeFormationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode requestNode, String region) {
        try {
            Object responseModel = switch (action) {
                case "PutDataLakeSettings" -> service.putDataLakeSettings(mapper.treeToValue(requestNode, PutDataLakeSettingsRequest.class));
                case "GetDataLakeSettings" -> service.getDataLakeSettings(mapper.treeToValue(requestNode, GetDataLakeSettingsRequest.class));
                case "RegisterResource" -> service.registerResource(mapper.treeToValue(requestNode, RegisterResourceRequest.class));
                case "DeregisterResource" -> service.deregisterResource(mapper.treeToValue(requestNode, DeregisterResourceRequest.class));
                case "ListResources" -> service.listResources(mapper.treeToValue(requestNode, ListResourcesRequest.class));
                case "DescribeResource" -> service.describeResource(mapper.treeToValue(requestNode, DescribeResourceRequest.class));
                case "GrantPermissions" -> service.grantPermissions(mapper.treeToValue(requestNode, GrantPermissionsRequest.class));
                case "RevokePermissions" -> service.revokePermissions(mapper.treeToValue(requestNode, RevokePermissionsRequest.class));
                case "ListPermissions" -> service.listPermissions(mapper.treeToValue(requestNode, ListPermissionsRequest.class));
                case "CreateLFTag" -> service.createLFTag(mapper.treeToValue(requestNode, CreateLFTagRequest.class));
                case "GetLFTag" -> service.getLFTag(mapper.treeToValue(requestNode, GetLFTagRequest.class));
                case "UpdateLFTag" -> service.updateLFTag(mapper.treeToValue(requestNode, UpdateLFTagRequest.class));
                case "DeleteLFTag" -> service.deleteLFTag(mapper.treeToValue(requestNode, DeleteLFTagRequest.class));
                case "ListLFTags" -> service.listLFTags(mapper.treeToValue(requestNode, ListLFTagsRequest.class));
                case "AddLFTagsToResource" -> service.addLFTagsToResource(mapper.treeToValue(requestNode, AddLFTagsToResourceRequest.class));
                case "RemoveLFTagsFromResource" -> service.removeLFTagsFromResource(mapper.treeToValue(requestNode, RemoveLFTagsFromResourceRequest.class));
                default -> null;
            };

            if (responseModel == null) {
                return null;
            }

            return Response.ok()
                    .header("x-amzn-RequestId", "floci-" + System.currentTimeMillis())
                    .entity(responseModel)
                    .build();

        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("SerializationException", "Failed to parse request or serialize response", 400);
        }
    }
}
