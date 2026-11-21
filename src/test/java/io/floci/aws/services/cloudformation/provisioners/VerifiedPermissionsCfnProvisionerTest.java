package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.verifiedpermissions.VerifiedPermissionsService;
import io.floci.aws.services.verifiedpermissions.model.EntityIdentifier;
import io.floci.aws.services.verifiedpermissions.model.Policy;
import io.floci.aws.services.verifiedpermissions.model.PolicyStore;
import io.floci.aws.services.verifiedpermissions.model.PolicyTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Verified Permissions CFN provisioner in isolation, against a mocked {@link VerifiedPermissionsService}. */
class VerifiedPermissionsCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String STORE_ID = "PS1a2b3c4d5e6f7g8h9i0j";
    private static final String STORE_ARN = "arn:aws:verifiedpermissions::000000000000:policy-store/" + STORE_ID;
    private static final String KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/11111111-2222-3333-4444-555555555555";
    private static final String SCHEMA = "{\"Demo\":{\"entityTypes\":{},\"actions\":{}}}";
    private static final String TEMPLATE_STATEMENT = "permit(principal == ?principal, action, resource == ?resource);";
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    private final VerifiedPermissionsService service = mock(VerifiedPermissionsService.class);
    private final VerifiedPermissionsCfnProvisioner provisioner = new VerifiedPermissionsCfnProvisioner(service);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, ACCOUNT, "authz-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String priorPhysicalId, Map<String, String> attributes) {
        StackResource r = new StackResource();
        r.setLogicalId("Thing");
        r.setResourceType(type);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>(attributes));
        return r;
    }

    private static PolicyStore store(String mode, String schema, String keyArn) {
        return new PolicyStore(STORE_ID, STORE_ARN, mode, "DISABLED", "authz", keyArn,
                keyArn == null ? Map.of() : Map.of("tenant", "acme"), Map.of(), schema, null, null, NOW, NOW);
    }

    private static PolicyTemplate template(String storeId, String templateId) {
        return new PolicyTemplate(storeId, templateId, null, TEMPLATE_STATEMENT, null, NOW, NOW);
    }

    private static Policy staticPolicy(String storeId, String policyId) {
        return new Policy(storeId, policyId, null, "STATIC", "permit(principal, action, resource);", null,
                null, null, null, "permit", NOW, NOW);
    }

    private static Policy linkedPolicy(String storeId, String policyId, String principalId) {
        return new Policy(storeId, policyId, null, "TEMPLATE_LINKED", null, null, "PT1",
                new EntityIdentifier("Demo::User", principalId), new EntityIdentifier("Demo::Doc", "readme"),
                "permit", NOW, NOW);
    }

    private ObjectNode storeProps(String mode) {
        ObjectNode props = mapper.createObjectNode();
        props.putObject("ValidationSettings").put("Mode", mode);
        props.put("Description", "authz");
        return props;
    }

    private ObjectNode linkedDefinition(String principalId) {
        ObjectNode definition = mapper.createObjectNode();
        ObjectNode linked = definition.putObject("TemplateLinked");
        linked.put("PolicyTemplateId", "PT1");
        linked.putObject("Principal").put("EntityType", "Demo::User").put("EntityId", principalId);
        linked.putObject("Resource").put("EntityType", "Demo::Doc").put("EntityId", "readme");
        return definition;
    }

    @Test
    void policyStoreAnswersRefWithItsIdSetsEveryAttributeAndPutsTheSchemaAfterTheCreate() {
        when(service.createPolicyStore(any(), eq(REGION))).thenReturn(store("STRICT", null, null));
        when(service.putSchema(any(), eq(REGION))).thenReturn(store("STRICT", SCHEMA, null));
        ObjectNode props = storeProps("STRICT");
        props.putObject("DeletionProtection").put("Mode", "ENABLED");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "platform");
        props.putObject("Schema").put("CedarJson", SCHEMA);
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", null, Map.of());

        provisioner.provision(r, props, ctx(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createPolicyStore(request.capture(), eq(REGION));
        assertEquals("STRICT", request.getValue().path("validationSettings").path("mode").asText());
        assertEquals("authz", request.getValue().path("description").asText());
        assertEquals("ENABLED", request.getValue().path("deletionProtection").asText());
        assertEquals("platform", request.getValue().path("tags").path("team").asText());
        assertFalse(request.getValue().has("encryptionSettings"));
        ArgumentCaptor<JsonNode> schema = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).putSchema(schema.capture(), eq(REGION));
        assertEquals(STORE_ID, schema.getValue().path("policyStoreId").asText());
        assertEquals(SCHEMA, schema.getValue().path("definition").path("cedarJson").asText());
        assertEquals(STORE_ID, r.getPhysicalId());
        assertEquals(STORE_ARN, r.getAttributes().get("Arn"));
        assertEquals(STORE_ID, r.getAttributes().get("PolicyStoreId"));
        assertEquals("{\"Default\":{}}", r.getAttributes().get("EncryptionState"));
    }

    @Test
    void policyStoreSendsItsKmsEncryptionAndReportsItsEncryptionState() {
        when(service.createPolicyStore(any(), eq(REGION))).thenReturn(store("OFF", null, KEY_ARN));
        ObjectNode props = storeProps("OFF");
        ObjectNode kms = props.putObject("EncryptionSettings").putObject("KmsEncryptionSettings");
        kms.put("Key", KEY_ARN);
        kms.putObject("EncryptionContext").put("tenant", "acme");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", null, Map.of());

        provisioner.provision(r, props, ctx(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createPolicyStore(request.capture(), eq(REGION));
        JsonNode encryption = request.getValue().path("encryptionSettings").path("kmsEncryptionSettings");
        assertEquals(KEY_ARN, encryption.path("key").asText());
        assertEquals("acme", encryption.path("encryptionContext").path("tenant").asText());
        verify(service, never()).putSchema(any(), any());
        assertEquals("{\"KmsEncryptionState\":{\"Key\":\"" + KEY_ARN + "\",\"EncryptionContext\":{\"tenant\":\"acme\"}}}",
                r.getAttributes().get("EncryptionState"));
    }

    @Test
    void policyStoreWithoutValidationSettingsIsRefused() {
        ObjectNode props = mapper.createObjectNode().put("Description", "authz");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx(null)));

        assertEquals("ValidationError", e.getErrorCode());
        verify(service, never()).createPolicyStore(any(), any());
    }

    @Test
    void policyStoreThatCannotTakeItsSchemaIsRemovedAgain() {
        when(service.createPolicyStore(any(), eq(REGION))).thenReturn(store("STRICT", null, null));
        AwsException rejected = new AwsException("ValidationException", "The Cedar schema is invalid", 400);
        when(service.putSchema(any(), eq(REGION))).thenThrow(rejected);
        ObjectNode props = storeProps("STRICT");
        props.putObject("Schema").put("CedarJson", "{\"Demo\":{}}");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx(null)));

        assertSame(rejected, e);
        verify(service).deletePolicyStore(STORE_ID, REGION);
    }

    @Test
    void policyStoreUpdateKeepsItsIdUpdatesItsSettingsClearsADroppedSchemaAndReconcilesTags() {
        when(service.getPolicyStore(STORE_ID, REGION)).thenReturn(store("STRICT", SCHEMA, null));
        when(service.updatePolicyStore(any(), eq(REGION))).thenReturn(store("OFF", null, null));
        when(service.listTags(STORE_ARN, REGION)).thenReturn(Map.of("team", "platform", "stale", "yes"));
        ObjectNode props = storeProps("OFF");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "data");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", STORE_ID,
                Map.of("Arn", STORE_ARN, "PolicyStoreId", STORE_ID));

        provisioner.provision(r, props, ctx(STORE_ID));

        ArgumentCaptor<JsonNode> schema = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).putSchema(schema.capture(), eq(REGION));
        assertEquals("{}", schema.getValue().path("definition").path("cedarJson").asText());
        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicyStore(request.capture(), eq(REGION));
        assertEquals(STORE_ID, request.getValue().path("policyStoreId").asText());
        assertEquals("OFF", request.getValue().path("validationSettings").path("mode").asText());
        assertEquals("DISABLED", request.getValue().path("deletionProtection").asText());
        verify(service).untagResource(STORE_ARN, List.of("stale"), REGION);
        verify(service).tagResource(STORE_ARN, Map.of("team", "data"), REGION);
        verify(service, never()).createPolicyStore(any(), any());
        assertEquals(STORE_ID, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void policyTemplateAnswersRefWithStoreAndTemplateIdsJoinedByAPipe() {
        when(service.createPolicyTemplate(any(), eq(REGION))).thenReturn(template(STORE_ID, "PT1"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", STORE_ID);
        props.put("Statement", TEMPLATE_STATEMENT);
        props.put("Description", "per-document grants");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyTemplate", null, Map.of());

        provisioner.provision(r, props, ctx(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createPolicyTemplate(request.capture(), eq(REGION));
        assertEquals(STORE_ID, request.getValue().path("policyStoreId").asText());
        assertEquals(TEMPLATE_STATEMENT, request.getValue().path("statement").asText());
        assertEquals("per-document grants", request.getValue().path("description").asText());
        assertEquals(STORE_ID + "|PT1", r.getPhysicalId());
        assertEquals("PT1", r.getAttributes().get("PolicyTemplateId"));
    }

    @Test
    void policyTemplateUpdatesInPlaceInItsStoreAndIsReplacedWhenTheStoreChanges() {
        when(service.updatePolicyTemplate(any(), eq(REGION))).thenReturn(template(STORE_ID, "PT1"));
        when(service.createPolicyTemplate(any(), eq(REGION))).thenReturn(template("PS2", "PT2"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", STORE_ID);
        props.put("Statement", TEMPLATE_STATEMENT);
        StackResource same = resource("AWS::VerifiedPermissions::PolicyTemplate", STORE_ID + "|PT1",
                Map.of("PolicyTemplateId", "PT1"));

        provisioner.provision(same, props, ctx(STORE_ID + "|PT1"));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicyTemplate(request.capture(), eq(REGION));
        assertEquals("PT1", request.getValue().path("policyTemplateId").asText());
        assertEquals(STORE_ID + "|PT1", same.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(same));

        props.put("PolicyStoreId", "PS2");
        StackResource moved = resource("AWS::VerifiedPermissions::PolicyTemplate", STORE_ID + "|PT1",
                Map.of("PolicyTemplateId", "PT1"));

        provisioner.provision(moved, props, ctx(STORE_ID + "|PT1"));

        assertEquals("PS2|PT2", moved.getPhysicalId());
        assertEquals("PT2", moved.getAttributes().get("PolicyTemplateId"));
        assertTrue(provisioner.hasReplacementUpdate(moved));
        assertEquals(STORE_ID + "|PT1", provisioner.updateCleanupPhysicalId(moved));
    }

    @Test
    void staticPolicyAnswersRefWithPolicyAndStoreIdsAndSendsTheDefinitionInTheApiSpelling() {
        when(service.createPolicy(any(), eq(REGION))).thenReturn(staticPolicy(STORE_ID, "SP1"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", STORE_ID);
        props.putObject("Definition").putObject("Static")
                .put("Statement", "permit(principal, action, resource);")
                .put("Description", "everyone");
        StackResource r = resource("AWS::VerifiedPermissions::Policy", null, Map.of());

        provisioner.provision(r, props, ctx(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createPolicy(request.capture(), eq(REGION));
        JsonNode definition = request.getValue().path("definition");
        assertEquals("permit(principal, action, resource);", definition.path("static").path("statement").asText());
        assertEquals("everyone", definition.path("static").path("description").asText());
        assertEquals("SP1|" + STORE_ID, r.getPhysicalId());
        assertEquals("SP1", r.getAttributes().get("PolicyId"));
        assertEquals("STATIC", r.getAttributes().get("PolicyType"));
        assertFalse(provisioner.idPrefixCanNameParent("AWS::VerifiedPermissions::Policy"));
        assertTrue(provisioner.idPrefixCanNameParent("AWS::VerifiedPermissions::PolicyTemplate"));
    }

    @Test
    void staticPolicyUpdatesInPlaceInItsStore() {
        when(service.getPolicy(STORE_ID, "SP1", REGION)).thenReturn(staticPolicy(STORE_ID, "SP1"));
        when(service.updatePolicy(any(), eq(REGION))).thenReturn(staticPolicy(STORE_ID, "SP1"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", STORE_ID);
        props.putObject("Definition").putObject("Static").put("Statement", "permit(principal, action, resource);");
        StackResource r = resource("AWS::VerifiedPermissions::Policy", "SP1|" + STORE_ID,
                Map.of("PolicyId", "SP1", "PolicyType", "STATIC"));

        provisioner.provision(r, props, ctx("SP1|" + STORE_ID));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicy(request.capture(), eq(REGION));
        assertEquals("SP1", request.getValue().path("policyId").asText());
        assertEquals(STORE_ID, request.getValue().path("policyStoreId").asText());
        verify(service, never()).createPolicy(any(), any());
        assertEquals("SP1|" + STORE_ID, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void templateLinkedPolicyIsCreatedWithItsSlotsAndLeftAloneByAnUpdateThatKeepsThem() {
        when(service.createPolicy(any(), eq(REGION))).thenReturn(linkedPolicy(STORE_ID, "LP1", "alice"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", STORE_ID);
        props.set("Definition", linkedDefinition("alice"));
        StackResource r = resource("AWS::VerifiedPermissions::Policy", null, Map.of());

        provisioner.provision(r, props, ctx(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createPolicy(request.capture(), eq(REGION));
        JsonNode linked = request.getValue().path("definition").path("templateLinked");
        assertEquals("PT1", linked.path("policyTemplateId").asText());
        assertEquals("alice", linked.path("principal").path("entityId").asText());
        assertEquals("Demo::Doc", linked.path("resource").path("entityType").asText());
        assertEquals("LP1|" + STORE_ID, r.getPhysicalId());
        assertEquals("TEMPLATE_LINKED", r.getAttributes().get("PolicyType"));

        when(service.getPolicy(STORE_ID, "LP1", REGION)).thenReturn(linkedPolicy(STORE_ID, "LP1", "alice"));
        StackResource again = resource("AWS::VerifiedPermissions::Policy", "LP1|" + STORE_ID,
                Map.of("PolicyId", "LP1", "PolicyType", "TEMPLATE_LINKED"));

        provisioner.provision(again, props, ctx("LP1|" + STORE_ID));

        verify(service, never()).updatePolicy(any(), any());
        assertEquals("LP1|" + STORE_ID, again.getPhysicalId());
        assertEquals("LP1", again.getAttributes().get("PolicyId"));
    }

    @Test
    void templateLinkedPolicyRefusesAnUpdateOfItsDefinition() {
        when(service.getPolicy(STORE_ID, "LP1", REGION)).thenReturn(linkedPolicy(STORE_ID, "LP1", "alice"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", STORE_ID);
        props.set("Definition", linkedDefinition("bob"));
        StackResource r = resource("AWS::VerifiedPermissions::Policy", "LP1|" + STORE_ID,
                Map.of("PolicyId", "LP1", "PolicyType", "TEMPLATE_LINKED"));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx("LP1|" + STORE_ID)));

        assertEquals("ValidationError", e.getErrorCode());
        verify(service, never()).updatePolicy(any(), any());
        verify(service, never()).createPolicy(any(), any());
    }

    @Test
    void deleteNamesTheStoreAndEntityEachIdCarriesAndToleratesOneAlreadyGone() {
        provisioner.delete("AWS::VerifiedPermissions::PolicyStore", STORE_ID, REGION);
        provisioner.delete("AWS::VerifiedPermissions::PolicyTemplate", STORE_ID + "|PT1", REGION);
        provisioner.delete("AWS::VerifiedPermissions::Policy", "SP1|" + STORE_ID, REGION);

        verify(service).deletePolicyStore(STORE_ID, REGION);
        verify(service).deletePolicyTemplate(STORE_ID, "PT1", REGION);
        verify(service).deletePolicy(STORE_ID, "SP1", REGION);

        doThrow(new AwsException("ResourceNotFoundException", "Policy store not found", 400))
                .when(service).deletePolicyTemplate("PS9", "PT9", REGION);
        provisioner.delete("AWS::VerifiedPermissions::PolicyTemplate", "PS9|PT9", REGION);

        AwsException protectedStore = new AwsException("InvalidStateException",
                "The policy store can't be deleted because deletion protection is enabled.", 400);
        doThrow(protectedStore).when(service).deletePolicyStore("PS9", REGION);
        assertSame(protectedStore, assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::VerifiedPermissions::PolicyStore", "PS9", REGION)));
        verify(service, never()).tagResource(any(), anyMap(), any());
        verify(service, never()).untagResource(any(), anyList(), any());
        assertNull(provisioner.updateCleanupPhysicalId(resource("AWS::VerifiedPermissions::Policy", null, Map.of())));
    }
}
