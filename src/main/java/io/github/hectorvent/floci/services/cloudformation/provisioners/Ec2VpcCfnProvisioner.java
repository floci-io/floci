package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::VPC}. Extracted verbatim from
 * {@code CloudFormationResourceProvisioner} as part of the per-service decomposition.
 */
@ApplicationScoped
public class Ec2VpcCfnProvisioner implements CfnResourceProvisioner {

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
        var vpc = ec2Service.createVpc(ctx.region(), cidr, false);
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

    // No delete override: the switch this replaces had no AWS::EC2::VPC delete arm,
    // so stack teardown leaves the VPC alone. Adding one here would change teardown
    // behavior beyond the scope of this extraction.
}
