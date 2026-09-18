package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.github.hectorvent.floci.core.common.AwsException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;

/**
 * {@code AWS::EC2::SecurityGroup} in isolation: the attributes Ref and Fn::GetAtt read, the
 * generated name, reuse of the prior group on update, and the duplicate check that keeps an update
 * from stacking another copy of every inline rule.
 */
class Ec2SecurityGroupCfnProvisionerTest {

    private static final String TYPE = "AWS::EC2::SecurityGroup";

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2SecurityGroupCfnProvisioner provisioner = new Ec2SecurityGroupCfnProvisioner(ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createPublishesGroupIdAndVpcIdOnly() throws Exception {
        when(ec2.createSecurityGroup("us-east-1", "web", "web tier", "vpc-1"))
                .thenReturn(group("sg-1", "web", "web tier", "vpc-1"));
        StackResource r = resource("WebSg");

        provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1"}
                """), ctx(null));

        assertEquals("sg-1", r.getPhysicalId());
        assertEquals(Set.of("GroupId", "VpcId"), r.getAttributes().keySet());
        assertEquals("sg-1", r.getAttributes().get("GroupId"));
        assertEquals("vpc-1", r.getAttributes().get("VpcId"));
    }

    @Test
    void absentGroupNameIsGeneratedFromStackAndLogicalId() throws Exception {
        when(ec2.createSecurityGroup(eq("us-east-1"), anyString(), anyString(), any()))
                .thenReturn(group("sg-2", "generated", "Managed by CloudFormation", "vpc-default"));
        StackResource r = resource("WebSg");

        provisioner.provision(r, props("{}"), ctx(null));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(ec2).createSecurityGroup(eq("us-east-1"), name.capture(), eq("Managed by CloudFormation"), any());
        assertTrue(name.getValue().matches("my-stack-WebSg-[0-9a-f]{12}"), name.getValue());
    }

    @Test
    void updateReusesThePriorGroupInsteadOfCreatingAnother() throws Exception {
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(group("sg-1", "web", "web tier", "vpc-1")));
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-1");

        provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1"}
                """), ctx("sg-1"));

