package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * CloudFormation provisioning for the EKS control-plane types. {@code AWS::EKS::Cluster} and
 * {@code AWS::EKS::Nodegroup} share one provisioner over {@link EksService} because a nodegroup
 * belongs to a cluster and its delete needs the cluster name, which is stored as a create-time
 * attribute. {@code Ref} returns the cluster name for a Cluster and the nodegroup name for a
 * Nodegroup, matching what the legacy switch published.
 */
@ApplicationScoped
public class EksCfnProvisioner implements CfnResourceProvisioner {

    private static final String CLUSTER = "AWS::EKS::Cluster";
    private static final String NODEGROUP = "AWS::EKS::Nodegroup";

    private final EksService eksService;

    @Inject
    public EksCfnProvisioner(EksService eksService) {
        this.eksService = eksService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CLUSTER, NODEGROUP);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case CLUSTER -> provisionCluster(r, props, ctx);
            case NODEGROUP -> provisionNodegroup(r, props, ctx);
            default -> throw new IllegalStateException("Unhandled type: " + r.getResourceType());
        }
    }

    private void provisionCluster(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), 100, false);
        }
        CreateClusterRequest request = new CreateClusterRequest();
        request.setName(name);
        request.setVersion(ctx.resolveOptional(props, "Version"));
        request.setRoleArn(ctx.resolveOptional(props, "RoleArn"));

        Cluster cluster = eksService.createCluster(request);
        r.setPhysicalId(cluster.getName());
        r.getAttributes().put("Arn", cluster.getArn());
        if (cluster.getEndpoint() != null) {
            r.getAttributes().put("Endpoint", cluster.getEndpoint());
        }
        if (cluster.getCertificateAuthority() != null && cluster.getCertificateAuthority().getData() != null) {
            r.getAttributes().put("CertificateAuthorityData", cluster.getCertificateAuthority().getData());
        }
        if (cluster.getIdentity() != null && cluster.getIdentity().getOidc() != null
                && cluster.getIdentity().getOidc().getIssuer() != null) {
            r.getAttributes().put("OpenIdConnectIssuerUrl", cluster.getIdentity().getOidc().getIssuer());
        }
    }

    private void provisionNodegroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterName = ctx.resolveOptional(props, "ClusterName");
        Nodegroup request = new Nodegroup();
        String nodegroupName = ctx.resolveOptional(props, "NodegroupName");
        if (nodegroupName == null || nodegroupName.isBlank()) {
            nodegroupName = ctx.generatePhysicalName(r.getLogicalId(), 100, false);
        }
        request.setNodegroupName(nodegroupName);
        request.setNodeRole(ctx.resolveOptional(props, "NodeRole"));
        request.setSubnets(ctx.resolveStringList(props, "Subnets"));

        Nodegroup nodegroup = eksService.createNodeGroup(clusterName, request);
        r.setPhysicalId(nodegroup.getNodegroupName());
        // ClusterName is stored so delete() can find the cluster, and NodegroupName mirrors the
        // physical id; Arn is the schema's read-only attribute.
        r.getAttributes().put("ClusterName", nodegroup.getClusterName());
        r.getAttributes().put("NodegroupName", nodegroup.getNodegroupName());
        if (nodegroup.getNodegroupArn() != null) {
            r.getAttributes().put("Arn", nodegroup.getNodegroupArn());
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        switch (resource.getResourceType()) {
            case CLUSTER -> CfnDeletes.safeDelete("EKS cluster", resource.getPhysicalId(),
                    () -> eksService.deleteCluster(resource.getPhysicalId()), "ResourceNotFoundException");
            case NODEGROUP -> {
                // Deleting a nodegroup needs the cluster name (a create-time attribute) as well as
                // the nodegroup name (the physical id), which the id-only delete path cannot supply.
                String clusterName = resource.getAttributes().get("ClusterName");
                if (clusterName != null && !clusterName.isBlank()) {
                    CfnDeletes.safeDelete("EKS nodegroup", resource.getPhysicalId(),
                            () -> eksService.deleteNodeGroup(clusterName, resource.getPhysicalId()),
                            "ResourceNotFoundException");
                }
            }
            default -> {
                // No other type reaches this provisioner's delete.
            }
        }
    }
}
