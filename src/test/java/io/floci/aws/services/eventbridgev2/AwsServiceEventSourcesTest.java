package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.eventbridge.model.Target;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import io.floci.aws.testing.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwsServiceEventSourcesTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUEUE = "arn:aws:sqs:us-east-1:000000000000:dead";
    private static final String ROLE = "arn:aws:iam::000000000000:role/Delivery";
    private static final String PARTNER = "arn:aws:events:us-east-1:111111111111:event-source/aws.partner/"
            + "example.com/123/orders";
    private static final String RULE_ARN_PREFIX = "arn:aws:events:us-east-1:000000000000:rule/";
    private static final String PROVISIONING_FAILED =
            "EventSource forwarding could not be provisioned: InvalidEventPatternException";

    private EventBridgeService eventBridge;
    private EventBridgeV2Delivery delivery;
    private EventBridgeV2Service service;
    private ManualClock clock;
    private TlsCertificateManager certificateManager;
    private String bus;

    @BeforeEach
    void setUp() {
        eventBridge = mock(EventBridgeService.class);
        when(eventBridge.isManagedRule(any(), any())).thenReturn(true);
        delivery = mock(EventBridgeV2Delivery.class);
        certificateManager = mock(TlsCertificateManager.class);
        clock = new ManualClock(NOW);
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        Secret secret = new Secret();
        secret.setArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:events-webhook!x-AbCdEf");
        when(secrets.createSecret(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(secret);
        EventBridgeV2EventSources eventSources = new EventBridgeV2EventSources(
                AccountAwareStorageBackend.inMemory(ACCOUNT), secrets, regionResolver, clock,
                "http://floci.test:4566", certificateManager, eventBridge);
        service = new EventBridgeV2Service(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), regionResolver, delivery, eventSources, clock, true);
        bus = service.createEventBus(json("{\"Name\":\"orders\"}"), REGION).getArn();
    }

    // ──────────────────────────── Create ────────────────────────────

    @Test
    void createMintsAnActiveSourceWithNoWebhookDetails() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);

        assertTrue(source.getArn().matches(
                "arn:aws:events:us-east-1:000000000000:event-sourcev2/aws.service/svc/[a-z0-9]{25}"), source.getArn());
        assertEquals("ACTIVE", source.getState());
        assertEquals("AWS_SERVICE_EVENTS", ServiceEventsConfigurations.typeOf(source));
        assertNull(source.getWebhookHostId());
        assertNull(source.getSecretArn());
        verify(certificateManager, never()).ensureHost(any());
    }

    @Test
    void describeEchoesTheConfigurationAndThePatternAsSent() {
        String pattern = "{ \"detail-type\" : [\"Parameter Store Change\"] }";
        EventSourceV2 source = service.createEventSource(create("svc",
                "{\"AwsService\":\"aws.ssm\",\"Pattern\":\"" + escaped(pattern) + "\","
                        + "\"OnFailureConfiguration\":{}}"), REGION);

        EventSourceV2 described = service.describeEventSource(arn(source.getArn()), REGION);

        assertEquals(json("{\"AwsServiceEventsConfiguration\":{\"AwsService\":\"aws.ssm\",\"Pattern\":\""
                + escaped(pattern) + "\",\"OnFailureConfiguration\":{}}}"), described.getConfiguration());
    }

    @Test
    void describeKeepsAnEmptyPattern() {
        EventSourceV2 source = service.createEventSource(create("svc",
                "{\"AwsService\":\"aws.ssm\",\"Pattern\":\"\"}"), REGION);

        assertEquals("", service.describeEventSource(arn(source.getArn()), REGION).getConfiguration()
                .path("AwsServiceEventsConfiguration").path("Pattern").textValue());
        assertEquals("{\"source\":[\"aws.ssm\"],\"account\":[\"000000000000\"],\"region\":[\"us-east-1\"]}",
                lastPutRule().pattern());
    }

    @Test
    void createPutsTheManagedRuleOnTheDefaultBusWithOneTarget() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);

        PutRule rule = lastPutRule();
        String id = source.getArn().substring(source.getArn().length() - 25);
        assertEquals(id, rule.name());
        assertEquals("events.amazonaws.com", rule.managedBy());
        assertEquals("{\"source\":[\"aws.ssm\"],\"account\":[\"000000000000\"],\"region\":[\"us-east-1\"]}",
                rule.pattern());
        assertEquals(id, rule.target().getId());
        assertEquals(bus, rule.target().getArn());
        assertNull(rule.target().getRoleArn());
        assertNull(rule.target().getDeadLetterConfig());
        assertEquals(REGION, rule.region());
    }

    @Test
    void thePatternMembersFollowTheForwardingKeysInTheirOwnOrder() {
        service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\",\"Pattern\":\""
                + escaped("{\"detail\":{\"name\":[\"/a\"]},\"detail-type\":[\"Parameter Store Change\"]}") + "\"}"),
                REGION);

        assertEquals("{\"source\":[\"aws.ssm\"],\"account\":[\"000000000000\"],\"region\":[\"us-east-1\"],"
                + "\"detail\":{\"name\":[\"/a\"]},\"detail-type\":[\"Parameter Store Change\"]}",
                lastPutRule().pattern());
    }

    @Test
    void theOnFailureQueueBecomesTheDeadLetterConfigOfTheTarget() {
        service.createEventSource(create("svc",
                "{\"AwsService\":\"aws.ssm\",\"OnFailureConfiguration\":{\"Arn\":\"" + QUEUE + "\"}}"), REGION);

        assertEquals(QUEUE, lastPutRule().target().getDeadLetterConfig().arn());
    }

    @Test
    void anOnFailureConfigurationWithNoArnLeavesNoDeadLetterConfig() {
        service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\",\"OnFailureConfiguration\":{}}"), REGION);

        assertNull(lastPutRule().target().getDeadLetterConfig());
    }

    @Test
    void twoSourcesOfOneServiceShareABus() {
        service.createEventSource(create("one", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        service.createEventSource(create("two", "{\"AwsService\":\"aws.ssm\"}"), REGION);

        verify(eventBridge, times(2)).putManagedRule(any(), any(), any(), any(), eq(REGION));
    }

    @Test
    void listTellsTheTypeOfEachSource() {
        service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        service.createEventSource(json("{\"ClientToken\":\"w\",\"Name\":\"hook\",\"EventBusArn\":\"" + bus + "\","
                + "\"Configuration\":{\"WebhookEventsConfiguration\":{\"AuthConfiguration\":"
                + "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Token\"}},\"RoleArn\":\"" + ROLE
                + "\"}}}"), REGION);

        PaginatedResult<EventSourceV2> page = service.listEventSources(json("{}"), REGION);

        assertEquals(Set.of("AWS_SERVICE_EVENTS", "WEBHOOK_EVENTS"), page.items().stream()
                .map(ServiceEventsConfigurations::typeOf).collect(Collectors.toSet()));
    }

    // ──────────────────────────── Checks ────────────────────────────

    @Test
    void aPatternThatIsNotJsonIsRefused() {
        assertEquals("Pattern is not valid JSON", refusal("{\"AwsService\":\"aws.ssm\",\"Pattern\":\"{oops\"}"));
    }

    @Test
    void aPatternThatIsNotAnObjectIsRefused() {
        assertEquals("Pattern must be a JSON object", refusal("{\"AwsService\":\"aws.ssm\",\"Pattern\":\"[]\"}"));
    }

    @Test
    void aPatternMayNotSetTheKeysTheForwardingRuleSets() {
        for (String key : List.of("source", "account", "region")) {
            assertEquals("Pattern must not set the key '" + key + "'; the forwarding rule sets it from the requested "
                    + "AwsService and the source account and region", refusal("{\"AwsService\":\"aws.ssm\","
                    + "\"Pattern\":\"{\\\"" + key + "\\\":[\\\"x\\\"]}\"}"));
        }
    }

    @Test
    void theFirstReservedKeyInThePatternsOwnOrderIsNamed() {
        assertEquals("Pattern must not set the key 'region'; the forwarding rule sets it from the requested "
                + "AwsService and the source account and region", refusal("{\"AwsService\":\"aws.ssm\","
                + "\"Pattern\":\"{\\\"region\\\":[\\\"x\\\"],\\\"source\\\":[\\\"y\\\"]}\"}"));
    }

    @Test
    void onlyTheTopLevelKeysAreReserved() {
        service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\",\"Pattern\":\""
                + escaped("{\"detail\":{\"source\":[\"x\"]}}") + "\"}"), REGION);

        verify(eventBridge).putManagedRule(any(), any(), any(), any(), eq(REGION));
    }

    @Test
    void theDeadLetterQueueMustBeInTheCallersRegion() {
        assertEquals("OnFailureConfiguration.Arn region 'us-west-2' must match the caller's region 'us-east-1'",
                refusal("{\"AwsService\":\"aws.ssm\",\"OnFailureConfiguration\":{\"Arn\":"
                        + "\"arn:aws:sqs:us-west-2:000000000000:dead\"}}"));
    }

    @Test
    void theDeadLetterQueueMustBeInTheCallersAccount() {
        assertEquals("OnFailureConfiguration.Arn account '111111111111' must match the caller's account "
                + "'000000000000'", refusal("{\"AwsService\":\"aws.ssm\",\"OnFailureConfiguration\":{\"Arn\":"
                + "\"arn:aws:sqs:us-east-1:111111111111:dead\"}}"));
    }

    @Test
    void theDeadLetterQueueMayNotBeFifo() {
        assertEquals("OnFailureConfiguration.Arn must name a standard SQS queue: FIFO queues are not supported as "
                + "EventSource dead-letter queues", refusal("{\"AwsService\":\"aws.ssm\","
                + "\"OnFailureConfiguration\":{\"Arn\":\"arn:aws:sqs:us-east-1:000000000000:dead.fifo\"}}"));
    }

    @Test
    void theRegionOfTheQueueIsCheckedBeforeItsAccountAndItsAccountBeforeFifo() {
        assertEquals("OnFailureConfiguration.Arn region 'us-west-2' must match the caller's region 'us-east-1'",
                refusal("{\"AwsService\":\"aws.ssm\",\"OnFailureConfiguration\":{\"Arn\":"
                        + "\"arn:aws:sqs:us-west-2:111111111111:dead.fifo\"}}"));
        assertEquals("OnFailureConfiguration.Arn account '111111111111' must match the caller's account "
                + "'000000000000'", refusal("{\"AwsService\":\"aws.ssm\",\"OnFailureConfiguration\":{\"Arn\":"
                + "\"arn:aws:sqs:us-east-1:111111111111:dead.fifo\"}}"));
    }

    @Test
    void thePatternIsCheckedBeforeTheDeadLetterQueue() {
        assertEquals("Pattern is not valid JSON", refusal("{\"AwsService\":\"aws.ssm\",\"Pattern\":\"{oops\","
                + "\"OnFailureConfiguration\":{\"Arn\":\"arn:aws:sqs:us-west-2:000000000000:dead\"}}"));
    }

    @Test
    void theChecksComeBeforeTheBusLookup() {
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(request("svc",
                missingBus(), "{\"AwsService\":\"aws.ssm\",\"Pattern\":\"[]\"}"), REGION));
        assertEquals("Pattern must be a JSON object", e.getMessage());
    }

    @Test
    void theBusLookupComesBeforeProvisioning() {
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(request("svc",
                missingBus(), "{\"AwsService\":\"aws.ssm\",\"Pattern\":\"{}\"}"), REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals("Resource does not exist", e.getMessage());
    }

    @Test
    void aRefusalOtherThanProvisioningStoresNothing() {
        refusal("{\"AwsService\":\"aws.ssm\",\"Pattern\":\"[]\"}");

        assertTrue(service.listEventSources(json("{}"), REGION).items().isEmpty());
        verify(eventBridge, never()).putManagedRule(any(), any(), any(), any(), any());
    }

    // ──────────────────────────── CREATE_FAILED ────────────────────────────

    @Test
    void aPatternNoRuleCanUseLeavesACreateFailedSource() {
        for (String pattern : List.of("{}", "{\"detail-type\":\"Object Created\"}")) {
            String name = "f" + Math.abs(pattern.hashCode());
            AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(create(name,
                    "{\"AwsService\":\"aws.s3\",\"Pattern\":\"" + escaped(pattern) + "\"}"), REGION));

            assertEquals("InvalidInputException", e.getErrorCode());
            assertEquals(400, e.getHttpStatus());
            assertEquals(PROVISIONING_FAILED, e.getMessage());
        }

        List<EventSourceV2> listed = service.listEventSources(json("{}"), REGION).items();
        assertEquals(2, listed.size());
        assertTrue(listed.stream().allMatch(source -> "CREATE_FAILED".equals(source.getState())));
        verify(eventBridge, never()).putManagedRule(any(), any(), any(), any(), any());
    }

    @Test
    void aPatternEventBridgeRefusesLeavesACreateFailedSource() {
        doThrow(new AwsException("InvalidEventPatternException", "refused", 400))
                .when(eventBridge).validateEventPattern(any());

        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(create("svc",
                "{\"AwsService\":\"aws.s3\",\"Pattern\":\"" + escaped("{\"detail-type\":[\"x\"]}") + "\"}"), REGION));

        assertEquals(PROVISIONING_FAILED, e.getMessage());
        assertEquals("CREATE_FAILED", service.listEventSources(json("{}"), REGION).items().getFirst().getState());
    }

    @Test
    void aCreateFailedSourceIsDescribedAndKeepsItsNameTaken() {
        failCreate("svc");
        EventSourceV2 stored = service.listEventSources(json("{}"), REGION).items().getFirst();

        assertEquals("CREATE_FAILED", service.describeEventSource(arn(stored.getArn()), REGION).getState());
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(
                create("svc", "{\"AwsService\":\"aws.s3\"}", "other"), REGION));
        assertEquals("ResourceAlreadyExistsException", e.getErrorCode());
    }

    @Test
    void aRetryOfACreateFailedSourceAnswersTheSame400() {
        JsonNode request = create("svc", "{\"AwsService\":\"aws.s3\",\"Pattern\":\"{}\"}");
        assertThrows(AwsException.class, () -> service.createEventSource(request, REGION));

        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(request, REGION));

        assertEquals(PROVISIONING_FAILED, e.getMessage());
        assertEquals(1, service.listEventSources(json("{}"), REGION).items().size());
    }

    @Test
    void aCreateFailedSourceDoesNotBlockDeletingItsBus() {
        failCreate("svc");

        service.deleteEventBus(bus, REGION);
    }

    @Test
    void aCreateFailedSourceIsNotFoundOnceItsBusIsGoneButStaysListedAndDeletable() {
        failCreate("svc");
        EventSourceV2 stored = service.listEventSources(json("{}"), REGION).items().getFirst();
        service.deleteEventBus(bus, REGION);

        AwsException e = assertThrows(AwsException.class,
                () -> service.describeEventSource(arn(stored.getArn()), REGION));

        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
        assertEquals("Resource does not exist", e.getMessage());
        assertEquals(1, service.listEventSources(json("{}"), REGION).items().size());
        service.deleteEventSource(arn(stored.getArn()), REGION);
        assertTrue(service.listEventSources(json("{}"), REGION).items().isEmpty());
    }

    // ──────────────────────────── Update ────────────────────────────

    @Test
    void updateReplacesTheConfigurationAndRewritesTheRule() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\",\"Pattern\":\""
                + escaped("{\"detail-type\":[\"x\"]}") + "\",\"OnFailureConfiguration\":{\"Arn\":\"" + QUEUE
                + "\"}}"), REGION);

        EventSourceV2 updated = service.updateEventSource(update(source, "{\"AwsService\":\"aws.s3\"}"), REGION);

        assertEquals("ACTIVE", updated.getState());
        assertEquals(json("{\"AwsServiceEventsConfiguration\":{\"AwsService\":\"aws.s3\"}}"),
                updated.getConfiguration());
        PutRule rule = lastPutRule();
        assertEquals("{\"source\":[\"aws.s3\"],\"account\":[\"000000000000\"],\"region\":[\"us-east-1\"]}",
                rule.pattern());
        assertNull(rule.target().getDeadLetterConfig());
    }

    @Test
    void aDescriptionOnlyUpdateLeavesTheRuleAlone() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        clock.advance(Duration.ofMinutes(1));

        EventSourceV2 updated = service.updateEventSource(json("{\"EventSourceArn\":\"" + source.getArn()
                + "\",\"Description\":\"d\"}"), REGION);

        assertEquals("d", updated.getDescription());
        assertEquals(NOW.plus(Duration.ofMinutes(1)), updated.getLastModifiedTime());
        verify(eventBridge, times(1)).putManagedRule(any(), any(), any(), any(), any());
    }

    @Test
    void updateRefusesAnotherVariantWithTheTypeOfTheSource() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.updateEventSource(json(
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":{\"PartnerEventsConfiguration\":"
                        + "{\"PartnerEventSourceArn\":\"" + PARTNER + "\"}}}"), REGION));

        assertEquals("EventSource type is immutable: the configuration variant must remain AWS_SERVICE_EVENTS",
                e.getMessage());
    }

    @Test
    void updateRunsTheChecksOfCreate() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.updateEventSource(update(source,
                "{\"AwsService\":\"aws.ssm\",\"Pattern\":\"{\\\"account\\\":[\\\"1\\\"]}\"}"), REGION));

        assertEquals("Pattern must not set the key 'account'; the forwarding rule sets it from the requested "
                + "AwsService and the source account and region", e.getMessage());
    }

    @Test
    void aFailedUpdateStoresTheNewConfigurationAndLeavesTheRule() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        clock.advance(Duration.ofMinutes(1));

        AwsException e = assertThrows(AwsException.class, () -> service.updateEventSource(update(source,
                "{\"AwsService\":\"aws.s3\",\"Pattern\":\"{}\"}", "new"), REGION));

        assertEquals(PROVISIONING_FAILED, e.getMessage());
        EventSourceV2 stored = service.describeEventSource(arn(source.getArn()), REGION);
        assertEquals("UPDATE_FAILED", stored.getState());
        assertEquals("new", stored.getDescription());
        assertEquals("aws.s3", stored.getConfiguration().path("AwsServiceEventsConfiguration").path("AwsService")
                .textValue());
        assertEquals(NOW.plus(Duration.ofMinutes(1)), stored.getLastModifiedTime());
        verify(eventBridge, times(1)).putManagedRule(any(), any(), any(), any(), any());
    }

    @Test
    void aValidUpdateAfterAFailedOneMakesTheSourceActiveAndRewritesTheRule() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        assertThrows(AwsException.class, () -> service.updateEventSource(update(source,
                "{\"AwsService\":\"aws.s3\",\"Pattern\":\"{}\"}"), REGION));

        EventSourceV2 updated = service.updateEventSource(update(source, "{\"AwsService\":\"aws.s3\"}"), REGION);

        assertEquals("ACTIVE", updated.getState());
        assertEquals("{\"source\":[\"aws.s3\"],\"account\":[\"000000000000\"],\"region\":[\"us-east-1\"]}",
                lastPutRule().pattern());
    }

    @Test
    void aValidUpdateOfACreateFailedSourceMakesItActiveAndCreatesTheRule() {
        failCreate("svc");
        EventSourceV2 source = service.listEventSources(json("{}"), REGION).items().getFirst();

        EventSourceV2 updated = service.updateEventSource(update(source, "{\"AwsService\":\"aws.s3\"}"), REGION);

        assertEquals("ACTIVE", updated.getState());
        assertEquals(ruleArn(source).substring(RULE_ARN_PREFIX.length()), lastPutRule().name());
    }

    @Test
    void aSourceWhoseBusIsGoneAnswersEveryUpdateWithNotFound() {
        failCreate("svc");
        EventSourceV2 stored = service.listEventSources(json("{}"), REGION).items().getFirst();
        service.deleteEventBus(bus, REGION);

        for (JsonNode request : List.of(update(stored, "{\"AwsService\":\"aws.s3\"}"),
                json("{\"EventSourceArn\":\"" + stored.getArn() + "\",\"Description\":\"d\"}"))) {
            AwsException e = assertThrows(AwsException.class, () -> service.updateEventSource(request, REGION));
            assertEquals("ResourceNotFoundException", e.getErrorCode());
            assertEquals(404, e.getHttpStatus());
            assertEquals("Resource does not exist", e.getMessage());
        }
        AwsException json = assertThrows(AwsException.class, () -> service.updateEventSource(update(stored,
                "{\"AwsService\":\"aws.s3\",\"Pattern\":\"{oops\"}"), REGION));
        assertEquals("Pattern is not valid JSON", json.getMessage());
        verify(eventBridge, never()).putManagedRule(any(), any(), any(), any(), any());
        assertEquals("CREATE_FAILED", service.listEventSources(json("{}"), REGION).items().getFirst().getState());
    }

    @Test
    void aRevokedSourceCannotBeUpdated() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        service.revokeResource(json("{\"Arn\":\"" + source.getArn() + "\"}"), REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.updateEventSource(update(source,
                "{\"AwsService\":\"aws.s3\"}"), REGION));

        assertEquals("InvalidStateException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals("EventSource is revoked and cannot be updated", e.getMessage());
    }

    // ──────────────────────────── Delete, revoke and the bus ────────────────────────────

    @Test
    void deleteRemovesTheManagedRule() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);

        service.deleteEventSource(arn(source.getArn()), REGION);

        verify(eventBridge).deleteManagedRule(ruleArn(source).substring(RULE_ARN_PREFIX.length()), REGION);
        assertTrue(service.listEventSources(json("{}"), REGION).items().isEmpty());
    }

    @Test
    void revokeKeepsTheRuleAndTheState() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);

        service.revokeResource(json("{\"Arn\":\"" + source.getArn() + "\"}"), REGION);

        EventSourceV2 described = service.describeEventSource(arn(source.getArn()), REGION);
        assertTrue(described.isRevoked());
        assertEquals("ACTIVE", described.getState());
        verify(eventBridge, never()).deleteManagedRule(any(), any());
    }

    @Test
    void anActiveSourceBlocksDeletingItsBusAndARevokedOneDoesNot() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.deleteEventBus(bus, REGION));
        assertEquals("ResourceInUseException", e.getErrorCode());
        assertEquals("The event bus cannot be deleted because it has one or more event sources that are not "
                + "revoked. Delete or revoke them and retry.", e.getMessage());

        service.revokeResource(json("{\"Arn\":\"" + source.getArn() + "\"}"), REGION);
        service.deleteEventBus(bus, REGION);
    }

    @Test
    void rotatingTheCredentialsOfAServiceSourceFailsTheArnPattern() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.rotateWebhookCredentials(json(
                "{\"EventSourceArn\":\"" + source.getArn() + "\",\"ClientToken\":\"t\"}"), REGION));

        assertEquals("InvalidInputException", e.getErrorCode());
        assertTrue(e.getMessage().contains("Value '" + source.getArn() + "' at 'eventSourceArn'"), e.getMessage());
    }

    // ──────────────────────────── Partner sources ────────────────────────────

    @Test
    void aPartnerSourceFailsAsAMissingPartnerSource() {
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(
                partner("{\"PartnerEventSourceArn\":\"" + PARTNER + "\"}"), REGION));

        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("The partner event source aws.partner/example.com/123/orders could not be described: Event "
                + "source aws.partner/example.com/123/orders does not exist.", e.getMessage());
        assertTrue(service.listEventSources(json("{}"), REGION).items().isEmpty());
    }

    @Test
    void thePartnerSourceMustBeInTheCallersRegion() {
        assertEquals("PartnerEventSourceArn region 'us-west-2' must match the caller's region 'us-east-1'",
                partnerRefusal("{\"PartnerEventSourceArn\":\"" + PARTNER.replace("us-east-1", "us-west-2") + "\"}"));
    }

    @Test
    void aPartnerPatternGetsTheJsonChecksButNotTheReservedKeyCheck() {
        assertEquals("Pattern is not valid JSON", partnerRefusal("{\"PartnerEventSourceArn\":\"" + PARTNER
                + "\",\"Pattern\":\"{oops\"}"));
        assertEquals("Pattern must be a JSON object", partnerRefusal("{\"PartnerEventSourceArn\":\"" + PARTNER
                + "\",\"Pattern\":\"[]\"}"));
        assertTrue(partnerRefusal("{\"PartnerEventSourceArn\":\"" + PARTNER
                + "\",\"Pattern\":\"{\\\"source\\\":[\\\"x\\\"]}\"}").contains("does not exist"));
    }

    @Test
    void aPartnerSourceRunsTheDeadLetterChecksBeforeTheRegionAndTheBusLookup() {
        assertEquals("OnFailureConfiguration.Arn must name a standard SQS queue: FIFO queues are not supported as "
                + "EventSource dead-letter queues", partnerRefusal("{\"PartnerEventSourceArn\":\""
                + PARTNER.replace("us-east-1", "us-west-2") + "\",\"OnFailureConfiguration\":{\"Arn\":"
                + "\"arn:aws:sqs:us-east-1:000000000000:dead.fifo\"}}"));
    }

    @Test
    void aPartnerSourceLooksUpTheBusBeforeThePartnerSource() {
        AwsException e = assertThrows(AwsException.class, () -> service.createEventSource(json(
                "{\"ClientToken\":\"t\",\"Name\":\"p\",\"EventBusArn\":\"" + missingBus() + "\",\"Configuration\":"
                        + "{\"PartnerEventsConfiguration\":{\"PartnerEventSourceArn\":\"" + PARTNER + "\"}}}"),
                REGION));

        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    // ──────────────────────────── Forwarding ────────────────────────────

    @Test
    void aServiceEventReachesTheSubscribersOfTheBusOnce() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        subscribe();
        String ruleName = ruleArn(source);
        String event = "{\"version\":\"0\",\"id\":\"11111111-2222-3333-4444-555555555555\","
                + "\"detail-type\":\"Parameter Store Change\",\"source\":\"aws.ssm\",\"account\":\"000000000000\","
                + "\"time\":\"2026-11-27T12:00:00Z\",\"region\":\"us-east-1\",\"resources\":[],\"detail\":{}}";

        service.forwardServiceEvent(bus, ruleName, event);
        service.forwardServiceEvent(bus, ruleName, event);

        ArgumentCaptor<PublishedEvent> delivered = ArgumentCaptor.forClass(PublishedEvent.class);
        verify(delivery, times(1)).deliver(any(), delivered.capture());
        PublishedEvent published = delivered.getValue();
        assertEquals(json(event), published.data());
        assertEquals(json("{}"), published.metadata());
        assertEquals(List.of("EventGroupId", "DeduplicationId", "ContentType", "aws:IngestionTime", "aws:Source",
                "aws:DetailType", "aws:EventId", "aws:SequenceNumber", "aws:DeliveryType"),
                published.systemMetadata().propertyStream().map(Map.Entry::getKey).toList());
        assertEquals("11111111-2222-3333-4444-555555555555", published.systemMetadata().path("EventGroupId")
                .textValue());
        assertEquals("11111111-2222-3333-4444-555555555555", published.systemMetadata().path("DeduplicationId")
                .textValue());
        assertEquals("application/eventbridge+json", published.systemMetadata().path("ContentType").textValue());
        assertEquals("aws.ssm", published.systemMetadata().path("aws:Source").textValue());
        assertEquals("Parameter Store Change", published.systemMetadata().path("aws:DetailType").textValue());
        assertEquals("LIVE", published.systemMetadata().path("aws:DeliveryType").textValue());
        assertEquals("1", published.systemMetadata().path("aws:SequenceNumber").textValue());
    }

    @Test
    void anUpdateFailedSourceStillTakesEventsThroughItsEarlierRule() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        subscribe();
        assertThrows(AwsException.class, () -> service.updateEventSource(update(source,
                "{\"AwsService\":\"aws.ssm\",\"Pattern\":\"{}\"}"), REGION));
        assertEquals("UPDATE_FAILED", service.describeEventSource(arn(source.getArn()), REGION).getState());

        service.forwardServiceEvent(bus, ruleArn(source),
                "{\"id\":\"e1\",\"source\":\"aws.ssm\",\"detail-type\":\"x\",\"detail\":{}}");

        verify(delivery, times(1)).deliver(any(), any());
    }

    @Test
    void aUserRuleNamedLikeASourceDeliversNothing() {
        failCreate("svc");
        EventSourceV2 failed = service.listEventSources(json("{}"), REGION).items().getFirst();
        subscribe();
        String event = "{\"id\":\"e1\",\"source\":\"x\",\"detail-type\":\"x\",\"detail\":{}}";
        when(eventBridge.isManagedRule(any(), any())).thenReturn(false);

        service.forwardServiceEvent(bus, ruleArn(failed), event);

        verify(delivery, never()).deliver(any(), any());
    }

    @Test
    void aRuleOnACustomBusOrInAnotherAccountDeliversNothing() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        subscribe();
        String id = ruleArn(source).substring(RULE_ARN_PREFIX.length());
        String event = "{\"id\":\"e1\",\"source\":\"aws.ssm\",\"detail-type\":\"x\",\"detail\":{}}";

        service.forwardServiceEvent(bus, RULE_ARN_PREFIX + "custom/" + id, event);
        service.forwardServiceEvent(bus, "arn:aws:events:us-east-1:111111111111:rule/" + id, event);
        service.forwardServiceEvent(bus, "arn:aws:events:us-west-2:000000000000:rule/" + id, event);

        verify(delivery, never()).deliver(any(), any());
    }

    @Test
    void aRevokedSourceTakesNoEvents() {
        EventSourceV2 source = service.createEventSource(create("svc", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        subscribe();
        service.revokeResource(json("{\"Arn\":\"" + source.getArn() + "\"}"), REGION);

        service.forwardServiceEvent(bus, ruleArn(source),
                "{\"id\":\"e1\",\"source\":\"aws.ssm\",\"detail-type\":\"x\",\"detail\":{}}");

        verify(delivery, never()).deliver(any(), any());
    }

    @Test
    void aRuleWithNoSourceTakesNoEvents() {
        subscribe();

        service.forwardServiceEvent(bus, RULE_ARN_PREFIX + "abcdefghijklmnopqrstuvwxy",
                "{\"id\":\"e1\",\"source\":\"aws.ssm\",\"detail-type\":\"x\",\"detail\":{}}");

        verify(delivery, never()).deliver(any(), any());
    }

    @Test
    void oneEventPerEventIdIsDeliveredWhenTwoSourcesShareABus() {
        EventSourceV2 one = service.createEventSource(create("one", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        EventSourceV2 two = service.createEventSource(create("two", "{\"AwsService\":\"aws.ssm\"}"), REGION);
        subscribe();
        String event = "{\"id\":\"e1\",\"source\":\"aws.ssm\",\"detail-type\":\"x\",\"detail\":{}}";

        service.forwardServiceEvent(bus, ruleArn(one), event);
        service.forwardServiceEvent(bus, ruleArn(two), event);

        verify(delivery, times(1)).deliver(any(), any());
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private record PutRule(String name, String managedBy, String pattern, Target target, String region) {
    }

    private PutRule lastPutRule() {
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> managedBy = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Target> target = ArgumentCaptor.forClass(Target.class);
        ArgumentCaptor<String> region = ArgumentCaptor.forClass(String.class);
        verify(eventBridge, atLeastOnce()).putManagedRule(name.capture(), managedBy.capture(),
                pattern.capture(), target.capture(), region.capture());
        int last = name.getAllValues().size() - 1;
        return new PutRule(name.getAllValues().get(last), managedBy.getAllValues().get(last),
                pattern.getAllValues().get(last), target.getAllValues().get(last), region.getAllValues().get(last));
    }

    private static String ruleArn(EventSourceV2 source) {
        return RULE_ARN_PREFIX + source.getArn().substring(source.getArn().length() - 25);
    }

    private void subscribe() {
        service.createSubscriber(json("{\"Name\":\"s\",\"EventBusArn\":\"" + bus + "\",\"InvokeConfiguration\":"
                + "{\"TargetArn\":\"arn:aws:sqs:us-east-1:000000000000:q\",\"RoleArn\":\"" + ROLE + "\"}}"), REGION);
    }

    private void failCreate(String name) {
        assertThrows(AwsException.class, () -> service.createEventSource(create(name,
                "{\"AwsService\":\"aws.s3\",\"Pattern\":\"{}\"}"), REGION));
    }

    private String refusal(String awsServiceConfiguration) {
        return assertThrows(AwsException.class, () -> service.createEventSource(
                create("svc", awsServiceConfiguration), REGION)).getMessage();
    }

    private String partnerRefusal(String partnerConfiguration) {
        return assertThrows(AwsException.class, () -> service.createEventSource(partner(partnerConfiguration),
                REGION)).getMessage();
    }

    private String missingBus() {
        return bus.substring(0, bus.lastIndexOf('/') + 1).replace("orders", "gone") + "abcdefghijklmnopqrstuvwxy";
    }

    private JsonNode create(String name, String awsServiceConfiguration) {
        return create(name, awsServiceConfiguration, null);
    }

    private JsonNode create(String name, String awsServiceConfiguration, String description) {
        return request(name, bus, awsServiceConfiguration, description);
    }

    private JsonNode request(String name, String busArn, String awsServiceConfiguration) {
        return request(name, busArn, awsServiceConfiguration, null);
    }

    private JsonNode request(String name, String busArn, String awsServiceConfiguration, String description) {
        String token = "t" + Integer.toHexString((name + busArn + awsServiceConfiguration).hashCode());
        return json("{\"ClientToken\":\"" + token + "\",\"Name\":\"" + name + "\",\"EventBusArn\":\"" + busArn
                + "\",\"Configuration\":{\"AwsServiceEventsConfiguration\":" + awsServiceConfiguration + "}"
                + (description == null ? "" : ",\"Description\":\"" + description + "\"") + "}");
    }

    private JsonNode partner(String partnerConfiguration) {
        return json("{\"ClientToken\":\"p\",\"Name\":\"p\",\"EventBusArn\":\"" + bus + "\",\"Configuration\":"
                + "{\"PartnerEventsConfiguration\":" + partnerConfiguration + "}}");
    }

    private static JsonNode update(EventSourceV2 source, String awsServiceConfiguration) {
        return update(source, awsServiceConfiguration, null);
    }

    private static JsonNode update(EventSourceV2 source, String awsServiceConfiguration, String description) {
        return json("{\"EventSourceArn\":\"" + source.getArn() + "\",\"Configuration\":"
                + "{\"AwsServiceEventsConfiguration\":" + awsServiceConfiguration + "}"
                + (description == null ? "" : ",\"Description\":\"" + description + "\"") + "}");
    }

    private static JsonNode arn(String arn) {
        return json("{\"EventSourceArn\":\"" + arn + "\"}");
    }

    private static String escaped(String json) {
        return json.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static JsonNode json(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }
}
