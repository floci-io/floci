package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridgev2.EventBridgeV2Webhooks.Request;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.eventbridgev2.model.SubscriberV2;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import io.floci.aws.testing.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.floci.aws.services.eventbridgev2.WebhookSourceFixture.HMAC_AUTH;
import static io.floci.aws.services.eventbridgev2.WebhookSourceFixture.MAPPER;
import static io.floci.aws.services.eventbridgev2.WebhookSourceFixture.NOW;
import static io.floci.aws.services.eventbridgev2.WebhookSourceFixture.REGION;
import static io.floci.aws.services.eventbridgev2.WebhookSourceFixture.ROLE;
import static io.floci.aws.services.eventbridgev2.WebhookSourceFixture.STATIC_AUTH;
import static io.floci.aws.services.eventbridgev2.WebhookSourceFixture.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RevokeResource against a real Secrets Manager service, with the messages, statuses and the order of checks as
 * measured on AWS.
 */
class EventBridgeV2RevokeResourceTest {
    private static final String QUEUE = "arn:aws:sqs:us-east-1:000000000000:orders";
    private static final String ID = "abcdefghijklmnopqrstuvwxy";
    private static final String ARN_PATTERN = "arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):"
            + "(subscriber|event-sourcev2\\/(aws\\.service|aws\\.partner|webhook))"
            + "\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}";
    private static final String SOURCE_MISSING_ID =
            "arn:aws:events:us-east-1:000000000000:event-sourcev2/webhook/hook/" + ID;
    private static final String UPDATE_MESSAGE = "EventSource is revoked and cannot be updated";
    private static final String ROTATE_MESSAGE = "EventSource is revoked and its credentials cannot be rotated";
    private static final String SUBSCRIBER_UPDATE_MESSAGE = "Subscriber is revoked and cannot be updated";
    private static final String IN_USE_SOURCES = "The event bus cannot be deleted because it has one or more event "
            + "sources that are not revoked. Delete or revoke them and retry.";
    private static final String IN_USE_BOTH = "The event bus cannot be deleted because it has subscribers and event "
            + "sources that are not revoked. Delete or revoke them and retry.";

    private final List<String> delivered = new ArrayList<>();
    private WebhookSourceFixture fixture;
    private SecretsManagerService secrets;
    private EventBridgeV2Service service;
    private EventBridgeV2Handler handler;
    private EventBridgeV2Webhooks webhooks;
    private ManualClock clock;
    private String bus;
    private int tokens;

    @BeforeEach
    void setUp() {
        EventBridgeV2Delivery delivery = mock(EventBridgeV2Delivery.class);
        doAnswer(call -> delivered.add(call.<SubscriberV2>getArgument(0).getName())).when(delivery)
                .deliver(any(), any());
        fixture = new WebhookSourceFixture(delivery);
        secrets = fixture.secrets;
        service = fixture.service;
        webhooks = fixture.webhooks;
        clock = fixture.clock;
        bus = fixture.bus;
        handler = new EventBridgeV2Handler(service, MAPPER);
    }

    // ──────────────────────────── Order of checks ────────────────────────────

    @Test
    void theArnIsAnsweredBackForASourceAndForASubscriber() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        SubscriberV2 subscriber = subscriber("keep", bus);

