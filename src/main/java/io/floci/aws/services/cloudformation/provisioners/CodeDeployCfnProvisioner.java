package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.codedeploy.CodeDeployService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::CodeDeploy::Application}. Previously unhandled, so
 * the type fell through to the generic stub: the stack reported CREATE_COMPLETE and no application
 * existed in CodeDeploy (issue #8626). {@code Ref} is the application name; the registry schema
 * declares no {@code Fn::GetAtt} attributes.
 */
@ApplicationScoped
public class CodeDeployCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::CodeDeploy::Application";
    private static final int APPLICATION_NAME_MAX_LENGTH = 100;

    private final CodeDeployService codeDeployService;

    @Inject
    public CodeDeployCfnProvisioner(CodeDeployService codeDeployService) {
        this.codeDeployService = codeDeployService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
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
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String explicitName = written(props, "ApplicationName", ctx);
        boolean hasExplicitName = explicitName != null;
        String priorPhysicalId = ctx.priorPhysicalId();

        // ApplicationName and ComputePlatform are create-only; only tags update in place.
        boolean replaces = ctx.isUpdate() && ctx.changes(TYPE, props).requiresReplacement();
        if (replaces && hasExplicitName && explicitName.equals(priorPhysicalId)) {
            throw CfnExceptions.customNamedReplacement(explicitName);
        }
        String name;
        if (hasExplicitName) {
            name = explicitName;
        } else if (ctx.isUpdate() && !replaces) {
            name = priorPhysicalId;
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), APPLICATION_NAME_MAX_LENGTH, false);
        }

        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        if (ctx.reusesPriorEntity(name)) {
            reconcileTags(codeDeployService.applicationArn(ctx.region(), name), tags);
        } else {
            codeDeployService.createApplication(ctx.region(), name, written(props, "ComputePlatform", ctx),
                    toTagList(tags));
        }
        r.setPhysicalId(name);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * A property's value, or null when it is absent or resolves to nothing: the engine resolves
     * {@code AWS::NoValue} to blank. A blank written into the template is kept, so CodeDeploy rejects
     * it as on AWS rather than the provisioner silently generating a name or defaulting the platform.
     */
    private static String written(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        return value == null || (value.isBlank() && !props.get(name).isTextual()) ? null : value;
    }

    private void reconcileTags(String arn, Map<String, String> desired) {
        Map<String, String> current = new HashMap<>();
        codeDeployService.listTagsForResource(arn).forEach(t -> current.put(t.get("Key"), t.get("Value")));
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            codeDeployService.untagResource(arn, stale);
        }
        if (!desired.isEmpty()) {
            codeDeployService.tagResource(arn, toTagList(desired));
        }
    }

    private static List<Map<String, String>> toTagList(Map<String, String> tags) {
        return tags.entrySet().stream().map(e -> Map.of("Key", e.getKey(), "Value", e.getValue())).toList();
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("CodeDeploy application", physicalId,
                () -> codeDeployService.deleteApplication(region, physicalId), "ApplicationDoesNotExistException");
    }
}
