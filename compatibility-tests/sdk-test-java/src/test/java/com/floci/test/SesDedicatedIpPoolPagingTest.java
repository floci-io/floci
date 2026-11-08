package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateDedicatedIpPoolRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteDedicatedIpPoolRequest;
import software.amazon.awssdk.services.sesv2.model.GetDedicatedIpsRequest;
import software.amazon.awssdk.services.sesv2.model.ListDedicatedIpPoolsRequest;
import software.amazon.awssdk.services.sesv2.model.ListDedicatedIpPoolsResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SES v2 dedicated IP pool list paging")
class SesDedicatedIpPoolPagingTest {

    private static SesV2Client sesV2;
    private static List<String> names;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        String prefix = "sdk-page-" + TestFixtures.uniqueName() + "-";
        names = List.of(prefix + "b", prefix + "c", prefix + "a");
        for (String name : names) {
            sesV2.createDedicatedIpPool(CreateDedicatedIpPoolRequest.builder().poolName(name).build());
        }
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            for (String name : names) {
                try {
                    sesV2.deleteDedicatedIpPool(DeleteDedicatedIpPoolRequest.builder().poolName(name).build());
                } catch (Exception ignored) {
                    // Best-effort cleanup; a leftover pool does not affect other tests.
                }
            }
            sesV2.close();
        }
    }

    @Test
    @DisplayName("Paginator walks one-item pages over every pool once")
    void paginatorWalksEveryPoolOnce() {
        List<String> listed = new ArrayList<>();
        for (ListDedicatedIpPoolsResponse page : sesV2.listDedicatedIpPoolsPaginator(
                ListDedicatedIpPoolsRequest.builder().pageSize(1).build())) {
            assertThat(page.dedicatedIpPools()).hasSizeLessThanOrEqualTo(1);
            listed.addAll(page.dedicatedIpPools());
        }

        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed.stream().filter(names::contains)).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("GetDedicatedIps paginator ends on an empty page")
    void getDedicatedIpsPaginatorEnds() {
        assertThat(sesV2.getDedicatedIpsPaginator(GetDedicatedIpsRequest.builder().pageSize(1).build())
                .stream().flatMap(page -> page.dedicatedIps().stream())).isEmpty();
    }
}
