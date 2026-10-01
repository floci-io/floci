package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.ConfigurationSet;
import software.amazon.awssdk.services.ses.model.ListConfigurationSetsRequest;
import software.amazon.awssdk.services.ses.model.ListConfigurationSetsResponse;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// The v1 and v2 SDKs both define ListConfigurationSetsRequest/Response: v1's are imported, v2's qualified.
@DisplayName("SES configuration set list paging")
class SesConfigurationSetPagingTest {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static List<String> names;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        String prefix = "sdk-page-" + TestFixtures.uniqueName() + "-";
        names = List.of(prefix + "b", prefix + "c", prefix + "a");
        for (String name : names) {
            sesV2.createConfigurationSet(CreateConfigurationSetRequest.builder()
                    .configurationSetName(name).build());
        }
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            for (String name : names) {
                try {
                    sesV2.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                            .configurationSetName(name).build());
                } catch (Exception ignored) {
                    // Best-effort cleanup; a leftover configuration set does not affect other tests.
                }
            }
            sesV2.close();
        }
        if (sesV1 != null) {
            sesV1.close();
        }
    }

    @Test
    @DisplayName("V2 paginator walks one-item pages over every configuration set once")
    void v2PaginatorWalksEveryConfigurationSetOnce() {
        List<String> listed = new ArrayList<>();
        for (software.amazon.awssdk.services.sesv2.model.ListConfigurationSetsResponse page
                : sesV2.listConfigurationSetsPaginator(
                        software.amazon.awssdk.services.sesv2.model.ListConfigurationSetsRequest.builder()
                                .pageSize(1).build())) {
            assertThat(page.configurationSets()).hasSizeLessThanOrEqualTo(1);
            listed.addAll(page.configurationSets());
        }

        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed.stream().filter(names::contains)).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("V1 MaxItems pages over every configuration set once and ends without a token")
    void v1PagesOverEveryConfigurationSetOnce() {
        List<String> listed = new ArrayList<>();
        String token = null;
        do {
            ListConfigurationSetsResponse page = sesV1.listConfigurationSets(ListConfigurationSetsRequest.builder()
                    .maxItems(1).nextToken(token).build());
            assertThat(page.configurationSets()).hasSizeLessThanOrEqualTo(1);
            page.configurationSets().stream().map(ConfigurationSet::name).forEach(listed::add);
            token = page.nextToken();
        } while (token != null);

        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed.stream().filter(names::contains)).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("A V1 token continues the V2 list")
    void v1TokenContinuesTheV2List() {
        ListConfigurationSetsResponse first = sesV1.listConfigurationSets(ListConfigurationSetsRequest.builder()
                .maxItems(1).build());
        assertThat(first.nextToken()).isNotNull();

        software.amazon.awssdk.services.sesv2.model.ListConfigurationSetsResponse rest =
                sesV2.listConfigurationSets(software.amazon.awssdk.services.sesv2.model.ListConfigurationSetsRequest
                        .builder().nextToken(first.nextToken()).build());

        assertThat(rest.configurationSets()).doesNotContain(first.configurationSets().get(0).name());
    }
}
