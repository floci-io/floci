package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.CustomVerificationEmailTemplate;
import software.amazon.awssdk.services.ses.model.ListCustomVerificationEmailTemplatesRequest;
import software.amazon.awssdk.services.ses.model.ListCustomVerificationEmailTemplatesResponse;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.CustomVerificationEmailTemplateMetadata;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// The v1 and v2 SDKs both define ListCustomVerificationEmailTemplatesResponse: v1's is imported, v2's qualified.
@DisplayName("SES custom verification email template list paging")
class SesCustomVerificationEmailTemplatePagingTest {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static String from;
    private static List<String> names;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        String prefix = "sdk-page-" + TestFixtures.uniqueName() + "-";
        from = prefix + "sender@floci-cvet.test";
        names = List.of(prefix + "b", prefix + "c", prefix + "a");
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(from).build());
        for (String name : names) {
            sesV2.createCustomVerificationEmailTemplate(b -> b
                    .templateName(name).fromEmailAddress(from).templateSubject("Verify")
                    .templateContent("<html><body>verify</body></html>")
                    .successRedirectionURL("https://example.com/ok")
                    .failureRedirectionURL("https://example.com/fail"));
        }
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            for (String name : names) {
                try {
                    sesV2.deleteCustomVerificationEmailTemplate(b -> b.templateName(name));
                } catch (Exception ignored) {
                    // Best-effort cleanup; a leftover template does not affect other tests.
                }
            }
            try {
                sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(from).build());
            } catch (Exception ignored) {
                // Best-effort cleanup; a leftover identity does not affect other tests.
            }
            sesV2.close();
        }
        if (sesV1 != null) {
            sesV1.close();
        }
    }

    @Test
    @DisplayName("V2 paginator walks one-item pages over every template once")
    void v2PaginatorWalksEveryTemplateOnce() {
        List<String> listed = new ArrayList<>();
        for (software.amazon.awssdk.services.sesv2.model.ListCustomVerificationEmailTemplatesResponse page
                : sesV2.listCustomVerificationEmailTemplatesPaginator(b -> b.pageSize(1))) {
            assertThat(page.customVerificationEmailTemplates()).hasSizeLessThanOrEqualTo(1);
            page.customVerificationEmailTemplates().stream()
                    .map(CustomVerificationEmailTemplateMetadata::templateName).forEach(listed::add);
        }

        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed.stream().filter(names::contains)).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("V1 MaxResults pages over every template once and ends without a token")
    void v1PagesOverEveryTemplateOnce() {
        List<String> listed = new ArrayList<>();
        String token = null;
        do {
            ListCustomVerificationEmailTemplatesResponse page = sesV1.listCustomVerificationEmailTemplates(
                    ListCustomVerificationEmailTemplatesRequest.builder().maxResults(1).nextToken(token).build());
            assertThat(page.customVerificationEmailTemplates()).hasSizeLessThanOrEqualTo(1);
            page.customVerificationEmailTemplates().stream()
                    .map(CustomVerificationEmailTemplate::templateName).forEach(listed::add);
            token = page.nextToken();
        } while (token != null);

        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed.stream().filter(names::contains)).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("A V1 token continues the V2 list")
    void v1TokenContinuesTheV2List() {
        ListCustomVerificationEmailTemplatesResponse first = sesV1.listCustomVerificationEmailTemplates(
                ListCustomVerificationEmailTemplatesRequest.builder().maxResults(1).build());
        assertThat(first.nextToken()).isNotNull();

        List<String> rest = sesV2.listCustomVerificationEmailTemplates(b -> b.nextToken(first.nextToken()))
                .customVerificationEmailTemplates().stream()
                .map(CustomVerificationEmailTemplateMetadata::templateName).toList();

        assertThat(rest).doesNotContain(first.customVerificationEmailTemplates().get(0).templateName());
    }
}
