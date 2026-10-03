package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::Cognito::UserPoolUserToGroupAttachment}. {@code Ref}
 * returns {@code <UserPoolId>|<GroupName>|<Username>}, with the Username as the template gives it.
 *
 * <p>Every property is createOnly, so any change is a replacement: the new membership is added and
 * the old one is removed once the update commits, through {@link ReplacementCleanup}.
 */
@ApplicationScoped
public class CognitoUserPoolUserToGroupAttachmentCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Cognito::UserPoolUserToGroupAttachment";

    private final CognitoService cognitoService;

    @Inject
    public CognitoUserPoolUserToGroupAttachmentCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String userPoolId = require(props, "UserPoolId", ctx);
        String groupName = require(props, "GroupName", ctx);
        String username = require(props, "Username", ctx);

        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String physicalId = userPoolId + "|" + groupName + "|" + username;
        if (!ctx.reusesPriorEntity(physicalId)) {
            cognitoService.adminAddUserToGroup(userPoolId, groupName, username);
        }
        r.setPhysicalId(physicalId);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private static String require(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires " + name, 400);
        }
        return value;
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        String[] parts = physicalId == null ? new String[0] : physicalId.split("\\|", 3);
        if (parts.length != 3) {
            return;
        }
        CfnDeletes.safeDelete("Cognito group membership", physicalId,
                () -> cognitoService.adminRemoveUserFromGroup(parts[0], parts[1], parts[2]),
                "UserNotFoundException", "ResourceNotFoundException");
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ReplacementCleanup.rollback(resource, this::delete);
        // Every property is createOnly, so without a replacement nothing changed.
        return true;
    }
}
