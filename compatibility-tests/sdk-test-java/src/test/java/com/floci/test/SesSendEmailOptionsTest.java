package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AlreadyExistsException;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.ConfigurationOverrides;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.CreateContactListRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteContactListRequest;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.FeatureStatus;
import software.amazon.awssdk.services.sesv2.model.GetContactRequest;
import software.amazon.awssdk.services.sesv2.model.GetContactResponse;
import software.amazon.awssdk.services.sesv2.model.ListManagementOptions;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SubscriptionStatus;
import software.amazon.awssdk.services.sesv2.model.Topic;
import software.amazon.awssdk.services.sesv2.model.TrackingConfigurationOverrides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the {@code SendEmail} request options, through the AWS Java SDK v2
 * {@link SesV2Client}. A real SDK round-trip proves each nested structure marshals as a real client
 * sends it, and that Floci actually processes it:
 * <ul>
 *   <li>{@code ListManagementOptions} (ContactListName + optional TopicName): an absent recipient is
 *       auto-created as a contact on the list (matches AWS).</li>
 *   <li>{@code ConfigurationOverrides.Tracking}: Floci accepts a valid override (it has no open or
 *       click tracking, so the override has no effect) and refuses a value outside the enum.</li>
 * </ul>
 */
@DisplayName("SES v2 SendEmail request options")
@ResourceLock("ses-account")
class SesSendEmailOptionsTest {

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
        try {
            sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(FROM).build());
        } catch (AlreadyExistsException expected) {
            // Another suite already verified this shared sender, which is all a send needs.
        }
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

    @Test
    void sendWithTrackingOverrides_isAccepted() {
        SendEmailResponse response = sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(FROM)
                .destination(Destination.builder()
                        .toAddresses("tracking-" + TestFixtures.uniqueName() + "@example.com").build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("compat-tracking").build())
                                .body(Body.builder().text(Content.builder().data("hi").build()).build())
                                .build())
                        .build())
                .configurationOverrides(ConfigurationOverrides.builder()
                        .tracking(TrackingConfigurationOverrides.builder()
                                .openTrackingEnabled(FeatureStatus.DISABLED)
                                .clickTrackingEnabled(FeatureStatus.ENABLED)
                                .build())
                        .build())
                .build());

        assertThat(response.messageId()).isNotBlank();
    }

    @Test
    void sendWithAnInvalidTrackingValue_isRefused() {
        assertThatThrownBy(() -> sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(FROM)
                .destination(Destination.builder()
                        .toAddresses("tracking-" + TestFixtures.uniqueName() + "@example.com").build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("compat-tracking").build())
                                .body(Body.builder().text(Content.builder().data("hi").build()).build())
                                .build())
                        .build())
                .configurationOverrides(ConfigurationOverrides.builder()
                        .tracking(TrackingConfigurationOverrides.builder().openTrackingEnabled("yes").build())
                        .build())
                .build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1 validation error detected: Value at "
                        + "'configurationOverrides.tracking.openTrackingEnabled' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [ENABLED, DISABLED]");
    }
}