        assertEquals(source.getArn(), revoke(source.getArn()));
        assertEquals(subscriber.getArn(), revoke(subscriber.getArn()));
        ObjectNode answer = (ObjectNode) handler.handle("RevokeResource", json("{\"Arn\":\"" + source.getArn() + "\"}"),
                REGION).getEntity();
        assertEquals(Set.of("Arn"), fieldNames(answer));
        assertEquals(source.getArn(), answer.get("Arn").textValue());
    }

    @Test
    void constraintsComeBeforeTheRegionCheck() {
        assertRefused(400, "InvalidInputException", "1 validation error detected: Value 'arn:aws:events:"
                + "eu-west-1:000000000000:event-busv2/orders/" + ID + "' at 'arn' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: " + ARN_PATTERN,
                "{\"Arn\":\"arn:aws:events:eu-west-1:000000000000:event-busv2/orders/" + ID + "\"}");
    }

    @Test
    void anEmptyArnFailsLengthBeforePattern() {
        assertRefused(400, "InvalidInputException", SmithyValidationErrors.message(List.of(
                "Value '' at 'arn' failed to satisfy constraint: Member must have length greater than or equal to 1",
                "Value '' at 'arn' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                        + ARN_PATTERN)), "{\"Arn\":\"\"}");
    }

    @Test
    void aMissingArnIsRefusedAsNull() {
        assertRefused(400, "InvalidInputException",
                "1 validation error detected: Value null at 'arn' failed to satisfy constraint: Member must not be null",
                "{}");
    }

    @Test
    void theRegionCheckComesBeforeTheAccountCheck() {
        String message = "The region or partition in Arn does not match this endpoint";
        assertRefused(400, "InvalidInputException", message, "{\"Arn\":\"arn:aws:events:eu-west-1:111111111111:"
                + "event-sourcev2/webhook/hook/" + ID + "\"}");
        assertRefused(400, "InvalidInputException", message, "{\"Arn\":\"arn:aws:events:eu-west-1:111111111111:"
                + "subscriber/keep/" + ID + "\"}");
        assertRefused(400, "InvalidInputException", message, "{\"Arn\":\"arn:aws-us-gov:events:us-east-1:"
                + "111111111111:event-sourcev2/webhook/hook/" + ID + "\"}");
    }

    @Test
    void theAccountCheckComesBeforeTheLookup() {
        assertRefused(403, "AccessDeniedException", "Access denied.", "{\"Arn\":\"arn:aws:events:us-east-1:"
                + "111111111111:event-sourcev2/webhook/hook/" + ID + "\"}");
        assertRefused(403, "AccessDeniedException", "Access denied.", "{\"Arn\":\"arn:aws:events:us-east-1:"
                + "111111111111:subscriber/keep/" + ID + "\"}");
        assertRefused(403, "AccessDeniedException", "Access denied.", "{\"Arn\":\"arn:aws:events:us-east-1:"
                + "111111111111:event-sourcev2/aws.service/hook/" + ID + "\"}");
    }

    @Test
    void aWebhookSourceThatDoesNotExistIsAccessDeniedNotNotFound() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        String wrongId = source.getArn().substring(0, source.getArn().length() - 5) + "aaaaa";

        assertRefused(403, "AccessDeniedException", "Access denied.", "{\"Arn\":\"arn:aws:events:us-east-1:"
                + "000000000000:event-sourcev2/webhook/nope/" + ID + "\"}");
        assertRefused(403, "AccessDeniedException", "Access denied.", "{\"Arn\":\"" + wrongId + "\"}");
        assertFalse(describe(source).has("Revoked"));
    }

    @Test
    void aSubscriberThatDoesNotExistIsAccessDenied() {
        SubscriberV2 subscriber = subscriber("keep", bus);
        String wrongId = subscriber.getArn().substring(0, subscriber.getArn().length() - 5) + "aaaaa";

        assertRefused(403, "AccessDeniedException", "Access denied.", "{\"Arn\":\"arn:aws:events:us-east-1:"
                + "000000000000:subscriber/nope/" + ID + "\"}");
        assertRefused(403, "AccessDeniedException", "Access denied.", "{\"Arn\":\"" + wrongId + "\"}");
        assertFalse(subscriber(subscriber.getArn()).isRevoked());
    }

    @Test
    void anAwsServiceOrPartnerSourceArnIsNotFound() {
        for (String type : List.of("aws.service", "aws.partner")) {
            String arn = "arn:aws:events:us-east-1:000000000000:event-sourcev2/" + type + "/rv-src/" + ID;
            assertRefused(404, "ResourceNotFoundException", "EventSource not found: " + arn,
                    "{\"Arn\":\"" + arn + "\"}");
        }
    }

    // ──────────────────────────── A revoked source ────────────────────────────

    @Test
    void aRevokedSourceStaysActiveAndMovesItsModifiedTimeToTheRevokeTime() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        clock.advance(Duration.ofMinutes(1));

        revoke(source.getArn());

        EventSourceV2 revoked = service.describeEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\"}"),
                REGION);
        assertTrue(revoked.isRevoked());
        assertEquals("ACTIVE", revoked.getState());
        assertEquals(NOW, revoked.getCreationTime());
        assertEquals(NOW.plusSeconds(60), revoked.getLastModifiedTime());
    }

    @Test
    void aSecondRevokeAnswersOkAndLeavesTheModifiedTimeAlone() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        clock.advance(Duration.ofMinutes(1));
        revoke(source.getArn());
        clock.advance(Duration.ofMinutes(1));

        assertEquals(source.getArn(), revoke(source.getArn()));

        assertTrue(service.describeEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\"}"), REGION)
                .isRevoked());
        assertEquals(NOW.plusSeconds(60), service.describeEventSource(json("{\"EventSourceArn\":\""
                + source.getArn() + "\"}"), REGION).getLastModifiedTime());
    }

    @Test
    void theSecretOfARevokedSourceStaysAndItsNameStaysTaken() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        revoke(source.getArn());

        assertNull(secrets.describeSecret(source.getSecretArn(), REGION).getDeletedDate());
        assertEquals("original-token", secrets.getSecretValue(source.getSecretArn(), null, "AWSCURRENT", REGION)
                .getSecretString());
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(json("{\"ClientToken\":"
                + "\"another\",\"Name\":\"hook\",\"EventBusArn\":\"" + bus + "\",\"Configuration\":"
                + "{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\"" + ROLE
                + "\"}}}"), REGION));
        assertEquals("ResourceAlreadyExistsException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals("Resource already exists", e.getMessage());
    }

    @Test
    void describeAndListReportRevokedOnlyOnceTheSourceIsRevoked() {
        EventSourceV2 revoked = fixture.source("rv-src", STATIC_AUTH);
        EventSourceV2 live = fixture.source("rv-live", STATIC_AUTH);
        revoke(revoked.getArn());

        assertEquals(true, describe(revoked).get("Revoked").booleanValue());
        assertEquals("ACTIVE", describe(revoked).get("State").textValue());
        assertTrue(describe(revoked).has("Configuration"));
        assertFalse(describe(live).has("Revoked"));
        JsonNode listed = list("{}").get("EventSources");
        for (JsonNode summary : listed) {
            boolean isRevoked = summary.get("Name").textValue().equals("rv-src");
            assertEquals(isRevoked, summary.has("Revoked"), summary.toString());
            if (isRevoked) {
                assertEquals(true, summary.get("Revoked").booleanValue());
                assertEquals("ACTIVE", summary.get("State").textValue());
            }
        }
        assertEquals(2, listed.size());
    }

    @Test
    void updateOfARevokedSourceIsInvalidStateAndChangesNothing() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        revoke(source.getArn());

        assertAnswer(409, "InvalidStateException", UPDATE_MESSAGE, "UpdateEventSource",
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Description\":\"changed\"}");
        assertAnswer(409, "InvalidStateException", UPDATE_MESSAGE, "UpdateEventSource",
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                        + "{\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Tok\"}},"
                        + "\"RoleArn\":\"" + ROLE + "\"}}}");
        assertAnswer(409, "InvalidStateException", UPDATE_MESSAGE, "UpdateEventSource",
                "{\"EventSourceArn\":\"" + source.getArn() + "\"}");
        assertNull(describe(source).get("Description"));
    }

    @Test
    void theRevokedCheckOfUpdateComesAfterItsBadRequestChecks() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        revoke(source.getArn());

        assertAnswer(400, "InvalidInputException", "Exactly one Configuration sub-field must be provided",
                "UpdateEventSource", "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":{}}");
        assertAnswer(400, "InvalidInputException",
                "The webhook authentication type can't be changed after the EventSource is created",
                "UpdateEventSource", "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":"
                        + "{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":{\"BasicHttpAuthConfiguration\":{}},"
                        + "\"RoleArn\":\"" + ROLE + "\"}}}");
        assertAnswer(400, "InvalidInputException", "1 validation error detected: Value 'bad' at 'eventSourceArn' "
                + "failed to satisfy constraint: Member must satisfy regular expression pattern: "
                        + "arn:aws(-[a-z0-9]+)*:events:[a-z][a-z0-9]*(-[a-z0-9]+)*:([0-9]{12}):event-sourcev2\\/"
                        + "(aws\\.service|aws\\.partner|webhook)\\/[A-Za-z0-9][\\.\\-_A-Za-z0-9]{0,255}\\/[a-z0-9]{25}",
                "UpdateEventSource", "{\"EventSourceArn\":\"bad\"}");
        assertAnswer(403, "AccessDeniedException", "Access denied.", "UpdateEventSource",
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                        + "{\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\"arn:aws:iam::111111111111:role/x\"}}}");
    }

    @Test
    void rotateOfARevokedSourceIsInvalidState() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        revoke(source.getArn());

        assertAnswer(409, "InvalidStateException", ROTATE_MESSAGE, "RotateWebhookCredentials",
                rotation(source, "{\"StaticTokenSecretValue\":\"second-token\"}"));
        assertAnswer(409, "InvalidStateException", ROTATE_MESSAGE, "RotateWebhookCredentials", rotation(source, null));
        assertEquals("original-token", secrets.getSecretValue(source.getSecretArn(), null, "AWSCURRENT", REGION)
                .getSecretString());
    }

    @Test
    void theRevokedCheckOfRotateComesAfterItsBadRequestChecks() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        revoke(source.getArn());

        assertAnswer(400, "InvalidInputException",
                "Credential must set exactly one of StaticTokenSecretValue, BasicHttpPassword or HmacSecretValue",
                "RotateWebhookCredentials", rotation(source, "{}"));
        assertAnswer(400, "InvalidInputException", "Credential must set StaticTokenSecretValue, because the "
                + "authentication type of this EventSource is StaticTokenAuthConfiguration",
                "RotateWebhookCredentials", rotation(source, "{\"BasicHttpPassword\":\"password\"}"));
        assertAnswer(400, "InvalidInputException", "ClientToken is required", "RotateWebhookCredentials",
                "{\"EventSourceArn\":\"" + source.getArn() + "\"}");
        assertAnswer(404, "ResourceNotFoundException", "EventSource not found: " + SOURCE_MISSING_ID,
                "RotateWebhookCredentials", "{\"EventSourceArn\":\"" + SOURCE_MISSING_ID + "\",\"ClientToken\":\"t\","
                        + "\"Credential\":{\"StaticTokenSecretValue\":\"second-token\"}}");
    }

    @Test
    void theRevokedCheckOfRotateComesAfterTheValueRulesOfTheAuthenticationType() {
        EventSourceV2 source = fixture.source("hmac", HMAC_AUTH);
        revoke(source.getArn());

        assertAnswer(400, "InvalidInputException",
                "Credential.HmacSecretValue cannot be used with the authentication settings of this EventSource",
                "RotateWebhookCredentials", rotation(source, "{\"HmacSecretValue\":\"short-secret1\"}"));
        assertAnswer(409, "InvalidStateException", ROTATE_MESSAGE, "RotateWebhookCredentials",
                rotation(source, "{\"HmacSecretValue\":\"0123456789abcdefghij\"}"));
    }

    @Test
    void theRevokedCheckOfRotateComesAfterTheReplayOfAClientToken() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        String request = "{\"EventSourceArn\":\"" + source.getArn() + "\",\"ClientToken\":\"same\","
                + "\"Credential\":{\"StaticTokenSecretValue\":\"second-token\"}}";
        service.rotateWebhookCredentials(json(request), REGION);
        revoke(source.getArn());

        assertEquals(source.getSecretArn(), service.rotateWebhookCredentials(json(request), REGION));
        assertAnswer(409, "IdempotentParameterMismatchException",
                "ClientToken was used by a previous request with different parameters", "RotateWebhookCredentials",
                request.replace("second-token", "third-token"));
        assertAnswer(409, "InvalidStateException", ROTATE_MESSAGE, "RotateWebhookCredentials",
                request.replace("same", "new"));
    }

    @Test
    void aRevokedSourceCanStillBeDescribedAndDeletedAndItsSecretGoesWithIt() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        revoke(source.getArn());

        service.deleteEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\"}"), REGION);

        assertNotNull(secrets.describeSecret(source.getSecretArn(), REGION).getDeletedDate());
        assertTrue(list("{}").get("EventSources").isEmpty());
        assertEquals("hook", fixture.source("hook", STATIC_AUTH).getName());
    }

    @Test
    void aRevokedSourceAnswersForbiddenOnItsUrl() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        URI url = URI.create(service.webhookUrl(source));
        revoke(source.getArn());

        assertEquals(EventBridgeV2Webhooks.FORBIDDEN, webhooks.receive(new Request("POST", source.getWebhookHostId(),
                url.getPath(), Map.of("x-tok", "original-token"), "{}".getBytes(StandardCharsets.UTF_8), false)));
    }

    // ──────────────────────────── A revoked subscriber ────────────────────────────

    @Test
    void aRevokedSubscriberStaysRunningAndMovesItsModifiedTimeToTheRevokeTime() {
        SubscriberV2 subscriber = subscriber("keep", bus);
        clock.advance(Duration.ofMinutes(1));

        revoke(subscriber.getArn());

        SubscriberV2 revoked = subscriber(subscriber.getArn());
        assertTrue(revoked.isRevoked());
        assertEquals("RUNNING", revoked.getState());
        assertEquals(NOW, revoked.getCreationTime());
        assertEquals(NOW.plusSeconds(60), revoked.getLastModifiedTime());
    }

    @Test
    void aSecondRevokeOfASubscriberAnswersOkAndLeavesTheModifiedTimeAlone() {
        SubscriberV2 subscriber = subscriber("keep", bus);
        clock.advance(Duration.ofMinutes(1));
        revoke(subscriber.getArn());
        clock.advance(Duration.ofMinutes(1));

        assertEquals(subscriber.getArn(), revoke(subscriber.getArn()));

        assertEquals(NOW.plusSeconds(60), subscriber(subscriber.getArn()).getLastModifiedTime());
    }

    @Test
    void describeAndListReportTheStoredRevokedFlagOfASubscriber() {
        SubscriberV2 revoked = subscriber("rv-rev", bus);
        SubscriberV2 keep = subscriber("rv-keep", bus);
        revoke(revoked.getArn());

        assertEquals(true, describeSubscriber(revoked).get("Revoked").booleanValue());
        assertEquals("RUNNING", describeSubscriber(revoked).get("State").textValue());
        assertFalse(describeSubscriber(keep).has("Revoked"));
        for (JsonNode summary : handlerList("ListSubscribers", "{}").get("Subscribers")) {
            assertEquals(summary.get("Name").textValue().equals("rv-rev"), summary.has("Revoked"));
            assertEquals(QUEUE, summary.get("TargetArn").textValue());
        }
    }

    @Test
    void updateOfARevokedSubscriberIsInvalidStateAndChangesNothing() {
        SubscriberV2 subscriber = subscriber("keep", bus);
        revoke(subscriber.getArn());

        assertAnswer(409, "InvalidStateException", SUBSCRIBER_UPDATE_MESSAGE, "UpdateSubscriber",
                "{\"SubscriberArn\":\"" + subscriber.getArn() + "\",\"Description\":\"changed\"}");
        assertAnswer(409, "InvalidStateException", SUBSCRIBER_UPDATE_MESSAGE, "UpdateSubscriber",
                "{\"SubscriberArn\":\"" + subscriber.getArn() + "\"}");
        assertNull(subscriber(subscriber.getArn()).getDescription());
        assertTrue(subscriber(subscriber.getArn()).isRevoked());
    }

    @Test
    void theRevokedCheckOfUpdateSubscriberComesAfterItsBadRequestChecks() {
        SubscriberV2 subscriber = subscriber("keep", bus);
        revoke(subscriber.getArn());
        String arn = subscriber.getArn();

        assertAnswer(400, "InvalidInputException", "InvokeConfiguration.TargetArn cannot be updated.",
                "UpdateSubscriber", "{\"SubscriberArn\":\"" + arn + "\",\"InvokeConfiguration\":{\"TargetArn\":\""
                        + QUEUE + "\",\"RoleArn\":\"" + ROLE + "\"}}");
        assertAnswer(400, "InvalidInputException", "ResumePosition must be one of LAST_PROCESSED, LATEST.",
                "UpdateSubscriber", "{\"SubscriberArn\":\"" + arn + "\",\"ResumePosition\":\"NEVER\"}");
        assertAnswer(404, "ResourceNotFoundException", "Subscriber arn:aws:events:us-east-1:000000000000:subscriber/"
                + "nope/" + ID + " does not exist.", "UpdateSubscriber", "{\"SubscriberArn\":\"arn:aws:events:"
                + "us-east-1:000000000000:subscriber/nope/" + ID + "\"}");
    }

    @Test
    void aRevokedSubscriberGetsNoMoreEventsAndTheOtherSubscribersOfTheBusStillDo() {
        SubscriberV2 revoked = subscriber("rv-rev", bus);
        subscriber("rv-keep", bus);

        putEvent();
        assertEquals(Set.of("rv-rev", "rv-keep"), Set.copyOf(delivered));
        delivered.clear();
        revoke(revoked.getArn());
        putEvent();

        assertEquals(List.of("rv-keep"), delivered);
    }

    @Test
    void aRevokedSubscriberCanStillBeDeleted() {
        SubscriberV2 subscriber = subscriber("keep", bus);
        revoke(subscriber.getArn());

        service.deleteSubscriber(json("{\"SubscriberArn\":\"" + subscriber.getArn() + "\"}"), REGION);

        assertTrue(service.listSubscribers(json("{}"), REGION).items().isEmpty());
    }

    // ──────────────────────────── DeleteEventBus ────────────────────────────

    @Test
    void aLiveSourceOrSubscriberStillKeepsTheBus() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        SubscriberV2 subscriber = subscriber("keep", bus);

        assertBusKept(IN_USE_BOTH);
        revoke(subscriber.getArn());
        assertBusKept(IN_USE_SOURCES);
        revoke(source.getArn());
        service.deleteEventBus(bus, REGION);
        assertTrue(service.listEventBuses(json("{}"), REGION).items().isEmpty());
    }

    @Test
    void aBusWhoseOnlySourceIsRevokedIsDeleted() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        revoke(source.getArn());

        service.deleteEventBus(bus, REGION);

        assertTrue(service.listEventBuses(json("{}"), REGION).items().isEmpty());
    }

    @Test
    void aBusWhoseOnlySubscriberIsRevokedIsDeleted() {
        SubscriberV2 subscriber = subscriber("keep", bus);
        revoke(subscriber.getArn());

        service.deleteEventBus(bus, REGION);

        assertTrue(service.listEventBuses(json("{}"), REGION).items().isEmpty());
    }

    @Test
    void aLiveSubscriberAlongsideARevokedSourceKeepsTheBus() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        subscriber("keep", bus);
        revoke(source.getArn());

        AwsException e = assertThrows(AwsException.class, () -> service.deleteEventBus(bus, REGION));
        assertEquals("ResourceInUseException", e.getErrorCode());
        assertEquals("Event bus orders still has subscribers; delete them first.", e.getMessage());
    }

    @Test
    void aRevokedSourceOutlivesItsBusAndIsDescribedWithoutItsConfiguration() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        revoke(source.getArn());
        service.deleteEventBus(bus, REGION);

        JsonNode described = describe(source);

        assertEquals(Set.of("EventSourceArn", "Name", "EventBusArn", "State", "Revoked", "EventSourceDetails",
                "CreationTime", "LastModifiedTime"), fieldNames(described));
        assertEquals(bus, described.get("EventBusArn").textValue());
        assertEquals(true, described.get("Revoked").booleanValue());
        assertEquals(source.getSecretArn(), described.at("/EventSourceDetails/WebhookDetails/SecretArn").textValue());
        assertEquals(1, list("{}").get("EventSources").size());
    }

    @Test
    void aRevokedSubscriberOutlivesItsBusAndIsDescribedWithoutItsDeliverySettings() {
        SubscriberV2 subscriber = subscriber("keep", bus);
        revoke(subscriber.getArn());
        service.deleteEventBus(bus, REGION);

        JsonNode described = describeSubscriber(subscriber);

        assertEquals(Set.of("SubscriberArn", "Name", "EventBusArn", "Type", "StartingPosition", "State", "Revoked",
                "CreationTime", "LastModifiedTime"), fieldNames(described));
        JsonNode listed = handlerList("ListSubscribers", "{}").get("Subscribers");
        assertEquals(1, listed.size());
        assertEquals(QUEUE, listed.get(0).get("TargetArn").textValue());
        assertEquals(true, listed.get(0).get("Revoked").booleanValue());
    }

    @Test
    void listingTheSourcesOfADeletedBusIsNotFound() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        revoke(source.getArn());
        service.deleteEventBus(bus, REGION);

        assertAnswer(404, "ResourceNotFoundException", "Resource does not exist", "ListEventSources",
                "{\"EventBusArn\":\"" + bus + "\"}");
    }

    @Test
    void anOrphanCanBeRevokedAgainAndDeletedAndItsSecretGoesWithIt() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        SubscriberV2 subscriber = subscriber("keep", bus);
        revoke(source.getArn());
        revoke(subscriber.getArn());
        service.deleteEventBus(bus, REGION);

        assertEquals(source.getArn(), revoke(source.getArn()));
        assertEquals(subscriber.getArn(), revoke(subscriber.getArn()));
        service.deleteEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\"}"), REGION);
        service.deleteSubscriber(json("{\"SubscriberArn\":\"" + subscriber.getArn() + "\"}"), REGION);

        assertNotNull(secrets.describeSecret(source.getSecretArn(), REGION).getDeletedDate());
        assertTrue(list("{}").get("EventSources").isEmpty());
        assertTrue(service.listSubscribers(json("{}"), REGION).items().isEmpty());
    }

    @Test
    void aNewBusOfTheSameNameDoesNotAdoptTheOrphans() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        SubscriberV2 subscriber = subscriber("keep", bus);
        revoke(source.getArn());
        revoke(subscriber.getArn());
        service.deleteEventBus(bus, REGION);

        String recreated = service.createEventBus(json("{\"Name\":\"orders\"}"), REGION).getArn();

        assertTrue(!recreated.equals(bus));
        assertTrue(list("{\"EventBusArn\":\"" + recreated + "\"}").get("EventSources").isEmpty());
        assertTrue(service.listSubscribers(json("{\"EventBusArn\":\"" + recreated + "\"}"), REGION).items().isEmpty());
        service.deleteEventBus(recreated, REGION);
        assertEquals(1, list("{}").get("EventSources").size());
    }

    @Test
    void anOrphanSubscriberDescribesItsDescriptionAsNotAccessibleOrNotAtAll() {
        SubscriberV2 described = service.createSubscriber(json("{\"Name\":\"with\",\"EventBusArn\":\"" + bus
                + "\",\"Description\":\"d\",\"InvokeConfiguration\":{\"TargetArn\":\"" + QUEUE
                + "\",\"RoleArn\":\"" + ROLE + "\"},\"FilterConfiguration\":{\"Filters\":[{\"Scope\":"
                + "\"DATA\",\"Pattern\":\"{}\"}]}}"), REGION);
        SubscriberV2 plain = subscriber("without", bus);
        revoke(described.getArn());
        revoke(plain.getArn());
        service.deleteEventBus(bus, REGION);

        JsonNode with = describeSubscriber(described);
        assertEquals("*** Not accessible: event bus deleted ***", with.get("Description").textValue());
        assertFalse(with.has("FilterConfiguration"));
        assertFalse(describeSubscriber(plain).has("Description"));
    }

    @Test
    void aSubscriberOfTheOrphansNameCanBeCreatedOnTheNewBusAndTheOrphanIsKept() {
        SubscriberV2 orphan = subscriber("keep", bus);
        revoke(orphan.getArn());
        service.deleteEventBus(bus, REGION);
        String recreated = service.createEventBus(json("{\"Name\":\"orders\"}"), REGION).getArn();

        SubscriberV2 fresh = subscriber("keep", recreated);

        assertEquals(List.of(fresh.getArn()), service.listSubscribers(json("{\"EventBusArn\":\"" + recreated
                + "\"}"), REGION).items().stream().map(SubscriberV2::getArn).toList());
        assertEquals(2, service.listSubscribers(json("{}"), REGION).items().size());
        assertTrue(subscriber(orphan.getArn()).isRevoked());
        assertFalse(subscriber(fresh.getArn()).isRevoked());
        service.deleteSubscriber(json("{\"SubscriberArn\":\"" + orphan.getArn() + "\"}"), REGION);
        assertEquals(fresh.getArn(), subscriber(fresh.getArn()).getArn());
        assertEquals(1, service.listSubscribers(json("{}"), REGION).items().size());
    }

    @Test
    void pagesOfSubscribersShowBothTheOrphanAndItsNamesakeOnTheNewBus() {
        SubscriberV2 orphan = subscriber("keep", bus);
        revoke(orphan.getArn());
        service.deleteEventBus(bus, REGION);
        String recreated = service.createEventBus(json("{\"Name\":\"orders\"}"), REGION).getArn();
        SubscriberV2 fresh = subscriber("keep", recreated);

        PaginatedResult<SubscriberV2> first = service.listSubscribers(json("{\"MaxResults\":1}"), REGION);
        PaginatedResult<SubscriberV2> second = service.listSubscribers(
                json("{\"MaxResults\":1,\"NextToken\":\"" + first.nextToken() + "\"}"), REGION);

        assertEquals(Set.of(orphan.getArn(), fresh.getArn()),
                Set.of(first.items().get(0).getArn(), second.items().get(0).getArn()));
        assertNull(second.nextToken());
    }

    @Test
    void aSourceOfTheOrphansNameIsRefusedOnTheNewBusAndTheNewBusListsNone() {
        EventSourceV2 orphan = fixture.source("hook", STATIC_AUTH);
        revoke(orphan.getArn());
        service.deleteEventBus(bus, REGION);
        String recreated = service.createEventBus(json("{\"Name\":\"orders\"}"), REGION).getArn();

        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(json("{\"ClientToken\":"
                + "\"fresh\",\"Name\":\"hook\",\"EventBusArn\":\"" + recreated + "\",\"Configuration\":"
                + "{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\""
                + ROLE + "\"}}}"), REGION));
        assertEquals("ResourceAlreadyExistsException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals("Resource already exists", e.getMessage());
        assertTrue(list("{\"EventBusArn\":\"" + recreated + "\"}").get("EventSources").isEmpty());
    }

    @Test
    void aNewBusOfTheSameNameDoesNotReceiveTheOrphanSubscribersDeliveries() {
        SubscriberV2 subscriber = subscriber("keep", bus);
        revoke(subscriber.getArn());
        service.deleteEventBus(bus, REGION);
        String recreated = service.createEventBus(json("{\"Name\":\"orders\"}"), REGION).getArn();

        service.putEvents(json("{\"EventBusArn\":\"" + recreated + "\",\"Entries\":[{\"Source\":\"s\","
                + "\"DetailType\":\"t\",\"Detail\":\"{}\"}]}"), REGION);

        assertTrue(delivered.isEmpty());
    }

    // ──────────────────────────── Stored state ────────────────────────────

    @Test
    void storedSourcesAndSubscribersWithoutTheFlagLoadAsNotRevoked() throws Exception {
        ObjectMapper stored = new ObjectMapper().registerModule(new JavaTimeModule());

        assertFalse(stored.readValue("{\"name\":\"hook\"}", EventSourceV2.class).isRevoked());
        assertFalse(stored.readValue("{\"name\":\"keep\"}", SubscriberV2.class).isRevoked());
        assertTrue(stored.readValue("{\"name\":\"hook\",\"revoked\":true}", EventSourceV2.class).isRevoked());
        assertTrue(stored.readValue("{\"name\":\"keep\",\"revoked\":true}", SubscriberV2.class).isRevoked());
    }

    @Test
    void theFlagSurvivesTheCopiesAnUpdateOrARotationMakes() {
        EventSourceV2 source = fixture.source("hook", STATIC_AUTH);
        SubscriberV2 subscriber = subscriber("keep", bus);
        revoke(subscriber.getArn());

        service.updateEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\",\"Description\":\"d\"}"),
                REGION);
        service.rotateWebhookCredentials(json(rotation(source, null)), REGION);
        revoke(source.getArn());

        assertTrue(describeSource(source).isRevoked());
        assertTrue(subscriber(subscriber.getArn()).isRevoked());
        assertEquals("d", describeSource(source).getDescription());
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private String revoke(String arn) {
        return service.revokeResource(json("{\"Arn\":\"" + arn + "\"}"), REGION);
    }

    private SubscriberV2 subscriber(String name, String eventBusArn) {
        return service.createSubscriber(json("{\"Name\":\"" + name + "\",\"EventBusArn\":\"" + eventBusArn
                + "\",\"InvokeConfiguration\":{\"TargetArn\":\"" + QUEUE + "\",\"RoleArn\":\"" + ROLE + "\"}}"),
                REGION);
    }

    private SubscriberV2 subscriber(String arn) {
        return service.describeSubscriber(json("{\"SubscriberArn\":\"" + arn + "\"}"), REGION);
    }

    private EventSourceV2 describeSource(EventSourceV2 source) {
        return service.describeEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\"}"), REGION);
    }

    private JsonNode describe(EventSourceV2 source) {
        return (JsonNode) handler.handle("DescribeEventSource", json("{\"EventSourceArn\":\"" + source.getArn()
                + "\"}"), REGION).getEntity();
    }

    private JsonNode describeSubscriber(SubscriberV2 subscriber) {
        return (JsonNode) handler.handle("DescribeSubscriber", json("{\"SubscriberArn\":\"" + subscriber.getArn()
                + "\"}"), REGION).getEntity();
    }

    private JsonNode list(String request) {
        return handlerList("ListEventSources", request);
    }

    private JsonNode handlerList(String operation, String request) {
        return (JsonNode) handler.handle(operation, json(request), REGION).getEntity();
    }

    private String rotation(EventSourceV2 source, String credential) {
        return "{\"EventSourceArn\":\"" + source.getArn() + "\",\"ClientToken\":\"token-" + tokens++ + "\""
                + (credential == null ? "" : ",\"Credential\":" + credential) + "}";
    }

    private void putEvent() {
        service.putEvents(json("{\"EventBusArn\":\"" + bus + "\",\"Entries\":[{\"Source\":\"s\","
                + "\"DetailType\":\"t\",\"Detail\":\"{}\"}]}"), REGION);
    }

    private void assertBusKept(String message) {
        AwsException e = assertThrows(AwsException.class, () -> service.deleteEventBus(bus, REGION));
        assertEquals("ResourceInUseException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals(message, e.getMessage());
    }

    private void assertRefused(int status, String code, String message, String request) {
        assertAnswer(status, code, message, "RevokeResource", request);
    }

    private void assertAnswer(int status, String code, String message, String operation, String request) {
        AwsException e = assertThrows(AwsException.class, () -> handler.handle(operation, json(request), REGION));
        assertEquals(status, e.getHttpStatus());
        assertEquals(code, e.getErrorCode());
        assertEquals(message, e.getMessage());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
