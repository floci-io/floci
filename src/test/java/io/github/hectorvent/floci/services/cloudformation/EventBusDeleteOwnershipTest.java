package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting an {@code AWS::Events::EventBus} is guarded by the created-time recorded at provision
 * time, so a bus that was destroyed and recreated under the same name out of band is not deleted
 * out from under whoever owns it now.
 */
class EventBusDeleteOwnershipTest {

    private static final String REGION = "us-east-1";
    private static final String BUS = "orders-bus";
    private static final String CREATED_TIME_ATTR = "FlociEventBusCreatedTime";

    private EventBridgeService eventBridgeService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        eventBridgeService = mock(EventBridgeService.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, null,
                null, null, null, null, eventBridgeService,
                null, null, null, null, null, null,
                new ObjectMapper(),
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null,
                new CloudFormationResourceRegistry(List.of()), null);
    }

    @Test
    void busWhoseCreatedTimeStillMatchesIsDeleted() {
        Instant createdTime = Instant.parse("2026-01-01T00:00:00Z");
        when(eventBridgeService.describeEventBus(BUS, REGION)).thenReturn(bus(createdTime));

        assertDoesNotThrow(() -> provisioner.delete(resource(createdTime.toString()), REGION));

        verify(eventBridgeService).deleteEventBus(BUS, REGION);
    }

    @Test
    void busRecreatedUnderTheSameNameIsNotDeleted() {
        when(eventBridgeService.describeEventBus(BUS, REGION))
                .thenReturn(bus(Instant.parse("2026-02-02T00:00:00Z")));

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.delete(resource("2026-01-01T00:00:00Z"), REGION));

        assertTrue(e.getMessage().contains("ownership changed"), e.getMessage());
        verify(eventBridgeService, never()).deleteEventBus(BUS, REGION);
    }

    /**
     * Stacks provisioned before the created-time attribute existed are restored from
     * cloudformation-stacks.json without it. Treating "not tracked" as "ownership changed" would
     * leave every such stack permanently in DELETE_FAILED after a Floci upgrade.
     */
    @Test
    void busProvisionedBeforeOwnershipTrackingIsStillDeleted() {
        when(eventBridgeService.describeEventBus(BUS, REGION))
                .thenReturn(bus(Instant.parse("2026-01-01T00:00:00Z")));

        assertDoesNotThrow(() -> provisioner.delete(resource(null), REGION));

        verify(eventBridgeService).deleteEventBus(BUS, REGION);
    }

    @Test
    void alreadyDeletedBusIsTreatedAsDeleted() {
        when(eventBridgeService.describeEventBus(BUS, REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 400));

        assertDoesNotThrow(() -> provisioner.delete(resource(null), REGION));

        verify(eventBridgeService, never()).deleteEventBus(BUS, REGION);
    }

    @Test
    void unexpectedRuleDeletionFailureIsNotReportedAsSuccess() {
        StackResource rule = new StackResource();
        rule.setLogicalId("OrdersRule");
        rule.setResourceType("AWS::Events::Rule");
        rule.setPhysicalId("orders-rule");
        rule.getAttributes().put("EventBusName", BUS);
        when(eventBridgeService.listTargetsByRule("orders-rule", BUS, REGION))
                .thenThrow(new IllegalStateException("storage unavailable"));

        AwsException error = assertThrows(AwsException.class,
                () -> provisioner.delete(rule, REGION));

        assertEquals("InternalFailure", error.getErrorCode());
        assertTrue(error.getMessage().contains("storage unavailable"), error.getMessage());
        verify(eventBridgeService, never()).deleteRule("orders-rule", BUS, REGION);
    }

    private StackResource resource(String createdTimeAttr) {
        StackResource r = new StackResource();
        r.setLogicalId("OrdersBus");
        r.setResourceType("AWS::Events::EventBus");
        r.setPhysicalId(BUS);
        if (createdTimeAttr != null) {
            r.getAttributes().put(CREATED_TIME_ATTR, createdTimeAttr);
        }
        return r;
    }

    private EventBus bus(Instant createdTime) {
        return new EventBus(BUS, "arn:aws:events:" + REGION + ":000000000000:event-bus/" + BUS,
                null, createdTime);
    }
}
