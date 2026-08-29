package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provision-side ownership behaviour for {@code AWS::Events::EventBus}. The delete side is covered
 * by {@link EventBusDeleteOwnershipTest}; both have to agree about what a missing created-time
 * attribute means, or an upgrade wedges the stack in one direction or the other.
 *
 * <p>Note {@code provision} reports failure by returning a {@code CREATE_FAILED} resource rather
 * than throwing, so these assert on status.
 */
class EventBusProvisionOwnershipTest {

    private static final String REGION = "us-east-1";
    private static final String BUS = "orders-bus";
    private static final String CREATED_TIME_ATTR = "FlociEventBusCreatedTime";
    private static final String MANAGED_TAG_KEYS_ATTR = "FlociEventBusManagedTagKeys";
    private static final String MANAGED_POLICY_ATTR = "FlociEventBusManagedPolicy";
    private static final String ROLLBACK_OWNED_ATTR = "__FlociRollbackOwned";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventBridgeService eventBridgeService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        eventBridgeService = mock(EventBridgeService.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, null,
                null, null, null, null, eventBridgeService,
                null, null, null, null, null, null,
                MAPPER,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null,
                new CloudFormationResourceRegistry(List.of()), null);
    }

    /**
     * A stack provisioned before {@code FlociEventBusCreatedTime} existed is restored without it.
     * Treating that as "ownership changed" made the first UpdateStack after an upgrade fail, leaving
     * the stack stuck for every subsequent update — including no-op ones.
     */
    @Test
    void updateOfBusProvisionedBeforeOwnershipTrackingAdoptsItInsteadOfFailing() {
        when(eventBridgeService.createEventBus(eq(BUS), any(), any(), eq(REGION)))
                .thenThrow(new AwsException("ResourceAlreadyExistsException", "exists", 400));
        when(eventBridgeService.describeEventBus(BUS, REGION))
                .thenReturn(bus(Instant.parse("2026-01-01T00:00:00Z")));

        StackResource r = provisionExisting(Map.of());

        assertEquals("CREATE_COMPLETE", r.getStatus(), r.getStatusReason());
        assertEquals(BUS, r.getPhysicalId());
        // and it starts tracking ownership from here on
        assertEquals("2026-01-01T00:00:00Z", r.getAttributes().get(CREATED_TIME_ATTR));
    }

    @Test
    void updateOfTaggedBusProvisionedBeforeTagTrackingAdoptsItInsteadOfFailing() {
        when(eventBridgeService.createEventBus(eq(BUS), any(), any(), eq(REGION)))
                .thenThrow(new AwsException("ResourceAlreadyExistsException", "exists", 400));
        EventBus existingBus = bus(Instant.parse("2026-01-01T00:00:00Z"));
        existingBus.setTags(Map.of("environment", "test"));
        when(eventBridgeService.describeEventBus(BUS, REGION)).thenReturn(existingBus);
        JsonNode properties = props(false);
        ((ObjectNode) properties).putArray("Tags")
                .addObject()
                .put("Key", "environment")
                .put("Value", "test");

        StackResource r = provisionExisting(Map.of(), properties);

        assertEquals("CREATE_COMPLETE", r.getStatus(), r.getStatusReason());
        assertEquals("[\"environment\"]", r.getAttributes().get(MANAGED_TAG_KEYS_ATTR));
    }

    @Test
    void updateOfBusProvisionedBeforePolicyTrackingAdoptsUnchangedPolicyWithoutReapplyingIt()
            throws Exception {
        when(eventBridgeService.createEventBus(eq(BUS), any(), any(), eq(REGION)))
                .thenThrow(new AwsException("ResourceAlreadyExistsException", "exists", 400));
        EventBus existingBus = bus(Instant.parse("2026-01-01T00:00:00Z"));
        existingBus.setPolicy("{\"Version\":\"2012-10-17\"}");
        when(eventBridgeService.describeEventBus(BUS, REGION)).thenReturn(existingBus);

        StackResource r = provisionExisting(Map.of(), props(true));

        assertEquals("CREATE_COMPLETE", r.getStatus(), r.getStatusReason());
        assertEquals(
                MAPPER.readTree("{\"Version\":\"2012-10-17\"}"),
                MAPPER.readTree(r.getAttributes().get(MANAGED_POLICY_ATTR)));
        verify(eventBridgeService, never())
                .putPermission(anyString(), any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void changedTrackedPolicyFailsWithoutMutatingTheOwnedBus() {
        when(eventBridgeService.createEventBus(eq(BUS), any(), any(), eq(REGION)))
                .thenThrow(new AwsException("ResourceAlreadyExistsException", "exists", 400));
        EventBus existingBus = bus(Instant.parse("2026-01-01T00:00:00Z"));
        existingBus.setPolicy("{\"Version\":\"2008-10-17\"}");
        when(eventBridgeService.describeEventBus(BUS, REGION)).thenReturn(existingBus);

        StackResource r = provisionExisting(
                Map.of(MANAGED_POLICY_ATTR, "{\"Version\":\"2008-10-17\"}"),
                props(true));

        assertEquals("CREATE_FAILED", r.getStatus());
        assertEquals(
                "Updating AWS::Events::EventBus Description, Tags, or Policy is not supported "
                        + "until transactional rollback is available.",
                r.getStatusReason());
        verify(eventBridgeService, never())
                .putPermission(anyString(), any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void updateOfBusRecreatedOutOfBandStillFails() {
        when(eventBridgeService.createEventBus(eq(BUS), any(), any(), eq(REGION)))
                .thenThrow(new AwsException("ResourceAlreadyExistsException", "exists", 400));
        when(eventBridgeService.describeEventBus(BUS, REGION))
                .thenReturn(bus(Instant.parse("2026-02-02T00:00:00Z")));

        StackResource r = provisionExisting(Map.of(CREATED_TIME_ATTR, "2026-01-01T00:00:00Z"));

        assertEquals("CREATE_FAILED", r.getStatus());
        assertNull(r.getAttributes().get(ROLLBACK_OWNED_ATTR),
                "a bus this stack no longer owns must not be deleted by rollback");
    }

    /**
     * rollbackCreatedResources skips any resource whose physicalId is still null, so identity has to
     * be recorded before the policy call — otherwise a malformed Policy leaves a live bus that
     * rollback cannot find, and it leaks permanently.
     */
    @Test
    void busIsRollbackDeletableWhenThePolicyCallFails() {
        when(eventBridgeService.createEventBus(eq(BUS), any(), any(), eq(REGION)))
                .thenReturn(bus(Instant.parse("2026-01-01T00:00:00Z")));
        doThrow(new AwsException("MalformedPolicyDocumentException", "bad policy", 400))
                .when(eventBridgeService)
                .putPermission(anyString(), any(), any(), any(), any(), anyString(), anyString());

        StackResource r = provisioner.provision("OrdersBus", "AWS::Events::EventBus", props(true),
                engine(), REGION, "000000000000", "my-stack", null, Map.of());

        assertEquals("CREATE_FAILED", r.getStatus());
        assertEquals(BUS, r.getPhysicalId(), "physicalId must be recorded before the policy call");
        assertEquals("true", r.getAttributes().get(ROLLBACK_OWNED_ATTR));
    }

    @Test
    void adoptedBusIsNotMarkedRollbackOwned() {
        when(eventBridgeService.createEventBus(eq(BUS), any(), any(), eq(REGION)))
                .thenThrow(new AwsException("ResourceAlreadyExistsException", "exists", 400));
        when(eventBridgeService.describeEventBus(BUS, REGION))
                .thenReturn(bus(Instant.parse("2026-01-01T00:00:00Z")));

        StackResource r = provisionExisting(Map.of());

        assertEquals("CREATE_COMPLETE", r.getStatus(), r.getStatusReason());
        assertNull(r.getAttributes().get(ROLLBACK_OWNED_ATTR),
                "a bus this stack did not create must not be deleted by rollback");
    }

    private StackResource provisionExisting(Map<String, String> existingAttributes) {
        return provisionExisting(existingAttributes, props(false));
    }

    private StackResource provisionExisting(Map<String, String> existingAttributes,
                                            JsonNode properties) {
        return provisioner.provision("OrdersBus", "AWS::Events::EventBus", properties,
                engine(), REGION, "000000000000", "my-stack", BUS, existingAttributes);
    }

    private JsonNode props(boolean withPolicy) {
        var node = MAPPER.createObjectNode();
        node.put("Name", BUS);
        if (withPolicy) {
            node.set("Policy", MAPPER.createObjectNode().put("Version", "2012-10-17"));
        }
        return node;
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", REGION, "my-stack",
                "stack/id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), MAPPER,
                (Function<String, String>) name -> null);
    }

    private EventBus bus(Instant createdTime) {
        return new EventBus(BUS, "arn:aws:events:" + REGION + ":000000000000:event-bus/" + BUS,
                null, createdTime);
    }
}
