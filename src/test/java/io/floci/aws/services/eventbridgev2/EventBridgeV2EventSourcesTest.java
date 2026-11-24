package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.eventbridgev2.model.EventBusV2;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventBridgeV2EventSourcesTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::000000000000:role/Hook";
    private static final String STATIC_AUTH = "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Token\"}}";
    private static final String BASIC_AUTH = "{\"BasicHttpAuthConfiguration\":{}}";

    private SecretsManagerService secrets;
    private EventBridgeV2Service service;
    private TestClock clock;
    private String bus;

    @BeforeEach
    void setUp() {
        secrets = mock(SecretsManagerService.class);
        when(secrets.createSecret(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            Secret secret = new Secret();
            secret.setArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:" + call.getArgument(0) + "-AbCdEf");
            return secret;
        });
        when(secrets.describeSecret(any(), any())).thenReturn(new Secret());
        clock = new TestClock(NOW);
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        EventBridgeV2EventSources eventSources = new EventBridgeV2EventSources(
                AccountAwareStorageBackend.inMemory(ACCOUNT), secrets, regionResolver, clock, "http://localhost:4566");
        service = new EventBridgeV2Service(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), regionResolver, mock(EventBridgeV2Delivery.class),
                eventSources, clock, true);
        bus = bus("orders");
    }

    // ──────────────────────────── Create ────────────────────────────

    @Test
    void createMintsAnActiveSourceWithAnArnAndAWebhookUrl() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);

        assertTrue(source.getArn().matches(
                "arn:aws:events:us-east-1:000000000000:event-sourcev2/webhook/hook/[a-z0-9]{25}"), source.getArn());
        assertEquals(bus, source.getEventBusArn());
        assertEquals("ACTIVE", source.getState());
        assertEquals(NOW, source.getCreationTime());
        assertEquals(NOW, source.getLastModifiedTime());
        String url = service.webhookUrl(source);
        String host = "[a-z][a-z2-7]{37}\\.c01\\.events-webhook\\.us-east-1\\.localhost:4566";
        assertTrue(url.matches("http://" + host + "/k1[A-Za-z0-9_-]{64}/"), url);
        assertTrue(source.getWebhookHostId().startsWith("a"));
    }

    @Test
    void createKeepsTheSecretOutOfTheStoredConfiguration() {
        EventSourceV2 source = service.createEventSource(create("hook", bus,
                "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Token\",\"SecretValue\":\"s3cret!!\"}}"),
                REGION);
        assertEquals(json("{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":" + STATIC_AUTH
                + ",\"RoleArn\":\"" + ROLE + "\"}}"), source.getConfiguration());
    }

    @Test
    void createStoresTheGivenStaticTokenInAManagedSecret() {
        EventSourceV2 source = service.createEventSource(create("hook", bus,
                "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Token\",\"SecretValue\":\"s3cret!!\"}}"),
                REGION);

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretString = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Secret.Tag>> tags = ArgumentCaptor.captor();
        verify(secrets).createSecret(name.capture(), secretString.capture(), isNull(), isNull(), isNull(),
                tags.capture(), eq("events-webhook"), eq(REGION));
        assertTrue(name.getValue().matches("events-webhook![0-9a-f-]{36}"), name.getValue());
        assertEquals("s3cret!!", secretString.getValue());
        assertEquals(List.of(new Secret.Tag("aws:secretsmanager:owningService", "events-webhook")), tags.getValue());
        assertTrue(source.getSecretArn().contains(name.getValue()));
    }

    @Test
    void createGeneratesA44CharacterStaticToken() {
        service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        String generated = capturedSecretString();
        assertEquals(44, generated.length());
        assertEquals(32, Base64.getDecoder().decode(generated).length);
    }

    @Test
    void createStoresBasicCredentialsAsACompactJsonObject() {
        service.createEventSource(create("hook", bus, "{\"BasicHttpAuthConfiguration\":{\"Username\":\"user\","
                + "\"Password\":\"p4ssword\"}}"), REGION);
        assertEquals("{\"username\":\"user\",\"password\":\"p4ssword\"}", capturedSecretString());
    }

    @Test
    void createGeneratesBothBasicCredentialsWhenNeitherIsGiven() throws Exception {
        service.createEventSource(create("hook", bus, BASIC_AUTH), REGION);
        String secretString = capturedSecretString();
        assertTrue(secretString.matches("\\{\"username\":\"[A-Za-z0-9+/]{43}=\",\"password\":\"[A-Za-z0-9+/]{43}=\"}"),
                secretString);
        assertEquals(List.of("username", "password"), MAPPER.readTree(secretString).properties().stream().map(Map.Entry::getKey).toList());
    }

    @Test
    void createStripsTheWhsecPrefixOnlyForABase64HmacSecret() {
        String hmac = "\"Algorithm\":\"SHA256\",\"Encoding\":\"HEX\",\"SignatureHttpHeader\":\"X-Sig\","
                + "\"SignedMessageComposition\":\"BODY\",";
        service.createEventSource(create("base64", bus, "{\"HmacAuthConfiguration\":{" + hmac
                + "\"SecretEncoding\":\"BASE64\",\"SecretValue\":\"whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}"),
                REGION);
        service.createEventSource(create("raw", bus, "{\"HmacAuthConfiguration\":{" + hmac
                + "\"SecretEncoding\":\"RAW\",\"SecretValue\":\"whsec_0123456789abcdef\"}}"), REGION);
        service.createEventSource(create("none", bus, "{\"HmacAuthConfiguration\":{" + hmac
                + "\"SecretValue\":\"whsec_0123456789abcdef\"}}"), REGION);
        service.createEventSource(create("generated", bus, "{\"HmacAuthConfiguration\":{" + hmac.substring(0,
                hmac.length() - 1) + "}}"), REGION);

        ArgumentCaptor<String> secretString = ArgumentCaptor.forClass(String.class);
        verify(secrets, times(4)).createSecret(any(), secretString.capture(), any(), any(), any(), any(), any(),
                any());
        List<String> values = secretString.getAllValues();
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", values.get(0));
        assertEquals("whsec_0123456789abcdef", values.get(1));
        assertEquals("whsec_0123456789abcdef", values.get(2));
        assertEquals(44, values.get(3).length());
    }

    @Test
    void createChecksConstraintsFirstAndReportsThemTogether() {
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(json(
                "{\"Name\":\"-x\",\"EventBusArn\":\"nope\",\"Configuration\":{}}"), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertTrue(e.getMessage().startsWith("2 validation errors detected: Value '-x' at 'name'"), e.getMessage());
    }

    @Test
    void createRefusesABusOfAnotherRegionBeforeAnotherAccount() {
        String otherRegion = "arn:aws:events:eu-west-1:111111111111:event-busv2/orders/abcdefghijklmnopqrstuvwxy";
        AwsException e = assertThrows(AwsException.class,
                () -> service.createEventSource(create("hook", otherRegion, STATIC_AUTH), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals("The region or partition in EventBusArn does not match this endpoint", e.getMessage());
    }

    @Test
    void createRefusesABusOrARoleOfAnotherAccount() {
        String otherAccount = "arn:aws:events:us-east-1:111111111111:event-busv2/orders/abcdefghijklmnopqrstuvwxy";
        AwsException bus = assertThrows(AwsException.class,
                () -> service.createEventSource(create("hook", otherAccount, STATIC_AUTH), REGION));
        assertEquals("AccessDeniedException", bus.getErrorCode());
        assertEquals(403, bus.getHttpStatus());
        assertEquals("Access denied.", bus.getMessage());

        JsonNode foreignRole = json(create("hook", this.bus, STATIC_AUTH).toString().replace(ROLE,
                "arn:aws:iam::111111111111:role/Hook"));
        AwsException role = assertThrows(AwsException.class, () -> service.createEventSource(foreignRole, REGION));
        assertEquals("AccessDeniedException", role.getErrorCode());
    }

    @Test
    void createAcceptsARoleThatDoesNotExist() {
        service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
    }

    @Test
    void createChecksTheRulesBeforeLookingForTheBus() {
        String missing = "arn:aws:events:us-east-1:000000000000:event-busv2/missing/abcdefghijklmnopqrstuvwxy";
        AwsException e = assertThrows(AwsException.class,
                () -> service.createEventSource(create("hook", missing, "{}"), REGION));
        assertTrue(e.getMessage().startsWith("WebhookEventsConfiguration.AuthConfiguration must set exactly one"),
                e.getMessage());
        AwsException notFound = assertThrows(AwsException.class,
                () -> service.createEventSource(create("hook", missing, STATIC_AUTH), REGION));
        assertEquals("ResourceNotFoundException", notFound.getErrorCode());
        assertEquals(404, notFound.getHttpStatus());
        assertEquals("Event bus not found: missing", notFound.getMessage());
    }

    @Test
    void createRefusesAnotherVariantWithFlociOwnWording() {
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(json(
                "{\"Name\":\"hook\",\"EventBusArn\":\"" + bus + "\",\"Configuration\":"
                        + "{\"AwsServiceEventsConfiguration\":{\"AwsService\":\"aws.s3\"}}}"), REGION));
        assertEquals("Only WebhookEventsConfiguration event sources are supported.", e.getMessage());
    }

    @Test
    void createWithAUsedNameFailsInEveryBusOfTheRegionOnly() {
        service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        String other = bus("payments");
        AwsException e = assertThrows(AwsException.class,
                () -> service.createEventSource(create("hook", other, STATIC_AUTH), REGION));
        assertEquals("ResourceAlreadyExistsException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals("Resource already exists", e.getMessage());
        verify(secrets, times(1)).createSecret(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createRetriedWithItsClientTokenReturnsTheSameSource() {
        JsonNode request = create("hook", bus, STATIC_AUTH, "\"ClientToken\":\"t-1\"");
        EventSourceV2 first = service.createEventSource(request, REGION);
        EventSourceV2 retried = service.createEventSource(request, REGION);
        assertEquals(first.getArn(), retried.getArn());
        verify(secrets, times(1)).createSecret(any(), any(), any(), any(), any(), any(), any(), any());
        assertEquals(first.getCreateRequestHash(), retried.getCreateRequestHash());
    }

    @Test
    void createRetriedWithAnotherRequestIsAnIdempotentParameterMismatch() {
        service.createEventSource(create("hook", bus, STATIC_AUTH, "\"ClientToken\":\"t-1\""), REGION);
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(
                create("hook", bus, STATIC_AUTH, "\"ClientToken\":\"t-1\",\"Description\":\"changed\""), REGION));
        assertEquals("IdempotentParameterMismatchException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals("ClientToken was used by a previous request with different parameters", e.getMessage());
    }

    @Test
    void createKeepsOnlyAHashOfTheRequestThatCarriesSecrets() {
        EventSourceV2 source = service.createEventSource(create("hook", bus,
                "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Token\",\"SecretValue\":\"s3cret!!\"}}",
                "\"ClientToken\":\"t-1\""), REGION);
        assertFalse(source.getCreateRequestHash().contains("s3cret"));
    }

    @Test
    void createDeletesItsSecretWhenTheSourceCannotBeStored() {
        AccountAwareStorageBackend<EventSourceV2> failing = spy(
                AccountAwareStorageBackend.<EventSourceV2>inMemory(ACCOUNT));
        IllegalStateException failure = new IllegalStateException("disk full");
        doThrow(failure).when(failing).put(any(), any());
        EventBridgeV2EventSources eventSources = new EventBridgeV2EventSources(failing, secrets,
                new RegionResolver(REGION, ACCOUNT), clock, "http://localhost:4566");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> eventSources.create(
                create("hook", bus, STATIC_AUTH), REGION, arn -> Optional.of(new EventBusV2())));

        assertSame(failure, e);
        ArgumentCaptor<String> secretName = ArgumentCaptor.forClass(String.class);
        verify(secrets).createSecret(secretName.capture(), any(), any(), any(), any(), any(), any(), any());
        verify(secrets).deleteSecret(
                eq("arn:aws:secretsmanager:us-east-1:000000000000:secret:" + secretName.getValue() + "-AbCdEf"),
                isNull(), eq(true), eq(REGION));
    }

    // ──────────────────────────── Describe ────────────────────────────

    @Test
    void describeFindsASourceByItsArnAndChecksTheArn() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        assertEquals(source.getArn(), service.describeEventSource(arn(source.getArn()), REGION).getArn());

        String missing = source.getArn().replace("/hook/", "/gone/");
        AwsException notFound = assertThrows(AwsException.class,
                () -> service.describeEventSource(arn(missing), REGION));
        assertEquals("ResourceNotFoundException", notFound.getErrorCode());
        assertEquals("EventSource not found: " + missing, notFound.getMessage());

        String otherRegion = source.getArn().replace("us-east-1", "eu-west-1");
        assertEquals("The region or partition in EventSourceArn does not match this endpoint", assertThrows(
                AwsException.class, () -> service.describeEventSource(arn(otherRegion), REGION)).getMessage());

        String otherAccount = source.getArn().replace(ACCOUNT, "111111111111");
        AwsException denied = assertThrows(AwsException.class,
                () -> service.describeEventSource(arn(otherAccount), REGION));
        assertEquals("AccessDeniedException", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());

        AwsException invalid = assertThrows(AwsException.class,
                () -> service.describeEventSource(arn("nope"), REGION));
        assertTrue(invalid.getMessage().startsWith("1 validation error detected: Value 'nope' at 'eventSourceArn'"),
                invalid.getMessage());
    }

    // ──────────────────────────── List ────────────────────────────

    @Test
    void listIsOrderedByTheIdInTheArnAndFiltersByBusAndNamePrefix() {
        String other = bus("payments");
        for (String name : List.of("a-one", "b-two", "a-three")) {
            service.createEventSource(create(name, bus, STATIC_AUTH), REGION);
        }
        service.createEventSource(create("a-four", other, STATIC_AUTH), REGION);

        List<EventSourceV2> all = service.listEventSources(json("{}"), REGION).items();
        assertEquals(4, all.size());
        List<String> ids = all.stream().map(EventBridgeV2EventSources::id).toList();
        assertEquals(ids.stream().sorted().toList(), ids);

        assertEquals(Set.of("a-one", "b-two", "a-three"), names(service.listEventSources(
                json("{\"EventBusArn\":\"" + bus + "\"}"), REGION)));
        assertEquals(Set.of("a-one", "a-three", "a-four"), names(service.listEventSources(
                json("{\"NamePrefix\":\"a-\"}"), REGION)));
        assertEquals(Set.of("a-one", "a-three"), names(service.listEventSources(
                json("{\"NamePrefix\":\"a-\",\"EventBusArn\":\"" + bus + "\"}"), REGION)));
        assertEquals(Set.of(), names(service.listEventSources(json("{}"), "eu-west-1")));
    }

    @Test
    void listPagesWithMaxResultsAndNextToken() {
        for (String name : List.of("one", "two", "three")) {
            service.createEventSource(create(name, bus, STATIC_AUTH), REGION);
        }
        PaginatedResult<EventSourceV2> first = service.listEventSources(json("{\"MaxResults\":2}"), REGION);
        assertEquals(2, first.items().size());
        PaginatedResult<EventSourceV2> second = service.listEventSources(
                json("{\"MaxResults\":2,\"NextToken\":\"" + first.nextToken() + "\"}"), REGION);
        assertEquals(1, second.items().size());
        assertNull(second.nextToken());
    }

    @Test
    void listForABusThatDoesNotExistFails() {
        AwsException e = assertThrows(AwsException.class, () -> service.listEventSources(json(
                "{\"EventBusArn\":\"arn:aws:events:us-east-1:000000000000:event-busv2/none/abcdefghijklmnopqrstuvwxy\"}"),
                REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    // ──────────────────────────── Update ────────────────────────────

    @Test
    void updateWithNothingStillMovesTheModifiedTime() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        clock.advance(Duration.ofMinutes(5));
        EventSourceV2 updated = service.updateEventSource(arn(source.getArn()), REGION);
        assertEquals(NOW, updated.getCreationTime());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), updated.getLastModifiedTime());
    }

    @Test
    void updateReplacesTheDescriptionAndKeepsItWhenLeftOut() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH,
                "\"Description\":\"first\""), REGION);
        service.updateEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\",\"Description\":\"second\"}"),
                REGION);
        assertEquals("second", service.describeEventSource(arn(source.getArn()), REGION).getDescription());
        service.updateEventSource(arn(source.getArn()), REGION);
        assertEquals("second", service.describeEventSource(arn(source.getArn()), REGION).getDescription());
    }

    @Test
    void updateReplacesTheWholeConfigurationAndKeepsTheSecretAndUrl() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH,
                "\"Tags\":{\"a\":\"b\"}").deepCopy().put("Description", "d"), REGION);
        String url = service.webhookUrl(source);
        String secretArn = source.getSecretArn();
        JsonNode request = json("{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":"
                + "{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":"
                + "{\"TokenHttpHeader\":\"X-Other\"}},\"RoleArn\":\"" + ROLE + "2\",\"MetadataHttpHeaders\":[\"X-A\"]}}}");

        EventSourceV2 updated = service.updateEventSource(request, REGION);

        assertEquals(json("{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":"
                + "{\"TokenHttpHeader\":\"X-Other\"}},\"RoleArn\":\"" + ROLE + "2\",\"MetadataHttpHeaders\":[\"X-A\"]}}"),
                updated.getConfiguration());
        assertEquals(url, service.webhookUrl(updated));
        assertEquals(secretArn, updated.getSecretArn());
        assertEquals("d", updated.getDescription());
        verify(secrets, times(1)).createSecret(any(), any(), any(), any(), any(), any(), any(), any());

        service.updateEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":"
                + "{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":"
                + "{\"TokenHttpHeader\":\"X-Other\"}},\"RoleArn\":\"" + ROLE + "\"}}}"), REGION);
        assertNull(service.describeEventSource(arn(source.getArn()), REGION).getConfiguration()
                .path("WebhookEventsConfiguration").get("MetadataHttpHeaders"));
    }

    @Test
    void aRefusedUpdateChangesNothing() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        clock.advance(Duration.ofMinutes(1));
        JsonNode before = source.getConfiguration().deepCopy();
        AwsException e = assertThrows(AwsException.class, () -> service.updateEventSource(json(
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Description\":\"changed\",\"Configuration\":"
                        + "{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":" + BASIC_AUTH + ",\"RoleArn\":\""
                        + ROLE + "\"}}}"), REGION));
        assertEquals("The webhook authentication type can't be changed after the EventSource is created",
                e.getMessage());
        EventSourceV2 stored = service.describeEventSource(arn(source.getArn()), REGION);
        assertEquals(before, stored.getConfiguration());
        assertNull(stored.getDescription());
        assertEquals(NOW, stored.getLastModifiedTime());
    }

    @Test
    void updateRefusesCredentialsAndRequiresTheRequiredMembers() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        AwsException secret = assertThrows(AwsException.class, () -> service.updateEventSource(json(
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                        + "{\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Token\","
                        + "\"SecretValue\":\"another-secret\"}},\"RoleArn\":\"" + ROLE + "\"}}}"), REGION));
        assertTrue(secret.getMessage().startsWith("UpdateEventSource does not accept SecretValue"));
        AwsException missing = assertThrows(AwsException.class, () -> service.updateEventSource(json(
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                        + "{}}}"), REGION));
        assertTrue(missing.getMessage().startsWith("2 validation errors detected: Value null at "), missing.getMessage());
    }

    // ──────────────────────────── Delete ────────────────────────────

    @Test
    void deleteSchedulesTheSecretForDeletionAndRemovesTheSource() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        service.deleteEventSource(arn(source.getArn()), REGION);

        verify(secrets).deleteSecret(source.getSecretArn(), null, false, REGION);
        assertThrows(AwsException.class, () -> service.describeEventSource(arn(source.getArn()), REGION));
        assertEquals(Set.of(), names(service.listEventSources(json("{}"), REGION)));
        service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
    }

    @Test
    void deleteToleratesASecretThatIsAlreadyGone() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        doThrow(new AwsException("ResourceNotFoundException", "gone", 400)).when(secrets)
                .deleteSecret(any(), any(), eq(false), any());
        service.deleteEventSource(arn(source.getArn()), REGION);
        assertEquals(Set.of(), names(service.listEventSources(json("{}"), REGION)));
    }

    @Test
    void deleteToleratesASecretThatDescribeCannotFind() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        when(secrets.describeSecret(any(), any()))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 400));
        service.deleteEventSource(arn(source.getArn()), REGION);
        assertEquals(Set.of(), names(service.listEventSources(json("{}"), REGION)));
        verify(secrets, never()).deleteSecret(any(), any(), eq(false), any());
    }

    @Test
    void deleteKeepsTheSourceWhenTheSecretFailsToDeleteForAnotherReason() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        AwsException failure = new AwsException("InternalServiceError", "boom", 500);
        doThrow(failure).when(secrets).deleteSecret(any(), any(), eq(false), any());
        AwsException e = assertThrows(AwsException.class,
                () -> service.deleteEventSource(arn(source.getArn()), REGION));
        assertSame(failure, e);
        assertEquals(Set.of("hook"), names(service.listEventSources(json("{}"), REGION)));
        assertEquals(source.getArn(), service.describeEventSource(arn(source.getArn()), REGION).getArn());
    }

    @Test
    void deleteDoesNotScheduleASecretThatIsAlreadyScheduledForDeletion() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        Secret scheduled = new Secret();
        scheduled.setDeletedDate(NOW.plus(Duration.ofDays(7)));
        when(secrets.describeSecret(any(), any())).thenReturn(scheduled);
        service.deleteEventSource(arn(source.getArn()), REGION);
        assertEquals(Set.of(), names(service.listEventSources(json("{}"), REGION)));
        verify(secrets, never()).deleteSecret(any(), any(), eq(false), any());
    }

    @Test
    void deleteOfAMissingSourceDeletesNoSecret() {
        String missing = "arn:aws:events:us-east-1:000000000000:event-sourcev2/webhook/x/abcdefghijklmnopqrstuvwxy";
        assertThrows(AwsException.class, () -> service.deleteEventSource(arn(missing), REGION));
        verify(secrets, never()).deleteSecret(any(), any(), eq(false), any());
    }

    // ──────────────────────────── DeleteEventBus ────────────────────────────

    @Test
    void aBusWithEventSourcesCannotBeDeleted() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        AwsException e = assertThrows(AwsException.class, () -> service.deleteEventBus(bus, REGION));
        assertEquals("ResourceInUseException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals("The event bus cannot be deleted because it has one or more event sources that are not "
                + "revoked. Delete or revoke them and retry.", e.getMessage());

        service.deleteEventSource(arn(source.getArn()), REGION);
        service.deleteEventBus(bus, REGION);
    }

    @Test
    void aBusWithSubscribersAndEventSourcesNamesBoth() {
        service.createSubscriber(json("{\"Name\":\"s\",\"EventBusArn\":\"" + bus + "\",\"InvokeConfiguration\":"
                + "{\"TargetArn\":\"arn:aws:sqs:us-east-1:000000000000:q\",\"RoleArn\":\"" + ROLE + "\"}}"), REGION);
        service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        AwsException e = assertThrows(AwsException.class, () -> service.deleteEventBus(bus, REGION));
        assertEquals("The event bus cannot be deleted because it has subscribers and event sources that are not "
                + "revoked. Delete or revoke them and retry.", e.getMessage());
    }

    @Test
    void aBusWithOnlySubscribersKeepsTheExistingMessage() {
        service.createSubscriber(json("{\"Name\":\"s\",\"EventBusArn\":\"" + bus + "\",\"InvokeConfiguration\":"
                + "{\"TargetArn\":\"arn:aws:sqs:us-east-1:000000000000:q\",\"RoleArn\":\"" + ROLE + "\"}}"), REGION);
        AwsException e = assertThrows(AwsException.class, () -> service.deleteEventBus(bus, REGION));
        assertEquals("Event bus orders still has subscribers; delete them first.", e.getMessage());
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private String bus(String name) {
        EventBusV2 created = service.createEventBus(json("{\"Name\":\"" + name + "\"}"), REGION);
        return created.getArn();
    }

    private String capturedSecretString() {
        ArgumentCaptor<String> secretString = ArgumentCaptor.forClass(String.class);
        verify(secrets).createSecret(any(), secretString.capture(), any(), any(), any(), any(), any(), any());
        return secretString.getValue();
    }

    private static Set<String> names(PaginatedResult<EventSourceV2> page) {
        return page.items().stream().map(EventSourceV2::getName).collect(Collectors.toSet());
    }

    private static ObjectNode create(String name, String bus, String auth,
                                                                          String... extra) {
        String more = extra.length == 0 ? "" : "," + String.join(",", extra);
        return (ObjectNode) json("{\"Name\":\"" + name + "\",\"EventBusArn\":\""
                + bus + "\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":" + auth
                + ",\"RoleArn\":\"" + ROLE + "\"}}" + more + "}");
    }

    private static JsonNode arn(String arn) {
        return json("{\"EventSourceArn\":\"" + arn + "\"}");
    }

    private static JsonNode json(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
