package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.DeleteSuppressedDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.InvalidNextTokenException;
import software.amazon.awssdk.services.sesv2.model.ListSuppressedDestinationsRequest;
import software.amazon.awssdk.services.sesv2.model.ListSuppressedDestinationsResponse;
import software.amazon.awssdk.services.sesv2.model.PutSuppressedDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.SuppressedDestinationSummary;
import software.amazon.awssdk.services.sesv2.model.SuppressionListReason;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SES v2 suppression list paging")
class SesSuppressionListPagingTest {

    private static SesV2Client sesV2;
    private static List<String> bounces;
    private static String complaint;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        String prefix = "sdk-page-" + TestFixtures.uniqueName() + "-";
        bounces = List.of(prefix + "b@example.com", prefix + "a@example.com");
        complaint = prefix + "c@example.com";
        for (String address : bounces) {
            put(address, SuppressionListReason.BOUNCE);
        }
        put(complaint, SuppressionListReason.COMPLAINT);
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            List<String> all = new ArrayList<>(bounces);
            all.add(complaint);
            for (String address : all) {
                try {
                    sesV2.deleteSuppressedDestination(DeleteSuppressedDestinationRequest.builder()
                            .emailAddress(address).build());
                } catch (Exception ignored) {
                    // Best-effort cleanup; a leftover destination does not affect other tests.
                }
            }
            sesV2.close();
        }
    }

    @Test
    @DisplayName("Paginator walks one-item pages of one reason over every destination once")
    void paginatorWalksEveryFilteredDestinationOnce() {
        List<String> listed = new ArrayList<>();
        for (ListSuppressedDestinationsResponse page : sesV2.listSuppressedDestinationsPaginator(
                ListSuppressedDestinationsRequest.builder()
                        .reasons(SuppressionListReason.BOUNCE).pageSize(1).build())) {
            assertThat(page.suppressedDestinationSummaries()).hasSizeLessThanOrEqualTo(1);
            page.suppressedDestinationSummaries().stream()
                    .map(SuppressedDestinationSummary::emailAddress).forEach(listed::add);
        }

        assertThat(listed).doesNotHaveDuplicates().doesNotContain(complaint);
        assertThat(listed.stream().filter(bounces::contains)).containsExactlyInAnyOrderElementsOf(bounces);
    }

    @Test
    @DisplayName("A token taken with one reason filter is refused without it")
    void tokenIsBoundToItsFilter() {
        ListSuppressedDestinationsResponse first = sesV2.listSuppressedDestinations(
                ListSuppressedDestinationsRequest.builder()
                        .reasons(SuppressionListReason.BOUNCE).pageSize(1).build());
        assertThat(first.nextToken()).isNotNull();

        assertThatThrownBy(() -> sesV2.listSuppressedDestinations(
                ListSuppressedDestinationsRequest.builder().nextToken(first.nextToken()).build()))
                .isInstanceOf(InvalidNextTokenException.class)
                .hasMessageContaining("Token is invalid.");
    }

    private static void put(String address, SuppressionListReason reason) {
        sesV2.putSuppressedDestination(PutSuppressedDestinationRequest.builder()
                .emailAddress(address).reason(reason).build());
    }
}
