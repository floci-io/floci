package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
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
 * SES V2 ConfigurationSet event destination compatibility tests using the
 * real AWS SDK for Java v2 (SesV2Client) against a live Floci instance.
 */
@DisplayName("SES Configuration Set Event Destinations")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetEventDestinationTest {

    private static SesV2Client ses;
    private static String csName;
    private static final String ED_NAME = "ed-sns";
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:ses-events";
    private static final String TOPIC_ARN_2 = "arn:aws:sns:us-east-1:000000000000:ses-events-2";

    @BeforeAll
    static void setup() {
        ses = TestFixtures.sesV2Client();
        csName = "sdk-ed-cs-" + TestFixtures.uniqueName();
        ses.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(csName)
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (ses == null) {
            return;
        }
        try {
            ses.deleteConfigurationSetEventDestination(DeleteConfigurationSetEventDestinationRequest.builder()
                    .configurationSetName(csName)
                    .eventDestinationName(ED_NAME)
                    .build());
        } catch (Exception ignored) {}
        try {
            ses.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                    .configurationSetName(csName)
                    .build());
        } catch (Exception ignored) {}
        ses.close();
    }

    @Test
    @Order(1)
    void createEventDestination() {
        ses.createConfigurationSetEventDestination(CreateConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(csName)
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
                ses.getConfigurationSetEventDestinations(GetConfigurationSetEventDestinationsRequest.builder()
                        .configurationSetName(csName)
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
        assertThatThrownBy(() -> ses.createConfigurationSetEventDestination(
                CreateConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(csName)
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
        ses.updateConfigurationSetEventDestination(UpdateConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(csName)
                .eventDestinationName(ED_NAME)
                .eventDestination(EventDestinationDefinition.builder()
                        .enabled(false)
                        .matchingEventTypes(EventType.DELIVERY)
                        .snsDestination(SnsDestination.builder().topicArn(TOPIC_ARN_2).build())
                        .build())
                .build());

        GetConfigurationSetEventDestinationsResponse response =
                ses.getConfigurationSetEventDestinations(GetConfigurationSetEventDestinationsRequest.builder()
                        .configurationSetName(csName)
                        .build());
        assertThat(response.eventDestinations()).hasSize(1);
        assertThat(response.eventDestinations().get(0).enabled()).isFalse();
        assertThat(response.eventDestinations().get(0).matchingEventTypes()).contains(EventType.DELIVERY);
        assertThat(response.eventDestinations().get(0).snsDestination().topicArn()).isEqualTo(TOPIC_ARN_2);
    }

    @Test
    @Order(5)
    void updateUnknownReturns404() {
        assertThatThrownBy(() -> ses.updateConfigurationSetEventDestination(
                UpdateConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(csName)
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
        ses.deleteConfigurationSetEventDestination(DeleteConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(csName)
                .eventDestinationName(ED_NAME)
                .build());

        GetConfigurationSetEventDestinationsResponse response =
                ses.getConfigurationSetEventDestinations(GetConfigurationSetEventDestinationsRequest.builder()
                        .configurationSetName(csName)
                        .build());
        assertThat(response.eventDestinations()).isEmpty();
    }

    @Test
    @Order(7)
    void deleteUnknownReturns404() {
        assertThatThrownBy(() -> ses.deleteConfigurationSetEventDestination(
                DeleteConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(csName)
                        .eventDestinationName("ed-ghost")
                        .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    @Test
    @Order(8)
    void createOnUnknownConfigSetReturns404() {
        assertThatThrownBy(() -> ses.createConfigurationSetEventDestination(
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
}
