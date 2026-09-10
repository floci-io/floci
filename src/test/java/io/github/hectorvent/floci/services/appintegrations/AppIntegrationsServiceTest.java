package io.github.hectorvent.floci.services.appintegrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appintegrations.model.DataIntegration;
import io.github.hectorvent.floci.services.appintegrations.model.EventIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AppIntegrations domain rules that the REST layer only passes through: required members,
 * duplicate names, ARN-or-id resolution, and the partial-update semantics of the two
 * PATCH operations.
 */
class AppIntegrationsServiceTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AppIntegrationsService service;

    @BeforeEach
    void setUp() {
        service = new AppIntegrationsService(new SharedStorageFactory(),
                new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void createEventIntegrationBuildsTheAwsArnAndStoresTheFilterSource() {
        EventIntegration integration = service.createEventIntegration("orders", "partner events",
                eventFilter("aws.partner/example.com/1234"), "floci-bus", Map.of("team", "data"), REGION);

        assertEquals("arn:aws:app-integrations:us-east-1:000000000000:event-integration/orders",
                integration.getEventIntegrationArn());
        assertEquals("aws.partner/example.com/1234", integration.getEventFilterSource());
        assertEquals("floci-bus", integration.getEventBridgeBus());
        assertEquals("data", integration.getTags().get("team"));
    }

    @Test
    void createEventIntegrationRejectsAMissingRequiredMember() {
        AwsException noName = assertThrows(AwsException.class, () -> service.createEventIntegration(
                null, null, eventFilter("aws.partner/x"), "floci-bus", Map.of(), REGION));
        assertEquals("InvalidRequestException", noName.getErrorCode());

        AwsException noBus = assertThrows(AwsException.class, () -> service.createEventIntegration(
                "orders", null, eventFilter("aws.partner/x"), null, Map.of(), REGION));
        assertEquals("InvalidRequestException", noBus.getErrorCode());

        AwsException noFilter = assertThrows(AwsException.class, () -> service.createEventIntegration(
                "orders", null, null, "floci-bus", Map.of(), REGION));
        assertEquals("InvalidRequestException", noFilter.getErrorCode());
    }

    @Test
    void duplicateEventIntegrationNameRaisesDuplicateResource() {
        service.createEventIntegration("orders", null, eventFilter("aws.partner/x"), "bus", Map.of(), REGION);

        AwsException duplicate = assertThrows(AwsException.class, () -> service.createEventIntegration(
                "orders", null, eventFilter("aws.partner/x"), "bus", Map.of(), REGION));
        assertEquals("DuplicateResourceException", duplicate.getErrorCode());
        assertEquals(409, duplicate.getHttpStatus());
    }

    @Test
    void updateEventIntegrationLeavesAnAbsentDescriptionAlone() {
        service.createEventIntegration("orders", "first", eventFilter("aws.partner/x"), "bus", Map.of(), REGION);

        service.updateEventIntegration("orders", Optional.of("second"), REGION);
        assertEquals("second", service.getEventIntegration("orders", REGION).getDescription());

        service.updateEventIntegration("orders", Optional.empty(), REGION);
        assertEquals("second", service.getEventIntegration("orders", REGION).getDescription());
    }

    @Test
    void getMissingEventIntegrationRaisesResourceNotFound() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getEventIntegration("nope", REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void createDataIntegrationRequiresNameAndKmsKey() {
        AwsException noName = assertThrows(AwsException.class, () -> service.createDataIntegration(
                null, null, "arn:aws:kms:us-east-1:000000000000:key/abc", null, null, null, null,
                Map.of(), REGION));
        assertEquals("InvalidRequestException", noName.getErrorCode());

        AwsException noKey = assertThrows(AwsException.class, () -> service.createDataIntegration(
                "orders", null, null, null, null, null, null, Map.of(), REGION));
        assertEquals("InvalidRequestException", noKey.getErrorCode());
    }

    @Test
    void dataIntegrationResolvesByIdAndByItsOwnArn() {
        DataIntegration created = service.createDataIntegration("orders", "pull", "key/abc",
                "Salesforce://AppFlow/test", null, null, null, Map.of(), REGION);

        assertEquals(created.getId(), service.getDataIntegration(created.getId(), REGION).getId());
        assertEquals(created.getId(), service.getDataIntegration(created.getArn(), REGION).getId());

        AwsException wrongType = assertThrows(AwsException.class, () -> service.getDataIntegration(
                "arn:aws:app-integrations:us-east-1:000000000000:event-integration/orders", REGION));
        assertEquals("InvalidRequestException", wrongType.getErrorCode());
    }

    @Test
    void updateDataIntegrationAppliesOnlyThePresentMembers() {
        DataIntegration created = service.createDataIntegration("orders", "pull", "key/abc",
                null, null, null, null, Map.of(), REGION);

        service.updateDataIntegration(created.getId(), Optional.empty(), Optional.of("revised"), REGION);

        DataIntegration updated = service.getDataIntegration(created.getId(), REGION);
        assertEquals("orders", updated.getName());
        assertEquals("revised", updated.getDescription());
    }

    @Test
    void tagsAreSharedAcrossBothResourceTypesByArn() {
        EventIntegration event = service.createEventIntegration("orders", null,
                eventFilter("aws.partner/x"), "bus", Map.of("team", "data"), REGION);
        DataIntegration data = service.createDataIntegration("orders-data", null, "key/abc",
                null, null, null, null, Map.of(), REGION);

        service.tagResource(REGION, data.getArn(), Map.of("tier", "gold"));
        assertEquals("gold", service.listTags(REGION, data.getArn()).get("tier"));
        assertEquals("data", service.listTags(REGION, event.getEventIntegrationArn()).get("team"));

        service.untagResource(REGION, event.getEventIntegrationArn(), List.of("team"));
        assertTrue(service.listTags(REGION, event.getEventIntegrationArn()).isEmpty());

        AwsException unknown = assertThrows(AwsException.class, () -> service.listTags(REGION,
                "arn:aws:app-integrations:us-east-1:000000000000:data-integration/missing"));
        assertEquals("ResourceNotFoundException", unknown.getErrorCode());
    }

    @Test
    void tagWritesAnswerTwoHundredAsTheAwsModelDoes() {
        assertEquals(200, service.tagResourceSuccessStatus());
        assertEquals(200, service.untagResourceSuccessStatus());
        assertEquals("app-integrations", service.serviceKey());
    }

    @Test
    void deleteRemovesTheIntegrationFromItsListing() {
        service.createEventIntegration("orders", null, eventFilter("aws.partner/x"), "bus", Map.of(), REGION);
        DataIntegration data = service.createDataIntegration("orders-data", null, "key/abc",
                null, null, null, null, Map.of(), REGION);

        service.deleteEventIntegration("orders", REGION);
        service.deleteDataIntegration(data.getArn(), REGION);

        assertTrue(service.listEventIntegrations(REGION).isEmpty());
        assertTrue(service.listDataIntegrations(REGION).isEmpty());
    }

    private ObjectNode eventFilter(String source) {
        ObjectNode filter = MAPPER.createObjectNode();
        filter.put("Source", source);
        return filter;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(
                    fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
