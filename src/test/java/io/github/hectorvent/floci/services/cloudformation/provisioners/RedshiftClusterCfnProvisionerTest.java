package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedshiftClusterCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode n = inv.getArgument(0);
            return n == null || n.isNull() ? null : n.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            JsonNode n = inv.getArgument(0);
            List<String> out = new ArrayList<>();
            if (n != null && n.isArray()) {
                n.forEach(e -> out.add(e.asText()));
            }
            return out;
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "test-stack", priorPhysicalId);
    }

    private Cluster availableCluster(String id) {
        Cluster c = new Cluster();
        c.setClusterIdentifier(id);
        c.setEndpoint(new Endpoint("host-" + id, 5439));
        return c;
    }

    private JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void resourceTypesCoversAllFour() {
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(mock(RedshiftService.class));
        assertEquals(Set.of("AWS::Redshift::Cluster", "AWS::Redshift::ClusterParameterGroup",
                "AWS::Redshift::ClusterSubnetGroup", "AWS::Redshift::ClusterSecurityGroup"),
                p.resourceTypes());
    }

    @Test
    void provisionThrowsOnUnknownResourceType() {
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(mock(RedshiftService.class));
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::UnknownType");
        r.setLogicalId("Unknown");
        assertThrows(IllegalStateException.class, () -> p.provision(r, json("{}"), ctx(null)));
    }

    @Test
    void provisionParameterGroupMapsToService() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterParameterGroup");
        r.setLogicalId("Params");

        p.provision(r, json("""
            {"ParameterGroupName":"pg1","ParameterGroupFamily":"redshift-1.0","Description":"d"}"""), ctx(null));

        verify(service).createClusterParameterGroup("pg1", "redshift-1.0", "d");
        assertEquals("pg1", r.getPhysicalId());
    }

    @Test
    void provisionParameterGroupWithGeneratedNameAndTags() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterParameterGroup");
        r.setLogicalId("Params");

        p.provision(r, json("""
            {"ParameterGroupFamily":"redshift-1.0","Description":"d",
             "Tags":[{"Key":"env","Value":"test"}]}"""), ctx(null));

        assertNotNull(r.getPhysicalId());
        verify(service).createClusterParameterGroup(eq(r.getPhysicalId()), eq("redshift-1.0"), eq("d"));
        verify(service).createTags(r.getPhysicalId(), Map.of("env", "test"));
    }

    @Test
    void provisionParameterGroupReusesPriorEntityWithoutCallingCreate() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterParameterGroup");
        r.setLogicalId("Params");

        p.provision(r, json("""
            {"ParameterGroupName":"pg1","ParameterGroupFamily":"redshift-1.0","Description":"d",
             "Parameters":[{"ParameterName":"wlm_json_configuration","ParameterValue":"[]"}]}"""), ctx("pg1"));

        verify(service, never()).createClusterParameterGroup(anyString(), anyString(), anyString());
        assertEquals("pg1", r.getPhysicalId());
    }

    @Test
    void deleteParameterGroupToleratesNotFound() {
        RedshiftService service = mock(RedshiftService.class);
        doThrow(new AwsException("ClusterParameterGroupNotFound", "gone", 404))
                .when(service).deleteClusterParameterGroup("pg1");
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        assertDoesNotThrow(() -> p.delete("AWS::Redshift::ClusterParameterGroup", "pg1", "us-east-1"));
    }

    @Test
    void deleteParameterGroupAndSubnetGroupTolerateFaultSuffix() {
        RedshiftService service = mock(RedshiftService.class);
        doThrow(new AwsException("ClusterParameterGroupNotFoundFault", "gone", 404))
                .when(service).deleteClusterParameterGroup("pg1");
        doThrow(new AwsException("ClusterSubnetGroupNotFoundFault", "gone", 404))
                .when(service).deleteClusterSubnetGroup("sg1");
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        assertDoesNotThrow(() -> p.delete("AWS::Redshift::ClusterParameterGroup", "pg1", "us-east-1"));
        assertDoesNotThrow(() -> p.delete("AWS::Redshift::ClusterSubnetGroup", "sg1", "us-east-1"));
    }

    @Test
    void deleteParameterGroupPropagatesUnexpectedError() {
        RedshiftService service = mock(RedshiftService.class);
        doThrow(new AwsException("InternalFailure", "boom", 500))
                .when(service).deleteClusterParameterGroup("pg1");
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        assertThrows(AwsException.class,
                () -> p.delete("AWS::Redshift::ClusterParameterGroup", "pg1", "us-east-1"));
    }

    @Test
    void provisionSubnetGroupMapsToServiceAndReconcilesOnUpdate() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterSubnetGroup");
        r.setLogicalId("Subnets");

        p.provision(r, json("""
            {"ClusterSubnetGroupName":"sg1","Description":"d","SubnetIds":["subnet-a","subnet-b"]}"""), ctx(null));
        verify(service).createClusterSubnetGroup("sg1", "d", null, List.of("subnet-a", "subnet-b"));
        assertEquals("sg1", r.getPhysicalId());
        assertEquals("sg1", r.getAttributes().get("ClusterSubnetGroupName"));

        p.provision(r, json("""
            {"ClusterSubnetGroupName":"sg1","Description":"d2","SubnetIds":["subnet-a"]}"""), ctx("sg1"));
        verify(service).modifyClusterSubnetGroup("sg1", "d2", List.of("subnet-a"));
        assertEquals("sg1", r.getPhysicalId());
    }

    @Test
    void provisionSubnetGroupWithGeneratedName() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterSubnetGroup");
        r.setLogicalId("Subnets");

        p.provision(r, json("""
            {"Description":"d","SubnetIds":["subnet-1"]}"""), ctx(null));

        assertNotNull(r.getPhysicalId());
        assertEquals(r.getPhysicalId(), r.getAttributes().get("ClusterSubnetGroupName"));
        verify(service).createClusterSubnetGroup(eq(r.getPhysicalId()), eq("d"), isNull(), eq(List.of("subnet-1")));
    }

    @Test
    void deleteSubnetGroupToleratesNotFound() {
        RedshiftService service = mock(RedshiftService.class);
        doThrow(new AwsException("ClusterSubnetGroupNotFound", "gone", 404))
                .when(service).deleteClusterSubnetGroup("sg1");
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        assertDoesNotThrow(() -> p.delete("AWS::Redshift::ClusterSubnetGroup", "sg1", "us-east-1"));
    }

    @Test
    void deleteSubnetGroupPropagatesUnexpectedError() {
        RedshiftService service = mock(RedshiftService.class);
        doThrow(new AwsException("InternalFailure", "boom", 500))
                .when(service).deleteClusterSubnetGroup("sg1");
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        assertThrows(AwsException.class,
                () -> p.delete("AWS::Redshift::ClusterSubnetGroup", "sg1", "us-east-1"));
    }

    @Test
    void provisionSecurityGroupIsAcceptOnly() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterSecurityGroup");
        r.setLogicalId("SecGroup");

        p.provision(r, json("""
            {"Description":"sg desc"}"""), ctx(null));

        assertNotNull(r.getPhysicalId());
        assertTrue(r.getAttributes().isEmpty());
        verifyNoInteractions(service);
        assertDoesNotThrow(() -> p.delete("AWS::Redshift::ClusterSecurityGroup", r.getPhysicalId(), "us-east-1"));
    }

    @Test
    void provisionCreatesClusterAndSetsRefAndEndpointAttributes() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.createCluster(eq("my-cluster"), eq("ra3.large"), eq("admin"), eq("Secret123"),
                isNull(), anyList())).thenReturn(availableCluster("my-cluster"));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("Warehouse");
        JsonNode props = json("""
            {"ClusterIdentifier":"my-cluster","NodeType":"ra3.large",
             "MasterUsername":"admin","MasterUserPassword":"Secret123",
             "ClusterType":"single-node","DBName":"dev"}""");

        p.provision(r, props, ctx(null));

        assertEquals("my-cluster", r.getPhysicalId());
        assertEquals("host-my-cluster", r.getAttributes().get("Endpoint.Address"));
        assertEquals("5439", r.getAttributes().get("Endpoint.Port"));
        assertEquals("my-cluster", r.getAttributes().get("Id"));
        assertTrue(r.getAttributes().get("ClusterNamespaceArn")
                .startsWith("arn:aws:redshift:us-east-1:000000000000:namespace:"));
    }

    @Test
    void provisionGeneratesLowercaseIdentifierWhenAbsent() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.createCluster(anyString(), anyString(), anyString(), anyString(), any(), anyList()))
                .thenAnswer(inv -> availableCluster(inv.getArgument(0)));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("MyWarehouse");
        p.provision(r, json("""
            {"NodeType":"ra3.large","MasterUsername":"admin","MasterUserPassword":"Secret123"}"""),
            ctx(null));

        assertEquals(r.getPhysicalId().toLowerCase(), r.getPhysicalId());
        assertTrue(r.getPhysicalId().length() <= 63);
    }

    @Test
    void provisionRejectsManageMasterPassword() {
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(mock(RedshiftService.class));
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");

        AwsException ex = assertThrows(AwsException.class, () -> p.provision(r, json("""
            {"NodeType":"ra3.large","MasterUsername":"admin","ManageMasterPassword":true}"""), ctx(null)));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void provisionRejectsMissingMasterPassword() {
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(mock(RedshiftService.class));
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");

        AwsException ex = assertThrows(AwsException.class, () -> p.provision(r, json("""
            {"NodeType":"ra3.large","MasterUsername":"admin"}"""), ctx(null)));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void provisionAppliesTags() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.createCluster(anyString(), anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn(availableCluster("my-cluster"));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");

        p.provision(r, json("""
            {"ClusterIdentifier":"my-cluster","NodeType":"ra3.large","MasterUsername":"admin",
             "MasterUserPassword":"Secret123","Tags":[{"Key":"env","Value":"dev"}]}"""), ctx(null));

        verify(service).createTags("my-cluster", Map.of("env", "dev"));
    }

    @Test
    void deleteCallsDeleteClusterAndToleratesNotFound() {
        RedshiftService service = mock(RedshiftService.class);
        doThrow(new AwsException("ClusterNotFound", "gone", 404)).when(service).deleteCluster("my-cluster");
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        assertDoesNotThrow(() -> p.delete("AWS::Redshift::Cluster", "my-cluster", "us-east-1"));
    }

    @Test
    void deletePropagatesUnexpectedError() {
        RedshiftService service = mock(RedshiftService.class);
        doThrow(new AwsException("InternalFailure", "boom", 500)).when(service).deleteCluster("my-cluster");
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        assertThrows(AwsException.class,
                () -> p.delete("AWS::Redshift::Cluster", "my-cluster", "us-east-1"));
    }

    @Test
    void updateInPlaceCallsModifyCluster() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.modifyCluster(eq("my-cluster"), eq("ra3.4xlarge"), isNull(), eq("NewSecret9"),
                isNull(), anyList())).thenReturn(availableCluster("my-cluster"));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");
        p.provision(r, json("""
            {"ClusterIdentifier":"my-cluster","NodeType":"ra3.4xlarge","MasterUsername":"admin",
             "MasterUserPassword":"NewSecret9"}"""), ctx("my-cluster"));

        verify(service).modifyCluster(eq("my-cluster"), eq("ra3.4xlarge"), isNull(), eq("NewSecret9"),
                isNull(), anyList());
        verify(service, never()).createCluster(anyString(), anyString(), anyString(), anyString(), any(), anyList());
    }

    @Test
    void replacementCreatesNewClusterAndReportsOldIdForCleanup() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.createCluster(eq("new-name"), anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn(availableCluster("new-name"));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");
        // prior id "old-name" differs from the template's new ClusterIdentifier "new-name"
        p.provision(r, json("""
            {"ClusterIdentifier":"new-name","NodeType":"ra3.large","MasterUsername":"admin",
             "MasterUserPassword":"Secret123"}"""), ctx("old-name"));

        assertEquals("new-name", r.getPhysicalId());
        assertEquals("old-name", p.updateCleanupPhysicalId(r));
        assertTrue(p.hasReplacementUpdate(r));

        when(service.deleteCluster("old-name")).thenReturn(null);
        UpdateCleanupResult result = p.completeUpdate(r);
        verify(service).deleteCluster("old-name");
        assertEquals(new UpdateCleanupResult(true, true, "old-name", 0, null), result);
    }

    @Test
    void replacementCleanupToleratesAlreadyDeletedOldCluster() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.createCluster(anyString(), anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn(availableCluster("new-name"));
        doThrow(new AwsException("ClusterNotFound", "gone", 404)).when(service).deleteCluster("old-name");
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");
        p.provision(r, json("""
            {"ClusterIdentifier":"new-name","NodeType":"ra3.large","MasterUsername":"admin",
             "MasterUserPassword":"Secret123"}"""), ctx("old-name"));

        assertDoesNotThrow(() -> p.completeUpdate(r));
    }

    @Test
    void clearUpdateDropsCleanupRecord() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.createCluster(eq("new-name"), anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn(availableCluster("new-name"));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");
        p.provision(r, json("""
            {"ClusterIdentifier":"new-name","NodeType":"ra3.large","MasterUsername":"admin",
             "MasterUserPassword":"Secret123"}"""), ctx("old-name"));

        assertTrue(p.hasReplacementUpdate(r));
        p.completeUpdate(r);
        p.clearUpdate(r);
        assertFalse(p.hasReplacementUpdate(r));
        assertNull(p.updateCleanupPhysicalId(r));
    }

    @Test
    void rollbackUpdateRestoresPriorPhysicalIdAndDeletesReplacement() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.createCluster(eq("new-name"), anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn(availableCluster("new-name"));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");
        r.setPhysicalId("old-name");
        r.getAttributes().put("Id", "old-name");

        p.provision(r, json("""
            {"ClusterIdentifier":"new-name","NodeType":"ra3.large","MasterUsername":"admin",
             "MasterUserPassword":"Secret123"}"""), ctx("old-name"));

        assertEquals("new-name", r.getPhysicalId());
        assertTrue(p.rollbackUpdate(r));
        assertEquals("old-name", r.getPhysicalId());
        verify(service).deleteCluster("new-name");
    }

    @Test
    void changingMasterUsernameTriggersReplacement() {
        RedshiftService service = mock(RedshiftService.class);
        Cluster existing = availableCluster("my-cluster");
        existing.setMasterUsername("admin");
        when(service.describeClusters("my-cluster")).thenReturn(List.of(existing));
        when(service.createCluster(anyString(), anyString(), eq("newadmin"), anyString(), any(), anyList()))
                .thenAnswer(inv -> availableCluster(inv.getArgument(0)));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");
        p.provision(r, json("""
            {"ClusterIdentifier":"my-cluster","NodeType":"ra3.large","MasterUsername":"newadmin",
             "MasterUserPassword":"Secret123"}"""), ctx("my-cluster"));

        assertNotEquals("my-cluster", r.getPhysicalId());
        assertTrue(r.getPhysicalId().startsWith("my-cluster-"));
        assertEquals("my-cluster", p.updateCleanupPhysicalId(r));
        assertTrue(p.hasReplacementUpdate(r));

        when(service.deleteCluster("my-cluster")).thenReturn(null);
        UpdateCleanupResult result = p.completeUpdate(r);
        verify(service).deleteCluster("my-cluster");
        assertEquals(new UpdateCleanupResult(true, true, "my-cluster", 0, null), result);
    }

    @Test
    void changingClusterSubnetGroupTriggersReplacement() {
        RedshiftService service = mock(RedshiftService.class);
        Cluster existing = availableCluster("my-cluster");
        existing.setMasterUsername("admin");
        existing.setClusterSubnetGroupName("subnet-group-1");
        when(service.describeClusters("my-cluster")).thenReturn(List.of(existing));
        when(service.createCluster(anyString(), anyString(), anyString(), anyString(), eq("subnet-group-2"), anyList()))
                .thenAnswer(inv -> availableCluster(inv.getArgument(0)));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");
        p.provision(r, json("""
            {"ClusterIdentifier":"my-cluster","NodeType":"ra3.large","MasterUsername":"admin",
             "MasterUserPassword":"Secret123","ClusterSubnetGroupName":"subnet-group-2"}"""), ctx("my-cluster"));

        assertNotEquals("my-cluster", r.getPhysicalId());
        assertEquals("my-cluster", p.updateCleanupPhysicalId(r));
        assertTrue(p.hasReplacementUpdate(r));
    }

    @Test
    void changingDBNameTriggersReplacement() {
        RedshiftService service = mock(RedshiftService.class);
        Cluster existing = availableCluster("my-cluster");
        existing.setMasterUsername("admin");
        when(service.describeClusters("my-cluster")).thenReturn(List.of(existing));
        when(service.createCluster(anyString(), anyString(), anyString(), anyString(), any(), anyList()))
                .thenAnswer(inv -> availableCluster(inv.getArgument(0)));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");
        r.setPhysicalId("my-cluster");
        r.getAttributes().put("Floci::DBName", "dev");

        p.provision(r, json("""
            {"ClusterIdentifier":"my-cluster","NodeType":"ra3.large","MasterUsername":"admin",
             "MasterUserPassword":"Secret123","DBName":"analytics"}"""), ctx("my-cluster"));

        assertNotEquals("my-cluster", r.getPhysicalId());
        assertEquals("my-cluster", p.updateCleanupPhysicalId(r));
        assertTrue(p.hasReplacementUpdate(r));
        assertEquals("analytics", r.getAttributes().get("Floci::DBName"));
    }
}
