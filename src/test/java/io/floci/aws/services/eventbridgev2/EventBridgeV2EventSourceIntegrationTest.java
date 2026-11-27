package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridgev2.EventBridgeV2Client;
import software.amazon.awssdk.services.eventbridgev2.model.CreateEventSourceResponse;
import software.amazon.awssdk.services.eventbridgev2.model.DescribeEventSourceResponse;
import software.amazon.awssdk.services.eventbridgev2.model.EventSourceConfiguration;
import software.amazon.awssdk.services.eventbridgev2.model.EventSourceSummary;
import software.amazon.awssdk.services.eventbridgev2.model.InvalidInputException;
import software.amazon.awssdk.services.eventbridgev2.model.ListEventSourcesResponse;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceInUseException;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceNotFoundException;
import software.amazon.awssdk.services.eventbridgev2.model.UpdateEventSourceResponse;
import software.amazon.awssdk.services.eventbridgev2.model.WebhookAuthConfiguration;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretResponse;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Webhook event sources of EventBridge V2 and the managed secrets that hold their credentials, through the SDKs. */
@QuarkusTest
class EventBridgeV2EventSourceIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/WebhookRole";
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aWebhookEventSourceLivesThroughCreateDescribeUpdateAndDelete() {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SecretsManagerClient secrets = secrets()) {
            String bus = events.createEventBus(request -> request.name("v2-src-" + suffix)).eventBusArn();

            CreateEventSourceResponse created = events.createEventSource(request -> request.name("hook-" + suffix)
                    .eventBusArn(bus).description("orders webhook").tags(Map.of("team", "orders"))
                    .configuration(EventSourceConfiguration.fromWebhookEventsConfiguration(webhook -> webhook
                            .roleArn(ROLE).metadataHttpHeaders("X-Request-Id")
                            .authConfiguration(staticToken("X-Token", "s3cret-token")))));

            assertTrue(created.eventSourceArn().matches(
                    "arn:aws:events:us-east-1:000000000000:event-sourcev2/webhook/hook-" + suffix + "/[a-z0-9]{25}"),
                    created.eventSourceArn());
            assertEquals(bus, created.eventBusArn());
            assertEquals("ACTIVE", created.stateAsString());
            assertNotNull(created.creationTime());
            String url = created.eventSourceDetails().webhookDetails().webhookUrl();
            assertTrue(url.matches("http://a[a-z2-7]{37}\\.c01\\.events-webhook\\.us-east-1\\..+/k1[A-Za-z0-9_-]{64}/"),
                    url);
            String secretArn = created.eventSourceDetails().webhookDetails().secretArn();

            assertEquals("s3cret-token", secrets.getSecretValue(request -> request.secretId(secretArn))
                    .secretString());
            DescribeSecretResponse secret = secrets.describeSecret(request -> request.secretId(secretArn));
            assertEquals("events-webhook", secret.owningService());
            assertTrue(secret.name().startsWith("events-webhook!"), secret.name());

            DescribeEventSourceResponse described = events.describeEventSource(
                    request -> request.eventSourceArn(created.eventSourceArn()));
            assertEquals("orders webhook", described.description());
            assertEquals(url, described.eventSourceDetails().webhookDetails().webhookUrl());
            assertEquals(secretArn, described.eventSourceDetails().webhookDetails().secretArn());
            assertEquals(List.of("X-Request-Id"),
                    described.configuration().webhookEventsConfiguration().metadataHttpHeaders());
            assertNull(described.configuration().webhookEventsConfiguration().authConfiguration()
                    .staticTokenAuthConfiguration().secretValue());
            assertEquals("X-Token", described.configuration().webhookEventsConfiguration().authConfiguration()
                    .staticTokenAuthConfiguration().tokenHttpHeader());

            ListEventSourcesResponse listed = events.listEventSources(request -> request.eventBusArn(bus));
            assertEquals(1, listed.eventSources().size());
            EventSourceSummary summary = listed.eventSources().getFirst();
            assertEquals("WEBHOOK_EVENTS", summary.typeAsString());
            assertEquals("000000000000", summary.eventSourceAccountId());
            assertEquals(0, events.listEventSources(request -> request.eventBusArn(bus).namePrefix("nope"))
                    .eventSources().size());

            UpdateEventSourceResponse updated = events.updateEventSource(request -> request
                    .eventSourceArn(created.eventSourceArn()).description("changed")
                    .configuration(EventSourceConfiguration.fromWebhookEventsConfiguration(webhook -> webhook
                            .roleArn(ROLE).authConfiguration(auth -> auth.staticTokenAuthConfiguration(
                                    token -> token.tokenHttpHeader("X-Changed"))))));
            assertEquals(created.eventSourceArn(), updated.eventSourceArn());
            DescribeEventSourceResponse afterUpdate = events.describeEventSource(
                    request -> request.eventSourceArn(created.eventSourceArn()));
            assertEquals("changed", afterUpdate.description());
            assertEquals(url, afterUpdate.eventSourceDetails().webhookDetails().webhookUrl());
            assertTrue(afterUpdate.configuration().webhookEventsConfiguration().metadataHttpHeaders().isEmpty());
            assertEquals("s3cret-token", secrets.getSecretValue(request -> request.secretId(secretArn))
                    .secretString());

            ResourceInUseException inUse = assertThrows(ResourceInUseException.class,
                    () -> events.deleteEventBus(request -> request.eventBusArn(bus)));
            assertEquals("The event bus cannot be deleted because it has one or more event sources that are not "
                    + "revoked. Delete or revoke them and retry.", inUse.awsErrorDetails().errorMessage());

            events.deleteEventSource(request -> request.eventSourceArn(created.eventSourceArn()));
            assertNotNull(secrets.describeSecret(request -> request.secretId(secretArn)).deletedDate());
            assertThrows(ResourceNotFoundException.class, () -> events.describeEventSource(
                    request -> request.eventSourceArn(created.eventSourceArn())));
            events.deleteEventBus(request -> request.eventBusArn(bus));
        }
    }

    @Test
    void basicAndHmacSourcesKeepTheirCredentialsInTheSecret() {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SecretsManagerClient secrets = secrets()) {
            String bus = events.createEventBus(request -> request.name("v2-auth-" + suffix)).eventBusArn();
            CreateEventSourceResponse basic = events.createEventSource(request -> request.name("basic-" + suffix)
                    .eventBusArn(bus).configuration(EventSourceConfiguration.fromWebhookEventsConfiguration(
                            webhook -> webhook.roleArn(ROLE).authConfiguration(auth -> auth
                                    .basicHttpAuthConfiguration(credential -> credential.username("alice")
                                            .password("p4ssword"))))));
            assertEquals("{\"username\":\"alice\",\"password\":\"p4ssword\"}", secrets.getSecretValue(
                    request -> request.secretId(basic.eventSourceDetails().webhookDetails().secretArn()))
                    .secretString());
            DescribeEventSourceResponse described = events.describeEventSource(
                    request -> request.eventSourceArn(basic.eventSourceArn()));
            assertNull(described.configuration().webhookEventsConfiguration().authConfiguration()
                    .basicHttpAuthConfiguration().username());

            CreateEventSourceResponse hmac = events.createEventSource(request -> request.name("hmac-" + suffix)
                    .eventBusArn(bus).configuration(EventSourceConfiguration.fromWebhookEventsConfiguration(
                            webhook -> webhook.roleArn(ROLE).authConfiguration(auth -> auth
                                    .hmacAuthConfiguration(signing -> signing.algorithm("SHA256").encoding("HEX")
                                            .signatureHttpHeader("X-Signature").signedMessageComposition("BODY"))))));
            String generated = secrets.getSecretValue(
                    request -> request.secretId(hmac.eventSourceDetails().webhookDetails().secretArn()))
                    .secretString();
            assertEquals(44, generated.length());

            events.deleteEventSource(request -> request.eventSourceArn(basic.eventSourceArn()));
            events.deleteEventSource(request -> request.eventSourceArn(hmac.eventSourceArn()));
            events.deleteEventBus(request -> request.eventBusArn(bus));
        }
    }

    @Test
    void errorsCarryTheCodesAndMessagesAwsAnswers() {
        String suffix = suffix();
        try (EventBridgeV2Client events = events()) {
            String bus = events.createEventBus(request -> request.name("v2-err-" + suffix)).eventBusArn();
            EventSourceConfiguration configuration = EventSourceConfiguration.fromWebhookEventsConfiguration(
                    webhook -> webhook.roleArn(ROLE).authConfiguration(staticToken("Host", null)));

            InvalidInputException reserved = assertThrows(InvalidInputException.class, () -> events
                    .createEventSource(request -> request.name("bad-" + suffix).eventBusArn(bus)
                            .configuration(configuration)));
            assertEquals("WebhookEventsConfiguration.AuthConfiguration.StaticTokenAuthConfiguration.TokenHttpHeader "
                    + "can't be the header Host: it is not allowed. Header names are case-insensitive.",
                    reserved.awsErrorDetails().errorMessage());

            InvalidInputException constraint = assertThrows(InvalidInputException.class, () -> events
                    .createEventSource(request -> request.name("-x").eventBusArn(bus).configuration(configuration)));
            assertEquals("1 validation error detected: Value '-x' at 'name' failed to satisfy constraint: Member "
                    + "must satisfy regular expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*",
                    constraint.awsErrorDetails().errorMessage());

            EventSourceConfiguration oversized = EventSourceConfiguration.fromWebhookEventsConfiguration(
                    webhook -> webhook.roleArn(ROLE).authConfiguration(auth -> auth.hmacAuthConfiguration(
                            signing -> signing.algorithm("SHA256").encoding("HEX").signatureHttpHeader("X-Signature")
                                    .signedMessageComposition("BODY").secretValue("é".repeat(257)))));
            InvalidInputException tooLong = assertThrows(InvalidInputException.class, () -> events
                    .createEventSource(request -> request.name("big-" + suffix).eventBusArn(bus)
                            .configuration(oversized)));
            assertEquals("WebhookEventsConfiguration.AuthConfiguration.HmacAuthConfiguration is invalid: "
                    + "SecretValue must be at most 512 bytes (for BASE64, after decoding)",
                    tooLong.awsErrorDetails().errorMessage());

            String missingBus = bus.replace("v2-err-" + suffix, "v2-none-" + suffix);
            ResourceNotFoundException noBus = assertThrows(ResourceNotFoundException.class,
                    () -> events.listEventSources(request -> request.eventBusArn(missingBus)));
            assertEquals("Resource does not exist", noBus.awsErrorDetails().errorMessage());

            EventSourceConfiguration valid = EventSourceConfiguration.fromWebhookEventsConfiguration(
                    webhook -> webhook.roleArn(ROLE).authConfiguration(staticToken("X-Token", null)));
            String created = events.createEventSource(request -> request.name("dup-" + suffix).eventBusArn(bus)
                    .configuration(valid)).eventSourceArn();
            ResourceAlreadyExistsException exists = assertThrows(ResourceAlreadyExistsException.class, () -> events
                    .createEventSource(request -> request.name("dup-" + suffix).eventBusArn(bus)
                            .configuration(valid)));
            assertEquals("Resource already exists", exists.awsErrorDetails().errorMessage());

            String missing = created.replace("dup-" + suffix, "none-" + suffix);
            ResourceNotFoundException notFound = assertThrows(ResourceNotFoundException.class,
                    () -> events.describeEventSource(request -> request.eventSourceArn(missing)));
            assertEquals("EventSource not found: " + missing, notFound.awsErrorDetails().errorMessage());

            events.deleteEventSource(request -> request.eventSourceArn(created));
            events.deleteEventBus(request -> request.eventBusArn(bus));
        }
    }

    @Test
    void aSourceWhoseSecretIsAlreadyScheduledForDeletionCanBeDeleted() {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SecretsManagerClient secrets = secrets()) {
            String bus = events.createEventBus(request -> request.name("v2-sched-" + suffix)).eventBusArn();
            CreateEventSourceResponse created = events.createEventSource(request -> request.name("sched-" + suffix)
                    .eventBusArn(bus).configuration(EventSourceConfiguration.fromWebhookEventsConfiguration(
                            webhook -> webhook.roleArn(ROLE).authConfiguration(staticToken("X-Token", null)))));
            String secretArn = created.eventSourceDetails().webhookDetails().secretArn();

            secrets.deleteSecret(request -> request.secretId(secretArn));
            events.deleteEventSource(request -> request.eventSourceArn(created.eventSourceArn()));

            assertNotNull(secrets.describeSecret(request -> request.secretId(secretArn)).deletedDate());
            assertThrows(ResourceNotFoundException.class, () -> events.describeEventSource(
                    request -> request.eventSourceArn(created.eventSourceArn())));
            events.deleteEventBus(request -> request.eventBusArn(bus));
        }
    }

    @Test
    void aMemberOfTheWrongTypeIsASerializationExceptionWithNoMessage() throws Exception {
        ObjectNode body = CBOR_MAPPER.createObjectNode().put("Name", 5);

        byte[] answer = given()
                .contentType("application/cbor")
                .accept("application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor")
                .body(CBOR_MAPPER.writeValueAsBytes(body))
        .when()
                .post("/service/AWSEventsV2/operation/CreateEventSource")
        .then()
                .statusCode(400)
                .header("smithy-protocol", equalTo("rpc-v2-cbor"))
                .extract().asByteArray();

        JsonNode error = CBOR_MAPPER.readTree(answer);
        assertEquals("SerializationException", error.path("__type").asText());
        assertNull(error.path("message").textValue());
    }

    private static WebhookAuthConfiguration staticToken(String header, String secretValue) {
        return WebhookAuthConfiguration.builder().staticTokenAuthConfiguration(
                token -> token.tokenHttpHeader(header).secretValue(secretValue)).build();
    }

    private static EventBridgeV2Client events() {
        return AwsTestClients.create(EventBridgeV2Client.builder(), "000000000000", Region.US_EAST_1);
    }

    private static SecretsManagerClient secrets() {
        return AwsTestClients.create(SecretsManagerClient.builder(), "000000000000", Region.US_EAST_1);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
