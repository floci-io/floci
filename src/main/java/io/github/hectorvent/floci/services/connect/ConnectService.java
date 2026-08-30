package io.github.hectorvent.floci.services.connect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.connect.model.ConnectInstance;
import io.github.hectorvent.floci.services.connect.model.ConnectStorageConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon Connect instance control plane (signing name {@code connect}). Instances
 * are ACTIVE the moment {@code CreateInstance} returns, so {@code aws_connect_instance}'s
 * status poll completes on its first read.
 */
@ApplicationScoped
public class ConnectService implements TagHandler {

    public static final String ACTIVE = "ACTIVE";

    private static final Logger LOG = Logger.getLogger(ConnectService.class);

    /** {@code InstanceAttributeType} from the AWS model. */
    private static final List<String> ATTRIBUTE_TYPES = List.of(
            "INBOUND_CALLS",
            "OUTBOUND_CALLS",
            "CONTACTFLOW_LOGS",
            "CONTACT_LENS",
            "AUTO_RESOLVE_BEST_VOICES",
            "USE_CUSTOM_TTS_VOICES",
            "EARLY_MEDIA",
            "MULTI_PARTY_CONFERENCE",
            "HIGH_VOLUME_OUTBOUND",
            "ENHANCED_CONTACT_MONITORING",
            "ENHANCED_CHAT_MONITORING",
            "MULTI_PARTY_CHAT_CONFERENCE",
            "MESSAGE_STREAMING");

    /** {@code DirectoryType} from the AWS model. */
    private static final Set<String> IDENTITY_MANAGEMENT_TYPES =
            Set.of("SAML", "CONNECT_MANAGED", "EXISTING_DIRECTORY");

    /** {@code InstanceStorageResourceType} from the AWS model. */
    private static final Set<String> STORAGE_RESOURCE_TYPES = Set.of(
            "CHAT_TRANSCRIPTS",
            "CALL_RECORDINGS",
            "SCHEDULED_REPORTS",
            "MEDIA_STREAMS",
            "CONTACT_TRACE_RECORDS",
            "AGENT_EVENTS",
            "REAL_TIME_CONTACT_ANALYSIS_SEGMENTS",
            "ATTACHMENTS",
            "CONTACT_EVALUATIONS",
            "SCREEN_RECORDINGS",
            "REAL_TIME_CONTACT_ANALYSIS_CHAT_SEGMENTS",
            "REAL_TIME_CONTACT_ANALYSIS_VOICE_SEGMENTS",
            "EMAIL_MESSAGES");

    /** {@code StorageType} from the AWS model, mapped to the member carrying its configuration. */
    private static final Map<String, String> STORAGE_TYPE_MEMBERS = Map.of(
            "S3", "S3Config",
            "KINESIS_VIDEO_STREAM", "KinesisVideoStreamConfig",
            "KINESIS_STREAM", "KinesisStreamConfig",
            "KINESIS_FIREHOSE", "KinesisFirehoseConfig");

    private final StorageBackend<String, ConnectInstance> instances;
    private final StorageBackend<String, ConnectStorageConfig> storageConfigs;
    private final RegionResolver regionResolver;

    @Inject
    public ConnectService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.instances = storageFactory.create("connect", "connect-instances.json",
                new TypeReference<Map<String, ConnectInstance>>() {});
        this.storageConfigs = storageFactory.create("connect", "connect-storage-configs.json",
                new TypeReference<Map<String, ConnectStorageConfig>>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Instances ────────────────────────────

    public ConnectInstance createInstance(String identityManagementType, String instanceAlias,
                                          String directoryId, Boolean inboundCallsEnabled,
                                          Boolean outboundCallsEnabled, Map<String, String> tags,
                                          String region) {
        if (identityManagementType == null || identityManagementType.isBlank()) {
            throw new AwsException("InvalidRequestException", "IdentityManagementType is required", 400);
        }
        if (!IDENTITY_MANAGEMENT_TYPES.contains(identityManagementType)) {
            throw new AwsException("InvalidParameterException",
                    "IdentityManagementType " + identityManagementType + " is not supported.", 400);
        }
        if (inboundCallsEnabled == null) {
            throw new AwsException("InvalidRequestException", "InboundCallsEnabled is required", 400);
        }
        if (outboundCallsEnabled == null) {
            throw new AwsException("InvalidRequestException", "OutboundCallsEnabled is required", 400);
        }
        if ("EXISTING_DIRECTORY".equals(identityManagementType)
                && (directoryId == null || directoryId.isBlank())) {
            throw new AwsException("InvalidRequestException",
                    "DirectoryId is required when IdentityManagementType is EXISTING_DIRECTORY", 400);
        }
        if (instanceAlias != null) {
            for (ConnectInstance existing : listInstances(region)) {
                if (instanceAlias.equals(existing.getInstanceAlias())) {
                    throw new AwsException("ResourceConflictException",
                            "An instance with alias " + instanceAlias + " already exists.", 409);
                }
            }
        }

        String instanceId = UUID.randomUUID().toString();
        ConnectInstance instance = new ConnectInstance();
        instance.setId(instanceId);
        instance.setArn(regionResolver.buildArn("connect", region, "instance/" + instanceId));
        instance.setIdentityManagementType(identityManagementType);
        instance.setInstanceAlias(instanceAlias);
        instance.setDirectoryId(directoryId);
        instance.setCreatedTime(Instant.now());
        instance.setServiceRole("arn:aws:iam::" + regionResolver.getAccountId()
                + ":role/aws-service-role/connect.amazonaws.com/AWSServiceRoleForAmazonConnect_" + instanceId);
        instance.setInboundCallsEnabled(inboundCallsEnabled);
        instance.setOutboundCallsEnabled(outboundCallsEnabled);
        if (instanceAlias != null) {
            instance.setInstanceAccessUrl("https://" + instanceAlias + ".my.connect.aws/");
        }
        instance.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        instance.setAccountId(regionResolver.getAccountId());

        // Every attribute type is seeded so DescribeInstanceAttribute never 404s on a
        // valid enum value: aws_connect_instance reads them all on every refresh.
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String attributeType : ATTRIBUTE_TYPES) {
            attributes.put(attributeType, "false");
        }
        attributes.put("INBOUND_CALLS", String.valueOf(inboundCallsEnabled));
        attributes.put("OUTBOUND_CALLS", String.valueOf(outboundCallsEnabled));
        instance.setAttributes(attributes);

        instances.put(key(region, instanceId), instance);
        LOG.infov("Created Connect instance: {0}", instanceId);
        return instance;
    }

