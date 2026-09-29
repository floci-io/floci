package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.CustomVerificationEmailTemplate;
import io.github.hectorvent.floci.services.ses.model.EmailContent;
import io.github.hectorvent.floci.services.ses.model.InsightsEvent;
import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SesServiceSmtpTest {

    @Mock SmtpRelay smtpRelay;

    private SesService service;
    private SesConfigurationSetService configSets;
    private SesSentEmailService sentEmails;
    private SesSuppressionService suppression;
    private SesCvetService cvetTemplates;
    private SesIdentityService identities;
    private InMemoryStorage<String, SentEmail> emailStore;

    @BeforeEach
    void setUp() {
        SesServiceTestBuilder builder = SesServiceTestBuilder.create().smtpRelay(smtpRelay);
        emailStore = builder.emailStore();
        service = builder.build();
        configSets = builder.configSetService();
        sentEmails = builder.sentEmailService();
        suppression = builder.suppressionService();
        cvetTemplates = builder.cvetService();
        identities = builder.identityService();
    }

    private void cvetTemplate(String name, String subject, String content) {
        identities.verifyEmailIdentity("verifier@example.com", "us-east-1");
        CustomVerificationEmailTemplate template = new CustomVerificationEmailTemplate();
        template.setTemplateName(name);
        template.setFromEmailAddress("verifier@example.com");
        template.setTemplateSubject(subject);
        template.setTemplateContent(content);
        template.setSuccessRedirectionURL("https://example.com/ok");
        template.setFailureRedirectionURL("https://example.com/ng");
        cvetTemplates.createCustomVerificationEmailTemplate(template, "us-east-1");
    }

    @Test
    void sendCustomVerificationEmail_signatureInTheTemplateSubject_isRejectedAndNotRelayed() {
        // The scan covers the subject as well as the content, and neither reaches the relay.
        cvetTemplate("cvet-subject", SesContentScan.signature(), "<p>clean</p>");

        String messageId = service.sendCustomVerificationEmail("target@example.com", "cvet-subject",
                null, "us-east-1");

        assertEquals("Bad content", storedEmail(messageId).getRejectReason());
        assertEquals(List.of("SEND", "REJECT"), insightsEventTypes(messageId));
        verify(smtpRelay, never()).relay(any());
    }

    @Test
    void sendCustomVerificationEmail_signatureInTheTemplateContent_isRejectedAndNotRelayed() {
        cvetTemplate("cvet-content", "clean", "<p>" + SesContentScan.signature() + "</p>");

        String messageId = service.sendCustomVerificationEmail("target@example.com", "cvet-content",
                null, "us-east-1");

        assertEquals("Bad content", storedEmail(messageId).getRejectReason());
        verify(smtpRelay, never()).relay(any());
    }

    @Test
    void sendCustomVerificationEmail_suppressedRecipient_isRecordedButNotRelayed() {
        cvetTemplate("cvet-suppressed", "clean", "<p>clean</p>");
        suppression.putSuppressedDestination("us-east-1", "blocked@example.com", "BOUNCE");

        String messageId = service.sendCustomVerificationEmail("blocked@example.com",
                "cvet-suppressed", null, "us-east-1");

        assertNull(storedEmail(messageId).getRejectReason());
        assertEquals(List.of("SEND", "BOUNCE"), insightsEventTypes(messageId));
        verify(smtpRelay, never()).relay(any());
    }

    private SentEmail storedEmail(String messageId) {
        return sentEmails.listAll().stream()
                .filter(e -> messageId.equals(e.getMessageId()))
                .findFirst()
                .orElseThrow();
    }

    private List<String> insightsEventTypes(String messageId) {
        return storedEmail(messageId).getInsights().get(0).events().stream()
                .map(InsightsEvent::type)
                .toList();
    }

    private SmtpRelay.RelayMessage capturedRelay() {
        ArgumentCaptor<SmtpRelay.RelayMessage> captor =
                ArgumentCaptor.forClass(SmtpRelay.RelayMessage.class);
        verify(smtpRelay).relay(captor.capture());
        return captor.getValue();
    }

    private SmtpRelay.RawRelayMessage capturedRawRelay() {
        ArgumentCaptor<SmtpRelay.RawRelayMessage> captor =
                ArgumentCaptor.forClass(SmtpRelay.RawRelayMessage.class);
        verify(smtpRelay).relayRaw(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendEmail_callsRelayWithAllFields() {
        String messageId = service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .ccAddresses(List.of("cc@example.com"))
                .bccAddresses(List.of("bcc@example.com"))
                .replyToAddresses(List.of("reply@example.com"))
                .region("us-east-1")
                .content(new EmailContent.Simple("Subject", "text body", "<p>html</p>", List.of()))
                .build());

        SmtpRelay.RelayMessage relayed = capturedRelay();
        assertEquals("from@example.com", relayed.from());
        assertEquals(List.of("to@example.com"), relayed.to());
        assertEquals(List.of("cc@example.com"), relayed.cc());
        assertEquals(List.of("bcc@example.com"), relayed.bcc());
        assertEquals(List.of("reply@example.com"), relayed.replyTo());
        assertEquals("Subject", relayed.subject());
        assertEquals("text body", relayed.bodyText());
        assertEquals("<p>html</p>", relayed.bodyHtml());
        assertEquals(List.of(), relayed.headers());
        assertEquals(messageId, relayed.messageId());
    }

    @Test
    void sendEmail_storesAndRelays() {
        String messageId = service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .region("us-east-1")
                .content(new EmailContent.Simple("Subject", "text", null, List.of()))
                .build());

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay).relay(any(SmtpRelay.RelayMessage.class));
    }

    @Test
    void sendEmail_noReturnPath_fallsBackToSource() {
        service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .region("us-east-1")
                .content(new EmailContent.Simple("Subject", "text", null, List.of()))
                .build());

        assertEquals("from@example.com", capturedRelay().returnPath());
    }

    @Test
    void sendEmail_explicitReturnPath_isRelayedAndStored() {
        String messageId = service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .returnPath("bounces@example.com")
                .region("us-east-1")
                .content(new EmailContent.Simple("Subject", "text", null, List.of()))
                .build());

        assertEquals("bounces@example.com", capturedRelay().returnPath());
        assertEquals("bounces@example.com", storedEmail(messageId).getReturnPath());
    }

    @Test
    void sendRawEmail_rejectedMessage_keepsNoReturnPathFromItsHeaders() {
        // The scan trips on the subject; the Return-Path header, read off the same message, must
        // not survive on the record either, so the request's return path is used.
        String raw = "From: from@example.com\r\nTo: to@example.com\r\nReturn-Path: <bounces@evil.example>\r\n"
                + "Subject: " + SesContentScan.signature() + "\r\n\r\nbody\r\n";

        String messageId = service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .returnPath("bounces@example.com")
                .region("us-east-1")
                .content(new EmailContent.Raw(raw))
                .build());

        assertEquals("bounces@example.com", storedEmail(messageId).getReturnPath());
        assertEquals("Bad content", storedEmail(messageId).getRejectReason());
        verify(smtpRelay, never()).relayRaw(any());
    }

    @Test
    void sendRawEmail_unreadableMessage_isRefusedNotStoredNotRelayed() {
        // Nested past what mime4j can parse: the content cannot be scanned, so the send is refused
        // rather than recorded and relayed unseen.
        String raw = "Content-Type: message/rfc822\r\n\r\n".repeat(100_000) + "Subject: deep\r\n\r\nx\r\n";

        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .region("us-east-1")
                .content(new EmailContent.Raw(raw))
                .build()));

        assertEquals(400, e.getHttpStatus());
        assertTrue(sentEmails.listAll().isEmpty());
        verify(smtpRelay, never()).relayRaw(any());
    }

    @Test
    void sendRawEmail_callsRelayRaw() {
        String messageId = service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .region("us-east-1")
                .content(new EmailContent.Raw("raw MIME"))
                .build());

        SmtpRelay.RawRelayMessage relayed = capturedRawRelay();
        assertEquals("from@example.com", relayed.from());
        assertEquals(List.of("to@example.com"), relayed.destinations());
        assertEquals("raw MIME", relayed.rawMessage());
        assertEquals(messageId, relayed.messageId());
    }

    @Test
    void sendRawEmail_returnPathHeader_winsOverRequestField() {
        String raw = "From: from@example.com\r\n"
                + "To: to@example.com\r\n"
                + "Return-Path: <mime-bounces@example.com>\r\n"
                + "Subject: x\r\n\r\nbody";

        String messageId = service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .returnPath("request-bounces@example.com")
                .region("us-east-1")
                .content(new EmailContent.Raw(raw))
                .build());

        assertEquals("mime-bounces@example.com", capturedRawRelay().returnPath());
        assertEquals("mime-bounces@example.com", storedEmail(messageId).getReturnPath());
    }

    @Test
    void sendRawEmail_noReturnPathHeader_usesRequestField() {
        String raw = "From: from@example.com\r\nTo: to@example.com\r\nSubject: x\r\n\r\nbody";

        service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .returnPath("request-bounces@example.com")
                .region("us-east-1")
                .content(new EmailContent.Raw(raw))
                .build());

        assertEquals("request-bounces@example.com", capturedRawRelay().returnPath());
    }

    @Test
    void sendRawEmail_storesAndRelays() {
        String messageId = service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .region("us-east-1")
                .content(new EmailContent.Raw("raw"))
                .build());

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay).relayRaw(any(SmtpRelay.RawRelayMessage.class));
    }

    // Template errors (a missing stored template, empty inline content) are reported before the
    // envelope checks, so envelope validation must not move ahead of the content switch.
    @Test
    void sendEmail_unknownTemplate_reportedBeforeMissingSource() {
        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(SendEmailRequest.builder()
                .region("us-east-1")
                .content(new EmailContent.Template("ghost", null, List.of()))
                .build()));

        assertEquals("TemplateDoesNotExist", e.getErrorCode());
    }

    @Test
    void sendEmail_emptyInlineTemplate_reportedBeforeMissingSource() {
        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(SendEmailRequest.builder()
                .region("us-east-1")
                .content(new EmailContent.InlineTemplate(null, null, null, null, List.of()))
                .build()));

        assertEquals("InvalidTemplate", e.getErrorCode());
    }

    @Test
    void sendEmail_relayReceivesCorrectFieldsWithNulls() {
        service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .ccAddresses(null)
                .bccAddresses(null)
                .replyToAddresses(null)
                .region("us-east-1")
                .content(new EmailContent.Simple("Subject", null, "<p>html only</p>", List.of()))
                .build());

        SmtpRelay.RelayMessage relayed = capturedRelay();
        assertEquals(List.of("to@example.com"), relayed.to());
        assertNull(relayed.cc());
        assertNull(relayed.bcc());
        assertNull(relayed.replyTo());
        assertNull(relayed.bodyText());
        assertEquals("<p>html only</p>", relayed.bodyHtml());
    }

    @Test
    void sendEmail_allRecipientsSuppressed_skipsRelayButStillStores() {
        suppression.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        suppression.putSuppressedDestination("us-east-1", "cc@example.com", "COMPLAINT");

        String messageId = service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .ccAddresses(List.of("cc@example.com"))
                .region("us-east-1")
                .content(new EmailContent.Simple("Subject", "text body", null, List.of()))
                .build());

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty(),
                "stored SentEmail should still record the original recipient list");
        verify(smtpRelay, never()).relay(any(SmtpRelay.RelayMessage.class));
    }

    @Test
    void sendEmail_partialSuppression_relayCalledWithFilteredRecipients() {
        // Only suppress one of the To recipients; the other should still reach the relay.
        suppression.putSuppressedDestination("us-east-1", "suppressed@example.com", "BOUNCE");

        service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com", "suppressed@example.com"))
                .ccAddresses(List.of("cc-keep@example.com"))
                .region("us-east-1")
                .content(new EmailContent.Simple("Subject", "text body", null, List.of()))
                .build());

        SmtpRelay.RelayMessage relayed = capturedRelay();
        assertEquals(List.of("to@example.com"), relayed.to());
        assertEquals(List.of("cc-keep@example.com"), relayed.cc());
    }

    @Test
    void sendRawEmail_allRecipientsSuppressed_skipsRelayRawButStillStores() {
        suppression.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");

        String messageId = service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .region("us-east-1")
                .content(new EmailContent.Raw("raw MIME"))
                .build());

        assertNotNull(messageId);
        assertFalse(emailStore.scan(k -> true).isEmpty());
        verify(smtpRelay, never()).relayRaw(any(SmtpRelay.RawRelayMessage.class));
    }

    @Test
    void sendRawEmail_partialSuppression_relayRawCalledWithFilteredRecipients() {
        suppression.putSuppressedDestination("us-east-1", "suppressed@example.com", "COMPLAINT");

        service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com", "suppressed@example.com"))
                .region("us-east-1")
                .content(new EmailContent.Raw("raw MIME"))
                .build());

        assertEquals(List.of("to@example.com"), capturedRawRelay().destinations());
    }

    // ─────────── Per-CS SuppressionOptions override at send time ───────────

    @Test
    void sendEmail_csOverridesAccountToEmptyList_suppressionListIsIgnored() {
        // Account defaults to [BOUNCE, COMPLAINT] suppression. The CS explicitly
        // overrides to an empty list, which is the AWS V2 contract for "disable
        // suppression filtering for this configuration set". A recipient on the
        // suppression list with reason=BOUNCE should still reach the SMTP relay.
        suppression.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        service.createConfigurationSet(new ConfigurationSet("cs-no-suppression"), "us-east-1");
        configSets.putSuppressionOptions("cs-no-suppression", List.of(), "us-east-1");

        service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .configurationSetName("cs-no-suppression")
                .region("us-east-1")
                .content(new EmailContent.Simple("Subject", "text body", null, List.of()))
                .build());

        assertEquals(List.of("to@example.com"), capturedRelay().to());
    }

    @Test
    void sendEmail_csOverridesAccountToBounceOnly_complaintSuppressedAddressStillRelayed() {
        // Account-level reasons include both BOUNCE and COMPLAINT by default.
        // The CS narrows the effective reasons to [BOUNCE] only — so a recipient
        // suppressed for COMPLAINT is NOT filtered when sending through this CS.
        suppression.putSuppressedDestination("us-east-1", "complainer@example.com", "COMPLAINT");
        service.createConfigurationSet(new ConfigurationSet("cs-bounce-only"), "us-east-1");
        configSets.putSuppressionOptions("cs-bounce-only", List.of("BOUNCE"), "us-east-1");

        service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("complainer@example.com"))
                .configurationSetName("cs-bounce-only")
                .region("us-east-1")
                .content(new EmailContent.Simple("Subject", "text body", null, List.of()))
                .build());

        assertEquals(List.of("complainer@example.com"), capturedRelay().to());
    }

    @Test
    void sendEmail_csWithoutOverride_fallsBackToAccountLevelSuppression() {
        // A configuration set whose SuppressionOptions block was never PUT must
        // fall back to account-level reasons — same filtering behaviour as a
        // send without any configuration set.
        suppression.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        service.createConfigurationSet(new ConfigurationSet("cs-default"), "us-east-1");

        service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .configurationSetName("cs-default")
                .region("us-east-1")
                .content(new EmailContent.Simple("Subject", "text body", null, List.of()))
                .build());

        verify(smtpRelay, never()).relay(any(SmtpRelay.RelayMessage.class));
    }

    @Test
    void sendRawEmail_csOverridesAccountToEmptyList_suppressionListIsIgnored() {
        suppression.putSuppressedDestination("us-east-1", "to@example.com", "BOUNCE");
        service.createConfigurationSet(new ConfigurationSet("cs-no-suppression-raw"), "us-east-1");
        configSets.putSuppressionOptions("cs-no-suppression-raw", List.of(), "us-east-1");

        service.sendEmail(SendEmailRequest.builder()
                .source("from@example.com")
                .toAddresses(List.of("to@example.com"))
                .configurationSetName("cs-no-suppression-raw")
                .region("us-east-1")
                .content(new EmailContent.Raw("raw MIME"))
                .build());

        assertEquals(List.of("to@example.com"), capturedRawRelay().destinations());
    }
}
