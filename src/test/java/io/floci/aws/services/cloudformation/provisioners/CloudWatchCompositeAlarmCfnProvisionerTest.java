package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.CompositeAlarm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.REGION;
import static io.floci.aws.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.ctx;
import static io.floci.aws.services.cloudformation.provisioners.IotCfnProvisionerTestSupport.resource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudWatchCompositeAlarmCfnProvisionerTest {

    private static final String TYPE = "AWS::CloudWatch::CompositeAlarm";

    @ParameterizedTest
    @CsvSource({
        "kept, A metric alarm now has this name, 1",
        "previous, CloudFormation cannot update a stack when a custom-named resource requires replacing. "
                + "Rename kept and update the stack again., 0"
    })
    void rejectedUpdateDoesNotArmRollback(String previousName, String expectedMessage, int writes) {
        CloudWatchMetricsService service = mock(CloudWatchMetricsService.class);
        CloudWatchCompositeAlarmCfnProvisioner provisioner = new CloudWatchCompositeAlarmCfnProvisioner(service);
        StackResource resource = resource(TYPE, "Alarm");
        resource.setPhysicalId("kept");
        resource.getAttributes().put("Arn", "prior-arn");
        AwsException collision = new AwsException("ValidationError", "A metric alarm now has this name", 400);
        doThrow(collision).when(service).putCompositeAlarm(any(), eq(REGION), eq(true));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode previous = mapper.createObjectNode().put("AlarmName", previousName).put("AlarmRule", "FALSE");
        JsonNode props = mapper.createObjectNode().put("AlarmName", "kept").put("AlarmRule", "TRUE");
        ProvisionContext context = ctx("kept", previous);

        AwsException error = assertThrows(AwsException.class, () -> provisioner.provision(resource, props, context));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(expectedMessage, error.getMessage());
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        assertFalse(provisioner.rollbackUpdate(resource));
        assertEquals("kept", resource.getPhysicalId());
        assertEquals(Map.of("Arn", "prior-arn"), resource.getAttributes());
        verify(service, times(writes)).putCompositeAlarm(any(), anyString(), anyBoolean());
        verify(service, never()).deleteAlarms(any(), anyString());
    }

    @Test
    void inPlaceUpdateRetriesPreviouslyDisplacedAlarmCleanup() {
        CloudWatchMetricsService service = mock(CloudWatchMetricsService.class);
        doAnswer(call -> {
            CompositeAlarm alarm = call.getArgument(0);
            alarm.setAlarmArn("arn:aws:cloudwatch:us-east-1:000000000000:alarm:" + alarm.getAlarmName());
            return null;
        }).when(service).putCompositeAlarm(any(), eq(REGION), anyBoolean());
        CloudWatchCompositeAlarmCfnProvisioner provisioner = new CloudWatchCompositeAlarmCfnProvisioner(service);
        StackResource resource = resource(TYPE, "Alarm");
        resource.setPhysicalId("displaced");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode previous = mapper.createObjectNode().put("AlarmName", "displaced").put("AlarmRule", "TRUE");
        JsonNode renamed = mapper.createObjectNode().put("AlarmName", "current").put("AlarmRule", "TRUE");
        provisioner.provision(resource, renamed, ctx("displaced", previous));
        assertEquals("current", resource.getPhysicalId());
        assertEquals("arn:aws:cloudwatch:us-east-1:000000000000:alarm:current", resource.getAttributes().get("Arn"));
        verify(service).putCompositeAlarm(argThat(alarm -> "current".equals(alarm.getAlarmName())),
                eq(REGION), eq(false));
        doThrow(new IllegalStateException("delete unavailable"))
                .doThrow(new IllegalStateException("delete still unavailable")).doNothing()
                .when(service).deleteAlarms(List.of("displaced"), "us-east-1");
        assertFalse(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
        assertEquals(Set.of("displaced"), ReplacementCleanup.owedPhysicalIds(resource));

        JsonNode props = mapper.createObjectNode().put("AlarmName", "current").put("AlarmRule", "FALSE");
        provisioner.provision(resource, props, ctx("current", renamed));
        verify(service).putCompositeAlarm(argThat(alarm -> "current".equals(alarm.getAlarmName())),
                eq(REGION), eq(true));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        UpdateCleanupResult retry = provisioner.completeUpdate(resource);
        assertFalse(retry.complete());
        assertEquals(2, retry.attempts());
        assertEquals("displaced", retry.previousPhysicalId());
        assertEquals("delete still unavailable", retry.failureReason());
        assertEquals(Set.of("displaced"), ReplacementCleanup.owedPhysicalIds(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        assertTrue(ReplacementCleanup.owedPhysicalIds(resource).isEmpty());
        assertEquals("current", resource.getPhysicalId());
        verify(service, times(3)).deleteAlarms(List.of("displaced"), "us-east-1");
        verify(service, never()).deleteAlarms(List.of("current"), "us-east-1");
    }

}
