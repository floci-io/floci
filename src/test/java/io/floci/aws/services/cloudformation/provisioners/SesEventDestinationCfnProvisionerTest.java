package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.ses.SesConfigurationSetService;
import io.floci.aws.services.ses.model.EventDestination;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SesEventDestinationCfnProvisionerTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SesConfigurationSetService configurationSets = mock(SesConfigurationSetService.class);
    private final SesEventDestinationCfnProvisioner provisioner =
            new SesEventDestinationCfnProvisioner(configurationSets);

    @Test
    void failedReplacementRollbackRetriesBeforeTheNextUpdateUsesTheRestoredPhysicalId() {
        StackResource resource = resource();
        provisioner.provision(resource, properties("first", "old", false), context(null));
        provisioner.provision(resource, properties("second", "new", true), context("old"));
        resource.setStatus("UPDATE_FAILED");

        doThrow(new AwsException("InternalFailure", "retry deletion", 500)).doNothing()
                .when(configurationSets).deleteEventDestination("second", "new", REGION);
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        assertTrue(provisioner.requiresUpdateRecovery(resource));
        assertEquals("new", resource.getPhysicalId());

        EventDestination previous = new EventDestination();
        previous.setName("old");
        previous.setMatchingEventTypes(List.of("SEND"));
        when(configurationSets.getEventDestinations("first", REGION)).thenReturn(List.of(previous));

        // The stack can still pass the failed replacement's id as the prior id on this call.
        provisioner.provision(resource, properties("first", "old", true), context("new"));

        assertEquals("old", resource.getPhysicalId());
        assertEquals("old", resource.getAttributes().get("Id"));
        verify(configurationSets, times(2)).deleteEventDestination("second", "new", REGION);
        verify(configurationSets).updateEventDestination(eq("first"), eq("old"), any(EventDestination.class),
                eq(ACCOUNT), eq(REGION), eq(true));
        verify(configurationSets, times(1)).createEventDestination(eq("first"), eq("old"),
                any(EventDestination.class), eq(ACCOUNT), eq(REGION), eq(true));
    }

    @Test
    void retainedReplacementWithTheSameNameKeepsTheOldConfigurationSetDestination() {
        StackResource resource = resource();
        provisioner.provision(resource, properties("first", "shared", false), context(null));
        resource.setUpdateReplacePolicy("Retain");

        provisioner.provision(resource, properties("second", "shared", true), context("shared"));

        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertNull(provisioner.updateCleanupPhysicalId(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        provisioner.clearUpdate(resource);
        provisioner.delete(resource, REGION);

        verify(configurationSets).deleteEventDestination("second", "shared", REGION);
        verify(configurationSets, never()).deleteEventDestination("first", "shared", REGION);
    }

    @Test
    void rejectsUnsupportedNestedDestinationProperties() {
        ObjectNode props = properties("first", "events", true);
        props.withObject("EventDestination").putObject("SnsDestination")
                .put("TopicARN", "arn:aws:sns:us-east-1:000000000000:events")
                .put("Unexpected", "ignored");

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, context(null)));
        verify(configurationSets, never()).createEventDestination(eq("first"), eq("events"),
                any(EventDestination.class), eq(ACCOUNT), eq(REGION), eq(true));
    }

    @Test
    void rejectsMissingAndBlankSnsTopicArnBeforeCallingSes() {
        ObjectNode missing = properties("first", "events", true);
        missing.withObject("EventDestination").putObject("SnsDestination");
        assertInvalidWithoutSesCall(missing);

        ObjectNode blank = properties("first", "events", true);
        blank.withObject("EventDestination").putObject("SnsDestination").put("TopicARN", " ");
        assertInvalidWithoutSesCall(blank);
    }

    @Test
    void rejectsMissingAndBlankFirehoseArnsBeforeCallingSes() {
        ObjectNode missing = properties("first", "events", true);
        missing.withObject("EventDestination").putObject("KinesisFirehoseDestination")
                .put("IAMRoleARN", "arn:aws:iam::000000000000:role/ses");
        assertInvalidWithoutSesCall(missing);

        ObjectNode blank = properties("first", "events", true);
        blank.withObject("EventDestination").putObject("KinesisFirehoseDestination")
                .put("IAMRoleARN", " ")
                .put("DeliveryStreamARN", "arn:aws:firehose:us-east-1:000000000000:deliverystream/events");
        assertInvalidWithoutSesCall(blank);
    }

    @Test
    void rejectsMissingAndBlankEventBusArnBeforeCallingSes() {
        ObjectNode missing = properties("first", "events", true);
        missing.withObject("EventDestination").putObject("EventBridgeDestination");
        assertInvalidWithoutSesCall(missing);

        ObjectNode blank = properties("first", "events", true);
        blank.withObject("EventDestination").putObject("EventBridgeDestination").put("EventBusArn", " ");
        assertInvalidWithoutSesCall(blank);
    }

    @Test
    void rejectsMissingConfigurationSetNameAndNonObjectDestinationsBeforeCallingSes() {
        ObjectNode missingSet = properties("first", "events", true);
        missingSet.remove("ConfigurationSetName");
        assertInvalidWithoutSesCall(missingSet);

        ObjectNode blankSet = properties("first", "events", true).put("ConfigurationSetName", " ");
        assertInvalidWithoutSesCall(blankSet);

        ObjectNode nonObjectDestination = properties("first", "events", true)
                .put("EventDestination", "events");
        assertInvalidWithoutSesCall(nonObjectDestination);

        ObjectNode nonObjectBlock = properties("first", "events", true);
        nonObjectBlock.withObject("EventDestination").put("SnsDestination", "topic");
        assertInvalidWithoutSesCall(nonObjectBlock);
    }

    @Test
    void rejectsMalformedCloudWatchDimensionsBeforeCallingSes() {
        ObjectNode nonArray = properties("first", "events", true);
        nonArray.withObject("EventDestination").putObject("CloudWatchDestination")
                .put("DimensionConfigurations", "dimension");
        assertInvalidWithoutSesCall(nonArray);

        ObjectNode nonObjectEntry = properties("first", "events", true);
        nonObjectEntry.withObject("EventDestination").putObject("CloudWatchDestination")
                .putArray("DimensionConfigurations").add("dimension");
        assertInvalidWithoutSesCall(nonObjectEntry);
    }

    private void assertInvalidWithoutSesCall(ObjectNode props) {
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, context(null)));
        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        verifyNoInteractions(configurationSets);
    }

    private ObjectNode properties(String set, String name, boolean enabled) {
        ObjectNode props = mapper.createObjectNode().put("ConfigurationSetName", set);
        ObjectNode destination = props.putObject("EventDestination");
        destination.put("Name", name);
        destination.put("Enabled", enabled);
        destination.putArray("MatchingEventTypes").add("SEND");
        return props;
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::SES::ConfigurationSetEventDestination");
        resource.setLogicalId("EventDestination");
        return resource;
    }

    private ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = CloudFormationTemplateEngine.standalone(ACCOUNT, REGION,
                "stack", mapper, UnaryOperator.identity());
        return new ProvisionContext(engine, REGION, ACCOUNT, "stack", prior);
    }
}
