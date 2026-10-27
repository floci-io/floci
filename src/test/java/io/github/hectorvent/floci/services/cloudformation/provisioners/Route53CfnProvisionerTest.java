package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.ChangeInfo;
import io.github.hectorvent.floci.services.route53.model.HealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
        ((ObjectNode) props).set("VPCs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("VPCId", "vpc-123").put("VPCRegion", "us-east-1")));
        ((ObjectNode) props).set("HostedZoneTags", mapper.createArrayNode()
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
                List.of(Map.of("Key", "Accelerator", "Value", "AWSAccelerator")), List.of());
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
        ((ObjectNode) props).set("VPCs", mapper.createArrayNode()
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
        ((ObjectNode) props).set("VPCs", mapper.createArrayNode()
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

    private static ArgumentMatcher<VpcAssociation> sameVpc(VpcAssociation expected) {
        if (expected == null) {
            return Objects::isNull;
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
        ((ObjectNode) props).set("VPCs", mapper.createArrayNode()
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
        ((ObjectNode) props).set("VPCs", mapper.createArrayNode()
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
        ((ObjectNode) props).set("VPCs", mapper.createArrayNode()
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
        ((ObjectNode) props).set("VPCs", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("VPCId", "vpc-123").put("VPCRegion", "us-east-1")));
        ((ObjectNode) props).set("HostedZoneTags", mapper.createArrayNode()
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
                List.of(Map.of("Key", "Accelerator", "Value", "AWSAccelerator")), List.of());
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
        ((ObjectNode) props).set("VPCs", mapper.createArrayNode()
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
        Mockito.doThrow(tagFailure).when(service)
                .changeTagsForResource(eq("hostedzone"), eq("Z987654321"), any(), any());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode().put("Name", "ssm.us-east-1.amazonaws.com");
        ((ObjectNode) props).set("HostedZoneTags", mapper.createArrayNode()
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
    void recordSetIsCreatedInItsZoneAndRefIsTheRecordName() {
        Route53Service service = mock(Route53Service.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode()
                .put("HostedZoneId", "Z123456789")
                .put("Name", "www.example.com")
                .put("Type", "A")
                .put("TTL", "300");
        ((ObjectNode) props).set("ResourceRecords",
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
        // A CREATE, not an UPSERT: the stack does not own the record yet, so a record another owner
        // already holds under this name must fail the stack rather than be overwritten.
        assertEquals("CREATE", change.get("action"));
        ResourceRecordSet rrs = (ResourceRecordSet) change.get("rrs");
        // Route 53 stores and lists every record name fully qualified, whatever the template wrote.
        assertEquals("www.example.com.", rrs.getName());
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
        priorRecord.setName("auth.example.com.");
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
        // Two changes: a CREATE for the new name, which the stack does not own yet, and a DELETE
        // removing the record left under the old.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service, times(2)).changeResourceRecordSets(eq("Z1"), captor.capture(), any());
        List<String> actions = captor.getAllValues().stream()
                .map(changes -> (String) changes.get(0).get("action")).toList();
        assertEquals(List.of("CREATE", "DELETE"), actions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"www.example.com.", "WWW.EXAMPLE.COM."})
    void anUpdateThatOnlyChangesNameIdentityKeepsTheRecord(String recordName) {
        Route53Service service = mock(Route53Service.class);
        ResourceRecordSet stored = new ResourceRecordSet();
        stored.setName("www.example.com.");
        stored.setType("A");
        when(service.listResourceRecordSets("Z1", null, null, 0)).thenReturn(List.of(stored));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode()
                .put("HostedZoneId", "Z1")
                .put("Name", recordName)
                .put("Type", "A");
        ProvisionContext context = new ProvisionContext(recordEngine(), "us-east-1", "623666680275",
                "dns-stack", "www.example.com");
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");
        resource.setPhysicalId("www.example.com");
        resource.getAttributes().put("__FlociRoute53RecordZoneId", "Z1");
        resource.getAttributes().put("__FlociRoute53RecordType", "A");

        new Route53CfnProvisioner(service).provision(resource, props, context);

        // Both names are the same record once fully qualified, so the UPSERT is the only change: a
        // DELETE of the "prior" name would remove the record the UPSERT just wrote.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).changeResourceRecordSets(eq("Z1"), captor.capture(), any());
        assertEquals("UPSERT", captor.getValue().get(0).get("action"));
    }

    @Test
    void anUpdateWithABlankSetIdentifierKeepsTheRecordWithoutOne() {
        Route53Service service = mock(Route53Service.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode props = mapper.createObjectNode()
                .put("HostedZoneId", "Z1")
                .put("Name", "www.example.com")
                .put("Type", "A")
                .put("SetIdentifier", "");
        ProvisionContext context = new ProvisionContext(recordEngine(), "us-east-1", "623666680275",
                "dns-stack", "www.example.com");
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");
        resource.setPhysicalId("www.example.com");
        resource.getAttributes().put("__FlociRoute53RecordZoneId", "Z1");
        resource.getAttributes().put("__FlociRoute53RecordType", "A");

        new Route53CfnProvisioner(service).provision(resource, props, context);

        // Route 53 requires a set identifier of at least one character, so a blank one is no
        // identifier: the record is the one the last provision wrote, re-applied in place.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).changeResourceRecordSets(eq("Z1"), captor.capture(), any());
        Map<String, Object> change = captor.getValue().get(0);
        assertEquals("UPSERT", change.get("action"));
        assertNull(((ResourceRecordSet) change.get("rrs")).getSetIdentifier());
    }

    @Test
    void deletingARecordSetViaTheResourceIssuesAMatchingDeleteChange() {
        Route53Service service = mock(Route53Service.class);
        ResourceRecordSet existing = new ResourceRecordSet();
        existing.setName("www.example.com.");
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
    void deletingFindsTheRecordAnEarlierVersionStoredAsWritten() {
        Route53Service service = mock(Route53Service.class);
        // An earlier version stored the template's undotted Name and blank SetIdentifier as written.
        ResourceRecordSet legacy = new ResourceRecordSet();
        legacy.setName("www.example.com");
        legacy.setType("A");
        legacy.setSetIdentifier("");
        when(service.listResourceRecordSets("Z1", null, null, 0)).thenReturn(List.of(legacy));
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");
        resource.setPhysicalId("www.example.com");
        resource.getAttributes().put("__FlociRoute53RecordZoneId", "Z1");
        resource.getAttributes().put("__FlociRoute53RecordType", "A");

        new Route53CfnProvisioner(service).delete(resource, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).changeResourceRecordSets(eq("Z1"), captor.capture(), any());
        Map<String, Object> change = captor.getValue().get(0);
        assertEquals("DELETE", change.get("action"));
        assertSame(legacy, change.get("rrs"));
    }

    @Test
    void deletingPrefersTheCopyNamedAsThePhysicalIdWhenAnEarlierVersionStoredBoth() {
        Route53Service service = mock(Route53Service.class);
        // An earlier version compared names literally, so another owner's undotted record and this
        // stack's dotted one can both be stored. The listing sorts the undotted one first.
        ResourceRecordSet otherOwners = new ResourceRecordSet();
        otherOwners.setName("www.example.com");
        otherOwners.setType("A");
        ResourceRecordSet own = new ResourceRecordSet();
        own.setName("www.example.com.");
        own.setType("A");
        when(service.listResourceRecordSets("Z1", null, null, 0)).thenReturn(List.of(otherOwners, own));
        StackResource resource = new StackResource();
        resource.setLogicalId("Www");
        resource.setResourceType("AWS::Route53::RecordSet");
        resource.setPhysicalId("www.example.com.");
        resource.getAttributes().put("__FlociRoute53RecordZoneId", "Z1");
        resource.getAttributes().put("__FlociRoute53RecordType", "A");

        new Route53CfnProvisioner(service).delete(resource, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).changeResourceRecordSets(eq("Z1"), captor.capture(), any());
        assertSame(own, captor.getValue().get(0).get("rrs"));
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

    @Test
    void recordSetCarriesItsRoutingPolicy() {
        Route53Service service = mock(Route53Service.class);
        JsonNode props = new ObjectMapper().createObjectNode()
                .put("HostedZoneId", "Z1")
                .put("Name", "www.example.test.")
                .put("Type", "CNAME")
                .put("TTL", "60")
                .put("SetIdentifier", "aws")
                .put("Region", "us-east-1")
                .put("Failover", "PRIMARY")
                .put("Weight", "10")
                .put("HealthCheckId", "hc-1");
        StackResource resource = new StackResource();
        resource.setLogicalId("Record");
        resource.setResourceType("AWS::Route53::RecordSet");

        new Route53CfnProvisioner(service).provision(resource, props,
                new ProvisionContext(recordEngine(), "us-east-1", "000000000000", "dns-stack"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).changeResourceRecordSets(eq("Z1"), captor.capture(), any());
        ResourceRecordSet rrs = (ResourceRecordSet) captor.getValue().get(0).get("rrs");
        assertEquals("us-east-1", rrs.getRegion());
        assertEquals("PRIMARY", rrs.getFailover());
        assertEquals(Long.valueOf(10), rrs.getWeight());
        assertEquals("hc-1", rrs.getHealthCheckId());
    }

    @Test
    void recordSetWithANonNumericWeightIsRejected() {
        assertRecordSetRejected("{\"HostedZoneId\":\"Z1\",\"Name\":\"a.example.com\",\"Type\":\"A\","
                + "\"SetIdentifier\":\"a\",\"Weight\":\"heavy\"}");
    }

    @Test
    void recordGroupWritesEveryRecordSetInOneBatchWithItsRoutingPolicy() throws Exception {
        Route53Service service = mock(Route53Service.class);
        when(service.changeResourceRecordSets(eq("Z1"), any(), any()))
                .thenReturn(new ChangeInfo("C123", "2026-10-23T00:00:00Z", "web tier"));
        JsonNode props = new ObjectMapper().readTree("""
                {"HostedZoneId": "Z1", "Comment": "web tier", "RecordSets": [
                  {"Name": "www.example.test", "Type": "A", "TTL": "60", "SetIdentifier": "blue", "Weight": 70,
                   "HealthCheckId": "hc-1", "ResourceRecords": ["10.0.0.7"]},
                  {"Name": "www.example.test.", "Type": "A", "TTL": "60", "SetIdentifier": "green", "Weight": 30,
                   "ResourceRecords": ["10.0.0.8"]}
                ]}
                """);
        StackResource resource = recordGroup();

        new Route53CfnProvisioner(service).provision(resource, props, groupContext(null));

        // Ref is the group's name, and Fn::GetAtt Id the change GetChange reads.
        assertEquals("Group", resource.getPhysicalId());
        assertEquals(Set.of("Id"), publicAttributes(resource));
        assertEquals("C123", resource.getAttributes().get("Id"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).changeResourceRecordSets(eq("Z1"), captor.capture(), eq("web tier"));
        List<Map<String, Object>> changes = captor.getValue();
        assertEquals(List.of("CREATE", "CREATE"), changes.stream().map(change -> change.get("action")).toList());
        ResourceRecordSet blue = (ResourceRecordSet) changes.get(0).get("rrs");
        assertEquals("www.example.test.", blue.getName());
        assertEquals(Long.valueOf(60), blue.getTtl());
        assertEquals("blue", blue.getSetIdentifier());
        assertEquals(Long.valueOf(70), blue.getWeight());
        assertEquals("hc-1", blue.getHealthCheckId());
        assertEquals(List.of("10.0.0.7"), blue.getRecords().stream().map(ResourceRecord::getValue).toList());
        ResourceRecordSet green = (ResourceRecordSet) changes.get(1).get("rrs");
        assertEquals("green", green.getSetIdentifier());
        assertEquals(Long.valueOf(30), green.getWeight());
    }

    @Test
    void recordGroupValidatesEveryRecordSetBeforeChangingTheZone() throws Exception {
        Route53Service service = mock(Route53Service.class);
        JsonNode props = new ObjectMapper().readTree("""
                {"HostedZoneId": "Z1", "RecordSets": [
                  {"Name": "valid.example.test", "Type": "A", "TTL": "60", "ResourceRecords": ["192.0.2.1"]},
                  {"Name": "invalid.example.test"}
                ]}
                """);
        StackResource resource = recordGroup();

        AwsException failure = assertThrows(AwsException.class,
                () -> new Route53CfnProvisioner(service).provision(resource, props, groupContext(null)));

        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("RecordSets[1]"), failure.getMessage());
        verifyNoInteractions(service);
        assertNull(resource.getPhysicalId());
        assertTrue(resource.getAttributes().isEmpty());
    }

    @Test
    void recordGroupWithoutRecordSetsIsRejected() throws Exception {
        assertRecordGroupRejected("{\"HostedZoneId\": \"Z1\", \"RecordSets\": []}");
        assertRecordGroupRejected("{\"HostedZoneId\": \"Z1\"}");
    }

    @Test
    void recordGroupWithoutAZoneIsRejected() throws Exception {
        assertRecordGroupRejected("""
                {"RecordSets": [{"Name": "www.example.test", "Type": "A", "ResourceRecords": ["192.0.2.1"]}]}
                """);
    }

    @Test
    void recordGroupRejectsAHostedZoneIdOnBothTheGroupAndARecordSet() throws Exception {
        // AWS documents that creation fails when HostedZoneId is defined on the group and a record set.
        assertRecordGroupRejected("""
                {"HostedZoneId": "Z1", "RecordSets": [
                  {"HostedZoneId": "Z1", "Name": "www.example.test", "Type": "A", "ResourceRecords": ["192.0.2.1"]}
                ]}
                """);
    }

    @Test
    void recordGroupRejectsARecordSetInAnotherZone() throws Exception {
        Route53Service service = mock(Route53Service.class);
        when(service.listHostedZonesByName("other.example.test.", 0)).thenReturn(List.of(
                new HostedZone("Z2", "other.example.test.", "caller", null, (VpcAssociation) null)));
        JsonNode props = new ObjectMapper().readTree("""
                {"HostedZoneId": "Z1", "RecordSets": [
                  {"HostedZoneName": "other.example.test.", "Name": "www.other.example.test", "Type": "A",
                   "ResourceRecords": ["192.0.2.1"]}
                ]}
                """);

        AwsException failure = assertThrows(AwsException.class,
                () -> new Route53CfnProvisioner(service).provision(recordGroup(), props, groupContext(null)));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(service, never()).changeResourceRecordSets(any(), any(), any());
    }

    @Test
    void recordGroupThatNamesNoZoneWritesIntoTheZoneItsRecordSetsName() throws Exception {
        Route53Service service = mock(Route53Service.class);
        when(service.changeResourceRecordSets(eq("Z2"), any(), any())).thenReturn(new ChangeInfo("C1", "now", null));
        JsonNode props = new ObjectMapper().readTree("""
                {"RecordSets": [
                  {"HostedZoneId": "Z2", "Name": "a.example.test", "Type": "A", "ResourceRecords": ["192.0.2.1"]},
                  {"Name": "b.example.test", "Type": "A", "ResourceRecords": ["192.0.2.2"]}
                ]}
                """);

        new Route53CfnProvisioner(service).provision(recordGroup(), props, groupContext(null));

        verify(service).changeResourceRecordSets(eq("Z2"), argThat(changes -> changes.size() == 2), any());
    }

    @Test
    void recordGroupUpdateInItsZoneSwapsItsRecordsInOneBatchAndARollbackPutsThemBack() throws Exception {
        Route53Service service = mock(Route53Service.class);
        ResourceRecordSet kept = aRecord("old.example.test.", "192.0.2.1");
        // Written by the last update, then deleted outside the stack: there is nothing to DELETE.
        ResourceRecordSet gone = aRecord("gone.example.test.", "192.0.2.5");
        when(service.listResourceRecordSets("Z1", null, null, 0)).thenReturn(List.of(kept));
        when(service.changeResourceRecordSets(eq("Z1"), any(), any())).thenReturn(new ChangeInfo("C2", "now", null));
        StackResource resource = existingGroup("Z1", "C1", kept, gone);
        Map<String, String> before = Map.copyOf(resource.getAttributes());
        JsonNode props = new ObjectMapper().readTree("""
                {"HostedZoneId": "Z1", "RecordSets": [
                  {"Name": "new.example.test", "Type": "A", "TTL": "300", "ResourceRecords": ["192.0.2.2"]}
                ]}
                """);
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);

        owner.provision(resource, props, groupContext("Group"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).changeResourceRecordSets(eq("Z1"), captor.capture(), any());
        assertEquals(List.of("DELETE old.example.test.", "CREATE new.example.test."), describe(captor.getValue()));
        assertEquals("C2", resource.getAttributes().get("Id"));
        assertFalse(owner.hasReplacementUpdate(resource));
        assertNull(owner.updateCleanupPhysicalId(resource));

        ResourceRecordSet written = (ResourceRecordSet) captor.getValue().get(1).get("rrs");
        when(service.listResourceRecordSets("Z1", null, null, 0)).thenReturn(List.of(written));
        assertTrue(owner.rollbackUpdate(resource));

        verify(service, times(2)).changeResourceRecordSets(eq("Z1"), captor.capture(), any());
        assertEquals(List.of("DELETE new.example.test.", "CREATE old.example.test.", "CREATE gone.example.test."),
                describe(captor.getValue()));
        assertEquals(before, resource.getAttributes());
    }

    @Test
    void recordGroupZoneChangeDeletesTheOldRecordsOnlyOnceTheUpdateCommits() throws Exception {
        Route53Service service = mock(Route53Service.class);
        ResourceRecordSet old = aRecord("www.example.test.", "192.0.2.1");
        when(service.listResourceRecordSets("ZOLD", null, null, 0)).thenReturn(List.of(old));
        when(service.changeResourceRecordSets(any(), any(), any())).thenReturn(new ChangeInfo("C2", "now", null));
        StackResource resource = existingGroup("ZOLD", "C1", old);
        JsonNode props = new ObjectMapper().readTree("""
                {"HostedZoneId": "ZNEW", "RecordSets": [
                  {"Name": "www.example.test", "Type": "A", "TTL": "300", "ResourceRecords": ["192.0.2.2"]}
                ]}
                """);
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);

        owner.provision(resource, props, groupContext("Group"));

        verify(service).changeResourceRecordSets(eq("ZNEW"), argThat(changes -> changes.size() == 1
                && "CREATE".equals(changes.get(0).get("action"))), any());
        verify(service, never()).changeResourceRecordSets(eq("ZOLD"), any(), any());
        assertTrue(owner.hasReplacementUpdate(resource));
        assertEquals("Group", owner.updateCleanupPhysicalId(resource));

        UpdateCleanupResult cleanup = owner.completeUpdate(resource);

        assertTrue(cleanup.applicable());
        assertTrue(cleanup.complete());
        verify(service).changeResourceRecordSets(eq("ZOLD"), argThat(changes -> changes.size() == 1
                && "DELETE".equals(changes.get(0).get("action")) && changes.get(0).get("rrs") != null), any());
        owner.clearUpdate(resource);
        assertFalse(owner.hasReplacementUpdate(resource));
        assertEquals(Set.of("Id"), publicAttributes(resource));
    }

    @Test
    void recordGroupCleanupThatGivesUpForgetsTheOldRecords() throws Exception {
        Route53Service service = mock(Route53Service.class);
        ResourceRecordSet old = aRecord("www.example.test.", "192.0.2.1");
        when(service.listResourceRecordSets("ZOLD", null, null, 0)).thenReturn(List.of(old));
        when(service.listResourceRecordSets("ZNEW", null, null, 0)).thenReturn(List.of(movedRecord()));
        when(service.changeResourceRecordSets(eq("ZOLD"), any(), any()))
                .thenThrow(new AwsException("InvalidChangeBatch", "values do not match", 400));
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        StackResource resource = movedGroup(owner, service, old);

        for (int attempt = 1; attempt <= 3; attempt++) {
            UpdateCleanupResult cleanup = owner.completeUpdate(resource);
            assertFalse(cleanup.complete());
            assertEquals(attempt, cleanup.attempts());
            assertEquals("values do not match", cleanup.failureReason());
        }
        owner.clearUpdate(resource);

        assertFalse(owner.hasReplacementUpdate(resource));
        assertNull(owner.updateCleanupPhysicalId(resource));
        owner.delete(resource, "us-east-1");
        verify(service, times(3)).changeResourceRecordSets(eq("ZOLD"), any(), any());
        // The update's write and the delete.
        verify(service, times(2)).changeResourceRecordSets(eq("ZNEW"), any(), any());
    }

    @Test
    void deletingARecordGroupMidMoveDeletesTheOldRecordsFirstAndKeepsBothUntilTheyAreGone() throws Exception {
        Route53Service service = mock(Route53Service.class);
        ResourceRecordSet old = aRecord("www.example.test.", "192.0.2.1");
        when(service.listResourceRecordSets("ZOLD", null, null, 0)).thenReturn(List.of(old));
        when(service.listResourceRecordSets("ZNEW", null, null, 0)).thenReturn(List.of(movedRecord()));
        when(service.changeResourceRecordSets(eq("ZOLD"), any(), any()))
                .thenThrow(new AwsException("InvalidChangeBatch", "values do not match", 400))
                .thenReturn(null);
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        StackResource resource = movedGroup(owner, service, old);

        assertThrows(AwsException.class, () -> owner.delete(resource, "us-east-1"));
        verify(service, never()).listResourceRecordSets(eq("ZNEW"), any(), any(), eq(0));
        assertTrue(owner.hasReplacementUpdate(resource));

        owner.delete(resource, "us-east-1");
        verify(service, times(2)).changeResourceRecordSets(eq("ZOLD"), any(), any());
        verify(service, times(2)).changeResourceRecordSets(eq("ZNEW"), any(), any());
        assertFalse(owner.hasReplacementUpdate(resource));
    }

    @Test
    void zoneChangeRollbackThatCannotDeleteTheNewRecordsPointsBackAtTheOldOnesAndRetries() throws Exception {
        Route53Service service = mock(Route53Service.class);
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        ResourceRecordSet old = aRecord("www.example.test.", "192.0.2.1");
        StackResource resource = failedMoveRollback(owner, service, old);

        // The group already names its old records again, so a skip leaves it matching the template the
        // stack rolls back to; only the new zone's records are owed, and Retain never keeps them.
        assertEquals("Group", resource.getPhysicalId());
        Map<String, String> restored = new HashMap<>(resource.getAttributes());
        assertNotNull(restored.remove("__FlociRoute53GroupOwedDeletes"));
        assertEquals(existingGroup("ZOLD", "C1", old).getAttributes(), restored);
        // Owed records do not make the group a replacement, and no cleanup announces them: a commit
        // never deletes them, and the engine announces before it says whether it commits or deletes.
        assertFalse(owner.hasReplacementUpdate(resource));
        assertNull(owner.updateCleanupPhysicalId(resource));

        // ContinueUpdateRollback runs the rollback again, which now only owes that delete.
        assertTrue(owner.rollbackUpdate(resource));

        assertEquals(existingGroup("ZOLD", "C1", old).getAttributes(), resource.getAttributes());
        assertFalse(owner.hasReplacementUpdate(resource));
        verify(service, never()).changeResourceRecordSets(eq("ZOLD"), any(), any());
        // The update's write, the failed delete and the retried one.
        verify(service, times(3)).changeResourceRecordSets(eq("ZNEW"), any(), any());
    }

    @Test
    void recordsAFailedRollbackCouldNotDeleteGoWithTheStackWhileTheRollbackIsUnfinished() throws Exception {
        Route53Service service = mock(Route53Service.class);
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        ResourceRecordSet old = aRecord("www.example.test.", "192.0.2.1");
        StackResource resource = failedMoveRollback(owner, service, old);

        // DeleteStack from UPDATE_ROLLBACK_FAILED: the stack's cleanup runs first, under Retain too.
        UpdateCleanupResult cleanup = owner.completeDeleteCleanup(resource);

        assertTrue(cleanup.applicable());
        assertTrue(cleanup.complete());
        assertEquals("Group", cleanup.previousPhysicalId());
        owner.clearDeleteCleanup(resource);
        assertEquals(existingGroup("ZOLD", "C1", old).getAttributes(), resource.getAttributes());
        // The failed delete and this one.
        verify(service, times(2)).changeResourceRecordSets(eq("ZNEW"), any(), eq("CloudFormation delete Group"));
        verify(service, never()).changeResourceRecordSets(eq("ZOLD"), any(), any());
    }

    @Test
    void aCommitNeverDeletesWhatAFailedRollbackLeftEvenWhileTheGroupIsUpdateFailed() throws Exception {
        Route53Service service = mock(Route53Service.class);
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        StackResource resource = failedMoveRollback(owner, service, aRecord("www.example.test.", "192.0.2.1"));

        // A nested stack's child is updated while it is still UPDATE_ROLLBACK_FAILED, so its commit can
        // meet a group whose rollback is unfinished.
        assertFalse(owner.completeUpdate(resource).applicable());

        verify(service, times(1)).changeResourceRecordSets(eq("ZNEW"), any(), eq("CloudFormation delete Group"));
        assertFalse(resource.getAttributes().containsKey("__FlociRoute53GroupOwedDeletes"));
    }

    @Test
    void removingAGroupNeverDeletesWhatAFailedRollbackLeft() throws Exception {
        Route53Service service = mock(Route53Service.class);
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        ResourceRecordSet old = aRecord("www.example.test.", "192.0.2.1");
        when(service.listResourceRecordSets("ZOLD", null, null, 0)).thenReturn(List.of(old));
        StackResource resource = failedMoveRollback(owner, service, old);

        owner.delete(resource, "us-east-1");

        verify(service, times(1)).changeResourceRecordSets(eq("ZNEW"), any(), eq("CloudFormation delete Group"));
        verify(service).changeResourceRecordSets(eq("ZOLD"), any(), eq("CloudFormation delete Group"));
    }

    @Test
    void aSkippedRollbackLeavesTheRecordsItCouldNotDeleteInTheirZone() throws Exception {
        Route53Service service = mock(Route53Service.class);
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        ResourceRecordSet old = aRecord("www.example.test.", "192.0.2.1");
        when(service.listResourceRecordSets("ZOLD", null, null, 0)).thenReturn(List.of(old));
        StackResource resource = failedMoveRollback(owner, service, old);
        // ContinueUpdateRollback skipped the group.
        resource.setStatus("UPDATE_COMPLETE");

        // Nothing tells the leftover apart from an identical record written there later, so neither the
        // next commit's cleanup nor a delete removes it.
        assertNull(owner.updateCleanupPhysicalId(resource));
        assertFalse(owner.completeUpdate(resource).applicable());
        assertFalse(resource.getAttributes().containsKey("__FlociRoute53GroupOwedDeletes"));
        owner.delete(resource, "us-east-1");

        verify(service, times(1)).changeResourceRecordSets(eq("ZNEW"), any(), eq("CloudFormation delete Group"));
        verify(service).changeResourceRecordSets(eq("ZOLD"), any(), eq("CloudFormation delete Group"));
    }

    @Test
    void reApplyingAMoveAfterASkippedRollbackKeepsTheRecordItWrites() throws Exception {
        Route53Service service = mock(Route53Service.class);
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        ResourceRecordSet old = aRecord("www.example.test.", "192.0.2.1");
        StackResource resource = failedMoveRollback(owner, service, old);
        when(service.listResourceRecordSets("ZOLD", null, null, 0)).thenReturn(List.of(old));
        resource.setUpdateReplacePolicy(null);
        resource.setStatus("UPDATE_COMPLETE");

        // ContinueUpdateRollback skipped the group, the leftover record was deleted by hand, and the
        // move is applied again, writing the same record into ZNEW.
        owner.provision(resource, new ObjectMapper().readTree("""
                {"HostedZoneId": "ZNEW", "RecordSets": [
                  {"Name": "www.example.test", "Type": "A", "TTL": "300", "ResourceRecords": ["192.0.2.2"]}
                ]}
                """), groupContext("Group"));
        UpdateCleanupResult cleanup = owner.completeUpdate(resource);
        owner.clearUpdate(resource);

        assertTrue(cleanup.complete());
        verify(service).changeResourceRecordSets(eq("ZOLD"), any(), eq("CloudFormation delete Group"));
        // Only the failed rollback's delete went to ZNEW; the record the move wrote stays.
        verify(service, times(1)).changeResourceRecordSets(eq("ZNEW"), any(), eq("CloudFormation delete Group"));
        assertFalse(resource.getAttributes().containsKey("__FlociRoute53GroupOwedDeletes"));
        assertFalse(owner.hasReplacementUpdate(resource));
    }

    @Test
    void aLaterUpdateForgetsWhatASkippedRollbackLeftSoARetryNeedsOnlyItsOwn() throws Exception {
        Route53Service service = mock(Route53Service.class);
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);
        StackResource resource = failedMoveRollback(owner, service, aRecord("www.example.test.", "192.0.2.1"));
        // The ZNEW record stays changed outside the stack, so deleting it keeps failing.
        doThrow(new AwsException("InvalidChangeBatch", "values do not match", 400))
                .when(service).changeResourceRecordSets(eq("ZNEW"), any(), eq("CloudFormation delete Group"));

        // After a skip, a second move, to Z3, fails too, and its rollback cannot delete Z3's record once.
        resource.setStatus("UPDATE_COMPLETE");
        when(service.changeResourceRecordSets(eq("Z3"), any(), any())).thenReturn(new ChangeInfo("C3", "now", null));
        when(service.listResourceRecordSets("Z3", null, null, 0))
                .thenReturn(List.of(aRecord("www.example.test.", "192.0.2.3")));
        owner.provision(resource, new ObjectMapper().readTree("""
                {"HostedZoneId": "Z3", "RecordSets": [
                  {"Name": "www.example.test", "Type": "A", "TTL": "300", "ResourceRecords": ["192.0.2.3"]}
                ]}
                """), groupContext("Group"));
        doThrow(new AwsException("InvalidChangeBatch", "Z3 values do not match", 400)).doReturn(null)
                .when(service).changeResourceRecordSets(eq("Z3"), any(), eq("CloudFormation delete Group"));
        assertThrows(AwsException.class, () -> owner.rollbackUpdate(resource));
        resource.setStatus("UPDATE_FAILED");

        // The skipped rollback's ZNEW record was left behind by the second update, so the retry needs only
        // Z3's record, which its own rollback could not delete.
        String owed = resource.getAttributes().get("__FlociRoute53GroupOwedDeletes");
        assertTrue(owed.contains("Z3") && !owed.contains("ZNEW"), owed);
        assertTrue(owner.rollbackUpdate(resource));

        verify(service, times(2)).changeResourceRecordSets(eq("Z3"), any(), eq("CloudFormation delete Group"));
        verify(service, times(1)).changeResourceRecordSets(eq("ZNEW"), any(), eq("CloudFormation delete Group"));
        assertFalse(resource.getAttributes().containsKey("__FlociRoute53GroupOwedDeletes"));
        assertFalse(owner.completeUpdate(resource).applicable());
    }

    @Test
    void replacingAStubAnOlderFlociLeftIsNotAZoneChangeAndARollbackPutsTheStubBack() throws Exception {
        Route53Service service = mock(Route53Service.class);
        when(service.changeResourceRecordSets(eq("Z1"), any(), any())).thenReturn(new ChangeInfo("C1", "now", null));
        StackResource resource = recordGroup();
        resource.setPhysicalId("Group-0a1b2c3d");
        resource.getAttributes().put("Arn", "arn:aws:stub:::Group");
        Map<String, String> stub = Map.copyOf(resource.getAttributes());
        Route53CfnProvisioner owner = new Route53CfnProvisioner(service);

        owner.provision(resource, new ObjectMapper().readTree("""
                {"HostedZoneId": "Z1", "RecordSets": [
                  {"Name": "www.example.test", "Type": "A", "TTL": "300", "ResourceRecords": ["192.0.2.1"]}
                ]}
                """), groupContext("Group-0a1b2c3d"));

        assertEquals("Group", resource.getPhysicalId());
        assertFalse(owner.hasReplacementUpdate(resource));
        assertNull(owner.updateCleanupPhysicalId(resource));

        when(service.listResourceRecordSets("Z1", null, null, 0))
                .thenReturn(List.of(aRecord("www.example.test.", "192.0.2.1")));
        assertTrue(owner.rollbackUpdate(resource));

        assertEquals("Group-0a1b2c3d", resource.getPhysicalId());
        assertEquals(stub, resource.getAttributes());
        verify(service).changeResourceRecordSets(eq("Z1"), argThat(changes -> describe(changes)
                .equals(List.of("DELETE www.example.test."))), any());
    }

    @Test
    void recordGroupRejectsTwoRecordSetsWithOneNameTypeAndSetIdentifier() throws Exception {
        assertRecordGroupRejected("""
                {"HostedZoneId": "Z1", "RecordSets": [
                  {"Name": "www.example.test", "Type": "A", "ResourceRecords": ["192.0.2.1"]},
                  {"Name": "www.example.test.", "Type": "A", "ResourceRecords": ["192.0.2.2"]}
                ]}
                """);
    }

    @Test
    void deletingARecordGroupToleratesAZoneAlreadyGone() throws Exception {
        Route53Service service = mock(Route53Service.class);
        when(service.listResourceRecordSets("Z1", null, null, 0))
                .thenThrow(new AwsException("NoSuchHostedZone", "No hosted zone found with ID: Z1", 404));
        StackResource resource = existingGroup("Z1", "C1", aRecord("www.example.test.", "192.0.2.1"));

        new Route53CfnProvisioner(service).delete(resource, "us-east-1");

        verify(service, never()).changeResourceRecordSets(any(), any(), any());
    }

    private void assertRecordGroupRejected(String json) throws Exception {
        Route53Service service = mock(Route53Service.class);
        JsonNode props = new ObjectMapper().readTree(json);

        AwsException failure = assertThrows(AwsException.class,
                () -> new Route53CfnProvisioner(service).provision(recordGroup(), props, groupContext(null)));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(service, never()).changeResourceRecordSets(any(), any(), any());
    }

    private static StackResource recordGroup() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Group");
        resource.setResourceType("AWS::Route53::RecordSetGroup");
        return resource;
    }

    /** A group a create or an earlier update wrote into {@code zoneId} with change {@code changeId}. */
    private static StackResource existingGroup(String zoneId, String changeId, ResourceRecordSet... records)
            throws Exception {
        StackResource resource = recordGroup();
        resource.setPhysicalId("Group");
        resource.getAttributes().put("Id", changeId);
        resource.getAttributes().put("__FlociRoute53RecordZoneId", zoneId);
        resource.getAttributes().put("__FlociRoute53GroupRecords",
                new ObjectMapper().writeValueAsString(List.of(records)));
        return resource;
    }

    /**
     * A group created in ZOLD as {@code old} that an update has just moved to ZNEW, writing
     * {@link #movedRecord()} there; the update has neither committed nor rolled back.
     */
    private static StackResource movedGroup(Route53CfnProvisioner owner, Route53Service service,
                                            ResourceRecordSet old) throws Exception {
        StackResource resource = existingGroup("ZOLD", "C1", old);
        when(service.changeResourceRecordSets(eq("ZNEW"), any(), any())).thenReturn(new ChangeInfo("C2", "now", null));
        owner.provision(resource, new ObjectMapper().readTree("""
                {"HostedZoneId": "ZNEW", "RecordSets": [
                  {"Name": "www.example.test", "Type": "A", "TTL": "300", "ResourceRecords": ["192.0.2.2"]}
                ]}
                """), groupContext("Group"));
        return resource;
    }

    private static ResourceRecordSet movedRecord() {
        return aRecord("www.example.test.", "192.0.2.2");
    }

    /**
     * A moved group, under {@code UpdateReplacePolicy: Retain}, whose rollback could not delete the
     * record it wrote into ZNEW; the next delete there succeeds.
     */
    private static StackResource failedMoveRollback(Route53CfnProvisioner owner, Route53Service service,
                                                    ResourceRecordSet old) throws Exception {
        when(service.listResourceRecordSets("ZNEW", null, null, 0)).thenReturn(List.of(movedRecord()));
        StackResource resource = movedGroup(owner, service, old);
        resource.setUpdateReplacePolicy("Retain");
        doThrow(new AwsException("InvalidChangeBatch", "values do not match", 400)).doReturn(null)
                .when(service).changeResourceRecordSets(eq("ZNEW"), any(), eq("CloudFormation delete Group"));
        AwsException failure = assertThrows(AwsException.class, () -> owner.rollbackUpdate(resource));
        assertEquals("values do not match", failure.getMessage());
        // As the rollback walker marks a resource whose rollback failed.
        resource.setStatus("UPDATE_FAILED");
        return resource;
    }

    private static ResourceRecordSet aRecord(String name, String address) {
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName(name);
        record.setType("A");
        record.setTtl(300L);
        record.setRecords(List.of(new ResourceRecord(address)));
        return record;
    }

    private static List<String> describe(List<Map<String, Object>> changes) {
        return changes.stream()
                .map(change -> change.get("action") + " " + ((ResourceRecordSet) change.get("rrs")).getName())
                .toList();
    }

    private static ProvisionContext groupContext(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = recordEngine();
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return priorPhysicalId == null
                ? new ProvisionContext(engine, "us-east-1", "000000000000", "dns-stack")
                : new ProvisionContext(engine, "us-east-1", "000000000000", "dns-stack", priorPhysicalId);
    }

    @Test
    void healthCheckIsCreatedWithItsConfigTagsAndExactAttributes() throws Exception {
        Route53Service service = mock(Route53Service.class);
        when(service.createHealthCheck(any(), any(), any()))
                .thenAnswer(inv -> new HealthCheck("hc-1", inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        JsonNode props = new ObjectMapper().readTree("""
                {"HealthCheckConfig": {"Type": "HTTPS", "FullyQualifiedDomainName": "app.example.net",
                  "ResourcePath": "/", "Port": 443, "RequestInterval": 10, "FailureThreshold": 2},
                 "HealthCheckTags": [{"Key": "team", "Value": "web"}]}""");
        StackResource resource = new StackResource();
        resource.setLogicalId("Check");
        resource.setResourceType("AWS::Route53::HealthCheck");

        new Route53CfnProvisioner(service).provision(resource, props, healthCheckContext(null));

        assertEquals("hc-1", resource.getPhysicalId());
        assertEquals(Set.of("HealthCheckId"), publicAttributes(resource));
        assertEquals("hc-1", resource.getAttributes().get("HealthCheckId"));
        ArgumentCaptor<HealthCheckConfig> config = ArgumentCaptor.forClass(HealthCheckConfig.class);
        verify(service).createHealthCheck(argThat(ref -> ref.startsWith("dns-stack/Check/")), config.capture(), any());
        assertEquals("HTTPS", config.getValue().getType());
        assertEquals("app.example.net", config.getValue().getFullyQualifiedDomainName());
        assertEquals("/", config.getValue().getResourcePath());
        assertEquals(Integer.valueOf(443), config.getValue().getPort());
        assertEquals(Integer.valueOf(10), config.getValue().getRequestInterval());
        assertEquals(Integer.valueOf(2), config.getValue().getFailureThreshold());
        verify(service).changeTagsForResource("healthcheck", "hc-1",
                List.of(Map.of("Key", "team", "Value", "web")), List.of());
    }

    @Test
    void healthCheckUpdateKeepsTheCheckAndDropsRemovedTags() throws Exception {
        Route53Service service = mock(Route53Service.class);
        HealthCheckConfig priorConfig = new HealthCheckConfig();
        priorConfig.setType("HTTP");
        HealthCheck prior = new HealthCheck("hc-1", "ref", priorConfig, "");
        when(service.getHealthCheck("hc-1")).thenReturn(prior);
        when(service.updateHealthCheck(eq("hc-1"), any())).thenReturn(prior);
        when(service.listTagsForResource("healthcheck", "hc-1")).thenReturn(Map.of("old", "x"));
        JsonNode props = new ObjectMapper().readTree("""
                {"HealthCheckConfig": {"Type": "HTTP", "IPAddress": "10.0.0.5", "Port": 8080}}""");
        StackResource resource = new StackResource();
        resource.setLogicalId("Check");
        resource.setResourceType("AWS::Route53::HealthCheck");
        resource.setPhysicalId("hc-1");

        new Route53CfnProvisioner(service).provision(resource, props, healthCheckContext("hc-1"));

        assertEquals("hc-1", resource.getPhysicalId());
        verify(service, never()).createHealthCheck(any(), any(), any());
        verify(service).updateHealthCheck(eq("hc-1"), argThat(cfg -> "10.0.0.5".equals(cfg.getIpAddress())
                && Integer.valueOf(8080).equals(cfg.getPort())));
        verify(service).changeTagsForResource("healthcheck", "hc-1", List.of(), List.of("old"));
    }

    @Test
    void healthCheckTypeChangeReplacesTheCheckAndDeletesTheOldOneOnlyOnceTheUpdateCommits() throws Exception {
        Route53Service service = replacingService();
        StackResource resource = existingCheck();

        new Route53CfnProvisioner(service).provision(resource, healthCheckProps("TCP", null), healthCheckContext("hc-1"));

        assertEquals("hc-2", resource.getPhysicalId());
        assertEquals("hc-2", resource.getAttributes().get("HealthCheckId"));
        verify(service, never()).updateHealthCheck(any(), any());
        verify(service, never()).deleteHealthCheck(any());

        Route53CfnProvisioner provisioner = new Route53CfnProvisioner(service);
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals("hc-1", provisioner.updateCleanupPhysicalId(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(service).deleteHealthCheck("hc-1");
    }

    @Test
    void healthCheckRequestIntervalChangeReplacesTheCheck() throws Exception {
        Route53Service service = replacingService();
        StackResource resource = existingCheck();

        new Route53CfnProvisioner(service).provision(resource, healthCheckProps("HTTP", 10), healthCheckContext("hc-1"));

        assertEquals("hc-2", resource.getPhysicalId());
        verify(service, never()).updateHealthCheck(any(), any());
    }

    @Test
    void healthCheckMeasureLatencyChangeReplacesTheCheck() throws Exception {
        Route53Service service = replacingService();
        StackResource resource = existingCheck();
        new Route53CfnProvisioner(service).provision(resource, healthCheckProps("HTTP", null), healthCheckContext("hc-1"));
        assertEquals("hc-1", resource.getPhysicalId());

        JsonNode props = new ObjectMapper().readTree("""
                {"HealthCheckConfig": {"Type": "HTTP", "IPAddress": "10.0.0.5", "Port": 80, "MeasureLatency": true}}""");
        new Route53CfnProvisioner(service).provision(resource, props, healthCheckContext("hc-1"));

        assertEquals("hc-2", resource.getPhysicalId());
    }

    @Test
    void retainedHealthCheckIsKeptWhenAReplacementCommits() throws Exception {
        Route53Service service = replacingService();
        StackResource resource = existingCheck();
        resource.setUpdateReplacePolicy("Retain");
        Route53CfnProvisioner provisioner = new Route53CfnProvisioner(service);

        provisioner.provision(resource, healthCheckProps("TCP", null), healthCheckContext("hc-1"));

        assertNull(provisioner.updateCleanupPhysicalId(resource));
        provisioner.completeUpdate(resource);
        verify(service, never()).deleteHealthCheck("hc-1");
    }

    @Test
    void rollingBackAReplacementDeletesTheNewCheckAndPointsBackAtTheOldOne() throws Exception {
        Route53Service service = replacingService();
        StackResource resource = existingCheck();
        Route53CfnProvisioner provisioner = new Route53CfnProvisioner(service);
        provisioner.provision(resource, healthCheckProps("TCP", null), healthCheckContext("hc-1"));

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("hc-1", resource.getPhysicalId());
        assertEquals("hc-1", resource.getAttributes().get("HealthCheckId"));
        verify(service).deleteHealthCheck("hc-2");
        verify(service, never()).deleteHealthCheck("hc-1");
    }

    @Test
    void rollingBackAnInPlaceUpdatePutsBackTheConfigAndTags() throws Exception {
        Route53Service service = mock(Route53Service.class);
        HealthCheckConfig priorConfig = new HealthCheckConfig();
        priorConfig.setType("HTTP");
        priorConfig.setIpAddress("10.0.0.5");
        priorConfig.setPort(80);
        HealthCheck prior = new HealthCheck("hc-1", "ref", priorConfig, "");
        when(service.getHealthCheck("hc-1")).thenReturn(prior);
        when(service.updateHealthCheck(eq("hc-1"), any())).thenReturn(prior);
        when(service.listTagsForResource("healthcheck", "hc-1")).thenReturn(Map.of("team", "web"));
        StackResource resource = existingCheck();
        Route53CfnProvisioner provisioner = new Route53CfnProvisioner(service);
        JsonNode props = new ObjectMapper().readTree("""
                {"HealthCheckConfig": {"Type": "HTTP", "IPAddress": "10.0.0.9", "Port": 8080}}""");
        provisioner.provision(resource, props, healthCheckContext("hc-1"));
        when(service.listTagsForResource("healthcheck", "hc-1")).thenReturn(Map.of());

        assertTrue(provisioner.rollbackUpdate(resource));

        verify(service).updateHealthCheck(eq("hc-1"), argThat(cfg -> "10.0.0.5".equals(cfg.getIpAddress())
                && Integer.valueOf(80).equals(cfg.getPort())));
        verify(service).changeTagsForResource("healthcheck", "hc-1",
                List.of(Map.of("Key", "team", "Value", "web")), List.of());
        assertFalse(resource.getAttributes().containsKey(CfnRollback.HEALTH_CHECK_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void anInPlaceUpdateThatFailsPartWayPutsTheCheckBack() throws Exception {
        Route53Service service = mock(Route53Service.class);
        HealthCheckConfig priorConfig = new HealthCheckConfig();
        priorConfig.setType("HTTP");
        priorConfig.setIpAddress("10.0.0.5");
        HealthCheck prior = new HealthCheck("hc-1", "ref", priorConfig, "");
        when(service.getHealthCheck("hc-1")).thenReturn(prior);
        when(service.updateHealthCheck(eq("hc-1"), any())).thenReturn(prior);
        when(service.listTagsForResource("healthcheck", "hc-1")).thenReturn(Map.of());
        Mockito.doThrow(new AwsException("Throttling", "slow down", 400)).doNothing()
                .when(service).changeTagsForResource(eq("healthcheck"), eq("hc-1"), any(), any());
        StackResource resource = existingCheck();
        JsonNode props = new ObjectMapper().readTree("""
                {"HealthCheckConfig": {"Type": "HTTP", "IPAddress": "10.0.0.9"},
                 "HealthCheckTags": [{"Key": "team", "Value": "web"}]}""");

        assertThrows(AwsException.class, () -> new Route53CfnProvisioner(service)
                .provision(resource, props, healthCheckContext("hc-1")));

        verify(service).updateHealthCheck(eq("hc-1"), argThat(cfg -> "10.0.0.5".equals(cfg.getIpAddress())));
        assertEquals("true", resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
    }

    @Test
    void aNewHealthCheckWhoseTaggingFailsIsDeleted() throws Exception {
        Route53Service service = mock(Route53Service.class);
        when(service.createHealthCheck(any(), any(), any()))
                .thenAnswer(inv -> new HealthCheck("hc-1", inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        Mockito.doThrow(new AwsException("Throttling", "slow down", 400))
                .when(service).changeTagsForResource(any(), any(), any(), any());
        JsonNode props = new ObjectMapper().readTree("""
                {"HealthCheckConfig": {"Type": "TCP", "IPAddress": "10.0.0.5", "Port": 22},
                 "HealthCheckTags": [{"Key": "team", "Value": "web"}]}""");
        StackResource resource = new StackResource();
        resource.setLogicalId("Check");
        resource.setResourceType("AWS::Route53::HealthCheck");

        assertThrows(AwsException.class, () -> new Route53CfnProvisioner(service)
                .provision(resource, props, healthCheckContext(null)));

        verify(service).deleteHealthCheck("hc-1");
        assertNull(resource.getPhysicalId());
    }

    @Test
    void healthCheckWithAnUnknownTypeIsRejected() throws Exception {
        Route53Service service = mock(Route53Service.class);
        JsonNode props = new ObjectMapper().readTree("{\"HealthCheckConfig\": {\"Type\": \"PING\"}}");
        StackResource resource = new StackResource();
        resource.setLogicalId("Check");
        resource.setResourceType("AWS::Route53::HealthCheck");

        AwsException error = assertThrows(AwsException.class, () -> new Route53CfnProvisioner(service)
                .provision(resource, props, healthCheckContext(null)));
        assertEquals("ValidationError", error.getErrorCode());
        verifyNoInteractions(service);
    }

    @Test
    void otherRoute53TypesKeepTheDefaultRollback() {
        StackResource zone = new StackResource();
        zone.setLogicalId("Zone");
        zone.setResourceType("AWS::Route53::HostedZone");

        assertFalse(new Route53CfnProvisioner(mock(Route53Service.class)).rollbackUpdate(zone));
    }

    /** A service holding check hc-1 (HTTP on 10.0.0.5:80) that creates hc-2. */
    private static Route53Service replacingService() {
        Route53Service service = mock(Route53Service.class);
        HealthCheckConfig priorConfig = new HealthCheckConfig();
        priorConfig.setType("HTTP");
        priorConfig.setIpAddress("10.0.0.5");
        priorConfig.setPort(80);
        when(service.getHealthCheck("hc-1")).thenReturn(new HealthCheck("hc-1", "ref", priorConfig, ""));
        when(service.createHealthCheck(any(), any(), any()))
                .thenAnswer(inv -> new HealthCheck("hc-2", inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        return service;
    }

    private static StackResource existingCheck() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Check");
        resource.setResourceType("AWS::Route53::HealthCheck");
        resource.setPhysicalId("hc-1");
        resource.getAttributes().put("HealthCheckId", "hc-1");
        return resource;
    }

    private static JsonNode healthCheckProps(String type, Integer requestInterval) throws Exception {
        String interval = requestInterval == null ? "" : ", \"RequestInterval\": " + requestInterval;
        return new ObjectMapper().readTree("{\"HealthCheckConfig\": {\"Type\": \"" + type
                + "\", \"IPAddress\": \"10.0.0.5\", \"Port\": 80" + interval + "}}");
    }

    private static Set<String> publicAttributes(StackResource resource) {
        Set<String> keys = new HashSet<>(resource.getAttributes().keySet());
        keys.removeIf(key -> key.startsWith("__Floci"));
        return keys;
    }

    @Test
    void healthCheckWithoutAConfigTypeIsRejected() throws Exception {
        Route53Service service = mock(Route53Service.class);
        JsonNode props = new ObjectMapper().readTree("{\"HealthCheckConfig\": {\"Port\": 80}}");
        StackResource resource = new StackResource();
        resource.setLogicalId("Check");
        resource.setResourceType("AWS::Route53::HealthCheck");

        assertThrows(AwsException.class, () -> new Route53CfnProvisioner(service)
                .provision(resource, props, healthCheckContext(null)));
        verifyNoInteractions(service);
    }

    @Test
    void deletingAHealthCheckAlreadyGoneIsTolerated() {
        Route53Service service = mock(Route53Service.class);
        Mockito.doThrow(new AwsException("NoSuchHealthCheck", "gone", 404))
                .when(service).deleteHealthCheck("hc-1");
        StackResource resource = new StackResource();
        resource.setLogicalId("Check");
        resource.setResourceType("AWS::Route53::HealthCheck");
        resource.setPhysicalId("hc-1");

        new Route53CfnProvisioner(service).delete(resource, "us-east-1");

        verify(service).deleteHealthCheck("hc-1");
    }

    private static ProvisionContext healthCheckContext(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = recordEngine();
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return priorPhysicalId == null
                ? new ProvisionContext(engine, "us-east-1", "000000000000", "dns-stack")
                : new ProvisionContext(engine, "us-east-1", "000000000000", "dns-stack", priorPhysicalId);
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
