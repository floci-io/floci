package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CloudFormation provisioning for AWS Lambda MicroVMs:
 * {@code AWS::Lambda::MicrovmImage} and {@code AWS::Lambda::NetworkConnector}.
 *
 * <p>Connector configuration nests under
 * {@code Configuration.VpcEgressConfiguration}, whose {@code SecurityGroupIds}
 * entries commonly arrive as {@code Fn::GetAtt} references to a security
 * group's {@code GroupId} — each array element resolves through the engine
 * individually. An image's {@code EgressNetworkConnectors} likewise arrive as
 * references to connector ARNs.</p>
 *
 * <p>Both types are identified by their ARN, the registry schemas' primaryIdentifier, so {@code Ref}
 * returns it. {@code Name} is the only createOnly property of either: an update that keeps it
 * reconciles the existing entity in place, and one that changes it creates a replacement and the
 * displaced entity is deleted once the update commits, through {@link ReplacementCleanup}.</p>
 */
@ApplicationScoped
public class LambdaMicrovmsCfnProvisioner implements CfnResourceProvisioner {

    private final LambdaMicrovmsService microvmsService;

    @Inject
    public LambdaMicrovmsCfnProvisioner(LambdaMicrovmsService microvmsService) {
        this.microvmsService = microvmsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Lambda::MicrovmImage", "AWS::Lambda::NetworkConnector");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::Lambda::MicrovmImage" -> provisionImage(r, props, ctx);
            case "AWS::Lambda::NetworkConnector" -> provisionConnector(r, props, ctx);
            default -> throw new IllegalStateException(
                    "LambdaMicrovmsCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case "AWS::Lambda::MicrovmImage" -> CfnDeletes.safeDelete("MicroVM image", physicalId,
                    () -> microvmsService.deleteImage(region, physicalId), "ResourceNotFoundException");
            case "AWS::Lambda::NetworkConnector" -> CfnDeletes.safeDelete("network connector", physicalId,
                    () -> microvmsService.deleteConnector(region, physicalId), "ResourceNotFoundException");
            default -> { }
        }
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

    private void provisionImage(StackResource r, JsonNode props, ProvisionContext ctx) {
        String priorName = r.getAttributes().get("Name");
        String name = nameOrPrior(ctx.resolveOptional(props, "Name"), priorName, r, ctx);
        String codeArtifactUri = null;
        if (props != null && props.has("CodeArtifact") && props.get("CodeArtifact").has("Uri")) {
            codeArtifactUri = ctx.engine().resolve(props.get("CodeArtifact").get("Uri"));
        }
        String baseImageArn = ctx.resolveOptional(props, "BaseImageArn");
        String buildRoleArn = ctx.resolveOptional(props, "BuildRoleArn");
        String description = ctx.resolveOptional(props, "Description");
        // provision is also the update path. With the name stable, the second UpdateStack reaches
        // the service with an image that exists; the registry schema's update handler is
        // UpdateMicrovmImage, so reconcile through updateImage rather than mint a version through
        // createImage. A replacing update derives a different name and still creates.
        boolean reused = ctx.isUpdate() && name.equals(priorName);
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        LambdaMicrovmsService.MicrovmImage image = reused
                ? microvmsService.updateImage(ctx.region(), name, baseImageArn, buildRoleArn,
                        codeArtifactUri, description)
                : microvmsService.createImage(ctx.region(), ctx.accountId(), name, baseImageArn,
                        buildRoleArn, codeArtifactUri, description);
        settle(r, ctx, attributesBefore, reused, image.imageArn);
        r.getAttributes().put("ImageArn", image.imageArn);
        r.getAttributes().put("Arn", image.imageArn);
        r.getAttributes().put("Name", image.name);
        r.getAttributes().put("LatestActiveImageVersion", image.latestActiveImageVersion);
    }

    private void provisionConnector(StackResource r, JsonNode props, ProvisionContext ctx) {
        String priorName = r.getAttributes().get("Name");
        String name = nameOrPrior(ctx.resolveOptional(props, "Name"), priorName, r, ctx);
        JsonNode vpc = props == null
                ? null
                : props.path("Configuration").path("VpcEgressConfiguration");
        String networkProtocol = null;
        if (vpc != null && vpc.has("NetworkProtocol")) {
            networkProtocol = ctx.engine().resolve(vpc.get("NetworkProtocol"));
        }
        List<String> subnetIds = resolveList(vpc == null ? null : vpc.get("SubnetIds"), ctx);
        List<String> securityGroupIds = resolveList(vpc == null ? null : vpc.get("SecurityGroupIds"), ctx);
        // The schema's update handler is UpdateNetworkConnector, so an update that keeps the name
        // reconciles the connector it already has instead of creating a second one.
        boolean reused = ctx.isUpdate() && name.equals(priorName);
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        LambdaMicrovmsService.NetworkConnector connector = reused
                ? microvmsService.updateConnector(ctx.region(), ctx.priorPhysicalId(), subnetIds, securityGroupIds)
                : microvmsService.createConnector(
                        ctx.region(),
                        ctx.accountId(),
                        name,
                        subnetIds,
                        securityGroupIds,
                        ctx.resolveOptional(props, "OperatorRole"),
                        UUID.randomUUID().toString(),
                        resolveList(vpc == null ? null : vpc.get("AssociatedComputeResourceTypes"), ctx),
                        networkProtocol);
        settle(r, ctx, attributesBefore, reused, connector.arn);
        r.getAttributes().put("Arn", connector.arn);
        r.getAttributes().put("Id", connector.id);
        r.getAttributes().put("Name", connector.name);
    }

    /**
     * The template's name when it gives one, otherwise the name this resource already had, and only
     * failing both a generated one. The physical id is the ARN, so the prior name comes from the
     * {@code Name} attribute recorded at create time rather than {@link ProvisionContext#stablePhysicalName}.
     */
    private static String nameOrPrior(String declared, String prior, StackResource r, ProvisionContext ctx) {
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        if (ctx.isUpdate() && prior != null && !prior.isBlank()) {
            return prior;
        }
        return ctx.generatePhysicalName(r.getLogicalId(), 64, false);
    }

    /**
     * Sets the ARN as the physical id and records any replacement. A reuse records before the id
     * changes: a stack created when the physical id was still the image name or connector id names
     * the entity it reuses by that, and recording after the switch would mark it displaced and
     * delete it once the update commits.
     */
    private static void settle(StackResource r, ProvisionContext ctx, Map<String, String> attributesBefore,
                               boolean reused, String arn) {
        if (reused) {
            ReplacementCleanup.record(r, ctx, attributesBefore);
            r.setPhysicalId(arn);
            return;
        }
        r.setPhysicalId(arn);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /** Resolves each array element through the engine so intrinsic refs work per entry. */
    private List<String> resolveList(JsonNode node, ProvisionContext ctx) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> out.add(ctx.engine().resolve(item)));
        return out;
    }
}
