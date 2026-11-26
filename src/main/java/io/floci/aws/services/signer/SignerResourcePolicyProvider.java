package io.floci.aws.services.signer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import io.floci.aws.services.signer.model.SigningProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Supplies the cross-account permissions of a Signer profile, or of the profile a job was signed with, to IAM
 * policy evaluation as that resource's policy, with the account that owns it, the one in its ARN, also when it
 * grants nothing, so a request naming another account's profile or job by its {@code profileOwner} or
 * {@code jobOwner} needs the owner's grant beside the caller's own policy, as AWS requires: each permission
 * AddProfilePermission grants is a statement allowing its principal its action on that resource.
 */
@ApplicationScoped
public class SignerResourcePolicyProvider implements ResourcePolicyProvider {

    private final SignerService signer;
    private final ObjectMapper objectMapper;

    @Inject
    public SignerResourcePolicyProvider(SignerService signer, ObjectMapper objectMapper) {
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        Optional<AwsArnUtils.Arn> parsed = ResourcePolicyProvider.regionalArn(resourceArn, "signer");
        if (parsed.isEmpty() || parsed.get().resource().contains("*")) {
            return List.of();
        }
        List<SigningProfile.ProfilePermission> permissions = signer.permissionsOn(resourceArn);
        return List.of(new ResourcePolicy(permissions.isEmpty() ? null : policy(permissions, resourceArn),
                parsed.get().accountId()));
    }

    /** A policy allowing each permission's principal its action on the resource. */
    private String policy(List<SigningProfile.ProfilePermission> permissions, String resourceArn) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("Version", "2012-10-17");
        ArrayNode statements = root.putArray("Statement");
        for (SigningProfile.ProfilePermission permission : permissions) {
            ObjectNode statement = statements.addObject();
            statement.put("Sid", permission.getStatementId());
            statement.put("Effect", "Allow");
            statement.putObject("Principal").put("AWS", permission.getPrincipal());
            statement.put("Action", permission.getAction());
            statement.put("Resource", resourceArn);
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A Signer permission policy built from its fields is not writable", e);
        }
    }
}
