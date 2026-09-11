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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void resourceTypesReturnsRedshiftCluster() {
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(mock(RedshiftService.class));
        assertEquals(Set.of("AWS::Redshift::Cluster"), p.resourceTypes());
    }

    @Test
    void provisionThrowsOnUnknownResourceType() {
        RedshiftClusterCfnProvisioner p = new RedshiftClusterCfnProvisioner(mock(RedshiftService.class));
        StackResource r = new StackResource();
        r.setResourceType("AWS::Redshift::ClusterParameterGroup");
        r.setLogicalId("ParamGroup");
        assertThrows(IllegalStateException.class, () -> p.provision(r, json("{}"), ctx(null)));
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
}
