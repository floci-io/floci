package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.HostedZoneVpc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Route53CfnProvisionerTest {
    @Test
    void createsBackingPrivateZoneAndExactAttributes() {
        Route53Service service = mock(Route53Service.class);
        HostedZone zone = new HostedZone("Z123456789", "ssm.us-east-1.amazonaws.com.",
                "caller", null, true);
        when(service.createHostedZone(eq("ssm.us-east-1.amazonaws.com"), eq("dns-stack/Zone"),
                eq("central endpoint zone"), eq(true), any())).thenReturn(
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
                eq("central endpoint zone"), eq(true),
                eq(List.of(new HostedZoneVpc("vpc-123", "us-east-1"))));
        verify(service).changeTagsForResource("hostedzone", "Z123456789",
                List.of(java.util.Map.of("Key", "Accelerator", "Value", "AWSAccelerator")), List.of());
    }

    @Test
    void backfillsBackingZoneUsingExistingPhysicalId() {
        Route53Service service = mock(Route53Service.class);
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

        verify(service).ensureHostedZone("Z68119D97-4AB", "ssm.us-east-1.amazonaws.com",
                "dns-stack/Zone", null, false, List.of());
        verify(service, never()).createHostedZone(any(), any(), any(), eq(false), any());
        assertEquals("Z68119D97-4AB", resource.getAttributes().get("Id"));
        assertEquals(Set.of("Id", "NameServers"), resource.getAttributes().keySet());
    }
}
