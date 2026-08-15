package io.github.hectorvent.floci.services.ivs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ivs.model.IvsChannel;
import io.github.hectorvent.floci.services.ivs.model.IvsPlaybackKeyPair;
import io.github.hectorvent.floci.services.ivs.model.IvsRecordingConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Amazon Interactive Video Service management plane. Recording configurations
 * are ACTIVE as soon as a create returns, so provider waiters complete on their
 * first poll. Ingest and playback endpoints are plausible but non-functional.
 */
@ApplicationScoped
public class IvsService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(IvsService.class);
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, IvsChannel> channels;
    private final StorageBackend<String, IvsPlaybackKeyPair> keyPairs;
    private final StorageBackend<String, IvsRecordingConfiguration> recordingConfigurations;
    private final RegionResolver regionResolver;

    @Inject
    public IvsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.channels = storageFactory.create("ivs", "ivs-channels.json",
                new TypeReference<Map<String, IvsChannel>>() {});
        this.keyPairs = storageFactory.create("ivs", "ivs-playback-key-pairs.json",
                new TypeReference<Map<String, IvsPlaybackKeyPair>>() {});
        this.recordingConfigurations = storageFactory.create("ivs", "ivs-recording-configurations.json",
                new TypeReference<Map<String, IvsRecordingConfiguration>>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Channels ────────────────────────────

    public IvsChannel createChannel(String name, String latencyMode, String type, String preset,
                                    boolean authorized, boolean insecureIngest,
                                    String recordingConfigurationArn, Map<String, String> tags,
                                    String region) {
        String channelId = randomId();
        IvsChannel channel = new IvsChannel();
        channel.setArn(regionResolver.buildArn("ivs", region, "channel/" + channelId));
        channel.setName(name != null ? name : "");
        channel.setLatencyMode(latencyMode != null ? latencyMode : "LOW");
        channel.setType(type != null ? type : "STANDARD");
        channel.setPreset(preset != null ? preset : "");
        channel.setAuthorized(authorized);
        channel.setInsecureIngest(insecureIngest);
        channel.setRecordingConfigurationArn(recordingConfigurationArn != null ? recordingConfigurationArn : "");
        channel.setIngestEndpoint(channelId + ".global-contribute.live-video.net");
        channel.setPlaybackUrl("https://" + channelId + "." + region
                + ".playback.live-video.net/api/video/v1/" + region + ".channel." + channelId + ".m3u8");
        channel.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        channel.setStreamKeyArn(regionResolver.buildArn("ivs", region, "stream-key/" + randomId()));
        channel.setStreamKeyValue("sk_" + region + "_" + randomId() + randomId());
        channel.setAccountId(regionResolver.getAccountId());

        channels.put(channel.getArn(), channel);
        LOG.infov("Created IVS channel: {0}", channel.getArn());
        return channel;
    }

    public IvsChannel getChannel(String arn, String region) {
        requireArn(arn);
        return channels.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Channel " + arn + " does not exist.", 404));
    }

    public void deleteChannel(String arn, String region) {
        getChannel(arn, region);
        channels.delete(arn);
        LOG.infov("Deleted IVS channel: {0}", arn);
    }

    // ─────────────────────── Playback key pairs ───────────────────────

    public IvsPlaybackKeyPair importPlaybackKeyPair(String publicKeyMaterial, String name,
                                                    Map<String, String> tags, String region) {
        if (publicKeyMaterial == null || publicKeyMaterial.isBlank()) {
            throw new AwsException("ValidationException", "publicKeyMaterial is required", 400);
        }
        IvsPlaybackKeyPair keyPair = new IvsPlaybackKeyPair();
        keyPair.setArn(regionResolver.buildArn("ivs", region, "playback-key/" + randomId()));
        keyPair.setName(name != null ? name : "");
        keyPair.setFingerprint(fingerprint(publicKeyMaterial));
        keyPair.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        keyPair.setAccountId(regionResolver.getAccountId());

        keyPairs.put(keyPair.getArn(), keyPair);
        LOG.infov("Imported IVS playback key pair: {0}", keyPair.getArn());
        return keyPair;
    }

    public IvsPlaybackKeyPair getPlaybackKeyPair(String arn, String region) {
        requireArn(arn);
        return keyPairs.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "PlaybackKeyPair " + arn + " does not exist.", 404));
    }

    public void deletePlaybackKeyPair(String arn, String region) {
        getPlaybackKeyPair(arn, region);
        keyPairs.delete(arn);
        LOG.infov("Deleted IVS playback key pair: {0}", arn);
    }

    // ─────────────────── Recording configurations ───────────────────

    public IvsRecordingConfiguration createRecordingConfiguration(String name, JsonNode destinationConfiguration,
                                                                  JsonNode thumbnailConfiguration,
                                                                  JsonNode renditionConfiguration,
                                                                  Integer recordingReconnectWindowSeconds,
                                                                  Map<String, String> tags, String region) {
        if (destinationConfiguration == null || destinationConfiguration.isNull()) {
            throw new AwsException("ValidationException", "destinationConfiguration is required", 400);
        }
        IvsRecordingConfiguration configuration = new IvsRecordingConfiguration();
        configuration.setArn(regionResolver.buildArn("ivs", region, "recording-configuration/" + randomId()));
        configuration.setName(name != null ? name : "");
        configuration.setDestinationConfiguration(destinationConfiguration);
        configuration.setThumbnailConfiguration(thumbnailConfiguration);
        configuration.setRenditionConfiguration(renditionConfiguration);
        configuration.setRecordingReconnectWindowSeconds(recordingReconnectWindowSeconds);
        configuration.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        configuration.setAccountId(regionResolver.getAccountId());

        recordingConfigurations.put(configuration.getArn(), configuration);
        LOG.infov("Created IVS recording configuration: {0}", configuration.getArn());
        return configuration;
    }

    public IvsRecordingConfiguration getRecordingConfiguration(String arn, String region) {
        requireArn(arn);
        return recordingConfigurations.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "RecordingConfiguration " + arn + " does not exist.", 404));
    }

    public void deleteRecordingConfiguration(String arn, String region) {
        getRecordingConfiguration(arn, region);
        recordingConfigurations.delete(arn);
        LOG.infov("Deleted IVS recording configuration: {0}", arn);
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Override
    public String serviceKey() {
        return "ivs";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = tagsOf(findByArn(arn));
        return tags != null ? tags : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        Object resource = findByArn(arn);
        Map<String, String> existing = tagsOf(resource);
        existing.putAll(tags);
        persist(arn, resource);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        Object resource = findByArn(arn);
        Map<String, String> existing = tagsOf(resource);
        if (tagKeys != null) {
            tagKeys.forEach(existing::remove);
        }
        persist(arn, resource);
    }

    private Object findByArn(String arn) {
        return channels.get(arn).<Object>map(c -> c)
                .or(() -> keyPairs.get(arn).map(k -> k))
                .or(() -> recordingConfigurations.get(arn).map(r -> r))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private Map<String, String> tagsOf(Object resource) {
        if (resource instanceof IvsChannel channel) {
            if (channel.getTags() == null) {
                channel.setTags(new HashMap<>());
            }
            return channel.getTags();
        }
        if (resource instanceof IvsPlaybackKeyPair keyPair) {
            if (keyPair.getTags() == null) {
                keyPair.setTags(new HashMap<>());
            }
            return keyPair.getTags();
        }
        IvsRecordingConfiguration configuration = (IvsRecordingConfiguration) resource;
        if (configuration.getTags() == null) {
            configuration.setTags(new HashMap<>());
        }
        return configuration.getTags();
    }

    private void persist(String arn, Object resource) {
        if (resource instanceof IvsChannel channel) {
            channels.put(arn, channel);
        } else if (resource instanceof IvsPlaybackKeyPair keyPair) {
            keyPairs.put(arn, keyPair);
        } else {
            recordingConfigurations.put(arn, (IvsRecordingConfiguration) resource);
        }
    }

    private static void requireArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("ValidationException", "arn is required", 400);
        }
    }

    private static String randomId() {
        StringBuilder id = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static String fingerprint(String publicKeyMaterial) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(publicKeyMaterial.getBytes(StandardCharsets.UTF_8));
            StringJoiner joiner = new StringJoiner(":");
            for (byte b : digest) {
                joiner.add(String.format("%02x", b));
            }
            return joiner.toString();
        } catch (Exception e) {
            throw new AwsException("InternalException", "Failed to compute key fingerprint", 500);
        }
    }
}
