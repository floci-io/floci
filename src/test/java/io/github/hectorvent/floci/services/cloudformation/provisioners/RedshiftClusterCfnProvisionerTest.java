package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        ClusterParameterGroup existing = new ClusterParameterGroup("pg1", "redshift-1.0", "d");
        when(service.getClusterParameterGroup("pg1")).thenReturn(Optional.of(existing));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterParameterGroup");
        r.setLogicalId("Params");

        p.provision(r, json("""
            {"ParameterGroupName":"pg1","ParameterGroupFamily":"redshift-1.0","Description":"d",
             "Parameters":[{"ParameterName":"wlm_json_configuration","ParameterValue":"[]"}]}"""), ctx("pg1"));

        verify(service, never()).createClusterParameterGroup(anyString(), anyString(), anyString());
        assertEquals("pg1", r.getPhysicalId());
        ArgumentCaptor<List<Parameter>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).modifyClusterParameterGroup(eq("pg1"), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("wlm_json_configuration", captor.getValue().get(0).getParameterName());
        assertEquals("[]", captor.getValue().get(0).getParameterValue());
    }

    @Test
    void provisionParameterGroupAppliesParametersOnCreate() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterParameterGroup");
        r.setLogicalId("Params");

        p.provision(r, json("""
            {"ParameterGroupName":"pg1","ParameterGroupFamily":"redshift-1.0","Description":"d",
             "Parameters":[{"ParameterName":"enable_user_activity_logging","ParameterValue":"true"}]}"""), ctx(null));

        verify(service).createClusterParameterGroup("pg1", "redshift-1.0", "d");
        ArgumentCaptor<List<Parameter>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).modifyClusterParameterGroup(eq("pg1"), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("enable_user_activity_logging", captor.getValue().get(0).getParameterName());
        assertEquals("true", captor.getValue().get(0).getParameterValue());
    }

    @Test
    void changingParameterGroupDescriptionTriggersReplacement() {
        RedshiftService service = mock(RedshiftService.class);
        ClusterParameterGroup existing = new ClusterParameterGroup("pg1", "redshift-1.0", "old description");
        when(service.getClusterParameterGroup("pg1")).thenReturn(Optional.of(existing));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterParameterGroup");
        r.setLogicalId("Params");
        r.setPhysicalId("pg1");

        p.provision(r, json("""
            {"ParameterGroupName":"pg1","ParameterGroupFamily":"redshift-1.0","Description":"new description"}"""),
                ctx("pg1"));

        assertNotEquals("pg1", r.getPhysicalId());
        assertEquals("pg1", p.updateCleanupPhysicalId(r));
        assertTrue(p.hasReplacementUpdate(r));
        verify(service).createClusterParameterGroup(eq(r.getPhysicalId()), eq("redshift-1.0"), eq("new description"));
    }

    @Test
    void changingParameterGroupFamilyTriggersReplacement() {
        RedshiftService service = mock(RedshiftService.class);
        ClusterParameterGroup existing = new ClusterParameterGroup("pg1", "redshift-1.0", "d");
        when(service.getClusterParameterGroup("pg1")).thenReturn(Optional.of(existing));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterParameterGroup");
        r.setLogicalId("Params");
        r.setPhysicalId("pg1");

        p.provision(r, json("""
            {"ParameterGroupName":"pg1","ParameterGroupFamily":"redshift-2.0","Description":"d"}"""), ctx("pg1"));

        assertNotEquals("pg1", r.getPhysicalId());
        assertEquals("pg1", p.updateCleanupPhysicalId(r));
        assertTrue(p.hasReplacementUpdate(r));
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
        assertEquals(r.getPhysicalId(), r.getAttributes().get("Id"));
        verifyNoInteractions(service);
        assertDoesNotThrow(() -> p.delete("AWS::Redshift::ClusterSecurityGroup", r.getPhysicalId(), "us-east-1"));
    }

    @Test
    void snapshotPrivateAttributesUseHiddenKeys() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.restoreFromClusterSnapshot("restored", "backup", "ra3.large", null, false, null, "us-east-1"))
                .thenReturn(availableCluster("restored"));
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");

        provisioner.provision(resource, json("""
                {"ClusterIdentifier":"restored","SnapshotIdentifier":"backup","NodeType":"ra3.large",
                 "MasterUsername":"admin","MasterUserPassword":"Template123","DBName":"dev"}"""), ctx(null));

        assertEquals("restored", resource.getAttributes().get("__FlociUsesRedshiftClusterIdentifier"));
        assertEquals("backup", resource.getAttributes().get("__FlociUsesRedshiftSnapshotIdentifier"));
        assertEquals("admin", resource.getAttributes().get("__FlociUsesRedshiftRestoreMasterUsername"));
        assertEquals("dev", resource.getAttributes().get("__FlociUsesRedshiftDBName"));
        assertNotNull(resource.getAttributes().get("__FlociUsesRedshiftMasterUserPasswordHash"));
        assertFalse(resource.getAttributes().containsValue("Template123"));
        assertTrue(resource.getAttributes().keySet().stream().noneMatch(key -> key.startsWith("Floci::")));
    }

    @Test
    void snapshotCreateRestoresWithoutMasterPasswordAndSetsReferences() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.restoreFromClusterSnapshot("restored", "backup", "ra3.large", null, false, null, "us-east-1"))
                .thenReturn(availableCluster("restored"));
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");

        provisioner.provision(resource, json("""
                {"ClusterIdentifier":"restored","NodeType":"ra3.large","SnapshotIdentifier":"backup",
                 "Tags":[{"Key":"env","Value":"restore"}]}"""), ctx(null));

        assertEquals("restored", resource.getPhysicalId());
        assertEquals("host-restored", resource.getAttributes().get("Endpoint.Address"));
        assertEquals("5439", resource.getAttributes().get("Endpoint.Port"));
        assertTrue(resource.getAttributes().get("ClusterNamespaceArn").contains(":namespace:"));
        verify(service).createTags("restored", Map.of("env", "restore"));
        verify(service, never()).createCluster(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void unchangedSnapshotUpdatesWithoutRestoringAgain() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.restoreFromClusterSnapshot("restored", "backup", "ra3.large", null, false, null, "us-east-1"))
                .thenReturn(availableCluster("restored"));
        when(service.modifyCluster(eq("restored"), eq("ra3.large"), isNull(), isNull(), isNull(), anyList()))
                .thenReturn(availableCluster("restored"));
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");
        JsonNode props = json("""
                {"ClusterIdentifier":"restored","NodeType":"ra3.large","SnapshotIdentifier":"backup"}""");
        provisioner.provision(resource, props, ctx(null));
        provisioner.provision(resource, props, ctx("restored"));

        verify(service).restoreFromClusterSnapshot("restored", "backup", "ra3.large", null, false, null, "us-east-1");
        assertEquals("restored", resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void changedSnapshotReplacesAndRollbackRestoresPriorSnapshot() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.restoreFromClusterSnapshot(anyString(), anyString(), eq("ra3.large"), isNull(), eq(false), isNull(), eq("us-east-1")))
                .thenAnswer(invocation -> availableCluster(invocation.getArgument(0)));
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");
        JsonNode original = json("""
                {"ClusterIdentifier":"restored","NodeType":"ra3.large","SnapshotIdentifier":"backup",
                 "MasterUsername":"admin","MasterUserPassword":"Original123","DBName":"dev"}""");
        provisioner.provision(resource, original, ctx(null));
        resource.getAttributes().put("MasterPasswordSecretArn", "old-managed-secret");
        String originalPasswordHash = resource.getAttributes().get("__FlociUsesRedshiftMasterUserPasswordHash");
        provisioner.provision(resource, json("""
                {"ClusterIdentifier":"restored","NodeType":"ra3.large","SnapshotIdentifier":"new-backup",
                 "MasterUsername":"other","MasterUserPassword":"Replacement123","DBName":"analytics"}"""),
                ctx("restored"));

        String replacement = resource.getPhysicalId();
        assertNotEquals("restored", replacement);
        assertFalse(resource.getAttributes().containsKey("MasterPasswordSecretArn"));
        assertEquals("restored", provisioner.updateCleanupPhysicalId(resource));
        verify(service).restoreFromClusterSnapshot(replacement, "new-backup", "ra3.large", null, false, null, "us-east-1");
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals("restored", resource.getPhysicalId());
        assertEquals("backup", resource.getAttributes().get("__FlociUsesRedshiftSnapshotIdentifier"));
        assertEquals("admin", resource.getAttributes().get("__FlociUsesRedshiftRestoreMasterUsername"));
        assertEquals("dev", resource.getAttributes().get("__FlociUsesRedshiftDBName"));
        assertEquals(originalPasswordHash, resource.getAttributes().get("__FlociUsesRedshiftMasterUserPasswordHash"));
        assertFalse(resource.getAttributes().containsKey("__FlociRedshiftPriorAttributes"));
        verify(service).deleteCluster(replacement);
        when(service.modifyCluster(eq("restored"), eq("ra3.large"), isNull(), isNull(), isNull(), anyList()))
                .thenReturn(availableCluster("restored"));
        provisioner.provision(resource, original, ctx("restored"));
        assertEquals("restored", resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void missingSnapshotPropagatesWithoutAssigningPhysicalId() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.restoreFromClusterSnapshot("restored", "missing", "ra3.large", null, false, null, "us-east-1"))
                .thenThrow(new AwsException("ClusterSnapshotNotFound", "Snapshot missing not found", 404));
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");

        AwsException error = assertThrows(AwsException.class, () -> provisioner.provision(resource, json("""
                {"ClusterIdentifier":"restored","NodeType":"ra3.large","SnapshotIdentifier":"missing"}"""), ctx(null)));

        assertEquals("ClusterSnapshotNotFound", error.getErrorCode());
        assertNull(resource.getPhysicalId());
    }

    @Test
    void snapshotCreateForwardsManagedPasswordAndSubnetSettings() {
        RedshiftService service = mock(RedshiftService.class);
        Cluster restored = availableCluster("restored");
        restored.setMasterPasswordSecretArn("secret-arn");
        when(service.restoreFromClusterSnapshot("restored", "backup", "ra3.large", "restore-subnets",
                true, "alias/restore", "us-east-1")).thenReturn(restored);
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");

        provisioner.provision(resource, json("""
                {"ClusterIdentifier":"restored","SnapshotIdentifier":"backup","NodeType":"ra3.large",
                 "ClusterSubnetGroupName":"restore-subnets","ManageMasterPassword":true,
                 "MasterPasswordSecretKmsKeyId":"alias/restore"}"""), ctx(null));

        assertEquals("restored", resource.getPhysicalId());
        assertEquals("secret-arn", resource.getAttributes().get("MasterPasswordSecretArn"));
    }

    @Test
    void snapshotCreateCleansUpWhenConfigurationFails() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.restoreFromClusterSnapshot("restored", "backup", "ra3.large", null, false, null, "us-east-1"))
                .thenReturn(availableCluster("restored"));
        when(service.modifyCluster(eq("restored"), eq("ra3.large"), isNull(), isNull(), eq("params"), anyList()))
                .thenThrow(new AwsException("InternalFailure", "storage failed", 500));
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");

        assertThrows(AwsException.class, () -> provisioner.provision(resource, json("""
                {"ClusterIdentifier":"restored","SnapshotIdentifier":"backup","NodeType":"ra3.large",
                 "ClusterParameterGroupName":"params"}"""), ctx(null)));

        verify(service).deleteCluster("restored");
        assertNull(resource.getPhysicalId());
    }

    @Test
    void blankSnapshotIsRejectedBeforeCreatingACluster() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");

        AwsException error = assertThrows(AwsException.class, () -> provisioner.provision(resource,
                json("{\"SnapshotIdentifier\":\" \"}"), ctx(null)));

        assertEquals("ValidationException", error.getErrorCode());
        verifyNoInteractions(service);
    }

    @Test
    void unchangedTemplateAfterSnapshotReplacementKeepsTheReplacement() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.restoreFromClusterSnapshot(anyString(), anyString(), anyString(), isNull(),
                eq(false), isNull(), eq("us-east-1")))
                .thenAnswer(invocation -> availableCluster(invocation.getArgument(0)));
        when(service.modifyCluster(anyString(), eq("ra3.large"), isNull(), isNull(), isNull(), anyList()))
                .thenAnswer(invocation -> availableCluster(invocation.getArgument(0)));
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");
        provisioner.provision(resource, json("""
                {"ClusterIdentifier":"restored","NodeType":"ra3.large","SnapshotIdentifier":"backup"}"""), ctx(null));
        JsonNode changed = json("""
                {"ClusterIdentifier":"restored","NodeType":"ra3.large","SnapshotIdentifier":"new-backup"}""");
        provisioner.provision(resource, changed, ctx("restored"));
        String replacement = resource.getPhysicalId();
        provisioner.completeUpdate(resource);
        provisioner.clearUpdate(resource);

        provisioner.provision(resource, changed, ctx(replacement));

        assertEquals(replacement, resource.getPhysicalId());
        verify(service).modifyCluster(eq(replacement), eq("ra3.large"), isNull(), isNull(), isNull(), anyList());
    }

    @Test
    void removingSnapshotReplacesWithFreshCluster() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.createCluster(anyString(), eq("ra3.large"), eq("admin"), eq("Secret123"), isNull(), anyList()))
                .thenAnswer(invocation -> availableCluster(invocation.getArgument(0)));
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");
        resource.setPhysicalId("restored");
        resource.getAttributes().put("__FlociUsesRedshiftSnapshotIdentifier", "backup");

        provisioner.provision(resource, json("""
                {"ClusterIdentifier":"restored","NodeType":"ra3.large","MasterUsername":"admin",
                 "MasterUserPassword":"Secret123"}"""), ctx("restored"));

        assertNotEquals("restored", resource.getPhysicalId());
        assertEquals("restored", provisioner.updateCleanupPhysicalId(resource));
        assertFalse(resource.getAttributes().containsKey("__FlociUsesRedshiftSnapshotIdentifier"));
    }

    @Test
    void snapshotCreateCleansUpWhenTaggingFails() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.restoreFromClusterSnapshot("restored", "backup", "ra3.large", null, false, null, "us-east-1"))
                .thenReturn(availableCluster("restored"));
        doThrow(new AwsException("InternalFailure", "tag storage failed", 500))
                .when(service).createTags("restored", Map.of("env", "restore"));
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");

        assertThrows(AwsException.class, () -> provisioner.provision(resource, json("""
                {"ClusterIdentifier":"restored","SnapshotIdentifier":"backup","NodeType":"ra3.large",
                 "Tags":[{"Key":"env","Value":"restore"}]}"""), ctx(null)));

        verify(service).deleteCluster("restored");
        assertNull(resource.getPhysicalId());
    }

    @Test
    void managedSnapshotCreateRejectsAnExplicitPassword() {
        RedshiftService service = mock(RedshiftService.class);
        RedshiftClusterCfnProvisioner provisioner = new RedshiftClusterCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::Redshift::Cluster");
        resource.setLogicalId("Warehouse");

        AwsException error = assertThrows(AwsException.class, () -> provisioner.provision(resource, json("""
                {"ClusterIdentifier":"restored","NodeType":"ra3.large","SnapshotIdentifier":"backup",
                 "ManageMasterPassword":true,"MasterUserPassword":"Secret123"}"""), ctx(null)));

        assertEquals("ValidationException", error.getErrorCode());
        verifyNoInteractions(service);
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
        assertFalse(r.getAttributes().containsKey("Id"));
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
    void provisionSupportsManageMasterPassword() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.createClusterWithManagedMasterPassword(anyString(), anyString(), anyString(), isNull(),
                anyList(), anyList(), any(), anyString())).thenAnswer(inv -> {
            Cluster cluster = availableCluster(inv.getArgument(0));
            cluster.setMasterPasswordSecretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:redshift-managed");
            return cluster;
        });
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");

        p.provision(r, json("""
            {"NodeType":"ra3.large","MasterUsername":"admin","ManageMasterPassword":true,
             "MasterPasswordSecretKmsKeyId":"alias/redshift-key"}"""), ctx(null));

        verify(service).createClusterWithManagedMasterPassword(anyString(), eq("ra3.large"), eq("admin"),
                isNull(), anyList(), eq(List.of()), eq("alias/redshift-key"), eq("us-east-1"));
        assertEquals("arn:aws:secretsmanager:us-east-1:000000000000:secret:redshift-managed",
                r.getAttributes().get("MasterPasswordSecretArn"));
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

    @ParameterizedTest
    @ValueSource(strings = {"__FlociUsesRedshiftDBName", "Floci::DBName"})
    void changingDBNameTriggersReplacement(String dbNameKey) {
        RedshiftService service = mock(RedshiftService.class);
        Cluster existing = availableCluster("my-cluster");
        existing.setMasterUsername("admin");
        when(service.describeClusters("my-cluster")).thenReturn(List.of(existing));
        when(service.createCluster(anyString(), anyString(), anyString(), anyString(), any(), anyList()))
                .thenAnswer(inv -> availableCluster(inv.getArgument(0)));
        when(service.modifyCluster(eq("my-cluster"), any(), any(), any(), any(), anyList()))
                .thenReturn(availableCluster("my-cluster"));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");
        r.setPhysicalId("my-cluster");
        r.getAttributes().put("Floci::DBName", "analytics");
        r.getAttributes().put(dbNameKey, "dev");

        p.provision(r, json("""
            {"ClusterIdentifier":"my-cluster","NodeType":"ra3.large","MasterUsername":"admin",
             "MasterUserPassword":"Secret123","DBName":"analytics"}"""), ctx("my-cluster"));

        assertNotEquals("my-cluster", r.getPhysicalId());
        assertEquals("my-cluster", p.updateCleanupPhysicalId(r));
        assertTrue(p.hasReplacementUpdate(r));
        assertEquals("analytics", r.getAttributes().get("__FlociUsesRedshiftDBName"));
    }

    @Test
    void unrelatedUpdateOnSnapshotRestoredClusterPreservesInheritedPassword() {
        RedshiftService service = mock(RedshiftService.class);
        when(service.restoreFromClusterSnapshot("restored", "backup", "ra3.large", null, false, null, "us-east-1"))
                .thenReturn(availableCluster("restored"));
        when(service.modifyCluster(eq("restored"), eq("ra3.xlplus"), isNull(), isNull(), isNull(), anyList()))
                .thenReturn(availableCluster("restored"));
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(service);

        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::Cluster");
        r.setLogicalId("W");
        // Initial restore with template password "TemplatePass1" (deliberately ignored during snapshot restore)
        p.provision(r, json("""
            {"ClusterIdentifier":"restored","NodeType":"ra3.large","SnapshotIdentifier":"backup",
             "MasterUserPassword":"TemplatePass1"}"""), ctx(null));

        // Now an unrelated update: NodeType changes to ra3.xlplus, MasterUserPassword unchanged
        p.provision(r, json("""
            {"ClusterIdentifier":"restored","NodeType":"ra3.xlplus","SnapshotIdentifier":"backup",
             "MasterUserPassword":"TemplatePass1"}"""), ctx("restored"));

        // Password passed to modifyCluster must be null, preserving inherited password
        verify(service).modifyCluster(eq("restored"), eq("ra3.xlplus"), isNull(), isNull(), isNull(), anyList());

        // Now explicitly updating MasterUserPassword to a new password
        when(service.modifyCluster(eq("restored"), eq("ra3.xlplus"), isNull(), eq("BrandNewPass2"), isNull(), anyList()))
                .thenReturn(availableCluster("restored"));
        p.provision(r, json("""
            {"ClusterIdentifier":"restored","NodeType":"ra3.xlplus","SnapshotIdentifier":"backup",
             "MasterUserPassword":"BrandNewPass2"}"""), ctx("restored"));

        verify(service).modifyCluster(eq("restored"), eq("ra3.xlplus"), isNull(), eq("BrandNewPass2"), isNull(), anyList());
    }
}
