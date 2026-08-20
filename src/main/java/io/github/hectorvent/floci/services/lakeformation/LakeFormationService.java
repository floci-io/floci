package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lakeformation.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class LakeFormationService {

    private final LakeFormationStorage storage;
    private final RegionResolver regionResolver;

    @Inject
    public LakeFormationService(LakeFormationStorage storage, RegionResolver regionResolver) {
        this.storage = storage;
        this.regionResolver = regionResolver;
    }

    public PutDataLakeSettingsResponse putDataLakeSettings(PutDataLakeSettingsRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        storage.putDataLakeSettings(catalogId, request.getDataLakeSettings());
        return new PutDataLakeSettingsResponse();
    }

    public GetDataLakeSettingsResponse getDataLakeSettings(GetDataLakeSettingsRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        DataLakeSettings settings = storage.getDataLakeSettings(catalogId).orElseGet(DataLakeSettings::new);
        
        GetDataLakeSettingsResponse response = new GetDataLakeSettingsResponse();
        response.setDataLakeSettings(settings);
        return response;
    }

    public RegisterResourceResponse registerResource(RegisterResourceRequest request) {
        if (request.getResourceArn() == null) {
            throw new AwsException("InvalidInputException", "ResourceArn is required", 400);
        }
        storage.registerResource(
                request.getResourceArn(),
                request.getRoleArn(),
                Boolean.TRUE.equals(request.getUseServiceLinkedRole()),
                request.getWithFederation()
        );
        return new RegisterResourceResponse();
    }

    public DeregisterResourceResponse deregisterResource(DeregisterResourceRequest request) {
        if (request.getResourceArn() == null) {
            throw new AwsException("InvalidInputException", "ResourceArn is required", 400);
        }
        storage.deregisterResource(request.getResourceArn());
        return new DeregisterResourceResponse();
    }

    public ListResourcesResponse listResources(ListResourcesRequest request) {
        List<ResourceInfo> resources = storage.listResources(
                request.getFilterConditionList() != null && !request.getFilterConditionList().isEmpty()
                        ? request.getFilterConditionList().get(0) : null,
                request.getMaxResults(),
                request.getNextToken()
        );
        ListResourcesResponse response = new ListResourcesResponse();
        response.setResourceInfoList(resources);
        return response;
    }

    public DescribeResourceResponse describeResource(DescribeResourceRequest request) {
        ResourceInfo info = storage.describeResource(request.getResourceArn())
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Resource not found", 400));
        
        DescribeResourceResponse response = new DescribeResourceResponse();
        response.setResourceInfo(info);
        return response;
    }

    public GrantPermissionsResponse grantPermissions(GrantPermissionsRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        
        PrincipalResourcePermissions p = new PrincipalResourcePermissions();
        p.setPrincipal(request.getPrincipal());
        p.setResource(request.getResource());
        p.setPermissions(request.getPermissions());
        p.setPermissionsWithGrantOption(request.getPermissionsWithGrantOption());
        
        storage.grantPermissions(catalogId, p);
        return new GrantPermissionsResponse();
    }

    public RevokePermissionsResponse revokePermissions(RevokePermissionsRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        
        PrincipalResourcePermissions p = new PrincipalResourcePermissions();
        p.setPrincipal(request.getPrincipal());
        p.setResource(request.getResource());
        p.setPermissions(request.getPermissions());
        p.setPermissionsWithGrantOption(request.getPermissionsWithGrantOption());
        
        storage.revokePermissions(catalogId, p);
        return new RevokePermissionsResponse();
    }

    public ListPermissionsResponse listPermissions(ListPermissionsRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        
        List<PrincipalResourcePermissions> permissions = storage.listPermissions(
                catalogId,
                request.getPrincipal(),
                request.getResource(),
                request.getResourceType(),
                Boolean.TRUE.equals(request.getIncludeRelated()),
                request.getMaxResults(),
                request.getNextToken()
        );
        
        ListPermissionsResponse response = new ListPermissionsResponse();
        response.setPrincipalResourcePermissions(permissions);
        return response;
    }

    public CreateLFTagResponse createLFTag(CreateLFTagRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        if (request.getTagKey() == null) {
            throw new AwsException("InvalidInputException", "TagKey is required", 400);
        }
        storage.createLFTag(catalogId, request.getTagKey(), request.getTagValues());
        return new CreateLFTagResponse();
    }

    public GetLFTagResponse getLFTag(GetLFTagRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        LFTag tag = storage.getLFTag(catalogId, request.getTagKey())
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "LF-Tag not found", 400));
        
        GetLFTagResponse response = new GetLFTagResponse();
        response.setCatalogId(catalogId);
        response.setTagKey(tag.getTagKey());
        response.setTagValues(tag.getTagValues());
        return response;
    }

    public UpdateLFTagResponse updateLFTag(UpdateLFTagRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        // check exists
        storage.getLFTag(catalogId, request.getTagKey())
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "LF-Tag not found", 400));
        
        storage.updateLFTag(catalogId, request.getTagKey(), request.getTagValuesToAdd(), request.getTagValuesToDelete());
        return new UpdateLFTagResponse();
    }

    public DeleteLFTagResponse deleteLFTag(DeleteLFTagRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        storage.getLFTag(catalogId, request.getTagKey())
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "LF-Tag not found", 400));
        
        storage.deleteLFTag(catalogId, request.getTagKey());
        return new DeleteLFTagResponse();
    }

    public ListLFTagsResponse listLFTags(ListLFTagsRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        List<LFTagPair> tags = storage.listLFTags(catalogId, request.getResourceShareType(), request.getMaxResults(), request.getNextToken());
        
        ListLFTagsResponse response = new ListLFTagsResponse();
        response.setLfTags(tags);
        return response;
    }

    public AddLFTagsToResourceResponse addLFTagsToResource(AddLFTagsToResourceRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        if (request.getResource() == null) {
            throw new AwsException("InvalidInputException", "Resource is required", 400);
        }
        storage.addLFTagsToResource(catalogId, request.getResource(), request.getLfTags());
        
        AddLFTagsToResourceResponse response = new AddLFTagsToResourceResponse();
        response.setFailures(List.of()); // Success for all
        return response;
    }

    public RemoveLFTagsFromResourceResponse removeLFTagsFromResource(RemoveLFTagsFromResourceRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        if (request.getResource() == null) {
            throw new AwsException("InvalidInputException", "Resource is required", 400);
        }
        storage.removeLFTagsFromResource(catalogId, request.getResource(), request.getLfTags());
        
        RemoveLFTagsFromResourceResponse response = new RemoveLFTagsFromResourceResponse();
        response.setFailures(List.of()); // Success for all
        return response;
    }

}
