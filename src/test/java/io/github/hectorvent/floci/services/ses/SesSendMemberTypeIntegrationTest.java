package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.EXPECTED_LIST;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.EXPECTED_NULL;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.LIST;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.NUMBER;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.STRUCTURE;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertError;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertSerializationException;

/**
 * A v2 send member of the wrong JSON type fails deserialization on AWS with a
 * SerializationException naming the type, ahead of every other check (probe-confirmed). One case
 * per member group shows the send endpoints check their body against the SES v2 shapes; the
 * messages per JSON type are covered by SesV2RequestShapesTest.
 */
@QuarkusTest
class SesSendMemberTypeIntegrationTest {

    private static final String TO = "success@simulator.amazonses.com";
    private static final String SEND = "/v2/email/outbound-emails";
    private static final String BULK = "/v2/email/outbound-bulk-emails";
    private static final String VERIFY = "/v2/email/outbound-custom-verification-emails";
    private static final String SIMPLE =
            "{\"Simple\": {\"Subject\": {\"Data\": \"s\"}, \"Body\": {\"Text\": {\"Data\": \"b\"}}}}";
    private static final String INLINE_TEMPLATE =
            "{\"Template\": {\"TemplateContent\": {\"Subject\": \"s\", \"Text\": \"b\"}, \"TemplateData\": \"{}\"}}";

    private static String send(String members, String content) {
        return "{" + members + "\"Content\": " + content + "}";
    }

    private static String bulkSend(String members, String entryDestination) {
        return "{" + members + "\"DefaultContent\": " + INLINE_TEMPLATE
                + ", \"BulkEmailEntries\": [{\"Destination\": " + entryDestination + "}]}";
    }

