package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridgev2.EventBridgeV2Client;
import software.amazon.awssdk.services.eventbridgev2.model.CreateEventSourceResponse;
import software.amazon.awssdk.services.eventbridgev2.model.DescribeEventSourceResponse;
import software.amazon.awssdk.services.eventbridgev2.model.DescribeSubscriberResponse;
import software.amazon.awssdk.services.eventbridgev2.model.EventSourceConfiguration;
import software.amazon.awssdk.services.eventbridgev2.model.EventSourceState;
import software.amazon.awssdk.services.eventbridgev2.model.InvalidInputException;
import software.amazon.awssdk.services.eventbridgev2.model.InvalidStateException;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceInUseException;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceNotFoundException;
import software.amazon.awssdk.services.eventbridgev2.model.RotateWebhookCredentialsResponse;
import software.amazon.awssdk.services.eventbridgev2.model.TransformerType;
import software.amazon.awssdk.services.eventbridgev2.model.WebhookAuthConfiguration;
import software.amazon.awssdk.services.eventbridgev2.model.WebhookEventsSourceConfiguration;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A POST to the URL of a webhook event source becomes an event on its bus, read here from the queues of subscribers. */
@QuarkusTest
class EventBridgeV2WebhookIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::000000000000:role/WebhookRole";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String HMAC_SECRET = "0123456789abcdef-hmac-key";

    @Inject
    Vertx vertx;

    @Test
    void eachAuthenticationTypeDeliversItsEventToTheWithMetadataAndRawSubscribers() throws Exception {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            Fixture fixture = fixture(events, sqs, suffix);
            Endpoint token = source(events, fixture.bus(), "token-" + suffix, webhook -> webhook
                    .metadataHttpHeaders("X-Request-Id").authConfiguration(staticToken("X-Token", "s3cret-token")));
            Endpoint basic = source(events, fixture.bus(), "basic-" + suffix, webhook -> webhook
                    .authConfiguration(auth -> auth.basicHttpAuthConfiguration(
                            credential -> credential.username("alice").password("p4ssword"))));
            Endpoint hmac = source(events, fixture.bus(), "hmac-" + suffix, webhook -> webhook
                    .authConfiguration(auth -> auth.hmacAuthConfiguration(signing -> signing.algorithm("SHA256")
                            .encoding("HEX").signatureHttpHeader("X-Sig").signaturePrefix("sha256=")
                            .signedMessageComposition("BODY").secretValue(HMAC_SECRET))));

            String tokenBody = "{\"n\":1,\"f\":1e2}";
            String basicBody = "{\"n\":2}";
            String hmacBody = " {\"n\":3, \"s\":\"\\u00e9\\/\"}\n";
            post(token, tokenBody, "X-Token", "s3cret-token", "X-Request-Id", "r-1").then().statusCode(200)
                    .body(equalTo("OK"));
            post(basic, basicBody, "Authorization", "Basic "
                    + Base64.getEncoder().encodeToString("alice:p4ssword".getBytes(StandardCharsets.UTF_8)))
                    .then().statusCode(200).body(equalTo("OK"));
            post(hmac, hmacBody, "X-Sig", "sha256=" + sign(HMAC_SECRET, hmacBody)).then().statusCode(200)
                    .body(equalTo("OK"));

            List<ObjectNode> withMetadata = received(sqs, fixture.metadataQueue(), 3);
            assertEquals(3, withMetadata.size());
            assertEquals("{\"Data\":{\"n\":1,\"f\":100.0},\"Metadata\":{\"x-request-id\":\"r-1\"},"
                    + system("1"), normalized(withMetadata.get(0)));
            assertEquals("{\"Data\":{\"n\":2},\"Metadata\":{}," + system("2"), normalized(withMetadata.get(1)));
            assertEquals("{\"Data\":{\"n\":3,\"s\":\"\u00e9/\"},\"Metadata\":{}," + system("3"),
                    normalized(withMetadata.get(2)));

            List<String> raw = rawBodies(sqs, fixture.rawQueue(), 3);
            assertEquals(List.of(tokenBody, basicBody, hmacBody), raw);

            fixture.cleanUp(events, sqs, token, basic, hmac);
        }
    }

    @Test
    void aRequestThatFailsIsAnsweredAsMeasuredAndPublishesNothing() throws Exception {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            Fixture fixture = fixture(events, sqs, suffix);
            Endpoint one = source(events, fixture.bus(), "one-" + suffix, webhook -> webhook
                    .authConfiguration(staticToken("X-Token", "one-token")));
            Endpoint two = source(events, fixture.bus(), "two-" + suffix, webhook -> webhook
                    .authConfiguration(staticToken("X-Token", "two-token")));

            expect(post(one, "{}", "X-Token", "wrong"), 403, "Forbidden");
            expect(post(one, "{}"), 403, "Forbidden");
            expect(post(one, "{}", "X-Token", "two-token"), 403, "Forbidden");
            expect(post(one, "{bad", "X-Token", "wrong"), 403, "Forbidden");

            expect(request(one.host(), one.path() + "?q=1", "{}", "X-Token", "one-token"), 200, "OK");
            expect(request(one.host(), one.path().substring(0, one.path().length() - 1), "{}", "X-Token", "one-token"),
                    404, "Not Found");
            expect(request(one.host(), one.path() + "x/", "{}", "X-Token", "one-token"), 404, "Not Found");
            expect(request(one.host(), one.path().replaceFirst(".$", "A/"), "{}", "X-Token", "one-token"), 404,
                    "Not Found");
            expect(request(one.host(), two.path(), "{}", "X-Token", "one-token"), 404, "Not Found");
            expect(request(two.host(), one.path(), "{}", "X-Token", "two-token"), 404, "Not Found");

            expect(given().header("Host", one.host()).header("X-Token", "one-token").when().get(one.path()), 400,
                    "Bad Request");
            expect(given().header("Host", one.host().toUpperCase()).when().get("/nope"), 400, "Bad Request");
            expect(post(one, "{bad", "X-Token", "one-token"), 400, "Bad Request");
            expect(post(one, "", "X-Token", "one-token"), 400, "Bad Request");
            expect(post(one, "{\"a\":1,\"a\":2}", "X-Token", "one-token"), 400, "Bad Request");
            String tooLarge = "{\"pad\":\"" + "a".repeat(1_048_576 - 9) + "\"}";
            assertEquals(1_048_577, tooLarge.length());
            expect(post(one, tooLarge, "X-Token", "one-token"), 400, "Bad Request");
            expect(post(one, tooLarge, "X-Token", "wrong"), 400, "Bad Request");
            String atLimit = "{\"pad\":\"" + "a".repeat(1_048_576 - 10) + "\"}";
            expect(post(one, atLimit, "X-Token", "one-token"), 200, "OK");

            // The queue refuses the 1 MiB message, so only the first request reaches it.
            assertEquals(1, messages(sqs, fixture.metadataQueue(), 2).size());
            events.deleteEventSource(request -> request.eventSourceArn(one.arn()));
            expect(post(one, "{}", "X-Token", "one-token"), 403, "Forbidden");
            expect(post(one, "{bad", "X-Token", "one-token"), 403, "Forbidden");
            assertEquals(0, messages(sqs, fixture.metadataQueue(), 1).size());

            fixture.cleanUp(events, sqs, two);
        }
    }

    @Test
    void contentBasedDeduplicationDropsARepeatAndKeepsTheHashAsTheId() throws Exception {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            Fixture fixture = fixture(events, sqs, suffix);
            Endpoint endpoint = source(events, fixture.bus(), "dedup-" + suffix, webhook -> webhook
                    .metadataHttpHeaders("X-Meta").deduplicationConfiguration(dedup -> dedup
                            .deduplicationType("CONTENT_BASED"))
                    .authConfiguration(staticToken("X-Token", "dedup-token")));

            expect(post(endpoint, "{\"dd\":1}", "X-Token", "dedup-token"), 200, "OK");
            expect(post(endpoint, "{\"dd\":1}", "X-Token", "dedup-token"), 200, "OK");
            expect(post(endpoint, "{ \"dd\" : 1 }", "X-Token", "dedup-token"), 200, "OK");
            expect(post(endpoint, "{\"dd\":1}", "X-Token", "dedup-token", "X-Meta", "m1"), 200, "OK");
            expect(post(endpoint, "{\"dd\":1}", "X-Token", "dedup-token", "X-Meta", "m1", "X-Other", "o"), 200, "OK");

            List<ObjectNode> delivered = received(sqs, fixture.metadataQueue(), 3);
            assertEquals(3, delivered.size());
            for (ObjectNode event : delivered) {
                JsonNode system = event.get("SystemMetadata");
                assertTrue(system.get("EventGroupId").asText().matches("[0-9a-f]{64}"), system.toString());
                assertEquals(system.get("EventGroupId"), system.get("DeduplicationId"));
            }
            assertEquals(List.of("1", "2", "3"), delivered.stream()
                    .map(event -> event.get("SystemMetadata").get("aws:SequenceNumber").asText()).toList());
            fixture.cleanUp(events, sqs, endpoint);
        }
    }

    @Test
    void aBadRequestClosesTheConnection() throws Exception {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            Fixture fixture = fixture(events, sqs, suffix);
            Endpoint endpoint = source(events, fixture.bus(), "close-" + suffix, webhook -> webhook
                    .authConfiguration(staticToken("X-Token", "close-token")));

            try (Socket socket = connect()) {
                send(socket, "GET " + endpoint.path() + " HTTP/1.1\r\nHost: " + endpoint.host() + "\r\n\r\n");
                // The read returns only once Floci closes the connection.
                String answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                assertTrue(answer.startsWith("HTTP/1.1 400 Bad Request\r\n"), answer);
                assertTrue(answer.contains("\r\nConnection: close\r\n"), answer);
                assertTrue(answer.endsWith("\r\n\r\nBad Request"), answer);
            }
            fixture.cleanUp(events, sqs, endpoint);
        }
    }

    @Test
    void aRequestThatExpectsContinueIsToldToSendItsBody() throws Exception {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            Fixture fixture = fixture(events, sqs, suffix);
            Endpoint endpoint = source(events, fixture.bus(), "expect-" + suffix, webhook -> webhook
                    .authConfiguration(staticToken("X-Token", "expect-token")));
            String body = "{\"n\":1}";

            try (Socket socket = connect()) {
                send(socket, "POST " + endpoint.path() + " HTTP/1.1\r\nHost: " + endpoint.host()
                        + "\r\nX-Token: expect-token\r\nContent-Length: " + body.length()
                        + "\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n");
                String interim = readHead(socket.getInputStream());
                assertTrue(interim.startsWith("HTTP/1.1 100 Continue\r\n"), interim);
                send(socket, body);
                String answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(answer.startsWith("HTTP/1.1 200 OK\r\n"), answer);
            }
            assertEquals(List.of(body), rawBodies(sqs, fixture.rawQueue(), 1));
            fixture.cleanUp(events, sqs, endpoint);
        }
    }

    @Test
    void aHeadRequestOverHttp2IsAnsweredWithoutABody() throws Exception {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            Fixture fixture = fixture(events, sqs, suffix);
            Endpoint endpoint = source(events, fixture.bus(), "head-" + suffix, webhook -> webhook
                    .authConfiguration(staticToken("X-Token", "head-token")));
            HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                    .setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
            try {
                RequestOptions options = new RequestOptions()
                        .setServer(SocketAddress.inetSocketAddress(RestAssured.port, "localhost"))
                        .setHost(URI.create("http://" + endpoint.host()).getHost()).setPort(RestAssured.port)
                        .setMethod(HttpMethod.HEAD).setURI(endpoint.path());
                String answer = client.request(options)
                        .compose(request -> request.send())
                        .compose(response -> response.body().map(body -> response.version() + " "
                                + response.statusCode() + " " + response.getHeader("Content-Length") + " "
                                + body.length()))
                        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

                assertEquals("HTTP_2 400 11 0", answer);
            } finally {
                client.close();
            }
            fixture.cleanUp(events, sqs, endpoint);
        }
    }

    @Test
    void rotatedCredentialsReplaceTheSecretAndTheReplacedOnesStayValidOnlyForTheLastRotation() {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SecretsManagerClient secrets = secrets()) {
            String bus = events.createEventBus(request -> request.name("v2-rot-" + suffix)).eventBusArn();
            CreateEventSourceResponse created = events.createEventSource(request -> request.name("rot-" + suffix)
                    .eventBusArn(bus).configuration(EventSourceConfiguration.fromWebhookEventsConfiguration(
                            configuration -> configuration.roleArn(ROLE)
                                    .authConfiguration(staticToken("X-Token", "original-token")))));
            URI url = URI.create(created.eventSourceDetails().webhookDetails().webhookUrl());
            Endpoint endpoint = new Endpoint(created.eventSourceArn(), url.getAuthority(), url.getPath());
            String secretArn = created.eventSourceDetails().webhookDetails().secretArn();

            RotateWebhookCredentialsResponse supplied = events.rotateWebhookCredentials(request -> request
                    .eventSourceArn(endpoint.arn()).clientToken("rotation-" + suffix)
                    .credential(credential -> credential.staticTokenSecretValue("second-token")));

            assertEquals(secretArn, supplied.secretArn());
            assertEquals("second-token", secretValue(secrets, secretArn, null));
            assertEquals("original-token", secretValue(secrets, secretArn, "AWSPREVIOUS"));
            expect(post(endpoint, "{}", "X-Token", "second-token"), 200, "OK");
            expect(post(endpoint, "{}", "X-Token", "original-token"), 200, "OK");

            RotateWebhookCredentialsResponse replay = events.rotateWebhookCredentials(request -> request
                    .eventSourceArn(endpoint.arn()).clientToken("rotation-" + suffix)
                    .credential(credential -> credential.staticTokenSecretValue("second-token")));
            assertEquals(secretArn, replay.secretArn());
            assertEquals("original-token", secretValue(secrets, secretArn, "AWSPREVIOUS"));

            events.rotateWebhookCredentials(request -> request.eventSourceArn(endpoint.arn()));
            String generated = secretValue(secrets, secretArn, null);
            assertEquals(44, generated.length());
            expect(post(endpoint, "{}", "X-Token", generated), 200, "OK");
            expect(post(endpoint, "{}", "X-Token", "second-token"), 200, "OK");
            expect(post(endpoint, "{}", "X-Token", "original-token"), 403, "Forbidden");

            InvalidInputException refused = assertThrows(InvalidInputException.class,
                    () -> events.rotateWebhookCredentials(request -> request.eventSourceArn(endpoint.arn())
                            .credential(credential -> credential.basicHttpPassword("second-pw"))));
            assertEquals("Credential must set StaticTokenSecretValue, because the authentication type of this "
                    + "EventSource is StaticTokenAuthConfiguration", refused.awsErrorDetails().errorMessage());

            events.deleteEventSource(request -> request.eventSourceArn(endpoint.arn()));
            events.deleteEventBus(request -> request.eventBusArn(bus));
        }
    }

    @Test
    void aRevokedSourceRefusesItsUrlAndCannotBeUpdatedOrRotatedButCanBeDescribedListedAndDeleted() {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            Fixture fixture = fixture(events, sqs, suffix);
            Endpoint endpoint = source(events, fixture.bus(), "revoked-" + suffix, webhook -> webhook
                    .authConfiguration(staticToken("X-Token", "s3cret-token")));
            expect(post(endpoint, "{}", "X-Token", "s3cret-token"), 200, "OK");

            assertEquals(endpoint.arn(), events.revokeResource(request -> request.arn(endpoint.arn())).arn());

            DescribeEventSourceResponse described = events.describeEventSource(request -> request
                    .eventSourceArn(endpoint.arn()));
            assertEquals(Boolean.TRUE, described.revoked());
            assertEquals(EventSourceState.ACTIVE, described.state());
            assertEquals(Boolean.TRUE, events.listEventSources(request -> request.eventBusArn(fixture.bus()))
                    .eventSources().getFirst().revoked());
            expect(post(endpoint, "{}", "X-Token", "s3cret-token"), 403, "Forbidden");
            expect(post(endpoint, "{}", "X-Token", "wrong"), 403, "Forbidden");
            expect(request(endpoint.host(), endpoint.path().substring(0, endpoint.path().length() - 1), "{}",
                    "X-Token", "s3cret-token"), 404, "Not Found");
            InvalidStateException update = assertThrows(InvalidStateException.class,
                    () -> events.updateEventSource(request -> request.eventSourceArn(endpoint.arn())
                            .description("changed")));
            assertEquals("EventSource is revoked and cannot be updated", update.awsErrorDetails().errorMessage());
            InvalidStateException rotate = assertThrows(InvalidStateException.class,
                    () -> events.rotateWebhookCredentials(request -> request.eventSourceArn(endpoint.arn())));
            assertEquals("EventSource is revoked and its credentials cannot be rotated",
                    rotate.awsErrorDetails().errorMessage());
            assertEquals(endpoint.arn(), events.revokeResource(request -> request.arn(endpoint.arn())).arn());

            fixture.cleanUp(events, sqs, endpoint);
        }
    }

    @Test
    void aRevokedSubscriberGetsNoMoreEventsWhileTheOtherSubscriberOfTheBusStillDoes() throws Exception {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            Fixture fixture = fixture(events, sqs, suffix);
            Endpoint endpoint = source(events, fixture.bus(), "live-" + suffix, webhook -> webhook
                    .authConfiguration(staticToken("X-Token", "s3cret-token")));

            events.revokeResource(request -> request.arn(fixture.rawSubscriber()));
            expect(post(endpoint, "{\"n\":1}", "X-Token", "s3cret-token"), 200, "OK");

            DescribeSubscriberResponse described = events.describeSubscriber(request -> request
                    .subscriberArn(fixture.rawSubscriber()));
            assertEquals(Boolean.TRUE, described.revoked());
            assertEquals(Boolean.TRUE, events.listSubscribers(request -> request.eventBusArn(fixture.bus()))
                    .subscribers().stream().filter(summary -> summary.subscriberArn().equals(fixture.rawSubscriber()))
                    .findFirst().orElseThrow().revoked());
            assertEquals(1, received(sqs, fixture.metadataQueue(), 1).size());
            assertTrue(messages(sqs, fixture.rawQueue(), 1).isEmpty());
            InvalidStateException update = assertThrows(InvalidStateException.class,
                    () -> events.updateSubscriber(request -> request.subscriberArn(fixture.rawSubscriber())
                            .description("changed")));
            assertEquals("Subscriber is revoked and cannot be updated", update.awsErrorDetails().errorMessage());

            fixture.cleanUp(events, sqs, endpoint);
        }
    }

    @Test
    void aBusWhoseOnlyResourcesAreRevokedIsDeletedAndTheyAreDescribedAfterItWithoutTheirConfiguration() {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            String queueUrl = sqs.createQueue(request -> request.queueName("v2-orphan-" + suffix)).queueUrl();
            String bus = events.createEventBus(request -> request.name("v2-orphan-" + suffix)).eventBusArn();
            String subscriber = events.createSubscriber(request -> request.name("orphan").eventBusArn(bus)
                    .invokeConfiguration(invoke -> invoke.targetArn(queueArn(sqs, queueUrl)).roleArn(ROLE)))
                    .subscriberArn();
            Endpoint endpoint = source(events, bus, "orphan-" + suffix, webhook -> webhook
                    .authConfiguration(staticToken("X-Token", "s3cret-token")));
            assertThrows(ResourceInUseException.class,
                    () -> events.deleteEventBus(request -> request.eventBusArn(bus)));

            events.revokeResource(request -> request.arn(subscriber));
            events.revokeResource(request -> request.arn(endpoint.arn()));
            events.deleteEventBus(request -> request.eventBusArn(bus));

            DescribeEventSourceResponse source = events.describeEventSource(request -> request
                    .eventSourceArn(endpoint.arn()));
            assertEquals(Boolean.TRUE, source.revoked());
            assertNull(source.configuration());
            DescribeSubscriberResponse described = events.describeSubscriber(request -> request
                    .subscriberArn(subscriber));
            assertEquals(Boolean.TRUE, described.revoked());
            assertNull(described.invokeConfiguration());
            assertNull(described.retryPolicy());
            assertNull(described.transformer());
            assertNull(described.batchConfiguration());
            assertEquals(queueArn(sqs, queueUrl), events.listSubscribers(request -> { }).subscribers().stream()
                    .filter(summary -> summary.subscriberArn().equals(subscriber)).findFirst().orElseThrow()
                    .targetArn());
            assertThrows(ResourceNotFoundException.class,
                    () -> events.listEventSources(request -> request.eventBusArn(bus)));

            events.deleteEventSource(request -> request.eventSourceArn(endpoint.arn()));
            events.deleteSubscriber(request -> request.subscriberArn(subscriber));
            sqs.deleteQueue(request -> request.queueUrl(queueUrl));
        }
    }

    // ──────────────────────────── Fixtures ────────────────────────────

    private record Fixture(String bus, String metadataQueue, String rawQueue, String metadataSubscriber,
                           String rawSubscriber) {
        void cleanUp(EventBridgeV2Client events, SqsClient sqs, Endpoint... endpoints) {
            for (Endpoint endpoint : endpoints) {
                events.deleteEventSource(request -> request.eventSourceArn(endpoint.arn()));
            }
            events.deleteSubscriber(request -> request.subscriberArn(metadataSubscriber));
            events.deleteSubscriber(request -> request.subscriberArn(rawSubscriber));
            events.deleteEventBus(request -> request.eventBusArn(bus));
            sqs.deleteQueue(request -> request.queueUrl(metadataQueue));
            sqs.deleteQueue(request -> request.queueUrl(rawQueue));
        }
    }

    private record Endpoint(String arn, String host, String path) {
    }

    private static Fixture fixture(EventBridgeV2Client events, SqsClient sqs, String suffix) {
        String metadataQueue = sqs.createQueue(request -> request.queueName("v2-hook-meta-" + suffix)).queueUrl();
        String rawQueue = sqs.createQueue(request -> request.queueName("v2-hook-raw-" + suffix)).queueUrl();
        String bus = events.createEventBus(request -> request.name("v2-hook-" + suffix)).eventBusArn();
        String metadataSubscriber = events.createSubscriber(request -> request.name("meta").eventBusArn(bus)
                .invokeConfiguration(invoke -> invoke.targetArn(queueArn(sqs, metadataQueue)).roleArn(ROLE))
                .transformer(transformer -> transformer.type(TransformerType.WITH_METADATA))).subscriberArn();
        String rawSubscriber = events.createSubscriber(request -> request.name("raw").eventBusArn(bus)
                .invokeConfiguration(invoke -> invoke.targetArn(queueArn(sqs, rawQueue)).roleArn(ROLE))
                .transformer(transformer -> transformer.type(TransformerType.RAW))).subscriberArn();
        return new Fixture(bus, metadataQueue, rawQueue, metadataSubscriber, rawSubscriber);
    }

    private static Endpoint source(EventBridgeV2Client events, String bus, String name,
                                   Consumer<WebhookEventsSourceConfiguration.Builder> webhook) {
        CreateEventSourceResponse created = events.createEventSource(request -> request.name(name).eventBusArn(bus)
                .configuration(EventSourceConfiguration.fromWebhookEventsConfiguration(
                        configuration -> configuration.roleArn(ROLE).applyMutation(webhook))));
        URI url = URI.create(created.eventSourceDetails().webhookDetails().webhookUrl());
        return new Endpoint(created.eventSourceArn(), url.getAuthority(), url.getPath());
    }

    private static WebhookAuthConfiguration staticToken(String header, String secret) {
        return WebhookAuthConfiguration.builder().staticTokenAuthConfiguration(
                token -> token.tokenHttpHeader(header).secretValue(secret)).build();
    }

    // ──────────────────────────── Requests and reads ────────────────────────────

    private static Response post(Endpoint endpoint, String body, String... headers) {
        return request(endpoint.host(), endpoint.path(), body, headers);
    }

    private static Response request(String host, String path, String body, String... headers) {
        RequestSpecification specification = given().header("Host", host)
                .contentType("application/json").body(body.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < headers.length; i += 2) {
            specification = specification.header(headers[i], headers[i + 1]);
        }
        return specification.when().post(path);
    }

    private static Socket connect() throws IOException {
        Socket socket = new Socket("localhost", RestAssured.port);
        // A connection Floci leaves open fails the read instead of leaving it waiting.
        socket.setSoTimeout(10_000);
        return socket;
    }

    private static void send(Socket socket, String text) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            int next = in.read();
            if (next < 0) {
                throw new IOException("The connection closed inside a response head: " + head);
            }
            head.append((char) next);
        }
        return head.toString();
    }

    private static void expect(Response response, int status, String text) {
        response.then().statusCode(status).body(equalTo(text))
                .header("Content-Type", "text/plain")
                .header("Content-Length", Integer.toString(text.length()))
                .header("x-amzn-RequestId", matchesPattern(UUID_PATTERN));
        assertEquals(status == 400 ? "close" : null, response.header("Connection"));
    }

    private static List<ObjectNode> received(SqsClient sqs, String queueUrl, int expected) throws Exception {
        List<ObjectNode> bodies = new ArrayList<>();
        for (Message message : messages(sqs, queueUrl, expected)) {
            bodies.add((ObjectNode) MAPPER.readTree(message.body()));
        }
        bodies.sort(Comparator.comparingLong(event -> Long.parseLong(
                event.get("SystemMetadata").get("aws:SequenceNumber").asText())));
        return bodies;
    }

    private static List<String> rawBodies(SqsClient sqs, String queueUrl, int expected) {
        return messages(sqs, queueUrl, expected).stream().map(Message::body)
                .sorted(Comparator.comparing(EventBridgeV2WebhookIntegrationTest::dataNumber)).toList();
    }

    private static int dataNumber(String body) {
        try {
            return MAPPER.readTree(body).path("n").asInt();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Message> messages(SqsClient sqs, String queueUrl, int expected) {
        List<Message> messages = new ArrayList<>();
        for (int attempt = 0; attempt < 3 && messages.size() < expected; attempt++) {
            messages.addAll(sqs.receiveMessage(request -> request.queueUrl(queueUrl).maxNumberOfMessages(10)
                    .waitTimeSeconds(1)).messages());
        }
        return messages;
    }

    /** The event with the random ids and the time replaced, after checking their shape. */
    private static String normalized(ObjectNode event) {
        ObjectNode system = (ObjectNode) event.get("SystemMetadata");
        assertTrue(system.get("EventGroupId").asText().matches(UUID_PATTERN), system.toString());
        assertEquals(system.get("EventGroupId"), system.get("DeduplicationId"));
        assertTrue(system.get("aws:IngestionTime").asText().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
        assertTrue(system.get("aws:EventId").asText().matches(UUID_PATTERN), system.toString());
        system.put("EventGroupId", "<id>");
        system.put("DeduplicationId", "<id>");
        system.put("aws:IngestionTime", "<time>");
        system.put("aws:EventId", "<id>");
        return event.toString();
    }

    private static String system(String sequenceNumber) {
        return "\"SystemMetadata\":{\"EventGroupId\":\"<id>\",\"DeduplicationId\":\"<id>\","
                + "\"ContentType\":\"application/json\",\"aws:IngestionTime\":\"<time>\",\"aws:EventId\":\"<id>\","
                + "\"aws:SequenceNumber\":\"" + sequenceNumber + "\",\"aws:DeliveryType\":\"LIVE\"}}";
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String queueArn(SqsClient sqs, String queueUrl) {
        return sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private static EventBridgeV2Client events() {
        return AwsTestClients.create(EventBridgeV2Client.builder(), "000000000000", Region.US_EAST_1);
    }

    private static SecretsManagerClient secrets() {
        return AwsTestClients.create(SecretsManagerClient.builder(), "000000000000", Region.US_EAST_1);
    }

    private static String secretValue(SecretsManagerClient secrets, String secretArn, String stage) {
        return secrets.getSecretValue(request -> request.secretId(secretArn).versionStage(stage)).secretString();
    }

    private static SqsClient sqs() {
        return AwsTestClients.create(SqsClient.builder(), "000000000000", Region.US_EAST_1);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
