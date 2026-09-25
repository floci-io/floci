package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.ListTemplatesRequest;
import software.amazon.awssdk.services.ses.model.ListTemplatesResponse;
import software.amazon.awssdk.services.ses.model.TemplateMetadata;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateEmailTemplateRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailTemplateRequest;
import software.amazon.awssdk.services.sesv2.model.EmailTemplateContent;
import software.amazon.awssdk.services.sesv2.model.EmailTemplateMetadata;
import software.amazon.awssdk.services.sesv2.model.ListEmailTemplatesRequest;
import software.amazon.awssdk.services.sesv2.model.ListEmailTemplatesResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SES template list paging")
class SesTemplatePagingTest {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static List<String> names;

    @BeforeAll
    static void setup() throws InterruptedException {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        String prefix = "sdk-page-" + TestFixtures.uniqueName() + "-";
        names = List.of(prefix + "b", prefix + "c", prefix + "a");
        for (String name : names) {
            sesV2.createEmailTemplate(CreateEmailTemplateRequest.builder()
                    .templateName(name)
                    .templateContent(EmailTemplateContent.builder().subject("s").text("t").build())
                    .build());
            Thread.sleep(5);
        }
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            for (String name : names) {
                try {
                    sesV2.deleteEmailTemplate(DeleteEmailTemplateRequest.builder()
                            .templateName(name).build());
                } catch (Exception ignored) {
                    // Best-effort cleanup; a leftover template does not affect other tests.
                }
            }
            sesV2.close();
        }
        if (sesV1 != null) {
            sesV1.close();
        }
    }

    @Test
    @DisplayName("V2 paginator walks one-item pages newest first")
    void v2PaginatorWalksNewestFirst() {
        List<String> listed = new ArrayList<>();
        for (ListEmailTemplatesResponse page : sesV2.listEmailTemplatesPaginator(
                ListEmailTemplatesRequest.builder().pageSize(1).build())) {
            assertThat(page.templatesMetadata()).hasSizeLessThanOrEqualTo(1);
            page.templatesMetadata().forEach(meta -> listed.add(meta.templateName()));
        }

        assertThat(listed.stream().filter(names::contains))
                .containsExactly(names.get(2), names.get(1), names.get(0));
    }

    @Test
    @DisplayName("V1 MaxItems pages newest first and ends without a token")
    void v1PagesNewestFirst() {
        List<String> listed = new ArrayList<>();
        String token = null;
        do {
            ListTemplatesResponse page = sesV1.listTemplates(ListTemplatesRequest.builder()
                    .maxItems(1).nextToken(token).build());
            assertThat(page.templatesMetadata()).hasSizeLessThanOrEqualTo(1);
            page.templatesMetadata().stream().map(TemplateMetadata::name).forEach(listed::add);
            token = page.nextToken();
        } while (token != null);

        assertThat(listed.stream().filter(names::contains))
                .containsExactly(names.get(2), names.get(1), names.get(0));
    }

    @Test
    @DisplayName("A V1 token continues the V2 list")
    void v1TokenContinuesTheV2List() {
        ListTemplatesResponse first = sesV1.listTemplates(ListTemplatesRequest.builder()
                .maxItems(1).build());
        assertThat(first.nextToken()).isNotNull();

        ListEmailTemplatesResponse rest = sesV2.listEmailTemplates(ListEmailTemplatesRequest.builder()
                .nextToken(first.nextToken()).build());

        assertThat(rest.templatesMetadata()).extracting(EmailTemplateMetadata::templateName)
                .doesNotContain(first.templatesMetadata().get(0).name());
    }
}
