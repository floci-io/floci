package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcCidrBlockAssociation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::EC2::VPC}. */
class Ec2VpcCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";

    private Ec2Service ec2;
    private Ec2VpcCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        provisioner = new Ec2VpcCfnProvisioner(ec2);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(ec2.describeSecurityGroups(anyString(), any(), any(), any())).thenReturn(List.of());
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Vpc vpc(String id, String cidr) {
        Vpc v = new Vpc();
        v.setVpcId(id);
        v.setCidrBlock(cidr);
        return v;
    }

    /** Matches a tag list by its {@code key=value} pairs as a set; {@link Tag} has no equals. */
    private static ArgumentMatcher<List<Tag>> tags(String... expected) {
        return actual -> actual != null
                && actual.stream().map(t -> t.getKey() + "=" + t.getValue()).collect(Collectors.toSet())
                        .equals(Set.of(expected));
    }

    private void stubCreate(Vpc created) {
        when(ec2.createVpc(anyString(), anyString(), anyBoolean(), anyBoolean(), anyString())).thenReturn(created);
    }

    /**
     * Provisions the way the engine does: an update arrives with the prior id on both the context
     * and the resource, so a provisioner reading the wrong one still looks correct here. The
     * create-path cases below are what separate them.
     */
    private StackResource provision(String priorPhysicalId, String json) {
        return provision(priorPhysicalId, priorPhysicalId, json);
    }

    private StackResource provision(String contextPriorId, String resourcePhysicalId, String json) {
        return provision(contextPriorId, resourcePhysicalId, Map.of(), json);
    }

    private StackResource provision(String priorPhysicalId, Map<String, String> priorAttributes, String json) {
        return provision(priorPhysicalId, priorPhysicalId, priorAttributes, json);
    }

    private StackResource provision(String contextPriorId, String resourcePhysicalId, Map<String, String> priorAttributes, String json) {
        StackResource r = new StackResource();
        r.setLogicalId("Vpc");
        r.setResourceType("AWS::EC2::VPC");
        r.setPhysicalId(resourcePhysicalId);
        r.getAttributes().putAll(priorAttributes);
        ProvisionContext ctx =
                new ProvisionContext(engine, REGION, "000000000000", "my-stack", contextPriorId);
        provisioner.provision(r, props(json), ctx);
        return r;
    }

    @Test
    void refIsTheVpcIdAndGetAttExposesTheDocumentedAttributes() {
        Vpc created = vpc("vpc-new", "10.0.0.0/16");
        created.getCidrBlockAssociationSet().add(new VpcCidrBlockAssociation("vpc-cidr-assoc-1", "10.0.0.0/16"));
        stubCreate(created);
        SecurityGroup def = new SecurityGroup();
        def.setGroupId("sg-default");
        def.setVpcId("vpc-new");
        when(ec2.describeSecurityGroups(REGION, List.of(), List.of("default"), Map.of()))
                .thenReturn(List.of(def));
        NetworkAcl acl = new NetworkAcl();
        acl.setNetworkAclId("acl-default");
        acl.setVpcId("vpc-new");
        acl.setDefault(true);
        when(ec2.describeNetworkAcls(REGION, List.of(), Map.of("vpc-id", List.of("vpc-new"), "default", List.of("true"))))
                .thenReturn(List.of(acl));

        StackResource r = provision(null, """
                {"CidrBlock": "10.0.0.0/16"}""");

        assertEquals("vpc-new", r.getPhysicalId());
        assertEquals("vpc-new", r.getAttributes().get("VpcId"));
        assertEquals("10.0.0.0/16", r.getAttributes().get("CidrBlock"));
        assertEquals("vpc-cidr-assoc-1", r.getAttributes().get("CidrBlockAssociations"));
        assertEquals("sg-default", r.getAttributes().get("DefaultSecurityGroup"));
        assertEquals("acl-default", r.getAttributes().get("DefaultNetworkAcl"));
        assertEquals("", r.getAttributes().get("Ipv6CidrBlocks"));
    }

    @Test
    void omittedDnsPropertiesApplyTheAwsDefaults() {
        stubCreate(vpc("vpc-new", "10.0.0.0/16"));

        provision(null, """
                {"CidrBlock": "10.0.0.0/16"}""");

        verify(ec2).createVpc(REGION, "10.0.0.0/16", false, false, "default");
        verify(ec2).modifyVpcAttribute(REGION, "vpc-new", "enableDnsSupport", "true");
        verify(ec2).modifyVpcAttribute(REGION, "vpc-new", "enableDnsHostnames", "false");
    }

    @Test
    void dnsPropertiesResolvingBlankApplyTheAwsDefaults() {
        // Fn::If with AWS::NoValue renders as "" through the engine; Boolean.parseBoolean("") would
        // otherwise silently switch DnsSupport off.
        stubCreate(vpc("vpc-new", "10.0.0.0/16"));

        provision(null, """
                {"CidrBlock": "10.0.0.0/16", "EnableDnsSupport": "", "EnableDnsHostnames": ""}""");

        verify(ec2).modifyVpcAttribute(REGION, "vpc-new", "enableDnsSupport", "true");
        verify(ec2).modifyVpcAttribute(REGION, "vpc-new", "enableDnsHostnames", "false");
    }

    @Test
    void tagsDnsSettingsAndTenancyFromTheTemplateAreApplied() {
        stubCreate(vpc("vpc-new", "10.0.0.0/16"));

        provision(null, """
                {"CidrBlock": "10.0.0.0/16", "EnableDnsHostnames": true, "EnableDnsSupport": "true",
                 "InstanceTenancy": "dedicated",
                 "Tags": [{"Key": "Name", "Value": "my-vpc"}, {"Key": "env", "Value": "dev"}]}""");

        verify(ec2).createVpc(REGION, "10.0.0.0/16", false, false, "dedicated");
        verify(ec2).modifyVpcAttribute(REGION, "vpc-new", "enableDnsHostnames", "true");
        verify(ec2).modifyVpcAttribute(REGION, "vpc-new", "enableDnsSupport", "true");
        verify(ec2).createTags(eq(REGION), eq(List.of("vpc-new")), argThat(tags("Name=my-vpc", "env=dev")));
    }

    @Test
    void anUnchangedVpcIsReusedAndItsTagSetReplaced() {
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of()))
                .thenReturn(List.of(vpc("vpc-existing", "10.0.0.0/16")));
        when(ec2.describeTags(REGION, Map.of("resource-id", List.of("vpc-existing")))).thenReturn(List.of(
                Map.of("key", "Name", "value", "old"),
                Map.of("key", "stale", "value", "x")));

        StackResource r = provision("vpc-existing", """
                {"CidrBlock": "10.0.0.0/16", "Tags": [{"Key": "Name", "Value": "new"}]}""");

        verify(ec2, never()).createVpc(anyString(), anyString(), anyBoolean(), anyBoolean(), any());
        verify(ec2, never()).modifyVpcTenancy(anyString(), anyString(), anyString());
        assertEquals("vpc-existing", r.getPhysicalId(),
                "reusing must keep the id every subnet and route table already references");
        verify(ec2).deleteTags(eq(REGION), eq(List.of("vpc-existing")), argThat(tags("stale=x")));
        verify(ec2).createTags(eq(REGION), eq(List.of("vpc-existing")), argThat(tags("Name=new")));
    }

    @Test
    void anUnchangedVpcWhoseTemplateCidrHasHostBitsIsReused() {
        // The backend stores the canonical block, so the template's spelling must be compared in
        // that form or every update would replace the VPC.
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of()))
                .thenReturn(List.of(vpc("vpc-existing", "10.0.0.0/16")));

        StackResource r = provision("vpc-existing", """
                {"CidrBlock": "10.0.0.5/16"}""");

        verify(ec2, never()).createVpc(anyString(), anyString(), anyBoolean());
        assertEquals("vpc-existing", r.getPhysicalId());
    }

    @Test
    void aVpcSavedWithHostBitsBeforeCanonicalizationIsReused() {
        // State persisted by an older build can still hold the template's spelling, host bits and all.
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of()))
                .thenReturn(List.of(vpc("vpc-existing", "10.0.0.5/16")));

        StackResource r = provision("vpc-existing", """
                {"CidrBlock": "10.0.0.5/16"}""");

        verify(ec2, never()).createVpc(anyString(), anyString(), anyBoolean());
        assertEquals("vpc-existing", r.getPhysicalId());
    }

    @Test
    void aChangedCidrBlockCreatesAReplacementAndTheDisplacedVpcIsDeletedOnceTheUpdateCommits() {
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of()))
                .thenReturn(List.of(vpc("vpc-existing", "10.0.0.0/16")));
        stubCreate(vpc("vpc-replaced", "10.1.0.0/16"));

        StackResource r = provision("vpc-existing", """
                {"CidrBlock": "10.1.0.0/16"}""");

        verify(ec2).createVpc(REGION, "10.1.0.0/16", false, false, "default");
        assertEquals("vpc-replaced", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r), "the stack enters UPDATE_COMPLETE_CLEANUP_IN_PROGRESS");
        assertEquals("vpc-existing", provisioner.updateCleanupPhysicalId(r));

        assertTrue(provisioner.completeUpdate(r).complete());
        verify(ec2).deleteVpc(REGION, "vpc-existing");
        provisioner.clearUpdate(r);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void tenancyDedicatedToDefaultIsAppliedInPlaceAnyOtherChangeReplaces() {
        Vpc dedicated = vpc("vpc-existing", "10.0.0.0/16");
        dedicated.setInstanceTenancy("dedicated");
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of())).thenReturn(List.of(dedicated));

        StackResource kept = provision("vpc-existing", """
                {"CidrBlock": "10.0.0.0/16", "InstanceTenancy": "default"}""");
        assertEquals("vpc-existing", kept.getPhysicalId());
        verify(ec2).modifyVpcTenancy(REGION, "vpc-existing", "default");
        assertFalse(provisioner.hasReplacementUpdate(kept));

        // Now a default VPC asked to become dedicated: the only in-place direction is dedicated->default.
        dedicated.setInstanceTenancy("default");
        stubCreate(vpc("vpc-replaced", "10.0.0.0/16"));
        StackResource replaced = provision("vpc-existing", """
                {"CidrBlock": "10.0.0.0/16", "InstanceTenancy": "dedicated"}""");
        assertEquals("vpc-replaced", replaced.getPhysicalId());
        verify(ec2).createVpc(eq(REGION), eq("10.0.0.0/16"), eq(false), eq(false), eq("dedicated"));
        assertEquals("vpc-existing", provisioner.updateCleanupPhysicalId(replaced), "the displaced VPC is owed a delete");
    }

    @Test
    void aRolledBackReplacementDeletesTheNewVpcAndNamesThePriorOneAgain() {
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of()))
                .thenReturn(List.of(vpc("vpc-existing", "10.0.0.0/16")));
        stubCreate(vpc("vpc-replaced", "10.1.0.0/16"));
        StackResource r = provision("vpc-existing", Map.of("VpcId", "vpc-existing", "CidrBlock", "10.0.0.0/16"), """
                {"CidrBlock": "10.1.0.0/16"}""");
        assertEquals("10.1.0.0/16", r.getAttributes().get("CidrBlock"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ec2).deleteVpc(REGION, "vpc-replaced");
        verify(ec2, never()).deleteVpc(REGION, "vpc-existing");
        assertEquals("vpc-existing", r.getPhysicalId());
        assertEquals("vpc-existing", r.getAttributes().get("VpcId"));
        assertEquals("10.0.0.0/16", r.getAttributes().get("CidrBlock"), "Fn::GetAtt answers the prior VPC again");
        assertFalse(provisioner.hasReplacementUpdate(r), "nothing is owed a delete once the prior VPC stands again");
    }

    @Test
    void anInPlaceChangeIsRolledBackToThePriorDnsAttributesTagsAndTenancy() {
        Vpc existing = vpc("vpc-existing", "10.0.0.0/16");
        existing.setInstanceTenancy("dedicated");
        existing.setEnableDnsSupport(true);
        existing.setEnableDnsHostnames(false);
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of())).thenReturn(List.of(existing));
        when(ec2.describeTags(REGION, Map.of("resource-id", List.of("vpc-existing"))))
                .thenReturn(List.of(Map.of("key", "Name", "value", "old"), Map.of("key", "team", "value", "net")))
                // The snapshot reads the prior set once; every later read sees what the update wrote.
                .thenReturn(List.of(Map.of("key", "Name", "value", "new"), Map.of("key", "env", "value", "test")));

        StackResource r = provision("vpc-existing", """
                {"CidrBlock": "10.0.0.0/16", "InstanceTenancy": "default", "EnableDnsHostnames": true,
                 "Tags": [{"Key": "Name", "Value": "new"}, {"Key": "env", "Value": "test"}]}""");
        assertTrue(r.getAttributes().containsKey(Ec2VpcCfnProvisioner.IN_PLACE_PRIOR_ATTR), "the prior state is snapshotted");
        verify(ec2).modifyVpcAttribute(REGION, "vpc-existing", "enableDnsHostnames", "true");
        verify(ec2).createTags(eq(REGION), eq(List.of("vpc-existing")), argThat(tags("Name=new", "env=test")));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ec2).modifyVpcAttribute(REGION, "vpc-existing", "enableDnsHostnames", "false");
        verify(ec2, times(2)).modifyVpcAttribute(REGION, "vpc-existing", "enableDnsSupport", "true");
        verify(ec2).deleteTags(eq(REGION), eq(List.of("vpc-existing")), argThat(tags("env=test")));
        verify(ec2).createTags(eq(REGION), eq(List.of("vpc-existing")), argThat(tags("Name=old", "team=net")));
        verify(ec2).modifyVpcTenancy(REGION, "vpc-existing", "dedicated");
        assertFalse(r.getAttributes().containsKey(Ec2VpcCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void aRestoreThatThrowsKeepsTheSnapshotForTheRetry() {
        Vpc existing = vpc("vpc-existing", "10.0.0.0/16");
        existing.setEnableDnsSupport(true);
        existing.setEnableDnsHostnames(false);
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of())).thenReturn(List.of(existing));
        when(ec2.describeTags(REGION, Map.of("resource-id", List.of("vpc-existing")))).thenReturn(List.of());
        StackResource r = provision("vpc-existing", """
                {"CidrBlock": "10.0.0.0/16", "EnableDnsHostnames": true}""");
        String snapshot = r.getAttributes().get(Ec2VpcCfnProvisioner.IN_PLACE_PRIOR_ATTR);
        assertTrue(snapshot != null, "the prior state is snapshotted");
        doThrow(new AwsException("InternalError", "ec2 is down", 500))
                .when(ec2).modifyVpcAttribute(REGION, "vpc-existing", "enableDnsHostnames", "false");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertEquals("InternalError", failure.getErrorCode());
        assertEquals(snapshot, r.getAttributes().get(Ec2VpcCfnProvisioner.IN_PLACE_PRIOR_ATTR),
                "a failed restore keeps the snapshot for a ContinueUpdateRollback retry");
    }

    @Test
    void anUnchangedReuseLeavesNoSnapshotAndRollsBackAsComplete() {
        Vpc existing = vpc("vpc-existing", "10.0.0.0/16");
        existing.setEnableDnsSupport(true);
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of())).thenReturn(List.of(existing));
        when(ec2.describeTags(REGION, Map.of("resource-id", List.of("vpc-existing"))))
                .thenReturn(List.of(Map.of("key", "Name", "value", "same")));

        StackResource r = provision("vpc-existing", """
                {"CidrBlock": "10.0.0.0/16", "Tags": [{"Key": "Name", "Value": "same"}]}""");

        assertFalse(r.getAttributes().containsKey(Ec2VpcCfnProvisioner.IN_PLACE_PRIOR_ATTR));
        assertTrue(provisioner.rollbackUpdate(r));
        verify(ec2, never()).deleteTags(any(), any(), any());
        verify(ec2, never()).modifyVpcTenancy(any(), any(), any());
        verify(ec2, never()).deleteVpc(any(), any());
    }

    @Test
    void completingOrClearingAnUpdateDropsTheSnapshot() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::EC2::VPC");
        r.getAttributes().put(Ec2VpcCfnProvisioner.IN_PLACE_PRIOR_ATTR, "{}");
        provisioner.completeUpdate(r);
        assertFalse(r.getAttributes().containsKey(Ec2VpcCfnProvisioner.IN_PLACE_PRIOR_ATTR));
        r.getAttributes().put(Ec2VpcCfnProvisioner.IN_PLACE_PRIOR_ATTR, "{}");
        provisioner.clearUpdate(r);
        assertFalse(r.getAttributes().containsKey(Ec2VpcCfnProvisioner.IN_PLACE_PRIOR_ATTR));
    }

    @Test
    void aFailedStepOnAReusedVpcRestoresTheSnapshotBeforeTheFailurePropagates() {
        Vpc existing = vpc("vpc-existing", "10.0.0.0/16");
        existing.setEnableDnsSupport(true);
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of())).thenReturn(List.of(existing));
        doThrow(new AwsException("TagLimitExceeded", "too many tags", 400)).when(ec2).createTags(any(), any(), any());

        AwsException e = assertThrows(AwsException.class, () -> provision("vpc-existing", """
                {"CidrBlock": "10.0.0.0/16", "EnableDnsHostnames": true, "Tags": [{"Key": "k", "Value": "v"}]}"""));

        assertEquals("TagLimitExceeded", e.getErrorCode());
        verify(ec2).modifyVpcAttribute(REGION, "vpc-existing", "enableDnsHostnames", "false");
        verify(ec2, never()).deleteVpc(any(), any());
    }

    @Test
    void aFailedStepAfterACreateDeletesTheVpcAgain() {
        stubCreate(vpc("vpc-new", "10.0.0.0/16"));
        doThrow(new AwsException("TagLimitExceeded", "too many tags", 400)).when(ec2).createTags(any(), any(), any());

        assertThrows(AwsException.class, () -> provision(null, """
                {"CidrBlock": "10.0.0.0/16", "Tags": [{"Key": "k", "Value": "v"}]}"""));

        verify(ec2).deleteVpc(REGION, "vpc-new");
    }

    @Test
    void aTagResolutionFailureHappensBeforeTheVpcIsCreated() {
        // A missing Fn::ImportValue fails resolution; the VPC must not exist by then, or it would
        // sit outside the rollback guard and leak.
        when(engine.resolveNode(argThat(node -> node != null && node.isArray())))
                .thenThrow(new AwsException("ValidationError", "No export named Shared-Tag found", 400));

        AwsException e = assertThrows(AwsException.class, () -> provision(null, """
                {"CidrBlock": "10.0.0.0/16", "Tags": [{"Key": "k", "Value": {"Fn::ImportValue": "Shared-Tag"}}]}"""));

        assertEquals("ValidationError", e.getErrorCode());
        verify(ec2, never()).createVpc(anyString(), anyString(), anyBoolean(), anyBoolean(), anyString());
        verify(ec2, never()).deleteVpc(any(), any());
    }

    @Test
    void reservedAwsTagsAreNeverSnapshottedNorWrittenBack() {
        Vpc existing = vpc("vpc-existing", "10.0.0.0/16");
        existing.setEnableDnsSupport(true);
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of())).thenReturn(List.of(existing));
        when(ec2.describeTags(REGION, Map.of("resource-id", List.of("vpc-existing")))).thenReturn(List.of(
                Map.of("key", "aws:cloudformation:stack-name", "value", "my-stack"),
                Map.of("key", "Name", "value", "old")));

        StackResource r = provision("vpc-existing", """
                {"CidrBlock": "10.0.0.0/16", "Tags": [{"Key": "Name", "Value": "new"}]}""");

        String snapshot = r.getAttributes().get(Ec2VpcCfnProvisioner.IN_PLACE_PRIOR_ATTR);
        assertTrue(snapshot.contains("\"Name\":\"old\""), snapshot);
        assertFalse(snapshot.contains("aws:cloudformation"), snapshot);
        verify(ec2, never()).deleteTags(any(), any(), any());

        assertTrue(provisioner.rollbackUpdate(r));

        verify(ec2).createTags(eq(REGION), eq(List.of("vpc-existing")), argThat(tags("Name=new")));
        verify(ec2).createTags(eq(REGION), eq(List.of("vpc-existing")), argThat(tags("Name=old")));
        verify(ec2, never()).createTags(eq(REGION), any(), argThat(actual -> actual != null
                && actual.stream().anyMatch(t -> t.getKey().startsWith("aws:"))));
        verify(ec2, never()).deleteTags(eq(REGION), any(), argThat(actual -> actual != null
                && actual.stream().anyMatch(t -> t.getKey().startsWith("aws:"))));
    }

    @Test
    void deleteToleratesOnlyAVpcThatIsAlreadyGone() {
        doThrow(new AwsException("InvalidVpcID.NotFound", "does not exist", 400)).when(ec2).deleteVpc(REGION, "vpc-gone");
        assertDoesNotThrow(() -> provisioner.delete("AWS::EC2::VPC", "vpc-gone", REGION));

        doThrow(new AwsException("DependencyViolation", "has dependencies", 400)).when(ec2).deleteVpc(REGION, "vpc-busy");
        AwsException e = assertThrows(AwsException.class, () -> provisioner.delete("AWS::EC2::VPC", "vpc-busy", REGION));
        assertEquals("DependencyViolation", e.getErrorCode(), "a VPC with dependents outside the stack fails the delete, as on AWS");

        assertDoesNotThrow(() -> provisioner.delete("AWS::EC2::VPC", null, REGION));
        verify(ec2, times(2)).deleteVpc(any(), any());
    }

    @Test
    void anIdOnTheResourceAloneDoesNotCountAsAnUpdate() {
        stubCreate(vpc("vpc-new", "10.0.0.0/16"));

        // A resource carrying a physical id under a create context: what provision() itself
        // produces the moment it assigns the new id. Reading create-vs-update off the resource
        // makes that state indistinguishable from a real update.
        StackResource r = provision(null, "vpc-assigned-mid-method", """
                {"CidrBlock": "10.0.0.0/16"}""");

        verify(ec2, never()).describeVpcs(anyString(), any(), any());
        verify(ec2).createVpc(REGION, "10.0.0.0/16", false, false, "default");
        assertEquals("vpc-new", r.getPhysicalId());
    }

    @Test
    void aVpcDeletedOutOfBandFallsBackToCreate() {
        when(ec2.describeVpcs(REGION, List.of("vpc-gone"), Map.of()))
                .thenReturn(List.of());
        stubCreate(vpc("vpc-fresh", "10.0.0.0/16"));

        StackResource r = provision("vpc-gone", """
                {"CidrBlock": "10.0.0.0/16"}""");

        verify(ec2).createVpc(REGION, "10.0.0.0/16", false, false, "default");
        assertEquals("vpc-fresh", r.getPhysicalId());
    }
}
