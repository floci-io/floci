package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.dsql.DsqlService;
import io.floci.aws.services.dsql.model.DsqlCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::DSQL::Cluster} against a mocked {@link DsqlService}, with what CloudFormation was
 * measured to do (eu-west-1, 2026-10-20): properties read against the resource schema, a create
 * that settles on PENDING_SETUP for a multi-Region cluster, and an update that leaves a property it
 * no longer sets as it was, but for the tags and the policy.
 */
class DsqlCfnProvisionerTest {

    private static final String REGION = "eu-west-1";
    private static final String ID = "aaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String ARN = "arn:aws:dsql:eu-west-1:111122223333:cluster/" + ID;
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    private final DsqlService dsql = mock(DsqlService.class);
    private final DsqlCfnProvisioner provisioner = new DsqlCfnProvisioner(dsql);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aCreateReadsTextAsTheSchemaTypesItAndSettlesOnPendingSetup() throws Exception {
        DsqlCluster cluster = cluster("PENDING_SETUP", Map.of());
        cluster.setPolicy(POLICY);
        cluster.setPolicyVersion("1792496211976");
        when(dsql.createCluster(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(cluster);
        when(dsql.getCluster(REGION, ID)).thenReturn(cluster);
        StackResource resource = resource();

        provisioner.provision(resource, props("""
                {"DeletionProtectionEnabled": "FALSE", "Tags": [{"Key": "team", "Value": "data"}],
                 "MultiRegionProperties": {"WitnessRegion": "eu-west-2"}, "PolicyDocument": %s}
                """.formatted(mapper.writeValueAsString(POLICY))), context(null));

        verify(dsql).createCluster(REGION, false, null, Map.of("team", "data"), null, "eu-west-2", null, POLICY, false);
        assertEquals(ID, resource.getPhysicalId());
        assertEquals("PENDING_SETUP", resource.getAttributes().get("Status"));
        assertEquals("1792496211976", resource.getAttributes().get("PolicyVersion"));
        assertEquals(ARN, resource.getAttributes().get("ResourceArn"));
    }

    @Test
    void anUpdateLeavesWhatTheTemplateNoLongerSetsButTheTagsAndThePolicy() throws Exception {
        DsqlCluster cluster = cluster("ACTIVE", Map.of("team", "data", "env", "test"));
        when(dsql.getCluster(REGION, ID)).thenReturn(cluster);
        StackResource resource = resource();
        resource.getAttributes().put("PolicyVersion", "1792496211976");

        provisioner.provision(resource, props("{\"Tags\": [{\"Key\": \"team\", \"Value\": \"platform\"}]}"),
                context(ID));

        verify(dsql).updateCluster(REGION, ID, null, null, null, null);
        verify(dsql).untagResource(REGION, ARN, List.of("env"));
        verify(dsql).tagResource(REGION, ARN, Map.of("team", "platform"));
        verify(dsql).replaceClusterPolicy(REGION, ID, null);
        verify(dsql, never()).createCluster(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        assertFalse(resource.getAttributes().containsKey("PolicyVersion"));
    }

    @Test
    void anUpdateHandsOverTheValuesTheTemplateSets() throws Exception {
        when(dsql.getCluster(REGION, ID)).thenReturn(cluster("ACTIVE", Map.of()));

        provisioner.provision(resource(), props("""
                {"DeletionProtectionEnabled": true, "KmsEncryptionKey": "AWS_OWNED_KMS_KEY",
                 "MultiRegionProperties": {"WitnessRegion": "eu-west-2", "Clusters": ["arn:aws:dsql:eu-west-3:111122223333:cluster/b"]},
                 "PolicyDocument": %s}
                """.formatted(mapper.writeValueAsString(POLICY))), context(ID));

        verify(dsql).updateCluster(REGION, ID, true, "AWS_OWNED_KMS_KEY", "eu-west-2",
                List.of("arn:aws:dsql:eu-west-3:111122223333:cluster/b"));
        verify(dsql).replaceClusterPolicy(REGION, ID, POLICY);
    }

    @Test
    void noValueLeavesAPropertyOut() throws Exception {
        when(dsql.getCluster(REGION, ID)).thenReturn(cluster("ACTIVE", Map.of()));

        provisioner.provision(resource(), props("{\"DeletionProtectionEnabled\": {\"Ref\": \"AWS::NoValue\"}}"),
                context(ID));

        verify(dsql).updateCluster(eq(REGION), eq(ID), isNull(), isNull(), isNull(), isNull());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"PolicyDocument\": {\"Version\": \"2012-10-17\"}} | Property [PolicyDocument] expected type: String, found: JSONObject",
        "{\"Tags\": \"x\"} | Property [Tags] expected type: JSONArray, found: String",
        "{\"MultiRegionProperties\": \"x\"} | Property [MultiRegionProperties] expected type: JSONObject, found: String",
        "{\"DeletionProtectionEnabled\": \"yes\"} | Model validation failed (#/DeletionProtectionEnabled: expected type: Boolean, found: String)"
    })
    void aValueOfAnotherTypeFailsTheResourceBeforeAnythingIsCreated(String properties, String message) {
        AwsException failed = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props(properties), context(null)));

        assertEquals(message, failed.getMessage());
        verify(dsql, never()).createCluster(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    private ProvisionContext context(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = CloudFormationTemplateEngine.standalone("111122223333", REGION, "stack",
                mapper, UnaryOperator.identity());
        return new ProvisionContext(engine, REGION, "111122223333", "stack", priorPhysicalId);
    }

    private JsonNode props(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Cluster");
        resource.setResourceType("AWS::DSQL::Cluster");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private static DsqlCluster cluster(String status, Map<String, String> tags) {
        DsqlCluster cluster = new DsqlCluster();
        cluster.setIdentifier(ID);
        cluster.setArn(ARN);
        cluster.setStatus(status);
        cluster.setCreationTime(Instant.parse("2026-10-20T11:27:22.382Z"));
        cluster.setEndpoint(ID + ".dsql.eu-west-1.localhost.floci.io");
        cluster.setTags(new LinkedHashMap<>(tags));
        return cluster;
    }
}
