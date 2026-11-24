package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import org.mockito.InOrder;

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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
        return ctx(priorPhysicalId, null);
    }

    /** An update whose previous template declared {@code priorProperties}, which the engine's changes compare against. */
    private ProvisionContext ctx(String priorPhysicalId, JsonNode priorProperties) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, ACCOUNT, "authz-stack", priorPhysicalId, event -> { },
                priorProperties);
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
        return staticPolicy(storeId, policyId, null);
    }

    private static Policy staticPolicy(String storeId, String policyId, String description) {
        return new Policy(storeId, policyId, null, "STATIC", "permit(principal, action, resource);", description,
                null, null, null, "permit", NOW, NOW);
    }

    /** What the dispatcher left for this type in a stack from before it had a provisioner. */
    private static Map<String, String> stubAttributes() {
        return Map.of("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + "Thing");
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

    /** The store's previous template: STRICT, with a schema and the team and stale tags. */
    private ObjectNode priorStore() {
        ObjectNode prior = storeProps("STRICT");
        prior.putObject("Schema").put("CedarJson", SCHEMA);
        ArrayNode tags = prior.putArray("Tags");
        tags.addObject().put("Key", "team").put("Value", "platform");
        tags.addObject().put("Key", "stale").put("Value", "yes");
        return prior;
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
        when(service.updatePolicyStore(any(), eq(REGION))).thenReturn(store("STRICT", SCHEMA, null));
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
        // Created unprotected, so a refused schema can still take it away; protection follows the schema.
        assertFalse(request.getValue().has("deletionProtection"));
        assertEquals("platform", request.getValue().path("tags").path("team").asText());
        assertFalse(request.getValue().has("encryptionSettings"));
        ArgumentCaptor<JsonNode> schema = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).putSchema(schema.capture(), eq(REGION));
        assertEquals(STORE_ID, schema.getValue().path("policyStoreId").asText());
        assertEquals(SCHEMA, schema.getValue().path("definition").path("cedarJson").asText());
        ArgumentCaptor<JsonNode> protect = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicyStore(protect.capture(), eq(REGION));
        assertEquals(STORE_ID, protect.getValue().path("policyStoreId").asText());
        assertEquals("ENABLED", protect.getValue().path("deletionProtection").asText());
        assertEquals("STRICT", protect.getValue().path("validationSettings").path("mode").asText());
        assertFalse(protect.getValue().has("tags"));
        assertEquals(STORE_ID, r.getPhysicalId());
        assertEquals(STORE_ARN, r.getAttributes().get("Arn"));
        assertEquals(STORE_ID, r.getAttributes().get("PolicyStoreId"));
        assertEquals("{\"Default\":{}}", r.getAttributes().get("EncryptionState"));
        assertEquals("[\"team\"]", r.getAttributes().get(VerifiedPermissionsCfnProvisioner.TAG_KEYS_ATTR));
    }

    // Catches: a store left standing when the deletion protection asked for after its schema is refused.
    @Test
    void policyStoreWhoseProtectionIsRefusedIsRemovedAgain() {
        when(service.createPolicyStore(any(), eq(REGION))).thenReturn(store("STRICT", null, null));
        when(service.putSchema(any(), eq(REGION))).thenReturn(store("STRICT", SCHEMA, null));
        AwsException refused = new AwsException("ValidationException", "deletionProtection is invalid", 400);
        when(service.updatePolicyStore(any(), eq(REGION))).thenThrow(refused);
        ObjectNode props = storeProps("STRICT");
        props.putObject("DeletionProtection").put("Mode", "SOMETIMES");
        props.putObject("Schema").put("CedarJson", SCHEMA);
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", null, Map.of());

        assertSame(refused, assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx(null))));

        verify(service).deletePolicyStore(STORE_ID, REGION);
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
        props.putObject("DeletionProtection").put("Mode", "ENABLED");
        props.putObject("Schema").put("CedarJson", "{\"Demo\":{}}");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx(null)));

        assertSame(rejected, e);
        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createPolicyStore(request.capture(), eq(REGION));
        assertFalse(request.getValue().has("deletionProtection"));
        verify(service).deletePolicyStore(STORE_ID, REGION);
        verify(service, never()).updatePolicyStore(any(), any());
    }

    // Catches: an update untagging keys added outside the stack, writing the schema before settings the
    // service then refuses, or leaving nothing for a rollback to put back.
    @Test
    void policyStoreUpdateKeepsItsIdChangesItsSettingsThenItsSchemaAndOnlyTheTagsTheStackOwns() {
        when(service.getPolicyStore(STORE_ID, REGION)).thenReturn(store("STRICT", SCHEMA, null));
        when(service.updatePolicyStore(any(), eq(REGION))).thenReturn(store("OFF", SCHEMA, null));
        when(service.listTags(STORE_ARN, REGION))
                .thenReturn(Map.of("team", "platform", "stale", "yes", "external", "kept"));
        ObjectNode props = storeProps("OFF");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "data");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", STORE_ID,
                Map.of("Arn", STORE_ARN, "PolicyStoreId", STORE_ID));

        provisioner.provision(r, props, ctx(STORE_ID, priorStore()));

        InOrder order = inOrder(service);
        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        order.verify(service).updatePolicyStore(request.capture(), eq(REGION));
        ArgumentCaptor<JsonNode> schema = ArgumentCaptor.forClass(JsonNode.class);
        order.verify(service).putSchema(schema.capture(), eq(REGION));
        assertEquals(STORE_ID, request.getValue().path("policyStoreId").asText());
        assertEquals("OFF", request.getValue().path("validationSettings").path("mode").asText());
        assertFalse(request.getValue().has("deletionProtection"));
        assertEquals("authz", request.getValue().path("description").asText());
        assertEquals("{}", schema.getValue().path("definition").path("cedarJson").asText());
        verify(service).untagResource(STORE_ARN, List.of("stale"), REGION);
        verify(service).tagResource(STORE_ARN, Map.of("team", "data"), REGION);
        verify(service, never()).createPolicyStore(any(), any());
        assertEquals(STORE_ID, r.getPhysicalId());
        assertEquals("[\"team\"]", r.getAttributes().get(VerifiedPermissionsCfnProvisioner.TAG_KEYS_ATTR));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    // Catches: a later resource failing the update leaving the store with this update's settings,
    // schema and tags, since an in-place change has no replacement to roll back.
    @Test
    void policyStoreRollbackPutsBackItsSettingsSchemaAndTags() {
        when(service.getPolicyStore(STORE_ID, REGION)).thenReturn(store("STRICT", SCHEMA, null));
        when(service.updatePolicyStore(any(), eq(REGION))).thenReturn(store("OFF", "{}", null));
        when(service.listTags(STORE_ARN, REGION)).thenReturn(Map.of("team", "platform", "stale", "yes"));
        ObjectNode props = storeProps("OFF");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "data");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", STORE_ID,
                Map.of("Arn", STORE_ARN, "PolicyStoreId", STORE_ID));
        provisioner.provision(r, props, ctx(STORE_ID, priorStore()));
        clearInvocations(service);

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<JsonNode> settings = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicyStore(settings.capture(), eq(REGION));
        assertEquals("STRICT", settings.getValue().path("validationSettings").path("mode").asText());
        assertEquals("authz", settings.getValue().path("description").asText());
        ArgumentCaptor<JsonNode> schema = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).putSchema(schema.capture(), eq(REGION));
        assertEquals(SCHEMA, schema.getValue().path("definition").path("cedarJson").asText());
        verify(service).tagResource(STORE_ARN, Map.of("team", "platform", "stale", "yes"), REGION);
        verify(service, never()).untagResource(any(), anyList(), any());
        assertNull(r.getAttributes().get(CfnRollback.VERIFIED_PERMISSIONS_UPDATE_SNAPSHOT_ATTR));
    }

    // Catches: a schema written although the settings update in the same change was refused.
    @Test
    void policyStoreSettingsTheServiceRefusesLeaveItsSchemaAlone() {
        when(service.getPolicyStore(STORE_ID, REGION)).thenReturn(store("STRICT", SCHEMA, null));
        AwsException refused = new AwsException("ValidationException", "validationSettings.mode is invalid", 400);
        when(service.updatePolicyStore(any(), eq(REGION))).thenThrow(refused).thenReturn(store("STRICT", SCHEMA, null));
        when(service.listTags(STORE_ARN, REGION)).thenReturn(Map.of());
        ObjectNode props = storeProps("SOMETIMES");
        props.putObject("Schema").put("CedarJson", "{\"Other\":{\"entityTypes\":{},\"actions\":{}}}");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", STORE_ID,
                Map.of("Arn", STORE_ARN, "PolicyStoreId", STORE_ID));

        assertSame(refused, assertThrows(AwsException.class,
                () -> provisioner.provision(r, props, ctx(STORE_ID, priorStore()))));

        verify(service, never()).putSchema(any(), any());
    }

    // Catches: an encryption change reported as applied while the store keeps its create-time key.
    @Test
    void policyStoreRefusesAChangeOfItsEncryption() {
        ObjectNode props = storeProps("STRICT");
        props.putObject("EncryptionSettings").putObject("KmsEncryptionSettings").put("Key", KEY_ARN);
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", STORE_ID,
                Map.of("Arn", STORE_ARN, "PolicyStoreId", STORE_ID));

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props, ctx(STORE_ID, storeProps("STRICT"))));

        assertEquals("ValidationError", e.getErrorCode());
        verify(service, never()).updatePolicyStore(any(), any());
    }

    // Catches: a Schema declared without CedarJson keeping the store's old schema.
    @Test
    void policyStoreSchemaDeclaredWithoutCedarJsonClearsTheOldOne() {
        when(service.getPolicyStore(STORE_ID, REGION)).thenReturn(store("OFF", SCHEMA, null));
        when(service.updatePolicyStore(any(), eq(REGION))).thenReturn(store("OFF", SCHEMA, null));
        when(service.listTags(STORE_ARN, REGION)).thenReturn(Map.of());
        ObjectNode props = storeProps("OFF");
        props.putObject("Schema");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", STORE_ID,
                Map.of("Arn", STORE_ARN, "PolicyStoreId", STORE_ID));

        provisioner.provision(r, props, ctx(STORE_ID, priorStore()));

        ArgumentCaptor<JsonNode> schema = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).putSchema(schema.capture(), eq(REGION));
        assertEquals("{}", schema.getValue().path("definition").path("cedarJson").asText());
    }

    // Catches: a description or deletion protection the template dropped staying on the store.
    @Test
    void policyStoreUpdateClearsTheDescriptionAndProtectionTheTemplateDropped() {
        when(service.getPolicyStore(STORE_ID, REGION)).thenReturn(store("STRICT", null, null));
        when(service.updatePolicyStore(any(), eq(REGION))).thenReturn(store("OFF", null, null));
        when(service.listTags(STORE_ARN, REGION)).thenReturn(Map.of());
        ObjectNode prior = storeProps("STRICT");
        prior.putObject("DeletionProtection").put("Mode", "ENABLED");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("ValidationSettings").put("Mode", "OFF");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", STORE_ID,
                Map.of("Arn", STORE_ARN, "PolicyStoreId", STORE_ID));

        provisioner.provision(r, props, ctx(STORE_ID, prior));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicyStore(request.capture(), eq(REGION));
        assertEquals("", request.getValue().path("description").asText(null));
        assertEquals("DISABLED", request.getValue().path("deletionProtection").asText());
    }

    // Catches: an update writing a description or deletion protection the stack never declared over
    // what the store has.
    @Test
    void policyStoreUpdateLeavesWhatTheStackNeverDeclared() {
        when(service.getPolicyStore(STORE_ID, REGION)).thenReturn(store("STRICT", SCHEMA, null));
        when(service.updatePolicyStore(any(), eq(REGION))).thenReturn(store("STRICT", SCHEMA, null));
        when(service.listTags(STORE_ARN, REGION)).thenReturn(Map.of());
        ObjectNode props = mapper.createObjectNode();
        props.putObject("ValidationSettings").put("Mode", "STRICT");
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", STORE_ID,
                Map.of("Arn", STORE_ARN, "PolicyStoreId", STORE_ID));

        provisioner.provision(r, props, ctx(STORE_ID, props.deepCopy()));

        verify(service, never()).putSchema(any(), any());
        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicyStore(request.capture(), eq(REGION));
        assertFalse(request.getValue().has("description"));
        assertFalse(request.getValue().has("deletionProtection"));
    }

    // Catches: a stack from before this provisioner, which holds the dispatcher's stub, failing its
    // update because the stub's id names no store.
    @Test
    void policyStoreUpdateOverTheDispatchersStubCreatesTheStore() {
        when(service.createPolicyStore(any(), eq(REGION))).thenReturn(store("STRICT", null, null));
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", "Thing-0a1b2c3d", stubAttributes());

        provisioner.provision(r, storeProps("STRICT"), ctx("Thing-0a1b2c3d", storeProps("STRICT")));

        verify(service).createPolicyStore(any(), eq(REGION));
        verify(service, never()).updatePolicyStore(any(), any());
        assertEquals(STORE_ID, r.getPhysicalId());
    }

    // Catches: an update wiping a schema the stack never managed, set on the store outside the stack.
    @Test
    void policyStoreUpdateLeavesAloneASchemaItsTemplatesNeverDeclared() {
        when(service.getPolicyStore(STORE_ID, REGION)).thenReturn(store("STRICT", SCHEMA, null));
        when(service.updatePolicyStore(any(), eq(REGION))).thenReturn(store("OFF", SCHEMA, null));
        when(service.listTags(STORE_ARN, REGION)).thenReturn(Map.of());
        StackResource r = resource("AWS::VerifiedPermissions::PolicyStore", STORE_ID,
                Map.of("Arn", STORE_ARN, "PolicyStoreId", STORE_ID));

        provisioner.provision(r, storeProps("OFF"), ctx(STORE_ID, storeProps("STRICT")));

        verify(service, never()).putSchema(any(), any());
        verify(service).updatePolicyStore(any(), eq(REGION));
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
        when(service.getPolicyTemplate(STORE_ID, "PT1", REGION)).thenReturn(template(STORE_ID, "PT1"));
        when(service.updatePolicyTemplate(any(), eq(REGION))).thenReturn(template(STORE_ID, "PT1"));
        when(service.createPolicyTemplate(any(), eq(REGION))).thenReturn(template("PS2", "PT2"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", STORE_ID);
        props.put("Statement", TEMPLATE_STATEMENT);
        StackResource same = resource("AWS::VerifiedPermissions::PolicyTemplate", STORE_ID + "|PT1",
                Map.of("PolicyTemplateId", "PT1"));
        ObjectNode prior = props.deepCopy().put("Description", "per-document grants");

        provisioner.provision(same, props, ctx(STORE_ID + "|PT1", prior));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicyTemplate(request.capture(), eq(REGION));
        assertEquals("PT1", request.getValue().path("policyTemplateId").asText());
        assertEquals("", request.getValue().path("description").asText(null));
        assertFalse(request.getValue().has("name"));
        assertEquals(STORE_ID + "|PT1", same.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(same));
        clearInvocations(service);
        assertTrue(provisioner.rollbackUpdate(same));
        ArgumentCaptor<JsonNode> restored = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicyTemplate(restored.capture(), eq(REGION));
        assertEquals("PT1", restored.getValue().path("policyTemplateId").asText());
        assertEquals(TEMPLATE_STATEMENT, restored.getValue().path("statement").asText());

        props.put("PolicyStoreId", "PS2");
        StackResource moved = resource("AWS::VerifiedPermissions::PolicyTemplate", STORE_ID + "|PT1",
                Map.of("PolicyTemplateId", "PT1"));

        provisioner.provision(moved, props, ctx(STORE_ID + "|PT1", prior));

        assertEquals("PS2|PT2", moved.getPhysicalId());
        assertEquals("PT2", moved.getAttributes().get("PolicyTemplateId"));
        assertTrue(provisioner.hasReplacementUpdate(moved));
        assertEquals(STORE_ID + "|PT1", provisioner.updateCleanupPhysicalId(moved));
    }

    // Catches: a template stub left by the dispatcher in an older stack failing its update.
    @Test
    void policyTemplateUpdateOverTheDispatchersStubCreatesTheTemplate() {
        when(service.createPolicyTemplate(any(), eq(REGION))).thenReturn(template(STORE_ID, "PT1"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", STORE_ID);
        props.put("Statement", TEMPLATE_STATEMENT);
        StackResource r = resource("AWS::VerifiedPermissions::PolicyTemplate", "Thing-0a1b2c3d", stubAttributes());

        provisioner.provision(r, props, ctx("Thing-0a1b2c3d", props.deepCopy()));

        verify(service).createPolicyTemplate(any(), eq(REGION));
        verify(service, never()).updatePolicyTemplate(any(), any());
        assertEquals(STORE_ID + "|PT1", r.getPhysicalId());
    }

    @Test
    void staticPolicyAnswersRefWithItsIdAndSendsTheDefinitionInTheApiSpelling() {
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
        assertEquals("SP1", r.getPhysicalId());
        assertEquals("SP1", r.getAttributes().get("PolicyId"));
        assertEquals("STATIC", r.getAttributes().get("PolicyType"));
    }

    @Test
    void staticPolicyUpdatesInPlaceInItsStoreAndClearsADroppedDescription() {
        when(service.getPolicy(STORE_ID, "SP1", REGION)).thenReturn(staticPolicy(STORE_ID, "SP1", "everyone"));
        when(service.updatePolicy(any(), eq(REGION))).thenReturn(staticPolicy(STORE_ID, "SP1"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", STORE_ID);
        props.putObject("Definition").putObject("Static").put("Statement", "permit(principal, action, resource);");
        StackResource r = resource("AWS::VerifiedPermissions::Policy", "SP1",
                Map.of("PolicyId", "SP1", "PolicyType", "STATIC"));
        ObjectNode prior = props.deepCopy();
        ((ObjectNode) prior.path("Definition").path("Static")).put("Description", "everyone");

        provisioner.provision(r, props, ctx("SP1", prior));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicy(request.capture(), eq(REGION));
        assertEquals("SP1", request.getValue().path("policyId").asText());
        assertEquals(STORE_ID, request.getValue().path("policyStoreId").asText());
        assertEquals("", request.getValue().path("definition").path("static").path("description").asText(null));
        verify(service, never()).createPolicy(any(), any());
        assertEquals("SP1", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));

        clearInvocations(service);
        assertTrue(provisioner.rollbackUpdate(r));
        ArgumentCaptor<JsonNode> restored = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updatePolicy(restored.capture(), eq(REGION));
        assertEquals("SP1", restored.getValue().path("policyId").asText());
        assertEquals("permit(principal, action, resource);",
                restored.getValue().path("definition").path("static").path("statement").asText());
        assertNull(r.getAttributes().get(CfnRollback.VERIFIED_PERMISSIONS_UPDATE_SNAPSHOT_ATTR));
    }

    // Catches: a policy moved to another store keeping the old one, or the displaced policy left behind
    // because its bare id no longer names the store it has to be deleted from.
    @Test
    void staticPolicyMovedToAnotherStoreIsReplacedAndTheOldOneDeletedFromItsStore() {
        when(service.policyStoreIdOf("SP1", REGION)).thenReturn(STORE_ID);
        when(service.createPolicy(any(), eq(REGION))).thenReturn(staticPolicy("PS2", "SP2"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", "PS2");
        props.putObject("Definition").putObject("Static").put("Statement", "permit(principal, action, resource);");
        StackResource r = resource("AWS::VerifiedPermissions::Policy", "SP1",
                Map.of("PolicyId", "SP1", "PolicyType", "STATIC"));
        ObjectNode prior = props.deepCopy().put("PolicyStoreId", STORE_ID);

        provisioner.provision(r, props, ctx("SP1", prior));

        verify(service, never()).updatePolicy(any(), any());
        assertEquals("SP2", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("SP1", provisioner.updateCleanupPhysicalId(r));

        provisioner.completeUpdate(r);

        verify(service).deletePolicy(STORE_ID, "SP1", REGION);
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
        assertEquals("LP1", r.getPhysicalId());
        assertEquals("TEMPLATE_LINKED", r.getAttributes().get("PolicyType"));

        when(service.getPolicy(STORE_ID, "LP1", REGION)).thenReturn(linkedPolicy(STORE_ID, "LP1", "alice"));
        StackResource again = resource("AWS::VerifiedPermissions::Policy", "LP1",
                Map.of("PolicyId", "LP1", "PolicyType", "TEMPLATE_LINKED"));

        provisioner.provision(again, props, ctx("LP1", props.deepCopy()));

        verify(service, never()).updatePolicy(any(), any());
        assertEquals("LP1", again.getPhysicalId());
        assertEquals("LP1", again.getAttributes().get("PolicyId"));
        assertTrue(provisioner.rollbackUpdate(again));
        verify(service, never()).updatePolicy(any(), any());
    }

    @Test
    void templateLinkedPolicyRefusesAnUpdateOfItsDefinition() {
        when(service.getPolicy(STORE_ID, "LP1", REGION)).thenReturn(linkedPolicy(STORE_ID, "LP1", "alice"));
        ObjectNode props = mapper.createObjectNode();
        props.put("PolicyStoreId", STORE_ID);
        props.set("Definition", linkedDefinition("bob"));
        StackResource r = resource("AWS::VerifiedPermissions::Policy", "LP1",
                Map.of("PolicyId", "LP1", "PolicyType", "TEMPLATE_LINKED"));
        ObjectNode prior = props.deepCopy();
        prior.set("Definition", linkedDefinition("alice"));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx("LP1", prior)));

        assertEquals("ValidationError", e.getErrorCode());
        verify(service, never()).updatePolicy(any(), any());
        verify(service, never()).createPolicy(any(), any());
    }

    @Test
    void deleteNamesTheStoreAndEntityEachIdCarriesAndToleratesOneAlreadyGone() {
        when(service.policyStoreIdOf("SP1", REGION)).thenReturn(STORE_ID);
        provisioner.delete("AWS::VerifiedPermissions::PolicyStore", STORE_ID, REGION);
        provisioner.delete("AWS::VerifiedPermissions::PolicyTemplate", STORE_ID + "|PT1", REGION);
        provisioner.delete("AWS::VerifiedPermissions::Policy", "SP1", REGION);
        // Cloud Control's identifier for a policy names its store.
        provisioner.delete("AWS::VerifiedPermissions::Policy", "SP2|PS2", REGION);
        // A policy no store holds any more went with its store.
        provisioner.delete("AWS::VerifiedPermissions::Policy", "SP3", REGION);

        verify(service).deletePolicyStore(STORE_ID, REGION);
        verify(service).deletePolicyTemplate(STORE_ID, "PT1", REGION);
        verify(service).deletePolicy(STORE_ID, "SP1", REGION);
        verify(service).deletePolicy("PS2", "SP2", REGION);
        verify(service, never()).deletePolicy(any(), eq("SP3"), any());

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