        assertEquals("sg-1", r.getPhysicalId());
        verify(ec2, never()).createSecurityGroup(anyString(), anyString(), anyString(), any());
    }

    @Test
    void inlineRuleTheGroupAlreadyCarriesIsNotAuthorizedAgain() throws Exception {
        SecurityGroup existing = group("sg-1", "web", "web tier", "vpc-1");
        existing.getIpPermissions().add(tcp(80, "0.0.0.0/0"));
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(existing));
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-1");

        provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1",
                 "SecurityGroupIngress": [
                   {"IpProtocol": "tcp", "FromPort": 80, "ToPort": 80, "CidrIp": "0.0.0.0/0"},
                   {"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443, "CidrIp": "0.0.0.0/0"}
                 ]}
                """), ctx("sg-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IpPermission>> authorized = ArgumentCaptor.forClass(List.class);
        verify(ec2).authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-1"), authorized.capture());
        assertEquals(1, authorized.getAllValues().size());
        assertEquals(443, authorized.getValue().get(0).getFromPort());
        verify(ec2, never()).authorizeSecurityGroupEgress(anyString(), anyString(), anyList());
    }

    @Test
    void deleteDeletesTheGroup() {
        provisioner.delete(TYPE, "sg-1", "us-east-1");

        verify(ec2).deleteSecurityGroup("us-east-1", "sg-1");
    }

    @Test
    void deleteToleratesAGroupAlreadyGone() {
        doThrow(new AwsException("InvalidGroup.NotFound", "gone", 400))
                .when(ec2).deleteSecurityGroup("us-east-1", "sg-1");

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "sg-1", "us-east-1"));
    }

    @Test
    void deletePropagatesAnUnexpectedError() {
        doThrow(new AwsException("DependencyViolation", "in use", 400))
                .when(ec2).deleteSecurityGroup("us-east-1", "sg-1");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(TYPE, "sg-1", "us-east-1"));
        assertEquals("DependencyViolation", failure.getErrorCode());
    }

    @Test
    void createTagsTheGroup() throws Exception {
        when(ec2.createSecurityGroup("us-east-1", "web", "web tier", "vpc-1"))
                .thenReturn(group("sg-1", "web", "web tier", "vpc-1"));
        StackResource r = resource("WebSg");

        provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1",
                 "Tags": [{"Key": "env", "Value": "prod"}, {"Key": "team", "Value": "web"}]}
                """), ctx(null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> created = ArgumentCaptor.forClass(List.class);
        verify(ec2).createTags(eq("us-east-1"), eq(List.of("sg-1")), created.capture());
        Map<String, String> tags = new HashMap<>();
        for (Tag t : created.getValue()) {
            tags.put(t.getKey(), t.getValue());
        }
        assertEquals(Map.of("env", "prod", "team", "web"), tags);
        verify(ec2, never()).deleteTags(anyString(), anyList(), anyList());
    }

    @Test
    void updateRemovesAndReconcilesTags() throws Exception {
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(group("sg-1", "web", "web tier", "vpc-1")));
        when(ec2.describeTags(eq("us-east-1"), any())).thenReturn(List.of(
                tagEntry("sg-1", "env", "prod"), tagEntry("sg-1", "old", "stale")));
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-1");

        provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1",
                 "Tags": [{"Key": "env", "Value": "prod"}]}
                """), ctx("sg-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> removed = ArgumentCaptor.forClass(List.class);
        verify(ec2).deleteTags(eq("us-east-1"), eq(List.of("sg-1")), removed.capture());
        assertEquals(1, removed.getValue().size());
        assertEquals("old", removed.getValue().get(0).getKey());
    }

    @Test
    void updateRevokesOnlyRulesThisResourceAuthorized() throws Exception {
        // First provision authorizes ports 80 and 443 and records them on the resource.
        SecurityGroup created = group("sg-1", "web", "web tier", "vpc-1");
        when(ec2.createSecurityGroup("us-east-1", "web", "web tier", "vpc-1")).thenReturn(created);
        StackResource r = resource("WebSg");
        provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1",
                 "SecurityGroupIngress": [
                   {"IpProtocol": "tcp", "FromPort": 80, "ToPort": 80, "CidrIp": "0.0.0.0/0"},
                   {"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443, "CidrIp": "0.0.0.0/0"}
                 ]}
                """), ctx(null));

        // The group now carries 80, 443, and a rule a standalone resource added (port 22) that
        // this provisioner never authorized.
        SecurityGroup existing = group("sg-1", "web", "web tier", "vpc-1");
        existing.getIpPermissions().add(tcp(80, "0.0.0.0/0"));
        existing.getIpPermissions().add(tcp(443, "0.0.0.0/0"));
        existing.getIpPermissions().add(tcp(22, "10.0.0.0/8"));
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(existing));
        r.setPhysicalId("sg-1");

        // Second provision drops 443 from the template.
        provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1",
                 "SecurityGroupIngress": [
                   {"IpProtocol": "tcp", "FromPort": 80, "ToPort": 80, "CidrIp": "0.0.0.0/0"}
                 ]}
                """), ctx("sg-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IpPermission>> revoked = ArgumentCaptor.forClass(List.class);
        verify(ec2).revokeSecurityGroupIngress(eq("us-east-1"), eq("sg-1"), revoked.capture());
        assertEquals(1, revoked.getAllValues().size());
        assertEquals(443, revoked.getValue().get(0).getFromPort());
    }

    private static Map<String, String> tagEntry(String resourceId, String key, String value) {
        Map<String, String> entry = new HashMap<>();
        entry.put("resourceId", resourceId);
        entry.put("key", key);
        entry.put("value", value);
        return entry;
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource(String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static SecurityGroup group(String id, String name, String description, String vpcId) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId(id);
        group.setGroupName(name);
        group.setDescription(description);
        group.setVpcId(vpcId);
        group.setIpPermissions(new ArrayList<>());
        group.setIpPermissionsEgress(new ArrayList<>());
        return group;
    }

    private static IpPermission tcp(int port, String cidr) {
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(port);
        permission.setToPort(port);
        permission.setIpRanges(new ArrayList<>(List.of(new IpRange(cidr))));
        return permission;
    }
}
