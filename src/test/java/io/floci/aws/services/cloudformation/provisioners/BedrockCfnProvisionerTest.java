package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrock.BedrockService;
import io.floci.aws.services.bedrock.model.ApplicationInferenceProfile;
import io.floci.aws.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BedrockCfnProvisionerTest {

    private static final String TYPE = "AWS::Bedrock::ApplicationInferenceProfile";
    private static final String REGION = "us-east-1";
    private static final String ID = "a1b2c3d4e5f6";
    private static final String ARN =
            "arn:aws:bedrock:us-east-1:000000000000:application-inference-profile/" + ID;
    private static final String SOURCE =
            "arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-micro-v1:0";

    private final BedrockService bedrock = mock(BedrockService.class);
    private final BedrockCfnProvisioner provisioner = new BedrockCfnProvisioner(bedrock);
    private final ObjectMapper mapper = new ObjectMapper();

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Profile");
        resource.setResourceType(TYPE);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private ProvisionContext context() {
        return new ProvisionContext(ProvisionContexts.passthroughEngine(), REGION, "000000000000", "stack");
    }

    private static ApplicationInferenceProfile profile() {
        ApplicationInferenceProfile profile = new ApplicationInferenceProfile();
        profile.setInferenceProfileId(ID);
        profile.setInferenceProfileArn(ARN);
        profile.setModels(List.of(SOURCE));
        profile.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        profile.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return profile;
    }

    @Test
    void createUsesTheBedrockServiceAndPublishesExactCloudFormationAttributes() throws Exception {
        ObjectNode properties = mapper.createObjectNode()
                .put("InferenceProfileName", "example")
                .put("Description", "example profile");
        properties.putObject("ModelSource").put("CopyFrom", SOURCE);
        properties.putArray("Tags").addObject().put("Key", "team").put("Value", "ml");
        when(bedrock.createApplicationInferenceProfile("example", "example profile", SOURCE,
                Map.of("team", "ml"), REGION)).thenReturn(profile());
        StackResource resource = resource();

        provisioner.provision(resource, properties, context());

        verify(bedrock).createApplicationInferenceProfile("example", "example profile", SOURCE,
                Map.of("team", "ml"), REGION);
        assertEquals(Set.of(TYPE), provisioner.resourceTypes());
        assertEquals(Map.of(TYPE, Set.of("Models")), provisioner.jsonAttributes());
        assertEquals(ID, resource.getPhysicalId());
        assertEquals(Set.of("InferenceProfileId", "InferenceProfileArn", "InferenceProfileIdentifier",
                "Status", "Type", "CreatedAt", "UpdatedAt", "Models",
                "__FlociBedrockProfileManagedTagKeys"), resource.getAttributes().keySet());
        assertEquals(ID, resource.getAttributes().get("InferenceProfileId"));
        assertEquals(ARN, resource.getAttributes().get("InferenceProfileArn"));
        assertEquals("[\"team\"]", resource.getAttributes().get("__FlociBedrockProfileManagedTagKeys"));
        assertEquals(ID, resource.getAttributes().get("InferenceProfileIdentifier"));
        assertEquals("ACTIVE", resource.getAttributes().get("Status"));
        assertEquals("APPLICATION", resource.getAttributes().get("Type"));
        assertEquals(SOURCE, mapper.readTree(resource.getAttributes().get("Models"))
                .get(0).path("ModelArn").asText());
    }

    @Test
    void missingSourceDoesNotCallBedrock() {
        ObjectNode properties = mapper.createObjectNode().put("InferenceProfileName", "example");

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), properties, context()));

        assertEquals("ValidationError", error.getErrorCode());
        verifyNoInteractions(bedrock);
    }

    @Test
    void replacementRejectsAnExternallyDeletedPriorProfileBeforeCreatingAnother() throws Exception {
        ObjectNode properties = mapper.createObjectNode().put("InferenceProfileName", "replacement");
        properties.putObject("ModelSource").put("CopyFrom", SOURCE);
        StackResource resource = resource();
        resource.setPhysicalId(ID);
        resource.getAttributes().put("InferenceProfileId", ID);
        resource.getAttributes().put("InferenceProfileArn", ARN);
        when(bedrock.getApplicationInferenceProfile(ID, REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "missing", 404));

        AwsException error = assertThrows(AwsException.class, () -> provisioner.provision(resource, properties,
                ProvisionContexts.update(REGION, "000000000000", "stack", ID,
                        "{\"InferenceProfileName\":\"example\"}")));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(ID, resource.getPhysicalId());
        verify(bedrock).getApplicationInferenceProfile(ID, REGION);
        verifyNoMoreInteractions(bedrock);
    }

    @Test
    void updateOfARealDispatcherStubCreatesTheFirstProfile() throws Exception {
        ObjectNode properties = mapper.createObjectNode().put("InferenceProfileName", "example");
        properties.putObject("ModelSource").put("CopyFrom", SOURCE);
        StackResource resource = resource();
        String stubId = "Profile-01234567";
        resource.setPhysicalId(stubId);
        resource.getAttributes().put("Arn", "arn:aws:stub:::Profile");
        when(bedrock.createApplicationInferenceProfile("example", null, SOURCE, Map.of(), REGION))
                .thenReturn(profile());

        provisioner.provision(resource, properties, ProvisionContexts.update(REGION, "000000000000",
                "stack", stubId, "{\"InferenceProfileName\":\"example\"}"));

        assertEquals(ID, resource.getPhysicalId());
        verify(bedrock).createApplicationInferenceProfile("example", null, SOURCE, Map.of(), REGION);
        verifyNoMoreInteractions(bedrock);
    }

    @Test
    void malformedSuppliedTagsFailBeforeProfileLookupOrTagReconciliation() throws Exception {
        ObjectNode properties = mapper.createObjectNode().put("InferenceProfileName", "example");
        properties.putObject("ModelSource").put("CopyFrom", SOURCE);
        String tooLongKey = "k".repeat(129);
        String tooLongValue = "v".repeat(257);
        String tooManyTags = "[" + String.join(",", Collections.nCopies(201,
                "{\"Key\":\"team\",\"Value\":\"ml\"}")) + "]";
        for (String tagsJson : List.of("{}", "[{\"Value\":\"ml\"}]", "[{\"Key\":\"team\"}]",
                "[{\"Key\":42,\"Value\":\"ml\"}]", "[{\"Key\":\"team\",\"Value\":null}]",
                "[{\"Key\":\"\",\"Value\":\"ml\"}]", "[{\"Key\":\"bad?key\",\"Value\":\"ml\"}]",
                "[{\"Key\":\"team\",\"Value\":\"bad?value\"}]",
                "[{\"Key\":\"" + tooLongKey + "\",\"Value\":\"ml\"}]",
                "[{\"Key\":\"team\",\"Value\":\"" + tooLongValue + "\"}]", tooManyTags)) {
            properties.set("Tags", mapper.readTree(tagsJson));
            StackResource resource = resource();
            resource.setPhysicalId(ID);

            AwsException error = assertThrows(AwsException.class, () -> provisioner.provision(resource,
                    properties, ProvisionContexts.update(REGION, "000000000000", "stack", ID,
                            "{\"InferenceProfileName\":\"example\"}")), tagsJson);

            assertEquals("ValidationError", error.getErrorCode(), tagsJson);
        }
        verifyNoInteractions(bedrock);
    }

    @Test
    void oversizedTagsReportTheItemCapBeforeMalformedItems() {
        ObjectNode properties = mapper.createObjectNode().put("InferenceProfileName", "example");
        properties.putObject("ModelSource").put("CopyFrom", SOURCE);
        ArrayNode tags = properties.putArray("Tags");
        tags.addObject().put("Key", "bad?key").put("Value", "ml");
        for (int i = 0; i < 200; i++) {
            tags.addObject().put("Key", "team").put("Value", "ml");
        }

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), properties, context()));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(TYPE + " Tags must have at most 200 items.", error.getMessage());
        verifyNoInteractions(bedrock);
    }

    @Test
    void validBoundaryTagsAreAllowedByTheBedrockTagPattern() {
        ObjectNode properties = mapper.createObjectNode().put("InferenceProfileName", "example");
        properties.putObject("ModelSource").put("CopyFrom", SOURCE);
        String maxKey = "k".repeat(128);
        String maxValue = "v".repeat(256);
        properties.putArray("Tags").addObject().put("Key", " ").put("Value", "");
        properties.withArray("Tags").addObject().put("Key", maxKey).put("Value", maxValue);
        Map<String, String> expectedTags = Map.of(" ", "", maxKey, maxValue);
        when(bedrock.createApplicationInferenceProfile("example", null, SOURCE, expectedTags, REGION))
                .thenReturn(profile());

        provisioner.provision(resource(), properties, context());

        verify(bedrock).createApplicationInferenceProfile("example", null, SOURCE, expectedTags, REGION);
    }

    @Test
    void omittedTagsRemainValid() {
        ObjectNode properties = mapper.createObjectNode().put("InferenceProfileName", "example");
        properties.putObject("ModelSource").put("CopyFrom", SOURCE);
        when(bedrock.createApplicationInferenceProfile("example", null, SOURCE, Map.of(), REGION))
                .thenReturn(profile());

        provisioner.provision(resource(), properties, context());

        verify(bedrock).createApplicationInferenceProfile("example", null, SOURCE, Map.of(), REGION);
    }

    @Test
    void updateRemovesOnlyPreviouslyTemplateManagedTags() throws Exception {
        ObjectNode properties = mapper.createObjectNode().put("InferenceProfileName", "example");
        properties.putObject("ModelSource").put("CopyFrom", SOURCE);
        StackResource resource = resource();
        resource.setPhysicalId(ID);
        resource.getAttributes().put("InferenceProfileId", ID);
        resource.getAttributes().put("InferenceProfileArn", ARN);
        resource.getAttributes().put("__FlociBedrockProfileManagedTagKeys", "[\"team\"]");
        ApplicationInferenceProfile existing = profile();
        existing.setTags(Map.of("team", "stack", "external", "keep"));
        when(bedrock.getApplicationInferenceProfile(ID, REGION)).thenReturn(existing);

        provisioner.provision(resource, properties, ProvisionContexts.update(REGION, "000000000000", "stack", ID,
                "{\"InferenceProfileName\":\"example\",\"ModelSource\":{\"CopyFrom\":\"" + SOURCE
                        + "\"},\"Tags\":[{\"Key\":\"team\",\"Value\":\"stack\"}]}"));

        verify(bedrock).untagResource(ARN, List.of("team"), REGION);
        assertFalse(resource.getAttributes().containsKey("__FlociBedrockProfileManagedTagKeys"));
    }

    @Test
    void failedProfileLookupAfterTagMutationRestoresPriorTags() throws Exception {
        ObjectNode properties = mapper.createObjectNode().put("InferenceProfileName", "example");
        properties.putObject("ModelSource").put("CopyFrom", SOURCE);
        properties.putArray("Tags").addObject().put("Key", "team").put("Value", "new");
        StackResource resource = resource();
        resource.setPhysicalId(ID);
        resource.getAttributes().put("InferenceProfileId", ID);
        resource.getAttributes().put("InferenceProfileArn", ARN);
        resource.getAttributes().put("__FlociBedrockProfileManagedTagKeys", "[\"team\"]");

        ApplicationInferenceProfile existing = profile();
        existing.setTags(Map.of("team", "old"));
        when(bedrock.getApplicationInferenceProfile(ID, REGION)).thenReturn(existing)
                .thenThrow(new AwsException("InternalFailure", "publish lookup failed", 500))
                .thenReturn(existing);

        AwsException updateFailure = assertThrows(AwsException.class, () -> provisioner.provision(resource,
                properties, ProvisionContexts.update(REGION, "000000000000", "stack", ID,
                        "{\"InferenceProfileName\":\"example\",\"ModelSource\":{\"CopyFrom\":\"" + SOURCE
                                + "\"},\"Tags\":[{\"Key\":\"team\",\"Value\":\"old\"}]}")));

        assertEquals("InternalFailure", updateFailure.getErrorCode());
        assertFalse(resource.getAttributes().containsKey("__FlociBedrockProfilePriorTags"));
        assertFalse(resource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        verify(bedrock).tagResource(ARN, Map.of("team", "new"), REGION);
        verify(bedrock).tagResource(ARN, Map.of("team", "old"), REGION);
    }

    @Test
    void failedTagUpdateCarriesSnapshotIntoContinueUpdateRollback() throws Exception {
        ObjectNode properties = mapper.createObjectNode().put("InferenceProfileName", "example");
        properties.putObject("ModelSource").put("CopyFrom", SOURCE);
        properties.putArray("Tags").addObject().put("Key", "new").put("Value", "value");

        StackResource previous = resource();
        previous.setPhysicalId(ID);
        previous.getAttributes().put("InferenceProfileId", ID);
        previous.getAttributes().put("InferenceProfileArn", ARN);
        previous.getAttributes().put("__FlociBedrockProfileManagedTagKeys", "[\"team\"]");
        StackResource attempted = resource();
        attempted.setPhysicalId(ID);
        attempted.getAttributes().putAll(previous.getAttributes());

        ApplicationInferenceProfile existing = profile();
        existing.setTags(Map.of("team", "stack", "external", "keep"));
        when(bedrock.getApplicationInferenceProfile(ID, REGION)).thenReturn(existing);
        doThrow(new AwsException("InternalFailure", "update untag failed", 500))
                .when(bedrock).untagResource(ARN, List.of("team"), REGION);
        doThrow(new AwsException("InternalFailure", "compensation tag failed", 500))
                .doNothing().when(bedrock).tagResource(ARN, Map.of("team", "stack"), REGION);

        AwsException updateFailure = assertThrows(AwsException.class, () -> provisioner.provision(attempted,
                properties, ProvisionContexts.update(REGION, "000000000000", "stack", ID,
                        "{\"InferenceProfileName\":\"example\",\"ModelSource\":{\"CopyFrom\":\"" + SOURCE
                                + "\"},\"Tags\":[{\"Key\":\"team\",\"Value\":\"stack\"}]}")));

        assertEquals("InternalFailure", updateFailure.getErrorCode());
        assertTrue(attempted.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        assertTrue(attempted.getAttributes().containsKey("__FlociBedrockProfilePriorTags"));

        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);
        previous.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                attempted.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        assertTrue(previous.getAttributes().containsKey("__FlociBedrockProfilePriorTags"));

        assertTrue(provisioner.rollbackUpdate(previous));

        assertFalse(previous.getAttributes().containsKey("__FlociBedrockProfilePriorTags"));
        verify(bedrock, times(2)).tagResource(ARN, Map.of("team", "stack"), REGION);
    }

    @Test
    void deleteIgnoresOnlyAnAlreadyDeletedProfile() {
        doThrow(new AwsException("ResourceNotFoundException", "missing", 404))
                .when(bedrock).deleteApplicationInferenceProfile(ID, REGION);
        assertDoesNotThrow(() -> provisioner.delete(TYPE, ID, REGION));

        doThrow(new AwsException("AccessDeniedException", "denied", 403))
                .when(bedrock).deleteApplicationInferenceProfile(ID, REGION);
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> provisioner.delete(TYPE, ID, REGION)).getErrorCode());
    }
}
