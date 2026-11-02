package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eventbridgev2.EventBridgeV2Service;
import io.github.hectorvent.floci.services.eventbridgev2.model.EventBusV2;
import io.github.hectorvent.floci.services.eventbridgev2.model.SubscriberV2;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The EventBridge V2 provisioner in isolation, over a mocked service. */
class EventsV2CfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String BUS = "arn:aws:events:us-east-1:000000000000:event-busv2/orders/abcdefghijklmnopqrstuvwxy";
    private static final String SUBSCRIBER =
            "arn:aws:events:us-east-1:000000000000:subscriber/large/abcdefghijklmnopqrstuvwxy";
    private static final String REPLACEMENT =
            "arn:aws:events:us-east-1:000000000000:subscriber/large/zyxwvutsrqponmlkjihgfedcb";
    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final String TARGET = "arn:aws:sqs:us-east-1:000000000000:q";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventBridgeV2Service service = mock(EventBridgeV2Service.class);
    private final EventsV2CfnProvisioner provisioner = new EventsV2CfnProvisioner(service);

    @Test
    void aBusIsCreatedFromItsPropertiesAndSetsRefAndEveryAttribute() throws Exception {
        when(service.createEventBus(any(), eq(REGION))).thenReturn(bus("orders"));
        StackResource r = resource(EventsV2CfnProvisioner.EVENT_BUS);

        provisioner.provision(r, json("{\"Name\":\"orders\",\"StorageConfiguration\":{\"RetentionPeriodInDays\":\"30\"},"
                + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"orders\"}]}"), ctx(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createEventBus(request.capture(), eq(REGION));
        assertEquals(30, request.getValue().path("StorageConfiguration").path("RetentionPeriodInDays").intValue(),
                "an integer given as a string is the integer the API takes");
        assertEquals("orders", request.getValue().path("Tags").path("team").asText(), "tags are the API's map");
        assertEquals(BUS, r.getPhysicalId());
        assertEquals(BUS, r.getAttributes().get("EventBusArn"));
        assertEquals("ACTIVE", r.getAttributes().get("State"));
        assertEquals(NOW.toString(), r.getAttributes().get("CreationTime"));
        assertEquals(NOW.toString(), r.getAttributes().get("LastModifiedTime"));
    }

    @Test
    void anUpdateKeepingTheBusNameChangesItInPlace() throws Exception {
        when(service.findEventBus(BUS, REGION)).thenReturn(Optional.of(bus("orders")));
        when(service.replaceEventBusSettings(eq(BUS), any(), eq(REGION))).thenReturn(bus("orders"));

        provisioner.provision(resource(EventsV2CfnProvisioner.EVENT_BUS),
                json("{\"Name\":\"orders\",\"Description\":\"new\"}"), ctx(BUS));

        verify(service).replaceEventBusSettings(eq(BUS), any(), eq(REGION));
        verify(service, never()).createEventBus(any(), anyString());
    }

    @Test
    void rollingBackAnInPlaceBusUpdatePutsBackTheSettingsItHadBefore() throws Exception {
        EventBusV2 before = bus("orders");
        before.setDescription("old");
        when(service.findEventBus(BUS, REGION)).thenReturn(Optional.of(before));
        when(service.replaceEventBusSettings(eq(BUS), any(), eq(REGION))).thenAnswer(inv -> {
            before.setDescription("new");
            return before;
        });
        when(service.restoreEventBus(any(), eq(REGION))).thenAnswer(inv -> inv.getArgument(0));
        StackResource r = resource(EventsV2CfnProvisioner.EVENT_BUS);
        provisioner.provision(r, json("{\"Name\":\"orders\",\"Description\":\"new\"}"), ctx(BUS));

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<EventBusV2> restored = ArgumentCaptor.forClass(EventBusV2.class);
        verify(service).restoreEventBus(restored.capture(), eq(REGION));
        assertEquals("old", restored.getValue().getDescription(), "the snapshot is taken before the change");
        assertEquals(BUS, r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey(EventsV2CfnProvisioner.UPDATED_PRIOR_ATTR));
    }

    @Test
    void aBusWithoutANameIsAValidationError() {
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource(EventsV2CfnProvisioner.EVENT_BUS), json("{}"), ctx(null)));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void aSubscriberIsCreatedAndSetsRefAndEveryAttribute() throws Exception {
        when(service.createSubscriber(any(), eq(REGION))).thenReturn(subscriber("large", "LATEST"));
        StackResource r = resource(EventsV2CfnProvisioner.SUBSCRIBER);

        provisioner.provision(r, json(subscriberProperties("")), ctx(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createSubscriber(request.capture(), eq(REGION));
        assertEquals("DATA", request.getValue().path("FilterConfiguration").path("Filters").path(0).path("Scope").asText());
        assertEquals(SUBSCRIBER, r.getPhysicalId());
        assertEquals(SUBSCRIBER, r.getAttributes().get("SubscriberArn"));
        assertEquals("orders", r.getAttributes().get("BusName"));
        assertEquals(NOW.toString(), r.getAttributes().get("CreationTime"));
        assertEquals(NOW.toString(), r.getAttributes().get("LastModifiedTime"));
    }

    @Test
    void anUpdateKeepingTheSubscribersIdentityChangesItInPlace() throws Exception {
        when(service.findSubscriber(SUBSCRIBER, REGION)).thenReturn(Optional.of(subscriber("large", "LATEST")));
        when(service.replaceSubscriberSettings(eq(SUBSCRIBER), any(), eq(REGION)))
                .thenReturn(subscriber("large", "LATEST"));

        provisioner.provision(resource(EventsV2CfnProvisioner.SUBSCRIBER),
                json(subscriberProperties(",\"ResumePosition\":\"LATEST\"")), ctx(SUBSCRIBER));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).replaceSubscriberSettings(eq(SUBSCRIBER), request.capture(), eq(REGION));
        assertFalse(request.getValue().has("ResumePosition"), "ResumePosition is not a subscriber setting");
        verify(service, never()).createSubscriber(any(), anyString());
    }

    @Test
    void anUpdateChangingTheSubscribersNameCreatesItAndLeavesTheOldOneToTheCleanup() throws Exception {
        when(service.findSubscriber(SUBSCRIBER, REGION)).thenReturn(Optional.of(subscriber("large", "LATEST")));
        when(service.createSubscriber(any(), eq(REGION))).thenReturn(subscriber("renamed", "LATEST"));

        provisioner.provision(resource(EventsV2CfnProvisioner.SUBSCRIBER),
                json(subscriberProperties("").replace("\"large\"", "\"renamed\"")), ctx(SUBSCRIBER));

        verify(service).createSubscriber(any(), eq(REGION));
        verify(service, never()).deleteSubscriber(anyString(), anyString());
    }

    /** The target is create-only in the type's schema, so a new one replaces the subscriber. */
    @Test
    void anUpdateChangingTheSubscribersTargetReplacesIt() throws Exception {
        when(service.findSubscriber(SUBSCRIBER, REGION)).thenReturn(Optional.of(subscriber("large", "LATEST")));
        when(service.createSubscriber(any(), eq(REGION))).thenReturn(subscriber("large", "LATEST"));

        provisioner.provision(resource(EventsV2CfnProvisioner.SUBSCRIBER),
                json(subscriberProperties("").replace(TARGET, TARGET + "-other")), ctx(SUBSCRIBER));

        verify(service, never()).replaceSubscriberSettings(anyString(), any(), anyString());
        verify(service).deleteSubscriber(SUBSCRIBER, REGION);
        verify(service).createSubscriber(any(), eq(REGION));
    }

    @Test
    void anUpdateReplacingTheSubscriberUnderItsOwnNameDeletesItBeforeCreatingTheReplacement() throws Exception {
        when(service.findSubscriber(SUBSCRIBER, REGION)).thenReturn(Optional.of(subscriber("large", "LATEST")));
        when(service.createSubscriber(any(), eq(REGION))).thenReturn(subscriber("large", "LATEST"));

        provisioner.provision(resource(EventsV2CfnProvisioner.SUBSCRIBER),
                json(subscriberProperties(",\"Type\":\"FIFO\"")), ctx(SUBSCRIBER));

        InOrder order = inOrder(service);
        order.verify(service).deleteSubscriber(SUBSCRIBER, REGION);
        order.verify(service).createSubscriber(any(), eq(REGION));
    }

    @Test
    void rollingBackASameNameReplacementDeletesTheReplacementAndRecreatesThePriorSubscriber() throws Exception {
        StackResource r = replacedUnderItsOwnName();
        when(service.restoreSubscriber(any(), eq(REGION))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(provisioner.rollbackUpdate(r));

        InOrder order = inOrder(service);
        order.verify(service).deleteSubscriber(REPLACEMENT, REGION);
        ArgumentCaptor<SubscriberV2> restored = ArgumentCaptor.forClass(SubscriberV2.class);
        order.verify(service).restoreSubscriber(restored.capture(), eq(REGION));
        assertEquals(SUBSCRIBER, restored.getValue().getArn());
        assertEquals("UNORDERED", restored.getValue().getType());
        assertEquals(NOW, restored.getValue().getCreationTime());
        assertEquals(SUBSCRIBER, r.getPhysicalId());
        assertEquals(SUBSCRIBER, r.getAttributes().get("SubscriberArn"));
        assertFalse(r.getAttributes().containsKey(EventsV2CfnProvisioner.DELETED_PRIOR_ATTR));
    }

    @Test
    void aRollbackThatCannotRecreateThePriorSubscriberFailsAndKeepsItsSnapshot() throws Exception {
        StackResource r = replacedUnderItsOwnName();
        when(service.restoreSubscriber(any(), eq(REGION)))
                .thenThrow(new AwsException("ResourceNotFoundException", "bus gone", 404));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertTrue(r.getAttributes().containsKey(EventsV2CfnProvisioner.DELETED_PRIOR_ATTR),
                "the snapshot stays for a retried rollback");
    }

    @Test
    void aFailedRestoreKeepsTheFailedStateAndIsRetriedBeforeTheNextUpdate() throws Exception {
        StackResource r = replacedUnderItsOwnName();
        when(service.restoreSubscriber(any(), eq(REGION)))
                .thenThrow(new AwsException("InternalException", "restore unavailable", 500));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        r.setStatus("UPDATE_FAILED");
        assertTrue(provisioner.retainsFailedUpdateState(r), "the engine must not swap in the pre-update copy");
        assertTrue(provisioner.requiresUpdateRecovery(r));

        SubscriberV2 prior = subscriber("large", "LATEST");
        reset(service);
        when(service.restoreSubscriber(any(), eq(REGION))).thenAnswer(inv -> inv.getArgument(0));
        when(service.findSubscriber(SUBSCRIBER, REGION)).thenReturn(Optional.of(prior));
        when(service.replaceSubscriberSettings(eq(SUBSCRIBER), any(), eq(REGION))).thenReturn(prior);
        provisioner.provision(r, json(subscriberProperties("")), ctx(SUBSCRIBER));

        InOrder order = inOrder(service);
        order.verify(service).restoreSubscriber(any(), eq(REGION));
        order.verify(service).replaceSubscriberSettings(eq(SUBSCRIBER), any(), eq(REGION));
        assertFalse(r.getAttributes().containsKey(EventsV2CfnProvisioner.DELETED_PRIOR_ATTR));
    }

    @Test
    void rollingBackAnInPlaceSubscriberUpdatePutsBackTheSettingsItHadBefore() throws Exception {
        StackResource r = updatedInPlace();
        when(service.restoreSubscriber(any(), eq(REGION))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<SubscriberV2> restored = ArgumentCaptor.forClass(SubscriberV2.class);
        verify(service).restoreSubscriber(restored.capture(), eq(REGION));
        assertEquals("old", restored.getValue().getDescription());
        assertEquals(SUBSCRIBER, r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey(EventsV2CfnProvisioner.UPDATED_PRIOR_ATTR));
        verify(service, never()).deleteSubscriber(anyString(), anyString());
    }

    @Test
    void anInPlaceRestoreThatFailsKeepsItsSnapshotForTheNextAttempt() throws Exception {
        StackResource r = updatedInPlace();
        when(service.restoreSubscriber(any(), eq(REGION)))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 404));

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertTrue(r.getAttributes().containsKey(EventsV2CfnProvisioner.UPDATED_PRIOR_ATTR));
    }

    @Test
    void committingAnInPlaceUpdateDropsTheSnapshot() throws Exception {
        StackResource r = updatedInPlace();
        provisioner.completeUpdate(r);
        assertFalse(r.getAttributes().containsKey(EventsV2CfnProvisioner.UPDATED_PRIOR_ATTR));
    }

    @Test
    void aRollbackOfAnUpdateThatChangedNothingSucceeds() {
        assertTrue(provisioner.rollbackUpdate(resource(EventsV2CfnProvisioner.SUBSCRIBER)));
    }

    /** A subscriber whose description an update changed in place from {@code old}. */
    private StackResource updatedInPlace() throws Exception {
        SubscriberV2 before = subscriber("large", "LATEST");
        before.setDescription("old");
        when(service.findSubscriber(SUBSCRIBER, REGION)).thenReturn(Optional.of(before));
        when(service.replaceSubscriberSettings(eq(SUBSCRIBER), any(), eq(REGION))).thenAnswer(inv -> {
            before.setDescription("new");
            return before;
        });
        StackResource r = resource(EventsV2CfnProvisioner.SUBSCRIBER);
        provisioner.provision(r, json(subscriberProperties(",\"Description\":\"new\"")), ctx(SUBSCRIBER));
        return r;
    }

    @Test
    void committingASameNameReplacementDropsTheSnapshot() throws Exception {
        StackResource r = replacedUnderItsOwnName();
        provisioner.completeUpdate(r);
        assertFalse(r.getAttributes().containsKey(EventsV2CfnProvisioner.DELETED_PRIOR_ATTR));
    }

    @Test
    void aSameNameReplacementThatFailsToCreateRecreatesThePriorSubscriber() throws Exception {
        when(service.findSubscriber(SUBSCRIBER, REGION)).thenReturn(Optional.of(subscriber("large", "LATEST")));
        when(service.createSubscriber(any(), eq(REGION)))
                .thenThrow(new AwsException("InvalidInputException", "bad target", 400));
        when(service.restoreSubscriber(any(), eq(REGION))).thenAnswer(inv -> inv.getArgument(0));
        StackResource r = resource(EventsV2CfnProvisioner.SUBSCRIBER);
        r.setPhysicalId(SUBSCRIBER);
        JsonNode properties = json(subscriberProperties(",\"Type\":\"FIFO\""));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, properties, ctx(SUBSCRIBER)));

        assertEquals("InvalidInputException", e.getErrorCode());
        ArgumentCaptor<SubscriberV2> restored = ArgumentCaptor.forClass(SubscriberV2.class);
        verify(service).restoreSubscriber(restored.capture(), eq(REGION));
        assertEquals(SUBSCRIBER, restored.getValue().getArn());
        assertEquals(SUBSCRIBER, r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey(EventsV2CfnProvisioner.DELETED_PRIOR_ATTR));
    }

    /** A subscriber resource whose update replaced it under its own name, the prior one deleted first. */
    private StackResource replacedUnderItsOwnName() throws Exception {
        when(service.findSubscriber(SUBSCRIBER, REGION)).thenReturn(Optional.of(subscriber("large", "LATEST")));
        SubscriberV2 replacement = subscriber("large", "LATEST");
        replacement.setArn(REPLACEMENT);
        replacement.setType("FIFO");
        when(service.createSubscriber(any(), eq(REGION))).thenReturn(replacement);
        StackResource r = resource(EventsV2CfnProvisioner.SUBSCRIBER);
        r.setPhysicalId(SUBSCRIBER);
        provisioner.provision(r, json(subscriberProperties(",\"Type\":\"FIFO\"")), ctx(SUBSCRIBER));
        assertEquals(REPLACEMENT, r.getPhysicalId());
        assertTrue(r.getAttributes().containsKey(EventsV2CfnProvisioner.DELETED_PRIOR_ATTR));
        return r;
    }

    @Test
    void aPointInTimeGivenAsAnIsoTimestampIsTheEpochSecondsTheApiTakes() throws Exception {
        when(service.createSubscriber(any(), eq(REGION))).thenReturn(subscriber("large", "POINT_IN_TIME"));

        provisioner.provision(resource(EventsV2CfnProvisioner.SUBSCRIBER), json(subscriberProperties(
                ",\"StartingPosition\":\"POINT_IN_TIME\",\"PointInTimeConfiguration\":{\"PointType\":\"TIMESTAMP\","
                        + "\"StartingPoint\":\"2026-01-01T00:00:00.250Z\"}")), ctx(null));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createSubscriber(request.capture(), eq(REGION));
        JsonNode startingPoint = request.getValue().path("PointInTimeConfiguration").path("StartingPoint");
        assertTrue(startingPoint.isNumber(), startingPoint.toString());
        assertEquals(0, new BigDecimal("1767225600.250").compareTo(startingPoint.decimalValue()));
    }

    @Test
    void aPointInTimeThatIsNotATimestampIsAValidationError() {
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource(EventsV2CfnProvisioner.SUBSCRIBER), json(subscriberProperties(
                        ",\"PointInTimeConfiguration\":{\"StartingPoint\":\"yesterday\"}")), ctx(null)));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void servesNothingWhileTheEventBridgeV2ServiceIsDisabled() {
        assertFalse(provisioner.serviceDisabled());

        when(service.isDisabled()).thenReturn(true);

        assertTrue(provisioner.serviceDisabled());
        assertEquals("floci.services.eventbridgev2.enabled", provisioner.serviceEnabledFlag());
    }

    @Test
    void deleteToleratesAResourceAlreadyGoneAndPropagatesAnyOtherFailure() {
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(service).deleteSubscriber(SUBSCRIBER, REGION);
        provisioner.delete(EventsV2CfnProvisioner.SUBSCRIBER, SUBSCRIBER, REGION);

        doThrow(new AwsException("ResourceInUseException", "has subscribers", 409))
                .when(service).deleteEventBus(BUS, REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.delete(EventsV2CfnProvisioner.EVENT_BUS, BUS, REGION));
        assertEquals("ResourceInUseException", e.getErrorCode());
    }

    private String subscriberProperties(String extra) {
        return "{\"Name\":\"large\",\"EventBusArn\":\"" + BUS + "\",\"InvokeConfiguration\":{\"TargetArn\":"
                + "\"" + TARGET + "\",\"RoleArn\":\"arn:aws:iam::000000000000:role/r\"},"
                + "\"FilterConfiguration\":{\"Filters\":[{\"Scope\":\"DATA\",\"Pattern\":\"{}\"}]}" + extra + "}";
    }

    private static EventBusV2 bus(String name) {
        EventBusV2 bus = new EventBusV2();
        bus.setName(name);
        bus.setArn(BUS);
        bus.setState("ACTIVE");
        bus.setCreationTime(NOW);
        bus.setLastModifiedTime(NOW);
        return bus;
    }

    private static SubscriberV2 subscriber(String name, String startingPosition) {
        SubscriberV2 subscriber = new SubscriberV2();
        subscriber.setName(name);
        subscriber.setArn(SUBSCRIBER);
        subscriber.setEventBusArn(BUS);
        subscriber.setType("UNORDERED");
        subscriber.setStartingPosition(startingPosition);
        subscriber.setInvokeConfiguration(MAPPER.createObjectNode().put("TargetArn", TARGET));
        subscriber.setCreationTime(NOW);
        subscriber.setLastModifiedTime(NOW);
        return subscriber;
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, REGION, "000000000000", "stack", priorPhysicalId);
    }

    private static StackResource resource(String type) {
        StackResource r = new StackResource();
        r.setLogicalId("Resource");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private JsonNode json(String value) throws Exception {
        return MAPPER.readTree(value);
    }
}
