package io.floci.aws.services.ses;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ses.model.BulkEmailEntry;
import io.floci.aws.services.ses.model.EmailContent;
import io.floci.aws.services.ses.model.SendBulkEmailRequest;
import io.floci.aws.services.ses.model.SendEmailRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A raw send and a bulk send check the sender before they refuse a paused account
 * (probe-confirmed on v2 SendEmail Raw, v2 SendBulkEmail and v1 SendBulkTemplatedEmail).
 */
class SesServiceAccountSendingPausedTest {

    private static final String REGION = "us-east-1";
    private static final String SENDER = "from@example.com";
    private static final String TO = "to@example.com";
    private static final EmailContent.InlineTemplate INLINE =
            new EmailContent.InlineTemplate("s", "t", null, null, List.of());
    private static final String RAW = "From: " + SENDER + "\r\nTo: " + TO + "\r\nSubject: s\r\n\r\nbody";

    private SesServiceTestBuilder builder;
    private SesService service;

    @BeforeEach
    void setUp() {
        builder = SesServiceTestBuilder.create();
        service = builder.build();
        builder.accountService().setAccountSendingEnabled(REGION, false);
    }

    @Test
    void rawSend_unverifiedSender_isRejectedBeforeThePause() {
        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(rawRequest()));

        assertEquals("MessageRejected", e.getErrorCode());
    }

    @Test
    void rawSend_verifiedSender_isRefusedByThePause() {
        builder.identityService().verifyEmailIdentity(SENDER, REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.sendEmail(rawRequest()));

        assertPaused(e);
    }

    @Test
    void bulkSend_unverifiedSender_isRejectedBeforeThePause() {
        AwsException e = assertThrows(AwsException.class, () -> service.sendBulkEmail(bulkRequest(INLINE)));

        assertEquals("MessageRejected", e.getErrorCode());
    }

    @Test
    void bulkSend_verifiedSender_isRefusedByThePause() {
        builder.identityService().verifyEmailIdentity(SENDER, REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.sendBulkEmail(bulkRequest(INLINE)));

        assertPaused(e);
    }

    @Test
    void bulkSend_verifiedSender_isRefusedByThePauseBeforeAMissingTemplate() {
        builder.identityService().verifyEmailIdentity(SENDER, REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.sendBulkEmail(
                bulkRequest(new EmailContent.Template("missing", null, List.of()))));

        assertPaused(e);
    }

    @Test
    void bulkSend_noRecipient_isRefusedByThePause() {
        AwsException e = assertThrows(AwsException.class, () -> service.sendBulkEmail(SendBulkEmailRequest.builder()
                .source(SENDER)
                .region(REGION)
                .defaultContent(INLINE)
                .entries(List.of(new BulkEmailEntry(List.of(), null, null, null, null, null)))
                .build()));

        assertPaused(e);
    }

    private static SendEmailRequest rawRequest() {
        return SendEmailRequest.builder()
                .region(REGION)
                .content(new EmailContent.Raw(RAW))
                .build();
    }

    private static SendBulkEmailRequest bulkRequest(EmailContent.TemplateContent content) {
        return SendBulkEmailRequest.builder()
                .source(SENDER)
                .region(REGION)
                .defaultContent(content)
                .entries(List.of(new BulkEmailEntry(List.of(TO), null, null, null, null, null)))
                .build();
    }

    private static void assertPaused(AwsException e) {
        assertEquals("AccountSendingPausedException", e.getErrorCode());
        assertEquals("Sending is paused for this account.", e.getMessage());
        assertEquals(400, e.getHttpStatus());
    }
}
