package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::EC2::VPC}. */
class Ec2VpcCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";

    private Ec2Service ec2;
    private Ec2VpcCfnProvisioner provisioner;
    private ProvisionContext ctx;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        provisioner = new Ec2VpcCfnProvisioner(ec2);
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        ctx = new ProvisionContext(engine, REGION, "000000000000", "my-stack");
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

    private StackResource provision(String priorPhysicalId, String json) {
        StackResource r = new StackResource();
        r.setLogicalId("Vpc");
        r.setResourceType("AWS::EC2::VPC");
        r.setPhysicalId(priorPhysicalId);
        provisioner.provision(r, props(json), ctx);
        return r;
    }

    @Test
    void refIsTheVpcIdAndGetAttExposesTheDocumentedAttributes() {
        when(ec2.createVpc(anyString(), anyString(), anyBoolean())).thenReturn(vpc("vpc-new", "10.0.0.0/16"));
        SecurityGroup def = new SecurityGroup();
        def.setGroupId("sg-default");
        def.setVpcId("vpc-new");
        when(ec2.describeSecurityGroups(REGION, List.of(), List.of("default"), Map.of()))
                .thenReturn(List.of(def));

        StackResource r = provision(null, """
                {"CidrBlock": "10.0.0.0/16"}""");

        assertEquals("vpc-new", r.getPhysicalId());
        assertEquals("vpc-new", r.getAttributes().get("VpcId"));
        assertEquals("10.0.0.0/16", r.getAttributes().get("CidrBlock"));
        assertEquals("sg-default", r.getAttributes().get("DefaultSecurityGroup"));
    }

    @Test
    void anUnchangedVpcIsReusedInsteadOfCreatedAgain() {
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of()))
                .thenReturn(List.of(vpc("vpc-existing", "10.0.0.0/16")));

        StackResource r = provision("vpc-existing", """
                {"CidrBlock": "10.0.0.0/16"}""");

        verify(ec2, never()).createVpc(anyString(), anyString(), anyBoolean());
        assertEquals("vpc-existing", r.getPhysicalId(),
                "reusing must keep the id every subnet and route table already references");
    }

    @Test
    void aChangedCidrBlockCreatesAReplacement() {
        when(ec2.describeVpcs(REGION, List.of("vpc-existing"), Map.of()))
                .thenReturn(List.of(vpc("vpc-existing", "10.0.0.0/16")));
        when(ec2.createVpc(anyString(), anyString(), anyBoolean())).thenReturn(vpc("vpc-replaced", "10.1.0.0/16"));

        StackResource r = provision("vpc-existing", """
                {"CidrBlock": "10.1.0.0/16"}""");

        verify(ec2).createVpc(REGION, "10.1.0.0/16", false);
        assertEquals("vpc-replaced", r.getPhysicalId());
    }

    @Test
    void aVpcDeletedOutOfBandFallsBackToCreate() {
        when(ec2.describeVpcs(REGION, List.of("vpc-gone"), Map.of()))
                .thenThrow(new AwsException("InvalidVpcID.NotFound", "not found", 400));
        when(ec2.createVpc(anyString(), anyString(), anyBoolean())).thenReturn(vpc("vpc-fresh", "10.0.0.0/16"));

        StackResource r = provision("vpc-gone", """
                {"CidrBlock": "10.0.0.0/16"}""");

        verify(ec2).createVpc(REGION, "10.0.0.0/16", false);
        assertEquals("vpc-fresh", r.getPhysicalId());
    }
}
