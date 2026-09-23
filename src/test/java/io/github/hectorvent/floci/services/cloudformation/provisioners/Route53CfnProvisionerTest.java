package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Route53CfnProvisionerTest {
    @Test
    void createsBackingPrivateZoneAndExactAttributes() {
        Route53Service service = mock(Route53Service.class);
        VpcAssociation vpc = new VpcAssociation("vpc-123", "us-east-1");
        HostedZone zone = new HostedZone("Z123456789", "ssm.us-east-1.amazonaws.com.",
                "caller", null, vpc);
        when(service.createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq("central endpoint zone"), argThat(sameVpc(vpc)))).thenReturn(
                        new Route53Service.CreateZoneResult(zone, null));
        when(service.getNameServers()).thenReturn(List.of("ns-1.example", "ns-2.example"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode()
                .put("Name", "ssm.us-east-1.amazonaws.com")
                .set("HostedZoneConfig", mapper.createObjectNode().put("Comment", "central endpoint zone"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("VPCs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("VPCId", "vpc-123").put("VPCRegion", "us-east-1")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("HostedZoneTags", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Key", "Accelerator").put("Value", "AWSAccelerator")));

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.<JsonNode>getArgument(0).asText());
        when(engine.resolveNode(props)).thenReturn(props);
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");

        new Route53CfnProvisioner(service).provision(resource, props, context);

        assertEquals("Z123456789", resource.getPhysicalId());
        assertEquals(Set.of("Id", "NameServers"), resource.getAttributes().keySet());
        assertEquals("Z123456789", resource.getAttributes().get("Id"));
        verify(service).createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq("central endpoint zone"), argThat(sameVpc(vpc)));
        verify(service, never()).associateVpcWithHostedZone(any(), any(), any());
        verify(service).changeTagsForResource("hostedzone", "Z123456789",
                List.of(java.util.Map.of("Key", "Accelerator", "Value", "AWSAccelerator")), List.of());
    }

    @Test
    void associatesAdditionalVpcsBeyondTheFirstOnCreate() {
        Route53Service service = mock(Route53Service.class);
        VpcAssociation firstVpc = new VpcAssociation("vpc-123", "us-east-1");
        VpcAssociation secondVpc = new VpcAssociation("vpc-456", "us-west-2");
        HostedZone zone = new HostedZone("Z123456789", "ssm.us-east-1.amazonaws.com.",
                "caller", null, firstVpc);
        when(service.createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq(null), argThat(sameVpc(firstVpc)))).thenReturn(
                        new Route53Service.CreateZoneResult(zone, null));
        when(service.getNameServers()).thenReturn(List.of("ns-1.example", "ns-2.example"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode().put("Name", "ssm.us-east-1.amazonaws.com");
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("VPCs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("VPCId", "vpc-123").put("VPCRegion", "us-east-1"))
                .add(mapper.createObjectNode().put("VPCId", "vpc-456").put("VPCRegion", "us-west-2")));

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.<JsonNode>getArgument(0).asText());
        when(engine.resolveNode(props)).thenReturn(props);
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");

        new Route53CfnProvisioner(service).provision(resource, props, context);

        verify(service).createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq(null), argThat(sameVpc(firstVpc)));
        verify(service).associateVpcWithHostedZone(eq("Z123456789"), argThat(sameVpc(secondVpc)), eq(null));
    }

    @Test
    void recordsPhysicalIdBeforeAssociatingAdditionalVpcsSoAFailureIsStillTrackedForCleanup() {
        Route53Service service = mock(Route53Service.class);
        VpcAssociation firstVpc = new VpcAssociation("vpc-123", "us-east-1");
        VpcAssociation secondVpc = new VpcAssociation("vpc-456", "us-west-2");
        HostedZone zone = new HostedZone("Z123456789", "ssm.us-east-1.amazonaws.com.",
                "caller", null, firstVpc);
        when(service.createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq(null), argThat(sameVpc(firstVpc)))).thenReturn(
                        new Route53Service.CreateZoneResult(zone, null));
        when(service.associateVpcWithHostedZone(eq("Z123456789"), argThat(sameVpc(secondVpc)), any()))
                .thenThrow(new RuntimeException("boom"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode().put("Name", "ssm.us-east-1.amazonaws.com");
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("VPCs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("VPCId", "vpc-123").put("VPCRegion", "us-east-1"))
                .add(mapper.createObjectNode().put("VPCId", "vpc-456").put("VPCRegion", "us-west-2")));

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.<JsonNode>getArgument(0).asText());
        when(engine.resolveNode(props)).thenReturn(props);
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");

        assertThrows(RuntimeException.class,
                () -> new Route53CfnProvisioner(service).provision(resource, props, context));

        assertEquals("Z123456789", resource.getPhysicalId());
        assertTrue("true".equals(resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR)),
                "a zone that was actually created must stay marked rollback-owned when a "
                        + "follow-up VPC association fails, so CREATE_FAILED cleanup still deletes it");
    }

    private static org.mockito.ArgumentMatcher<VpcAssociation> sameVpc(VpcAssociation expected) {
        if (expected == null) {
            return java.util.Objects::isNull;
        }
        return actual -> actual != null
                && expected.getVpcId().equals(actual.getVpcId())
                && expected.getVpcRegion().equals(actual.getVpcRegion());
    }

    @Test
    void backfillsBackingZoneUsingExistingPhysicalId() {
        Route53Service service = mock(Route53Service.class);
        HostedZone zone = new HostedZone("Z68119D97-4AB", "ssm.us-east-1.amazonaws.com.",
                "dns-stack/Zone", null, (VpcAssociation) null);
        when(service.getHostedZone("Z68119D97-4AB")).thenReturn(zone);
        when(service.getNameServers()).thenReturn(List.of("ns-1.example", "ns-2.example"));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode().put("Name", "ssm.us-east-1.amazonaws.com");
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.<JsonNode>getArgument(0).asText());
        when(engine.resolveNode(props)).thenReturn(props);
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");
        resource.setPhysicalId("Z68119D97-4AB");

        new Route53CfnProvisioner(service).provision(resource, props, context);

        verify(service).getHostedZone("Z68119D97-4AB");
        verify(service, never()).createHostedZone(any(), any(), any(), any());
        assertEquals("Z68119D97-4AB", resource.getAttributes().get("Id"));
        assertEquals(Set.of("Id", "NameServers"), resource.getAttributes().keySet());
    }

    @Test
    void recreatesZoneWhenTheTrackedPhysicalIdNoLongerBacksARealZone() {
        // Covers a physical ID left over from the retired monolith's HostedZone stub, which
        // minted a random Z-id with zero Route53Service backing (see commit 76228741d) -
        // there is no real zone to migrate, so an update recreates rather than failing.
        Route53Service service = mock(Route53Service.class);
        when(service.getHostedZone("ZLEGACYSTUBID")).thenThrow(
                new AwsException("NoSuchHostedZone", "No hosted zone found with ID: ZLEGACYSTUBID", 404));
        VpcAssociation vpc = new VpcAssociation("vpc-123", "us-east-1");
        HostedZone zone = new HostedZone("Z987654321", "ssm.us-east-1.amazonaws.com.",
                "dns-stack/Zone", null, vpc);
        when(service.createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq(null), argThat(sameVpc(vpc)))).thenReturn(new Route53Service.CreateZoneResult(zone, null));
        when(service.getNameServers()).thenReturn(List.of("ns-1.example", "ns-2.example"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode().put("Name", "ssm.us-east-1.amazonaws.com");
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("VPCs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("VPCId", "vpc-123").put("VPCRegion", "us-east-1")));

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.<JsonNode>getArgument(0).asText());
        when(engine.resolveNode(props)).thenReturn(props);
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");
        resource.setPhysicalId("ZLEGACYSTUBID");

        new Route53CfnProvisioner(service).provision(resource, props, context);

        verify(service).createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq(null), argThat(sameVpc(vpc)));
        assertEquals("Z987654321", resource.getPhysicalId());
    }

    @Test
    void rejectsVpcEntryMissingRegionInsteadOfSilentlyDroppingIt() {
        Route53Service service = mock(Route53Service.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode().put("Name", "ssm.us-east-1.amazonaws.com");
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("VPCs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("VPCId", "vpc-123")));

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.<JsonNode>getArgument(0).asText());
        when(engine.resolveNode(props)).thenReturn(props);
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");

        assertThrows(IllegalArgumentException.class,
                () -> new Route53CfnProvisioner(service).provision(resource, props, context));
        verify(service, never()).createHostedZone(any(), any(), any(), any());
    }

    @Test
    void rejectsVpcEntryWithBlankIdOrRegionInsteadOfSilentlyAccepting() {
        Route53Service service = mock(Route53Service.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode().put("Name", "ssm.us-east-1.amazonaws.com");
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("VPCs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("VPCId", "vpc-123").put("VPCRegion", "   ")));

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.<JsonNode>getArgument(0).asText());
        when(engine.resolveNode(props)).thenReturn(props);
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");

        assertThrows(IllegalArgumentException.class,
                () -> new Route53CfnProvisioner(service).provision(resource, props, context));
        verify(service, never()).createHostedZone(any(), any(), any(), any());
    }

    @Test
    void skipsBlankTagKeysMatchingProvisionContextResolveTagsConvention() {
        Route53Service service = mock(Route53Service.class);
        VpcAssociation vpc = new VpcAssociation("vpc-123", "us-east-1");
        HostedZone zone = new HostedZone("Z123456789", "ssm.us-east-1.amazonaws.com.",
                "caller", null, vpc);
        when(service.createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq(null), argThat(sameVpc(vpc)))).thenReturn(new Route53Service.CreateZoneResult(zone, null));
        when(service.getNameServers()).thenReturn(List.of("ns-1.example", "ns-2.example"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode().put("Name", "ssm.us-east-1.amazonaws.com");
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("VPCs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("VPCId", "vpc-123").put("VPCRegion", "us-east-1")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("HostedZoneTags", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Key", "").put("Value", "blank-key-should-be-skipped"))
                .add(mapper.createObjectNode().put("Key", "Accelerator").put("Value", "AWSAccelerator")));

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.<JsonNode>getArgument(0).asText());
        when(engine.resolveNode(props)).thenReturn(props);
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");

        new Route53CfnProvisioner(service).provision(resource, props, context);

        verify(service).changeTagsForResource("hostedzone", "Z123456789",
                List.of(java.util.Map.of("Key", "Accelerator", "Value", "AWSAccelerator")), List.of());
    }

    @Test
    void deletesReplacementZoneWhenVpcAssociationFailsDuringUpdateRecreate() {
        // If an update recreates a missing legacy zone (see
        // recreatesZoneWhenTheTrackedPhysicalIdNoLongerBacksARealZone) and a later VPC
        // association throws, the update-rollback path in CloudFormationService restores the
        // stack's previous StackResource wholesale and never learns the new zone's ID -
        // orphaning it. The next retry then reuses the same caller reference and fails with
        // HostedZoneAlreadyExists. The provisioner must clean up the replacement itself.
        Route53Service service = mock(Route53Service.class);
        when(service.getHostedZone("ZLEGACYSTUBID")).thenThrow(
                new AwsException("NoSuchHostedZone", "No hosted zone found with ID: ZLEGACYSTUBID", 404));
        VpcAssociation firstVpc = new VpcAssociation("vpc-123", "us-east-1");
        VpcAssociation secondVpc = new VpcAssociation("vpc-456", "us-west-2");
        HostedZone zone = new HostedZone("Z987654321", "ssm.us-east-1.amazonaws.com.",
                "dns-stack/Zone", null, firstVpc);
        when(service.createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq(null), argThat(sameVpc(firstVpc)))).thenReturn(
                        new Route53Service.CreateZoneResult(zone, null));
        RuntimeException associateFailure = new AwsException(
                "ServiceUnavailable", "throttled", 503);
        when(service.associateVpcWithHostedZone(eq("Z987654321"), argThat(sameVpc(secondVpc)), any()))
                .thenThrow(associateFailure);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode().put("Name", "ssm.us-east-1.amazonaws.com");
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("VPCs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("VPCId", "vpc-123").put("VPCRegion", "us-east-1"))
                .add(mapper.createObjectNode().put("VPCId", "vpc-456").put("VPCRegion", "us-west-2")));

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.<JsonNode>getArgument(0).asText());
        when(engine.resolveNode(props)).thenReturn(props);
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");
        resource.setPhysicalId("ZLEGACYSTUBID");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new Route53CfnProvisioner(service).provision(resource, props, context));

        assertEquals(associateFailure, thrown);
        verify(service).deleteHostedZone("Z987654321");
        assertTrue("true".equals(resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR)),
                "once the replacement zone is deleted, physical state matches pre-update reality "
                        + "(tracked zone missing), so the generic rollback walker must treat this "
                        + "resource as restored instead of failing rollback as unimplemented");
    }

    @Test
    void deletesReplacementZoneWhenTagWriteFailsDuringUpdateRecreate() {
        Route53Service service = mock(Route53Service.class);
        when(service.getHostedZone("ZLEGACYSTUBID")).thenThrow(
                new AwsException("NoSuchHostedZone", "No hosted zone found with ID: ZLEGACYSTUBID", 404));
        HostedZone zone = new HostedZone("Z987654321", "ssm.us-east-1.amazonaws.com.",
                "dns-stack/Zone", null, (VpcAssociation) null);
        when(service.createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq(null), eq(null))).thenReturn(new Route53Service.CreateZoneResult(zone, null));
        when(service.getNameServers()).thenReturn(List.of("ns-1.example", "ns-2.example"));
        RuntimeException tagFailure = new AwsException("ServiceUnavailable", "throttled", 503);
        org.mockito.Mockito.doThrow(tagFailure).when(service)
                .changeTagsForResource(eq("hostedzone"), eq("Z987654321"), any(), any());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode().put("Name", "ssm.us-east-1.amazonaws.com");
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("HostedZoneTags", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Key", "Accelerator").put("Value", "AWSAccelerator")));

        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.<JsonNode>getArgument(0).asText());
        when(engine.resolveNode(props)).thenReturn(props);
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Zone");
        resource.setResourceType("AWS::Route53::HostedZone");
        resource.setPhysicalId("ZLEGACYSTUBID");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new Route53CfnProvisioner(service).provision(resource, props, context));

        assertEquals(tagFailure, thrown);
        verify(service).deleteHostedZone("Z987654321");
        assertTrue("true".equals(resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR)),
                "once the replacement zone is deleted, physical state matches pre-update reality "
                        + "(tracked zone missing), so the generic rollback walker must treat this "
                        + "resource as restored instead of failing rollback as unimplemented");
    }

    @Test
    void recordSetIsUpsertedIntoItsZoneAndRefIsTheRecordName() {
        Route53Service service = mock(Route53Service.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode()
                .put("HostedZoneId", "Z123456789")
                .put("Name", "www.example.com")
                .put("Type", "A")
                .put("TTL", "300");
        ((com.fasterxml.jackson.databind.node.ObjectNode) props).set("ResourceRecords",
                mapper.createArrayNode().add("10.0.0.1"));
        CloudFormationTemplateEngine engine = recordEngine();
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");

        new Route53CfnProvisioner(service).provision(resource, props, context);

        assertEquals("www.example.com", resource.getPhysicalId());
        // The internal zone/type record survives for delete, but no schema attribute is published.
        assertEquals(Set.of("__FlociRoute53RecordZoneId", "__FlociRoute53RecordType"),
                resource.getAttributes().keySet());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).changeResourceRecordSets(eq("Z123456789"), captor.capture(), any());
        Map<String, Object> change = captor.getValue().get(0);
        assertEquals("UPSERT", change.get("action"));
        ResourceRecordSet rrs = (ResourceRecordSet) change.get("rrs");
        assertEquals("www.example.com", rrs.getName());
        assertEquals("A", rrs.getType());
        assertEquals(Long.valueOf(300), rrs.getTtl());
        assertEquals(List.of("10.0.0.1"), rrs.getRecords().stream().map(ResourceRecord::getValue).toList());
    }

    @Test
    void recordSetResolvesTheZoneFromHostedZoneNameByExactMatch() {
        Route53Service service = mock(Route53Service.class);
        // listHostedZonesByName is a starts-at paginator: it also returns zones that sort after the
        // requested name, so the provisioner must pick the exact match, not the first result.
        when(service.listHostedZonesByName("example.com.", 0)).thenReturn(List.of(
                new HostedZone("Z999", "example.com.", "caller", null, (VpcAssociation) null),
                new HostedZone("Zzzz", "zzz.internal.", "caller", null, (VpcAssociation) null)));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode()
                .put("HostedZoneName", "example.com.")
                .put("Name", "www.example.com")
                .put("Type", "A");
        ProvisionContext context = new ProvisionContext(recordEngine(), "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");

        new Route53CfnProvisioner(service).provision(resource, props, context);

        verify(service).changeResourceRecordSets(eq("Z999"), any(), any());
    }

    @Test
    void recordSetRejectsAHostedZoneNameThatMatchesNoZone() {
        Route53Service service = mock(Route53Service.class);
        // The paginator returns a later-sorting zone, but none whose name actually equals the request.
        when(service.listHostedZonesByName("app.example.com.", 0)).thenReturn(List.of(
                new HostedZone("Zzzz", "zzz.internal.", "caller", null, (VpcAssociation) null)));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode()
                .put("HostedZoneName", "app.example.com.")
                .put("Name", "www.app.example.com")
                .put("Type", "A");
        ProvisionContext context = new ProvisionContext(recordEngine(), "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");

        AwsException failure = assertThrows(AwsException.class,
                () -> new Route53CfnProvisioner(service).provision(resource, props, context));
        assertEquals("InvalidChangeBatch", failure.getErrorCode());
        verify(service, never()).changeResourceRecordSets(any(), any(), any());
    }

    @Test
    void recordSetWithANonNumericTtlIsRejected() {
        assertRecordSetRejected("{\"HostedZoneId\": \"Z1\", \"Name\": \"www.example.com\", "
                + "\"Type\": \"A\", \"TTL\": \"5 minutes\"}");
    }

    @Test
    void recordSetWithoutANameIsRejected() {
        assertRecordSetRejected("{\"HostedZoneId\": \"Z1\", \"Type\": \"A\"}");
    }

    @Test
    void recordSetWithoutATypeIsRejected() {
        assertRecordSetRejected("{\"HostedZoneId\": \"Z1\", \"Name\": \"www.example.com\"}");
    }

    @Test
    void recordSetWithoutAZoneIsRejected() {
        assertRecordSetRejected("{\"Name\": \"www.example.com\", \"Type\": \"A\"}");
    }

    private void assertRecordSetRejected(String json) {
        Route53Service service = mock(Route53Service.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props;
        try {
            props = mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ProvisionContext context = new ProvisionContext(recordEngine(), "us-east-1", "623666680275", "dns-stack");
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");

        AwsException failure = assertThrows(AwsException.class,
                () -> new Route53CfnProvisioner(service).provision(resource, props, context));
        assertEquals("ValidationError", failure.getErrorCode());
        verify(service, never()).changeResourceRecordSets(any(), any(), any());
    }

    @Test
    void renamingARecordSetOnUpdateRemovesTheSupersededRecord() {
        Route53Service service = mock(Route53Service.class);
        ResourceRecordSet priorRecord = new ResourceRecordSet();
        priorRecord.setName("auth.example.com");
        priorRecord.setType("A");
        when(service.listResourceRecordSets("Z1", null, null, 0)).thenReturn(List.of(priorRecord));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode()
                .put("HostedZoneId", "Z1")
                .put("Name", "login.example.com")
                .put("Type", "A");
        ProvisionContext context = new ProvisionContext(recordEngine(), "us-east-1", "623666680275",
                "dns-stack", "auth.example.com");
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");
        resource.setPhysicalId("auth.example.com");
        resource.getAttributes().put("__FlociRoute53RecordZoneId", "Z1");
        resource.getAttributes().put("__FlociRoute53RecordType", "A");

        new Route53CfnProvisioner(service).provision(resource, props, context);

        assertEquals("login.example.com", resource.getPhysicalId());
        // Two changes: an UPSERT for the new name and a DELETE removing the record left under the old.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service, times(2)).changeResourceRecordSets(eq("Z1"), captor.capture(), any());
        List<String> actions = captor.getAllValues().stream()
                .map(changes -> (String) changes.get(0).get("action")).toList();
        assertTrue(actions.contains("UPSERT"), actions.toString());
        assertTrue(actions.contains("DELETE"), actions.toString());
    }

    @Test
    void deletingARecordSetViaTheResourceIssuesAMatchingDeleteChange() {
        Route53Service service = mock(Route53Service.class);
        ResourceRecordSet existing = new ResourceRecordSet();
        existing.setName("www.example.com");
        existing.setType("A");
        when(service.listResourceRecordSets("Z123456789", null, null, 0)).thenReturn(List.of(existing));
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");
        resource.setPhysicalId("www.example.com");
        resource.getAttributes().put("__FlociRoute53RecordZoneId", "Z123456789");
        resource.getAttributes().put("__FlociRoute53RecordType", "A");

        new Route53CfnProvisioner(service).delete(resource, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).changeResourceRecordSets(eq("Z123456789"), captor.capture(), any());
        Map<String, Object> change = captor.getValue().get(0);
        assertEquals("DELETE", change.get("action"));
        assertSame(existing, change.get("rrs"));
    }

    @Test
    void deletingARecordSetToleratesAZoneAlreadyGone() {
        Route53Service service = mock(Route53Service.class);
        when(service.listResourceRecordSets("Z123456789", null, null, 0))
                .thenThrow(new AwsException("NoSuchHostedZone", "gone", 404));
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");
        resource.setPhysicalId("www.example.com");
        resource.getAttributes().put("__FlociRoute53RecordZoneId", "Z123456789");
        resource.getAttributes().put("__FlociRoute53RecordType", "A");

        new Route53CfnProvisioner(service).delete(resource, "us-east-1");

        verify(service, never()).changeResourceRecordSets(any(), any(), any());
    }

    @Test
    void deletingARecordSetByIdAloneLeavesTheZoneUntouched() {
        Route53Service service = mock(Route53Service.class);

        new Route53CfnProvisioner(service).delete("AWS::Route53::RecordSet", "www.example.com", "us-east-1");

        verifyNoInteractions(service);
    }

    private static CloudFormationTemplateEngine recordEngine() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveStringList(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            List<String> out = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(element -> out.add(element.asText()));
            }
            return out;
        });
        return engine;
    }

    @Test
    void deletingAHostedZoneStillDeletesTheZone() {
        Route53Service service = mock(Route53Service.class);

        new Route53CfnProvisioner(service).delete("AWS::Route53::HostedZone", "Z123456789", "us-east-1");

        verify(service).deleteHostedZone("Z123456789");
    }
}
