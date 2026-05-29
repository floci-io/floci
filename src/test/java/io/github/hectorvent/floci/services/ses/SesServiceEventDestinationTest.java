package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDestination;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDimensionConfiguration;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.PinpointDestination;
import io.github.hectorvent.floci.services.ses.model.SnsDestination;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the static input validation behind the SES V2
 * ConfigurationSetEventDestination operations. These fail-closed paths are
 * pure logic and live here rather than in the integration test. Error
 * messages mirror the real AWS SESv2 wire responses.
 */
class SesServiceEventDestinationTest {

    private static EventDestination withSns(List<String> matchingEventTypes) {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(matchingEventTypes);
        SnsDestination sns = new SnsDestination();
        sns.setTopicArn("arn:aws:sns:us-east-1:000000000000:ses-events");
        ed.setSnsDestination(sns);
        return ed;
    }

    @Test
    void validName_passes() {
        assertDoesNotThrow(() -> SesService.validateEventDestinationName("my-dest_1"));
    }

    @Test
    void blankName_throwsInvalidParameterValue() {
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestinationName("   "));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void invalidNameCharacters_throws() {
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestinationName("bad name!"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("Invalid event destination name <bad name!>: only alphanumeric ASCII characters, "
                + "'_', and '-' are allowed.", ex.getMessage());
    }

    @Test
    void nameTooLong_throws() {
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestinationName("a".repeat(65)));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("Event destination name cannot exceed 64 characters.", ex.getMessage());
    }

    @Test
    void validDestination_passes() {
        assertDoesNotThrow(() -> SesService.validateEventDestination(withSns(List.of("SEND", "BOUNCE"))));
    }

    @Test
    void emptyMatchingEventTypes_throws() {
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestination(withSns(List.of())));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("At least one event type must be specified.", ex.getMessage());
    }

    @Test
    void invalidEventType_throws() {
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestination(withSns(List.of("SEND", "NOPE"))));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("Invalid event type: NOPE. Valid values are [SEND, REJECT, BOUNCE, COMPLAINT, "
                + "DELIVERY, OPEN, CLICK, RENDERING_FAILURE, DELIVERY_DELAY, SUBSCRIPTION].", ex.getMessage());
    }

    @Test
    void zeroDestinations_throws() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestination(ed));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("Event destination is not provided.", ex.getMessage());
    }

    @Test
    void multipleDestinations_throws() {
        EventDestination ed = withSns(List.of("SEND"));
        ed.setCloudWatchDestination(cloudWatch());
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestination(ed));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("Please provide only one destination with each request. Either a Firehose Destination "
                + "or a Cloudwatch Destination or an SNS Destination or an EventBridge Destination.",
                ex.getMessage());
    }

    @Test
    void cloudWatchEmptyDimensions_throws() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        ed.setCloudWatchDestination(new CloudWatchDestination());
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestination(ed));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("CloudWatch metrics dimension configuration list cannot be empty.", ex.getMessage());
    }

    @Test
    void validCloudWatchDestination_passes() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        ed.setCloudWatchDestination(cloudWatch());
        assertDoesNotThrow(() -> SesService.validateEventDestination(ed));
    }

    @Test
    void pinpointNullArn_throws() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        ed.setPinpointDestination(new PinpointDestination());
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestination(ed));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("Invalid Pinpoint application ARN provided: null.", ex.getMessage());
    }

    @Test
    void validPinpointDestination_passes() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        PinpointDestination pp = new PinpointDestination();
        pp.setApplicationArn("arn:aws:mobiletargeting:us-east-1:000000000000:apps/abc");
        ed.setPinpointDestination(pp);
        assertDoesNotThrow(() -> SesService.validateEventDestination(ed));
    }

    private static CloudWatchDestination cloudWatch() {
        CloudWatchDestination cw = new CloudWatchDestination();
        CloudWatchDimensionConfiguration dim = new CloudWatchDimensionConfiguration();
        dim.setDimensionName("floci-dim");
        dim.setDimensionValueSource("MESSAGE_TAG");
        dim.setDefaultDimensionValue("default");
        cw.setDimensionConfigurations(List.of(dim));
        return cw;
    }
}
