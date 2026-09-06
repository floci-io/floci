package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::VPC}. Extracted verbatim from
 * {@code CloudFormationResourceProvisioner} as part of the per-service decomposition.
 */
@ApplicationScoped
public class Ec2VpcCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(Ec2VpcCfnProvisioner.class);

    private final Ec2Service ec2Service;

    @Inject
    public Ec2VpcCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::VPC");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String cidr = ctx.resolveOptional(props, "CidrBlock");
        Vpc reconciled = existingVpcToReconcile(ctx.isUpdate() ? ctx.priorPhysicalId() : null, cidr, ctx.region());
        final Vpc vpc = reconciled != null ? reconciled : ec2Service.createVpc(ctx.region(), cidr, false);
        r.setPhysicalId(vpc.getVpcId());
        r.getAttributes().put("VpcId", vpc.getVpcId());
        if (vpc.getCidrBlock() != null) {
            r.getAttributes().put("CidrBlock", vpc.getCidrBlock());
        }
        // Fn::GetAtt DefaultSecurityGroup — CDK's Custom::VpcRestrictDefaultSG handler
        // depends on it resolving to the VPC's default security group id.
        ec2Service.describeSecurityGroups(ctx.region(), List.of(), List.of("default"), Map.of()).stream()
                .filter(sg -> vpc.getVpcId().equals(sg.getVpcId()))
                .findFirst()
                .ifPresent(sg -> r.getAttributes().put("DefaultSecurityGroup", sg.getGroupId()));
    }

    /**
     * The VPC this stack resource already points at, when an UpdateStack re-invocation left it
     * unchanged. {@code provision()} re-runs for every resource on every update, so creating
     * unconditionally would mint a fresh VPC id and silently orphan every subnet, route table and
     * security group that referenced the old one.
     *
     * <p>Returns {@code null} for a fresh create, for a CidrBlock change (which AWS treats as a
     * replacement), or when the VPC is gone from the backend - the caller then creates.
     *
     * <p>The prior id is the one the context captured, not the one on the {@link StackResource}:
     * {@code provision} assigns the new id onto that resource as it runs, so a resource-derived
     * check flips from create to update mid-method.
     */
    private Vpc existingVpcToReconcile(String priorPhysicalId, String cidr, String region) {
        if (priorPhysicalId == null || priorPhysicalId.isBlank()) {
            return null;
        }
        Vpc existing;
        try {
            existing = ec2Service.describeVpcs(region, List.of(priorPhysicalId), Map.of())
                    .stream().findFirst().orElse(null);
        } catch (AwsException notFound) {
            // Expected when the VPC was deleted out of band since the prior update.
            LOG.debugv(notFound, "No existing VPC {0} found on file, falling back to create", priorPhysicalId);
            return null;
        }
        if (existing == null) {
            return null;
        }
        // A changed CidrBlock is a replacement on AWS, so let the caller create a new VPC.
        if (cidr != null && !cidr.isBlank() && !cidr.equals(existing.getCidrBlock())) {
            return null;
        }
        return existing;
    }

    // No delete override: the switch this replaces had no AWS::EC2::VPC delete arm,
    // so stack teardown leaves the VPC alone. Adding one here would change teardown
    // behavior beyond the scope of this extraction.
}
