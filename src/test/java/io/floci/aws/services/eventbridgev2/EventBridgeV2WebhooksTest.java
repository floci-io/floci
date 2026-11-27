package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.eventbridgev2.EventBridgeV2Webhooks.Answer;
import io.floci.aws.services.eventbridgev2.EventBridgeV2Webhooks.Request;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.eventbridgev2.model.SubscriberV2;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import io.floci.aws.services.secretsmanager.model.SecretVersion;
import io.floci.aws.testing.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventBridgeV2WebhooksTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::000000000000:role/Hook";
    private static final String QUEUE = "arn:aws:sqs:us-east-1:000000000000:orders";
    private static final String TOKEN_AUTH = "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Tok\","
            + "\"SecretValue\":\"s3cret\"}}";

    private final Map<String, String> secretStrings = new HashMap<>();
    private final List<String> deliveredBodies = new ArrayList<>();
    private final List<PublishedEvent> delivered = new ArrayList<>();
    private EventBridgeV2EventSources sources;
    private EventBridgeV2Service service;
    private EventBridgeV2Webhooks webhooks;
    private ManualClock clock;
    private String bus;

    @BeforeEach
    void setUp() {
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        when(secrets.createSecret(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            Secret secret = new Secret();
            secret.setArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:" + call.getArgument(0) + "-AbCdEf");
            secretStrings.put(secret.getArn(), call.getArgument(1));
            return secret;
        });
        when(secrets.getSecretValue(any(), any(), any(), eq(REGION))).thenAnswer(call -> {
            SecretVersion version = new SecretVersion();
            version.setSecretString(secretStrings.get(call.<String>getArgument(0)));
            return version;
        });
        when(secrets.describeSecret(any(), any())).thenReturn(new Secret());
        EventBridgeV2Delivery delivery = mock(EventBridgeV2Delivery.class);
        doAnswer(call -> {
            SubscriberV2 subscriber = call.getArgument(0);
            PublishedEvent event = call.getArgument(1);
            delivered.add(event);
            deliveredBodies.add(subscriber.getName() + "=" + EventBridgeV2Delivery.body(subscriber, event));
            return null;
        }).when(delivery).deliver(any(), any());
        clock = new ManualClock(Instant.parse("2026-11-25T06:00:00.123Z"));
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        sources = new EventBridgeV2EventSources(
                AccountAwareStorageBackend.inMemory(ACCOUNT), secrets, regionResolver, clock, "http://floci.test:4566",
                mock(TlsCertificateManager.class));
        service = new EventBridgeV2Service(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), regionResolver, delivery, sources, clock, true);
        webhooks = new EventBridgeV2Webhooks(service, sources, secrets, clock);
        bus = service.createEventBus(json("{\"Name\":\"orders\"}"), REGION).getArn();
        subscriber("meta", "{\"Type\":\"WITH_METADATA\"}", "RUNNING");
        subscriber("raw", "{\"Type\":\"RAW\"}", "RUNNING");
    }

    // ──────────────────────────── Order of checks ────────────────────────────

    @Test
    void aMethodOtherThanPostIsBadRequestEvenOnAnUnknownUrl() {
        Target target = source("hook", TOKEN_AUTH, "");

        assertEquals(EventBridgeV2Webhooks.BAD_REQUEST, answer("GET", target, target.path(), Map.of(), "{}"));
        assertEquals(EventBridgeV2Webhooks.BAD_REQUEST, answer("PUT", target, target.path(), Map.of(), "{}"));
        assertEquals(EventBridgeV2Webhooks.BAD_REQUEST, answer("GET", target, "/nope", Map.of(), "{}"));
        assertEquals(EventBridgeV2Webhooks.BAD_REQUEST, webhooks.receive(
                new Request("DELETE", "a" + "b".repeat(37), "/", Map.of(), new byte[0], false)));
    }

    @Test
    void aUrlThatIsNotTheSourcesIsNotFoundWhateverTheCredential() {
        Target one = source("one", TOKEN_AUTH, "");
        Target two = source("two", TOKEN_AUTH, "");
        Map<String, String> good = Map.of("x-tok", "s3cret");

        assertEquals(EventBridgeV2Webhooks.NOT_FOUND, answer("POST", one, one.path().substring(0, 60) + "/", good, "{}"));
        assertEquals(EventBridgeV2Webhooks.NOT_FOUND,
                answer("POST", one, one.path().substring(0, one.path().length() - 1), good, "{}"));
        assertEquals(EventBridgeV2Webhooks.NOT_FOUND, answer("POST", one, one.path() + "extra/", good, "{}"));
        assertEquals(EventBridgeV2Webhooks.NOT_FOUND, answer("POST", one, "/", good, "{}"));
        assertEquals(EventBridgeV2Webhooks.NOT_FOUND, answer("POST", one, "/k1" + "A".repeat(64) + "/", good, "{}"));
        assertEquals(EventBridgeV2Webhooks.NOT_FOUND, answer("POST", one, two.path(), good, "{}"));
        assertEquals(EventBridgeV2Webhooks.NOT_FOUND, answer("POST", two, one.path(), good, "{}"));
        assertEquals(EventBridgeV2Webhooks.NOT_FOUND, webhooks.receive(new Request("POST", "a" + "b".repeat(37),
                one.path(), good, new byte[0], false)));
        assertTrue(deliveredBodies.isEmpty());
    }

    @Test
    void aBodyOverTheLimitIsBadRequestEvenWithABadCredential() {
        Target target = source("hook", TOKEN_AUTH, "");

        assertEquals(EventBridgeV2Webhooks.BAD_REQUEST,
                webhooks.receive(new Request("POST", target.hostId(), target.path(), Map.of("x-tok", "wrong"),
                        new byte[0], true)));
        assertEquals(EventBridgeV2Webhooks.BAD_REQUEST,
                webhooks.receive(new Request("POST", target.hostId(), target.path(), Map.of("x-tok", "s3cret"),
                        new byte[0], true)));
    }

    @Test
    void aBodyOfExactlyTheLimitIsAnEventAndTheLimitIs1048576() {
        assertEquals(1_048_576, WebhookEvents.MAX_BODY_BYTES);
        Target target = source("hook", TOKEN_AUTH, "");
        String body = "{\"pad\":\"" + "a".repeat(1_048_576 - 10) + "\"}";
        assertEquals(1_048_576, body.length());

        assertEquals(EventBridgeV2Webhooks.OK, answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), body));
        assertEquals(2, deliveredBodies.size());
    }

    @Test
    void aBadCredentialIsForbiddenBeforeTheBodyIsRead() {
        Target target = source("hook", TOKEN_AUTH, "");

        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, answer("POST", target, target.path(), Map.of("x-tok", "no"), "{bad"));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, answer("POST", target, target.path(), Map.of(), ""));
        assertEquals(EventBridgeV2Webhooks.BAD_REQUEST,
                answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{bad"));
        assertEquals(EventBridgeV2Webhooks.BAD_REQUEST,
                answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), ""));
        assertTrue(deliveredBodies.isEmpty());
    }

    @Test
    void aSourceThatWasDeletedAnswersForbiddenAtItsFormerUrl() {
        Target target = source("hook", TOKEN_AUTH, "");
        service.deleteEventSource(json("{\"EventSourceArn\":\"" + target.source().getArn() + "\"}"), REGION);

        assertEquals(EventBridgeV2Webhooks.FORBIDDEN,
                answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{}"));
    }

    @Test
    void theAnswersAreTheMeasuredStatusesAndTexts() {
        assertEquals(new Answer(200, "OK"), EventBridgeV2Webhooks.OK);
        assertEquals(new Answer(403, "Forbidden"), EventBridgeV2Webhooks.FORBIDDEN);
        assertEquals(new Answer(400, "Bad Request"), EventBridgeV2Webhooks.BAD_REQUEST);
        assertEquals(new Answer(404, "Not Found"), EventBridgeV2Webhooks.NOT_FOUND);
        assertTrue(EventBridgeV2Webhooks.BAD_REQUEST.closesConnection());
        assertFalse(EventBridgeV2Webhooks.OK.closesConnection());
        assertFalse(EventBridgeV2Webhooks.FORBIDDEN.closesConnection());
        assertFalse(EventBridgeV2Webhooks.NOT_FOUND.closesConnection());
    }

    // ──────────────────────────── Events ────────────────────────────

    @Test
    void aValidRequestIsDeliveredToEachRunningSubscriberAsTheirTransformerSays() throws Exception {
        Target target = source("hook", TOKEN_AUTH, ",\"MetadataHttpHeaders\":[\"X-Meta\",\"X-Missing\"]");
        String body = " {\"e\":1e2, \"s\":\"\\/\"}\n";

        assertEquals(EventBridgeV2Webhooks.OK, answer("POST", target, target.path(),
                Map.of("x-tok", "s3cret", "x-meta", "m1", "x-unlisted", "u"), body));

        assertEquals(2, deliveredBodies.size());
        JsonNode meta = MAPPER.readTree(bodyOf("meta"));
        assertEquals("{\"e\":100.0,\"s\":\"/\"}", meta.get("Data").toString());
        assertEquals("{\"x-meta\":\"m1\"}", meta.get("Metadata").toString());
        JsonNode system = meta.get("SystemMetadata");
        assertEquals(List.of("EventGroupId", "DeduplicationId", "ContentType", "aws:IngestionTime", "aws:EventId",
                "aws:SequenceNumber", "aws:DeliveryType"), system.properties().stream().map(Map.Entry::getKey).toList());
        assertEquals(system.get("EventGroupId"), system.get("DeduplicationId"));
        assertTrue(system.get("EventGroupId").asText().matches("[0-9a-f-]{36}"));
        assertEquals("application/json", system.get("ContentType").asText());
        assertEquals("2026-11-25T06:00:00.123Z", system.get("aws:IngestionTime").asText());
        assertEquals("1", system.get("aws:SequenceNumber").asText());
        assertEquals("LIVE", system.get("aws:DeliveryType").asText());
        assertEquals(body, bodyOf("raw"));
    }

    @Test
    void eachEventTakesTheBusNextSequenceNumberInOrderWithPutEvents() {
        Target target = source("hook", TOKEN_AUTH, "");
        service.putEvents(json("{\"EventBusArn\":\"" + bus + "\",\"Entries\":[{\"Source\":\"a\","
                + "\"DetailType\":\"t\"}]}"), REGION);
        deliveredBodies.clear();
        delivered.clear();

        answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{\"n\":1}");
        answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{\"n\":2}");

        assertEquals(List.of("2", "2", "3", "3"), delivered.stream()
                .map(event -> event.systemMetadata().get("aws:SequenceNumber").asText()).toList());
    }

    @Test
    void aStoppedSubscriberGetsNothing() {
        subscriber("stopped", "{\"Type\":\"RAW\"}", "STOPPED");
        Target target = source("hook", TOKEN_AUTH, "");

        answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{}");

        assertEquals(2, deliveredBodies.size());
        assertTrue(deliveredBodies.stream().noneMatch(delivery -> delivery.startsWith("stopped=")));
    }

    @Test
    void aSubscriberWhoseFilterDoesNotMatchGetsNothing() {
        subscriber("big", "{\"Type\":\"RAW\"}", "RUNNING", ",\"FilterConfiguration\":{\"Filters\":[{\"Scope\":\"DATA\","
                + "\"Pattern\":\"{\\\"total\\\":[{\\\"numeric\\\":[\\\">\\\",500]}]}\"}]}");
        Target target = source("hook", TOKEN_AUTH, "");

        answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{\"total\":900}");
        answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{\"total\":20}");

        assertEquals(1, deliveredBodies.stream().filter(delivery -> delivery.startsWith("big=")).count());
        assertEquals(4, deliveredBodies.stream().filter(delivery -> !delivery.startsWith("big=")).count());
    }

    @Test
    void anEventWithoutDeduplicationHasAFreshRandomIdEachTime() {
        Target target = source("hook", TOKEN_AUTH, "");

        answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{}");
        answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{}");

        assertEquals(4, delivered.size());
        assertNotEquals(delivered.get(0).systemMetadata().get("EventGroupId"),
                delivered.get(2).systemMetadata().get("EventGroupId"));
    }

    // ──────────────────────────── Authentication types ────────────────────────────

    @Test
    void basicIsCheckedAgainstTheUsernameAndPasswordOfTheSecret() {
        Target target = source("basic", "{\"BasicHttpAuthConfiguration\":{\"Username\":\"alice\","
                + "\"Password\":\"pw1234\"}}", "");
        String good = "Basic " + Base64.getEncoder().encodeToString("alice:pw1234".getBytes(StandardCharsets.UTF_8));

        assertEquals(EventBridgeV2Webhooks.OK, answer("POST", target, target.path(), Map.of("authorization", good), "{}"));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN,
                answer("POST", target, target.path(), Map.of("authorization", good + "x"), "{}"));
    }

    @Test
    void hmacIsCheckedOverTheRawBodyBytes() throws Exception {
        Target target = source("hmac", "{\"HmacAuthConfiguration\":{\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\","
                + "\"SignatureHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\","
                + "\"SecretValue\":\"0123456789abcdef\"}}", "");
        String body = "{ \"a\" : 1 }";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        assertEquals(EventBridgeV2Webhooks.OK, answer("POST", target, target.path(), Map.of("x-sig", signature), body));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN,
                answer("POST", target, target.path(), Map.of("x-sig", signature), "{\"a\":1}"));
    }

    // ──────────────────────────── Revoked sources and subscribers ────────────────────────────

    @Test
    void aRevokedStaticTokenSourceAnswersForbiddenForTheRightAndAWrongToken() {
        Target target = source("hook", TOKEN_AUTH, "");
        revoke(target.source().getArn());

        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{}"));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, answer("POST", target, target.path(), Map.of("x-tok", "no"), "{}"));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, answer("POST", target, target.path(), Map.of(), "{bad"));
        assertTrue(deliveredBodies.isEmpty());
    }

    @Test
    void aRevokedBasicSourceAnswersForbiddenForTheRightAndAWrongCredential() {
        Target target = source("basic", "{\"BasicHttpAuthConfiguration\":{\"Username\":\"alice\","
                + "\"Password\":\"pw1234\"}}", "");
        String good = "Basic " + Base64.getEncoder().encodeToString("alice:pw1234".getBytes(StandardCharsets.UTF_8));
        revoke(target.source().getArn());

        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, answer("POST", target, target.path(), Map.of("authorization", good), "{}"));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN,
                answer("POST", target, target.path(), Map.of("authorization", good + "x"), "{}"));
        assertTrue(deliveredBodies.isEmpty());
    }

    @Test
    void aRevokedHmacSourceAnswersForbiddenForTheRightAndAWrongSignature() throws Exception {
        Target target = source("hmac", "{\"HmacAuthConfiguration\":{\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\","
                + "\"SignatureHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\","
                + "\"SecretValue\":\"0123456789abcdef\"}}", "");
        String body = "{\"a\":1}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        revoke(target.source().getArn());

        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, answer("POST", target, target.path(), Map.of("x-sig", signature), body));
        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, answer("POST", target, target.path(), Map.of("x-sig", "00"), body));
        assertTrue(deliveredBodies.isEmpty());
    }

    @Test
    void aRevokedSourceKeepsTheEarlierChecksOfTheDataPlane() {
        Target target = source("hook", TOKEN_AUTH, "");
        revoke(target.source().getArn());
        Map<String, String> good = Map.of("x-tok", "s3cret");

        assertEquals(EventBridgeV2Webhooks.BAD_REQUEST, answer("GET", target, target.path(), good, "{}"));
        assertEquals(EventBridgeV2Webhooks.NOT_FOUND,
                answer("POST", target, target.path().substring(0, target.path().length() - 1), good, "{}"));
        assertEquals(EventBridgeV2Webhooks.NOT_FOUND, answer("POST", target, target.path() + "extra/", good, "{}"));
        assertEquals(EventBridgeV2Webhooks.BAD_REQUEST, webhooks.receive(new Request("POST", target.hostId(),
                target.path(), good, new byte[0], true)));
    }

    @Test
    void aRevokedSubscriberGetsNothingAndTheOthersOfTheBusStillDo() {
        Target target = source("hook", TOKEN_AUTH, "");
        String metaArn = service.listSubscribers(json("{\"EventBusArn\":\"" + bus + "\"}"), REGION).items().stream()
                .filter(subscriber -> subscriber.getName().equals("meta")).findFirst().orElseThrow().getArn();
        revoke(metaArn);

        assertEquals(EventBridgeV2Webhooks.OK, answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), "{}"));

        assertEquals(1, deliveredBodies.size());
        assertTrue(deliveredBodies.getFirst().startsWith("raw="));
    }

    // ──────────────────────────── Deduplication ────────────────────────────

    private static final String DEDUP = ",\"DeduplicationConfiguration\":{\"DeduplicationType\":\"CONTENT_BASED\"}";

    @Test
    void aRepeatedRequestAnswersOkAndPublishesNothing() {
        Target target = source("hook", TOKEN_AUTH, DEDUP);
        Map<String, String> headers = Map.of("x-tok", "s3cret");

        assertEquals(EventBridgeV2Webhooks.OK, answer("POST", target, target.path(), headers, "{\"dd\":1}"));
        assertEquals(2, delivered.size());
        assertEquals(EventBridgeV2Webhooks.OK, answer("POST", target, target.path(), headers, "{\"dd\":1}"));
        assertEquals(2, delivered.size());
        JsonNode system = delivered.getFirst().systemMetadata();
        assertTrue(system.get("EventGroupId").asText().matches("[0-9a-f]{64}"));
        assertEquals(system.get("EventGroupId"), system.get("DeduplicationId"));
    }

    @Test
    void anotherSpacingOfTheSameValueIsAnotherEvent() {
        Target target = source("hook", TOKEN_AUTH, DEDUP);
        Map<String, String> headers = Map.of("x-tok", "s3cret");

        answer("POST", target, target.path(), headers, "{\"dd\":1}");
        answer("POST", target, target.path(), headers, "{ \"dd\" : 1 }");

        assertEquals(4, delivered.size());
        assertNotEquals(delivered.get(0).systemMetadata().get("EventGroupId"),
                delivered.get(2).systemMetadata().get("EventGroupId"));
    }

    @Test
    void anotherSourceOnTheSameBusGetsItsOwnEventAndTheSameId() {
        Target one = source("one", TOKEN_AUTH, DEDUP);
        Target two = source("two", TOKEN_AUTH, DEDUP);
        Map<String, String> headers = Map.of("x-tok", "s3cret");

        answer("POST", one, one.path(), headers, "{\"dd\":1}");
        answer("POST", two, two.path(), headers, "{\"dd\":1}");

        assertEquals(4, delivered.size());
        assertEquals(delivered.get(0).systemMetadata().get("EventGroupId"),
                delivered.get(2).systemMetadata().get("EventGroupId"));
    }

    @Test
    void theCopiedMetadataCountsButTheOrderTheHeadersArriveInAndAnUnlistedHeaderDoNot() {
        Target target = source("hook", TOKEN_AUTH, DEDUP.concat(",\"MetadataHttpHeaders\":[\"X-Meta\",\"X-Two\"]"));
        Map<String, String> base = Map.of("x-tok", "s3cret");

        answer("POST", target, target.path(), base, "{\"dd\":2}");
        assertEquals(2, delivered.size());
        answer("POST", target, target.path(), Map.of("x-tok", "s3cret", "x-meta", "m1"), "{\"dd\":2}");
        assertEquals(4, delivered.size());
        answer("POST", target, target.path(), inOrder("x-tok", "s3cret", "x-meta", "m1", "x-two", "t"), "{\"dd\":2}");
        assertEquals(6, delivered.size());
        answer("POST", target, target.path(), inOrder("x-two", "t", "x-meta", "m1", "x-tok", "s3cret"), "{\"dd\":2}");
        assertEquals(6, delivered.size());
        answer("POST", target, target.path(),
                Map.of("x-tok", "s3cret", "x-meta", "m1", "x-two", "t", "x-other", "o"), "{\"dd\":2}");
        assertEquals(6, delivered.size());
        answer("POST", target, target.path(), Map.of("x-tok", "s3cret", "x-meta", "m2", "x-two", "t"), "{\"dd\":2}");
        assertEquals(8, delivered.size());
    }

    @Test
    void aRepeatAfterFiveMinutesIsDeliveredAgain() {
        Target target = source("hook", TOKEN_AUTH, DEDUP);
        Map<String, String> headers = Map.of("x-tok", "s3cret");

        answer("POST", target, target.path(), headers, "{\"dd\":1}");
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        answer("POST", target, target.path(), headers, "{\"dd\":1}");
        assertEquals(2, delivered.size());
        clock.advance(Duration.ofSeconds(1));
        answer("POST", target, target.path(), headers, "{\"dd\":1}");
        assertEquals(4, delivered.size());
    }

    @Test
    void aDroppedRepeatTakesNoSequenceNumber() {
        Target target = source("hook", TOKEN_AUTH, DEDUP);
        Map<String, String> headers = Map.of("x-tok", "s3cret");

        answer("POST", target, target.path(), headers, "{\"dd\":1}");
        answer("POST", target, target.path(), headers, "{\"dd\":1}");
        answer("POST", target, target.path(), headers, "{\"dd\":2}");

        assertEquals(List.of("1", "1", "2", "2"), delivered.stream()
                .map(event -> event.systemMetadata().get("aws:SequenceNumber").asText()).toList());
    }

    @Test
    void aRequestWhosePublishFailsIsNotKeptAsSeen() {
        Target target = source("hook", TOKEN_AUTH, DEDUP);
        String body = "{\"dd\":1}";
        service.clear();

        assertThrows(AwsException.class,
                () -> answer("POST", target, target.path(), Map.of("x-tok", "s3cret"), body));

        assertTrue(sources.firstSeen(target.source(),
                WebhookEvents.contentHash(body.getBytes(StandardCharsets.UTF_8), Map.of())));
    }

    @Test
    void deletingASourceAndResettingForgetTheHashes() {
        Target target = source("hook", TOKEN_AUTH, DEDUP);

        assertTrue(sources.firstSeen(target.source(), "h1"));
        assertFalse(sources.firstSeen(target.source(), "h1"));
        service.deleteEventSource(json("{\"EventSourceArn\":\"" + target.source().getArn() + "\"}"), REGION);
        assertTrue(sources.firstSeen(target.source(), "h1"));
        sources.clear();
        assertTrue(sources.firstSeen(target.source(), "h1"));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private record Target(EventSourceV2 source, String hostId, String path) {
    }

    private Target source(String name, String auth, String extra) {
        EventSourceV2 source = service.createEventSource(json("{\"ClientToken\":\"" + name + "-token\",\"Name\":\""
                + name + "\",\"EventBusArn\":\"" + bus + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                + "{\"AuthConfiguration\":" + auth + ",\"RoleArn\":\"" + ROLE + "\"" + extra + "}}}"), REGION);
        URI url = URI.create(service.webhookUrl(source));
        return new Target(source, source.getWebhookHostId(), url.getPath());
    }

    private void subscriber(String name, String transformer, String state, String... extra) {
        service.createSubscriber(json("{\"Name\":\"" + name + "\",\"EventBusArn\":\"" + bus + "\",\"State\":\"" + state
                + "\",\"InvokeConfiguration\":{\"TargetArn\":\"" + QUEUE + "\",\"RoleArn\":\"" + ROLE
                + "\"},\"Transformer\":" + transformer + String.join("", extra) + "}"), REGION);
    }

    private void revoke(String arn) {
        service.revokeResource(json("{\"Arn\":\"" + arn + "\"}"), REGION);
    }

    private Answer answer(String method, Target target, String path, Map<String, String> headers, String body) {
        return webhooks.receive(new Request(method, target.hostId(), path, headers,
                body.getBytes(StandardCharsets.UTF_8), false));
    }

    private static Map<String, String> inOrder(String... namesAndValues) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return headers;
    }

    private String bodyOf(String subscriber) {
        return deliveredBodies.stream().filter(delivery -> delivery.startsWith(subscriber + "="))
                .map(delivery -> delivery.substring(subscriber.length() + 1)).findFirst().orElseThrow();
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(value, e);
        }
    }
}
