package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.CidrCanonicalizer;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcCidrBlockAssociation;
import io.github.hectorvent.floci.services.ec2.model.VpcIpv6CidrBlockAssociation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CloudFormation provisioning for {@code AWS::EC2::VPC}.
 *
 * <p>Property and attribute contract follows
 * https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-resource-ec2-vpc.html:
 * {@code CidrBlock} is create-only and a change replaces the VPC; {@code InstanceTenancy} is
 * applied in place only for the {@code dedicated} to {@code default} direction AWS allows, any
 * other change replaces; {@code EnableDnsSupport}, {@code EnableDnsHostnames} and {@code Tags} are
 * applied in place on every provision. {@code Ref} is the VPC id; {@code Fn::GetAtt} exposes
 * VpcId, CidrBlock, CidrBlockAssociations, DefaultNetworkAcl, DefaultSecurityGroup and
 * Ipv6CidrBlocks. List-valued attributes are comma-joined like the other provisioners' lists.
 *
 * <p>Tags are reconciled to the template's {@code Tags} the way {@link Ec2Tags} does for the
 * instance and security-group provisioners: a key the template dropped is deleted. Stack-level
 * tags and the {@code aws:cloudformation:*} system tags are not applied here, matching every other
 * EC2 provisioner in this package.
 */
@ApplicationScoped
public class Ec2VpcCfnProvisioner implements CfnResourceProvisioner {

    private static final String DEFAULT_TENANCY = "default";
    private static final String DEDICATED_TENANCY = "dedicated";

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
        String region = ctx.region();
        String cidr = ctx.resolveOptional(props, "CidrBlock");
        String tenancy = ctx.resolveOptional(props, "InstanceTenancy");
        String desiredTenancy = tenancy == null || tenancy.isBlank() ? DEFAULT_TENANCY : tenancy;
        Vpc reconciled = existingVpcToReconcile(ctx.isUpdate() ? ctx.priorPhysicalId() : null, cidr, desiredTenancy, region);
        Vpc vpc = reconciled != null
                ? reconciled
                : ec2Service.createVpc(region, cidr, false, false, desiredTenancy);
        String vpcId = vpc.getVpcId();
        if (reconciled != null && isDedicatedToDefault(vpc, desiredTenancy)) {
            ec2Service.modifyVpcTenancy(region, vpcId, desiredTenancy);
        }

        // AWS defaults for a non-default VPC: DnsSupport on, DnsHostnames off. A template that
        // omits the property (or resolves it to AWS::NoValue, which the engine renders blank) gets
        // the default, as CloudFormation applies it on every update too.
        String dnsSupport = ctx.resolveOrDefault(props, "EnableDnsSupport", "true");
        String dnsHostnames = ctx.resolveOrDefault(props, "EnableDnsHostnames", "false");
        ec2Service.modifyVpcAttribute(region, vpcId, "enableDnsSupport",
                String.valueOf(Boolean.parseBoolean(dnsSupport)));
        ec2Service.modifyVpcAttribute(region, vpcId, "enableDnsHostnames",
                String.valueOf(Boolean.parseBoolean(dnsHostnames)));

        Ec2Tags.reconcile(ec2Service, region, vpcId, ctx.resolveTags(props, "Tags"));

        r.setPhysicalId(vpcId);
        r.getAttributes().put("VpcId", vpcId);
        if (vpc.getCidrBlock() != null) {
            r.getAttributes().put("CidrBlock", vpc.getCidrBlock());
        }
        r.getAttributes().put("CidrBlockAssociations", vpc.getCidrBlockAssociationSet().stream()
                .map(VpcCidrBlockAssociation::getAssociationId)
                .collect(Collectors.joining(",")));
        r.getAttributes().put("Ipv6CidrBlocks", vpc.getIpv6CidrBlockAssociationSet().stream()
                .map(VpcIpv6CidrBlockAssociation::getIpv6CidrBlock)
                .collect(Collectors.joining(",")));
        // Fn::GetAtt DefaultSecurityGroup — CDK's Custom::VpcRestrictDefaultSG handler
        // depends on it resolving to the VPC's default security group id.
        ec2Service.describeSecurityGroups(region, List.of(), List.of("default"), Map.of()).stream()
                .filter(sg -> vpcId.equals(sg.getVpcId()))
                .findFirst()
                .ifPresent(sg -> r.getAttributes().put("DefaultSecurityGroup", sg.getGroupId()));
        ec2Service.describeNetworkAcls(region, List.of(),
                        Map.of("vpc-id", List.of(vpcId), "default", List.of("true"))).stream()
                .map(NetworkAcl::getNetworkAclId)
                .findFirst()
                .ifPresent(aclId -> r.getAttributes().put("DefaultNetworkAcl", aclId));
    }

    /**
     * The VPC this stack resource already points at, when an UpdateStack re-invocation left it
     * unchanged. {@code provision()} re-runs for every resource on every update, so creating
     * unconditionally would mint a fresh VPC id and silently orphan every subnet, route table and
     * security group that referenced the old one.
     *
     * <p>Returns {@code null} for a fresh create, for a CidrBlock change or an InstanceTenancy
     * change other than {@code dedicated} to {@code default} (which AWS treats as a replacement),
     * or when the VPC is gone from the backend - the caller then creates.
     *
     * <p>The prior id is the one the context captured, not the one on the {@link StackResource}:
     * {@code provision} assigns the new id onto that resource as it runs, so a resource-derived
     * check flips from create to update mid-method.
     */
    private Vpc existingVpcToReconcile(String priorPhysicalId, String cidr, String tenancy, String region) {
        if (priorPhysicalId == null || priorPhysicalId.isBlank()) {
            return null;
        }
        Vpc existing = ec2Service.describeVpcs(region, List.of(priorPhysicalId), Map.of())
                .stream().findFirst().orElse(null);
        if (existing == null) {
            return null;
        }
        // A changed CidrBlock is a replacement on AWS, so let the caller create a new VPC. Compare both
        // sides canonically (10.0.0.5/16 is 10.0.0.0/16): a VPC saved before canonicalization may
        // still hold host bits.
        if (cidr != null && !cidr.isBlank()
                && !CidrCanonicalizer.sameBlock(cidr, existing.getCidrBlock())) {
            return null;
        }
        // ModifyVpcTenancy only goes dedicated -> default; every other tenancy change replaces.
        if (!tenancy.equals(existing.getInstanceTenancy()) && !isDedicatedToDefault(existing, tenancy)) {
            return null;
        }
        return existing;
    }

    private static boolean isDedicatedToDefault(Vpc existing, String desiredTenancy) {
        return DEDICATED_TENANCY.equals(existing.getInstanceTenancy()) && DEFAULT_TENANCY.equals(desiredTenancy);
    }

    // No delete override: the switch this replaces had no AWS::EC2::VPC delete arm,
    // so stack teardown leaves the VPC alone. Adding one here would change teardown
    // behavior beyond the scope of this extraction.
}
