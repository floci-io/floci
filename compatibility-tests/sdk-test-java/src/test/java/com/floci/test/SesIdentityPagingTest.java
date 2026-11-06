package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.ListIdentitiesRequest;
import software.amazon.awssdk.services.ses.model.ListIdentitiesResponse;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.IdentityInfo;
import software.amazon.awssdk.services.sesv2.model.ListEmailIdentitiesRequest;
import software.amazon.awssdk.services.sesv2.model.ListEmailIdentitiesResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SES identity list paging")
class SesIdentityPagingTest {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static List<String> names;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        String prefix = "sdk-page-" + TestFixtures.uniqueName() + "-";
        names = List.of(prefix + "b.test", prefix + "c.test", prefix + "a.test");
        for (String name : names) {
            sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(name).build());
        }
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            for (String name : names) {
                try {
                    sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(name).build());
                } catch (Exception ignored) {
                    // Best-effort cleanup; a leftover identity does not affect other tests.
                }
            }
            sesV2.close();
        }
        if (sesV1 != null) {
            sesV1.close();
        }
    }

    @Test
    @DisplayName("V2 paginator walks small pages over every identity once")
    void v2PaginatorWalksEveryIdentityOnce() {
        List<String> listed = new ArrayList<>();
        for (ListEmailIdentitiesResponse page : sesV2.listEmailIdentitiesPaginator(
                ListEmailIdentitiesRequest.builder().pageSize(5).build())) {
            assertThat(page.emailIdentities()).hasSizeLessThanOrEqualTo(5);
            page.emailIdentities().forEach(identity -> listed.add(identity.identityName()));
        }

        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed.stream().filter(names::contains)).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("V1 MaxItems pages over every identity once and ends without a token")
    void v1PagesOverEveryIdentityOnce() {
        List<String> listed = new ArrayList<>();
        String token = null;
        do {
            ListIdentitiesResponse page = sesV1.listIdentities(ListIdentitiesRequest.builder()
                    .maxItems(5).nextToken(token).build());
            assertThat(page.identities()).hasSizeLessThanOrEqualTo(5);
            listed.addAll(page.identities());
            token = page.nextToken();
        } while (token != null);

        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed.stream().filter(names::contains)).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("A V1 token continues the V2 list")
    void v1TokenContinuesTheV2List() {
        ListIdentitiesResponse first = sesV1.listIdentities(ListIdentitiesRequest.builder()
                .maxItems(1).build());
        assertThat(first.nextToken()).isNotNull();

        ListEmailIdentitiesResponse rest = sesV2.listEmailIdentities(ListEmailIdentitiesRequest.builder()
                .nextToken(first.nextToken()).build());

        assertThat(rest.emailIdentities()).extracting(IdentityInfo::identityName)
                .doesNotContain(first.identities().get(0));
    }
}
