package io.github.hectorvent.floci.services.appintegrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appintegrations.model.DataIntegration;
import io.github.hectorvent.floci.services.appintegrations.model.EventIntegration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Amazon AppIntegrations (signing name {@code app-integrations}, API version 2020-07-29).
 * Event integrations are addressed by name, data integrations by generated id; both accept
 * their own ARN wherever the API takes an identifier.
 */
@ApplicationScoped
public class AppIntegrationsService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(AppIntegrationsService.class);

    static final String SERVICE = "app-integrations";
    private static final String EVENT_INTEGRATION_RESOURCE = "event-integration";
    private static final String DATA_INTEGRATION_RESOURCE = "data-integration";

    private final StorageBackend<String, EventIntegration> eventIntegrations;
    private final StorageBackend<String, DataIntegration> dataIntegrations;
    private final RegionResolver regionResolver;

    @Inject
    public AppIntegrationsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.eventIntegrations = storageFactory.create("appintegrations",
                "appintegrations-event-integrations.json",
                new TypeReference<Map<String, EventIntegration>>() {});
        this.dataIntegrations = storageFactory.create("appintegrations",
                "appintegrations-data-integrations.json",
                new TypeReference<Map<String, DataIntegration>>() {});
        this.regionResolver = regionResolver;
    }

    public EventIntegration createEventIntegration(String name, String description, JsonNode eventFilter,
                                                   String eventBridgeBus, Map<String, String> tags,
                                                   String region) {
        requireMember(name, "Name");
        requireMember(eventBridgeBus, "EventBridgeBus");
        if (eventFilter == null || !eventFilter.isObject()
                || eventFilter.get("Source") == null || eventFilter.get("Source").isNull()) {
            throw new AwsException("InvalidRequestException", "EventFilter.Source is required", 400);
        }
        if (eventIntegrations.get(key(region, name)).isPresent()) {
            throw new AwsException("DuplicateResourceException",
                    "An event integration named " + name + " already exists.", 409);
        }

        EventIntegration integration = new EventIntegration();
        integration.setName(name);
        integration.setDescription(description);
        integration.setEventIntegrationArn(
                regionResolver.buildArn(SERVICE, region, EVENT_INTEGRATION_RESOURCE + "/" + name));
        integration.setEventBridgeBus(eventBridgeBus);
        integration.setEventFilterSource(eventFilter.get("Source").asText());
        integration.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        integration.setAccountId(regionResolver.getAccountId());

        eventIntegrations.put(key(region, name), integration);
        LOG.infov("Created AppIntegrations event integration: {0}", name);
        return integration;
    }

    public EventIntegration getEventIntegration(String name, String region) {
        String resolved = resolveIdentifier(name, EVENT_INTEGRATION_RESOURCE);
        return eventIntegrations.get(key(region, resolved))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Event integration " + name + " does not exist.", 404));
    }

    /**
     * {@code Description} is the only updatable member. An absent member leaves the stored
     * value alone, which is how AWS treats an optional member the caller did not send.
     */
    public EventIntegration updateEventIntegration(String name, Optional<String> description, String region) {
        EventIntegration integration = getEventIntegration(name, region);
        description.ifPresent(integration::setDescription);
        eventIntegrations.put(key(region, integration.getName()), integration);
        return integration;
    }

    public void deleteEventIntegration(String name, String region) {
        EventIntegration integration = getEventIntegration(name, region);
        eventIntegrations.delete(key(region, integration.getName()));
        LOG.infov("Deleted AppIntegrations event integration: {0}", integration.getName());
    }

    public List<EventIntegration> listEventIntegrations(String region) {
        String prefix = region + "::";
        return eventIntegrations.scan(k -> k.startsWith(prefix));
    }

    public DataIntegration createDataIntegration(String name, String description, String kmsKey,
                                                 String sourceUri, JsonNode scheduleConfig,
                                                 JsonNode fileConfiguration, JsonNode objectConfiguration,
                                                 Map<String, String> tags, String region) {
        requireMember(name, "Name");
        requireMember(kmsKey, "KmsKey");
        for (DataIntegration existing : listDataIntegrations(region)) {
            if (name.equals(existing.getName())) {
                throw new AwsException("DuplicateResourceException",
                        "A data integration named " + name + " already exists.", 409);
            }
        }

        String id = UUID.randomUUID().toString();
        DataIntegration integration = new DataIntegration();
        integration.setId(id);
        integration.setArn(regionResolver.buildArn(SERVICE, region, DATA_INTEGRATION_RESOURCE + "/" + id));
        integration.setName(name);
        integration.setDescription(description);
        integration.setKmsKey(kmsKey);
        integration.setSourceUri(sourceUri);
        integration.setScheduleConfiguration(scheduleConfig);
        integration.setFileConfiguration(fileConfiguration);
        integration.setObjectConfiguration(objectConfiguration);
        integration.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        integration.setAccountId(regionResolver.getAccountId());

        dataIntegrations.put(key(region, id), integration);
        LOG.infov("Created AppIntegrations data integration: {0}", id);
        return integration;
    }

    public DataIntegration getDataIntegration(String identifier, String region) {
        String id = resolveIdentifier(identifier, DATA_INTEGRATION_RESOURCE);
        return dataIntegrations.get(key(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Data integration " + identifier + " does not exist.", 404));
    }

    public DataIntegration updateDataIntegration(String identifier, Optional<String> name,
                                                 Optional<String> description, String region) {
        DataIntegration integration = getDataIntegration(identifier, region);
        name.ifPresent(integration::setName);
        description.ifPresent(integration::setDescription);
        dataIntegrations.put(key(region, integration.getId()), integration);
        return integration;
    }

    public void deleteDataIntegration(String identifier, String region) {
        DataIntegration integration = getDataIntegration(identifier, region);
        dataIntegrations.delete(key(region, integration.getId()));
        LOG.infov("Deleted AppIntegrations data integration: {0}", integration.getId());
    }

    public List<DataIntegration> listDataIntegrations(String region) {
        String prefix = region + "::";
        return dataIntegrations.scan(k -> k.startsWith(prefix));
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    /** AppIntegrations models no {@code responseCode} on either tag write, so both answer 200. */
    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = tagsOf(findByArn(arn, region));
        return tags != null ? tags : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        Object resource = findByArn(arn, region);
        if (tagsOf(resource) == null) {
            setTags(resource, new LinkedHashMap<>());
        }
        tagsOf(resource).putAll(tags);
        store(region, resource);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        Object resource = findByArn(arn, region);
        Map<String, String> tags = tagsOf(resource);
        if (tags != null && tagKeys != null) {
            tagKeys.forEach(tags::remove);
        }
        store(region, resource);
    }

    private Object findByArn(String arn, String region) {
        String prefix = region + "::";
        for (EventIntegration event : eventIntegrations.scan(k -> k.startsWith(prefix))) {
            if (arn.equals(event.getEventIntegrationArn())) {
                return event;
            }
        }
        for (DataIntegration data : dataIntegrations.scan(k -> k.startsWith(prefix))) {
            if (arn.equals(data.getArn())) {
                return data;
            }
        }
        throw new AwsException("ResourceNotFoundException", "Resource " + arn + " does not exist.", 404);
    }

    private Map<String, String> tagsOf(Object resource) {
        return resource instanceof EventIntegration event ? event.getTags() : ((DataIntegration) resource).getTags();
    }

    private void setTags(Object resource, Map<String, String> tags) {
        if (resource instanceof EventIntegration event) {
            event.setTags(tags);
        } else {
            ((DataIntegration) resource).setTags(tags);
        }
    }

    private void store(String region, Object resource) {
        if (resource instanceof EventIntegration event) {
            eventIntegrations.put(key(region, event.getName()), event);
        } else {
            DataIntegration data = (DataIntegration) resource;
            dataIntegrations.put(key(region, data.getId()), data);
        }
    }

    /**
     * Both the bare identifier and the resource's own ARN are accepted, as AWS does.
     * An ARN naming a different AppIntegrations resource type is rejected rather than
     * silently resolving against the wrong store.
     */
    private String resolveIdentifier(String identifier, String expectedResourceType) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("InvalidRequestException", "Identifier is required", 400);
        }
        if (!identifier.startsWith("arn:")) {
            return identifier;
        }
        String resource;
        try {
            resource = AwsArnUtils.parse(identifier).resource();
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidRequestException", "Invalid ARN: " + identifier, 400);
        }
        int slash = resource.indexOf('/');
        if (slash < 0 || slash == resource.length() - 1
                || !expectedResourceType.equals(resource.substring(0, slash))) {
            throw new AwsException("InvalidRequestException", "Invalid ARN: " + identifier, 400);
        }
        return resource.substring(slash + 1);
    }

    private void requireMember(String value, String member) {
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidRequestException", member + " is required", 400);
        }
    }

    private String key(String region, String identifier) {
        return region + "::" + identifier;
    }
}