    public ConnectInstance describeInstance(String instanceId, String region) {
        return instances.get(key(region, resolveInstanceId(instanceId)))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Instance " + instanceId + " does not exist.", 404));
    }

    public void deleteInstance(String instanceId, String region) {
        ConnectInstance instance = describeInstance(instanceId, region);
        String prefix = key(region, instance.getId()) + "::";
        for (ConnectStorageConfig config : storageConfigs.scan(k -> k.startsWith(prefix))) {
            storageConfigs.delete(storageKey(region, instance.getId(), config.getResourceType(),
                    config.getAssociationId()));
        }
        instances.delete(key(region, instance.getId()));
        LOG.infov("Deleted Connect instance: {0}", instance.getId());
    }

    public List<ConnectInstance> listInstances(String region) {
        String prefix = region + "::";
        return instances.scan(k -> k.startsWith(prefix));
    }

    // ──────────────────────────── Attributes ────────────────────────────

    public ConnectInstance updateInstanceAttribute(String instanceId, String attributeType, String value,
                                                   String region) {
        validateAttributeType(attributeType);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidRequestException", "Value is required", 400);
        }
        // Instance attributes are boolean-valued; storing the canonical form keeps the
        // attributes map and the inbound/outbound flags from diverging on read.
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new AwsException("InvalidParameterException",
                    "Value must be 'true' or 'false' for attribute " + attributeType, 400);
        }
        String canonical = value.toLowerCase(java.util.Locale.ROOT);
        ConnectInstance instance = describeInstance(instanceId, region);
        instance.getAttributes().put(attributeType, canonical);
        if ("INBOUND_CALLS".equals(attributeType)) {
            instance.setInboundCallsEnabled(Boolean.parseBoolean(canonical));
        } else if ("OUTBOUND_CALLS".equals(attributeType)) {
            instance.setOutboundCallsEnabled(Boolean.parseBoolean(canonical));
        }
        instances.put(key(region, instance.getId()), instance);
        return instance;
    }

    public String describeInstanceAttribute(String instanceId, String attributeType, String region) {
        validateAttributeType(attributeType);
        ConnectInstance instance = describeInstance(instanceId, region);
        String value = instance.getAttributes().get(attributeType);
        if (value == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Attribute " + attributeType + " is not set on instance " + instanceId + ".", 404);
        }
        return value;
    }

    public Map<String, String> listInstanceAttributes(String instanceId, String region) {
        return describeInstance(instanceId, region).getAttributes();
    }

    // ──────────────────────── Instance storage configs ────────────────────────

    public ConnectStorageConfig associateInstanceStorageConfig(String instanceId, String resourceType,
                                                               JsonNode storageConfig, String region) {
        validateResourceType(resourceType);
        String storageType = validateStorageConfig(storageConfig);
        ConnectInstance instance = describeInstance(instanceId, region);

        String associationId = UUID.randomUUID().toString();
        ConnectStorageConfig config = new ConnectStorageConfig();
        config.setAssociationId(associationId);
        config.setInstanceId(instance.getId());
        config.setResourceType(resourceType);
        config.setStorageType(storageType);
        config.setStorageConfig(storageConfig);

        storageConfigs.put(storageKey(region, instance.getId(), resourceType, associationId), config);
        LOG.infov("Associated Connect storage config {0} ({1}) on instance {2}",
                associationId, resourceType, instance.getId());
        return config;
    }

    public ConnectStorageConfig describeInstanceStorageConfig(String instanceId, String associationId,
                                                              String resourceType, String region) {
        validateResourceType(resourceType);
        ConnectInstance instance = describeInstance(instanceId, region);
        return storageConfigs.get(storageKey(region, instance.getId(), resourceType, associationId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Storage config " + associationId + " does not exist on instance "
                                + instance.getId() + ".", 404));
    }

    public ConnectStorageConfig updateInstanceStorageConfig(String instanceId, String associationId,
                                                            String resourceType, JsonNode storageConfig,
                                                            String region) {
        String storageType = validateStorageConfig(storageConfig);
        ConnectStorageConfig config =
                describeInstanceStorageConfig(instanceId, associationId, resourceType, region);
        config.setStorageType(storageType);
        config.setStorageConfig(storageConfig);
        storageConfigs.put(storageKey(region, config.getInstanceId(), resourceType, associationId), config);
        return config;
    }

    public void disassociateInstanceStorageConfig(String instanceId, String associationId,
                                                  String resourceType, String region) {
        ConnectStorageConfig config =
                describeInstanceStorageConfig(instanceId, associationId, resourceType, region);
        storageConfigs.delete(storageKey(region, config.getInstanceId(), resourceType, associationId));
        LOG.infov("Disassociated Connect storage config {0} from instance {1}",
                associationId, config.getInstanceId());
    }

    public List<ConnectStorageConfig> listInstanceStorageConfigs(String instanceId, String resourceType,
                                                                 String region) {
        validateResourceType(resourceType);
        ConnectInstance instance = describeInstance(instanceId, region);
        String prefix = key(region, instance.getId()) + "::" + resourceType + "::";
        return storageConfigs.scan(k -> k.startsWith(prefix));
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Override
    public String serviceKey() {
        return "connect";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        ConnectInstance instance = findByArn(arn, region);
        return instance.getTags() != null ? instance.getTags() : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        ConnectInstance instance = findByArn(arn, region);
        if (instance.getTags() == null) {
            instance.setTags(new LinkedHashMap<>());
        }
        instance.getTags().putAll(tags);
        instances.put(key(region, instance.getId()), instance);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        ConnectInstance instance = findByArn(arn, region);
        if (instance.getTags() != null && tagKeys != null) {
            tagKeys.forEach(instance.getTags()::remove);
        }
        instances.put(key(region, instance.getId()), instance);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ConnectInstance findByArn(String arn, String region) {
        String prefix = region + "::";
        for (ConnectInstance instance : instances.scan(k -> k.startsWith(prefix))) {
            if (arn.equals(instance.getArn())) {
                return instance;
            }
        }
        throw new AwsException("ResourceNotFoundException", "Resource " + arn + " does not exist.", 404);
    }

    /** Both a bare instance id and a full instance ARN are accepted, as AWS does. */
    private String resolveInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new AwsException("InvalidRequestException", "InstanceId is required", 400);
        }
        if (!instanceId.startsWith("arn:")) {
            return instanceId;
        }
        String resource;
        try {
            resource = AwsArnUtils.parse(instanceId).resource();
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "Invalid instance ARN: " + instanceId, 400);
        }
        int slash = resource.indexOf('/');
        if (slash < 0 || slash == resource.length() - 1) {
            throw new AwsException("InvalidParameterException", "Invalid instance ARN: " + instanceId, 400);
        }
        return resource.substring(slash + 1);
    }

    private void validateAttributeType(String attributeType) {
        if (attributeType == null || !ATTRIBUTE_TYPES.contains(attributeType)) {
            throw new AwsException("InvalidParameterException",
                    "AttributeType " + attributeType + " is not supported.", 400);
        }
    }

    private void validateResourceType(String resourceType) {
        if (resourceType == null || !STORAGE_RESOURCE_TYPES.contains(resourceType)) {
            throw new AwsException("InvalidParameterException",
                    "ResourceType " + resourceType + " is not supported.", 400);
        }
    }

    private String validateStorageConfig(JsonNode storageConfig) {
        if (storageConfig == null || !storageConfig.isObject()) {
            throw new AwsException("InvalidRequestException", "StorageConfig is required", 400);
        }
        JsonNode storageTypeNode = storageConfig.get("StorageType");
        if (storageTypeNode == null || storageTypeNode.isNull()) {
            throw new AwsException("InvalidRequestException", "StorageConfig.StorageType is required", 400);
        }
        String storageType = storageTypeNode.asText();
        String member = STORAGE_TYPE_MEMBERS.get(storageType);
        if (member == null) {
            throw new AwsException("InvalidParameterException",
                    "StorageType " + storageType + " is not supported.", 400);
        }
        if (storageConfig.get(member) == null || storageConfig.get(member).isNull()) {
            throw new AwsException("InvalidRequestException",
                    "StorageConfig." + member + " is required when StorageType is " + storageType, 400);
        }
        return storageType;
    }

    private String key(String region, String instanceId) {
        return region + "::" + instanceId;
    }

    private String storageKey(String region, String instanceId, String resourceType, String associationId) {
        return key(region, instanceId) + "::" + resourceType + "::" + associationId;
    }
}
