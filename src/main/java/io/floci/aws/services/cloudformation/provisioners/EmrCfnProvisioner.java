package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.emr.EmrService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for Amazon EMR's own types over {@link EmrService}: for now
 * {@code AWS::EMR::SecurityConfiguration}, whose {@code Ref} is its name and which has no {@code Fn::GetAtt}
 * attributes.
 *
 * <p>Both of a security configuration's properties are create-only and its schema has no update handler, so any change
 * replaces it: under a new name, generated when the template gives none, and refused, as for every custom-named
 * resource, when the template keeps its own name. The {@code SecurityConfiguration} JSON is taken as an object or as
 * the text of one, as the schema allows both.
 */
@ApplicationScoped
public class EmrCfnProvisioner implements CfnResourceProvisioner {

    private static final String SECURITY_CONFIGURATION = "AWS::EMR::SecurityConfiguration";
    private static final int NAME_MAX_LENGTH = 255;

    private final EmrService emrService;

    @Inject
    public EmrCfnProvisioner(EmrService emrService) {
        this.emrService = emrService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(SECURITY_CONFIGURATION);
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
        JsonNode template = new CfnPropertyTypes.HandlerReader(ctx.engine()).read(SECURITY_CONFIGURATION, props);
        String explicitName = template.path("Name").textValue();
        boolean stub = CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore);
        if (ctx.isUpdate() && !stub && !ctx.changes(SECURITY_CONFIGURATION, props).requiresReplacement()) {
            r.setPhysicalId(ctx.priorPhysicalId());
        } else {
            if (ctx.isUpdate() && !stub && explicitName != null && explicitName.equals(ctx.priorPhysicalId())) {
                throw CfnExceptions.customNamedReplacement(explicitName);
            }
            String name = explicitName != null ? explicitName
                    : ctx.generatePhysicalName(r.getLogicalId(), NAME_MAX_LENGTH, false);
            JsonNode configuration = template.path("SecurityConfiguration");
            emrService.createSecurityConfiguration(name,
                    configuration.isTextual() ? configuration.textValue() : configuration.toString(), ctx.region());
            r.setPhysicalId(name);
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /** Deletes the security configuration, unless the Region lists none of that name, it being gone. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (emrService.listSecurityConfigurations(region).stream().anyMatch(sc -> physicalId.equals(sc.getName()))) {
            emrService.deleteSecurityConfiguration(physicalId, region);
        }
    }
}
