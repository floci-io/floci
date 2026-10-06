package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.SingleBody;
import org.apache.james.mime4j.stream.Field;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SES V2 SendEmail with {@code Content.Simple.Attachments}: the message is assembled as one MIME
 * message, scanned, and captured by the inspection endpoint with its attachments.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesSimpleAttachmentsV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String SENDER = "attach-sender@example.com";
    private static final byte[] PDF_BYTES = "%PDF-1.4 fake report \u0000\u0001ÿ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3, (byte) 0xfe};

    @Test
    @Order(1)
    void createSender() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\": \"" + SENDER + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
    }

    @Test
    @Order(2)
    void simpleSend_withAttachmentAndInlinePart_isCapturedWithBoth() throws IOException {
        String messageId = send("""
                {"FromEmailAddress": "%s",
                 "Destination": {"ToAddresses": ["to@example.com"], "BccAddresses": ["hidden@example.com"]},
                 "Content": {"Simple": {
                    "Subject": {"Data": "With attachments"},
                    "Body": {"Text": {"Data": "plain body"},
                             "Html": {"Data": "<p>html body <img src=\\"cid:logo\\"></p>"}},
                    "Headers": [{"Name": "X-Custom", "Value": "custom-value"}],
                    "Attachments": [
                      {"FileName": "report.pdf", "RawContent": "%s", "ContentType": "application/pdf",
                       "ContentDisposition": "ATTACHMENT", "ContentDescription": "Monthly report"},
                      {"FileName": "logo.png", "RawContent": "%s", "ContentType": "image/png",
                       "ContentDisposition": "INLINE", "ContentId": "logo"}
                    ]}}}
                """.formatted(SENDER, b64(PDF_BYTES), b64(PNG_BYTES)))
                .then().statusCode(200).extract().path("MessageId");

        JsonPath captured = given().when().get("/_aws/ses?id=" + messageId)
                .then().statusCode(200)
                .body("messages", hasSize(1))
                .body("messages[0].Subject", equalTo("With attachments"))
                .body("messages[0].Body.text_part", equalTo("plain body"))
                .body("messages[0].Body.html_part", equalTo("<p>html body <img src=\"cid:logo\"></p>"))
                .body("messages[0].Destination.ToAddresses", contains("to@example.com"))
                .body("messages[0].Headers.find { it.Name == 'X-Custom' }.Value", equalTo("custom-value"))
                .body("messages[0].Attachments", hasSize(2))
                .body("messages[0].Attachments[0].FileName", equalTo("report.pdf"))
                .body("messages[0].Attachments[0].ContentType", equalTo("application/pdf"))
                .body("messages[0].Attachments[0].ContentDisposition", equalTo("ATTACHMENT"))
                .body("messages[0].Attachments[0].ContentDescription", equalTo("Monthly report"))
                .body("messages[0].Attachments[0]", not(hasKey("ContentId")))
                .body("messages[0].Attachments[0].Size", equalTo(PDF_BYTES.length))
                .body("messages[0].Attachments[1].FileName", equalTo("logo.png"))
                .body("messages[0].Attachments[1].ContentType", equalTo("image/png"))
                .body("messages[0].Attachments[1].ContentDisposition", equalTo("INLINE"))
                .body("messages[0].Attachments[1].ContentId", equalTo("<logo>"))
                .body("messages[0].Attachments[1].Size", equalTo(PNG_BYTES.length))
                .body("messages[0].RawData", notNullValue())
                .extract().jsonPath();

        byte[] mime = Base64.getDecoder().decode(captured.getString("messages[0].RawData"));
        String wire = new String(mime, StandardCharsets.UTF_8);
        assertTrue(wire.contains("Message-ID: <" + messageId + "@email.amazonses.com>"), wire);
        assertTrue(wire.contains("X-Custom: custom-value"), wire);
        assertFalse(wire.contains("hidden@example.com"), "Bcc must not appear in the message");

        Message message = SmtpRelay.parseMime(mime);
        assertNotNull(message);
        assertEquals("With attachments", message.getSubject());
        List<Entity> leaves = new ArrayList<>();
        collectLeaves(message, leaves);

        Entity pdf = leafNamed(leaves, "report.pdf");
        assertEquals("application/pdf", pdf.getMimeType());
        assertEquals("attachment", pdf.getDispositionType());
        assertArrayEquals(PDF_BYTES, bytes(pdf));
        assertEquals("Monthly report", headerValue(pdf, "Content-Description"));

        Entity png = leafNamed(leaves, "logo.png");
        assertEquals("image/png", png.getMimeType());
        assertEquals("inline", png.getDispositionType());
        assertEquals("<logo>", headerValue(png, "Content-ID"));
        assertArrayEquals(PNG_BYTES, bytes(png));

        assertTrue(leaves.stream().anyMatch(e -> "text/plain".equals(e.getMimeType())
                && new String(bytesUnchecked(e), StandardCharsets.UTF_8).contains("plain body")));
        assertTrue(leaves.stream().anyMatch(e -> "text/html".equals(e.getMimeType())
                && new String(bytesUnchecked(e), StandardCharsets.UTF_8).contains("cid:logo")));
    }

    @Test
    @Order(3)
    void simpleSend_withoutAttachments_isCapturedAsBefore() {
        String messageId = send("""
                {"FromEmailAddress": "%s",
                 "Destination": {"ToAddresses": ["to@example.com"]},
                 "Content": {"Simple": {
                    "Subject": {"Data": "No attachments"},
                    "Body": {"Text": {"Data": "just text"}}}}}
                """.formatted(SENDER))
                .then().statusCode(200).extract().path("MessageId");

        given().when().get("/_aws/ses?id=" + messageId)
                .then().statusCode(200)
                .body("messages[0].Subject", equalTo("No attachments"))
                .body("messages[0].Body.text_part", equalTo("just text"))
                .body("messages[0].Body.html_part", nullValue())
                .body("messages[0]", not(hasKey("Attachments")))
                .body("messages[0]", not(hasKey("RawData")));
    }

    @Test
    @Order(4)
    void simpleSend_withVirusInAnAttachment_isRejectedLikeARawSend() {
        String virus = b64(SesContentScan.signature().getBytes(StandardCharsets.US_ASCII));
        String messageId = send("""
                {"FromEmailAddress": "%s",
                 "Destination": {"ToAddresses": ["to@example.com"]},
                 "Content": {"Simple": {
                    "Subject": {"Data": "Infected"},
                    "Body": {"Text": {"Data": "clean body"}},
                    "Attachments": [{"FileName": "test.txt", "RawContent": "%s"}]}}}
                """.formatted(SENDER, virus))
                .then().statusCode(200).extract().path("MessageId");

        given().when().get("/_aws/ses?id=" + messageId)
                .then().statusCode(200)
                .body("messages[0].RejectReason", equalTo("Bad content"))
                .body("messages[0].Destination.ToAddresses", contains("to@example.com"))
                .body("messages[0]", not(hasKey("Attachments")))
                .body("messages[0]", not(hasKey("RawData")))
                .body("messages[0]", not(hasKey("Subject")))
                .body("messages[0]", not(hasKey("Body")));
    }

    @Test
    @Order(5)
    void simpleSend_attachmentWithoutFileName_isRejected() {
        sendWithAttachments("[{\"RawContent\": \"" + b64(PDF_BYTES) + "\"}]")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'content.simple.attachments.1.member.fileName' failed to satisfy constraint: "
                        + "Member must not be null"));
    }

    @Test
    @Order(6)
    void simpleSend_attachmentWithoutRawContent_isRejected() {
        sendWithAttachments("[{\"FileName\": \"a.pdf\", \"RawContent\": \"" + b64(PDF_BYTES) + "\"},"
                + "{\"FileName\": \"b.pdf\"}]")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'content.simple.attachments.2.member.rawContent' failed to satisfy constraint: "
                        + "Member must not be null"));
    }

    @Test
    @Order(7)
    void simpleSend_attachmentWithUnknownDisposition_isRejected() {
        sendWithAttachments("[{\"FileName\": \"a.pdf\", \"RawContent\": \"" + b64(PDF_BYTES)
                + "\", \"ContentDisposition\": \"EMBEDDED\"}]")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value 'EMBEDDED' at "
                        + "'content.simple.attachments.1.member.contentDisposition' failed to satisfy "
                        + "constraint: Member must satisfy enum value set: [ATTACHMENT, INLINE]"));
    }

    @Test
    @Order(8)
    void simpleSend_attachmentWithUnknownTransferEncoding_isRejected() {
        sendWithAttachments("[{\"FileName\": \"a.pdf\", \"RawContent\": \"" + b64(PDF_BYTES)
                + "\", \"ContentTransferEncoding\": \"UUENCODE\"}]")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", containsString("Member must satisfy enum value set: "
                        + "[BASE64, QUOTED_PRINTABLE, SEVEN_BIT]"));
    }

    @Test
    @Order(9)
    void simpleSend_attachmentLengthLimits_areEnforced() {
        String longName = "a".repeat(256);
        sendWithAttachments("[{\"FileName\": \"" + longName + "\", \"RawContent\": \"" + b64(PDF_BYTES) + "\"}]")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", containsString("'content.simple.attachments.1.member.fileName' failed to "
                        + "satisfy constraint: Member must have length less than or equal to 255"));

        sendWithAttachments("[{\"FileName\": \"a.png\", \"RawContent\": \"" + b64(PNG_BYTES)
                + "\", \"ContentId\": \"\"}]")
                .then().statusCode(400)
                .body("message", containsString("'content.simple.attachments.1.member.contentId' failed to "
                        + "satisfy constraint: Member must have length greater than or equal to 1"));

        sendWithAttachments("[{\"FileName\": \"a.png\", \"RawContent\": \"" + b64(PNG_BYTES)
                + "\", \"ContentType\": \"" + "x".repeat(79) + "\"}]")
                .then().statusCode(400)
                .body("message", containsString("'content.simple.attachments.1.member.contentType' failed to "
                        + "satisfy constraint: Member must have length less than or equal to 78"));

        sendWithAttachments("[{\"FileName\": \"a.png\", \"RawContent\": \"" + b64(PNG_BYTES)
                + "\", \"ContentDescription\": \"" + "x".repeat(1001) + "\"}]")
                .then().statusCode(400)
                .body("message", containsString("'content.simple.attachments.1.member.contentDescription' "
                        + "failed to satisfy constraint: Member must have length less than or equal to 1000"));
    }

    @Test
    @Order(10)
    void simpleSend_attachmentWithInvalidBase64_isRejected() {
        sendWithAttachments("[{\"FileName\": \"a.pdf\", \"RawContent\": \"not base64!!\"}]")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(11)
    void cleanUp() {
        given().header("Authorization", AUTH)
        .when().delete("/v2/email/identities/" + SENDER).then().statusCode(200);
    }

    private static Response send(String body) {
        return given().contentType("application/json").header("Authorization", AUTH)
                .body(body)
                .when().post("/v2/email/outbound-emails");
    }

    private static Response sendWithAttachments(String attachmentsJson) {
        return send("""
                {"FromEmailAddress": "%s",
                 "Destination": {"ToAddresses": ["to@example.com"]},
                 "Content": {"Simple": {
                    "Subject": {"Data": "Invalid"},
                    "Body": {"Text": {"Data": "body"}},
                    "Attachments": %s}}}
                """.formatted(SENDER, attachmentsJson));
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static void collectLeaves(Entity entity, List<Entity> leaves) {
        Body body = entity.getBody();
        if (body instanceof Multipart multipart) {
            for (Entity part : multipart.getBodyParts()) {
                collectLeaves(part, leaves);
            }
        } else {
            leaves.add(entity);
        }
    }

    private static Entity leafNamed(List<Entity> leaves, String fileName) {
        return leaves.stream().filter(e -> fileName.equals(e.getFilename())).findFirst()
                .orElseThrow(() -> new AssertionError("no MIME part named " + fileName));
    }

    private static byte[] bytes(Entity entity) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((SingleBody) entity.getBody()).writeTo(out);
        return out.toByteArray();
    }

    private static byte[] bytesUnchecked(Entity entity) {
        try {
            return bytes(entity);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String headerValue(Entity entity, String name) {
        Field field = entity.getHeader().getField(name);
        return field == null ? null : field.getBody().trim();
    }
}
