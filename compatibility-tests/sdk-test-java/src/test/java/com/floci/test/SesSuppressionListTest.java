package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.DeleteSuppressedDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.GetSuppressedDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.GetSuppressedDestinationResponse;
import software.amazon.awssdk.services.sesv2.model.ListSuppressedDestinationsRequest;
import software.amazon.awssdk.services.sesv2.model.ListSuppressedDestinationsResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.PutSuppressedDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.SuppressionListReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SES V2 Suppression list")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesSuppressionListTest {

    private static SesV2Client sesV2;
    private static String bounceEmail;
    private static String complaintEmail;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        String suffix = TestFixtures.uniqueName();
        bounceEmail = "sdk-bounce-" + suffix + "@example.com";
        complaintEmail = "sdk-complaint-" + suffix + "@example.com";
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                sesV2.deleteSuppressedDestination(DeleteSuppressedDestinationRequest.builder()
                        .emailAddress(bounceEmail).build());
            } catch (Exception ignored) {}
            try {
                sesV2.deleteSuppressedDestination(DeleteSuppressedDestinationRequest.builder()
                        .emailAddress(complaintEmail).build());
            } catch (Exception ignored) {}
            sesV2.close();
        }
    }

    @Test
    @Order(1)
    void putAndGet_bounceRoundTrips() {
        sesV2.putSuppressedDestination(PutSuppressedDestinationRequest.builder()
                .emailAddress(bounceEmail)
                .reason(SuppressionListReason.BOUNCE)
                .build());

        GetSuppressedDestinationResponse got = sesV2.getSuppressedDestination(
                GetSuppressedDestinationRequest.builder()
                        .emailAddress(bounceEmail).build());
        assertThat(got.suppressedDestination().emailAddress()).isEqualTo(bounceEmail);
        assertThat(got.suppressedDestination().reason()).isEqualTo(SuppressionListReason.BOUNCE);
        assertThat(got.suppressedDestination().lastUpdateTime()).isNotNull();
    }

    @Test
    @Order(2)
    void putAndGet_complaintRoundTrips() {
        sesV2.putSuppressedDestination(PutSuppressedDestinationRequest.builder()
                .emailAddress(complaintEmail)
                .reason(SuppressionListReason.COMPLAINT)
                .build());

        GetSuppressedDestinationResponse got = sesV2.getSuppressedDestination(
                GetSuppressedDestinationRequest.builder()
                        .emailAddress(complaintEmail).build());
        assertThat(got.suppressedDestination().reason()).isEqualTo(SuppressionListReason.COMPLAINT);
    }

    @Test
    @Order(3)
    void list_includesBothEntries() {
        ListSuppressedDestinationsResponse listed = sesV2.listSuppressedDestinations(
                ListSuppressedDestinationsRequest.builder().build());
        assertThat(listed.suppressedDestinationSummaries())
                .extracting(s -> s.emailAddress())
                .contains(bounceEmail, complaintEmail);
    }

    @Test
    @Order(4)
    void list_filteredByReason() {
        ListSuppressedDestinationsResponse listed = sesV2.listSuppressedDestinations(
                ListSuppressedDestinationsRequest.builder()
                        .reasons(SuppressionListReason.BOUNCE)
                        .build());
        assertThat(listed.suppressedDestinationSummaries())
                .allMatch(s -> s.reason() == SuppressionListReason.BOUNCE);
    }

    @Test
    @Order(5)
    void delete_removesEntry_subsequentGetThrowsNotFound() {
        sesV2.deleteSuppressedDestination(DeleteSuppressedDestinationRequest.builder()
                .emailAddress(bounceEmail).build());

        assertThatThrownBy(() -> sesV2.getSuppressedDestination(
                GetSuppressedDestinationRequest.builder()
                        .emailAddress(bounceEmail).build()))
                .isInstanceOf(NotFoundException.class);
    }
}
