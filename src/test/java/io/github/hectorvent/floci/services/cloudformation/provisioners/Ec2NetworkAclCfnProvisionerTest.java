package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.NetworkAclAssociation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The EC2 network ACL family provisioner in isolation: one mocked service instead of the
 * ~30 the monolithic provisioner needed.
 */
class Ec2NetworkAclCfnProvisionerTest {

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2NetworkAclCfnProvisioner provisioner = new Ec2NetworkAclCfnProvisioner(ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // The engine is a collaborator; scalars resolve to their text and {"Ref": "X"} nodes
        // resolve to "resolved-X", enough to prove every property goes through the engine.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            if (node == null) {
                return null;
            }
            if (node.isObject() && node.has("Ref")) {
                return "resolved-" + node.get("Ref").asText();
            }
            return node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static NetworkAcl acl(String aclId, String vpcId, boolean isDefault, NetworkAclAssociation... assocs) {
        NetworkAcl acl = new NetworkAcl();
        acl.setNetworkAclId(aclId);
        acl.setVpcId(vpcId);
        acl.setDefault(isDefault);
        acl.setAssociations(List.of(assocs));
        return acl;
    }

    private static NetworkAclAssociation assoc(String assocId, String aclId, String subnetId) {
        NetworkAclAssociation a = new NetworkAclAssociation();
        a.setNetworkAclAssociationId(assocId);
        a.setNetworkAclId(aclId);
        a.setSubnetId(subnetId);
        return a;
    }

    @Test
    void networkAclSetsPhysicalIdAndIdAttribute() {
        when(ec2.createNetworkAcl("us-east-1", "vpc-123")).thenReturn(acl("acl-abc", "vpc-123", false));
        StackResource r = resource("AWS::EC2::NetworkAcl", "Acl");
        ObjectNode props = mapper.createObjectNode().put("VpcId", "vpc-123");

        provisioner.provision(r, props, ctx());

        assertEquals("acl-abc", r.getPhysicalId());
        assertEquals("acl-abc", r.getAttributes().get("Id"));
    }

