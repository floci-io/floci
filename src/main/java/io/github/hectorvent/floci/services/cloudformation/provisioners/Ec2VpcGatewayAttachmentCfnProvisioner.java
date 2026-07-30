package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::VPCGatewayAttachment} (issue #1970).
 * The physical id encodes both sides of the attachment as {@code <vpcId>|<gatewayId>} so
 * {@link #delete} can detach the internet gateway on stack deletion instead of orphaning it.
 */
@ApplicationScoped
public class Ec2VpcGatewayAttachmentCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(Ec2VpcGatewayAttachmentCfnProvisioner.class);

    private final Ec2Service ec2Service;

    @Inject
    public Ec2VpcGatewayAttachmentCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::VPCGatewayAttachment");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String vpcId = ctx.resolveOptional(props, "VpcId");
        String igwId = ctx.resolveOptional(props, "InternetGatewayId");
        if (igwId != null && !igwId.isBlank()) {
            ec2Service.attachInternetGateway(ctx.region(), igwId, vpcId);
            r.setPhysicalId(vpcId + "|" + igwId);
        } else {
            // VpnGatewayId variant — record the attachment; there is no VPN gateway model.
            String vgwId = ctx.resolveOptional(props, "VpnGatewayId");
            r.setPhysicalId(vpcId + "|" + (vgwId == null ? "" : vgwId));
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        try {
            String[] parts = physicalId.split("\\|", 2);
            if (parts.length == 2 && parts[1].startsWith("igw-")) {
                ec2Service.detachInternetGateway(region, parts[1], parts[0]);
            }
        } catch (Exception e) {
            LOG.debugv("Could not detach VPC gateway attachment {0}: {1}", physicalId, e.getMessage());
        }
    }
}
