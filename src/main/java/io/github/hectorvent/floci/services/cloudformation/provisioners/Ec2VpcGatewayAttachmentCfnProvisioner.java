package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::VPCGatewayAttachment} (issue #1970).
 *
 * <p>The physical id is {@code igw|<vpcId>|<gatewayId>}. The leading kind tag records how the
 * attachment was declared rather than inferring it from the gateway id's prefix, so a template
 * that puts an {@code igw-*} value in {@code VpnGatewayId} can never make {@link #delete} tear
 * down an internet-gateway attachment this stack never created.
 */
@ApplicationScoped
public class Ec2VpcGatewayAttachmentCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(Ec2VpcGatewayAttachmentCfnProvisioner.class);

    /** Kind tag for the internet-gateway form — the only form with a backing model. */
    private static final String IGW_KIND = "igw";

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
        if (vpcId == null || vpcId.isBlank()) {
            // Without this the attachment lands with a null VpcId, which both shows up in
            // DescribeInternetGateways and makes every later detach on that gateway throw.
            throw new AwsException("ValidationError",
                    "Property VpcId is required for AWS::EC2::VPCGatewayAttachment", 400);
        }

        String igwId = ctx.resolveOptional(props, "InternetGatewayId");
        String vgwId = ctx.resolveOptional(props, "VpnGatewayId");
        boolean hasIgw = igwId != null && !igwId.isBlank();
        boolean hasVgw = vgwId != null && !vgwId.isBlank();
        if (hasIgw == hasVgw) {
            throw new AwsException("ValidationError",
                    "AWS::EC2::VPCGatewayAttachment requires exactly one of "
                    + "InternetGatewayId or VpnGatewayId", 400);
        }
        if (hasVgw) {
            // There is no VPN gateway model to attach to and nothing DescribeVpnGateways
            // could report afterwards, so recording a physical id would report
            // CREATE_COMPLETE for an attachment that does not exist. Fail instead.
            throw new AwsException("ValidationError",
                    "AWS::EC2::VPCGatewayAttachment with VpnGatewayId is not supported: "
                    + "this emulator has no VPN gateway model", 400);
        }

        String physicalId = IGW_KIND + "|" + vpcId + "|" + igwId;
        String prior = r.getPhysicalId();
        if (physicalId.equals(prior)) {
            // Unchanged on update — re-attaching would duplicate the attachment.
            return;
        }

        ec2Service.attachInternetGateway(ctx.region(), igwId, vpcId);
        // Only after the new pair is attached, so a failed update leaves the previous
        // attachment in place instead of orphaning it.
        detachAttachment(prior, ctx.region());
        r.setPhysicalId(physicalId);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        detachAttachment(physicalId, region);
    }

    /** Detaches the pair encoded in a physical id, ignoring ids this provisioner did not write. */
    private void detachAttachment(String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        String[] parts = physicalId.split("\\|", 3);
        if (parts.length != 3 || !IGW_KIND.equals(parts[0])) {
            return;
        }
        try {
            ec2Service.detachInternetGateway(region, parts[2], parts[1]);
        } catch (Exception e) {
            LOG.debugv("Could not detach VPC gateway attachment {0}: {1}", physicalId, e.getMessage());
        }
    }
}
