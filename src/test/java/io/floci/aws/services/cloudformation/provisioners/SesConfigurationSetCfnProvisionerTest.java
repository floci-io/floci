package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.ses.SesIdentityService;
import io.floci.aws.services.ses.SesService;
import io.floci.aws.services.ses.model.ConfigurationSet;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesConfigurationSetCfnProvisionerTest {
    private static final String TYPE = "AWS::SES::ConfigurationSet";
    private final ObjectMapper mapper = new ObjectMapper();
    private final SesService ses = mock(SesService.class);
    private final SesCfnProvisioner provisioner = new SesCfnProvisioner(ses, mock(SesIdentityService.class));

    @Test
    void createUsesNameAsRefAndDeletesTheBackingSet() throws Exception {
        StackResource resource = resource(null);

        provisioner.provision(resource, props("{\"Name\":\"delivery\"}"), context(null, null));

        assertEquals("delivery", resource.getPhysicalId());
        verify(ses).createConfigurationSet(argThat(set -> "delivery".equals(set.getName())), eq("us-east-1"));
        provisioner.delete(resource, "us-east-1");
        verify(ses).deleteConfigurationSet("delivery", "us-east-1");
    }

    @Test
    void generatedNameRemainsStableOnAnInPlaceUpdate() throws Exception {
        StackResource resource = resource(null);
        JsonNode properties = props("{}");
        provisioner.provision(resource, properties, context(null, null));
        String generated = resource.getPhysicalId();

        provisioner.provision(resource, properties, context(generated, properties));

        assertTrue(generated.startsWith("ses-stack-ConfigurationSet-"));
        assertTrue(generated.length() <= 64);
        assertEquals(generated, resource.getPhysicalId());
        verify(ses, times(1)).createConfigurationSet(any(ConfigurationSet.class), eq("us-east-1"));
    }

    @Test
    void declaringTheExistingGeneratedNameNeedsNoReplacementOrRollbackWork() throws Exception {
        StackResource resource = resource(null);
        JsonNode previous = props("{}");
        provisioner.provision(resource, previous, context(null, null));
        String generated = resource.getPhysicalId();

        provisioner.provision(resource, props("{\"Name\":\"" + generated + "\"}"),
                context(generated, previous));

        assertFalse(provisioner.hasReplacementUpdate(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(generated, resource.getPhysicalId());
        verify(ses, never()).deleteConfigurationSet(any(), any());
    }

    @Test
    void renameCreatesReplacementAndDeletesOldSetAfterCommit() throws Exception {
        StackResource resource = resource("old");
        JsonNode previous = props("{\"Name\":\"old\"}");

        provisioner.provision(resource, props("{\"Name\":\"new\"}"), context("old", previous));

        assertEquals("new", resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        verify(ses).createConfigurationSet(argThat(set -> "new".equals(set.getName())), eq("us-east-1"));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(ses).deleteConfigurationSet("old", "us-east-1");
    }

    @Test
    void removingAnExplicitNameGeneratesAReplacement() throws Exception {
        StackResource resource = resource("old");

        provisioner.provision(resource, props("{}"),
                context("old", props("{\"Name\":\"old\"}")));

        assertNotEquals("old", resource.getPhysicalId());
        assertTrue(resource.getPhysicalId().startsWith("ses-stack-ConfigurationSet-"));
        assertTrue(provisioner.hasReplacementUpdate(resource));
        provisioner.completeUpdate(resource);
        verify(ses).deleteConfigurationSet("old", "us-east-1");
    }

    @Test
    void assigningAnExplicitNameReplacesAndDeletesTheGeneratedSet() throws Exception {
        StackResource resource = resource(null);
        JsonNode previous = props("{}");
        provisioner.provision(resource, previous, context(null, null));
        String generated = resource.getPhysicalId();

        provisioner.provision(resource, props("{\"Name\":\"named\"}"), context(generated, previous));

        assertEquals("named", resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(ses).deleteConfigurationSet(generated, "us-east-1");
    }

    @Test
    void assigningAnExplicitNameRollsBackToTheGeneratedSet() throws Exception {
        StackResource resource = resource(null);
        JsonNode previous = props("{}");
        provisioner.provision(resource, previous, context(null, null));
        String generated = resource.getPhysicalId();

        provisioner.provision(resource, props("{\"Name\":\"named\"}"), context(generated, previous));

        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(generated, resource.getPhysicalId());
        verify(ses).deleteConfigurationSet("named", "us-east-1");
        verify(ses, never()).deleteConfigurationSet(generated, "us-east-1");
    }

    @Test
    void failedStackUpdateRestoresOldRefAndDeletesOnlyTheNewSet() throws Exception {
        StackResource resource = resource("old");

        provisioner.provision(resource, props("{\"Name\":\"new\"}"),
                context("old", props("{\"Name\":\"old\"}")));

        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals("old", resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        verify(ses).deleteConfigurationSet("new", "us-east-1");
        verify(ses, never()).deleteConfigurationSet("old", "us-east-1");
    }

    @Test
    void retainPolicyLeavesDisplacedSetAndDeletesCurrentSet() throws Exception {
        StackResource resource = resource("old");
        resource.setUpdateReplacePolicy("Retain");
        provisioner.provision(resource, props("{\"Name\":\"new\"}"),
                context("old", props("{\"Name\":\"old\"}")));

        provisioner.completeUpdate(resource);
        provisioner.delete(resource, "us-east-1");

        verify(ses, never()).deleteConfigurationSet("old", "us-east-1");
        verify(ses).deleteConfigurationSet("new", "us-east-1");
    }

    @Test
    void unsupportedOptionsFailBeforeCreation() throws Exception {
        StackResource resource = resource(null);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource,
                props("{\"Name\":\"delivery\",\"SendingOptions\":{\"SendingEnabled\":false}}"),
                context(null, null)));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(ses, never()).createConfigurationSet(any(ConfigurationSet.class), any());
    }

    @Test
    void blankExplicitNameFailsBeforeCreation() throws Exception {
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource(null),
                props("{\"Name\":\"\"}"), context(null, null)));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(ses, never()).createConfigurationSet(any(ConfigurationSet.class), any());
    }

    @Test
    void deletionToleratesOnlyMissingConfigurationSet() {
        doThrow(new AwsException("ConfigurationSetDoesNotExist", "gone", 400))
                .when(ses).deleteConfigurationSet("gone", "us-east-1");
        doThrow(new AwsException("InternalFailure", "failed", 500))
                .when(ses).deleteConfigurationSet("failed", "us-east-1");

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "gone", "us-east-1"));
        assertThrows(AwsException.class, () -> provisioner.delete(TYPE, "failed", "us-east-1"));
    }

    private StackResource resource(String physicalId) {
        StackResource resource = new StackResource();
        resource.setLogicalId("ConfigurationSet");
        resource.setResourceType(TYPE);
        resource.setPhysicalId(physicalId);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private ProvisionContext context(String priorPhysicalId, JsonNode priorProperties) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(call -> call.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "ses-stack",
                priorPhysicalId, event -> {}, priorProperties);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }
}
