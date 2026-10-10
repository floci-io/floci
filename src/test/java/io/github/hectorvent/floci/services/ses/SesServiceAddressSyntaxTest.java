package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import io.github.hectorvent.floci.services.ses.model.EmailContent;
import io.github.hectorvent.floci.services.ses.model.SendBulkEmailRequest;
import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
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
    private static final String MISSING_FINAL_DOMAIN = "Missing final '@domain'";
    private static final String LOCAL_CONTROL = "Local address contains control or whitespace";
    private static final EmailContent.Simple SIMPLE =
            new EmailContent.Simple("Subject", "body", null, List.of());

    private SesService service;
    private SesSentEmailService sentEmails;

    @BeforeEach
    void setUp() {
        SesServiceTestBuilder builder = SesServiceTestBuilder.create();
        service = builder.build();
        sentEmails = builder.sentEmailService();
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
    void rawSend_unprobedAddressHeadersAreNotChecked() {
        assertDoesNotThrow(() -> service.sendEmail(request(SENDER).content(raw(SENDER, TO,
                "Cc: " + NO_AT + "\r\nBcc: " + NON_ASCII_LOCAL + "\r\nReply-To: " + NO_AT
                        + "\r\nReturn-Path: <>\r\n")).build()));
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

    private static EmailContent.Raw raw(String from, String to, String extraHeaders) {
        return new EmailContent.Raw("From: " + from + "\r\nTo: " + to + "\r\n" + extraHeaders
                + "Subject: s\r\n\r\nbody");
    }

    private static String address(int length) {
        return "a".repeat(length - DOMAIN.length() - 1) + "@" + DOMAIN;
    }
}
