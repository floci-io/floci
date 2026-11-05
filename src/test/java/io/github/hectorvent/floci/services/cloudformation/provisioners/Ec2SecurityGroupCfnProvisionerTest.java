package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    void createPublishesGroupIdIdAndVpcId() throws Exception {
        when(ec2.createSecurityGroup("us-east-1", "web", "web tier", "vpc-1"))
                .thenReturn(group("sg-1", "web", "web tier", "vpc-1"));
        StackResource r = resource("WebSg");

        provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1"}
                """), ctx(null));

        assertEquals("sg-1", r.getPhysicalId());
        assertEquals(Set.of("GroupId", "Id", "VpcId", Ec2SecurityGroupCfnProvisioner.TAG_KEYS_ATTR),
                r.getAttributes().keySet());
        assertEquals("sg-1", r.getAttributes().get("GroupId"));
        assertEquals("sg-1", r.getAttributes().get("Id"));
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
    void updateWithoutGroupNameKeepsTheGeneratedGroupAndAddsItsRule() throws Exception {
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(group("sg-1", "my-stack-WebSg-0123456789ab", "web tier", "vpc-1")));
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-1");

        provisioner.provision(r, props("""
                {"GroupDescription": "web tier", "VpcId": "vpc-1",
                 "SecurityGroupIngress": [
                   {"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443, "CidrIp": "192.0.2.1/32"}
                 ]}
                """), ctx("sg-1", props("""
                {"GroupDescription": "web tier", "VpcId": "vpc-1"}
                """)));

        assertEquals("sg-1", r.getPhysicalId());
        assertEquals("sg-1", r.getAttributes().get("GroupId"));
        verify(ec2).authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-1"), anyList());
        verify(ec2, never()).createSecurityGroup(anyString(), anyString(), anyString(), any());
    }

    @Test
    void removingAnExplicitGroupNameStillCreatesAReplacement() throws Exception {
        when(ec2.createSecurityGroup(eq("us-east-1"), anyString(), eq("web tier"), eq("vpc-1")))
                .thenReturn(group("sg-new", "generated", "web tier", "vpc-1"));
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-old");

        provisioner.provision(r, props("""
                {"GroupDescription": "web tier", "VpcId": "vpc-1"}
                """), ctx("sg-old", props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1"}
                """)));

        assertEquals("sg-new", r.getPhysicalId());
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(ec2).createSecurityGroup(eq("us-east-1"), name.capture(), eq("web tier"), eq("vpc-1"));
        assertTrue(name.getValue().matches("my-stack-WebSg-[0-9a-f]{12}"), name.getValue());
    }

    @Test
    void changingAnUnnamedGroupsDescriptionStillCreatesAReplacement() throws Exception {
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-old"), List.of(), Map.of()))
                .thenReturn(List.of(group("sg-old", "my-stack-WebSg-0123456789ab", "old tier", "vpc-1")));
        when(ec2.createSecurityGroup(eq("us-east-1"), anyString(), eq("new tier"), eq("vpc-1")))
                .thenReturn(group("sg-new", "generated", "new tier", "vpc-1"));
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-old");

        provisioner.provision(r, props("""
                {"GroupDescription": "new tier", "VpcId": "vpc-1"}
                """), ctx("sg-old"));

        assertEquals("sg-new", r.getPhysicalId());
    }

    @Test
    void reservedUpdateTagsFailBeforeChangingRulesOrDeletingExistingTags() throws Exception {
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-1");
        r.getAttributes().put("GroupId", "sg-1");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1",
                 "SecurityGroupIngress": [{"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443,
                                           "CidrIp": "10.0.0.0/24"}],
                 "Tags": [{"Key": "aWs:reserved", "Value": "invalid"}]}
                """), ctx("sg-1")));

        assertEquals("InvalidParameterValue", failure.getErrorCode());
        assertEquals("sg-1", r.getPhysicalId());
        assertEquals(Map.of("GroupId", "sg-1"), r.getAttributes());
        verifyNoInteractions(ec2);
    }

    @Test
    void reservedCreateTagsFailBeforeAllocatingASecurityGroup() throws Exception {
        assertThrows(AwsException.class, () -> provisioner.provision(resource("WebSg"), props("""
                {"GroupDescription": "web tier", "Tags": [{"Key": "aws:reserved", "Value": "invalid"}]}
                """), ctx(null)));

        verifyNoInteractions(ec2);
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
    void updateRemovesOnlyTheTagKeysItOwns() throws Exception {
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(group("sg-1", "web", "web tier", "vpc-1")));
        // team was added through EC2, outside the stack, so AWS keeps it.
        when(ec2.describeTags(eq("us-east-1"), any())).thenReturn(List.of(
                tagEntry("sg-1", "env", "prod"), tagEntry("sg-1", "old", "stale"), tagEntry("sg-1", "team", "core")));
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-1");
        r.getAttributes().put(Ec2SecurityGroupCfnProvisioner.TAG_KEYS_ATTR, "[\"env\",\"old\"]");

        provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1",
                 "Tags": [{"Key": "env", "Value": "prod"}]}
                """), ctx("sg-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> removed = ArgumentCaptor.forClass(List.class);
        verify(ec2).deleteTags(eq("us-east-1"), eq(List.of("sg-1")), removed.capture());
        assertEquals(1, removed.getValue().size());
        assertEquals("old", removed.getValue().get(0).getKey());
        assertEquals("[\"env\"]", r.getAttributes().get(Ec2SecurityGroupCfnProvisioner.TAG_KEYS_ATTR));
    }

    @Test
    void aDeploymentWithoutTagsOwnsNoKeysEvenIfThePreviousTemplateNowResolvesSome() throws Exception {
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(group("sg-1", "web", "web tier", "vpc-1")));
        when(ec2.describeTags(eq("us-east-1"), any())).thenReturn(List.of(tagEntry("sg-1", "external", "x")));
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-1");
        String untagged = """
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1"}
                """;
        provisioner.provision(r, props(untagged), ctx("sg-1"));
        assertEquals("[]", r.getAttributes().get(Ec2SecurityGroupCfnProvisioner.TAG_KEYS_ATTR));

        // An Fn::If over the next update's conditions can make the previous Tags resolve to a key
        // that deployment never wrote; the recorded empty set keeps it from being removed.
        provisioner.provision(r, props(untagged), ctx("sg-1", props("""
                {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1",
                 "Tags": [{"Key": "external", "Value": "x"}]}
                """)));

        verify(ec2, never()).deleteTags(anyString(), anyList(), anyList());
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

    private static final String WEB_80_443 = """
            {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1",
             "SecurityGroupIngress": [
               {"IpProtocol": "tcp", "FromPort": 80, "ToPort": 80, "CidrIp": "0.0.0.0/0"},
               {"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443, "CidrIp": "0.0.0.0/0"}
             ],
             "Tags": [{"Key": "team", "Value": "core"}]}
            """;

    private static final String WEB_80_8080 = """
            {"GroupName": "web", "GroupDescription": "web tier", "VpcId": "vpc-1",
             "SecurityGroupIngress": [
               {"IpProtocol": "tcp", "FromPort": 80, "ToPort": 80, "CidrIp": "0.0.0.0/0"},
               {"IpProtocol": "tcp", "FromPort": 8080, "ToPort": 8080, "CidrIp": "0.0.0.0/0"}
             ],
             "Tags": [{"Key": "owner", "Value": "platform"}]}
            """;

    /** A group the first deployment created from {@link #WEB_80_443}, its rule and tag records in place. */
    private StackResource createdWeb() throws Exception {
        when(ec2.createSecurityGroup("us-east-1", "web", "web tier", "vpc-1"))
                .thenReturn(group("sg-1", "web", "web tier", "vpc-1"));
        StackResource r = resource("WebSg");
        provisioner.provision(r, props(WEB_80_443), ctx(null));
        r.setPhysicalId("sg-1");
        return r;
    }

    /** The group as the first deployment left it, with a port 22 rule a standalone resource added. */
    private static SecurityGroup webBeforeUpdate() {
        SecurityGroup group = group("sg-1", "web", "web tier", "vpc-1");
        group.getIpPermissions().add(tcp(80, "0.0.0.0/0"));
        group.getIpPermissions().add(tcp(443, "0.0.0.0/0"));
        group.getIpPermissions().add(tcp(22, "10.0.0.0/8"));
        return group;
    }

    @Test
    void aRolledBackUpdatePutsTheRulesTheTagsAndTheRecordsBack() throws Exception {
        StackResource r = createdWeb();
        Map<String, String> recordsBefore = new HashMap<>(r.getAttributes());
        SecurityGroup afterUpdate = group("sg-1", "web", "web tier", "vpc-1");
        afterUpdate.getIpPermissions().add(tcp(80, "0.0.0.0/0"));
        afterUpdate.getIpPermissions().add(tcp(8080, "0.0.0.0/0"));
        afterUpdate.getIpPermissions().add(tcp(22, "10.0.0.0/8"));
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(webBeforeUpdate()), List.of(afterUpdate));
        // external was added through EC2, outside the stack, so neither the update nor its rollback touches it.
        when(ec2.describeTags(eq("us-east-1"), any())).thenReturn(
                List.of(tagEntry("sg-1", "team", "core"), tagEntry("sg-1", "external", "x")),
                List.of(tagEntry("sg-1", "team", "core"), tagEntry("sg-1", "external", "x")),
                List.of(tagEntry("sg-1", "owner", "platform"), tagEntry("sg-1", "external", "x")));

        provisioner.provision(r, props(WEB_80_8080), ctx("sg-1"));
        verify(ec2).authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-1"), argThat(port(8080)));
        verify(ec2).revokeSecurityGroupIngress(eq("us-east-1"), eq("sg-1"), argThat(port(443)));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ec2).revokeSecurityGroupIngress(eq("us-east-1"), eq("sg-1"), argThat(port(8080)));
        // 443 the first time, from the create, and again from the rollback.
        verify(ec2, times(2)).authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-1"), argThat(port(443)));
        verify(ec2).deleteTags(eq("us-east-1"), eq(List.of("sg-1")), argThat(tags("owner")));
        verify(ec2, times(2)).createTags(eq("us-east-1"), eq(List.of("sg-1")), argThat(tags("team")));
        verify(ec2, never()).deleteTags(anyString(), anyList(), argThat(tags("external")));
        assertEquals(recordsBefore, r.getAttributes(), "the rule and tag records are the first deployment's again");
    }

    @Test
    void aRollbackDoesNotAuthorizeARuleTheGroupStillCarries() throws Exception {
        StackResource r = createdWeb();
        // The update's revoke never took effect, so 443 is still there when the rollback runs.
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(webBeforeUpdate()));
        when(ec2.describeTags(eq("us-east-1"), any())).thenReturn(List.of(tagEntry("sg-1", "team", "core")));
        provisioner.provision(r, props(WEB_80_8080), ctx("sg-1"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ec2, times(1)).authorizeSecurityGroupIngress(eq("us-east-1"), eq("sg-1"), argThat(port(443)));
    }

    @Test
    void aSourceAPermissionOutsideTheStackAlreadyHoldsIsNeitherAuthorizedNorRolledBack() throws Exception {
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-1");
        // One permission holding two sources, as an AuthorizeSecurityGroupIngress outside the stack
        // can leave it; the template declares one of them.
        SecurityGroup group = group("sg-1", "web", "web tier", "vpc-1");
        IpPermission external = tcp(80, "0.0.0.0/0");
        external.getIpRanges().add(new IpRange("10.0.0.0/8"));
        group.getIpPermissions().add(external);
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of())).thenReturn(List.of(group));

        provisioner.provision(r, props(WEB_80_8080), ctx("sg-1"));
        assertTrue(provisioner.rollbackUpdate(r));

        verify(ec2, never()).authorizeSecurityGroupIngress(anyString(), anyString(), argThat(port(80)));
        // A revoke of 80 would take 0.0.0.0/0 out of the permission that held it before the update.
        verify(ec2, never()).revokeSecurityGroupIngress(anyString(), anyString(), argThat(port(80)));
        verify(ec2).revokeSecurityGroupIngress(eq("us-east-1"), eq("sg-1"), argThat(port(8080)));
    }

    @Test
    void aRollbackPutsBackATagThatHadNoValue() throws Exception {
        StackResource r = resource("WebSg");
        r.setPhysicalId("sg-1");
        r.getAttributes().put(Ec2SecurityGroupCfnProvisioner.TAG_KEYS_ATTR, "[\"owner\"]");
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(group("sg-1", "web", "web tier", "vpc-1")));
        when(ec2.describeTags(eq("us-east-1"), any())).thenReturn(
                List.of(tagEntry("sg-1", "owner", null)),
                List.of(tagEntry("sg-1", "owner", null)),
                List.of(tagEntry("sg-1", "owner", "platform")));

        provisioner.provision(r, props(WEB_80_8080), ctx("sg-1"));
        assertTrue(provisioner.rollbackUpdate(r));

        verify(ec2).createTags(eq("us-east-1"), eq(List.of("sg-1")), argThat(actual -> actual.size() == 1
                && "owner".equals(actual.getFirst().getKey()) && actual.getFirst().getValue() == null));
    }

    @Test
    void aFailedStepPutsAReusedGroupBackBeforeTheFailurePropagates() throws Exception {
        StackResource r = createdWeb();
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(webBeforeUpdate()));
        when(ec2.describeTags(eq("us-east-1"), any())).thenReturn(List.of(tagEntry("sg-1", "team", "core")));
        doThrow(new AwsException("InternalError", "ec2 is down", 500))
                .when(ec2).createTags(eq("us-east-1"), eq(List.of("sg-1")), argThat(tags("owner")));

        assertThrows(AwsException.class, () -> provisioner.provision(r, props(WEB_80_8080), ctx("sg-1")));

        verify(ec2).revokeSecurityGroupIngress(eq("us-east-1"), eq("sg-1"), argThat(port(8080)));
        assertFalse(r.getAttributes().containsKey(Ec2SecurityGroupCfnProvisioner.UPDATE_SNAPSHOT_ATTR));
        verify(ec2, never()).deleteSecurityGroup(anyString(), anyString());
    }

    @Test
    void aFailedTagWriteOnANewGroupDeletesIt() throws Exception {
        when(ec2.createSecurityGroup("us-east-1", "web", "web tier", "vpc-1"))
                .thenReturn(group("sg-1", "web", "web tier", "vpc-1"));
        doThrow(new AwsException("InternalError", "ec2 is down", 500))
                .when(ec2).createTags(anyString(), anyList(), anyList());

        assertThrows(AwsException.class, () -> provisioner.provision(resource("WebSg"), props(WEB_80_443), ctx(null)));

        verify(ec2).deleteSecurityGroup("us-east-1", "sg-1");
    }

    @Test
    void aReplacedGroupIsNotReportedAsRolledBack() throws Exception {
        StackResource r = createdWeb();
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(webBeforeUpdate()));
        when(ec2.createSecurityGroup("us-east-1", "web", "new description", "vpc-1"))
                .thenReturn(group("sg-2", "web", "new description", "vpc-1"));

        provisioner.provision(r, props("""
                {"GroupName": "web", "GroupDescription": "new description", "VpcId": "vpc-1"}
                """), ctx("sg-1"));

        assertEquals("sg-2", r.getPhysicalId());
        assertFalse(provisioner.rollbackUpdate(r), "a replacement is not rolled back here");
    }

    @Test
    void aCommittedOrClearedUpdateDropsTheSnapshot() throws Exception {
        StackResource r = createdWeb();
        when(ec2.describeSecurityGroups("us-east-1", List.of("sg-1"), List.of(), Map.of()))
                .thenReturn(List.of(webBeforeUpdate()));
        provisioner.provision(r, props(WEB_80_8080), ctx("sg-1"));
        assertTrue(r.getAttributes().containsKey(Ec2SecurityGroupCfnProvisioner.UPDATE_SNAPSHOT_ATTR));

        provisioner.completeUpdate(r);
        assertFalse(r.getAttributes().containsKey(Ec2SecurityGroupCfnProvisioner.UPDATE_SNAPSHOT_ATTR));

        provisioner.provision(r, props(WEB_80_443), ctx("sg-1"));
        provisioner.clearUpdate(r);
        assertFalse(r.getAttributes().containsKey(Ec2SecurityGroupCfnProvisioner.UPDATE_SNAPSHOT_ATTR));
    }

    private static ArgumentMatcher<List<IpPermission>> port(int port) {
        return perms -> perms != null && perms.size() == 1 && perms.getFirst().getFromPort() == port;
    }

    private static ArgumentMatcher<List<Tag>> tags(String... keys) {
        return actual -> actual != null
                && actual.stream().map(Tag::getKey).collect(Collectors.toSet()).equals(Set.of(keys));
    }

    private static Map<String, String> tagEntry(String resourceId, String key, String value) {
        Map<String, String> entry = new HashMap<>();
        entry.put("resourceId", resourceId);
        entry.put("key", key);
        entry.put("value", value);
        return entry;
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        return ctx(priorPhysicalId, null);
    }

    private ProvisionContext ctx(String priorPhysicalId, JsonNode priorProperties) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId,
                event -> {}, priorProperties);
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
