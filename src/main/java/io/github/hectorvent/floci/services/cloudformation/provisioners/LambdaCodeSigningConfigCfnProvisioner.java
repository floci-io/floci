package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaCodeSigningConfigService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.CodeSigningConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provisions {@code AWS::Lambda::CodeSigningConfig}. {@code Ref} is the config's ARN. Every property
 * updates in place, and an update applies the template's whole state, so a dropped description or
 * policy returns to its default. A rolled-back update puts the prior settings back. Deleting a config
 * a function still uses is refused, as Lambda's DeleteCodeSigningConfig refuses it.
 */
@ApplicationScoped
public class LambdaCodeSigningConfigCfnProvisioner implements CfnResourceProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        r.getAttributes().remove(CfnRollback.CODE_SIGNING_CONFIG_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String description = Objects.requireNonNullElse(ctx.resolveOptional(props, "Description"), "");
        CodeSigningConfig.AllowedPublishers publishers = new CodeSigningConfig.AllowedPublishers();
        publishers.setSigningProfileVersionArns(ctx.resolveStringList(
                ctx.engine().resolveNode(props.path("AllowedPublishers")), "SigningProfileVersionArns"));
        CodeSigningConfig.CodeSigningPolicies policies = new CodeSigningConfig.CodeSigningPolicies();
        policies.setUntrustedArtifactOnDeployment(Objects.requireNonNullElse(ctx.resolveOptional(
                ctx.engine().resolveNode(props.path("CodeSigningPolicies")), "UntrustedArtifactOnDeployment"), "Warn"));

        // A dispatcher stub from before this provisioner existed is not an ARN and names no config.
        String prior = ctx.priorPhysicalId();
        CodeSigningConfig config = ctx.isUpdate() && AwsArnUtils.isArn(prior)
                ? configs.withConfigLock(prior, () -> {
                    r.getAttributes().put(CfnRollback.CODE_SIGNING_CONFIG_UPDATE_SNAPSHOT_ATTR,
                            write(configs.get(ctx.region(), prior)));
                    return configs.update(ctx.region(), prior, description, publishers, policies);
                })
                : configs.create(ctx.region(), ctx.accountId(), description, publishers, policies);
        r.setPhysicalId(config.getCodeSigningConfigArn());
        r.getAttributes().put("CodeSigningConfigArn", config.getCodeSigningConfigArn());
        r.getAttributes().put("CodeSigningConfigId", config.getCodeSigningConfigId());
        ReplacementCleanup.record(r, ctx, attributesBefore);
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

    /** A stub migration deletes the config it created; an in-place update puts the prior settings back. */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String raw = resource.getAttributes().get(CfnRollback.CODE_SIGNING_CONFIG_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        CodeSigningConfig prior = read(raw);
        String arn = resource.getPhysicalId();
        String region = AwsArnUtils.parse(arn).region();
        configs.withConfigLock(arn, () -> configs.update(region, arn, prior.getDescription(),
                prior.getAllowedPublishers(), prior.getCodeSigningPolicies()));
        resource.getAttributes().remove(CfnRollback.CODE_SIGNING_CONFIG_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (!AwsArnUtils.isArn(physicalId)) {
            return;
        }
        String accountId = AwsArnUtils.parse(physicalId).accountId();
        configs.withConfigLock(physicalId, () -> {
            CfnDeletes.safeDelete("Lambda code signing config", physicalId, () -> {
                configs.get(region, accountId, physicalId);
                if (!lambdaService.listFunctionsByCodeSigningConfig(region, accountId, physicalId, 1, null)
                        .items().isEmpty()) {
                    throw new AwsException("ResourceConflictException",
                            "The code signing configuration is still attached to a function.", 409);
                }
                configs.delete(region, physicalId);
            }, "ResourceNotFoundException");
            return null;
        });
    }

    private static String write(CodeSigningConfig config) {
        try {
            return MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot snapshot code signing config " + config.getCodeSigningConfigArn(), e);
        }
    }

    private static CodeSigningConfig read(String raw) {
        try {
            return MAPPER.readValue(raw, CodeSigningConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read code signing config snapshot", e);
        }
    }
}
