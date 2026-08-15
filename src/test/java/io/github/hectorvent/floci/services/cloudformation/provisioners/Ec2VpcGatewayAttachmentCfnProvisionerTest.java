package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The failure modes the internet-gateway happy path does not reach: a missing VpcId, the
 * unbacked VPN form, an update that would orphan the previous pair, and a physical id whose
 * gateway id looks like an internet gateway but was never declared as one.
 */
class Ec2VpcGatewayAttachmentCfnProvisionerTest {

    private static final String REGION = "us-east-1";

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2VpcGatewayAttachmentCfnProvisioner provisioner =
            new Ec2VpcGatewayAttachmentCfnProvisioner(ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void internetGatewayAttachmentRecordsAKindTaggedPhysicalId() {
        StackResource r = resource();

        provisioner.provision(r, props("vpc-1", "igw-1", null), ctx());

        verify(ec2).attachInternetGateway(REGION, "igw-1", "vpc-1");
        assertEquals("igw|vpc-1|igw-1", r.getPhysicalId());
    }

    @Test
    void missingVpcIdIsRejectedInsteadOfAttachingNull() {
        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props(null, "igw-1", null), ctx()));

        assertEquals("ValidationError", e.getErrorCode());
        verifyNoInteractions(ec2);
    }

    @Test
    void exactlyOneGatewayIsRequired() {
        assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props("vpc-1", null, null), ctx()));
        assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props("vpc-1", "igw-1", "vgw-1"), ctx()));
        verifyNoInteractions(ec2);
    }

    @Test
    void vpnGatewayAttachmentFailsRatherThanReportingSuccess() {
        StackResource r = resource();

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("vpc-1", null, "vgw-1"), ctx()));

        assertTrue(e.getMessage().contains("VpnGatewayId"), e.getMessage());
        // No physical id, so nothing for delete to act on later.
        assertEquals(null, r.getPhysicalId());
        verifyNoInteractions(ec2);
    }

    @Test
    void anInternetGatewayIdDeclaredAsAVpnGatewayNeverReachesDetach() {
        // The template is malformed, but the danger is the delete path treating
        // the value as an internet gateway and detaching one this stack never attached.
        assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props("vpc-1", null, "igw-existing"), ctx()));

        provisioner.delete("AWS::EC2::VPCGatewayAttachment", "vgw|vpc-1|igw-existing", REGION);
        provisioner.delete("AWS::EC2::VPCGatewayAttachment", "vpc-1|igw-existing", REGION);

        verifyNoInteractions(ec2);
    }

    @Test
    void updateAttachesTheNewPairBeforeDetachingTheOld() {
        StackResource r = resource();
        r.setPhysicalId("igw|vpc-old|igw-old");

        provisioner.provision(r, props("vpc-new", "igw-new", null), ctx());

        // Order matters: a failed attach must leave the previous attachment alone.
        var order = inOrder(ec2);
        order.verify(ec2).attachInternetGateway(REGION, "igw-new", "vpc-new");
        order.verify(ec2).detachInternetGateway(REGION, "igw-old", "vpc-old");
        assertEquals("igw|vpc-new|igw-new", r.getPhysicalId());
    }

    @Test
    void unchangedUpdateDoesNotReattach() {
        StackResource r = resource();
        r.setPhysicalId("igw|vpc-1|igw-1");

        provisioner.provision(r, props("vpc-1", "igw-1", null), ctx());

        assertEquals("igw|vpc-1|igw-1", r.getPhysicalId());
        verifyNoInteractions(ec2);
    }

    @Test
    void deleteDetachesTheRecordedPair() {
        provisioner.delete("AWS::EC2::VPCGatewayAttachment", "igw|vpc-1|igw-1", REGION);

        verify(ec2).detachInternetGateway(REGION, "igw-1", "vpc-1");
        verifyNoMoreInteractions(ec2);
    }

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Attachment");
        r.setResourceType("AWS::EC2::VPCGatewayAttachment");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode props(String vpcId, String igwId, String vgwId) {
        ObjectNode props = mapper.createObjectNode();
        if (vpcId != null) { props.put("VpcId", vpcId); }
        if (igwId != null) { props.put("InternetGatewayId", igwId); }
        if (vgwId != null) { props.put("VpnGatewayId", vgwId); }
        return props;
    }
}
