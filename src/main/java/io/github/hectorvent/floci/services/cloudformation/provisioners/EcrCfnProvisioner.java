package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::ECR::Repository}.
 *
 * <p>A create whose name already belongs to a repository fails, as it does on CloudFormation:
 * {@code RepositoryAlreadyExistsException} propagates and the stack reports the failure instead of
 * adopting a repository it does not own (and would later delete). The earlier adopt-on-conflict
 * path existed for CDK bootstrap re-runs, but a re-run is an UpdateStack of the existing
 * {@code CDKToolkit} stack, where the prior physical id already equals the fixed repository name
 * and the update path below reconciles it in place.
 */
@ApplicationScoped
public class EcrCfnProvisioner implements CfnResourceProvisioner {

    private static final int REPOSITORY_NAME_MAX_LENGTH = 256;
    /** What CreateRepository applies when ImageTagMutability is omitted. */
    private static final String DEFAULT_IMAGE_TAG_MUTABILITY = "MUTABLE";

    private final EcrService ecrService;

    public EcrCfnProvisioner(EcrService ecrService) {
        this.ecrService = ecrService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::ECR::Repository");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // CDK bootstrap requires lower-case repository names; CFN-generated suffixes can include
        // upper-case characters. Normalize to satisfy the AWS ECR repository name pattern.
        String repoName = ctx.stablePhysicalName(ctx.resolveOptional(props, "RepositoryName"),
                r.getLogicalId(), REPOSITORY_NAME_MAX_LENGTH, true).toLowerCase();

        String mutability = ctx.resolveOptional(props, "ImageTagMutability");
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);
        String region = ctx.region();

        // provision is also the update path. Only the repository this resource already owns is
        // reconciled in place; a replacing update derives a different name and creates, and a name
        // that already belongs to another repository fails the create as on CloudFormation.
        boolean reusing = ctx.reusesPriorEntity(repoName);
        Repository repo = reusing
                ? reconcileExisting(repoName, mutability, tags, region)
                : ecrService.createRepository(repoName, null, mutability, null, null, null, tags, region);

        // Both policies are updatable: a template that declares one sets it, and on the update
        // path a template that no longer declares one deletes it, tolerating "none set".
        String lifecyclePolicy = lifecyclePolicyText(props, ctx);
        if (lifecyclePolicy != null) {
            ecrService.putLifecyclePolicy(repoName, null, lifecyclePolicy, region);
        } else if (reusing) {
            CfnDeletes.safeDelete("ECR lifecycle policy", repoName,
                    () -> ecrService.deleteLifecyclePolicy(repoName, null, region),
                    "LifecyclePolicyNotFoundException");
        }
        String repositoryPolicy = repositoryPolicyText(props, ctx);
        if (repositoryPolicy != null) {
            ecrService.setRepositoryPolicy(repoName, null, repositoryPolicy, region);
        } else if (reusing) {
            CfnDeletes.safeDelete("ECR repository policy", repoName,
                    () -> ecrService.deleteRepositoryPolicy(repoName, null, region),
                    "RepositoryPolicyNotFoundException");
        }

        r.setPhysicalId(repoName);
        r.getAttributes().put("Arn", repo.getRepositoryArn());
        r.getAttributes().put("RepositoryUri", repo.getRepositoryUri());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        ecrService.deleteRepository(physicalId, null, true, region);
    }

    /**
     * The update path for the repository this resource already owns. Tags are driven to the
     * template's desired state (TagResource alone leaves unspecified tags in place, so dropped
     * keys are untagged first), and so is ImageTagMutability: a template that no longer declares
     * it means the MUTABLE default the API applies when the parameter is omitted, not "keep the
     * previous value". Both are updatable in the registry schema.
     */
    private Repository reconcileExisting(String repoName, String mutability, Map<String, String> tags,
                                         String region) {
        List<String> stale = ProvisionContext.staleTagKeys(
                ecrService.listTagsForResource(repoName, null, region), tags);
        if (!stale.isEmpty()) {
            ecrService.untagResource(repoName, null, stale, region);
        }
        if (!tags.isEmpty()) {
            ecrService.tagResource(repoName, null, tags, region);
        }
        return ecrService.putImageTagMutability(repoName, null,
                mutability != null ? mutability : DEFAULT_IMAGE_TAG_MUTABILITY, region);
    }

    /** {@code LifecyclePolicy.LifecyclePolicyText}, or null when the template declares none. */
    private static String lifecyclePolicyText(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("LifecyclePolicy")) {
            return null;
        }
        JsonNode lp = ctx.engine().resolveNode(props.get("LifecyclePolicy"));
        String policyText = lp.path("LifecyclePolicyText").asText(null);
        return policyText == null || policyText.isEmpty() ? null : policyText;
    }

    /** {@code RepositoryPolicyText} as a JSON string, or null when the template declares none. */
    private static String repositoryPolicyText(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("RepositoryPolicyText")) {
            return null;
        }
        JsonNode pol = ctx.engine().resolveNode(props.get("RepositoryPolicyText"));
        String policyText = pol.isTextual() ? pol.asText() : pol.toString();
        return policyText == null || policyText.isEmpty() ? null : policyText;
    }

    /** See {@code KmsCfnProvisioner#parseCfnTags} for why this is copied rather than shared. */
    private Map<String, String> parseCfnTags(JsonNode tagsNode, ProvisionContext ctx) {
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = ctx.engine().resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
