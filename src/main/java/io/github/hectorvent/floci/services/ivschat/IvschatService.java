package io.github.hectorvent.floci.services.ivschat;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ivschat.model.IvschatLoggingConfiguration;
import io.github.hectorvent.floci.services.ivschat.model.IvschatRoom;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Amazon IVS Chat management plane. Logging configurations are ACTIVE as soon
 * as a create returns, so provider waiters complete on their first poll.
 */
@ApplicationScoped
public class IvschatService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(IvschatService.class);
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, IvschatRoom> rooms;
    private final StorageBackend<String, IvschatLoggingConfiguration> loggingConfigurations;
    private final RegionResolver regionResolver;

    @Inject
    public IvschatService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.rooms = storageFactory.create("ivschat", "ivschat-rooms.json",
                new TypeReference<Map<String, IvschatRoom>>() {});
        this.loggingConfigurations = storageFactory.create("ivschat", "ivschat-logging-configurations.json",
                new TypeReference<Map<String, IvschatLoggingConfiguration>>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Rooms ────────────────────────────

    public IvschatRoom createRoom(String name, Integer maximumMessageRatePerSecond,
                                  Integer maximumMessageLength, JsonNode messageReviewHandler,
                                  List<String> loggingConfigurationIdentifiers,
                                  Map<String, String> tags, String region) {
        String roomId = randomId();
        IvschatRoom room = new IvschatRoom();
        room.setId(roomId);
        room.setArn(regionResolver.buildArn("ivschat", region, "room/" + roomId));
        room.setName(name != null ? name : "");
        Instant now = Instant.now();
        room.setCreateTime(now);
        room.setUpdateTime(now);
        room.setMaximumMessageRatePerSecond(maximumMessageRatePerSecond != null ? maximumMessageRatePerSecond : 10);
        room.setMaximumMessageLength(maximumMessageLength != null ? maximumMessageLength : 500);
        room.setMessageReviewHandler(messageReviewHandler);
        room.setLoggingConfigurationIdentifiers(loggingConfigurationIdentifiers != null
                ? List.copyOf(loggingConfigurationIdentifiers)
                : List.of());
        room.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        room.setAccountId(regionResolver.getAccountId());

        rooms.put(room.getArn(), room);
        LOG.infov("Created IVS Chat room: {0}", room.getArn());
        return room;
    }

    public IvschatRoom getRoom(String identifier, String region) {
        return findRoom(identifier)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Room " + identifier + " does not exist.", 404));
    }

    public void deleteRoom(String identifier, String region) {
        IvschatRoom room = getRoom(identifier, region);
        rooms.delete(room.getArn());
        LOG.infov("Deleted IVS Chat room: {0}", room.getArn());
    }

    private Optional<IvschatRoom> findRoom(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("ValidationException", "identifier is required", 400);
        }
        if (identifier.startsWith("arn:")) {
            return rooms.get(identifier);
        }
        return rooms.scan(key -> true).stream()
                .filter(room -> identifier.equals(room.getId()))
                .findFirst();
    }

    // ─────────────────── Logging configurations ───────────────────

    public IvschatLoggingConfiguration createLoggingConfiguration(String name, JsonNode destinationConfiguration,
                                                                  Map<String, String> tags, String region) {
        if (destinationConfiguration == null || destinationConfiguration.isNull()) {
            throw new AwsException("ValidationException", "destinationConfiguration is required", 400);
        }
        String configurationId = randomId();
        IvschatLoggingConfiguration configuration = new IvschatLoggingConfiguration();
        configuration.setId(configurationId);
        configuration.setArn(regionResolver.buildArn("ivschat", region, "logging-configuration/" + configurationId));
        configuration.setName(name != null ? name : "");
        Instant now = Instant.now();
        configuration.setCreateTime(now);
        configuration.setUpdateTime(now);
        configuration.setDestinationConfiguration(destinationConfiguration);
        configuration.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        configuration.setAccountId(regionResolver.getAccountId());

        loggingConfigurations.put(configuration.getArn(), configuration);
        LOG.infov("Created IVS Chat logging configuration: {0}", configuration.getArn());
        return configuration;
    }

    public IvschatLoggingConfiguration getLoggingConfiguration(String identifier, String region) {
        return findLoggingConfiguration(identifier)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "LoggingConfiguration " + identifier + " does not exist.", 404));
    }

    public void deleteLoggingConfiguration(String identifier, String region) {
        IvschatLoggingConfiguration configuration = getLoggingConfiguration(identifier, region);
        loggingConfigurations.delete(configuration.getArn());
        LOG.infov("Deleted IVS Chat logging configuration: {0}", configuration.getArn());
    }

    private Optional<IvschatLoggingConfiguration> findLoggingConfiguration(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("ValidationException", "identifier is required", 400);
        }
        if (identifier.startsWith("arn:")) {
            return loggingConfigurations.get(identifier);
        }
        return loggingConfigurations.scan(key -> true).stream()
                .filter(configuration -> identifier.equals(configuration.getId()))
                .findFirst();
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Override
    public String serviceKey() {
        return "ivschat";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Object resource = findByArn(arn);
        Map<String, String> tags = resource instanceof IvschatRoom room
                ? room.getTags()
                : ((IvschatLoggingConfiguration) resource).getTags();
        return tags != null ? tags : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        Object resource = findByArn(arn);
        if (resource instanceof IvschatRoom room) {
            if (room.getTags() == null) {
                room.setTags(new HashMap<>());
            }
            room.getTags().putAll(tags);
            rooms.put(arn, room);
        } else {
            IvschatLoggingConfiguration configuration = (IvschatLoggingConfiguration) resource;
            if (configuration.getTags() == null) {
                configuration.setTags(new HashMap<>());
            }
            configuration.getTags().putAll(tags);
            loggingConfigurations.put(arn, configuration);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        Object resource = findByArn(arn);
        if (resource instanceof IvschatRoom room) {
            if (room.getTags() != null && tagKeys != null) {
                tagKeys.forEach(room.getTags()::remove);
            }
            rooms.put(arn, room);
        } else {
            IvschatLoggingConfiguration configuration = (IvschatLoggingConfiguration) resource;
            if (configuration.getTags() != null && tagKeys != null) {
                tagKeys.forEach(configuration.getTags()::remove);
            }
            loggingConfigurations.put(arn, configuration);
        }
    }

    private Object findByArn(String arn) {
        return rooms.get(arn).<Object>map(r -> r)
                .or(() -> loggingConfigurations.get(arn).map(c -> c))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private static String randomId() {
        StringBuilder id = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }
}
