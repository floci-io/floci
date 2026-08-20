package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
            case "AWS::Lambda::MicrovmImage" -> microvmsService.deleteImage(region, physicalId);
            case "AWS::Lambda::NetworkConnector" -> microvmsService.deleteConnector(region, physicalId);
            default -> { }
        }
    }

    private void provisionImage(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), 64, false);
        }
        String codeArtifactUri = null;
        if (props != null && props.has("CodeArtifact") && props.get("CodeArtifact").has("Uri")) {
            codeArtifactUri = ctx.engine().resolve(props.get("CodeArtifact").get("Uri"));
        }
        LambdaMicrovmsService.MicrovmImage image = microvmsService.createImage(
                ctx.region(),
                ctx.accountId(),
                name,
                ctx.resolveOptional(props, "BaseImageArn"),
                ctx.resolveOptional(props, "BuildRoleArn"),
                codeArtifactUri,
                ctx.resolveOptional(props, "Description"));
        r.setPhysicalId(image.name);
        r.getAttributes().put("ImageArn", image.imageArn);
        r.getAttributes().put("Arn", image.imageArn);
        r.getAttributes().put("Name", image.name);
        r.getAttributes().put("LatestActiveImageVersion", image.latestActiveImageVersion);
    }

    private void provisionConnector(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), 64, false);
        }
        JsonNode vpc = props == null
                ? null
                : props.path("Configuration").path("VpcEgressConfiguration");
        String networkProtocol = null;
        if (vpc != null && vpc.has("NetworkProtocol")) {
            networkProtocol = ctx.engine().resolve(vpc.get("NetworkProtocol"));
        }
        LambdaMicrovmsService.NetworkConnector connector = microvmsService.createConnector(
                ctx.region(),
                ctx.accountId(),
                name,
                resolveList(vpc == null ? null : vpc.get("SubnetIds"), ctx),
                resolveList(vpc == null ? null : vpc.get("SecurityGroupIds"), ctx),
                ctx.resolveOptional(props, "OperatorRole"),
                java.util.UUID.randomUUID().toString(),
                resolveList(vpc == null ? null : vpc.get("AssociatedComputeResourceTypes"), ctx),
                networkProtocol);
        r.setPhysicalId(connector.id);
        r.getAttributes().put("Arn", connector.arn);
        r.getAttributes().put("Id", connector.id);
        r.getAttributes().put("Name", connector.name);
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