    private static String rawData() {
        return Base64.getEncoder().encodeToString(("From: sender@example.com\r\nTo: " + TO
                + "\r\nSubject: s\r\n\r\nb").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void v2SendEmail_objectFromEmailAddress_isNotReadAsAbsent() {
        assertSerializationException("POST", SEND, send("\"FromEmailAddress\": {},",
                "{\"Raw\": {\"Data\": \"" + rawData() + "\"}}"), STRUCTURE);
    }

    @Test
    void v2SendEmail_numericConfigurationSetName_returnsSerializationException() {
        assertSerializationException("POST", SEND, send("\"FromEmailAddress\": \"sender@example.com\", "
                + "\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, \"ConfigurationSetName\": 123,", SIMPLE),
                NUMBER);
    }

    @Test
    void v2SendEmail_numericRawData_namesTheBlobType() {
        assertSerializationException("POST", SEND, send("\"FromEmailAddress\": \"sender@example.com\",",
                "{\"Raw\": {\"Data\": 123}}"), "NUMBER_VALUE can not be converted to a Blob");
    }

    @Test
    void v2SendEmail_simpleContentTypeErrorPrecedesMissingSender() {
        assertSerializationException("POST", SEND, send("\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]},",
                "{\"Simple\": {\"Subject\": {\"Data\": 123}, \"Body\": {\"Text\": {\"Data\": \"b\"}}}}"), NUMBER);
    }

    @Test
    void v2SendEmail_stringSubject_returnsExpectedNull() {
        assertSerializationException("POST", SEND, send("\"FromEmailAddress\": \"sender@example.com\", "
                + "\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]},",
                "{\"Simple\": {\"Subject\": \"s\", \"Body\": {\"Text\": {\"Data\": \"b\"}}}}"), EXPECTED_NULL);
    }

    @Test
    void v2SendEmail_stringHeaders_returnsExpectedList() {
        assertSerializationException("POST", SEND, send("\"FromEmailAddress\": \"sender@example.com\", "
                + "\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]},",
                "{\"Simple\": {\"Subject\": {\"Data\": \"s\"}, \"Body\": {\"Text\": {\"Data\": \"b\"}}, "
                        + "\"Headers\": \"X-A: b\"}}"), EXPECTED_LIST);
    }

    @Test
    void v2SendEmail_templateNameTypeErrorPrecedesMissingSender() {
        assertSerializationException("POST", SEND, send("\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]},",
                "{\"Template\": {\"TemplateName\": 123, \"TemplateData\": \"{}\"}}"), NUMBER);
    }

    @Test
    void v2SendEmail_stringDestination_returnsExpectedNull() {
        assertSerializationException("POST", SEND, send("\"FromEmailAddress\": \"sender@example.com\", "
                + "\"Destination\": \"" + TO + "\",", SIMPLE), EXPECTED_NULL);
    }

    @Test
    void v2SendEmail_arrayDestination_returnsStartOfList() {
        assertSerializationException("POST", SEND, send("\"FromEmailAddress\": \"sender@example.com\", "
                + "\"Destination\": [\"" + TO + "\"],", SIMPLE), LIST);
    }

    @Test
    void v2SendEmail_nonArrayReplyTo_returnsExpectedList() {
        assertSerializationException("POST", SEND, send("\"FromEmailAddress\": \"sender@example.com\", "
                + "\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, "
                + "\"ReplyToAddresses\": \"reply@example.com\",", SIMPLE), EXPECTED_LIST);
    }

    @Test
    void v2SendEmail_nullReplyToElement_isAnInvalidAddress() {
        assertError("POST", SEND, send("\"FromEmailAddress\": \"sender@example.com\", "
                + "\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, \"ReplyToAddresses\": [null],", SIMPLE),
                "BadRequestException", "Invalid email address null.");
    }

    @Test
    void v2SendEmail_nullAddressElementIsReportedAfterContentTypeErrors() {
        assertSerializationException("POST", SEND, send("\"FromEmailAddress\": \"sender@example.com\", "
                + "\"Destination\": {\"ToAddresses\": [\"" + TO + "\"]}, \"ReplyToAddresses\": [null],",
                "{\"Simple\": {\"Subject\": {\"Data\": 123}, \"Body\": {\"Text\": {\"Data\": \"b\"}}}}"), NUMBER);
    }

    @Test
    void v2SendEmail_contentTypeErrorPrecedesEmailTagValidation() {
        assertSerializationException("POST", SEND, send("\"FromEmailAddress\": \"sender@example.com\", "
                + "\"EmailTags\": [{\"Name\": \"\", \"Value\": \"v\"}],",
                "{\"Raw\": {\"Data\": 123}}"), "NUMBER_VALUE can not be converted to a Blob");
    }

    @Test
    void v2SendBulkEmail_replyToTypeErrorPrecedesMissingSender() {
        assertSerializationException("POST", BULK, bulkSend("\"ReplyToAddresses\": \"reply@example.com\",",
                "{\"ToAddresses\": [\"" + TO + "\"]}"), EXPECTED_LIST);
    }

    @Test
    void v2SendBulkEmail_entryDestinationTypeErrorPrecedesMalformedSender() {
        assertSerializationException("POST", BULK, bulkSend("\"FromEmailAddress\": \"sender@\",",
                "{\"ToAddresses\": [\"" + TO + "\"], \"CcAddresses\": \"bad-shape\"}"), EXPECTED_LIST);
    }

    @Test
    void v2SendBulkEmail_arrayEntryDestination_returnsStartOfList() {
        assertSerializationException("POST", BULK, bulkSend("\"FromEmailAddress\": \"sender@example.com\",",
                "[\"" + TO + "\"]"), LIST);
    }

    @Test
    void v2SendBulkEmail_numericFromEmailAddress_returnsSerializationException() {
        assertSerializationException("POST", BULK, bulkSend("\"FromEmailAddress\": 123,",
                "{\"ToAddresses\": [\"" + TO + "\"]}"), NUMBER);
    }

    @Test
    void v2SendBulkEmail_nullReplyToElementIsReportedAfterEntryTypeErrors() {
        assertSerializationException("POST", BULK, bulkSend("\"FromEmailAddress\": \"sender@example.com\", "
                + "\"ReplyToAddresses\": [null],", "{\"ToAddresses\": [1]}"), NUMBER);
    }

    @Test
    void v2SendBulkEmail_entryReplacementTypeErrorPrecedesMissingStoredTemplate() {
        assertSerializationException("POST", BULK, """
            {
                "FromEmailAddress": "sender@example.com",
                "DefaultContent": {"Template": {"TemplateName": "no-such-template", "TemplateData": "{}"}},
                "BulkEmailEntries": [{"Destination": {"ToAddresses": ["%s"]}, "ReplacementTags": "bad"}]
            }
            """.formatted(TO), EXPECTED_LIST);
    }

    @Test
    void v2SendBulkEmail_entryTypeErrorPrecedesMissingStoredTemplate() {
        assertSerializationException("POST", BULK, """
            {
                "FromEmailAddress": "sender@example.com",
                "DefaultContent": {"Template": {"TemplateName": "no-such-template", "TemplateData": "{}"}},
                "BulkEmailEntries": [{"Destination": {"ToAddresses": [1]}}]
            }
            """, NUMBER);
    }

    @Test
    void v2SendCustomVerificationEmail_numericEmailAddress_returnsSerializationException() {
        assertSerializationException("POST", VERIFY, "{\"EmailAddress\": 123, \"TemplateName\": \"t\"}", NUMBER);
    }
}
