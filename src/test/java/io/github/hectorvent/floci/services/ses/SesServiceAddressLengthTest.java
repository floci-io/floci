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

class SesServiceAddressLengthTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "example.com";
    private static final String SENDER = "sender@" + DOMAIN;
    private static final String TO = "success@simulator.amazonses.com";
    private static final EmailContent.Simple SIMPLE =
            new EmailContent.Simple("Subject", "body", null, List.of());

    private SesService service;

    @BeforeEach
    void setUp() {
        service = SesServiceTestBuilder.create().build();
    }

    @Test
    void simpleSend_sourceOverLimit_isRejectedWithAddressInMessage() {
        String source = address(321, 'a');

        AwsException e = assertThrows(AwsException.class,
                () -> service.sendEmail(request(source).content(SIMPLE).build()));

        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals(expectedMessage(source), e.getMessage());
    }

    @Test
    void simpleSend_eachEnvelopeFieldIsChecked() {
        String tooLong = address(321, 'a');

        assertRejects(tooLong, () -> service.sendEmail(request(SENDER)
                .toAddresses(List.of(TO, tooLong)).content(SIMPLE).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(SENDER)
                .ccAddresses(List.of(tooLong)).content(SIMPLE).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(SENDER)
                .bccAddresses(List.of(tooLong)).content(SIMPLE).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(SENDER)
                .returnPath(tooLong).content(SIMPLE).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(SENDER)
                .replyToAddresses(List.of(tooLong)).content(SIMPLE).build()));
    }

    @Test
    void simpleSend_fieldsAreReportedInAwsOrder() {
        String source = address(400, 'b');
        String to = address(390, 'c');
        String cc = address(380, 'd');
        String bcc = address(370, 'e');
        String returnPath = address(360, 'f');
        String replyTo = address(350, 'g');

        assertRejects(source, () -> service.sendEmail(request(source).toAddresses(List.of(to))
                .content(SIMPLE).build()));
        assertRejects(to, () -> service.sendEmail(request(SENDER).toAddresses(List.of(to))
                .ccAddresses(List.of(cc)).content(SIMPLE).build()));
        assertRejects(cc, () -> service.sendEmail(request(SENDER).ccAddresses(List.of(cc))
                .bccAddresses(List.of(bcc)).content(SIMPLE).build()));
        assertRejects(bcc, () -> service.sendEmail(request(SENDER).bccAddresses(List.of(bcc))
                .returnPath(returnPath).content(SIMPLE).build()));
        assertRejects(returnPath, () -> service.sendEmail(request(SENDER).returnPath(returnPath)
                .replyToAddresses(List.of(replyTo)).content(SIMPLE).build()));
    }

    @Test
    void simpleSend_unknownConfigurationSetIsReportedBeforeLength() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.sendEmail(request(address(321, 'a')).configurationSetName("missing")
                        .content(SIMPLE).build()));

        assertEquals("ConfigurationSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void templatedSend_lengthIsReportedBeforeMissingTemplate() {
        String replyTo = address(321, 'a');

        assertRejects(replyTo, () -> service.sendEmail(request(SENDER).replyToAddresses(List.of(replyTo))
                .content(new EmailContent.Template("missing", null, List.of())).build()));
    }

    @Test
    void templatedSend_unknownConfigurationSetIsReportedBeforeMissingTemplate() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.sendEmail(request(address(321, 'a')).configurationSetName("missing")
                        .content(new EmailContent.Template("missing", null, List.of())).build()));

        assertEquals("ConfigurationSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void inlineTemplateSend_emptyContentIsReportedBeforeConfigurationSetAndLength() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.sendEmail(request(address(321, 'a')).configurationSetName("missing")
                        .content(new EmailContent.InlineTemplate("", "", "", null, List.of())).build()));

        assertEquals("InvalidTemplate", e.getErrorCode());
    }

    @Test
    void rawSend_sourceParameterIsReportedBeforeFromHeader() {
        String source = address(400, 'b');

        assertRejects(source, () -> service.sendEmail(request(source)
                .content(raw(address(330, 'c'), TO, "")).build()));
    }

    @Test
    void rawSend_fromHeaderIsReportedBeforeDestinations() {
        String from = address(330, 'c');

        assertRejects(from, () -> service.sendEmail(request(null).toAddresses(List.of(address(500, 'd')))
                .content(raw(from, TO, "")).build()));
    }

    @Test
    void rawSend_destinationsAreReportedBeforeToHeader() {
        String destination = address(500, 'd');

        assertRejects(destination, () -> service.sendEmail(request(SENDER).toAddresses(List.of(destination))
                .content(raw(SENDER, address(330, 'e'), "")).build()));
    }

    @Test
    void rawSend_eachAddressHeaderIsChecked() {
        String tooLong = address(330, 'a');

        assertRejects(tooLong, () -> service.sendEmail(request(null).toAddresses(List.of())
                .content(raw(SENDER, tooLong, "")).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(null).toAddresses(List.of())
                .content(raw(SENDER, TO, "Cc: " + tooLong + "\r\n")).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(null).toAddresses(List.of())
                .content(raw(SENDER, TO, "Bcc: " + tooLong + "\r\n")).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(null).toAddresses(List.of())
                .content(raw(SENDER, TO, "Reply-To: " + tooLong + "\r\n")).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(null).toAddresses(List.of())
                .content(raw(SENDER, TO, "Return-Path: " + tooLong + "\r\n")).build()));
    }

    @Test
    void rawSend_eachEnvelopeFieldOfTheRequestIsChecked() {
        String tooLong = address(330, 'a');

        assertRejects(tooLong, () -> service.sendEmail(request(SENDER)
                .ccAddresses(List.of(tooLong)).content(raw(SENDER, TO, "")).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(SENDER)
                .bccAddresses(List.of(tooLong)).content(raw(SENDER, TO, "")).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(SENDER)
                .returnPath(tooLong).content(raw(SENDER, TO, "")).build()));
        assertRejects(tooLong, () -> service.sendEmail(request(SENDER)
                .replyToAddresses(List.of(tooLong)).content(raw(SENDER, TO, "")).build()));
    }

    @Test
    void rawSend_nonAsciiFromHeaderIsMeasuredQuoted() {
        String suffix = " <" + SENDER + ">";
        String fits = "山".repeat(320 - suffix.length() - 2) + suffix;
        String tooLong = "山".repeat(321 - suffix.length() - 2) + suffix;

        assertDoesNotThrow(() -> service.sendEmail(request(null).content(rawUtf8(fits)).build()));
        assertRejects("\"" + tooLong.replace(suffix, "\"" + suffix),
                () -> service.sendEmail(request(null).content(rawUtf8(tooLong)).build()));
    }

    @Test
    void rawSend_headerAddressesAreMeasuredOneByOne() {
        String toHeader = address(200, 'a') + ", " + address(200, 'b');

        assertDoesNotThrow(() -> service.sendEmail(request(null).toAddresses(List.of())
                .content(raw(SENDER, toHeader, "")).build()));
    }

    @Test
    void rawSend_fromHeaderDisplayNameIsMeasuredWithoutQuotes() {
        assertDoesNotThrow(() -> service.sendEmail(request(null)
                .content(raw(quotedName(320), TO, "")).build()));
        assertRejects(quotedName(321).replace("\"", ""), () -> service.sendEmail(request(null)
                .content(raw(quotedName(321), TO, "")).build()));
    }

    @Test
    void rawSend_encodedWordHeaderNameIsMeasuredUndecoded() {
        String from = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(
                "山".repeat(90).getBytes(StandardCharsets.UTF_8)) + "?= <" + SENDER + ">";

        assertRejects(from, () -> service.sendEmail(request(null).content(raw(from, TO, "")).build()));
    }

    @Test
    void rawSend_escapedQuotesInHeaderNameCount() {
        String suffix = " <" + SENDER + ">";
        String inner = "a \\\"b\\\" ";
        String tooLong = "\"" + inner + "N".repeat(321 - suffix.length() - 2 - inner.length()) + "\"" + suffix;

        assertRejects(tooLong, () -> service.sendEmail(request(null).content(raw(tooLong, TO, "")).build()));
    }

    @Test
    void bulkSend_isNotSubjectToThePerSendCheck() {
        List<BulkEmailEntryResult> results = service.sendBulkEmail(SendBulkEmailRequest.builder()
                .source(SENDER)
                .defaultContent(new EmailContent.InlineTemplate("Subject", "body", null, null, List.of()))
                .entries(List.of(new BulkEmailEntry(List.of(address(321, 'a')), null, null, null, null, null)))
                .region(REGION)
                .build());

        assertEquals(BulkEmailEntryResult.Status.SUCCESS, results.get(0).getStatus());
    }

    private static void assertRejects(String reported, Executable send) {
        AwsException e = assertThrows(AwsException.class, send);
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals(expectedMessage(reported), e.getMessage());
    }

    private static String expectedMessage(String address) {
        return "Address length is more than 320 characters long: '" + address + "'.";
    }

    private static SendEmailRequest.Builder request(String source) {
        return SendEmailRequest.builder()
                .source(source)
                .toAddresses(List.of(TO))
                .region(REGION);
    }

    private static EmailContent.Raw raw(String from, String to, String extraHeaders) {
        return new EmailContent.Raw("From: " + from + "\r\nTo: " + to + "\r\n" + extraHeaders
                + "Subject: s\r\n\r\nbody");
    }

    private static EmailContent.Raw rawUtf8(String from) {
        return new EmailContent.Raw(Base64.getEncoder().encodeToString(
                ("From: " + from + "\r\nTo: " + TO + "\r\nSubject: s\r\n\r\nbody").getBytes(StandardCharsets.UTF_8)));
    }

    private static String address(int length, char fill) {
        return String.valueOf(fill).repeat(length - DOMAIN.length() - 1) + "@" + DOMAIN;
    }

    /** A {@code "N..." <sender>} mailbox whose unquoted {@code N... <sender>} form is {@code length} long. */
    private static String quotedName(int length) {
        String suffix = " <" + SENDER + ">";
        return "\"" + "N".repeat(length - suffix.length()) + "\"" + suffix;
    }
}
