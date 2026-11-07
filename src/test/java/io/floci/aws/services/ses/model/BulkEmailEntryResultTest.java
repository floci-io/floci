package io.floci.aws.services.ses.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BulkEmailEntryResultTest {

    @Test
    void toV1String_invalidParameter_mapsToInvalidParameterValue() {
        // SES v1 SendBulkTemplatedEmail per-destination Status uses
        // "InvalidParameterValue", not "InvalidParameter".
        assertEquals("InvalidParameterValue",
                BulkEmailEntryResult.Status.INVALID_PARAMETER.toV1String());
    }

    @Test
    void toV1String_standardEnumsUseCamelCase() {
        assertEquals("Success", BulkEmailEntryResult.Status.SUCCESS.toV1String());
        assertEquals("MessageRejected", BulkEmailEntryResult.Status.MESSAGE_REJECTED.toV1String());
        assertEquals("MailFromDomainNotVerified",
                BulkEmailEntryResult.Status.MAIL_FROM_DOMAIN_NOT_VERIFIED.toV1String());
        assertEquals("ConfigurationSetDoesNotExist",
                BulkEmailEntryResult.Status.CONFIGURATION_SET_DOES_NOT_EXIST.toV1String());
        assertEquals("AccountDailyQuotaExceeded",
                BulkEmailEntryResult.Status.ACCOUNT_DAILY_QUOTA_EXCEEDED.toV1String());
        assertEquals("TransientFailure",
                BulkEmailEntryResult.Status.TRANSIENT_FAILURE.toV1String());
        assertEquals("Failed", BulkEmailEntryResult.Status.FAILED.toV1String());
    }
}
