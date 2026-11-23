package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.BlockStorage;
import io.floci.aws.services.eks.model.CertificateAuthority;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterIdentity;
import io.floci.aws.services.eks.model.ComputeConfig;
import io.floci.aws.services.eks.model.CreateClusterRequest;
import io.floci.aws.services.eks.model.ElasticLoadBalancing;
import io.floci.aws.services.eks.model.KubernetesNetworkConfig;
import io.floci.aws.services.eks.model.Logging;
import io.floci.aws.services.eks.model.Nodegroup;
import io.floci.aws.services.eks.model.OidcIdentity;
import io.floci.aws.services.eks.model.StorageConfig;
import io.floci.aws.services.eks.model.UpdateClusterConfigRequest;
import io.floci.aws.services.eks.model.UpgradePolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.times;
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

    private static final String PROD_ROLE = "{\"Name\": \"prod\", \"RoleArn\": \"arn:aws:iam::000000000000:role/eks\", ";
    private static final String NODE_ROLE = "arn:aws:iam::000000000000:role/eks-auto-node";
    /** EKS Auto Mode on: both built-in pools with their node role, block storage and load balancing. */
    private static final String AUTO_MODE = "\"ComputeConfig\": {\"Enabled\": true, "
            + "\"NodePools\": [\"general-purpose\", \"system\"], \"NodeRoleArn\": \"" + NODE_ROLE + "\"}, "
            + "\"StorageConfig\": {\"BlockStorage\": {\"Enabled\": true}}, "
            + "\"KubernetesNetworkConfig\": {\"ElasticLoadBalancing\": {\"Enabled\": true}}";

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
        assertEquals(Set.of("Arn", "Endpoint", "CertificateAuthorityData", "OpenIdConnectIssuerUrl",
                        "__FlociEksClusterTagKeys"),
                r.getAttributes().keySet());
        assertEquals("arn:aws:eks:us-east-1:000000000000:cluster/prod", r.getAttributes().get("Arn"));
        assertEquals("LS0tLS1CRUdJTg==", r.getAttributes().get("CertificateAuthorityData"));
        assertEquals("https://oidc.eks.us-east-1.amazonaws.com/id/ABC",
                r.getAttributes().get("OpenIdConnectIssuerUrl"));
    }

    @Test
    void aTagsOnlyUpdateRollsBackNoClusterConfig() throws Exception {
        // A tags-only update saves no cluster config, so its rollback writes none back over another caller's.
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        provisioner.provision(r, props("{\"Name\": \"prod\", \"RoleArn\": \"arn:aws:iam::000000000000:role/eks\"}"),
                ctx());
        created.setTags(new HashMap<>(Map.of("team", "a")));
        created.setLogging(new Logging());
        when(eks.describeCluster("prod")).thenReturn(created);
        JsonNode before = props("{\"Name\": \"prod\", \"RoleArn\": \"arn:aws:iam::000000000000:role/eks\", "
                + "\"Tags\": [{\"Key\": \"team\", \"Value\": \"a\"}]}");
        JsonNode after = props("{\"Name\": \"prod\", \"RoleArn\": \"arn:aws:iam::000000000000:role/eks\", "
                + "\"Tags\": [{\"Key\": \"team\", \"Value\": \"b\"}]}");
        ProvisionContext update = new ProvisionContext(ctx().engine(), "us-east-1", "000000000000", "my-stack",
                "prod", event -> { }, before, PropertyChanges.between("AWS::EKS::Cluster", true, before, after));
        provisioner.provision(r, after, update);
        assertTrue(provisioner.rollbackUpdate(r));
        verify(eks, never()).restoreClusterConfig(any(), any(), any(), any(), any());
    }

    @Test
    void aDroppedUpgradePolicyReturnsTheClusterToExtendedSupportAndARollbackRestoresIt() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        JsonNode before = props("{\"Name\": \"prod\", \"RoleArn\": \"arn:aws:iam::000000000000:role/eks\", "
                + "\"UpgradePolicy\": {\"SupportType\": \"STANDARD\"}}");
        provisioner.provision(r, before, ctx());
        ArgumentCaptor<CreateClusterRequest> create = ArgumentCaptor.forClass(CreateClusterRequest.class);
        verify(eks).createCluster(create.capture());
        assertEquals(new UpgradePolicy("STANDARD"), create.getValue().getUpgradePolicy());

        created.setUpgradePolicy(new UpgradePolicy("STANDARD"));
        when(eks.describeCluster("prod")).thenReturn(created);
        JsonNode after = props("{\"Name\": \"prod\", \"RoleArn\": \"arn:aws:iam::000000000000:role/eks\"}");
        ProvisionContext update = new ProvisionContext(ctx().engine(), "us-east-1", "000000000000", "my-stack",
                "prod", event -> { }, before, PropertyChanges.between("AWS::EKS::Cluster", true, before, after));
        provisioner.provision(r, after, update);

        verify(eks).updateClusterConfig("prod",
                new UpdateClusterConfigRequest(null, null, null, new UpgradePolicy("EXTENDED"), null));
        assertTrue(provisioner.rollbackUpdate(r));
        verify(eks).restoreClusterConfig("prod", null, null, null, new UpgradePolicy("STANDARD"));
    }

    @Test
    void aClusterCreatedWithAutoModeAsksForItsComputeStorageAndLoadBalancing() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);

        provisioner.provision(resource("AWS::EKS::Cluster", "Control"), props(PROD_ROLE + AUTO_MODE + "}"), ctx());

        ArgumentCaptor<CreateClusterRequest> create = ArgumentCaptor.forClass(CreateClusterRequest.class);
        verify(eks).createCluster(create.capture());
        assertEquals(new ComputeConfig(true, List.of("general-purpose", "system"),
                NODE_ROLE), create.getValue().getComputeConfig());
        assertEquals(new StorageConfig(new BlockStorage(true)), create.getValue().getStorageConfig());
        assertEquals(new ElasticLoadBalancing(true),
                create.getValue().getKubernetesNetworkConfig().getElasticLoadBalancing());
    }

    @Test
    void anUpdateTurningAutoModeOnSendsItsThreeMembersAndARollbackRestoresTheConfigurationBefore() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        created.setKubernetesNetworkConfig(new KubernetesNetworkConfig());
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        JsonNode before = props("{\"Name\": \"prod\", \"RoleArn\": \"arn:aws:iam::000000000000:role/eks\"}");
        provisioner.provision(r, before, ctx());
        when(eks.describeCluster("prod")).thenReturn(created);
        JsonNode after = props(PROD_ROLE + AUTO_MODE + "}");
        provisioner.provision(r, after, update(before, after));

        ArgumentCaptor<UpdateClusterConfigRequest> request = ArgumentCaptor.forClass(UpdateClusterConfigRequest.class);
        verify(eks).updateClusterConfig(eq("prod"), request.capture());
        assertEquals(new ComputeConfig(true, List.of("general-purpose", "system"), NODE_ROLE),
                request.getValue().computeConfig());
        assertEquals(new StorageConfig(new BlockStorage(true)), request.getValue().storageConfig());
        assertEquals(new ElasticLoadBalancing(true),
                request.getValue().kubernetesNetworkConfig().getElasticLoadBalancing());
        assertTrue(provisioner.rollbackUpdate(r));
        verify(eks).restoreAutoMode("prod", null, null, null);
    }

    @Test
    void anUpdateThatOnlyLeavesOutTheNodeRoleChangesNoAutoMode() throws Exception {
        StackResource r = autoModeCluster();
        JsonNode before = props(PROD_ROLE + AUTO_MODE + "}");
        // The same pools in another order, and no node role: the live one is kept.
        JsonNode after = props(PROD_ROLE + AUTO_MODE.replace("[\"general-purpose\", \"system\"], \"NodeRoleArn\": \""
                + NODE_ROLE + "\"", "[\"system\", \"general-purpose\"]") + "}");

        provisioner.provision(r, after, update(before, after));

        verify(eks, never()).updateClusterConfig(any(), any());
    }

    @Test
    void anUpdateChangingTheNodePoolsWithoutTheNodeRoleSendsTheLiveRole() throws Exception {
        StackResource r = autoModeCluster();
        JsonNode before = props(PROD_ROLE + AUTO_MODE + "}");
        JsonNode after = props(PROD_ROLE + AUTO_MODE.replace("[\"general-purpose\", \"system\"], \"NodeRoleArn\": \""
                + NODE_ROLE + "\"", "[\"general-purpose\"]") + "}");

        provisioner.provision(r, after, update(before, after));

        ArgumentCaptor<UpdateClusterConfigRequest> request = ArgumentCaptor.forClass(UpdateClusterConfigRequest.class);
        verify(eks).updateClusterConfig(eq("prod"), request.capture());
        assertEquals(new ComputeConfig(true, List.of("general-purpose"), NODE_ROLE), request.getValue().computeConfig());
    }

    @Test
    void aNetworkChangeOtherThanLoadBalancingLeavesAutoModeTheStackNeverDeclaredAlone() throws Exception {
        // Auto Mode was turned on outside the stack, whose template never declares it.
        StackResource r = autoModeCluster();
        JsonNode before = props(PROD_ROLE + "\"KubernetesNetworkConfig\": {\"IpFamily\": \"ipv6\"}}");
        JsonNode after = props(PROD_ROLE + "\"KubernetesNetworkConfig\": {\"IpFamily\": \"ipv6\", "
                + "\"ServiceIpv6Cidr\": \"fd00::/108\"}}");

        provisioner.provision(r, after, update(before, after));

        verify(eks, never()).updateClusterConfig(any(), any());
    }

    /** A cluster the stack created, now running EKS Auto Mode with both built-in pools. */
    private StackResource autoModeCluster() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        provisioner.provision(r, props(PROD_ROLE + AUTO_MODE + "}"), ctx());
        created.setComputeConfig(new ComputeConfig(true, List.of("general-purpose", "system"), NODE_ROLE));
        created.setStorageConfig(new StorageConfig(new BlockStorage(true)));
        KubernetesNetworkConfig network = new KubernetesNetworkConfig();
        network.setElasticLoadBalancing(new ElasticLoadBalancing(true));
        created.setKubernetesNetworkConfig(network);
        when(eks.describeCluster("prod")).thenReturn(created);
        return r;
    }

    @Test
    void anUnrelatedUpdateStoresThePolicyTheTemplateDeclaresForAClusterThatHoldsNone() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        String declared = "\"Name\": \"prod\", \"RoleArn\": \"arn:aws:iam::000000000000:role/eks\", "
                + "\"UpgradePolicy\": {\"SupportType\": \"STANDARD\"}, ";
        JsonNode first = props("{" + declared + "\"Tags\": [{\"Key\": \"team\", \"Value\": \"a\"}]}");
        provisioner.provision(r, first, ctx());
        // A cluster created before Floci kept the policy holds none, and reports EXTENDED.
        created.setTags(new HashMap<>(Map.of("team", "a")));
        when(eks.describeCluster("prod")).thenReturn(created);

        JsonNode second = props("{" + declared + "\"Tags\": [{\"Key\": \"team\", \"Value\": \"b\"}]}");
        provisioner.provision(r, second, update(first, second));
        verify(eks).restoreClusterConfig("prod", null, null, null, new UpgradePolicy("STANDARD"));
        verify(eks, never()).updateClusterConfig(any(), any());

        // A cluster that holds a policy keeps it, as it keeps one changed outside the stack.
        created.setUpgradePolicy(new UpgradePolicy("EXTENDED"));
        JsonNode third = props("{" + declared + "\"Tags\": [{\"Key\": \"team\", \"Value\": \"c\"}]}");
        provisioner.provision(r, third, update(second, third));
        verify(eks, times(1)).restoreClusterConfig(any(), any(), any(), any(), any());
        verify(eks, never()).updateClusterConfig(any(), any());
    }

    private ProvisionContext update(JsonNode before, JsonNode after) {
        return new ProvisionContext(ctx().engine(), "us-east-1", "000000000000", "my-stack",
                "prod", event -> { }, before, PropertyChanges.between("AWS::EKS::Cluster", true, before, after));
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
        when(engine.omitNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> strings(inv.getArgument(0)));
        when(engine.resolveLiteralList(any())).thenAnswer(inv -> strings(inv.getArgument(0)));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> out.add(element.asText()));
        }
        return out;
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
