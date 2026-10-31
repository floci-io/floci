package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSubnetGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import io.github.hectorvent.floci.services.redshift.model.SnapshotCopyGrant;
import io.github.hectorvent.floci.services.redshift.model.SnapshotSchedule;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedshiftQueryHandlerTest {

    @Test
    void pauseAndResumeRequireClusterIdentifier() {
        for (String action : List.of("PauseCluster", "ResumeCluster")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> handler.handle(action, new MultivaluedHashMap<>()));
            assertEquals("InvalidParameterValue", error.getErrorCode());
        }
        verifyNoInteractions(service);
    }

    @Test
    void pauseAndResumeUseAwsQueryClusterWrappersAndNamespace() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("warehouse");
        cluster.setClusterStatus("paused");
        when(service.pauseCluster("warehouse")).thenReturn(cluster);
        when(service.resumeCluster("warehouse")).thenReturn(cluster);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "warehouse");
        for (String action : List.of("PauseCluster", "ResumeCluster")) {
            Response response = handler.handle(action, params);
            assertEquals(200, response.getStatus());
            String xml = response.getEntity().toString();
            assertTrue(xml.contains("<" + action + "Response xmlns=\"" + AwsNamespaces.REDSHIFT + "\">"));
            assertTrue(xml.contains("<" + action + "Result><Cluster>"));
            assertTrue(xml.contains("<ClusterIdentifier>warehouse</ClusterIdentifier>"));
        }
        verify(service).pauseCluster("warehouse");
        verify(service).resumeCluster("warehouse");
    }

    @Test
    void eventSubscriptionResponsesUseTheRedshiftNamespace() {
        EventSubscription subscription = new EventSubscription();
        subscription.setCustSubscriptionId("watcher");
        subscription.setSubscriptionCreationTime(Instant.parse("2026-10-26T12:00:00Z"));
        when(service.createEventSubscription(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(subscription);
        Response response = handler.handle("CreateEventSubscription", new MultivaluedHashMap<>());
        assertTrue(response.getEntity().toString().contains(
                "<CreateEventSubscriptionResponse xmlns=\"" + AwsNamespaces.REDSHIFT + "\">"));
    }

    @Test
    void nonFiniteEventTimestampsReturnAnAwsValidationError() {
        for (String invalid : List.of("NaN", "Infinity", "-Infinity", "1e300")) {
            MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
            params.putSingle("StartTime", invalid);
            AwsException error = assertThrows(AwsException.class, () -> handler.handle("DescribeEvents", params));
            assertEquals("InvalidParameterValue", error.getErrorCode());
        }
        verifyNoInteractions(service);
    }

    @Test
    void modifyClusterIamRolesParsesAndReturnsDefaultRole() {
        String role = "arn:aws:iam::111111111111:role/default";
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("AddIamRoles.IamRoleArn.1", role);
        params.putSingle("DefaultIamRoleArn", role);
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setDefaultIamRoleArn(role);
        when(service.modifyClusterIamRoles("c1", List.of(role), List.of(), role)).thenReturn(cluster);

        Response response = handler.handle("ModifyClusterIamRoles", params);

        verify(service).modifyClusterIamRoles("c1", List.of(role), List.of(), role);
        assertTrue(response.getEntity().toString().contains("<DefaultIamRoleArn>" + role + "</DefaultIamRoleArn>"));
    }

    @Test
    void createClusterPassesDefaultRoleToService() {
        String role = "arn:aws:iam::111111111111:role/default";
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("NodeType", "dc2.large");
        params.putSingle("MasterUsername", "admin");
        params.putSingle("MasterUserPassword", "pw");
        params.putSingle("IamRoles.IamRoleArn.1", role);
        params.putSingle("DefaultIamRoleArn", role);
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setDefaultIamRoleArn(role);
        when(service.createCluster("c1", "dc2.large", "admin", "pw", null, List.of(), List.of(role), role))
                .thenReturn(cluster);

        Response response = handler.handle("CreateCluster", params);

        assertTrue(response.getEntity().toString().contains("<DefaultIamRoleArn>" + role + "</DefaultIamRoleArn>"));
        verify(service).createCluster("c1", "dc2.large", "admin", "pw", null, List.of(), List.of(role), role);
    }

    private RedshiftQueryHandler handler;
    private RedshiftService service;
    private RedshiftCredentialBroker credentialBroker;
    private EmulatorConfig config;
    private RedshiftIamDbUserResolver iamDbUserResolver;
    private RegionResolver regionResolver;

    @Test
    void resetClusterParameterGroupReturnsAwsQueryResponse() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "reset-pg");
        params.putSingle("ResetAllParameters", "true");

        Response response = handler.handle("ResetClusterParameterGroup", params);

        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ResetClusterParameterGroupResult>"));
        assertTrue(xml.contains("<ParameterGroupName>reset-pg</ParameterGroupName>"));
        assertTrue(xml.contains("<ParameterGroupStatus>"));
        verify(service).resetClusterParameterGroup("reset-pg", true, List.of());
    }

    @Test
    void resetClusterParameterGroupDefaultsToAllWithoutNames() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "reset-pg");

        handler.handle("ResetClusterParameterGroup", params);

        verify(service).resetClusterParameterGroup("reset-pg", true, List.of());
    }

    @Test
    void resetClusterParameterGroupAcceptsBothQueryMemberFormatsWithoutValues() {
        for (String prefix : List.of("Parameters.Parameter", "Parameters.member")) {
            MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
            params.putSingle("ParameterGroupName", "reset-pg");
            params.putSingle("ResetAllParameters", "false");
            params.putSingle(prefix + ".1.ParameterName", "wlm_json_configuration");

            handler.handle("ResetClusterParameterGroup", params);
        }

        verify(service, times(2)).resetClusterParameterGroup("reset-pg", false, List.of("wlm_json_configuration"));
    }

    @Test
    void resetClusterParameterGroupDefaultsToSelectedNamesWhenNamesAreGiven() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "reset-pg");
        params.putSingle("Parameters.Parameter.1.ParameterName", "wlm_json_configuration");

        handler.handle("ResetClusterParameterGroup", params);

        verify(service).resetClusterParameterGroup("reset-pg", false, List.of("wlm_json_configuration"));
    }

    @Test
    void resetClusterParameterGroupDoesNotTreatSparseNamesAsResetAll() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "reset-pg");
        params.putSingle("Parameters.Parameter.2.ParameterName", "wlm_json_configuration");

        handler.handle("ResetClusterParameterGroup", params);

        verify(service).resetClusterParameterGroup("reset-pg", false, List.of("wlm_json_configuration"));
    }

    @Test
    void resetClusterParameterGroupRejectsInvalidBoolean() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "reset-pg");
        params.putSingle("ResetAllParameters", "invalid");

        AwsException failure = assertThrows(AwsException.class,
                () -> handler.handle("ResetClusterParameterGroup", params));

        assertEquals("InvalidParameterValue", failure.getErrorCode());
        verify(service, never()).resetClusterParameterGroup(any(), anyBoolean(), anyList());
    }

    @Test
    void resetClusterParameterGroupAcceptsBotocoreEmptyListForResetAll() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "reset-pg");
        params.putSingle("Parameters", "");
        params.putSingle("ResetAllParameters", "true");

        Response response = handler.handle("ResetClusterParameterGroup", params);

        assertEquals(200, response.getStatus());
        verify(service).resetClusterParameterGroup("reset-pg", true, List.of());
    }

    @Test
    void resetClusterParameterGroupTreatsEmptyListLikeOmittedList() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "reset-pg");
        params.putSingle("Parameters", "");

        handler.handle("ResetClusterParameterGroup", params);

        verify(service).resetClusterParameterGroup("reset-pg", true, List.of());
    }

    @Test
    void resetClusterParameterGroupRejectsNonemptyBareParameters() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "reset-pg");
        params.putSingle("Parameters", "invalid");

        AwsException failure = assertThrows(AwsException.class,
                () -> handler.handle("ResetClusterParameterGroup", params));

        assertEquals("InvalidParameterValue", failure.getErrorCode());
        verify(service, never()).resetClusterParameterGroup(any(), anyBoolean(), anyList());
    }

    @Test
    void resetClusterParameterGroupPassesAllSuppliedNamesToServiceForValidation() {
        for (String prefix : List.of("Parameters.Parameter", "Parameters.member")) {
            MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
            params.putSingle("ParameterGroupName", "reset-pg");
            for (int index = 1; index <= 21; index++) {
                params.putSingle(prefix + "." + index + ".ParameterName", "max_cursor_result_set_size");
            }

            handler.handle("ResetClusterParameterGroup", params);
        }
        ArgumentCaptor<List<String>> names = ArgumentCaptor.captor();
        verify(service, times(2)).resetClusterParameterGroup(eq("reset-pg"), eq(false), names.capture());
        assertTrue(names.getAllValues().stream().allMatch(value -> value.size() == 21));
    }

    @Test
    void modifyClusterParameterGroupRejectsMembersWithoutNames() {
        for (String prefix : List.of("Parameters.Parameter", "Parameters.member")) {
            MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
            params.putSingle("ParameterGroupName", "reset-pg");
            params.putSingle(prefix + ".1.ParameterValue", "1000");

            AwsException failure = assertThrows(AwsException.class,
                    () -> handler.handle("ModifyClusterParameterGroup", params));

            assertEquals("InvalidParameterValue", failure.getErrorCode());
        }
        verify(service, never()).modifyClusterParameterGroup(any(), anyList());
    }

    @Test
    void modifyClusterParameterGroupReadsSparseMembersInNumericOrder() {
        for (String prefix : List.of("Parameters.Parameter", "Parameters.member")) {
            MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
            params.putSingle("ParameterGroupName", "reset-pg");
            params.putSingle(prefix + ".10.ParameterName", "max_cursor_result_set_size");
            params.putSingle(prefix + ".10.ParameterValue", "2000");
            params.putSingle(prefix + ".2.ParameterName", "max_cursor_result_set_size");
            params.putSingle(prefix + ".2.ParameterValue", "1000");

            handler.handle("ModifyClusterParameterGroup", params);
        }

        ArgumentCaptor<List<Parameter>> updates = ArgumentCaptor.captor();
        verify(service, times(2)).modifyClusterParameterGroup(eq("reset-pg"), updates.capture());
        for (List<Parameter> parameters : updates.getAllValues()) {
            assertEquals(List.of("1000", "2000"), parameters.stream().map(Parameter::getParameterValue).toList());
        }
    }

    @Test
    void resetClusterParameterGroupCountsMembersRatherThanFields() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "reset-pg");
        for (int index = 1; index <= 20; index++) {
            params.putSingle("Parameters.Parameter." + index + ".ParameterName", "max_cursor_result_set_size");
            params.putSingle("Parameters.Parameter." + index + ".ParameterValue", "ignored");
        }

        handler.handle("ResetClusterParameterGroup", params);

        ArgumentCaptor<List<String>> names = ArgumentCaptor.captor();
        verify(service).resetClusterParameterGroup(eq("reset-pg"), eq(false), names.capture());
        assertEquals(20, names.getValue().size());
    }

    @Test
    void resetClusterParameterGroupRejectsMembersWithoutNamesBeforeChoosingResetAll() {
        for (String prefix : List.of("Parameters.Parameter", "Parameters.member")) {
            MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
            params.putSingle("ParameterGroupName", "reset-pg");
            params.putSingle(prefix + ".1.ParameterValue", "1000");

            AwsException failure = assertThrows(AwsException.class,
                    () -> handler.handle("ResetClusterParameterGroup", params));

            assertEquals("InvalidParameterValue", failure.getErrorCode());
            params.putSingle(prefix + ".2.ParameterName", "wlm_json_configuration");
            assertThrows(AwsException.class, () -> handler.handle("ResetClusterParameterGroup", params));
            params.putSingle(prefix + ".1.ParameterName", " ");
            assertThrows(AwsException.class, () -> handler.handle("ResetClusterParameterGroup", params));
        }
        verify(service, never()).resetClusterParameterGroup(any(), anyBoolean(), anyList());
    }

    @Test
    void describeClusterParametersFiltersAndSerializesSource() {
        Parameter overridden = new Parameter("max_cursor_result_set_size", "1000");
        overridden.setSource("user");
        Parameter engineDefault = new Parameter("wlm_json_configuration", "{}");
        engineDefault.setSource("engine-default");
        when(service.describeClusterParameters("reset-pg")).thenReturn(List.of(overridden, engineDefault));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "reset-pg");
        params.putSingle("Source", "user");

        Response response = handler.handle("DescribeClusterParameters", params);

        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<Source>user</Source>"));
        assertFalse(xml.contains("wlm_json_configuration"));
    }

    @BeforeEach
    void setUp() {
        service = mock(RedshiftService.class);
        credentialBroker = new RedshiftCredentialBroker();
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redshift().defaultCredentialDurationSeconds()).thenReturn(900);
        iamDbUserResolver = mock(RedshiftIamDbUserResolver.class);
        regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("acc");
        when(regionResolver.resolveRegionFromAuth(anyString())).thenReturn("us-east-1");
        handler = new RedshiftQueryHandler(service, credentialBroker, config, iamDbUserResolver, regionResolver);
    }

    private Cluster availableCluster(String id) {
        Cluster c = new Cluster();
        c.setClusterIdentifier(id);
        c.setMasterUsername("admin");
        c.setMasterPassword("SecretPass1");
        c.setClusterStatus("available");
        return c;
    }

    @Test
    void createClusterAction() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");
        params.putSingle("NodeType", "dc2.large");
        params.putSingle("MasterUsername", "admin");
        params.putSingle("MasterUserPassword", "password123");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("available");
        when(service.createCluster(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(cluster);

        Response response = handler.handle("CreateCluster", params);

        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterIdentifier>test-cluster</ClusterIdentifier>"));
        assertTrue(xml.contains("<ClusterStatus>available</ClusterStatus>"));
        assertTrue(xml.contains("<RequestId>test-req-id</RequestId>"));
    }

    @Test
    void createClusterWithManagedMasterPasswordUsesSecretsManagerSecret() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "managed-cluster");
        params.putSingle("NodeType", "dc2.large");
        params.putSingle("MasterUsername", "admin");
        params.putSingle("ManageMasterPassword", "true");
        params.putSingle("MasterPasswordSecretKmsKeyId", "arn:aws:kms:us-east-1:acc:key/key-1");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("managed-cluster");
        cluster.setClusterStatus("available");
        cluster.setMasterPasswordSecretArn("arn:aws:secretsmanager:us-east-1:acc:secret:redshift-managed");
        cluster.setMasterPasswordSecretKmsKeyId("arn:aws:kms:us-east-1:acc:key/key-1");
        when(service.createClusterWithManagedMasterPassword(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(cluster);

        Response response = handler.handle("CreateCluster", params,
                "AWS4-HMAC-SHA256 Credential=test/20260918/us-east-1/redshift/aws4_request");

        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<MasterPasswordSecretArn>arn:aws:secretsmanager:us-east-1:acc:secret:redshift-managed</MasterPasswordSecretArn>"));
        assertTrue(xml.contains("<MasterPasswordSecretKmsKeyId>arn:aws:kms:us-east-1:acc:key/key-1</MasterPasswordSecretKmsKeyId>"));
        assertFalse(xml.contains("<MasterUserSecret>"));
        verify(service).createClusterWithManagedMasterPassword(eq("managed-cluster"), eq("dc2.large"),
                eq("admin"), isNull(), eq(List.of()), eq(List.of()),
                eq("arn:aws:kms:us-east-1:acc:key/key-1"), eq("us-east-1"), isNull());
    }

    @Test
    void createClusterParsesIamRoleArnLocationName() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");
        params.putSingle("NodeType", "dc2.large");
        params.putSingle("MasterUsername", "admin");
        params.putSingle("MasterUserPassword", "password123");
        params.putSingle("IamRoles.IamRoleArn.2", "arn:aws:iam::000000000000:role/second");
        params.putSingle("IamRoles.IamRoleArn.1", "arn:aws:iam::000000000000:role/first");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("available");
        when(service.createCluster(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(cluster);

        handler.handle("CreateCluster", params);

        verify(service).createCluster(eq("test-cluster"), eq("dc2.large"), eq("admin"), eq("password123"),
                isNull(), eq(List.of()), eq(List.of("arn:aws:iam::000000000000:role/first",
                        "arn:aws:iam::000000000000:role/second")), isNull());
    }
    
    @Test
    void describeClusters() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("available");
        when(service.describeClusters(any())).thenReturn(List.of(cluster));

        Response response = handler.handle("DescribeClusters", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterIdentifier>test-cluster</ClusterIdentifier>"));
    }

    @Test
    void describeClustersIncludesAvailabilityStatuses() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("available");
        when(service.describeClusters(any())).thenReturn(List.of(cluster));

        Response response = handler.handle("DescribeClusters", params);
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterAvailabilityStatus>Available</ClusterAvailabilityStatus>"));
        assertTrue(xml.contains("<AvailabilityZoneRelocationStatus>disabled</AvailabilityZoneRelocationStatus>"));
    }

    @Test
    void clusterAvailabilityStatusMapsTransientStatesToModifying() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("creating");
        when(service.createCluster(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(cluster);

        Response response = handler.handle("CreateCluster", params);
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterAvailabilityStatus>Modifying</ClusterAvailabilityStatus>"));
    }

    @Test
    void clusterAvailabilityStatusMapsFailed() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("failed");
        when(service.describeClusters(any())).thenReturn(List.of(cluster));

        Response response = handler.handle("DescribeClusters", params);
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterAvailabilityStatus>Failed</ClusterAvailabilityStatus>"));
    }

    @Test
    void deleteCluster() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("deleting");
        when(service.deleteCluster("test-cluster", null, null, null)).thenReturn(cluster);

        Response response = handler.handle("DeleteCluster", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterIdentifier>test-cluster</ClusterIdentifier>"));
        assertTrue(xml.contains("<ClusterStatus>deleting</ClusterStatus>"));
    }

    @Test
    void createClusterSnapshot() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SnapshotIdentifier", "test-snapshot");
        params.putSingle("ClusterIdentifier", "test-cluster");

        Snapshot snapshot = new Snapshot("test-snapshot", "test-cluster", "available", 5439, "admin");
        when(service.createSnapshot("test-snapshot", "test-cluster")).thenReturn(snapshot);

        Response response = handler.handle("CreateClusterSnapshot", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<SnapshotIdentifier>test-snapshot</SnapshotIdentifier>"));
        assertTrue(xml.contains("<ClusterIdentifier>test-cluster</ClusterIdentifier>"));
        assertTrue(xml.contains("<Status>available</Status>"));
        assertTrue(xml.contains("<Port>5439</Port>"));
        assertTrue(xml.contains("<MasterUsername>admin</MasterUsername>"));
        assertTrue(xml.contains("<RequestId>test-req-id</RequestId>"));
        assertTrue(xml.contains("<ManualSnapshotRetentionPeriod>-1</ManualSnapshotRetentionPeriod>"));
        // A Snapshot built via this constructor carries no ARN/CreateTime; the elements
        // must be omitted rather than serialized as empty or "null".
        assertFalse(xml.contains("<SnapshotArn>"));
        assertFalse(xml.contains("<SnapshotCreateTime>"));
    }

    @Test
    void describeLegacySnapshotsDefaultsMissingAndNullRetention() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String json : List.of("{\"snapshotIdentifier\":\"legacy\"}",
                "{\"snapshotIdentifier\":\"legacy\",\"manualSnapshotRetentionPeriod\":null}")) {
            Snapshot snapshot = mapper.readValue(json, Snapshot.class);
            when(service.describeSnapshots("legacy", null)).thenReturn(List.of(snapshot));
            MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
            params.putSingle("SnapshotIdentifier", "legacy");

            String xml = handler.handle("DescribeClusterSnapshots", params).getEntity().toString();

            assertTrue(xml.contains("<ManualSnapshotRetentionPeriod>-1</ManualSnapshotRetentionPeriod>"));
        }
    }

    @Test
    void describeClusterSnapshots() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SnapshotIdentifier", "test-snapshot");
        params.putSingle("ClusterIdentifier", "test-cluster");

        Snapshot snapshot = new Snapshot("test-snapshot", "test-cluster", "available", 5439, "admin");
        snapshot.setSnapshotArn("arn:aws:redshift:us-east-1:111111111111:snapshot:test-cluster/test-snapshot");
        snapshot.setSnapshotCreateTime(Instant.parse("2026-09-16T04:00:00Z"));
        when(service.describeSnapshots("test-snapshot", "test-cluster")).thenReturn(List.of(snapshot));

        Response response = handler.handle("DescribeClusterSnapshots", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<Snapshots>"));
        assertTrue(xml.contains("<SnapshotIdentifier>test-snapshot</SnapshotIdentifier>"));
        assertTrue(xml.contains(
                "<SnapshotArn>arn:aws:redshift:us-east-1:111111111111:snapshot:test-cluster/test-snapshot</SnapshotArn>"));
        assertTrue(xml.contains("<SnapshotCreateTime>2026-09-16T04:00:00Z</SnapshotCreateTime>"));
        assertTrue(xml.contains("</Snapshots>"));
    }

    @Test
    void deleteClusterSnapshot() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SnapshotIdentifier", "test-snapshot");

        Snapshot snapshot = new Snapshot("test-snapshot", "test-cluster", "deleted", 5439, "admin");
        when(service.deleteSnapshot("test-snapshot")).thenReturn(snapshot);

        Response response = handler.handle("DeleteClusterSnapshot", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<SnapshotIdentifier>test-snapshot</SnapshotIdentifier>"));
        assertTrue(xml.contains("<Status>deleted</Status>"));
    }

    @Test
    void restoreFromClusterSnapshot() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "restored-cluster");
        params.putSingle("SnapshotIdentifier", "test-snapshot");
        params.putSingle("NodeType", "dc2.large");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("restored-cluster");
        cluster.setNodeType("dc2.large");
        cluster.setMasterUsername("admin");
        cluster.setClusterStatus("available");
        cluster.setEndpoint(new Endpoint("localhost", 5439));
        when(service.restoreFromClusterSnapshot("restored-cluster", "test-snapshot", "dc2.large")).thenReturn(cluster);

        Response response = handler.handle("RestoreFromClusterSnapshot", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterIdentifier>restored-cluster</ClusterIdentifier>"));
        assertTrue(xml.contains("<ClusterStatus>available</ClusterStatus>"));
        assertTrue(xml.contains("<Address>localhost</Address>"));
        assertTrue(xml.contains("<Port>5439</Port>"));
    }

    @Test
    void restoreFromClusterSnapshotBySnapshotArn() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "restored-cluster");
        params.putSingle("SnapshotArn", "arn:aws:redshift:us-east-1:acc:snapshot:src/my-snap");
        Cluster cluster = availableCluster("restored-cluster");
        when(service.restoreFromClusterSnapshot("restored-cluster", "my-snap", null)).thenReturn(cluster);

        Response response = handler.handle("RestoreFromClusterSnapshot", params, "auth");
        assertEquals(200, response.getStatus());
        verify(service).restoreFromClusterSnapshot("restored-cluster", "my-snap", null);
    }

    @Test
    void restoreFromClusterSnapshotMalformedArnIs400() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "restored-cluster");
        params.putSingle("SnapshotArn", "not-an-arn");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RestoreFromClusterSnapshot", params, "auth"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void restoreFromClusterSnapshotWithoutSnapshotIs400() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "restored-cluster");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RestoreFromClusterSnapshot", params, "auth"));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void restoreFromClusterSnapshotForeignAccountArnNotFound() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "restored-cluster");
        params.putSingle("SnapshotArn", "arn:aws:redshift:us-east-1:999999999999:snapshot:src/my-snap");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RestoreFromClusterSnapshot", params, "auth"));
        assertEquals("ClusterSnapshotNotFound", ex.getErrorCode());
        verify(service, never()).restoreFromClusterSnapshot(any(), any(), any());
    }

    @Test
    void restoreFromClusterSnapshotWithBothIdentifierAndArnIs400() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "restored-cluster");
        params.putSingle("SnapshotIdentifier", "snap-a");
        params.putSingle("SnapshotArn", "arn:aws:redshift:us-east-1:acc:snapshot:src/snap-b");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RestoreFromClusterSnapshot", params, "auth"));
        assertEquals("InvalidParameterCombination", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        verify(service, never()).restoreFromClusterSnapshot(any(), any(), any());
    }

    @Test
    void clusterXmlCarriesDefaultParameterGroupAndMultiAZ() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));

        String xml = (String) handler.handle("DescribeClusters", params).getEntity();
        assertTrue(xml.contains("<ParameterGroupName>default.redshift-1.0</ParameterGroupName>"));
        assertTrue(xml.contains("<MultiAZ>disabled</MultiAZ>"));
    }

    @Test
    void createClusterParameterGroup() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "test-pg");
        params.putSingle("ParameterGroupFamily", "redshift-1.0");
        params.putSingle("Description", "custom redshift param group");

        ClusterParameterGroup group = new ClusterParameterGroup("test-pg", "redshift-1.0", "custom redshift param group");
        when(service.createClusterParameterGroup("test-pg", "redshift-1.0", "custom redshift param group")).thenReturn(group);

        Response response = handler.handle("CreateClusterParameterGroup", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ParameterGroupName>test-pg</ParameterGroupName>"));
        assertTrue(xml.contains("<ParameterGroupFamily>redshift-1.0</ParameterGroupFamily>"));
        assertTrue(xml.contains("<Description>custom redshift param group</Description>"));
    }

    @Test
    void describeClusterParameterGroups() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "test-pg");

        ClusterParameterGroup group = new ClusterParameterGroup("test-pg", "redshift-1.0", "custom redshift param group");
        when(service.describeClusterParameterGroups("test-pg")).thenReturn(List.of(group));

        Response response = handler.handle("DescribeClusterParameterGroups", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ParameterGroups>"));
        assertTrue(xml.contains("<ParameterGroupName>test-pg</ParameterGroupName>"));
        assertTrue(xml.contains("</ParameterGroups>"));
    }

    @Test
    void describeClusterParameters() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "test-pg");

        ClusterParameterGroup group = new ClusterParameterGroup("test-pg", "redshift-1.0", "custom redshift param group");
        when(service.describeClusterParameterGroups("test-pg")).thenReturn(List.of(group));

        Response response = handler.handle("DescribeClusterParameters", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<Parameters>"));
        assertTrue(xml.contains("</Parameters>"));
    }

    @Test
    void deleteClusterParameterGroup() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "test-pg");

        ClusterParameterGroup group = new ClusterParameterGroup("test-pg", "redshift-1.0", "custom redshift param group");
        when(service.deleteClusterParameterGroup("test-pg")).thenReturn(group);

        Response response = handler.handle("DeleteClusterParameterGroup", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<DeleteClusterParameterGroupResponse>"));
    }

    @Test
    void createTagsAcceptsNamedMemberForm() {
        // Real Redshift SDK sends "Tags.Tag.N.Key/.Value", không phải "Tags.member.N...".
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ResourceName", "arn:aws:redshift:us-east-1:000000000000:cluster:test-cluster");
        params.putSingle("Tags.Tag.1.Key", "env");
        params.putSingle("Tags.Tag.1.Value", "prod");

        Response response = handler.handle("CreateTags", params);
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).createTags(eq("arn:aws:redshift:us-east-1:000000000000:cluster:test-cluster"), captor.capture());
        assertEquals(Map.of("env", "prod"), captor.getValue());
    }

    @Test
    void createClusterSubnetGroupAcceptsNamedMemberForm() {
        // Real Redshift SDK sends "SubnetIds.SubnetIdentifier.N", không phải "SubnetIds.member.N".
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterSubnetGroupName", "test-sng");
        params.putSingle("Description", "desc");
        params.putSingle("SubnetIds.SubnetIdentifier.1", "subnet-aaa");
        params.putSingle("SubnetIds.SubnetIdentifier.2", "subnet-bbb");

        ClusterSubnetGroup group = new ClusterSubnetGroup("test-sng", "desc", null, List.of("subnet-aaa", "subnet-bbb"));
        when(service.createClusterSubnetGroup(any(), any(), any(), any())).thenReturn(group);

        Response response = handler.handle("CreateClusterSubnetGroup", params);
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).createClusterSubnetGroup(eq("test-sng"), eq("desc"), any(), captor.capture());
        assertEquals(List.of("subnet-aaa", "subnet-bbb"), captor.getValue());
    }

    @Test
    void modifyClusterParameterGroupAcceptsNamedMemberForm() {
        // Real Redshift SDK sends "Parameters.Parameter.N.ParameterName/.ParameterValue".
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ParameterGroupName", "test-pg");
        params.putSingle("Parameters.Parameter.1.ParameterName", "statement_timeout");
        params.putSingle("Parameters.Parameter.1.ParameterValue", "5000");

        Response response = handler.handle("ModifyClusterParameterGroup", params);
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Parameter>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).modifyClusterParameterGroup(eq("test-pg"), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("statement_timeout", captor.getValue().get(0).getParameterName());
        assertEquals("5000", captor.getValue().get(0).getParameterValue());
    }

    @Test
    void createClusterSubnetGroupRequiresName() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Description", "desc");

        AwsException ex = assertThrows(
                AwsException.class,
                () -> handler.handle("CreateClusterSubnetGroup", params));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void modifyClusterRequiresClusterIdentifier() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("NodeType", "dc2.large");

        AwsException ex = assertThrows(
                AwsException.class,
                () -> handler.handle("ModifyCluster", params));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void createSnapshotScheduleReadsScheduleDefinitionLocationNameInNumericOrder() {
        // The SDK serialises ScheduleDefinitions as "ScheduleDefinitions.ScheduleDefinition.N".
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ScheduleIdentifier", "sched");
        params.putSingle("ScheduleDefinitions.ScheduleDefinition.10", "cron(0 1 * * ? *)");
        params.putSingle("ScheduleDefinitions.ScheduleDefinition.2", "rate(12 hours)");
        when(service.createSnapshotSchedule(any(), any(), any(), any()))
                .thenAnswer(inv -> new SnapshotSchedule(inv.getArgument(0), null, inv.getArgument(2)));

        Response response = handler.handle("CreateSnapshotSchedule", params);

        assertEquals(200, response.getStatus());
        verify(service).createSnapshotSchedule(eq("sched"), isNull(),
                eq(List.of("rate(12 hours)", "cron(0 1 * * ? *)")), eq(Map.of()));
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<CreateSnapshotScheduleResult><ScheduleDefinitions>"
                + "<ScheduleDefinition>rate(12 hours)</ScheduleDefinition>"), xml);
    }

    @Test
    void createsSnapshotCopyGrant() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SnapshotCopyGrantName", "grant-1");
        params.putSingle("KmsKeyId", "key-abc");

        when(service.createSnapshotCopyGrant(eq("grant-1"), eq("key-abc"), any()))
                .thenReturn(new SnapshotCopyGrant("grant-1", "key-abc"));

        Response response = handler.handle("CreateSnapshotCopyGrant", params);

        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<CreateSnapshotCopyGrantResult>"));
        assertTrue(xml.contains("<SnapshotCopyGrantName>grant-1</SnapshotCopyGrantName>"));
        assertTrue(xml.contains("<KmsKeyId>key-abc</KmsKeyId>"));
        assertTrue(xml.contains("<RequestId>test-req-id</RequestId>"));
    }

    @Test
    void createSnapshotCopyGrantAcceptsNamedMemberTags() {
        // Real Redshift SDK sends "Tags.Tag.N.Key/.Value" on CreateSnapshotCopyGrant.
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SnapshotCopyGrantName", "grant-1");
        params.putSingle("Tags.Tag.1.Key", "env");
        params.putSingle("Tags.Tag.1.Value", "prod");

        SnapshotCopyGrant grant = new SnapshotCopyGrant("grant-1", "key-abc");
        grant.setTags(Map.of("env", "prod"));
        when(service.createSnapshotCopyGrant(any(), any(), any())).thenReturn(grant);

        Response response = handler.handle("CreateSnapshotCopyGrant", params);
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).createSnapshotCopyGrant(eq("grant-1"), isNull(), captor.capture());
        assertEquals(Map.of("env", "prod"), captor.getValue());

        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<Key>env</Key>"));
        assertTrue(xml.contains("<Value>prod</Value>"));
    }

    @Test
    void createSnapshotCopyGrantRequiresName() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("KmsKeyId", "key-abc");

        AwsException ex = assertThrows(
                AwsException.class,
                () -> handler.handle("CreateSnapshotCopyGrant", params));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void modifyClusterIamRolesAcceptsNamedMemberForm() {
        // Real Redshift SDK sends "AddIamRoles.IamRoleArn.N" and "RemoveIamRoles.IamRoleArn.N".
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("AddIamRoles.IamRoleArn.1", "arn:aws:iam::111111111111:role/a");
        params.putSingle("RemoveIamRoles.IamRoleArn.1", "arn:aws:iam::111111111111:role/b");
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setIamRoleArns(List.of("arn:aws:iam::111111111111:role/a"));
        when(service.modifyClusterIamRoles(eq("c1"), any(), any(), isNull())).thenReturn(cluster);

        Response response = handler.handle("ModifyClusterIamRoles", params);

        assertEquals(200, response.getStatus());
        verify(service).modifyClusterIamRoles("c1",
                List.of("arn:aws:iam::111111111111:role/a"), List.of("arn:aws:iam::111111111111:role/b"), null);
        String body = response.getEntity().toString();
        assertTrue(body.contains("<ModifyClusterIamRolesResult>"));
        assertTrue(body.contains("<IamRoleArn>arn:aws:iam::111111111111:role/a</IamRoleArn>"));
        assertTrue(body.contains("<ApplyStatus>in-sync</ApplyStatus>"));
    }

    @Test
    void describeClusterVersionsListsTheEmulatedVersion() {
        Response response = handler.handle("DescribeClusterVersions", new MultivaluedHashMap<>());

        assertEquals(200, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("<DescribeClusterVersionsResult>"));
        assertTrue(body.contains("<ClusterVersion>1.0</ClusterVersion>"));
        assertTrue(body.contains("<ClusterParameterGroupFamily>redshift-1.0</ClusterParameterGroupFamily>"));
    }

    @Test
    void describeClusterVersionsFiltersByVersion() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterVersion", "9.9");

        String body = handler.handle("DescribeClusterVersions", params).getEntity().toString();

        assertFalse(body.contains("<ClusterVersion>1.0</ClusterVersion>"));
    }

    @Test
    void describeOrderableClusterOptionsFiltersByNodeType() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("NodeType", "ra3.xlplus");
        when(regionResolver.resolveRegionFromAuth("auth")).thenReturn("eu-west-1");

        Response response = handler.handle("DescribeOrderableClusterOptions", params, "auth");

        assertEquals(200, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("<Name>eu-west-1a</Name>"));
        assertTrue(body.contains("<DescribeOrderableClusterOptionsResult>"));
        assertTrue(body.contains("<NodeType>ra3.xlplus</NodeType>"));
        assertFalse(body.contains("<NodeType>dc2.large</NodeType>"));
        assertTrue(body.contains("<ClusterType>multi-node</ClusterType>"));
    }

    @Test
    void modifyClusterIamRolesRequiresClusterIdentifier() {
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("ModifyClusterIamRoles", new MultivaluedHashMap<>()));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void describeLoggingStatus() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingBucketName("my-bucket");
        cluster.setLoggingS3KeyPrefix("logs/");
        when(service.describeLoggingStatus("test-cluster")).thenReturn(cluster);

        Response response = handler.handle("DescribeLoggingStatus", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        // The real AWS wire format wraps the fields in a *Result element matching the
        // operation name, verified against the SDK's own deserializer, not guessed.
        assertTrue(xml.contains("<DescribeLoggingStatusResult>"));
        assertTrue(xml.contains("<LoggingEnabled>true</LoggingEnabled>"));
        assertTrue(xml.contains("<BucketName>my-bucket</BucketName>"));
        assertTrue(xml.contains("<S3KeyPrefix>logs/</S3KeyPrefix>"));
    }

    @Test
    void describeS3TableLoggingStatusIncludesEnabledAllWithoutGranularity() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("s3table");
        when(service.describeLoggingStatus("test-cluster")).thenReturn(cluster);

        Response response = handler.handle("DescribeLoggingStatus", params);

        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<S3Tables><EnabledAll>true</EnabledAll></S3Tables>"));
        assertFalse(xml.contains("<S3Tables></S3Tables>"));
    }

    @Test
    void describeLoggingStatusRequiresClusterIdentifier() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        AwsException ex = assertThrows(
                AwsException.class,
                () -> handler.handle("DescribeLoggingStatus", params));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void enableLogging() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");
        params.putSingle("BucketName", "my-bucket");
        params.putSingle("S3KeyPrefix", "logs/");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingBucketName("my-bucket");
        cluster.setLoggingS3KeyPrefix("logs/");
        when(service.enableLogging("test-cluster", "my-bucket", "logs/", null, List.of(), null, null)).thenReturn(cluster);

        Response response = handler.handle("EnableLogging", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<EnableLoggingResult>"));
        assertTrue(xml.contains("<LoggingEnabled>true</LoggingEnabled>"));
    }

    @Test
    void enableLoggingS3TableWithGranularityAndKmsKey() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");
        params.putSingle("LogDestinationType", "s3table");
        params.putSingle("S3TableKmsKeyId", "my-kms-key");
        params.putSingle("S3TableGranularity", "cluster");
        params.putSingle("LogExports.member.1", "sys_query_history");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("s3table");
        cluster.setLoggingS3TableKmsKeyId("my-kms-key");
        cluster.setLoggingS3TableGranularity("cluster");
        cluster.setLoggingExports(List.of("sys_query_history"));
        when(service.enableLogging("test-cluster", null, null, "s3table", List.of("sys_query_history"),
                "my-kms-key", "cluster"))
                .thenReturn(cluster);

        Response response = handler.handle("EnableLogging", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<EnableLoggingResult>"));
        assertTrue(xml.contains("<LoggingEnabled>true</LoggingEnabled>"));
        assertTrue(xml.contains("<LogDestinationType>s3table</LogDestinationType>"));
        assertTrue(xml.contains("<S3Tables><S3Tables><member>sys_query_history</member></S3Tables>"));
        assertTrue(xml.contains("<EnabledAll>false</EnabledAll>"));
        assertTrue(xml.contains("<S3TableGranularity>cluster</S3TableGranularity>"));
    }

    @Test
    void disableLogging() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setLoggingEnabled(false);
        when(service.disableLogging("test-cluster")).thenReturn(cluster);

        Response response = handler.handle("DisableLogging", params);
        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<DisableLoggingResult>"));
        assertTrue(xml.contains("<LoggingEnabled>false</LoggingEnabled>"));
        // No bucket was ever configured; the element must be omitted, not emitted empty/"null".
        assertFalse(xml.contains("<BucketName>"));
    }

    @Test
    void describesSnapshotCopyGrants() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SnapshotCopyGrantName", "grant-1");

        when(service.describeSnapshotCopyGrants(eq("grant-1"), isNull(), isNull()))
                .thenReturn(new PaginatedResult<>(List.of(new SnapshotCopyGrant("grant-1", "key-abc")), null));

        Response response = handler.handle("DescribeSnapshotCopyGrants", params);

        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<SnapshotCopyGrants>"));
        assertTrue(xml.contains("<SnapshotCopyGrantName>grant-1</SnapshotCopyGrantName>"));
        assertTrue(xml.contains("</SnapshotCopyGrants>"));
        assertFalse(xml.contains("<Marker>"), "a single full page must not advertise a marker");
    }

    @Test
    void describeSnapshotCopyGrantsWithoutNameListsAll() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        when(service.describeSnapshotCopyGrants(isNull(), isNull(), isNull()))
                .thenReturn(new PaginatedResult<>(List.of(
                        new SnapshotCopyGrant("grant-a", "key-a"),
                        new SnapshotCopyGrant("grant-b", "key-b")), null));

        Response response = handler.handle("DescribeSnapshotCopyGrants", params);

        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<SnapshotCopyGrantName>grant-a</SnapshotCopyGrantName>"));
        assertTrue(xml.contains("<SnapshotCopyGrantName>grant-b</SnapshotCopyGrantName>"));
    }

    @Test
    void describeSnapshotCopyGrantsForwardsMaxRecordsAndMarker() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("MaxRecords", "20");
        params.putSingle("Marker", "Z3JhbnQtMjA");

        when(service.describeSnapshotCopyGrants(isNull(), eq(20), eq("Z3JhbnQtMjA")))
                .thenReturn(new PaginatedResult<>(List.of(new SnapshotCopyGrant("grant-21", "key-a")), null));

        Response response = handler.handle("DescribeSnapshotCopyGrants", params);

        assertEquals(200, response.getStatus());
        verify(service).describeSnapshotCopyGrants(isNull(), eq(20), eq("Z3JhbnQtMjA"));
    }

    @Test
    void describeSnapshotCopyGrantsEmitsMarkerWhenMorePagesRemain() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("MaxRecords", "20");

        when(service.describeSnapshotCopyGrants(isNull(), eq(20), isNull()))
                .thenReturn(new PaginatedResult<>(
                        List.of(new SnapshotCopyGrant("grant-01", "key-a")), "Z3JhbnQtMjA"));

        Response response = handler.handle("DescribeSnapshotCopyGrants", params);

        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<Marker>Z3JhbnQtMjA</Marker>"));
    }

    @Test
    void describeSnapshotCopyGrantsRejectsNonNumericMaxRecords() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("MaxRecords", "many");

        AwsException ex = assertThrows(
                AwsException.class,
                () -> handler.handle("DescribeSnapshotCopyGrants", params));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void deletesSnapshotCopyGrant() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("SnapshotCopyGrantName", "grant-1");

        when(service.deleteSnapshotCopyGrant("grant-1"))
                .thenReturn(new SnapshotCopyGrant("grant-1", "key-abc"));

        Response response = handler.handle("DeleteSnapshotCopyGrant", params);

        assertEquals(200, response.getStatus());
        verify(service).deleteSnapshotCopyGrant("grant-1");
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<DeleteSnapshotCopyGrantResponse>"));
    }

    @Test
    void deleteSnapshotCopyGrantRequiresName() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        AwsException ex = assertThrows(
                AwsException.class,
                () -> handler.handle("DeleteSnapshotCopyGrant", params));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        verify(service, never()).deleteSnapshotCopyGrant(any());
    }

    @Test
    void buildClusterXmlIncludesParameterGroupAndTags() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "test-cluster");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-cluster");
        cluster.setClusterStatus("available");
        cluster.setClusterParameterGroupName("test-pg");
        cluster.setTags(Map.of("env", "prod"));
        when(service.describeClusters(any())).thenReturn(List.of(cluster));

        Response response = handler.handle("DescribeClusters", params);
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<ClusterParameterGroups>"));
        assertTrue(xml.contains("<ParameterGroupName>test-pg</ParameterGroupName>"));
        assertTrue(xml.contains("<ParameterApplyStatus>in-sync</ParameterApplyStatus>"));
        assertTrue(xml.contains("<Tags>"));
        assertTrue(xml.contains("<Key>env</Key>"));
        assertTrue(xml.contains("<Value>prod</Value>"));
    }

    // ── GetClusterCredentials ────────────────────────────────────────────────

    @Test
    void getClusterCredentialsReturnsUserPasswordAndExpiration() {
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("DbUser", "analyst");

        Response response = handler.handle("GetClusterCredentials", params);

        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<DbUser>IAM:analyst</DbUser>"));
        assertTrue(xml.contains("<DbPassword>"));
        assertTrue(xml.contains("<Expiration>"));
        assertTrue(xml.contains("<GetClusterCredentialsResult>"));
    }

    @Test
    void getClusterCredentialsStoresCredentialInBroker() {
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("DbUser", "analyst");

        handler.handle("GetClusterCredentials", params);

        assertTrue(credentialBroker.resolve("acc", "c1", "IAM:analyst").isPresent());
    }

    @Test
    void getClusterCredentialsRejectsMissingDbUser() {
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("GetClusterCredentials", params));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void getClusterCredentialsClampsAnOutOfRangeConfigDefault() {
        when(config.services().redshift().defaultCredentialDurationSeconds()).thenReturn(5);
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("DbUser", "analyst");

        handler.handle("GetClusterCredentials", params);

        Instant expiresAt = credentialBroker.resolve("acc", "c1", "IAM:analyst").orElseThrow().expiresAt();
        assertTrue(expiresAt.isAfter(Instant.now().plusSeconds(800)),
                "an out-of-range config default must fall back to the AWS minimum, not be used as-is");
    }

    @Test
    void getClusterCredentialsRejectsDurationBelowMinimum() {
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("DbUser", "analyst");
        params.putSingle("DurationSeconds", "899");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("GetClusterCredentials", params));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void getClusterCredentialsRejectsDurationAboveMaximum() {
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("DbUser", "analyst");
        params.putSingle("DurationSeconds", "3601");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("GetClusterCredentials", params));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void getClusterCredentialsAutoCreateTruePrefixesIamaOnDbUser() {
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("DbUser", "analyst");
        params.putSingle("AutoCreate", "true");

        Response response = handler.handle("GetClusterCredentials", params);

        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<DbUser>IAMA:analyst</DbUser>"));
    }

    @Test
    void getClusterCredentialsAutoCreateFalsePrefixesIamOnDbUser() {
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("DbUser", "analyst");

        Response response = handler.handle("GetClusterCredentials", params);

        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<DbUser>IAM:analyst</DbUser>"));
    }

    @Test
    void getClusterCredentialsEchoesDbGroups() {
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("DbUser", "analyst");
        params.putSingle("DbGroups.member.1", "etl");
        params.putSingle("DbGroups.member.2", "readonly");

        Response response = handler.handle("GetClusterCredentials", params);

        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<DbGroup>etl</DbGroup>"));
        assertTrue(xml.contains("<DbGroup>readonly</DbGroup>"));
    }

    @Test
    void getClusterCredentialsPropagatesClusterNotFound() {
        when(service.describeClusters("missing"))
                .thenThrow(new AwsException("ClusterNotFound", "Cluster missing not found", 404));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "missing");
        params.putSingle("DbUser", "analyst");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("GetClusterCredentials", params));
        assertEquals("ClusterNotFound", ex.getErrorCode());
    }

    // ── GetClusterCredentialsWithIAM ─────────────────────────────────────────

    @Test
    void getClusterCredentialsWithIamDerivesDbUserFromCaller() {
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        when(iamDbUserResolver.resolveDbUser(any())).thenReturn("IAMR:Deployer");
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");

        Response response = handler.handle("GetClusterCredentialsWithIAM", params);

        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<DbUser>IAMR:Deployer</DbUser>"));
        assertTrue(xml.contains("<GetClusterCredentialsWithIAMResult>"));
    }

    @Test
    void getClusterCredentialsWithIamIgnoresDbUserParam() {
        when(service.describeClusters("c1")).thenReturn(List.of(availableCluster("c1")));
        when(iamDbUserResolver.resolveDbUser(any())).thenReturn("IAM:alice");
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "c1");
        params.putSingle("DbUser", "ignored");

        Response response = handler.handle("GetClusterCredentialsWithIAM", params);

        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<DbUser>IAM:alice</DbUser>"));
    }
}
