package io.github.hectorvent.floci.services.mediapackage;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.mediapackage.model.MediaPackageChannel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AWS Elemental MediaPackage (v1) management plane: channels only. The HLS
 * ingest endpoints returned on create are plausible but non-functional; the
 * packaging data plane is not emulated.
 */
@ApplicationScoped
public class MediaPackageService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(MediaPackageService.class);
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, MediaPackageChannel> channels;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MediaPackageService(StorageFactory storageFactory, RegionResolver regionResolver,
                               ObjectMapper objectMapper) {
        this.channels = storageFactory.create("mediapackage", "mediapackage-channels.json",
                new TypeReference<Map<String, MediaPackageChannel>>() {});
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public MediaPackageChannel createChannel(String id, String description, Map<String, String> tags,
                                             String region) {
        if (id == null || id.isBlank()) {
            throw new AwsException("UnprocessableEntityException", "id is required", 422);
        }
        if (channels.get(id).isPresent()) {
            throw new AwsException("UnprocessableEntityException",
                    "Channel with id=" + id + " already exists", 422);
        }
        MediaPackageChannel channel = new MediaPackageChannel();
        channel.setId(id);
        channel.setArn(regionResolver.buildArn("mediapackage", region, "channels/" + uuid()));
        // Null stays null, the same round-trip rule MediaPackageV2Service
        // states: Terraform rejects a create that echoes "" for a
        // description it never sent. (The provider's own schema defaults
        // this field, so it is normally present - this guards the direct-SDK
        // path.)
        channel.setDescription(description);
        channel.setCreatedAt(Instant.now().toString());
        channel.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        channel.setHlsIngest(hlsIngest(region));
        channel.setAccountId(regionResolver.getAccountId());

        channels.put(id, channel);
        LOG.infov("Created MediaPackage channel: {0}", channel.getArn());
        return channel;
    }

    public MediaPackageChannel getChannel(String id) {
        return channels.get(id)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Channel with id=" + id + " not found", 404));
    }

    public MediaPackageChannel updateChannel(String id, String description) {
        MediaPackageChannel channel = getChannel(id);
        if (description != null) {
            channel.setDescription(description);
        }
        channels.put(id, channel);
        return channel;
    }

    public void deleteChannel(String id) {
        getChannel(id);
        channels.delete(id);
        LOG.infov("Deleted MediaPackage channel: {0}", id);
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Override
    public String serviceKey() {
        return "mediapackage";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = findByArn(arn).getTags();
        return tags != null ? tags : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        MediaPackageChannel channel = findByArn(arn);
        if (channel.getTags() == null) {
            channel.setTags(new HashMap<>());
        }
        channel.getTags().putAll(tags);
        channels.put(channel.getId(), channel);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        MediaPackageChannel channel = findByArn(arn);
        if (channel.getTags() != null && tagKeys != null) {
            tagKeys.forEach(channel.getTags()::remove);
        }
        channels.put(channel.getId(), channel);
    }

    private MediaPackageChannel findByArn(String arn) {
        return channels.scan(k -> true).stream()
                .filter(c -> arn.equals(c.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private ObjectNode hlsIngest(String region) {
        ObjectNode ingest = objectMapper.createObjectNode();
        ArrayNode endpoints = ingest.putArray("ingestEndpoints");
        for (int i = 0; i < 2; i++) {
            String endpointId = uuid();
            ObjectNode endpoint = endpoints.addObject();
            endpoint.put("id", endpointId);
            endpoint.put("url", "https://" + randomId() + ".mediapackage." + region
                    + ".amazonaws.com/in/v2/" + endpointId + "/" + endpointId + "/channel");
            endpoint.put("username", randomId());
            endpoint.put("password", randomId());
        }
        return ingest;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String randomId() {
        StringBuilder id = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }
}
