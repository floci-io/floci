package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetEventDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetEventDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.EventDestinationDefinition;
import software.amazon.awssdk.services.sesv2.model.EventType;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetEventDestinationsRequest;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetEventDestinationsResponse;
import software.amazon.awssdk.services.sesv2.model.SnsDestination;
import software.amazon.awssdk.services.sesv2.model.UpdateConfigurationSetEventDestinationRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SES ConfigurationSet event destination compatibility tests against both the
 * V1 (query) SES API via {@link SesClient} and the V2 (REST JSON) SESv2 API via
 * {@link SesV2Client}. Each protocol gets its own configuration set so V1 and V2
 * tests don't fight each other; the orderings within each cluster mirror the
 * AWS create / describe / duplicate / update / delete lifecycle.
 */
@DisplayName("SES Configuration Set Event Destinations")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetEventDestinationTest {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static String v1CsName;
    private static String v2CsName;
    private static final String ED_NAME = "ed-sns";
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:ses-events";
    private static final String TOPIC_ARN_2 = "arn:aws:sns:us-east-1:000000000000:ses-events-2";

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        String suffix = TestFixtures.uniqueName();
        v1CsName = "sdk-v1-ed-cs-" + suffix;
        v2CsName = "sdk-v2-ed-cs-" + suffix;
        sesV1.createConfigurationSet(software.amazon.awssdk.services.ses.model.CreateConfigurationSetRequest.builder()
                .configurationSet(b -> b.name(v1CsName))
                .build());
        sesV2.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(v2CsName)
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                sesV2.deleteConfigurationSetEventDestination(DeleteConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v2CsName)
                        .eventDestinationName(ED_NAME)
                        .build());
            } catch (Exception ignored) {}
            try {
                sesV2.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                        .configurationSetName(v2CsName)
                        .build());
            } catch (Exception ignored) {}
            sesV2.close();
        }
        if (sesV1 != null) {
            try {
                sesV1.deleteConfigurationSetEventDestination(
                        software.amazon.awssdk.services.ses.model.DeleteConfigurationSetEventDestinationRequest.builder()
                                .configurationSetName(v1CsName)
                                .eventDestinationName(ED_NAME)
                                .build());
            } catch (Exception ignored) {}
            try {
                sesV1.deleteConfigurationSet(software.amazon.awssdk.services.ses.model.DeleteConfigurationSetRequest.builder()
                        .configurationSetName(v1CsName)
                        .build());
            } catch (Exception ignored) {}
            sesV1.close();
        }
    }

    @Test
    @Order(1)
    void createEventDestination() {
        sesV2.createConfigurationSetEventDestination(CreateConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(v2CsName)
                .eventDestinationName(ED_NAME)
                .eventDestination(EventDestinationDefinition.builder()
                        .enabled(true)
                        .matchingEventTypes(EventType.SEND, EventType.BOUNCE)
                        .snsDestination(SnsDestination.builder().topicArn(TOPIC_ARN).build())
                        .build())
                .build());
    }

    @Test
    @Order(2)
    void getEventDestinationsReturnsRoundTrip() {
        GetConfigurationSetEventDestinationsResponse response =
                sesV2.getConfigurationSetEventDestinations(GetConfigurationSetEventDestinationsRequest.builder()
                        .configurationSetName(v2CsName)
                        .build());

        assertThat(response.eventDestinations()).hasSize(1);
        assertThat(response.eventDestinations().get(0).name()).isEqualTo(ED_NAME);
        assertThat(response.eventDestinations().get(0).enabled()).isTrue();
        assertThat(response.eventDestinations().get(0).matchingEventTypes())
                .contains(EventType.SEND, EventType.BOUNCE);
        assertThat(response.eventDestinations().get(0).snsDestination().topicArn()).isEqualTo(TOPIC_ARN);
    }

    @Test
    @Order(3)
    void createDuplicateRejectedWith400() {
        assertThatThrownBy(() -> sesV2.createConfigurationSetEventDestination(
                CreateConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v2CsName)
                        .eventDestinationName(ED_NAME)
                        .eventDestination(EventDestinationDefinition.builder()
                                .matchingEventTypes(EventType.SEND)
                                .snsDestination(SnsDestination.builder().topicArn(TOPIC_ARN).build())
                                .build())
                        .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(4)
    void updateReplacesDefinition() {
        sesV2.updateConfigurationSetEventDestination(UpdateConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(v2CsName)
                .eventDestinationName(ED_NAME)
                .eventDestination(EventDestinationDefinition.builder()
                        .enabled(false)
                        .matchingEventTypes(EventType.DELIVERY)
                        .snsDestination(SnsDestination.builder().topicArn(TOPIC_ARN_2).build())
                        .build())
                .build());

        GetConfigurationSetEventDestinationsResponse response =
                sesV2.getConfigurationSetEventDestinations(GetConfigurationSetEventDestinationsRequest.builder()
                        .configurationSetName(v2CsName)
                        .build());
        assertThat(response.eventDestinations()).hasSize(1);
        assertThat(response.eventDestinations().get(0).enabled()).isFalse();
        assertThat(response.eventDestinations().get(0).matchingEventTypes()).contains(EventType.DELIVERY);
        assertThat(response.eventDestinations().get(0).snsDestination().topicArn()).isEqualTo(TOPIC_ARN_2);
    }

    @Test
    @Order(5)
    void updateUnknownReturns404() {
        assertThatThrownBy(() -> sesV2.updateConfigurationSetEventDestination(
                UpdateConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v2CsName)
                        .eventDestinationName("ed-ghost")
                        .eventDestination(EventDestinationDefinition.builder()
                                .matchingEventTypes(EventType.SEND)
                                .snsDestination(SnsDestination.builder().topicArn(TOPIC_ARN).build())
                                .build())
                        .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    @Test
    @Order(6)
    void deleteEventDestination() {
        sesV2.deleteConfigurationSetEventDestination(DeleteConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(v2CsName)
                .eventDestinationName(ED_NAME)
                .build());

        GetConfigurationSetEventDestinationsResponse response =
                sesV2.getConfigurationSetEventDestinations(GetConfigurationSetEventDestinationsRequest.builder()
                        .configurationSetName(v2CsName)
                        .build());
        assertThat(response.eventDestinations()).isEmpty();
    }

    @Test
    @Order(7)
    void deleteUnknownReturns404() {
        assertThatThrownBy(() -> sesV2.deleteConfigurationSetEventDestination(
                DeleteConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v2CsName)
                        .eventDestinationName("ed-ghost")
                        .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    @Test
    @Order(8)
    void createOnUnknownConfigSetReturns404() {
        assertThatThrownBy(() -> sesV2.createConfigurationSetEventDestination(
                CreateConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName("sdk-ed-cs-missing-" + System.currentTimeMillis())
                        .eventDestinationName("ed-x")
                        .eventDestination(EventDestinationDefinition.builder()
                                .matchingEventTypes(EventType.SEND)
                                .snsDestination(SnsDestination.builder().topicArn(TOPIC_ARN).build())
                                .build())
                        .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    // ─────────────────────────── V1 (Query/XML) ───────────────────────────

    @Test
    @Order(10)
    void v1_createEventDestination() {
        sesV1.createConfigurationSetEventDestination(
                software.amazon.awssdk.services.ses.model.CreateConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v1CsName)
                        .eventDestination(software.amazon.awssdk.services.ses.model.EventDestination.builder()
                                .name(ED_NAME)
                                .enabled(true)
                                .matchingEventTypes(
                                        software.amazon.awssdk.services.ses.model.EventType.SEND,
                                        software.amazon.awssdk.services.ses.model.EventType.BOUNCE)
                                .snsDestination(software.amazon.awssdk.services.ses.model.SNSDestination.builder()
                                        .topicARN(TOPIC_ARN)
                                        .build())
                                .build())
                        .build());
    }

    @Test
    @Order(11)
    void v1_describeConfigurationSet_withEventDestinationsAttribute_returnsRoundTrip() {
        software.amazon.awssdk.services.ses.model.DescribeConfigurationSetResponse resp =
                sesV1.describeConfigurationSet(
                        software.amazon.awssdk.services.ses.model.DescribeConfigurationSetRequest.builder()
                                .configurationSetName(v1CsName)
                                .configurationSetAttributeNames(
                                        software.amazon.awssdk.services.ses.model.ConfigurationSetAttribute.EVENT_DESTINATIONS)
                                .build());
        assertThat(resp.eventDestinations()).hasSize(1);
        software.amazon.awssdk.services.ses.model.EventDestination ed = resp.eventDestinations().get(0);
        assertThat(ed.name()).isEqualTo(ED_NAME);
        assertThat(ed.enabled()).isTrue();
        assertThat(ed.matchingEventTypes()).contains(
                software.amazon.awssdk.services.ses.model.EventType.SEND,
                software.amazon.awssdk.services.ses.model.EventType.BOUNCE);
        assertThat(ed.snsDestination()).isNotNull();
        assertThat(ed.snsDestination().topicARN()).isEqualTo(TOPIC_ARN);
    }

    @Test
    @Order(12)
    void v1_describeConfigurationSet_withoutEventDestinationsAttribute_returnsEmptyList() {
        software.amazon.awssdk.services.ses.model.DescribeConfigurationSetResponse resp =
                sesV1.describeConfigurationSet(
                        software.amazon.awssdk.services.ses.model.DescribeConfigurationSetRequest.builder()
                                .configurationSetName(v1CsName)
                                .build());
        assertThat(resp.eventDestinations()).isEmpty();
    }

    @Test
    @Order(13)
    void v1_createDuplicateRejected() {
        assertThatThrownBy(() -> sesV1.createConfigurationSetEventDestination(
                software.amazon.awssdk.services.ses.model.CreateConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v1CsName)
                        .eventDestination(software.amazon.awssdk.services.ses.model.EventDestination.builder()
                                .name(ED_NAME)
                                .enabled(true)
                                .matchingEventTypes(software.amazon.awssdk.services.ses.model.EventType.SEND)
                                .snsDestination(software.amazon.awssdk.services.ses.model.SNSDestination.builder()
                                        .topicARN(TOPIC_ARN)
                                        .build())
                                .build())
                        .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).awsErrorDetails().errorCode())
                .isEqualTo("AlreadyExists");
    }

    @Test
    @Order(14)
    void v1_updateReplacesDefinition() {
        sesV1.updateConfigurationSetEventDestination(
                software.amazon.awssdk.services.ses.model.UpdateConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v1CsName)
                        .eventDestination(software.amazon.awssdk.services.ses.model.EventDestination.builder()
                                .name(ED_NAME)
                                .enabled(false)
                                .matchingEventTypes(software.amazon.awssdk.services.ses.model.EventType.COMPLAINT)
                                .snsDestination(software.amazon.awssdk.services.ses.model.SNSDestination.builder()
                                        .topicARN(TOPIC_ARN_2)
                                        .build())
                                .build())
                        .build());

        software.amazon.awssdk.services.ses.model.DescribeConfigurationSetResponse resp =
                sesV1.describeConfigurationSet(
                        software.amazon.awssdk.services.ses.model.DescribeConfigurationSetRequest.builder()
                                .configurationSetName(v1CsName)
                                .configurationSetAttributeNames(
                                        software.amazon.awssdk.services.ses.model.ConfigurationSetAttribute.EVENT_DESTINATIONS)
                                .build());
        software.amazon.awssdk.services.ses.model.EventDestination ed = resp.eventDestinations().get(0);
        assertThat(ed.enabled()).isFalse();
        assertThat(ed.matchingEventTypes())
                .containsExactly(software.amazon.awssdk.services.ses.model.EventType.COMPLAINT);
        assertThat(ed.snsDestination().topicARN()).isEqualTo(TOPIC_ARN_2);
    }

    @Test
    @Order(15)
    void v1_updateUnknown_throws() {
        assertThatThrownBy(() -> sesV1.updateConfigurationSetEventDestination(
                software.amazon.awssdk.services.ses.model.UpdateConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v1CsName)
                        .eventDestination(software.amazon.awssdk.services.ses.model.EventDestination.builder()
                                .name("ed-ghost")
                                .enabled(true)
                                .matchingEventTypes(software.amazon.awssdk.services.ses.model.EventType.SEND)
                                .snsDestination(software.amazon.awssdk.services.ses.model.SNSDestination.builder()
                                        .topicARN(TOPIC_ARN)
                                        .build())
                                .build())
                        .build()))
                .isInstanceOf(AwsServiceException.class);
    }

    @Test
    @Order(16)
    void v1_partialFirehoseRejected() {
        assertThatThrownBy(() -> sesV1.createConfigurationSetEventDestination(
                software.amazon.awssdk.services.ses.model.CreateConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v1CsName)
                        .eventDestination(software.amazon.awssdk.services.ses.model.EventDestination.builder()
                                .name("ed-firehose-bad")
                                .enabled(true)
                                .matchingEventTypes(software.amazon.awssdk.services.ses.model.EventType.SEND)
                                .kinesisFirehoseDestination(
                                        software.amazon.awssdk.services.ses.model.KinesisFirehoseDestination.builder()
                                                .iamRoleARN("arn:aws:iam::000000000000:role/ses-firehose")
                                                .build())
                                .build())
                        .build()))
                .isInstanceOf(AwsServiceException.class)
                .hasMessageContaining("KinesisFirehoseDestination requires both");
    }

    @Test
    @Order(17)
    void v1_deleteEventDestination() {
        sesV1.deleteConfigurationSetEventDestination(
                software.amazon.awssdk.services.ses.model.DeleteConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v1CsName)
                        .eventDestinationName(ED_NAME)
                        .build());

        software.amazon.awssdk.services.ses.model.DescribeConfigurationSetResponse resp =
                sesV1.describeConfigurationSet(
                        software.amazon.awssdk.services.ses.model.DescribeConfigurationSetRequest.builder()
                                .configurationSetName(v1CsName)
                                .configurationSetAttributeNames(
                                        software.amazon.awssdk.services.ses.model.ConfigurationSetAttribute.EVENT_DESTINATIONS)
                                .build());
        assertThat(resp.eventDestinations()).isEmpty();
    }

    @Test
    @Order(18)
    void v1_deleteUnknown_throws() {
        assertThatThrownBy(() -> sesV1.deleteConfigurationSetEventDestination(
                software.amazon.awssdk.services.ses.model.DeleteConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(v1CsName)
                        .eventDestinationName("ed-ghost")
                        .build()))
                .isInstanceOf(AwsServiceException.class);
    }
}
