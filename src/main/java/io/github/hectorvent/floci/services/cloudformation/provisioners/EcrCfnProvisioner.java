package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provisions {@code AWS::ECR::Repository}. */
@ApplicationScoped
public class EcrCfnProvisioner implements CfnResourceProvisioner {

    private static final int REPOSITORY_NAME_MAX_LENGTH = 256;

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
        String repoName = ctx.resolveOptional(props, "RepositoryName");
        if (repoName == null || repoName.isBlank()) {
            repoName = ctx.generatePhysicalName(r.getLogicalId(), REPOSITORY_NAME_MAX_LENGTH, true);
        }
        // CDK bootstrap requires lower-case repository names; CFN-generated suffixes can include
        // upper-case characters. Normalize to satisfy the AWS ECR repository name pattern.
        repoName = repoName.toLowerCase();

        String mutability = ctx.resolveOptional(props, "ImageTagMutability");
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);

        Repository repo;
        try {
            repo = ecrService.createRepository(repoName, null, mutability, null, null, null, tags, ctx.region());
        } catch (AwsException e) {
            if ("RepositoryAlreadyExistsException".equals(e.getErrorCode())) {
                repo = ecrService.describeRepositories(List.of(repoName), null, ctx.region()).get(0);
            } else {
                throw e;
            }
        }

        // Lifecycle policy can be inlined as `LifecyclePolicy.LifecyclePolicyText`
        if (props != null && props.has("LifecyclePolicy")) {
            JsonNode lp = ctx.engine().resolveNode(props.get("LifecyclePolicy"));
            String policyText = lp.path("LifecyclePolicyText").asText(null);
            if (policyText != null && !policyText.isEmpty()) {
                ecrService.putLifecyclePolicy(repoName, null, policyText, ctx.region());
            }
        }
        if (props != null && props.has("RepositoryPolicyText")) {
            JsonNode pol = ctx.engine().resolveNode(props.get("RepositoryPolicyText"));
            String policyText = pol.isTextual() ? pol.asText() : pol.toString();
            if (policyText != null && !policyText.isEmpty()) {
                ecrService.setRepositoryPolicy(repoName, null, policyText, ctx.region());
            }
        }

        r.setPhysicalId(repoName);
        r.getAttributes().put("Arn", repo.getRepositoryArn());
        r.getAttributes().put("RepositoryUri", repo.getRepositoryUri());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        ecrService.deleteRepository(physicalId, null, true, region);
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
