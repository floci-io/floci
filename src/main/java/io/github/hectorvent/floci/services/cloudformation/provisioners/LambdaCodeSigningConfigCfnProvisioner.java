package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaCodeSigningConfigService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.CodeSigningConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Provisions {@code AWS::Lambda::CodeSigningConfig}. {@code Ref} is the config's ARN. Every property
 * updates in place, and an update applies the template's whole state, so a dropped description or
 * policy returns to its default. Deleting a config a function still uses is refused, as Lambda's
 * DeleteCodeSigningConfig refuses it.
 */
@ApplicationScoped
public class LambdaCodeSigningConfigCfnProvisioner implements CfnResourceProvisioner {

    private final LambdaCodeSigningConfigService configs;
    private final LambdaService lambdaService;

    @Inject
    public LambdaCodeSigningConfigCfnProvisioner(LambdaCodeSigningConfigService configs, LambdaService lambdaService) {
        this.configs = configs;
        this.lambdaService = lambdaService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Lambda::CodeSigningConfig");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String description = ctx.resolveOrDefault(props, "Description", "");
        CodeSigningConfig.AllowedPublishers publishers = new CodeSigningConfig.AllowedPublishers();
        publishers.setSigningProfileVersionArns(ctx.resolveStringList(
                ctx.engine().resolveNode(props.path("AllowedPublishers")), "SigningProfileVersionArns"));
        CodeSigningConfig.CodeSigningPolicies policies = new CodeSigningConfig.CodeSigningPolicies();
        policies.setUntrustedArtifactOnDeployment(ctx.resolveOrDefault(
                ctx.engine().resolveNode(props.path("CodeSigningPolicies")), "UntrustedArtifactOnDeployment", "Warn"));

        // A dispatcher stub from before this provisioner existed is not an ARN and names no config.
        CodeSigningConfig config = ctx.isUpdate() && AwsArnUtils.isArn(ctx.priorPhysicalId())
                ? configs.update(ctx.region(), ctx.priorPhysicalId(), description, publishers, policies)
                : configs.create(ctx.region(), ctx.accountId(), description, publishers, policies);
        r.setPhysicalId(config.getCodeSigningConfigArn());
        r.getAttributes().put("CodeSigningConfigArn", config.getCodeSigningConfigArn());
        r.getAttributes().put("CodeSigningConfigId", config.getCodeSigningConfigId());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (!AwsArnUtils.isArn(physicalId)) {
            return;
        }
        String accountId = AwsArnUtils.parse(physicalId).accountId();
        configs.withConfigLock(physicalId, () -> {
            if (!lambdaService.listFunctionsByCodeSigningConfig(region, accountId, physicalId, 1, null)
                    .items().isEmpty()) {
                throw new AwsException("ResourceConflictException",
                        "The code signing configuration is still attached to a function.", 409);
            }
            CfnDeletes.safeDelete("Lambda code signing config", physicalId,
                    () -> configs.delete(region, physicalId), "ResourceNotFoundException");
            return null;
        });
    }
}
