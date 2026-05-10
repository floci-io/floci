package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailTemplateRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailTemplateRequest;
import software.amazon.awssdk.services.sesv2.model.EmailTemplateContent;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityResponse;
import software.amazon.awssdk.services.sesv2.model.GetEmailTemplateRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailTemplateResponse;
import software.amazon.awssdk.services.sesv2.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.sesv2.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.Tag;
import software.amazon.awssdk.services.sesv2.model.TagResourceRequest;
import software.amazon.awssdk.services.sesv2.model.UntagResourceRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SES V2 TagResource / UntagResource / ListTagsForResource")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTagResourceTest {

    private static SesV2Client sesV2;
    private static String configSetName;
    private static String configSetArn;
    private static String templateName;
    private static String templateArn;
    private static String identityValue;
    private static String identityArn;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        String suffix = TestFixtures.uniqueName();
        configSetName = "sdk-tag-cs-" + suffix;
        configSetArn = "arn:aws:ses:us-east-1:000000000000:configuration-set/" + configSetName;
        templateName = "sdk-tag-tpl-" + suffix;
        templateArn = "arn:aws:ses:us-east-1:000000000000:template/" + templateName;
        identityValue = "sdk-tag-id-" + suffix + "@example.com";
        identityArn = "arn:aws:ses:us-east-1:000000000000:identity/" + identityValue;

        sesV2.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(configSetName)
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                sesV2.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                        .configurationSetName(configSetName).build());
            } catch (Exception ignored) {}
            try {
                sesV2.deleteEmailTemplate(DeleteEmailTemplateRequest.builder()
                        .templateName(templateName).build());
            } catch (Exception ignored) {}
            try {
                sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder()
                        .emailIdentity(identityValue).build());
            } catch (Exception ignored) {}
            sesV2.close();
        }
    }

    @Test
    @Order(1)
    void listTagsForResource_initiallyEmpty() {
        ListTagsForResourceResponse response = sesV2.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(configSetArn).build());
        assertThat(response.tags()).isEmpty();
    }

    @Test
    @Order(2)
    void tagResource_addsTagsAndListReflectsThem() {
        sesV2.tagResource(TagResourceRequest.builder()
                .resourceArn(configSetArn)
                .tags(
                        Tag.builder().key("env").value("dev").build(),
                        Tag.builder().key("owner").value("alice").build())
                .build());

        ListTagsForResourceResponse response = sesV2.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(configSetArn).build());

        List<Tag> tags = response.tags();
        assertThat(tags).hasSize(2);
        assertThat(tags).anySatisfy(t -> {
            assertThat(t.key()).isEqualTo("env");
            assertThat(t.value()).isEqualTo("dev");
        });
        assertThat(tags).anySatisfy(t -> {
            assertThat(t.key()).isEqualTo("owner");
            assertThat(t.value()).isEqualTo("alice");
        });
    }

    @Test
    @Order(3)
    void tagResource_existingKeyReplacesValue() {
        sesV2.tagResource(TagResourceRequest.builder()
                .resourceArn(configSetArn)
                .tags(Tag.builder().key("env").value("prod").build())
                .build());

        ListTagsForResourceResponse response = sesV2.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(configSetArn).build());
        assertThat(response.tags())
                .filteredOn(t -> t.key().equals("env"))
                .singleElement()
                .extracting(Tag::value)
                .isEqualTo("prod");
    }

    @Test
    @Order(4)
    void untagResource_removesSpecifiedKeys() {
        sesV2.untagResource(UntagResourceRequest.builder()
                .resourceArn(configSetArn)
                .tagKeys("env")
                .build());

        ListTagsForResourceResponse response = sesV2.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(configSetArn).build());
        assertThat(response.tags()).hasSize(1);
        assertThat(response.tags().get(0).key()).isEqualTo("owner");
    }

    @Test
    @Order(5)
    void tagResource_unknownConfigurationSet_throwsNotFound() {
        String missingArn = "arn:aws:ses:us-east-1:000000000000:configuration-set/missing-" + TestFixtures.uniqueName();
        assertThatThrownBy(() -> sesV2.tagResource(TagResourceRequest.builder()
                .resourceArn(missingArn)
                .tags(Tag.builder().key("k").value("v").build())
                .build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(10)
    void emailTemplate_createWithTags_visibleViaListTagsAndGet() {
        sesV2.createEmailTemplate(CreateEmailTemplateRequest.builder()
                .templateName(templateName)
                .templateContent(EmailTemplateContent.builder()
                        .subject("S").text("T").build())
                .tags(
                        Tag.builder().key("env").value("dev").build(),
                        Tag.builder().key("team").value("platform").build())
                .build());

        // ListTagsForResource sees the create-time tags
        ListTagsForResourceResponse listed = sesV2.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(templateArn).build());
        assertThat(listed.tags()).hasSize(2);

        // GetEmailTemplate also surfaces them
        GetEmailTemplateResponse got = sesV2.getEmailTemplate(GetEmailTemplateRequest.builder()
                .templateName(templateName).build());
        assertThat(got.tags())
                .extracting(Tag::key)
                .containsExactlyInAnyOrder("env", "team");
    }

    @Test
    @Order(11)
    void emailTemplate_tagAndUntag_lifecycle() {
        sesV2.tagResource(TagResourceRequest.builder()
                .resourceArn(templateArn)
                .tags(Tag.builder().key("owner").value("alice").build())
                .build());

        ListTagsForResourceResponse afterTag = sesV2.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(templateArn).build());
        assertThat(afterTag.tags()).hasSize(3);

        sesV2.untagResource(UntagResourceRequest.builder()
                .resourceArn(templateArn)
                .tagKeys("env", "team")
                .build());

        ListTagsForResourceResponse afterUntag = sesV2.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(templateArn).build());
        assertThat(afterUntag.tags()).hasSize(1);
        assertThat(afterUntag.tags().get(0).key()).isEqualTo("owner");
    }

    @Test
    @Order(20)
    void emailIdentity_createWithTags_visibleViaListTagsAndGet() {
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder()
                .emailIdentity(identityValue)
                .tags(
                        Tag.builder().key("env").value("dev").build(),
                        Tag.builder().key("team").value("platform").build())
                .build());

        ListTagsForResourceResponse listed = sesV2.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(identityArn).build());
        assertThat(listed.tags()).hasSize(2);

        GetEmailIdentityResponse got = sesV2.getEmailIdentity(GetEmailIdentityRequest.builder()
                .emailIdentity(identityValue).build());
        assertThat(got.tags())
                .extracting(Tag::key)
                .containsExactlyInAnyOrder("env", "team");
    }

    @Test
    @Order(21)
    void emailIdentity_tagAndUntag_lifecycle() {
        sesV2.tagResource(TagResourceRequest.builder()
                .resourceArn(identityArn)
                .tags(Tag.builder().key("owner").value("alice").build())
                .build());

        ListTagsForResourceResponse afterTag = sesV2.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(identityArn).build());
        assertThat(afterTag.tags()).hasSize(3);

        sesV2.untagResource(UntagResourceRequest.builder()
                .resourceArn(identityArn)
                .tagKeys("env", "team")
                .build());

        ListTagsForResourceResponse afterUntag = sesV2.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(identityArn).build());
        assertThat(afterUntag.tags()).hasSize(1);
        assertThat(afterUntag.tags().get(0).key()).isEqualTo("owner");
    }
}
