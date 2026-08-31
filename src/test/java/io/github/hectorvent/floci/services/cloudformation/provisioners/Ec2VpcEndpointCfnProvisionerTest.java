package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    private static final Instant CREATED_AT = Instant.parse("2026-08-13T10:15:30Z");

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2VpcEndpointCfnProvisioner provisioner = new Ec2VpcEndpointCfnProvisioner(ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // Scalars resolve to their text; {"Ref": "X"} resolves to a fake physical id, which is
        // enough to prove properties go through the engine instead of being read raw.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> fakeResolve(inv.getArgument(0)));
        when(engine.resolveNode(any())).thenAnswer(inv -> fakeResolveNode(inv.getArgument(0)));
        // Same contract as the real resolveJsonAttribute: resolve the tree, then unwrap a
        // textual result rather than re-serialising it (#2317's double-encoding trap).
        when(engine.resolveJsonAttribute(any())).thenAnswer(inv -> {
            JsonNode resolved = fakeResolveNode(inv.getArgument(0));
            if (resolved == null || resolved.isNull() || resolved.isMissingNode()) {
                return null;
            }
            return resolved.isTextual() ? resolved.asText() : resolved.toString();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    /** The subset of intrinsic handling these tests need. */
    private String fakeResolve(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject() && node.has("Ref")) {
            String ref = node.get("Ref").asText();
            // Models a template parameter carrying a boolean value.
            return "EnableDns".equals(ref) ? "true" : "resolved-" + ref;
        }
        if (node.isObject() && node.has("Fn::Sub")) {
            return node.get("Fn::Sub").asText()
                    .replace("${AWS::Region}", "us-east-1")
                    .replace("${AWS::AccountId}", "000000000000");
        }
        return node.asText();
    }

    /** Mirrors CloudFormationTemplateEngine.resolveNode: intrinsics collapse to a TextNode. */
    private JsonNode fakeResolveNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isObject()) {
            if (node.has("Ref") || node.has("Fn::Sub")) {
                return TextNode.valueOf(fakeResolve(node));
            }
            ObjectNode resolved = mapper.createObjectNode();
            node.fields().forEachRemaining(e -> resolved.set(e.getKey(), fakeResolveNode(e.getValue())));
            return resolved;
        }
        if (node.isArray()) {
            ArrayNode arr = mapper.createArrayNode();
            node.forEach(item -> arr.add(fakeResolveNode(item)));
            return arr;
        }
        return node;
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
        e.setCreationTimestamp(CREATED_AT);
        return e;
    }

    @Test
    void gatewayEndpointSetsPhysicalIdAndResolvesRefs() {
        when(ec2.createVpcEndpoint(eq("us-east-1"), eq("resolved-Vpc"), eq("com.amazonaws.us-east-1.s3"),
                eq("Gateway"), eq(List.of("resolved-Rt")), eq(List.of()), eq(List.of()), isNull(), isNull(), anyList()))
                .thenReturn(endpoint("vpce-123"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("ServiceName", "com.amazonaws.us-east-1.s3");
        props.set("VpcId", mapper.createObjectNode().put("Ref", "Vpc"));
        props.putArray("RouteTableIds").add(mapper.createObjectNode().put("Ref", "Rt"));

        provisioner.provision(r, props, ctx());

        assertEquals("vpce-123", r.getPhysicalId());
        assertEquals("vpce-123", r.getAttributes().get("Id"));
        assertEquals("2026-08-13T10:15:30.000Z", r.getAttributes().get("CreationTimestamp"));
    }

    @Test
    void privateDnsEnabledResolvesThroughEngine() {
        when(ec2.createVpcEndpoint(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyList(), anyList(), eq(Boolean.TRUE), any(), anyList()))
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
                eq("Interface"), anyList(), anyList(), anyList(), eq(Boolean.TRUE), any(), anyList());
    }

    @Test
    void policyDocumentIsSerialisedAndPassedThrough() {
        // The template declares PolicyDocument as JSON, so it arrives as an object node.
        // Passing null here would drop a CloudFormation-declared policy exactly as the
        // EC2 API path used to.
        when(ec2.createVpcEndpoint(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyList(), anyList(), any(), any(), anyList()))
                .thenReturn(endpoint("vpce-policy"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("VpcId", "vpc-1")
                .put("ServiceName", "com.amazonaws.us-east-1.s3");
        ObjectNode statement = mapper.createObjectNode()
                .put("Effect", "Allow")
                .put("Principal", "*")
                .put("Action", "s3:GetObject")
                .put("Resource", "*");
        ObjectNode policy = mapper.createObjectNode().put("Version", "2012-10-17");
        policy.putArray("Statement").add(statement);
        props.set("PolicyDocument", policy);

        provisioner.provision(r, props, ctx());

        verify(ec2).createVpcEndpoint(eq("us-east-1"), eq("vpc-1"), eq("com.amazonaws.us-east-1.s3"),
                anyString(), anyList(), anyList(), anyList(), any(),
                eq(policy.toString()), anyList());
    }

    @Test
    void policyDocumentIntrinsicsAreResolvedNotStoredAsTemplateSyntax() {
        // A PolicyDocument carrying Fn::Sub/Ref must reach Ec2Service resolved, or
        // DescribeVpcEndpoints hands the caller back literal template text.
        when(ec2.createVpcEndpoint(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyList(), anyList(), any(), any(), anyList()))
                .thenReturn(endpoint("vpce-sub"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("VpcId", "vpc-1")
                .put("ServiceName", "com.amazonaws.us-east-1.s3");
        ObjectNode statement = mapper.createObjectNode()
                .put("Effect", "Allow")
                .put("Action", "s3:GetObject");
        statement.set("Resource",
                mapper.createObjectNode().put("Fn::Sub", "arn:aws:s3:::${AWS::Region}-artifacts/*"));
        statement.set("Principal", mapper.createObjectNode().put("Ref", "AppRole"));
        ObjectNode policy = mapper.createObjectNode().put("Version", "2012-10-17");
        policy.putArray("Statement").add(statement);
        props.set("PolicyDocument", policy);

        provisioner.provision(r, props, ctx());

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(ec2).createVpcEndpoint(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyList(), anyList(), any(), stored.capture(), anyList());
        String document = stored.getValue();
        assertTrue(document.contains("arn:aws:s3:::us-east-1-artifacts/*"), document);
        assertTrue(document.contains("resolved-AppRole"), document);
        assertFalse(document.contains("Fn::Sub"), document);
        assertFalse(document.contains("\"Ref\""), document);
    }

    @Test
    void anAbsentPolicyDocumentStaysNull() {
        when(ec2.createVpcEndpoint(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyList(), anyList(), any(), isNull(), anyList()))
                .thenReturn(endpoint("vpce-nopolicy"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("VpcId", "vpc-1")
                .put("ServiceName", "com.amazonaws.us-east-1.s3");

        provisioner.provision(r, props, ctx());

        verify(ec2).createVpcEndpoint(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyList(), anyList(), any(), isNull(), anyList());
    }

    @Test
    void updateReplacesAndDeletesPreviousEndpoint() {
        when(ec2.createVpcEndpoint(anyString(), anyString(), anyString(), anyString(),
                anyList(), anyList(), anyList(), any(), any(), anyList()))
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
