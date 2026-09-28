package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The EKS control-plane types in isolation: the physical id (Ref) and Fn::GetAtt keys each
 * publishes, the cluster name flowing into a nodegroup delete, and the already-gone tolerance.
 */
class EksCfnProvisionerTest {

    private final EksService eks = mock(EksService.class);
    private final EksCfnProvisioner provisioner = new EksCfnProvisioner(eks);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void clusterRefIsItsNameAndItPublishesTheResolvableReadOnlyAttributes() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        created.setEndpoint("https://prod.eks.amazonaws.com");
        created.setCertificateAuthority(new CertificateAuthority("LS0tLS1CRUdJTg=="));
        ClusterIdentity identity = new ClusterIdentity();
        identity.setOidc(new OidcIdentity("https://oidc.eks.us-east-1.amazonaws.com/id/ABC"));
        created.setIdentity(identity);
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);

        StackResource r = resource("AWS::EKS::Cluster", "Control");
        provisioner.provision(r, props("""
                {"Name": "prod", "RoleArn": "arn:aws:iam::000000000000:role/eks", "Version": "1.31"}
                """), ctx());

        assertEquals("prod", r.getPhysicalId());
        assertEquals(Set.of("Arn", "Endpoint", "CertificateAuthorityData", "OpenIdConnectIssuerUrl"),
                r.getAttributes().keySet());
        assertEquals("arn:aws:eks:us-east-1:000000000000:cluster/prod", r.getAttributes().get("Arn"));
        assertEquals("LS0tLS1CRUdJTg==", r.getAttributes().get("CertificateAuthorityData"));
        assertEquals("https://oidc.eks.us-east-1.amazonaws.com/id/ABC",
                r.getAttributes().get("OpenIdConnectIssuerUrl"));
    }

    @Test
    void absentClusterNameIsGeneratedFromStackAndLogicalId() throws Exception {
        Cluster created = new Cluster();
        created.setName("my-stack-Control-abc123");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/generated");
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);

        StackResource r = resource("AWS::EKS::Cluster", "Control");
        provisioner.provision(r, props("{}"), ctx());

        ArgumentCaptor<CreateClusterRequest> request = ArgumentCaptor.forClass(CreateClusterRequest.class);
        verify(eks).createCluster(request.capture());
        assertTrue(request.getValue().getName().matches("my-stack-Control-[0-9a-f]{12}"),
                request.getValue().getName());
    }

    @Test
    void nodegroupRefIsTheCompositeIdAndItStoresNamesForDelete() throws Exception {
        Nodegroup created = new Nodegroup();
        created.setNodegroupName("workers");
        created.setClusterName("prod");
        created.setNodegroupArn("arn:aws:eks:us-east-1:000000000000:nodegroup/prod/workers/uuid");
        when(eks.createNodeGroup(eq("prod"), any(Nodegroup.class))).thenReturn(created);

        StackResource r = resource("AWS::EKS::Nodegroup", "Workers");
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "NodegroupName": "workers",
                 "NodeRole": "arn:aws:iam::000000000000:role/nodes", "Subnets": ["subnet-1", "subnet-2"]}
                """), ctx());

        // Ref and Id are the composite clusterName/nodegroupName, matching AWS.
        assertEquals("prod/workers", r.getPhysicalId());
        assertEquals("prod/workers", r.getAttributes().get("Id"));
        assertEquals("prod", r.getAttributes().get("ClusterName"));
        assertEquals("workers", r.getAttributes().get("NodegroupName"));
        assertEquals("arn:aws:eks:us-east-1:000000000000:nodegroup/prod/workers/uuid",
                r.getAttributes().get("Arn"));
        ArgumentCaptor<Nodegroup> request = ArgumentCaptor.forClass(Nodegroup.class);
        verify(eks).createNodeGroup(eq("prod"), request.capture());
        assertEquals(List.of("subnet-1", "subnet-2"), request.getValue().getSubnets());
    }

    @Test
    void deleteRemovesTheClusterByPhysicalId() {
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        r.setPhysicalId("prod");

        provisioner.delete(r, "us-east-1");

        verify(eks).deleteCluster("prod");
    }

    @Test
    void deleteRemovesTheNodegroupUsingTheStoredNames() {
        StackResource r = resource("AWS::EKS::Nodegroup", "Workers");
        r.setPhysicalId("prod/workers");
        r.getAttributes().put("ClusterName", "prod");
        r.getAttributes().put("NodegroupName", "workers");

        provisioner.delete(r, "us-east-1");

        verify(eks).deleteNodeGroup("prod", "workers");
    }

    @Test
    void deleteToleratesAClusterAlreadyGone() {
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        r.setPhysicalId("prod");
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(eks).deleteCluster("prod");

        assertDoesNotThrow(() -> provisioner.delete(r, "us-east-1"));
    }

    @Test
    void deletePropagatesAnUnexpectedClusterError() {
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        r.setPhysicalId("prod");
        doThrow(new AwsException("ResourceInUseException", "in use", 409))
                .when(eks).deleteCluster("prod");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete(r, "us-east-1"));
        assertEquals("ResourceInUseException", failure.getErrorCode());
    }

    @Test
    void deleteSkipsANodegroupWithNoStoredClusterName() {
        StackResource r = resource("AWS::EKS::Nodegroup", "Workers");
        r.setPhysicalId("workers");

        provisioner.delete(r, "us-east-1");

        verify(eks, never()).deleteNodeGroup(any(), any());
    }

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            List<String> out = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(element -> out.add(element.asText()));
            }
            return out;
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
