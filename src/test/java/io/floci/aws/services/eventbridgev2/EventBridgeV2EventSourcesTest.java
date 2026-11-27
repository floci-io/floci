package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.eventbridgev2.model.EventBusV2;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsKey;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import io.floci.aws.testing.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
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
    private TlsCertificateManager certificateManager;
    private EventBridgeV2Service service;
    private ManualClock clock;
    private EventBridgeService eventBridge;
    private KmsService kms;
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
        clock = new ManualClock(NOW);
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        certificateManager = mock(TlsCertificateManager.class);
        eventBridge = mock(EventBridgeService.class);
        kms = mock(KmsService.class);
        EventBridgeV2EventSources eventSources = new EventBridgeV2EventSources(
                AccountAwareStorageBackend.inMemory(ACCOUNT), secrets, regionResolver, clock, "http://floci.test:4566",
                certificateManager, eventBridge, kms);
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
        String host = "[a-z][a-z2-7]{37}\\.c01\\.events-webhook\\.us-east-1\\.floci\\.test:4566";
        assertTrue(url.matches("http://" + host + "/k1[A-Za-z0-9_-]{64}/"), url);
        assertTrue(source.getWebhookHostId().startsWith("a"));
    }

    @Test
    void createAsksTheCertificateToNameTheWebhookHostsOfTheRegion() {
        service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);

        verify(certificateManager).ensureHost("*.c01.events-webhook.us-east-1.floci.test");
    }

    @Test
    void refusedCreatesAskForNoCertificateName() {
        assertThrows(AwsException.class, () -> service.createEventSource(
                create("hook", "arn:aws:events:us-east-1:000000000000:event-bus/missing", STATIC_AUTH), REGION));
        service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        assertThrows(AwsException.class, () -> service.createEventSource(
                create("hook", bus, BASIC_AUTH), REGION));

        verify(certificateManager, times(1)).ensureHost(any());
    }

    @Test
    void describeAsksTheCertificateToNameTheWebhookHostsOfTheRegion() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);

        service.describeEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\"}"), REGION);

        verify(certificateManager, times(2)).ensureHost("*.c01.events-webhook.us-east-1.floci.test");
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
        assertEquals("Resource does not exist", notFound.getMessage());
    }

    @Test
    void createNamesTheBusWhenOnlyTheIdInItsArnDiffers() {
        String otherId = bus.substring(0, bus.lastIndexOf('/') + 1) + "zzzzzzzzzzzzzzzzzzzzzzzzz";
        AwsException e = assertThrows(AwsException.class,
                () -> service.createEventSource(create("hook", otherId, STATIC_AUTH), REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
        assertEquals("Event bus not found: orders", e.getMessage());
    }

    @Test
    void createWithoutAClientTokenIsRefusedBeforeTheSemanticRules() {
        ObjectNode request = create("hook", bus, "{\"BasicHttpAuthConfiguration\":{\"Username\":\"u\"}}");
        request.remove("ClientToken");
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(request, REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("ClientToken is required", e.getMessage());
    }

    @Test
    void createChecksTheAccountBeforeTheClientToken() {
        String otherAccount = "arn:aws:events:us-east-1:111111111111:event-busv2/orders/abcdefghijklmnopqrstuvwxy";
        ObjectNode request = create("hook", otherAccount, STATIC_AUTH);
        request.remove("ClientToken");
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(request, REGION));
        assertEquals("AccessDeniedException", e.getErrorCode());
        assertEquals(403, e.getHttpStatus());
    }

    @Test
    void createChecksTheRegionAndTheConstraintsBeforeTheClientToken() {
        String otherRegion = "arn:aws:events:eu-west-1:000000000000:event-busv2/orders/abcdefghijklmnopqrstuvwxy";
        ObjectNode regionRequest = create("hook", otherRegion, STATIC_AUTH);
        regionRequest.remove("ClientToken");
        assertEquals("The region or partition in EventBusArn does not match this endpoint",
                assertThrows(AwsException.class, () -> service.createEventSource(regionRequest, REGION)).getMessage());

        ObjectNode constraintRequest = create("bad name", bus, STATIC_AUTH);
        constraintRequest.remove("ClientToken");
        assertEquals("1 validation error detected: Value 'bad name' at 'name' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: (?!aws\\.)[A-Za-z0-9][\\.\\-_A-Za-z0-9]*",
                assertThrows(AwsException.class, () -> service.createEventSource(constraintRequest, REGION))
                        .getMessage());
    }

    @Test
    void createWithNoVariantAndNoClientTokenNamesTheClientToken() {
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(json(
                "{\"Name\":\"hook\",\"EventBusArn\":\"" + bus + "\",\"Configuration\":{}}"), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals("ClientToken is required", e.getMessage());
    }

    @Test
    void createWithTwoVariantsAndNoClientTokenNamesTheClientToken() {
        ObjectNode request = (ObjectNode) twoVariants(bus);
        request.remove("ClientToken");
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(request, REGION));
        assertEquals("ClientToken is required", e.getMessage());
    }

    @Test
    void createWithTwoVariantsAndAClientTokenNamesTheSubField() {
        assertExactlyOne(twoVariants(bus));
    }

    @Test
    void createWithNoVariantAndAClientTokenNamesTheSubField() {
        assertExactlyOne(json("{\"Name\":\"hook\",\"ClientToken\":\"t\",\"EventBusArn\":\"" + bus
                + "\",\"Configuration\":{}}"));
    }

    @Test
    void createWithAnyVariantCountOnABusOfAnotherAccountIsAccessDenied() {
        String otherAccount = "arn:aws:events:us-east-1:111111111111:event-busv2/orders/abcdefghijklmnopqrstuvwxy";
        JsonNode none = json("{\"Name\":\"hook\",\"ClientToken\":\"t\",\"EventBusArn\":\"" + otherAccount
                + "\",\"Configuration\":{}}");
        for (JsonNode request : List.of(twoVariants(otherAccount), none)) {
            AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(request, REGION));
            assertEquals("AccessDeniedException", e.getErrorCode());
            assertEquals(403, e.getHttpStatus());
            assertEquals("Access denied.", e.getMessage());
        }
    }

    private void assertExactlyOne(JsonNode request) {
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(request, REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("Exactly one Configuration sub-field must be provided", e.getMessage());
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
                new RegionResolver(REGION, ACCOUNT), clock, "http://floci.test:4566",
                certificateManager, eventBridge, kms);

        EventBusV2 existing = new EventBusV2();
        existing.setArn(bus);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> eventSources.create(
                create("hook", bus, STATIC_AUTH), REGION, name -> Optional.of(existing)));

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
        assertEquals(404, e.getHttpStatus());
        assertEquals("Resource does not exist", e.getMessage());
    }

    @Test
    void listForABusOfAnotherAccountDoesNotExistEvenWhenTheNameIsOurs() {
        AwsException e = assertThrows(AwsException.class, () -> service.listEventSources(json(
                "{\"EventBusArn\":\"arn:aws:events:us-east-1:111111111111:event-busv2/orders/abcdefghijklmnopqrstuvwxy\"}"),
                REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
        assertEquals("Resource does not exist", e.getMessage());
    }

    @Test
    void listNamesTheBusWhenOnlyTheIdInItsArnDiffers() {
        String otherId = bus.substring(0, bus.lastIndexOf('/') + 1) + "zzzzzzzzzzzzzzzzzzzzzzzzz";
        AwsException e = assertThrows(AwsException.class,
                () -> service.listEventSources(json("{\"EventBusArn\":\"" + otherId + "\"}"), REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
        assertEquals("Event bus not found: orders", e.getMessage());
    }

    @Test
    void listChecksConstraintsThenTheRegionThenTheTokenThenTheBus() {
        String missing = "arn:aws:events:us-east-1:000000000000:event-busv2/none/abcdefghijklmnopqrstuvwxy";
        String otherRegion = "arn:aws:events:eu-west-1:000000000000:event-busv2/none/abcdefghijklmnopqrstuvwxy";

        AwsException constraint = assertThrows(AwsException.class, () -> service.listEventSources(json(
                "{\"MaxResults\":0,\"NextToken\":\"garbage\",\"EventBusArn\":\"" + otherRegion + "\"}"), REGION));
        assertEquals("1 validation error detected: Value '0' at 'maxResults' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1", constraint.getMessage());

        AwsException region = assertThrows(AwsException.class, () -> service.listEventSources(json(
                "{\"NextToken\":\"garbage\",\"EventBusArn\":\"" + otherRegion + "\"}"), REGION));
        assertEquals("InvalidInputException", region.getErrorCode());
        assertEquals("The region or partition in EventBusArn does not match this endpoint", region.getMessage());

        AwsException token = assertThrows(AwsException.class, () -> service.listEventSources(json(
                "{\"NextToken\":\"garbage\",\"EventBusArn\":\"" + missing + "\"}"), REGION));
        assertEquals("InvalidInputException", token.getErrorCode());
        assertEquals(400, token.getHttpStatus());
        assertEquals("Invalid nextToken", token.getMessage());
    }

    @Test
    void listRefusesATokenOfAnotherListing() {
        service.createEventBus(json("{\"Name\":\"extra\"}"), REGION);
        String busToken = service.listEventBuses(json("{\"MaxResults\":1}"), REGION).nextToken();
        AwsException e = assertThrows(AwsException.class, () -> service.listEventSources(
                json("{\"NextToken\":\"" + busToken + "\"}"), REGION));
        assertEquals("Invalid nextToken", e.getMessage());
    }

    @Test
    void listTokenKeepsWorkingWithTheSameFilters() {
        for (String name : List.of("a-one", "a-two", "a-three")) {
            service.createEventSource(create(name, bus, STATIC_AUTH), REGION);
        }
        String filters = "\"EventBusArn\":\"" + bus + "\",\"NamePrefix\":\"a-\",\"MaxResults\":2";
        PaginatedResult<EventSourceV2> first = service.listEventSources(json("{" + filters + "}"), REGION);

        PaginatedResult<EventSourceV2> second = service.listEventSources(
                json("{" + filters + ",\"NextToken\":\"" + first.nextToken() + "\"}"), REGION);

        assertEquals(1, second.items().size());
    }

    @Test
    void listRefusesATokenOfAListingWithOtherFilters() {
        for (String name : List.of("a-one", "a-two", "a-three")) {
            service.createEventSource(create(name, bus, STATIC_AUTH), REGION);
        }
        String token = service.listEventSources(json("{\"NamePrefix\":\"a-\",\"MaxResults\":1}"), REGION)
                .nextToken();
        String withBusToken = service.listEventSources(json("{\"EventBusArn\":\"" + bus + "\",\"MaxResults\":1}"),
                REGION).nextToken();
        String unfilteredToken = service.listEventSources(json("{\"MaxResults\":1}"), REGION).nextToken();

        for (String request : List.of(
                "{\"NamePrefix\":\"b-\",\"NextToken\":\"" + token + "\"}",
                "{\"NamePrefix\":\"a-\",\"EventBusArn\":\"" + bus + "\",\"NextToken\":\"" + token + "\"}",
                "{\"EventBusArn\":\"" + bus + "\",\"NamePrefix\":\"a-\",\"NextToken\":\"" + withBusToken + "\"}",
                "{\"NamePrefix\":\"a-\",\"NextToken\":\"" + unfilteredToken + "\"}")) {
            AwsException e = assertThrows(AwsException.class, () -> service.listEventSources(json(request), REGION),
                    request);
            assertEquals("InvalidInputException", e.getErrorCode());
            assertEquals("Invalid nextToken", e.getMessage());
        }
    }

    @Test
    void listRefusesATokenOfAnotherRegion() {
        for (String name : List.of("a-one", "a-two")) {
            service.createEventSource(create(name, bus, STATIC_AUTH), REGION);
        }
        String token = service.listEventSources(json("{\"MaxResults\":1}"), REGION).nextToken();

        AwsException e = assertThrows(AwsException.class, () -> service.listEventSources(
                json("{\"NextToken\":\"" + token + "\"}"), "eu-west-1"));

        assertEquals("Invalid nextToken", e.getMessage());
    }

    @Test
    void createDoesNotKeepMembersTheModelDoesNotDeclare() {
        EventSourceV2 source = service.createEventSource(create("hook", bus,
                "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Token\",\"secretValue\":\"s3cret!!\","
                        + "\"password\":\"p\"},\"Extra\":1}").deepCopy(), REGION);
        ObjectNode withExtra = create("hook2", bus, STATIC_AUTH);
        ((ObjectNode) withExtra.path("Configuration").path("WebhookEventsConfiguration")).put("Unknown", "x");

        EventSourceV2 other = service.createEventSource(withExtra, REGION);

        assertEquals(json("{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":" + STATIC_AUTH
                + ",\"RoleArn\":\"" + ROLE + "\"}}"), source.getConfiguration());
        assertEquals(source.getConfiguration(), other.getConfiguration());
    }

    @Test
    void updateDoesNotKeepMembersTheModelDoesNotDeclare() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);

        EventSourceV2 updated = service.updateEventSource(json("{\"EventSourceArn\":\"" + source.getArn()
                + "\",\"Configuration\":{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":"
                + "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Token\",\"secretValue\":\"s\","
                + "\"password\":\"p\"}},\"RoleArn\":\"" + ROLE + "\",\"Unknown\":true}}}"), REGION);

        assertEquals(json("{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":" + STATIC_AUTH
                + ",\"RoleArn\":\"" + ROLE + "\"}}"), updated.getConfiguration());
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
    void updateStoresANewSourceAndLeavesTheOneAReaderHeldUnchanged() {
        EventSourceV2 held = service.createEventSource(create("hook", bus, STATIC_AUTH,
                "\"Description\":\"first\""), REGION);
        clock.advance(Duration.ofMinutes(5));

        EventSourceV2 updated = service.updateEventSource(json("{\"EventSourceArn\":\"" + held.getArn()
                + "\",\"Description\":\"second\"}"), REGION);

        assertEquals("first", held.getDescription());
        assertEquals(NOW, held.getLastModifiedTime());
        assertEquals("second", updated.getDescription());
        assertEquals(held.getSecretArn(), updated.getSecretArn());
        assertEquals(service.webhookUrl(held), service.webhookUrl(updated));
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

    // ──────────────────────────── KmsKeyIdentifier ────────────────────────────

    private static final String KEY_ARN =
            "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";

    private void customerKey(String identifier) {
        stubKey(identifier, KEY_ARN, false);
    }

    private void stubKey(String identifier, String keyArn, boolean awsManaged) {
        KmsKey key = new KmsKey();
        key.setArn(keyArn);
        when(kms.describeKey(identifier, REGION)).thenReturn(key);
        when(kms.isAwsManagedKey(keyArn)).thenReturn(awsManaged);
    }

    private void missingKey(String identifier) {
        when(kms.describeKey(identifier, REGION)).thenThrow(
                new AwsException("NotFoundException", "Alias is not found.", 400));
    }

    private static ObjectNode withKey(ObjectNode request, String identifier) {
        ((ObjectNode) request.get("Configuration").get("WebhookEventsConfiguration"))
                .put("KmsKeyIdentifier", identifier);
        return request;
    }

    private JsonNode updateWithKey(EventSourceV2 source, String identifier) {
        String key = identifier == null ? "" : ",\"KmsKeyIdentifier\":\"" + identifier + "\"";
        return json("{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":"
                + "{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":" + STATIC_AUTH + ",\"RoleArn\":\"" + ROLE
                + "\"" + key + "}}}");
    }

    private void assertCreateFails(ObjectNode request, String code, int status, String message) {
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(request, REGION));
        assertEquals(code, e.getErrorCode());
        assertEquals(status, e.getHttpStatus());
        assertEquals(message, e.getMessage());
    }

    private static final String KEY_MISSING = "KMS_KEY_NOT_FOUND: The event bus's KMS key does not exist.";

    @Test
    void createRefusesAKeyArnOfAnotherRegionBeforeAnotherAccountAndBeforeTheClientToken() {
        ObjectNode request = withKey(create("hook", bus, STATIC_AUTH),
                "arn:aws:kms:eu-west-1:111111111111:key/1234abcd-12ab-34cd-56ef-1234567890ab");
        request.remove("ClientToken");
        assertCreateFails(request, "InvalidInputException", 400,
                "WebhookEventsConfiguration.KmsKeyIdentifier region 'eu-west-1' must match the caller's region "
                        + "'us-east-1'");
    }

    @Test
    void createRefusesAKeyOrAliasArnOfAnotherAccountBeforeTheBusLookup() {
        String missingBus = "arn:aws:events:us-east-1:000000000000:event-busv2/missing/abcdefghijklmnopqrstuvwxy";
        String message = "WebhookEventsConfiguration.KmsKeyIdentifier account '111111111111' must match the "
                + "caller's account '000000000000'";
        assertCreateFails(withKey(create("hook", missingBus, STATIC_AUTH),
                "arn:aws:kms:us-east-1:111111111111:key/1234abcd-12ab-34cd-56ef-1234567890ab"),
                "InvalidInputException", 400, message);
        assertCreateFails(withKey(create("hook", missingBus, STATIC_AUTH),
                "arn:aws:kms:us-east-1:111111111111:alias/mine"), "InvalidInputException", 400, message);
    }

    @Test
    void createRefusesAKeyArnOfAnotherRegionBeforeAuthChecks() {
        ObjectNode request = withKey(create("hook", bus, "{\"StaticTokenAuthConfiguration\":"
                + "{\"TokenHttpHeader\":\"Host\"}}"), "arn:aws:kms:eu-west-1:000000000000:alias/mine");
        assertCreateFails(request, "InvalidInputException", 400,
                "WebhookEventsConfiguration.KmsKeyIdentifier region 'eu-west-1' must match the caller's region "
                        + "'us-east-1'");
    }

    @Test
    void createAnswersAMissingKeyAfterTheBusLookup() {
        missingKey("alias/gone");
        String missingBus = "arn:aws:events:us-east-1:000000000000:event-busv2/missing/abcdefghijklmnopqrstuvwxy";
        assertCreateFails(withKey(create("hook", missingBus, STATIC_AUTH), "alias/gone"),
                "ResourceNotFoundException", 404, "Resource does not exist");
        assertCreateFails(withKey(create("hook", bus, STATIC_AUTH), "alias/gone"),
                "AccessDeniedException", 403, KEY_MISSING);
        verify(secrets, never()).createSecret(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createAnswersAMissingKeyForAReplayBeforeTheIdempotencyConflict() {
        service.createEventSource(create("hook", bus, STATIC_AUTH, "\"ClientToken\":\"t-1\""), REGION);
        missingKey("alias/gone");
        assertCreateFails(withKey(create("hook", bus, STATIC_AUTH, "\"ClientToken\":\"t-1\""), "alias/gone"),
                "AccessDeniedException", 403, KEY_MISSING);
    }

    @Test
    void createAnswersAMissingKeyBeforeTheNameClash() {
        service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        missingKey("alias/gone");
        assertCreateFails(withKey(create("hook", bus, STATIC_AUTH, "\"ClientToken\":\"other\""), "alias/gone"),
                "AccessDeniedException", 403, KEY_MISSING);
    }

    @Test
    void createStillReplaysWhenTheKeyExists() {
        customerKey("alias/mine");
        ObjectNode request = withKey(create("hook", bus, STATIC_AUTH, "\"ClientToken\":\"t-1\""), "alias/mine");
        EventSourceV2 first = service.createEventSource(request, REGION);
        assertEquals(first.getArn(), service.createEventSource(request, REGION).getArn());
    }

    @Test
    void createRefusesAnAwsManagedKeyAfterTheNameClash() {
        stubKey("alias/aws/secretsmanager", "arn:aws:kms:us-east-1:000000000000:key/aws-key", true);
        service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        assertCreateFails(withKey(create("hook", bus, STATIC_AUTH, "\"ClientToken\":\"other\""),
                "alias/aws/secretsmanager"), "ResourceAlreadyExistsException", 409, "Resource already exists");
        assertCreateFails(withKey(create("second", bus, STATIC_AUTH), "alias/aws/secretsmanager"),
                "InvalidInputException", 400, "The KMS key "
                        + "arn:aws:kms:us-east-1:000000000000:alias/aws/secretsmanager can't be used for a webhook "
                        + "EventSource because it is an AWS managed key. Supply a customer managed key, or omit "
                        + "KmsKeyIdentifier to use the AWS managed key aws/secretsmanager");
        verify(secrets, times(1)).createSecret(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createNamesTheKeyArnOrTheAliasArnOfAnAwsManagedKey() {
        String keyArn = "arn:aws:kms:us-east-1:000000000000:key/aws-key";
        String aliasArn = "arn:aws:kms:us-east-1:000000000000:alias/aws/secretsmanager";
        stubKey("aws-key", keyArn, true);
        stubKey(keyArn, keyArn, true);
        stubKey(aliasArn, keyArn, true);
        String tail = " can't be used for a webhook EventSource because it is an AWS managed key. Supply a "
                + "customer managed key, or omit KmsKeyIdentifier to use the AWS managed key aws/secretsmanager";
        assertCreateFails(withKey(create("a", bus, STATIC_AUTH), "aws-key"), "InvalidInputException", 400,
                "The KMS key " + keyArn + tail);
        assertCreateFails(withKey(create("b", bus, STATIC_AUTH), keyArn), "InvalidInputException", 400,
                "The KMS key " + keyArn + tail);
        assertCreateFails(withKey(create("c", bus, STATIC_AUTH), aliasArn), "InvalidInputException", 400,
                "The KMS key " + aliasArn + tail);
    }

    @Test
    void createEncryptsTheSecretWithTheKeyAndEchoesTheIdentifierAsGiven() {
        customerKey("alias/mine");
        EventSourceV2 source = service.createEventSource(withKey(create("hook", bus, STATIC_AUTH), "alias/mine"),
                REGION);

        ArgumentCaptor<String> kmsKeyId = ArgumentCaptor.forClass(String.class);
        verify(secrets).createSecret(any(), any(), any(), any(), kmsKeyId.capture(), any(), any(), any());
        assertEquals("alias/mine", kmsKeyId.getValue());
        assertEquals("alias/mine", source.getConfiguration().get("WebhookEventsConfiguration")
                .get("KmsKeyIdentifier").textValue());
    }

    @Test
    void createAcceptsAnEmptyKeyIdentifierWithNoKeyAndLeavesItOutOfTheEcho() {
        EventSourceV2 source = service.createEventSource(withKey(create("hook", bus, STATIC_AUTH), ""), REGION);

        ArgumentCaptor<String> kmsKeyId = ArgumentCaptor.forClass(String.class);
        verify(secrets).createSecret(any(), any(), any(), any(), kmsKeyId.capture(), any(), any(), any());
        assertNull(kmsKeyId.getValue());
        assertFalse(source.getConfiguration().get("WebhookEventsConfiguration").has("KmsKeyIdentifier"));
        verify(kms, never()).describeKey(any(), any());
    }

    @Test
    void createFailsBeforeStoringAnythingWhenTheSecretRefusesTheKey() {
        customerKey("alias/disabled");
        AwsException refused = new AwsException("EncryptionFailure", "Secrets Manager can't encrypt", 400);
        when(secrets.createSecret(any(), any(), any(), any(), eq("alias/disabled"), any(), any(), any()))
                .thenThrow(refused);
        assertCreateFails(withKey(create("hook", bus, STATIC_AUTH), "alias/disabled"), "EncryptionFailure", 400,
                "Secrets Manager can't encrypt");
        assertTrue(names(service.listEventSources(json("{}"), REGION)).isEmpty());
    }

    @Test
    void updateChecksTheKeyArnAfterTheSecretRefusalAndTheAuthTypeAndBeforeTheAuthHeaders() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        String foreign = "arn:aws:kms:eu-west-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";
        String message = "WebhookEventsConfiguration.KmsKeyIdentifier region 'eu-west-1' must match the caller's "
                + "region 'us-east-1'";

        AwsException secret = assertThrows(AwsException.class, () -> service.updateEventSource(json(
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                        + "{\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Token\","
                        + "\"SecretValue\":\"secret-value\"}},\"RoleArn\":\"" + ROLE + "\",\"KmsKeyIdentifier\":\"" + foreign
                        + "\"}}}"), REGION));
        assertTrue(secret.getMessage().startsWith("UpdateEventSource does not accept SecretValue"));

        AwsException type = assertThrows(AwsException.class, () -> service.updateEventSource(json(
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                        + "{\"AuthConfiguration\":" + BASIC_AUTH + ",\"RoleArn\":\"" + ROLE
                        + "\",\"KmsKeyIdentifier\":\"" + foreign + "\"}}}"), REGION));
        assertTrue(type.getMessage().startsWith("The webhook authentication type can't be changed"));

        AwsException header = assertThrows(AwsException.class, () -> service.updateEventSource(json(
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                        + "{\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"Host\"}},"
                        + "\"RoleArn\":\"" + ROLE + "\",\"KmsKeyIdentifier\":\"" + foreign + "\"}}}"), REGION));
        assertEquals(message, header.getMessage());

        AwsException account = assertThrows(AwsException.class, () -> service.updateEventSource(
                updateWithKey(source, "arn:aws:kms:us-east-1:111111111111:alias/mine"), REGION));
        assertEquals("WebhookEventsConfiguration.KmsKeyIdentifier account '111111111111' must match the caller's "
                + "account '000000000000'", account.getMessage());
    }

    @Test
    void updateAnswersARevokedSourceBeforeTheKeyLookup() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        service.revokeResource(json("{\"Arn\":\"" + source.getArn() + "\"}"), REGION);
        missingKey("alias/gone");
        AwsException e = assertThrows(AwsException.class,
                () -> service.updateEventSource(updateWithKey(source, "alias/gone"), REGION));
        assertEquals("EventSource is revoked and cannot be updated", e.getMessage());
    }

    @Test
    void updateAnswersAMissingKeyThenAnAwsManagedKey() {
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);
        missingKey("alias/gone");
        AwsException missing = assertThrows(AwsException.class,
                () -> service.updateEventSource(updateWithKey(source, "alias/gone"), REGION));
        assertEquals("AccessDeniedException", missing.getErrorCode());
        assertEquals(KEY_MISSING, missing.getMessage());

        stubKey("alias/aws/secretsmanager", "arn:aws:kms:us-east-1:000000000000:key/aws-key", true);
        AwsException managed = assertThrows(AwsException.class,
                () -> service.updateEventSource(updateWithKey(source, "alias/aws/secretsmanager"), REGION));
        assertEquals("InvalidInputException", managed.getErrorCode());
        assertTrue(managed.getMessage().startsWith(
                "The KMS key arn:aws:kms:us-east-1:000000000000:alias/aws/secretsmanager can't be used"));
        verify(secrets, never()).updateSecret(any(), any(), any(), any());
    }

    @Test
    void updateReEncryptsTheSecretWhenTheKeyChanges() {
        customerKey("alias/one");
        customerKey("alias/two");
        EventSourceV2 source = service.createEventSource(create("hook", bus, STATIC_AUTH), REGION);

        service.updateEventSource(updateWithKey(source, "alias/one"), REGION);
        verify(secrets).updateSecret(source.getSecretArn(), null, "alias/one", REGION);

        service.updateEventSource(updateWithKey(source, "alias/one"), REGION);
        verify(secrets, times(1)).updateSecret(any(), any(), any(), any());

        service.updateEventSource(updateWithKey(source, "alias/two"), REGION);
        verify(secrets).updateSecret(source.getSecretArn(), null, "alias/two", REGION);

        EventSourceV2 updated = service.updateEventSource(updateWithKey(source, null), REGION);
        verify(secrets).updateSecret(source.getSecretArn(), null, "", REGION);
        assertFalse(updated.getConfiguration().get("WebhookEventsConfiguration").has("KmsKeyIdentifier"));
    }

    @Test
    void updateWithAnEmptyKeyIdentifierEchoesItAndReturnsTheSecretToTheDefaultKey() {
        customerKey("alias/one");
        EventSourceV2 source = service.createEventSource(withKey(create("hook", bus, STATIC_AUTH), "alias/one"),
                REGION);

        EventSourceV2 updated = service.updateEventSource(updateWithKey(source, ""), REGION);

        assertEquals("", updated.getConfiguration().get("WebhookEventsConfiguration").get("KmsKeyIdentifier")
                .textValue());
        verify(secrets).updateSecret(source.getSecretArn(), null, "", REGION);
        verify(kms, times(1)).describeKey(any(), any());
    }

    @Test
    void updateWithAnEmptyKeyIdentifierOnASourceWithNoKeyLeavesTheSecretAlone() {
        EventSourceV2 source = service.createEventSource(withKey(create("hook", bus, STATIC_AUTH), ""), REGION);
        service.updateEventSource(updateWithKey(source, ""), REGION);
        verify(secrets, never()).updateSecret(any(), any(), any(), any());
    }

    @Test
    void anUpdateWithNoConfigurationLeavesTheKeyAlone() {
        customerKey("alias/one");
        EventSourceV2 source = service.createEventSource(withKey(create("hook", bus, STATIC_AUTH), "alias/one"),
                REGION);
        service.updateEventSource(json("{\"EventSourceArn\":\"" + source.getArn() + "\",\"Description\":\"d\"}"),
                REGION);
        verify(secrets, never()).updateSecret(any(), any(), any(), any());
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

    private static ObjectNode create(String name, String bus, String auth, String... extra) {
        String more = extra.length == 0 ? "" : "," + String.join(",", extra);
        String token = "t" + Integer.toHexString((name + bus + auth + more).hashCode());
        return (ObjectNode) json("{\"ClientToken\":\"" + token + "\",\"Name\":\"" + name
                + "\",\"EventBusArn\":\"" + bus + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                + "{\"AuthConfiguration\":" + auth + ",\"RoleArn\":\"" + ROLE + "\"}}" + more + "}");
    }

    private static JsonNode twoVariants(String busArn) {
        ObjectNode request = create("hook", busArn, STATIC_AUTH);
        ((ObjectNode) request.get("Configuration")).putObject("AwsServiceEventsConfiguration")
                .put("AwsService", "aws.s3");
        return request;
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
}
