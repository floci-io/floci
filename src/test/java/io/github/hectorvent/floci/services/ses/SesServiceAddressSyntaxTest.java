package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import io.github.hectorvent.floci.services.ses.model.EmailContent;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.SendBulkEmailRequest;
import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SesServiceAddressSyntaxTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "example.com";
    private static final String SENDER = "sender@" + DOMAIN;
    private static final String TO = "success@simulator.amazonses.com";
    private static final String NON_ASCII_LOCAL = "やまだ@" + DOMAIN;
    private static final String NO_AT = "sender." + DOMAIN;
    private static final String EMPTY_DOMAIN = "sender@";
    private static final String NON_ASCII_DOMAIN = "sender@例え.jp";
    private static final String MISSING_FINAL_DOMAIN = "Missing final '@domain'";
    private static final String LOCAL_CONTROL = "Local address contains control or whitespace";
    private static final String INVALID_FROM = "Invalid From address provided.";
    private static final EmailContent.Simple SIMPLE =
            new EmailContent.Simple("Subject", "body", null, List.of());

    private SesService service;
    private SesSentEmailService sentEmails;
    private SesIdentityService identityService;

    @BeforeEach
    void setUp() {
        SesServiceTestBuilder builder = SesServiceTestBuilder.create();
        service = builder.build();
        sentEmails = builder.sentEmailService();
        identityService = builder.identityService();
        identityService.verifyEmailIdentity(SENDER, REGION);
    }

    @Test
    void simpleSend_eachEnvelopeFieldIsChecked() {
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(NON_ASCII_LOCAL).content(SIMPLE).build()));
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(SENDER)
                .toAddresses(List.of(TO, NON_ASCII_LOCAL)).content(SIMPLE).build()));
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(SENDER)
                .ccAddresses(List.of(NON_ASCII_LOCAL)).content(SIMPLE).build()));
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(SENDER)
                .bccAddresses(List.of(NON_ASCII_LOCAL)).content(SIMPLE).build()));
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(SENDER)
                .returnPath(NON_ASCII_LOCAL).content(SIMPLE).build()));
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(SENDER)
                .replyToAddresses(List.of(NON_ASCII_LOCAL)).content(SIMPLE).build()));
    }

    @Test
    void simpleSend_sourceIsReportedBeforeRecipients() {
        assertRejects(MISSING_FINAL_DOMAIN, () -> service.sendEmail(request(NO_AT)
                .toAddresses(List.of(NON_ASCII_LOCAL)).content(SIMPLE).build()));
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(NON_ASCII_LOCAL)
                .toAddresses(List.of("success.simulator.amazonses.com")).content(SIMPLE).build()));
    }

    @Test
    void simpleSend_ccIsReportedBeforeBcc() {
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(SENDER)
                .ccAddresses(List.of(NON_ASCII_LOCAL)).bccAddresses(List.of(NO_AT)).content(SIMPLE).build()));
    }

    @Test
    void simpleSend_syntaxIsReportedBeforeUnknownConfigurationSet() {
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(NON_ASCII_LOCAL)
                .configurationSetName("missing").content(SIMPLE).build()));
    }

    @Test
    void simpleSend_syntaxOfAnyFieldIsReportedBeforeLength() {
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(NON_ASCII_LOCAL)
                .toAddresses(List.of(address(321))).content(SIMPLE).build()));
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(address(321))
                .toAddresses(List.of(NON_ASCII_LOCAL)).content(SIMPLE).build()));
    }

    @Test
    void simpleSend_syntaxIsReportedBeforeMissingDestination() {
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(NON_ASCII_LOCAL)
                .toAddresses(List.of()).content(SIMPLE).build()));
    }

    @Test
    void simpleSend_missingSourceIsStillReportedFirst() {
        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(request(null)
                .toAddresses(List.of(NON_ASCII_LOCAL)).content(SIMPLE).build()));

        assertEquals("Source email is required.", e.getMessage());
    }

    @Test
    void simpleSend_nonAsciiDisplayNamesAreAccepted() {
        assertDoesNotThrow(() -> service.sendEmail(request("山田 <" + SENDER + ">")
                .toAddresses(List.of("\"山田\" <" + TO + ">")).content(SIMPLE).build()));
    }

    @Test
    void templatedSend_syntaxIsReportedBeforeMissingTemplate() {
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(NON_ASCII_LOCAL)
                .content(new EmailContent.Template("missing", null, List.of())).build()));
    }

    @Test
    void rawSend_sourceParameterIsChecked() {
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(NON_ASCII_LOCAL)
                .content(raw(SENDER, TO)).build()));
    }

    @Test
    void rawSend_fromHeaderIsChecked() {
        assertRejects(MISSING_FINAL_DOMAIN, () -> service.sendEmail(request(null)
                .content(raw(NO_AT, TO)).build()));
    }

    @Test
    void rawSend_fromHeaderKeepsJavaMailMessagesWithOrWithoutSender() {
        for (String source : Arrays.asList(null, SENDER)) {
            assertRejects("Missing domain", () -> service.sendEmail(request(source)
                    .content(fromHeaderRaw(EMPTY_DOMAIN, false)).build()));
            assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(source)
                    .content(fromHeaderRaw(NON_ASCII_LOCAL, false)).build()));
            assertRejects("Domain contains control or whitespace", () -> service.sendEmail(request(source)
                    .content(fromHeaderRaw(NON_ASCII_DOMAIN, false)).build()));
        }
    }

    @Test
    void v1RawSendWithSender_fromHeaderWithoutAtIsAMissingUserName() {
        assertRejects("User name is missing: 'sender.example.com'.", () -> service.sendEmail(request(SENDER)
                .content(fromHeaderRaw(NO_AT, false)).build()));
        assertRejects("User name is missing: '=?UTF-8?B?5bGx55Sw?= <sender.example.com>'.",
                () -> service.sendEmail(request(SENDER).content(fromHeaderRaw("山田 <" + NO_AT + ">", false)).build()));
    }

    @Test
    void v1RawSendWithSender_addressInTheDisplayNameIsNotSwappedIn() {
        assertRejects("User name is missing: '\"sender@example.com\" <sender.example.com>'.",
                () -> service.sendEmail(request(SENDER).content(fromHeaderRaw(SENDER + " <" + NO_AT + ">", false)).build()));
    }

    @Test
    void v1RawSendWithSender_onlyTheFirstFromHeaderAddressNeedsAnAt() {
        assertRejects("User name is missing: 'sender.example.com'.", () -> service.sendEmail(request(SENDER)
                .content(fromHeaderRaw(NO_AT + ", " + SENDER, false)).build()));
        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(request(SENDER)
                .content(fromHeaderRaw(SENDER + ", " + NO_AT, false)).build()));
        assertEquals("MessageRejected", e.getErrorCode(), "a later address without @ reaches sender verification");
    }

    @Test
    void v1RawSendWithSender_otherFromHeaderViolationsAreReportedFirst() {
        assertRejects("Missing domain", () -> service.sendEmail(request(SENDER)
                .content(fromHeaderRaw(NO_AT + ", " + EMPTY_DOMAIN, false)).build()));
    }

    @Test
    void v1RawSendWithSender_missingUserNameIsReportedAfterRecipientSyntax() {
        assertRejects(MISSING_FINAL_DOMAIN, () -> service.sendEmail(request(SENDER).toAddresses(List.of())
                .content(fromHeaderRaw(NO_AT, false, "success.simulator.amazonses.com")).build()));
    }

    @Test
    void v1RawSendWithSender_missingUserNameIsReportedAfterUnknownConfigurationSet() {
        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(request(SENDER)
                .configurationSetName("missing").content(fromHeaderRaw(NO_AT, false)).build()));

        assertEquals("ConfigurationSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void v1RawSendWithSender_missingUserNameIsReportedAfterMissingDestination() {
        assertRejects("Missing required header 'To'.", () -> service.sendEmail(request(SENDER).toAddresses(List.of())
                .content(rawWithoutTo(NO_AT, false)).build()));
    }

    @Test
    void v1RawSendWithSender_missingUserNameIsReportedBeforeLength() {
        assertRejects("User name is missing: 'sender.example.com'.", () -> service.sendEmail(request(SENDER)
                .toAddresses(List.of(address(321))).content(fromHeaderRaw(NO_AT, false)).build()));
    }

    @Test
    void v2RawSend_fromHeaderWithoutAtKeepsJavaMailMessage() {
        assertRejects(MISSING_FINAL_DOMAIN, () -> service.sendEmail(request(null)
                .content(fromHeaderRaw(NO_AT, true)).build()));
    }

    @Test
    void v2RawSend_otherFromHeaderViolationsAreAnInvalidFromAddress() {
        for (String from : List.of(EMPTY_DOMAIN, NON_ASCII_LOCAL, NON_ASCII_DOMAIN)) {
            assertRejects(INVALID_FROM, () -> service.sendEmail(request(null)
                    .content(fromHeaderRaw(from, true)).build()));
        }
    }

    @Test
    void v2RawSend_fromHeaderIsNotCheckedWhenSenderIsGiven() {
        for (String from : List.of(NO_AT, EMPTY_DOMAIN, NON_ASCII_LOCAL, NON_ASCII_DOMAIN)) {
            assertDoesNotThrow(() -> service.sendEmail(request(SENDER)
                    .content(fromHeaderRaw(from, true)).build()));
        }
    }

    @Test
    void v2RawSend_malformedSenderKeepsJavaMailMessage() {
        assertRejects("Missing domain", () -> service.sendEmail(request(EMPTY_DOMAIN)
                .content(fromHeaderRaw(SENDER, true)).build()));
    }

    @Test
    void v2RawSend_fromHeaderWithoutAtIsReportedAfterMissingDestination() {
        assertRejects("Missing required header 'To'.", () -> service.sendEmail(request(null).toAddresses(List.of())
                .content(v2RawWithoutTo(NO_AT)).build()));
    }

    @Test
    void v2RawSend_staleDefaultConfigurationSetOfFromHeaderIsReportedBeforeMissingDestination() {
        Identity identity = new Identity(DOMAIN, "Domain");
        identity.setConfigurationSetName("deleted");
        identityService.save(identity, REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(request(null)
                .toAddresses(List.of()).content(v2RawWithoutTo(SENDER)).build()));

        assertEquals("Configuration set <deleted> does not exist.", e.getMessage());
    }

    @Test
    void v2RawSend_otherFromHeaderViolationsAreReportedBeforeMissingDestination() {
        assertRejects(INVALID_FROM, () -> service.sendEmail(request(null).toAddresses(List.of())
                .content(v2RawWithoutTo(EMPTY_DOMAIN)).build()));
    }

    @Test
    void v2RawSend_fromHeaderWithoutAtIsReportedAfterRecipientSyntax() {
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(null).toAddresses(List.of(NON_ASCII_LOCAL))
                .content(fromHeaderRaw(NO_AT, true)).build()));
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(null).toAddresses(List.of())
                .content(fromHeaderRaw(NO_AT, true, NON_ASCII_LOCAL)).build()));
    }

    @Test
    void v2RawSend_fromHeaderWithoutAtIsReportedAfterUnknownConfigurationSet() {
        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(request(null)
                .configurationSetName("missing").content(fromHeaderRaw(NO_AT, true)).build()));

        assertEquals("ConfigurationSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void v2RawSend_fromHeaderWithoutAtIsReportedBeforeLength() {
        assertRejects(MISSING_FINAL_DOMAIN, () -> service.sendEmail(request(null).toAddresses(List.of(address(321)))
                .content(fromHeaderRaw(NO_AT, true)).build()));
    }

    @Test
    void rawSend_destinationsAreChecked() {
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(SENDER)
                .toAddresses(List.of(NON_ASCII_LOCAL)).content(raw(SENDER, TO)).build()));
    }

    @Test
    void rawSend_toHeaderIsChecked() {
        assertRejects(MISSING_FINAL_DOMAIN, () -> service.sendEmail(request(null).toAddresses(List.of())
                .content(raw(SENDER, TO + ", " + NO_AT)).build()));
    }

    @Test
    void rawSend_domainLiteralInToHeaderIsAccepted() {
        assertDoesNotThrow(() -> service.sendEmail(request(SENDER).content(raw(SENDER,
                TO + ", user@[IPv6:2001:db8::1]")).build()));
    }

    @Test
    void rawSend_ccAndBccHeadersAreChecked() {
        assertRejects(LOCAL_CONTROL, () -> service.sendEmail(request(SENDER)
                .content(raw(SENDER, TO, "Cc: " + NON_ASCII_LOCAL + "\r\n")).build()));
        assertRejects(MISSING_FINAL_DOMAIN, () -> service.sendEmail(request(SENDER)
                .content(raw(SENDER, TO, "Bcc: " + NO_AT + "\r\n")).build()));
    }

    @Test
    void rawSend_unprobedAddressHeadersAreNotChecked() {
        assertDoesNotThrow(() -> service.sendEmail(request(SENDER).content(raw(SENDER, TO,
                "Reply-To: " + NO_AT + "\r\nReturn-Path: <>\r\n")).build()));
    }

    @Test
    void rawSend_nonAsciiFromHeaderNameIsAccepted() {
        String raw = Base64.getEncoder().encodeToString(("From: 山田 <" + SENDER + ">\r\nTo: " + TO
                + "\r\nSubject: s\r\n\r\nbody").getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> service.sendEmail(request(null).content(new EmailContent.Raw(raw)).build()));
    }

    @Test
    void bulkSend_malformedEntryRecipientFailsOnlyThatEntry() {
        List<BulkEmailEntryResult> results = service.sendBulkEmail(SendBulkEmailRequest.builder()
                .source(SENDER)
                .defaultContent(new EmailContent.InlineTemplate("Subject", "body", null, null, List.of()))
                .entries(List.of(
                        new BulkEmailEntry(List.of(TO), null, null, null, null, null),
                        new BulkEmailEntry(List.of(TO), List.of(NON_ASCII_LOCAL), null, null, null, null),
                        new BulkEmailEntry(List.of(TO), null, List.of(NO_AT), null, null, null)))
                .region(REGION)
                .build());

        assertEquals(BulkEmailEntryResult.Status.SUCCESS, results.get(0).getStatus());
        assertEquals(BulkEmailEntryResult.Status.INVALID_PARAMETER, results.get(1).getStatus());
        assertEquals(LOCAL_CONTROL, results.get(1).getError());
        assertEquals(BulkEmailEntryResult.Status.INVALID_PARAMETER, results.get(2).getStatus());
        assertEquals(MISSING_FINAL_DOMAIN, results.get(2).getError());
        assertEquals(1, sentEmails.countInRegion(REGION), "only the successful entry is recorded");
    }

    @Test
    void bulkSend_malformedReplyToFailsEveryEntry() {
        List<BulkEmailEntryResult> results = service.sendBulkEmail(SendBulkEmailRequest.builder()
                .source(SENDER)
                .replyToAddresses(List.of(NO_AT))
                .defaultContent(new EmailContent.InlineTemplate("Subject", "body", null, null, List.of()))
                .entries(List.of(
                        new BulkEmailEntry(List.of(TO), null, null, null, null, null),
                        new BulkEmailEntry(List.of(TO), null, null, null, null, null)))
                .region(REGION)
                .build());

        for (BulkEmailEntryResult result : results) {
            assertEquals(BulkEmailEntryResult.Status.INVALID_PARAMETER, result.getStatus());
            assertEquals(MISSING_FINAL_DOMAIN, result.getError());
        }
        assertEquals(0, sentEmails.countInRegion(REGION), "no entry is recorded");
    }

    private void assertRejects(String message, Executable send) {
        long recorded = sentEmails.countInRegion(REGION);
        AwsException e = assertThrows(AwsException.class, send);
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals(message, e.getMessage());
        assertEquals(recorded, sentEmails.countInRegion(REGION), "a rejected send must not be recorded");
    }

    private static SendEmailRequest.Builder request(String source) {
        return SendEmailRequest.builder()
                .source(source)
                .toAddresses(List.of(TO))
                .region(REGION);
    }

    private static EmailContent.Raw raw(String from, String to) {
        return raw(from, to, "");
    }

    private static EmailContent.Raw fromHeaderRaw(String from, boolean fromHeaderAsFallback) {
        return fromHeaderRaw(from, fromHeaderAsFallback, TO);
    }

    private static EmailContent.Raw fromHeaderRaw(String from, boolean fromHeaderAsFallback, String to) {
        return new EmailContent.Raw(Base64.getEncoder().encodeToString(("From: " + from + "\r\nTo: " + to
                + "\r\nSubject: s\r\n\r\nbody").getBytes(StandardCharsets.UTF_8)), fromHeaderAsFallback);
    }

    private static EmailContent.Raw v2RawWithoutTo(String from) {
        return rawWithoutTo(from, true);
    }

    private static EmailContent.Raw rawWithoutTo(String from, boolean fromHeaderAsFallback) {
        return new EmailContent.Raw(Base64.getEncoder().encodeToString(("From: " + from
                + "\r\nSubject: s\r\n\r\nbody").getBytes(StandardCharsets.UTF_8)), fromHeaderAsFallback);
    }

    private static EmailContent.Raw raw(String from, String to, String extraHeaders) {
        return new EmailContent.Raw("From: " + from + "\r\nTo: " + to + "\r\n" + extraHeaders
                + "Subject: s\r\n\r\nbody");
    }

    private static String address(int length) {
        return "a".repeat(length - DOMAIN.length() - 1) + "@" + DOMAIN;
    }
}
