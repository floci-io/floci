package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.EmailInsights;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.GetMessageInsightsRequest;
import software.amazon.awssdk.services.sesv2.model.GetMessageInsightsResponse;
import software.amazon.awssdk.services.sesv2.model.InsightsEvent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageTag;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.PutAccountVdmAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.VdmAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for SES v2 GetMessageInsights: the Virtual Deliverability Manager gate,
 * the not-found shape, and the per-recipient event timeline a send produces, against a live Floci
 * instance. VDM is account and region scoped, so the original attributes are captured and restored.
 */
@DisplayName("SES v2 Message Insights")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesMessageInsightsTest {

    private static final String SENDER = "compat-insights@example.com";

    private static SesV2Client sesV2;
    private static VdmAttributes originalVdm;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        GetAccountResponse account = sesV2.getAccount(GetAccountRequest.builder().build());
        originalVdm = account.vdmAttributes();
        // An interrupted earlier run can leave the identity behind; creating it again would fail
        // with AlreadyExistsException before any test ran.
        quietly(() -> sesV2.deleteEmailIdentity(
                DeleteEmailIdentityRequest.builder().emailIdentity(SENDER).build()));
        setVdmEnabled(false);
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(SENDER).build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 == null) {
            return;
        }
        quietly(() -> sesV2.deleteEmailIdentity(
                DeleteEmailIdentityRequest.builder().emailIdentity(SENDER).build()));
        quietly(SesMessageInsightsTest::restoreVdm);
        sesV2.close();
    }

    @Test
    @Order(1)
    @DisplayName("reports the VDM gate before it looks at the message id")
    void vdmDisabledIsReportedFirst() {
        assertThatThrownBy(() -> sesV2.getMessageInsights(
                GetMessageInsightsRequest.builder().messageId("not-even-an-id").build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Virtual Deliverability Manager");
    }

    @Test
    @Order(2)
    @DisplayName("an unknown message names the account alongside the id")
    void unknownMessageNamesTheAccount() {
        setVdmEnabled(true);

        assertThatThrownBy(() -> sesV2.getMessageInsights(
                GetMessageInsightsRequest.builder().messageId("no-such-message").build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Message <no-such-message> not found for account <");
    }

    @Test
    @Order(3)
    @DisplayName("an ordinary recipient reports SEND then DELIVERY with the send's tags")
    void ordinaryRecipientTimeline() {
        String messageId = send("user@example.com", "compat-ordinary");

        GetMessageInsightsResponse insights = sesV2.getMessageInsights(
                GetMessageInsightsRequest.builder().messageId(messageId).build());

        assertThat(insights.messageId()).isEqualTo(messageId);
        assertThat(insights.fromEmailAddress()).isEqualTo(SENDER);
        assertThat(insights.subject()).isEqualTo("compat-ordinary");
        assertThat(insights.emailTags()).singleElement()
                .satisfies(tag -> {
                    assertThat(tag.name()).isEqualTo("campaign");
                    assertThat(tag.value()).isEqualTo("compat");
                });
        assertThat(insights.insights()).singleElement().satisfies(recipient -> {
            assertThat(recipient.destination()).isEqualTo("user@example.com");
            assertThat(recipient.isp()).isEqualTo("UNKNOWN_ISP");
            assertThat(recipient.events()).extracting(event -> event.type().toString())
                    .containsExactly("SEND", "DELIVERY");
            assertThat(recipient.events()).allSatisfy(
                    event -> assertThat(event.timestamp()).isNotNull());
        });
    }

    @Test
    @Order(4)
    @DisplayName("the bounce simulator carries the bounce details")
    void bounceSimulatorTimeline() {
        String messageId = send("bounce@simulator.amazonses.com", "compat-bounce");

        GetMessageInsightsResponse insights = sesV2.getMessageInsights(
                GetMessageInsightsRequest.builder().messageId(messageId).build());

        List<InsightsEvent> events = insights.insights().get(0).events();
        assertThat(events).extracting(event -> event.type().toString())
                .containsExactly("SEND", "BOUNCE");
        assertThat(events.get(1).details().bounce().bounceTypeAsString()).isEqualTo("PERMANENT");
        assertThat(events.get(1).details().bounce().diagnosticCode()).contains("550 5.1.1");
    }

    @Test
    @Order(5)
    @DisplayName("Floci reports one timeline per recipient where AWS records nothing at all")
    void multipleRecipientsGetOneTimelineEach() {
        String messageId = sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(SENDER)
                .destination(Destination.builder()
                        .toAddresses("to@example.com")
                        .ccAddresses("cc@example.com")
                        .build())
                .content(content("compat-multi"))
                .build()).messageId();

        GetMessageInsightsResponse insights = sesV2.getMessageInsights(
                GetMessageInsightsRequest.builder().messageId(messageId).build());

        assertThat(insights.insights()).extracting(EmailInsights::destination)
                .containsExactly("to@example.com", "cc@example.com");
    }

    private static String send(String recipient, String subject) {
        return sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(SENDER)
                .destination(Destination.builder().toAddresses(recipient).build())
                .emailTags(MessageTag.builder().name("campaign").value("compat").build())
                .content(content(subject))
                .build()).messageId();
    }

    private static EmailContent content(String subject) {
        return EmailContent.builder()
                .simple(Message.builder()
                        .subject(Content.builder().data(subject).build())
                        .body(Body.builder().text(Content.builder().data("hi").build()).build())
                        .build())
                .build();
    }

    private static void setVdmEnabled(boolean enabled) {
        sesV2.putAccountVdmAttributes(PutAccountVdmAttributesRequest.builder()
                .vdmAttributes(VdmAttributes.builder()
                        .vdmEnabled(enabled ? "ENABLED" : "DISABLED")
                        .build())
                .build());
    }

    // VDM has no un-configure API, so a region that started unconfigured is left DISABLED rather
    // than restored to absent. Same compromise as SesAccountAttributesTest.
    private static void restoreVdm() {
        VdmAttributes.Builder vdm = VdmAttributes.builder();
        if (originalVdm != null && originalVdm.vdmEnabled() != null) {
            vdm.vdmEnabled(originalVdm.vdmEnabled())
                    .dashboardAttributes(originalVdm.dashboardAttributes())
                    .guardianAttributes(originalVdm.guardianAttributes());
        } else {
            vdm.vdmEnabled("DISABLED");
        }
        sesV2.putAccountVdmAttributes(
                PutAccountVdmAttributesRequest.builder().vdmAttributes(vdm.build()).build());
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            // Cleanup is best effort: a failed teardown must not mask the test result.
        }
    }
}
