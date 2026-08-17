package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The standalone security-group rule provisioner in isolation: property mapping, the
 * revoke-before-authorize that keeps UpdateStack from duplicating rules, and the delete that keeps a
 * rule from outliving its stack on a group that survives it (issue #1992).
 */
class Ec2SecurityGroupRuleCfnProvisionerTest {

    private static final String INGRESS = "AWS::EC2::SecurityGroupIngress";
    private static final String EGRESS = "AWS::EC2::SecurityGroupEgress";

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2SecurityGroupRuleCfnProvisioner provisioner = new Ec2SecurityGroupRuleCfnProvisioner(ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
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

    private SecurityGroupRule rule(String id) {
        SecurityGroupRule rule = new SecurityGroupRule();
        rule.setSecurityGroupRuleId(id);
        return rule;
    }

    @SuppressWarnings("unchecked")
    private IpPermission authorizedIngress() {
        ArgumentCaptor<List<IpPermission>> perms = ArgumentCaptor.forClass(List.class);
        verify(ec2).authorizeSecurityGroupIngress(eq("us-east-1"), anyString(), perms.capture());
        return perms.getValue().get(0);
    }

    @Test
    void ingressAuthorizesCidrRuleAndExposesRuleId() {
        when(ec2.authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-123"), anyList()))
                .thenReturn(List.of(rule("sgr-abc")));
        StackResource r = resource(INGRESS, "WebIngress");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-123")
                .put("IpProtocol", "tcp")
                .put("FromPort", 443)
                .put("ToPort", 443)
                .put("CidrIp", "10.0.0.0/16")
                .put("Description", "https from vpc");

        provisioner.provision(r, props, ctx());

        assertEquals("sgr-abc", r.getPhysicalId());
        assertEquals("sgr-abc", r.getAttributes().get("Id"));
        IpPermission perm = authorizedIngress();
        assertEquals("tcp", perm.getIpProtocol());
        assertEquals(443, perm.getFromPort().intValue());
        assertEquals(443, perm.getToPort().intValue());
        assertEquals("10.0.0.0/16", perm.getIpRanges().get(0).getCidrIp());
        assertEquals("https from vpc", perm.getIpRanges().get(0).getDescription());
        verify(ec2, never()).authorizeSecurityGroupEgress(anyString(), anyString(), anyList());
    }

    @Test
    void egressGoesToTheEgressApi() {
        when(ec2.authorizeSecurityGroupEgress(eq("us-east-1"), eq("sg-123"), anyList()))
                .thenReturn(List.of(rule("sgr-egr")));
        StackResource r = resource(EGRESS, "WebEgress");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-123")
                .put("IpProtocol", "tcp")
                .put("FromPort", 80)
                .put("ToPort", 80)
                .put("CidrIp", "0.0.0.0/0");

        provisioner.provision(r, props, ctx());

        assertEquals("sgr-egr", r.getPhysicalId());
        verify(ec2).authorizeSecurityGroupEgress(eq("us-east-1"), eq("sg-123"), anyList());
        verify(ec2, never()).authorizeSecurityGroupIngress(anyString(), anyString(), anyList());
    }

    @Test
    void ipv6RuleKeepsItsDescription() {
        when(ec2.authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-123"), anyList()))
                .thenReturn(List.of(rule("sgr-v6")));
        StackResource r = resource(INGRESS, "V6Ingress");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-123")
                .put("IpProtocol", "tcp")
                .put("FromPort", 22)
                .put("ToPort", 22)
                .put("CidrIpv6", "::/0")
                .put("Description", "ssh over v6");

        provisioner.provision(r, props, ctx());

        IpPermission perm = authorizedIngress();
        assertEquals("::/0", perm.getIpv6Ranges().get(0).getCidrIpv6());
        assertEquals("ssh over v6", perm.getIpv6Ranges().get(0).getDescription());
        assertTrue(perm.getIpRanges().isEmpty());
    }

    @Test
    void sourceSecurityGroupBecomesAPeerPair() {
        when(ec2.authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-app"), anyList()))
                .thenReturn(List.of(rule("sgr-peer")));
        StackResource r = resource(INGRESS, "AppFromWeb");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-app")
                .put("IpProtocol", "tcp")
                .put("FromPort", 8080)
                .put("ToPort", 8080)
                .put("SourceSecurityGroupId", "sg-web")
                .put("Description", "app from web");

        provisioner.provision(r, props, ctx());

        IpPermission perm = authorizedIngress();
        assertEquals("sg-web", perm.getUserIdGroupPairs().get(0).getGroupId());
        // Ec2Service reads the description off the pair, so dropping it here loses the
        // template's text from DescribeSecurityGroupRules, as it would for a cidr peer.
        assertEquals("app from web", perm.getUserIdGroupPairs().get(0).getDescription());
    }

    @Test
    @SuppressWarnings("unchecked")
    void destinationSecurityGroupBecomesAPeerPair() {
        when(ec2.authorizeSecurityGroupEgress(eq("us-east-1"), eq("sg-web"), anyList()))
                .thenReturn(List.of(rule("sgr-peer")));
        StackResource r = resource(EGRESS, "WebToApp");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-web")
                .put("IpProtocol", "tcp")
                .put("FromPort", 8080)
                .put("ToPort", 8080)
                .put("DestinationSecurityGroupId", "sg-app");

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<List<IpPermission>> perms = ArgumentCaptor.forClass(List.class);
        verify(ec2).authorizeSecurityGroupEgress(eq("us-east-1"), eq("sg-web"), perms.capture());
        assertEquals("sg-app", perms.getValue().get(0).getUserIdGroupPairs().get(0).getGroupId());
    }

    @Test
    void missingProtocolDefaultsToAll() {
        when(ec2.authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-123"), anyList()))
                .thenReturn(List.of(rule("sgr-all")));
        StackResource r = resource(INGRESS, "AllIngress");
        ObjectNode props = mapper.createObjectNode().put("GroupId", "sg-123").put("CidrIp", "10.0.0.0/8");

        provisioner.provision(r, props, ctx());

        IpPermission perm = authorizedIngress();
        assertEquals("-1", perm.getIpProtocol());
        assertNull(perm.getFromPort());
        assertNull(perm.getToPort());
    }

    @Test
    void groupNameResolvesToTheGroupId() {
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-by-name");
        sg.setGroupName("legacy-group");
        when(ec2.describeSecurityGroups(eq("us-east-1"), eq(List.of()), eq(List.of("legacy-group")), any()))
                .thenReturn(List.of(sg));
        when(ec2.authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-by-name"), anyList()))
                .thenReturn(List.of(rule("sgr-named")));
        StackResource r = resource(INGRESS, "NamedIngress");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupName", "legacy-group")
                .put("IpProtocol", "tcp")
                .put("FromPort", 25)
                .put("ToPort", 25)
                .put("CidrIp", "10.0.0.0/8");

        provisioner.provision(r, props, ctx());

        assertEquals("sgr-named", r.getPhysicalId());
        verify(ec2).authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-by-name"), anyList());
    }

    @Test
    void updateAuthorizesTheNewRuleBeforeRevokingThePrevious() {
        when(ec2.authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-123"), anyList()))
                .thenReturn(List.of(rule("sgr-new")));
        StackResource r = resource(INGRESS, "WebIngress");
        r.setPhysicalId("sgr-old"); // what the previous stack execution created
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-123")
                .put("IpProtocol", "tcp")
                .put("FromPort", 443)
                .put("ToPort", 443)
                .put("CidrIp", "10.0.0.0/16");

        provisioner.provision(r, props, ctx());

        var order = inOrder(ec2);
        order.verify(ec2).authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-123"), anyList());
        order.verify(ec2).deleteSecurityGroupRule("us-east-1", "sgr-old");
        assertEquals("sgr-new", r.getPhysicalId());
    }

    @Test
    void aFailedUpdateLeavesThePreviousRuleInPlace() {
        // Revoking first would leave the group with neither rule, and rollback keeps the old
        // physical id without recreating its permission.
        when(ec2.authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-123"), anyList()))
                .thenThrow(new AwsException("InvalidPermission.Malformed", "bad rule", 400));
        StackResource r = resource(INGRESS, "WebIngress");
        r.setPhysicalId("sgr-old");
        ObjectNode props = mapper.createObjectNode().put("GroupId", "sg-123").put("CidrIp", "10.0.0.0/16");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        verify(ec2, never()).deleteSecurityGroupRule(anyString(), anyString());
        assertEquals("sgr-old", r.getPhysicalId());
    }

    @Test
    void createDoesNotRevokeAnything() {
        when(ec2.authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-123"), anyList()))
                .thenReturn(List.of(rule("sgr-new")));
        StackResource r = resource(INGRESS, "WebIngress");
        ObjectNode props = mapper.createObjectNode().put("GroupId", "sg-123").put("CidrIp", "10.0.0.0/8");

        provisioner.provision(r, props, ctx());

        verify(ec2, never()).deleteSecurityGroupRule(anyString(), anyString());
    }

    @Test
    void deleteRevokesTheRule() {
        provisioner.delete(INGRESS, "sgr-abc", "us-east-1");
        verify(ec2).deleteSecurityGroupRule("us-east-1", "sgr-abc");
    }

    @Test
    void deleteOfANonRuleIdIsANoOp() {
        // The logical-id fallback, or a null id on a resource that never got provisioned.
        provisioner.delete(EGRESS, "WebEgress", "us-east-1");
        provisioner.delete(EGRESS, null, "us-east-1");
        verifyNoInteractions(ec2);
    }

    @Test
    void deletePropagatesServiceFailures() {
        // deleteSecurityGroupRule already absorbs an unrecorded rule and an absent group, so a
        // throw is a real failure. Swallowing it reported the resource deleted while its permission
        // was still on the group.
        doThrow(new AwsException("InternalFailure", "storage down", 500))
                .when(ec2).deleteSecurityGroupRule("us-east-1", "sgr-abc");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(INGRESS, "sgr-abc", "us-east-1"));

        assertEquals("InternalFailure", failure.getErrorCode());
    }

    @Test
    void severalPeerPropertiesAreRejected() {
        // "You must specify exactly one of the following sources." Naming several authorized a rule
        // record per peer while only the first id was kept as the physical id, so delete revoked one
        // of them and the rest stayed on the group.
        StackResource r = resource(INGRESS, "WebIngress");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-123")
                .put("IpProtocol", "tcp")
                .put("CidrIp", "10.0.0.0/16")
                .put("CidrIpv6", "::/0");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(ec2, never()).authorizeSecurityGroupIngress(anyString(), anyString(), anyList());
    }

    @Test
    void aPeerNamedByGroupNameCountsAsASource() {
        // The schema lists SourceSecurityGroupName beside the id form. Counting only the id meant
        // a template naming its peer by name was rejected for naming no source.
        when(ec2.authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-app"), anyList()))
                .thenReturn(List.of(rule("sgr-byname")));
        StackResource r = resource(INGRESS, "AppFromWeb");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-app")
                .put("IpProtocol", "tcp")
                .put("FromPort", 8080)
                .put("ToPort", 8080)
                .put("SourceSecurityGroupName", "web-sg")
                .put("SourceSecurityGroupOwnerId", "111111111111");

        provisioner.provision(r, props, ctx());

        // Handed over as a name. Ec2Service resolves it to an id on authorize, before it records
        // the rule, so delete still matches on the resolved id.
        UserIdGroupPair pair = authorizedIngress().getUserIdGroupPairs().get(0);
        assertEquals("web-sg", pair.getGroupName());
        assertNull(pair.getGroupId());
        assertEquals("111111111111", pair.getUserId());
    }

    @Test
    void aPeerNamedByBothIdAndNameStillCountsAsOneSource() {
        when(ec2.authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-app"), anyList()))
                .thenReturn(List.of(rule("sgr-both")));
        StackResource r = resource(INGRESS, "AppFromWeb");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-app")
                .put("IpProtocol", "tcp")
                .put("SourceSecurityGroupId", "sg-web")
                .put("SourceSecurityGroupName", "web-sg");

        provisioner.provision(r, props, ctx());

        // One peer named two ways is still one source, and the id wins.
        UserIdGroupPair pair = authorizedIngress().getUserIdGroupPairs().get(0);
        assertEquals("sg-web", pair.getGroupId());
        assertNull(pair.getGroupName());
    }

    @Test
    void aRuleNamingNoSourceAtAllIsRejected() {
        // Exactly one, so none is as invalid as several. Accepting zero sent an IpPermission
        // naming no peer, which the stack then reported as successfully provisioned.
        StackResource r = resource(INGRESS, "WebIngress");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-123")
                .put("IpProtocol", "tcp")
                .put("FromPort", 443)
                .put("ToPort", 443);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(ec2, never()).authorizeSecurityGroupIngress(anyString(), anyString(), anyList());
    }

    @Test
    void aCidrAndAPeerGroupTogetherAreRejected() {
        StackResource r = resource(EGRESS, "WebEgress");
        ObjectNode props = mapper.createObjectNode()
                .put("GroupId", "sg-123")
                .put("IpProtocol", "tcp")
                .put("CidrIp", "10.0.0.0/16")
                .put("DestinationSecurityGroupId", "sg-peer");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(ec2, never()).authorizeSecurityGroupEgress(anyString(), anyString(), anyList());
    }

    @Test
    void unknownResourceTypeIsRejected() {
        StackResource r = resource("AWS::EC2::SecurityGroup", "Sg");
        assertThrows(IllegalStateException.class,
                () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
    }

    @Test
    void registryRoutesBothStandaloneTypes() {
        CloudFormationResourceRegistry registry = new CloudFormationResourceRegistry(List.of(provisioner));
        assertEquals(provisioner, registry.forType(INGRESS).orElseThrow());
        assertEquals(provisioner, registry.forType(EGRESS).orElseThrow());
        assertTrue(registry.forType("AWS::EC2::SecurityGroup").isEmpty());
    }
}
