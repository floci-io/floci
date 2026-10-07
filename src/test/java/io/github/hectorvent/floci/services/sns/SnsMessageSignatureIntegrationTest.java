package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies SNS deliveries the way an AWS SDK message validator does: fetch the certificate from
 * {@code SigningCertURL}, rebuild the canonical string for the message type, and check the
 * base64 signature with SHA1withRSA ({@code SignatureVersion} 1) or SHA256withRSA (2).
 */
@QuarkusTest
class SnsMessageSignatureIntegrationTest {

    private static final String BASE_URL = "http://localhost:4566";
    private static final String CERT_PREFIX = BASE_URL + "/_aws/sns/SimpleNotificationService-";
    private static final List<String> NOTIFICATION_FIELDS =
            List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type");
    private static final List<String> CONFIRMATION_FIELDS =
            List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> received = new CopyOnWriteArrayList<>();
    private static HttpServer httpServer;
    private static int httpPort;

    @BeforeAll
    static void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpPort = httpServer.getAddress().getPort();
        httpServer.createContext("/signed", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        httpServer.start();
    }

    @AfterAll
    static void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @BeforeEach
    void clearReceived() {
        received.clear();
    }

    @Test
    void httpConfirmationAndNotification_verifyAgainstServedCertificate() throws Exception {
        String topicArn = createTopic("signed-http-topic");
        subscribe(topicArn, "http", "http://localhost:" + httpPort + "/signed");

        JsonNode confirmation = MAPPER.readTree(awaitMessage(0));
        assertEquals("SubscriptionConfirmation", confirmation.get("Type").asText());
        assertEquals("1", confirmation.get("SignatureVersion").asText());
        assertTrue(verify(confirmation, "SigningCertURL"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ConfirmSubscription")
            .formParam("TopicArn", topicArn)
            .formParam("Token", confirmation.get("Token").asText())
        .when()
            .post("/")
        .then()
            .statusCode(200);

        publish(topicArn, "Hello signed HTTP", "Signed subject");
        JsonNode notification = MAPPER.readTree(awaitMessage(1));
        assertEquals("Notification", notification.get("Type").asText());
        assertEquals("Signed subject", notification.get("Subject").asText());
        assertEquals("1", notification.get("SignatureVersion").asText());
        assertTrue(verify(notification, "SigningCertURL"));
        assertEquals(confirmation.get("SigningCertURL").asText(), notification.get("SigningCertURL").asText());
    }

    @Test
    void sqsEnvelope_verifiesAndTamperedMessageFails() throws Exception {
        String queueUrl = createQueue("signed-envelope-queue");
        String topicArn = createTopic("signed-sqs-topic");
        subscribe(topicArn, "sqs", queueUrl);

        publish(topicArn, "Hello signed SQS", null);
        JsonNode envelope = MAPPER.readTree(receiveBody(queueUrl));

        assertEquals("Notification", envelope.get("Type").asText());
        assertFalse(envelope.has("Subject"));
        assertEquals("1", envelope.get("SignatureVersion").asText());
        assertTrue(envelope.get("UnsubscribeURL").asText().contains("Action=Unsubscribe"));
        assertTrue(verify(envelope, "SigningCertURL"));

        ObjectNode tampered = envelope.deepCopy();
        tampered.put("Message", "Hello forged SQS");
        assertFalse(verify(tampered, "SigningCertURL"));
    }

    @Test
    void signatureVersion2Topic_signsWithSha256() throws Exception {
        String queueUrl = createQueue("signed-v2-queue");
        String topicArn = createTopic("signed-v2-topic");
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SetTopicAttributes")
            .formParam("TopicArn", topicArn)
            .formParam("AttributeName", "SignatureVersion")
            .formParam("AttributeValue", "2")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        subscribe(topicArn, "sqs", queueUrl);

        publish(topicArn, "Hello SHA256", "v2");
        JsonNode envelope = MAPPER.readTree(receiveBody(queueUrl));

        assertEquals("2", envelope.get("SignatureVersion").asText());
        assertTrue(verify(envelope, "SigningCertURL"));
        assertFalse(verifyWith(envelope, "SigningCertURL", "SHA1withRSA"));
    }

    @Test
    void unknownCertificateName_isNotFound() {
        given()
        .when()
            .get("/_aws/sns/SimpleNotificationService-00000000000000000000000000000000.pem")
        .then()
            .statusCode(404);
    }

    private static boolean verify(JsonNode message, String certUrlField) throws Exception {
        String algorithm = "2".equals(message.get("SignatureVersion").asText()) ? "SHA256withRSA" : "SHA1withRSA";
        return verifyWith(message, certUrlField, algorithm);
    }

    private static boolean verifyWith(JsonNode message, String certUrlField, String algorithm) throws Exception {
        String certUrl = message.get(certUrlField).asText();
        assertTrue(certUrl.startsWith(CERT_PREFIX), certUrl);
        assertTrue(certUrl.endsWith(".pem"), certUrl);

        ExtractableResponse<Response> response = given()
        .when()
            .get(certUrl.substring(BASE_URL.length()))
        .then()
            .statusCode(200)
            .extract();
        assertTrue(response.contentType().startsWith("application/x-pem-file"), response.contentType());
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(response.asByteArray()));

        List<String> fields = "Notification".equals(message.get("Type").asText())
                ? NOTIFICATION_FIELDS
                : CONFIRMATION_FIELDS;
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            JsonNode value = message.get(field);
            if (value != null && !value.isNull()) {
                canonical.append(field).append('\n').append(value.asText()).append('\n');
            }
        }

        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(message.get("Signature").asText()));
    }

    private static String awaitMessage(int index) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (received.size() <= index && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(received.size() > index, "no HTTP delivery #" + index);
        return received.get(index);
    }

    private static String createTopic(String name) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", name)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
    }

    private static String createQueue(String name) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", name)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static void subscribe(String topicArn, String protocol, String endpoint) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", protocol)
            .formParam("Endpoint", endpoint)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"));
    }

    private static void publish(String topicArn, String message, String subject) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", message);
        if (subject != null) {
            request.formParam("Subject", subject);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String receiveBody(String queueUrl) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
            .formParam("WaitTimeSeconds", "2")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Body");
    }
}
