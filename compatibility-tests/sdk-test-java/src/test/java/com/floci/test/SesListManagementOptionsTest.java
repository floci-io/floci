package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.CreateContactListRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteContactListRequest;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.GetContactRequest;
import software.amazon.awssdk.services.sesv2.model.GetContactResponse;
import software.amazon.awssdk.services.sesv2.model.ListManagementOptions;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SubscriptionStatus;
import software.amazon.awssdk.services.sesv2.model.Topic;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDK compatibility test for {@code SendEmail} with {@code ListManagementOptions} through the AWS
 * Java SDK v2 {@link SesV2Client}. A real SDK round-trip proves the nested {@code ListManagementOptions}
 * structure (ContactListName + optional TopicName) marshals as a real client sends it — and that Floci
 * actually processes it, since an absent recipient is auto-created as a contact on the list (matches AWS).
 */
@DisplayName("SES v2 SendEmail ListManagementOptions")
class SesListManagementOptionsTest {

    private static final String LIST = "compat-lmo-list";
    private static final String TOPIC = "weekly";
    private static final String FROM = "sender@example.com";

    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        // Only one contact list may exist per account; clear any leftover, then create ours.
        sesV2.listContactLists(r -> { }).contactLists().forEach(cl ->
                sesV2.deleteContactList(DeleteContactListRequest.builder()
                        .contactListName(cl.contactListName()).build()));
        sesV2.createContactList(CreateContactListRequest.builder()
                .contactListName(LIST)
                .topics(Topic.builder()
                        .topicName(TOPIC)
                        .displayName("Weekly")
                        .defaultSubscriptionStatus(SubscriptionStatus.OPT_IN)
                        .description("weekly digest")
                        .build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                sesV2.deleteContactList(DeleteContactListRequest.builder().contactListName(LIST).build());
            } catch (RuntimeException ignored) {
                // best-effort
            }
            sesV2.close();
        }
    }

    @Test
    void sendWithListManagementOptions_roundTripsAndAutoCreatesContact() {
        String recipient = "lmo-compat-" + TestFixtures.uniqueName() + "@example.com";

        SendEmailResponse response = sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(FROM)
                .destination(Destination.builder().toAddresses(recipient).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("compat-lmo").build())
                                .body(Body.builder().text(Content.builder().data("hi").build()).build())
                                .build())
                        .build())
                .listManagementOptions(ListManagementOptions.builder()
                        .contactListName(LIST)
                        .topicName(TOPIC)
                        .build())
                .build());

        assertThat(response.messageId()).isNotBlank();

        // The nested ListManagementOptions was actually applied: an absent recipient is auto-created
        // as a contact on the named list.
        GetContactResponse contact = sesV2.getContact(GetContactRequest.builder()
                .contactListName(LIST).emailAddress(recipient).build());
        assertThat(contact.emailAddress()).isEqualTo(recipient);
    }

    @Test
    void sendWithListManagementOptions_topicNameOptional() {
        String recipient = "lmo-compat-notopic-" + TestFixtures.uniqueName() + "@example.com";

        SendEmailResponse response = sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(FROM)
                .destination(Destination.builder().toAddresses(recipient).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("compat-lmo-notopic").build())
                                .body(Body.builder().text(Content.builder().data("hi").build()).build())
                                .build())
                        .build())
                .listManagementOptions(ListManagementOptions.builder()
                        .contactListName(LIST)
                        .build())
                .build());

        assertThat(response.messageId()).isNotBlank();
    }
}
