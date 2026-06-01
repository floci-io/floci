package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDestination;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDimensionConfiguration;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.KinesisFirehoseDestination;
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

    @Test
    void snsBlankTopicArn_throws() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        SnsDestination sns = new SnsDestination();
        sns.setTopicArn("");
        ed.setSnsDestination(sns);
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestination(ed));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("SnsDestination requires a non-blank TopicArn.", ex.getMessage());
    }

    @Test
    void snsNullTopicArn_throws() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        ed.setSnsDestination(new SnsDestination()); // TopicArn left null
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestination(ed));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("SnsDestination requires a non-blank TopicArn.", ex.getMessage());
    }

    @Test
    void firehoseMissingDeliveryStream_throws() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        KinesisFirehoseDestination fh = new KinesisFirehoseDestination();
        fh.setIamRoleArn("arn:aws:iam::000000000000:role/ses-firehose");
        // DeliveryStreamArn left null
        ed.setKinesisFirehoseDestination(fh);
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestination(ed));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("KinesisFirehoseDestination requires both IamRoleArn and DeliveryStreamArn.",
                ex.getMessage());
    }

    @Test
    void firehoseMissingIamRole_throws() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        KinesisFirehoseDestination fh = new KinesisFirehoseDestination();
        fh.setDeliveryStreamArn("arn:aws:firehose:us-east-1:000000000000:deliverystream/ses");
        // IamRoleArn left null
        ed.setKinesisFirehoseDestination(fh);
        assertThrows(AwsException.class, () -> SesService.validateEventDestination(ed));
    }

    @Test
    void firehoseValid_passes() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        KinesisFirehoseDestination fh = new KinesisFirehoseDestination();
        fh.setIamRoleArn("arn:aws:iam::000000000000:role/ses-firehose");
        fh.setDeliveryStreamArn("arn:aws:firehose:us-east-1:000000000000:deliverystream/ses");
        ed.setKinesisFirehoseDestination(fh);
        assertDoesNotThrow(() -> SesService.validateEventDestination(ed));
    }

    @Test
    void cloudWatchDimensionMissingName_throws() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        CloudWatchDestination cw = new CloudWatchDestination();
        CloudWatchDimensionConfiguration dim = new CloudWatchDimensionConfiguration();
        // DimensionName left null
        dim.setDimensionValueSource("MESSAGE_TAG");
        dim.setDefaultDimensionValue("default");
        cw.setDimensionConfigurations(List.of(dim));
        ed.setCloudWatchDestination(cw);
        AwsException ex = assertThrows(AwsException.class,
                () -> SesService.validateEventDestination(ed));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        // Member index 1-based for callers, included in the error message.
        assertEquals("CloudWatchDestination dimension configurations require "
                + "DimensionName, DimensionValueSource, and DefaultDimensionValue "
                + "(missing on member 1).", ex.getMessage());
    }

    @Test
    void cloudWatchDimensionBlankValueSource_throws() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        CloudWatchDestination cw = new CloudWatchDestination();
        CloudWatchDimensionConfiguration dim = new CloudWatchDimensionConfiguration();
        dim.setDimensionName("dim");
        dim.setDimensionValueSource("");
        dim.setDefaultDimensionValue("default");
        cw.setDimensionConfigurations(List.of(dim));
        ed.setCloudWatchDestination(cw);
        assertThrows(AwsException.class, () -> SesService.validateEventDestination(ed));
    }

    @Test
    void cloudWatchDimensionMissingDefaultValue_throws() {
        EventDestination ed = new EventDestination();
        ed.setMatchingEventTypes(List.of("SEND"));
        CloudWatchDestination cw = new CloudWatchDestination();
        CloudWatchDimensionConfiguration dim = new CloudWatchDimensionConfiguration();
        dim.setDimensionName("dim");
        dim.setDimensionValueSource("MESSAGE_TAG");
        // DefaultDimensionValue left null
        cw.setDimensionConfigurations(List.of(dim));
        ed.setCloudWatchDestination(cw);
        assertThrows(AwsException.class, () -> SesService.validateEventDestination(ed));
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
