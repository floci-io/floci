package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedshiftEventSubscriptionCfnProvisionerTest {

    private static final String TYPE = "AWS::Redshift::EventSubscription";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:events";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RedshiftService redshift = mock(RedshiftService.class);
    private final RedshiftEventSubscriptionCfnProvisioner provisioner =
            new RedshiftEventSubscriptionCfnProvisioner(redshift);

    @Test
    void createSetsRefAndEverySchemaAttribute() throws Exception {
        EventSubscription subscription = subscription("watcher");
        when(redshift.createEventSubscription(eq("watcher"), eq(TOPIC), eq("cluster"), eq(List.of("warehouse")),
                eq(List.of("management")), eq("INFO"), eq(true), eq(Map.of("team", "analytics"))))
                .thenReturn(subscription);
        ObjectNode props = properties("watcher");
        props.put("SourceType", "cluster").put("Severity", "INFO").put("Enabled", true);
        props.putArray("SourceIds").add("warehouse");
        props.putArray("EventCategories").add("management");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "analytics");
        StackResource resource = resource();

        provisioner.provision(resource, props, context(null));

        assertEquals("watcher", resource.getPhysicalId());
        assertEquals(Set.of("CustomerAwsId", "CustSubscriptionId", "Status", "SubscriptionCreationTime",
                "SourceIdsList", "EventCategoriesList"), resource.getAttributes().keySet());
        assertEquals("000000000000", resource.getAttributes().get("CustomerAwsId"));
        assertEquals("watcher", resource.getAttributes().get("CustSubscriptionId"));
        assertEquals("active", resource.getAttributes().get("Status"));
        assertEquals("2026-10-26T12:00:00Z", resource.getAttributes().get("SubscriptionCreationTime"));
        assertEquals(mapper.readTree("[\"warehouse\"]"), provisioner.attributeValue(TYPE, "SourceIdsList",
                resource.getAttributes().get("SourceIdsList")));
        assertEquals(mapper.readTree("[\"management\"]"), provisioner.attributeValue(TYPE, "EventCategoriesList",
                resource.getAttributes().get("EventCategoriesList")));
    }

    @Test
    void updateKeepsPhysicalIdAndReconcilesTags() {
        when(redshift.describeEventSubscriptions("watcher", null, null, null, null))
                .thenReturn(new PaginatedResult<>(List.of(subscription("watcher")), null));
        when(redshift.reconcileEventSubscription(eq("watcher"), eq(TOPIC), any(), any(), any(), any(), eq(false), any()))
                .thenReturn(subscription("watcher"));
        ObjectNode props = properties("watcher").put("Enabled", false);
        StackResource resource = resource();
        resource.setPhysicalId("watcher");

        provisioner.provision(resource, props, context("watcher"));

        assertEquals("watcher", resource.getPhysicalId());
        verify(redshift, never()).createEventSubscription(any(), any(), any(), any(), any(), any(), any(), any());
        verify(redshift).reconcileEventSubscription("watcher", TOPIC, null, List.of(), List.of(), null, false, Map.of());
    }

    @Test
    void inPlaceRollbackRestoresFiltersTagsAndSavedAttributes() {
        EventSubscription prior = subscription("watcher");
        prior.setSnsTopicArn(TOPIC);
        prior.setSourceType("cluster");
        prior.setSeverity("INFO");
        prior.setEnabled(true);
        prior.setTags(Map.of("phase", "prior"));
        prior.setStatus("topic-not-exist");
        when(redshift.describeEventSubscriptions("watcher", null, null, null, null))
                .thenReturn(new PaginatedResult<>(List.of(prior), null));
        EventSubscription updated = subscription("watcher");
        updated.setSourceIdsList(List.of("replacement"));
        updated.setEventCategoriesList(List.of("configuration"));
        when(redshift.reconcileEventSubscription(eq("watcher"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(updated);
        StackResource resource = resource();
        resource.setPhysicalId("watcher");
        Map<String, String> priorAttributes = Map.of(
                "CustomerAwsId", "000000000000", "CustSubscriptionId", "watcher", "Status", "topic-not-exist",
                "SubscriptionCreationTime", "2026-10-26T12:00:00Z", "SourceIdsList", "[\"warehouse\"]",
                "EventCategoriesList", "[\"management\"]", CfnRollback.USES_ATTR_PREFIX + "Test", "retained");
        resource.getAttributes().putAll(priorAttributes);
        provisioner.provision(resource, properties("watcher").put("Enabled", false), context("watcher"));
        assertEquals("active", resource.getAttributes().get("Status"));
        assertEquals("[\"replacement\"]", resource.getAttributes().get("SourceIdsList"));
        assertEquals("[\"configuration\"]", resource.getAttributes().get("EventCategoriesList"));

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals(priorAttributes, resource.getAttributes());
        verify(redshift).restoreEventSubscription("watcher", TOPIC, "cluster", List.of("warehouse"),
                List.of("management"), "INFO", true, Map.of("phase", "prior"), "topic-not-exist");
    }

    @Test
    void changedNameCreatesReplacementAndDeletesPriorAfterCommit() {
        when(redshift.createEventSubscription(eq("replacement"), eq(TOPIC), any(), any(), any(), any(), any(), any()))
                .thenReturn(subscription("replacement"));
        StackResource resource = resource();
        resource.setPhysicalId("watcher");
        resource.getAttributes().put("CustSubscriptionId", "watcher");

        provisioner.provision(resource, properties("replacement"), context("watcher"));

        assertEquals("replacement", resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        verify(redshift, never()).deleteEventSubscription(any());
        provisioner.completeUpdate(resource);
        verify(redshift).deleteEventSubscription("watcher");
    }

    @Test
    void replacementRollbackDeletesNewSubscriptionAndRestoresPriorIdentity() {
        when(redshift.createEventSubscription(eq("replacement"), eq(TOPIC), any(), any(), any(), any(), any(), any()))
                .thenReturn(subscription("replacement"));
        StackResource resource = resource();
        resource.setPhysicalId("watcher");
        resource.getAttributes().put("CustSubscriptionId", "watcher");
        provisioner.provision(resource, properties("replacement"), context("watcher"));

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("watcher", resource.getPhysicalId());
        verify(redshift).deleteEventSubscription("replacement");
    }

    @Test
    void deleteToleratesOnlyMissingSubscription() {
        doThrow(new AwsException("SubscriptionNotFound", "gone", 404)).when(redshift).deleteEventSubscription("gone");
        assertDoesNotThrow(() -> provisioner.delete(TYPE, "gone", "us-east-1"));
        doThrow(new AwsException("SNSNoAuthorization", "denied", 400)).when(redshift).deleteEventSubscription("denied");
        assertThrows(AwsException.class, () -> provisioner.delete(TYPE, "denied", "us-east-1"));
    }

    @Test
    void missingRequiredNameFailsBeforeCallingService() {
        assertThrows(AwsException.class, () -> provisioner.provision(resource(), mapper.createObjectNode(), context(null)));
        verifyNoInteractions(redshift);
    }

    private ObjectNode properties(String name) {
        return mapper.createObjectNode().put("SubscriptionName", name).put("SnsTopicArn", TOPIC);
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Watcher");
        resource.setResourceType(TYPE);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> invocation.getArgument(0, JsonNode.class).asText());
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(invocation -> {
            List<String> values = new ArrayList<>();
            JsonNode array = invocation.getArgument(0);
            array.forEach(value -> values.add(value.asText()));
            return values;
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "event-stack", prior);
    }

    private EventSubscription subscription(String name) {
        EventSubscription subscription = new EventSubscription();
        subscription.setCustSubscriptionId(name);
        subscription.setCustomerAwsId("000000000000");
        subscription.setStatus("active");
        subscription.setSubscriptionCreationTime(Instant.parse("2026-10-26T12:00:00Z"));
        subscription.setSourceIdsList(List.of("warehouse"));
        subscription.setEventCategoriesList(List.of("management"));
        return subscription;
    }
}
