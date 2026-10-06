package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.redshiftserverless.WorkgroupSettings;
import io.github.hectorvent.floci.services.redshiftserverless.model.ConfigParameter;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.PricePerformanceTarget;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Redshift Serverless CFN provisioner in isolation, against a mocked service. */
class RedshiftServerlessCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String NAMESPACE_TYPE = "AWS::RedshiftServerless::Namespace";
    private static final String WORKGROUP_TYPE = "AWS::RedshiftServerless::Workgroup";
    private static final String NAMESPACE_ARN = "arn:aws:redshift-serverless:us-east-1:000000000000:namespace/ns-id";
    private static final String WORKGROUP_ARN = "arn:aws:redshift-serverless:us-east-1:000000000000:workgroup/wg-id";

    private final RedshiftServerlessService service = mock(RedshiftServerlessService.class);
    private final RedshiftServerlessCfnProvisioner provisioner = new RedshiftServerlessCfnProvisioner(service);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("MyResource");
        r.setResourceType(type);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static Namespace namespace(String name) {
        Namespace namespace = new Namespace();
        namespace.setNamespaceName(name);
        namespace.setNamespaceId("ns-id");
        namespace.setNamespaceArn(NAMESPACE_ARN);
        namespace.setAdminUsername("admin");
        namespace.setDbName("dev");
        namespace.setKmsKeyId("AWS_OWNED_KMS_KEY");
        namespace.setIamRoles(new ArrayList<>(List.of("arn:aws:iam::000000000000:role/a")));
        namespace.setLogExports(new ArrayList<>(List.of("userlog")));
        namespace.setStatus("AVAILABLE");
        namespace.setCreationDate(Instant.parse("2026-10-03T00:00:00Z"));
        return namespace;
    }

    private static Workgroup workgroup(String name, String namespaceName) {
        Workgroup workgroup = new Workgroup();
        workgroup.setWorkgroupName(name);
        workgroup.setWorkgroupId("wg-id");
        workgroup.setWorkgroupArn(WORKGROUP_ARN);
        workgroup.setNamespaceName(namespaceName);
        workgroup.setBaseCapacity(32);
        workgroup.setTrackName("current");
        workgroup.setStatus("AVAILABLE");
        workgroup.setCreationDate(Instant.parse("2026-10-03T00:00:00Z"));
        workgroup.setSecurityGroupIds(new ArrayList<>(List.of("sg-1", "sg-2")));
        workgroup.setEndpoint(new Endpoint("localhost", 7100));
        workgroup.setConfigParameters(new ArrayList<>(List.of(new ConfigParameter("datestyle", "ISO, MDY"))));
        return workgroup;
    }

    @Test
    void createNamespaceSendsPropertiesAndRecordsEveryDottedAttribute() {
        when(service.createNamespace(eq("my-ns"), eq("admin"), eq("Secret123"), eq("analytics"), isNull(),
                isNull(), anyList(), anyList(), anyMap(), eq(REGION))).thenReturn(namespace("my-ns"));
        ObjectNode props = mapper.createObjectNode();
        props.put("NamespaceName", "my-ns");
        props.put("AdminUsername", "admin");
        props.put("AdminUserPassword", "Secret123");
        props.put("DbName", "analytics");
        props.putObject("Tags").put("env", "dev");
        StackResource r = resource(NAMESPACE_TYPE, null);

        provisioner.provision(r, props, ctx(null));

        assertEquals("my-ns", r.getPhysicalId());
        Map<String, String> attributes = r.getAttributes();
        assertEquals("my-ns", attributes.get("Namespace.NamespaceName"));
        assertEquals("ns-id", attributes.get("Namespace.NamespaceId"));
        assertEquals(NAMESPACE_ARN, attributes.get("Namespace.NamespaceArn"));
        assertEquals("dev", attributes.get("Namespace.DbName"));
        assertEquals("AWS_OWNED_KMS_KEY", attributes.get("Namespace.KmsKeyId"));
        assertEquals("AVAILABLE", attributes.get("Namespace.Status"));
        assertEquals("2026-10-03T00:00:00Z", attributes.get("Namespace.CreationDate"));
        assertEquals("admin", attributes.get("Namespace.AdminUsername"));
        assertEquals("arn:aws:iam::000000000000:role/a", attributes.get("Namespace.IamRoles"));
        assertEquals("userlog", attributes.get("Namespace.LogExports"));
        assertFalse(attributes.containsKey("Namespace.DefaultIamRoleArn"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> tags = ArgumentCaptor.forClass(Map.class);
        verify(service).createNamespace(eq("my-ns"), eq("admin"), eq("Secret123"), eq("analytics"), isNull(),
                isNull(), anyList(), anyList(), tags.capture(), eq(REGION));
        assertEquals(Map.of("env", "dev"), tags.getValue());
    }

    @Test
    void anUnnamedNamespaceGetsALowercaseGeneratedNameThatIsStableAcrossUpdates() {
        when(service.createNamespace(any(), any(), any(), any(), any(), any(), anyList(), anyList(), anyMap(),
                eq(REGION)))
                .thenAnswer(inv -> namespace(inv.getArgument(0)));
        StackResource created = resource(NAMESPACE_TYPE, null);

        provisioner.provision(created, mapper.createObjectNode(), ctx(null));

        String generated = created.getPhysicalId();
        assertTrue(generated.startsWith("my-stack-myresource-"), generated);
        assertEquals(generated, generated.toLowerCase());

        when(service.getNamespace(generated, REGION)).thenReturn(namespace(generated));
        when(service.reconcileNamespace(eq(generated), any(), any(), any(), any(), anyList(), anyList(), eq(REGION)))
                .thenReturn(namespace(generated));
        when(service.listTagsForResource(NAMESPACE_ARN, REGION)).thenReturn(Map.of());
        StackResource updated = resource(NAMESPACE_TYPE, generated);

        provisioner.provision(updated, mapper.createObjectNode(), ctx(generated));

        assertEquals(generated, updated.getPhysicalId());
        verify(service).reconcileNamespace(eq(generated), any(), any(), any(), any(), anyList(), anyList(), eq(REGION));
    }

    @Test
    void updateNamespaceUpdatesInPlaceAndRemovesTagsTheTemplateDropped() {
        when(service.getNamespace("my-ns", REGION)).thenReturn(namespace("my-ns"));
        when(service.reconcileNamespace(eq("my-ns"), isNull(), isNull(), eq("custom-key"), isNull(), anyList(),
                anyList(), eq(REGION))).thenReturn(namespace("my-ns"));
        when(service.listTagsForResource(NAMESPACE_ARN, REGION)).thenReturn(Map.of("old", "1", "keep", "2"));
        ObjectNode props = mapper.createObjectNode();
        props.put("NamespaceName", "my-ns");
        props.put("KmsKeyId", "custom-key");
        props.putObject("Tags").put("keep", "2");
        StackResource r = resource(NAMESPACE_TYPE, "my-ns");

        provisioner.provision(r, props, ctx("my-ns"));

        verify(service, never()).createNamespace(any(), any(), any(), any(), any(), any(), anyList(), anyList(),
                anyMap(), any());
        verify(service).untagResource(NAMESPACE_ARN, List.of("old"), REGION);
        verify(service).tagResource(NAMESPACE_ARN, Map.of("keep", "2"), REGION);
        assertEquals("my-ns", r.getPhysicalId());
    }

    @Test
    void updateNamespaceRejectsADbNameChangeBecauseItIsFixedAtCreation() {
        when(service.getNamespace("my-ns", REGION)).thenReturn(namespace("my-ns"));
        ObjectNode props = mapper.createObjectNode();
        props.put("NamespaceName", "my-ns");
        props.put("DbName", "analytics");
        StackResource r = resource(NAMESPACE_TYPE, "my-ns");

        AwsException rejected = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx("my-ns")));

        assertEquals("ValidationException", rejected.getErrorCode());
        verify(service, never()).reconcileNamespace(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateNamespaceSendsOmittedKmsKeyAndDefaultRoleAsNullSoTheServiceResetsThem() {
        when(service.getNamespace("my-ns", REGION)).thenReturn(namespace("my-ns"));
        when(service.reconcileNamespace(eq("my-ns"), isNull(), isNull(), isNull(), isNull(), anyList(), anyList(),
                eq(REGION))).thenReturn(namespace("my-ns"));
        when(service.listTagsForResource(NAMESPACE_ARN, REGION)).thenReturn(Map.of());
        ObjectNode props = mapper.createObjectNode();
        props.put("NamespaceName", "my-ns");
        props.put("DbName", "dev");
        StackResource r = resource(NAMESPACE_TYPE, "my-ns");

        provisioner.provision(r, props, ctx("my-ns"));

        verify(service).reconcileNamespace(eq("my-ns"), isNull(), isNull(), isNull(), isNull(), anyList(),
                anyList(), eq(REGION));
    }

    @Test
    void updateNamespaceDropsTheDefaultRoleAttributeWhenTheTemplateNoLongerDeclaresIt() {
        when(service.getNamespace("my-ns", REGION)).thenReturn(namespace("my-ns"));
        when(service.reconcileNamespace(eq("my-ns"), isNull(), isNull(), isNull(), isNull(), anyList(), anyList(),
                eq(REGION))).thenReturn(namespace("my-ns"));
        when(service.listTagsForResource(NAMESPACE_ARN, REGION)).thenReturn(Map.of());
        ObjectNode props = mapper.createObjectNode();
        props.put("NamespaceName", "my-ns");
        StackResource r = resource(NAMESPACE_TYPE, "my-ns");
        r.getAttributes().put("Namespace.DefaultIamRoleArn", "arn:aws:iam::000000000000:role/old");

        provisioner.provision(r, props, ctx("my-ns"));

        assertFalse(r.getAttributes().containsKey("Namespace.DefaultIamRoleArn"));
    }

    @Test
    void createWorkgroupParsesConfigParametersResolvedFromAnotherWorkgroupAttribute() {
        when(service.createWorkgroup(eq("my-wg"), eq("my-ns"), any(WorkgroupSettings.class), anyMap(), eq(REGION)))
                .thenReturn(workgroup("my-wg", "my-ns"));
        ObjectNode props = mapper.createObjectNode();
        props.put("WorkgroupName", "my-wg");
        props.put("NamespaceName", "my-ns");
        props.put("ConfigParameters", "[{\"ParameterKey\":\"datestyle\",\"ParameterValue\":\"ISO, MDY\"}]");

        provisioner.provision(resource(WORKGROUP_TYPE, null), props, ctx(null));

        ArgumentCaptor<WorkgroupSettings> settings = ArgumentCaptor.forClass(WorkgroupSettings.class);
        verify(service).createWorkgroup(eq("my-wg"), eq("my-ns"), settings.capture(), anyMap(), eq(REGION));
        assertEquals(1, settings.getValue().configParameters().size());
        assertEquals("datestyle", settings.getValue().configParameters().get(0).getParameterKey());
        assertEquals("ISO, MDY", settings.getValue().configParameters().get(0).getParameterValue());
    }

    @Test
    void createWorkgroupRejectsConfigParametersThatAreNotAList() {
        ObjectNode props = mapper.createObjectNode();
        props.put("WorkgroupName", "my-wg");
        props.put("NamespaceName", "my-ns");
        props.put("ConfigParameters", "not-json");

        AwsException rejected = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(WORKGROUP_TYPE, null), props, ctx(null)));

        assertEquals("ValidationException", rejected.getErrorCode());
    }

    @Test
    void createWorkgroupMapsEveryPropertyAndRecordsTheEndpointAttributes() {
        when(service.createWorkgroup(eq("my-wg"), eq("my-ns"), any(WorkgroupSettings.class), anyMap(), eq(REGION)))
                .thenReturn(workgroup("my-wg", "my-ns"));
        ObjectNode props = mapper.createObjectNode();
        props.put("WorkgroupName", "my-wg");
        props.put("NamespaceName", "my-ns");
        props.put("BaseCapacity", 32);
        props.put("MaxCapacity", 64);
        props.put("EnhancedVpcRouting", false);
        props.put("PubliclyAccessible", true);
        props.put("Port", 5440);
        props.put("TrackName", "trailing");
        props.putArray("SecurityGroupIds").add("sg-1");
        props.putArray("SubnetIds").add("subnet-1");
        props.putArray("ConfigParameters").addObject()
                .put("ParameterKey", "datestyle").put("ParameterValue", "ISO, MDY");
        props.putObject("PricePerformanceTarget").put("Status", "ENABLED").put("Level", 50);
        StackResource r = resource(WORKGROUP_TYPE, null);

        provisioner.provision(r, props, ctx(null));

        ArgumentCaptor<WorkgroupSettings> settings = ArgumentCaptor.forClass(WorkgroupSettings.class);
        verify(service).createWorkgroup(eq("my-wg"), eq("my-ns"), settings.capture(), anyMap(), eq(REGION));
        WorkgroupSettings sent = settings.getValue();
        assertEquals(32, sent.baseCapacity());
        assertEquals(64, sent.maxCapacity());
        assertEquals(Boolean.FALSE, sent.enhancedVpcRouting());
        assertEquals(Boolean.TRUE, sent.publiclyAccessible());
        assertEquals(5440, sent.port());
        assertEquals("trailing", sent.trackName());
        assertEquals("datestyle", sent.configParameters().get(0).getParameterKey());
        PricePerformanceTarget target = sent.pricePerformanceTarget();
        assertEquals("ENABLED", target.getStatus());
        assertEquals(50, target.getLevel());

        assertEquals("my-wg", r.getPhysicalId());
        Map<String, String> attributes = r.getAttributes();
        assertEquals("wg-id", attributes.get("Workgroup.WorkgroupId"));
        assertEquals(WORKGROUP_ARN, attributes.get("Workgroup.WorkgroupArn"));
        assertEquals("my-ns", attributes.get("Workgroup.NamespaceName"));
        assertEquals("localhost", attributes.get("Workgroup.Endpoint.Address"));
        assertEquals("7100", attributes.get("Workgroup.Endpoint.Port"));
        assertEquals("32", attributes.get("Workgroup.BaseCapacity"));
        assertEquals("sg-1,sg-2", attributes.get("Workgroup.SecurityGroupIds"));
        assertEquals("[{\"ParameterKey\":\"datestyle\",\"ParameterValue\":\"ISO, MDY\"}]",
                attributes.get("Workgroup.ConfigParameters"));
        assertEquals("AVAILABLE", attributes.get("Workgroup.Status"));
        assertFalse(attributes.containsKey("Workgroup.MaxCapacity"), "unset on the stored workgroup");
    }

    @Test
    void updateWorkgroupUpdatesInPlaceAndRejectsANamespaceChange() {
        when(service.getWorkgroup("my-wg", REGION)).thenReturn(workgroup("my-wg", "my-ns"));
        when(service.updateWorkgroup(eq("my-wg"), any(WorkgroupSettings.class), eq(REGION)))
                .thenReturn(workgroup("my-wg", "my-ns"));
        when(service.listTagsForResource(WORKGROUP_ARN, REGION)).thenReturn(Map.of());
        ObjectNode props = mapper.createObjectNode();
        props.put("WorkgroupName", "my-wg");
        props.put("NamespaceName", "my-ns");
        props.put("BaseCapacity", 64);

        provisioner.provision(resource(WORKGROUP_TYPE, "my-wg"), props, ctx("my-wg"));

        verify(service).updateWorkgroup(eq("my-wg"), any(WorkgroupSettings.class), eq(REGION));
        verify(service, never()).createWorkgroup(any(), any(), any(), any(), any());

        ObjectNode moved = mapper.createObjectNode();
        moved.put("WorkgroupName", "my-wg");
        moved.put("NamespaceName", "other-ns");
        AwsException rejected = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(WORKGROUP_TYPE, "my-wg"), moved, ctx("my-wg")));
        assertEquals("ValidationException", rejected.getErrorCode());
    }

    @Test
    void anUpdateThatOmitsAPropertyResetsItToTheDefaultInsteadOfKeepingTheStoredValue() {
        when(service.getWorkgroup("my-wg", REGION)).thenReturn(workgroup("my-wg", "my-ns"));
        when(service.updateWorkgroup(eq("my-wg"), any(WorkgroupSettings.class), eq(REGION)))
                .thenReturn(workgroup("my-wg", "my-ns"));
        when(service.listTagsForResource(WORKGROUP_ARN, REGION)).thenReturn(Map.of());
        ObjectNode props = mapper.createObjectNode();
        props.put("WorkgroupName", "my-wg");
        props.put("NamespaceName", "my-ns");

        provisioner.provision(resource(WORKGROUP_TYPE, "my-wg"), props, ctx("my-wg"));

        ArgumentCaptor<WorkgroupSettings> settings = ArgumentCaptor.forClass(WorkgroupSettings.class);
        verify(service).updateWorkgroup(eq("my-wg"), settings.capture(), eq(REGION));
        WorkgroupSettings sent = settings.getValue();
        assertEquals(RedshiftServerlessService.DEFAULT_BASE_CAPACITY, sent.baseCapacity());
        assertEquals(Boolean.FALSE, sent.enhancedVpcRouting());
        assertEquals(Boolean.FALSE, sent.publiclyAccessible());
        assertEquals(RedshiftServerlessService.DEFAULT_PORT, sent.port());
        assertEquals(RedshiftServerlessService.DEFAULT_TRACK_NAME, sent.trackName());
        assertEquals("DISABLED", sent.pricePerformanceTarget().getStatus());
        assertTrue(sent.configParameters().isEmpty());
        assertTrue(sent.subnetIds().isEmpty());
    }

    @Test
    void aNonNumericCapacityIsAValidationError() {
        ObjectNode props = mapper.createObjectNode();
        props.put("WorkgroupName", "my-wg");
        props.put("NamespaceName", "my-ns");
        props.put("BaseCapacity", "many");

        AwsException rejected = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(WORKGROUP_TYPE, null), props, ctx(null)));

        assertEquals("ValidationException", rejected.getErrorCode());
        verify(service, never()).createWorkgroup(any(), any(), any(), any(), any());
    }

    @Test
    void deleteToleratesOnlyNotFoundAndLetsOtherFailuresReachTheStack() {
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(service).deleteWorkgroup("gone-wg", REGION);
        provisioner.delete(WORKGROUP_TYPE, "gone-wg", REGION);

        doThrow(new AwsException("ConflictException", "still has a workgroup", 409))
                .when(service).deleteNamespace("busy-ns", REGION);
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(NAMESPACE_TYPE, "busy-ns", REGION));
        assertEquals("ConflictException", failure.getErrorCode());

        provisioner.delete(NAMESPACE_TYPE, "ok-ns", REGION);
        verify(service).deleteNamespace("ok-ns", REGION);
    }

    @Test
    void theProvisionerServesBothTypes() {
        assertEquals(Set.of(NAMESPACE_TYPE, WORKGROUP_TYPE), provisioner.resourceTypes());
    }
}
