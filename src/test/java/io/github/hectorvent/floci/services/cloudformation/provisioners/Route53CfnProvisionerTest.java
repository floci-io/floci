package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
}
