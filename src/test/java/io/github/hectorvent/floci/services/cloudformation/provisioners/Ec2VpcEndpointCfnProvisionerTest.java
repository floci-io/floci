package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The VPC-endpoint CFN provisioner in isolation, mocking only Ec2Service. */
class Ec2VpcEndpointCfnProvisionerTest {

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2VpcEndpointCfnProvisioner provisioner = new Ec2VpcEndpointCfnProvisioner(ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // Scalars resolve to their text; {"Ref": "X"} resolves to a fake physical id, which is
        // enough to prove properties go through the engine instead of being read raw.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            if (node == null) {
                return null;
            }
            if (node.isObject() && node.has("Ref")) {
                String ref = node.get("Ref").asText();
                // Models a template parameter carrying a boolean value.
                return "EnableDns".equals(ref) ? "true" : "resolved-" + ref;
            }
            return node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("S3Endpoint");
        r.setResourceType("AWS::EC2::VPCEndpoint");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private VpcEndpoint endpoint(String id) {
        VpcEndpoint e = new VpcEndpoint();
        e.setVpcEndpointId(id);
        return e;
    }

    @Test
    void gatewayEndpointSetsPhysicalIdAndResolvesRefs() {
        when(ec2.createVpcEndpoint(eq("us-east-1"), eq("resolved-Vpc"), eq("com.amazonaws.us-east-1.s3"),
                eq("Gateway"), eq(List.of("resolved-Rt")), eq(List.of()), eq(List.of()), isNull(), anyList()))
                .thenReturn(endpoint("vpce-123"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("ServiceName", "com.amazonaws.us-east-1.s3");
        props.set("VpcId", mapper.createObjectNode().put("Ref", "Vpc"));
        props.putArray("RouteTableIds").add(mapper.createObjectNode().put("Ref", "Rt"));

        provisioner.provision(r, props, ctx());

        assertEquals("vpce-123", r.getPhysicalId());
        assertEquals("vpce-123", r.getAttributes().get("Id"));
    }

    @Test
    void privateDnsEnabledResolvesThroughEngine() {
        when(ec2.createVpcEndpoint(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyList(), anyList(), eq(Boolean.TRUE), anyList()))
                .thenReturn(endpoint("vpce-dns"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("VpcId", "vpc-1")
                .put("ServiceName", "com.amazonaws.us-east-1.ecr.api")
                .put("VpcEndpointType", "Interface");
        // A Ref, not a literal boolean: raw props.asBoolean(false) would silently yield false.
        props.set("PrivateDnsEnabled", mapper.createObjectNode().put("Ref", "EnableDns"));

        provisioner.provision(r, props, ctx());

        assertEquals("vpce-dns", r.getPhysicalId());
        verify(ec2).createVpcEndpoint(eq("us-east-1"), eq("vpc-1"), eq("com.amazonaws.us-east-1.ecr.api"),
                eq("Interface"), anyList(), anyList(), anyList(), eq(Boolean.TRUE), anyList());
    }

    @Test
    void updateReplacesAndDeletesPreviousEndpoint() {
        when(ec2.createVpcEndpoint(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyList(), anyList(), any(), anyList()))
                .thenReturn(endpoint("vpce-new"));
        StackResource r = resource();
        r.setPhysicalId("vpce-old");
        ObjectNode props = mapper.createObjectNode()
                .put("VpcId", "vpc-1")
                .put("ServiceName", "com.amazonaws.us-east-1.s3");

        provisioner.provision(r, props, ctx());

        assertEquals("vpce-new", r.getPhysicalId());
        verify(ec2).deleteVpcEndpoints("us-east-1", List.of("vpce-old"));
    }

    @Test
    void deleteDelegatesToService() {
        provisioner.delete("AWS::EC2::VPCEndpoint", "vpce-123", "us-east-1");
        verify(ec2).deleteVpcEndpoints("us-east-1", List.of("vpce-123"));
    }
}