    @Test
    void aclEntryResolvesEveryPropertyThroughTheEngine() {
        StackResource r = resource("AWS::EC2::NetworkAclEntry", "Entry");
        ObjectNode props = mapper.createObjectNode();
        props.set("NetworkAclId", mapper.createObjectNode().put("Ref", "Acl"));
        // RuleNumber/Egress/PortRange arrive as intrinsics too: they must resolve, not be read raw.
        props.set("RuleNumber", mapper.createObjectNode().put("Ref", "Rule"));
        props.put("Protocol", "6");
        props.put("RuleAction", "deny");
        props.put("Egress", true);
        props.put("CidrBlock", "0.0.0.0/0");
        ObjectNode portRange = mapper.createObjectNode();
        portRange.set("From", mapper.createObjectNode().put("Ref", "Port"));
        portRange.put("To", 22);
        props.set("PortRange", portRange);

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            if (node.isObject() && node.has("Ref")) {
                return switch (node.get("Ref").asText()) {
                    case "Acl" -> "acl-abc";
                    case "Rule" -> "150";
                    case "Port" -> "22";
                    default -> "";
                };
            }
            return node.asText();
        });
        provisioner.provision(r, props, new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack"));

        verify(ec2).createNetworkAclEntry("us-east-1", "acl-abc", 150, "6", "deny", true,
                "0.0.0.0/0", 22, 22, true);
        assertEquals("acl-abc|150|egress", r.getPhysicalId());
    }

    @Test
    void aclEntryDefaultsMatchPriorBehavior() {
        StackResource r = resource("AWS::EC2::NetworkAclEntry", "Entry");
        ObjectNode props = mapper.createObjectNode().put("NetworkAclId", "acl-abc");

        provisioner.provision(r, props, ctx());

        verify(ec2).createNetworkAclEntry("us-east-1", "acl-abc", 100, "-1", null, false,
                null, null, null, true);
        assertEquals("acl-abc|100|ingress", r.getPhysicalId());
    }

    @Test
    void updateReusesTheAclThisStackAlreadyCreated() {
        StackResource r = resource("AWS::EC2::NetworkAcl", "Acl");
        r.setPhysicalId("acl-abc");
        when(ec2.describeNetworkAcls("us-east-1", List.of("acl-abc"), Map.of()))
                .thenReturn(List.of(acl("acl-abc", "vpc-123", false)));
        ObjectNode props = mapper.createObjectNode().put("VpcId", "vpc-123");

        provisioner.provision(r, props, ctx());

        // A second ACL here would orphan the first permanently: nothing else references it.
        verify(ec2, never()).createNetworkAcl(any(), any());
        assertEquals("acl-abc", r.getPhysicalId());
        assertEquals("acl-abc", r.getAttributes().get("Id"));
    }

    @Test
    void updateRecreatesAnAclRemovedOutOfBand() {
        StackResource r = resource("AWS::EC2::NetworkAcl", "Acl");
        r.setPhysicalId("acl-gone");
        when(ec2.describeNetworkAcls("us-east-1", List.of("acl-gone"), Map.of()))
                .thenReturn(List.of());
        when(ec2.createNetworkAcl("us-east-1", "vpc-123")).thenReturn(acl("acl-new", "vpc-123", false));
        ObjectNode props = mapper.createObjectNode().put("VpcId", "vpc-123");

        provisioner.provision(r, props, ctx());

        assertEquals("acl-new", r.getPhysicalId());
        assertEquals("acl-new", r.getAttributes().get("Id"));
    }

    @Test
    void changingVpcIdReportsAnUnsupportedReplacement() {
        StackResource r = resource("AWS::EC2::NetworkAcl", "Acl");
        r.setPhysicalId("acl-abc");
        when(ec2.describeNetworkAcls("us-east-1", List.of("acl-abc"), Map.of()))
                .thenReturn(List.of(acl("acl-abc", "vpc-123", false)));
        ObjectNode props = mapper.createObjectNode().put("VpcId", "vpc-456");

        // VpcId is createOnly. Reusing silently would leave the ACL on the original VPC.
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("ValidationError", e.getErrorCode());
        verify(ec2, never()).createNetworkAcl(any(), any());
    }

    @Test
    void subnetAssociationReplacesTheSubnetsLiveAssociation() {
        when(ec2.describeNetworkAcls(eq("us-east-1"), eq(List.of()), anyMap()))
                .thenReturn(List.of(acl("acl-default", "vpc-123", true,
                        assoc("aclassoc-1", "acl-default", "subnet-1"))));
        when(ec2.replaceNetworkAclAssociation("us-east-1", "aclassoc-1", "acl-abc"))
                .thenReturn(assoc("aclassoc-2", "acl-abc", "subnet-1"));
        StackResource r = resource("AWS::EC2::SubnetNetworkAclAssociation", "Assoc");
        ObjectNode props = mapper.createObjectNode()
                .put("SubnetId", "subnet-1")
                .put("NetworkAclId", "acl-abc");

        provisioner.provision(r, props, ctx());

        assertEquals("aclassoc-2", r.getPhysicalId());
        assertEquals("aclassoc-2", r.getAttributes().get("AssociationId"));
    }

    @Test
    void deleteNetworkAclDelegatesToService() {
        provisioner.delete("AWS::EC2::NetworkAcl", "acl-abc", "us-east-1");
        verify(ec2).deleteNetworkAcl("us-east-1", "acl-abc");
    }

    @Test
    void deleteAclEntryParsesThePhysicalId() {
        provisioner.delete("AWS::EC2::NetworkAclEntry", "acl-abc|150|egress", "us-east-1");
        verify(ec2).deleteNetworkAclEntry("us-east-1", "acl-abc", 150, true);
    }

    @Test
    void deleteSubnetAssociationRevertsToTheDefaultAcl() {
        when(ec2.describeNetworkAcls("us-east-1", List.of(),
                Map.of("association.network-acl-association-id", List.of("aclassoc-2"))))
                .thenReturn(List.of(acl("acl-abc", "vpc-123", false,
                        assoc("aclassoc-2", "acl-abc", "subnet-1"))));
        when(ec2.describeNetworkAcls("us-east-1", List.of(),
                Map.of("vpc-id", List.of("vpc-123"), "default", List.of("true"))))
                .thenReturn(List.of(acl("acl-default", "vpc-123", true)));

        provisioner.delete("AWS::EC2::SubnetNetworkAclAssociation", "aclassoc-2", "us-east-1");

        verify(ec2).replaceNetworkAclAssociation("us-east-1", "aclassoc-2", "acl-default");
    }

    @Test
    void deleteSubnetAssociationIsANoOpWhenAlreadyGone() {
        when(ec2.describeNetworkAcls(eq("us-east-1"), eq(List.of()), anyMap())).thenReturn(List.of());

        provisioner.delete("AWS::EC2::SubnetNetworkAclAssociation", "aclassoc-2", "us-east-1");

        verify(ec2, never()).replaceNetworkAclAssociation(any(), any(), any());
    }
}
