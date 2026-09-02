package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
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

    public PutDataLakeSettingsResponse putDataLakeSettings(String region, PutDataLakeSettingsRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        storage.putDataLakeSettings(region, catalogId, request.getDataLakeSettings());
        return new PutDataLakeSettingsResponse();
    }

    public GetDataLakeSettingsResponse getDataLakeSettings(String region, GetDataLakeSettingsRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        DataLakeSettings settings = storage.getDataLakeSettings(region, catalogId).orElseGet(DataLakeSettings::new);
        
        GetDataLakeSettingsResponse response = new GetDataLakeSettingsResponse();
        response.setDataLakeSettings(settings);
        return response;
    }

    public RegisterResourceResponse registerResource(String region, RegisterResourceRequest request) {
        if (request.getResourceArn() == null) {
            throw new AwsException("InvalidInputException", "ResourceArn is required", 400);
        }
        if (!request.getResourceArn().startsWith("arn:")) {
            throw new AwsException("InvalidInputException", "ResourceArn must be a valid ARN", 400);
        }
        if (storage.describeResource(region, request.getResourceArn()).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Resource is already registered", 400);
        }
        storage.registerResource(
                region,
                request.getResourceArn(),
                request.getRoleArn(),
                Boolean.TRUE.equals(request.getUseServiceLinkedRole()),
                request.getWithFederation()
        );
        return new RegisterResourceResponse();
    }

    public UpdateResourceResponse updateResource(String region, UpdateResourceRequest request) {
        validateResourceArn(request.getResourceArn());
        validateRoleArn(request.getRoleArn());

        storage.updateResource(
                region,
                request.getResourceArn(),
                request.getRoleArn(),
                request.getExpectedResourceOwnerAccount(),
                request.getHybridAccessEnabled(),
                request.getWithFederation()
        );
        return new UpdateResourceResponse();
    }

    private static void validateResourceArn(String resourceArn) {
        if (resourceArn == null) {
            throw new AwsException("InvalidInputException", "ResourceArn is required", 400);
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(resourceArn);
            if (parsed.partition().isBlank() || parsed.service().isBlank() || parsed.resource().isBlank()
                    || resourceArn.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Invalid resource ARN");
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidInputException", "ResourceArn must be a valid ARN", 400);
        }
    }

    private static void validateRoleArn(String roleArn) {
        if (roleArn == null) {
            throw new AwsException("InvalidInputException", "RoleArn is required", 400);
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(roleArn);
            if (parsed.partition().isBlank()
                    || !"iam".equals(parsed.service())
                    || !parsed.region().isEmpty()
                    || parsed.accountId().isBlank()
                    || !parsed.resource().startsWith("role/")
                    || parsed.resource().length() == "role/".length()
                    || roleArn.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Invalid IAM role ARN");
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidInputException", "RoleArn must be a valid IAM role ARN", 400);
        }
    }

    public DeregisterResourceResponse deregisterResource(String region, DeregisterResourceRequest request) {
        if (request.getResourceArn() == null) {
            throw new AwsException("InvalidInputException", "ResourceArn is required", 400);
        }
        if (storage.describeResource(region, request.getResourceArn()).isEmpty()) {
            throw new AwsException("EntityNotFoundException", "Resource not found", 400);
        }
        storage.deregisterResource(region, request.getResourceArn());
        return new DeregisterResourceResponse();
    }

    public ListResourcesResponse listResources(String region, ListResourcesRequest request) {
        List<ResourceInfo> resources = storage.listResources(
                region,
                request.getFilterConditionList(),
                request.getMaxResults(),
                request.getNextToken()
        );
        ListResourcesResponse response = new ListResourcesResponse();
        response.setResourceInfoList(resources);
        return response;
    }

    public DescribeResourceResponse describeResource(String region, DescribeResourceRequest request) {
        ResourceInfo info = storage.describeResource(region, request.getResourceArn())
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Resource not found", 400));
        
        DescribeResourceResponse response = new DescribeResourceResponse();
        response.setResourceInfo(info);
        return response;
    }

    public GrantPermissionsResponse grantPermissions(String region, GrantPermissionsRequest request) {
        if (request.getPrincipal() == null) {
            throw new AwsException("InvalidInputException", "Principal is required", 400);
        }
        if (request.getResource() == null) {
            throw new AwsException("InvalidInputException", "Resource is required", 400);
        }
        if (request.getPermissions() == null || request.getPermissions().isEmpty()) {
            throw new AwsException("InvalidInputException", "Permissions is required", 400);
        }
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        
        if (request.getPermissionsWithGrantOption() != null) {
            java.util.List<String> perms = request.getPermissions() != null ? request.getPermissions() : java.util.List.of();
            for (String grantOption : request.getPermissionsWithGrantOption()) {
                if (!perms.contains(grantOption)) {
                    throw new AwsException("InvalidInputException", "PermissionsWithGrantOption must be a subset of Permissions", 400);
                }
            }
        }
        
        PrincipalResourcePermissions p = new PrincipalResourcePermissions();
        p.setPrincipal(request.getPrincipal());
        p.setResource(request.getResource());
        p.setPermissions(request.getPermissions());
        p.setPermissionsWithGrantOption(request.getPermissionsWithGrantOption());
        
        storage.grantPermissions(region, catalogId, p);
        return new GrantPermissionsResponse();
    }

    public RevokePermissionsResponse revokePermissions(String region, RevokePermissionsRequest request) {
        if (request.getPrincipal() == null) {
            throw new AwsException("InvalidInputException", "Principal is required", 400);
        }
        if (request.getResource() == null) {
            throw new AwsException("InvalidInputException", "Resource is required", 400);
        }
        if (request.getPermissions() == null || request.getPermissions().isEmpty()) {
            throw new AwsException("InvalidInputException", "Permissions is required", 400);
        }
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        
        PrincipalResourcePermissions p = new PrincipalResourcePermissions();
        p.setPrincipal(request.getPrincipal());
        p.setResource(request.getResource());
        p.setPermissions(request.getPermissions());
        p.setPermissionsWithGrantOption(request.getPermissionsWithGrantOption());
        
        storage.revokePermissions(region, catalogId, p);
        return new RevokePermissionsResponse();
    }

    public ListPermissionsResponse listPermissions(String region, ListPermissionsRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        
        List<PrincipalResourcePermissions> permissions = storage.listPermissions(
                region,
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

    public CreateLFTagResponse createLFTag(String region, CreateLFTagRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        if (request.getTagKey() == null) {
            throw new AwsException("InvalidInputException", "TagKey is required", 400);
        }
        if (storage.getLFTag(region, catalogId, request.getTagKey()).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Tag already exists", 400);
        }
        storage.createLFTag(region, catalogId, request.getTagKey(), request.getTagValues());
        return new CreateLFTagResponse();
    }

    public GetLFTagResponse getLFTag(String region, GetLFTagRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        LFTag tag = storage.getLFTag(region, catalogId, request.getTagKey())
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "LF-Tag not found", 400));
        
        GetLFTagResponse response = new GetLFTagResponse();
        response.setCatalogId(catalogId);
        response.setTagKey(tag.getTagKey());
        response.setTagValues(tag.getTagValues());
        return response;
    }

    public UpdateLFTagResponse updateLFTag(String region, UpdateLFTagRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        // check exists
        storage.getLFTag(region, catalogId, request.getTagKey())
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "LF-Tag not found", 400));
        
        storage.updateLFTag(region, catalogId, request.getTagKey(), request.getTagValuesToAdd(), request.getTagValuesToDelete());
        return new UpdateLFTagResponse();
    }

    public DeleteLFTagResponse deleteLFTag(String region, DeleteLFTagRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        storage.getLFTag(region, catalogId, request.getTagKey())
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "LF-Tag not found", 400));
        
        storage.deleteLFTag(region, catalogId, request.getTagKey());
        return new DeleteLFTagResponse();
    }

    public ListLFTagsResponse listLFTags(String region, ListLFTagsRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        List<LFTagPair> tags = storage.listLFTags(region, catalogId, request.getResourceShareType(), request.getMaxResults(), request.getNextToken());
        
        ListLFTagsResponse response = new ListLFTagsResponse();
        response.setLfTags(tags);
        return response;
    }

    public AddLFTagsToResourceResponse addLFTagsToResource(String region, AddLFTagsToResourceRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        if (request.getResource() == null) {
            throw new AwsException("InvalidInputException", "Resource is required", 400);
        }
        storage.addLFTagsToResource(region, catalogId, request.getResource(), request.getLfTags());
        
        AddLFTagsToResourceResponse response = new AddLFTagsToResourceResponse();
        response.setFailures(List.of()); // Success for all
        return response;
    }

    public RemoveLFTagsFromResourceResponse removeLFTagsFromResource(String region, RemoveLFTagsFromResourceRequest request) {
        String catalogId = request.getCatalogId() != null ? request.getCatalogId() : regionResolver.getAccountId();
        if (request.getResource() == null) {
            throw new AwsException("InvalidInputException", "Resource is required", 400);
        }
        storage.removeLFTagsFromResource(region, catalogId, request.getResource(), request.getLfTags());
        
        RemoveLFTagsFromResourceResponse response = new RemoveLFTagsFromResourceResponse();
        response.setFailures(List.of()); // Success for all
        return response;
    }

}
