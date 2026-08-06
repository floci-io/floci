package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iot.model.IotCertificate;
import io.github.hectorvent.floci.services.iot.model.IotJob;
import io.github.hectorvent.floci.services.iot.model.IotJobExecution;
import io.github.hectorvent.floci.services.iot.model.IotPolicy;
import io.github.hectorvent.floci.services.iot.model.IotRetainedMessage;
import io.github.hectorvent.floci.services.iot.model.IotShadow;
import io.github.hectorvent.floci.services.iot.model.IotThingGroup;
import io.github.hectorvent.floci.services.iot.model.IotThingType;
import io.github.hectorvent.floci.services.iot.model.IotTopicRule;
import io.github.hectorvent.floci.services.iot.model.Thing;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

@ApplicationScoped
public class IotService {

    static final String DEFAULT_ENDPOINT_TYPE = "iot:Data-ATS";

    private static final Pattern THING_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9:_-]{1,128}");

    private final StorageBackend<String, Thing> thingStore;
    private final StorageBackend<String, IotCertificate> certificateStore;
    private final StorageBackend<String, IotPolicy> policyStore;
    private final StorageBackend<String, Set<String>> policyAttachmentStore;
    private final StorageBackend<String, Set<String>> thingPrincipalStore;
    private final StorageBackend<String, IotShadow> shadowStore;
    private final StorageBackend<String, IotTopicRule> topicRuleStore;
    private final StorageBackend<String, IotRetainedMessage> retainedMessageStore;
    private final StorageBackend<String, IotJob> jobStore;
    private final StorageBackend<String, IotJobExecution> jobExecutionStore;
    private final StorageBackend<String, IotThingType> thingTypeStore;
    private final StorageBackend<String, IotThingGroup> thingGroupStore;
    private final StorageBackend<String, Set<String>> thingGroupMembershipStore;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final IotPublishEventRecorder publishEventRecorder;
    private final IotMqttBrokerService mqttBrokerService;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final S3Service s3Service;
    private final KinesisService kinesisService;
    private final DynamoDbService dynamoDbService;
    private final LambdaService lambdaService;

    @Inject
    public IotService(StorageFactory storageFactory,
                      EmulatorConfig config,
                      RegionResolver regionResolver,
                       ObjectMapper objectMapper,
                        IotPublishEventRecorder publishEventRecorder,
                        IotMqttBrokerService mqttBrokerService,
                        SqsService sqsService,
                        SnsService snsService,
                        S3Service s3Service,
                        KinesisService kinesisService,
                        DynamoDbService dynamoDbService,
                        LambdaService lambdaService) {
        this(storageFactory.create("iot", "iot-things.json", new TypeReference<Map<String, Thing>>() {}),
                storageFactory.create("iot", "iot-certificates.json", new TypeReference<Map<String, IotCertificate>>() {}),
                storageFactory.create("iot", "iot-policies.json", new TypeReference<Map<String, IotPolicy>>() {}),
                storageFactory.create("iot", "iot-policy-attachments.json", new TypeReference<Map<String, Set<String>>>() {}),
                storageFactory.create("iot", "iot-thing-principals.json", new TypeReference<Map<String, Set<String>>>() {}),
                storageFactory.create("iot", "iot-shadows.json", new TypeReference<Map<String, IotShadow>>() {}),
                storageFactory.create("iot", "iot-topic-rules.json", new TypeReference<Map<String, IotTopicRule>>() {}),
                storageFactory.create("iot", "iot-retained-messages.json", new TypeReference<Map<String, IotRetainedMessage>>() {}),
                storageFactory.create("iot", "iot-jobs.json", new TypeReference<Map<String, IotJob>>() {}),
                storageFactory.create("iot", "iot-job-executions.json", new TypeReference<Map<String, IotJobExecution>>() {}),
                storageFactory.create("iot", "iot-thing-types.json", new TypeReference<Map<String, IotThingType>>() {}),
                storageFactory.create("iot", "iot-thing-groups.json", new TypeReference<Map<String, IotThingGroup>>() {}),
                storageFactory.create("iot", "iot-thing-group-memberships.json", new TypeReference<Map<String, Set<String>>>() {}),
                config, regionResolver, objectMapper, publishEventRecorder, mqttBrokerService, sqsService, snsService,
                s3Service, kinesisService, dynamoDbService, lambdaService);
    }

    IotService(StorageBackend<String, Thing> thingStore,
               StorageBackend<String, IotCertificate> certificateStore,
               StorageBackend<String, IotPolicy> policyStore,
                StorageBackend<String, Set<String>> policyAttachmentStore,
                 StorageBackend<String, Set<String>> thingPrincipalStore,
                  StorageBackend<String, IotShadow> shadowStore,
                  StorageBackend<String, IotTopicRule> topicRuleStore,
                  StorageBackend<String, IotRetainedMessage> retainedMessageStore,
                  StorageBackend<String, IotJob> jobStore,
                  StorageBackend<String, IotJobExecution> jobExecutionStore,
                  StorageBackend<String, IotThingType> thingTypeStore,
                  StorageBackend<String, IotThingGroup> thingGroupStore,
                  StorageBackend<String, Set<String>> thingGroupMembershipStore,
                  EmulatorConfig config,
                 RegionResolver regionResolver,
                 ObjectMapper objectMapper,
                  IotPublishEventRecorder publishEventRecorder,
                  IotMqttBrokerService mqttBrokerService,
                  SqsService sqsService,
                  SnsService snsService,
                  S3Service s3Service,
                  KinesisService kinesisService,
                  DynamoDbService dynamoDbService,
                  LambdaService lambdaService) {
        this.thingStore = thingStore;
        this.certificateStore = certificateStore;
        this.policyStore = policyStore;
        this.policyAttachmentStore = policyAttachmentStore;
        this.thingPrincipalStore = thingPrincipalStore;
        this.shadowStore = shadowStore;
        this.topicRuleStore = topicRuleStore;
        this.retainedMessageStore = retainedMessageStore;
        this.jobStore = jobStore;
        this.jobExecutionStore = jobExecutionStore;
        this.thingTypeStore = thingTypeStore;
        this.thingGroupStore = thingGroupStore;
        this.thingGroupMembershipStore = thingGroupMembershipStore;
        this.config = config;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.publishEventRecorder = publishEventRecorder;
        this.mqttBrokerService = mqttBrokerService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.s3Service = s3Service;
        this.kinesisService = kinesisService;
        this.dynamoDbService = dynamoDbService;
        this.lambdaService = lambdaService;
    }

    public String describeEndpoint(String endpointType) {
        String effectiveType = endpointType == null || endpointType.isBlank() ? DEFAULT_ENDPOINT_TYPE : endpointType;
        if (!Set.of(DEFAULT_ENDPOINT_TYPE, "iot:Data", "iot:Jobs").contains(effectiveType)) {
            throw new AwsException("InvalidRequestException", "Unsupported endpoint type: " + effectiveType, 400);
        }
        startMqttIfEnabled();
        URI baseUri = URI.create(config.effectiveBaseUrl());
        return baseUri.getAuthority();
    }

    public Thing createThing(String thingName, Map<String, String> attributes, String region) {
        return createThing(thingName, attributes, null, region);
    }

    public Thing createThing(String thingName, Map<String, String> attributes, String thingTypeName, String region) {
        startMqttIfEnabled();
        validateThingName(thingName);
        if (thingTypeName != null && !thingTypeName.isBlank()) {
            describeThingType(thingTypeName, region);
        }
        String key = thingKey(region, thingName);
        Thing existing = thingStore.get(key).orElse(null);
        if (existing != null) {
            if (existing.getAttributes().equals(attributes)
                    && java.util.Objects.equals(existing.getThingTypeName(), blankToNull(thingTypeName))) {
                return existing;
            }
            throw new AwsException("ResourceAlreadyExistsException", "Thing already exists: " + thingName, 409);
        }

        Instant now = Instant.now();
        Thing thing = new Thing();
        thing.setThingName(thingName);
        thing.setThingArn(regionResolver.buildArn("iot", region, "thing/" + thingName));
        thing.setThingId(UUID.randomUUID().toString());
        thing.setAttributes(attributes);
        thing.setThingTypeName(blankToNull(thingTypeName));
        thing.setVersion(1L);
        thing.setCreationDate(now);
        thing.setLastModifiedDate(now);
        thingStore.put(key, thing);
        return thing;
    }

    public Thing describeThing(String thingName, String region) {
        validateThingName(thingName);
        return thingStore.get(thingKey(region, thingName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Thing not found: " + thingName, 404));
    }

    public List<Thing> listThings(String region) {
        String prefix = "thing:" + region + ":";
        return thingStore.scan(key -> key.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(Thing::getThingName))
                .toList();
    }

    public Page<Thing> listThings(String region, Integer maxResults, String nextToken) {
        return paginate(listThings(region), maxResults, nextToken);
    }

    public Thing updateThing(String thingName, Map<String, String> attributes, Long expectedVersion, String region) {
        Thing thing = describeThing(thingName, region);
        if (expectedVersion != null && thing.getVersion() != expectedVersion) {
            throw new AwsException("VersionConflictException", "Thing version does not match expectedVersion", 409);
        }
        thing.setAttributes(attributes);
        thing.setVersion(thing.getVersion() + 1);
        thing.setLastModifiedDate(Instant.now());
        thingStore.put(thingKey(region, thingName), thing);
        return thing;
    }

    public void deleteThing(String thingName, String region) {
        describeThing(thingName, region);
        thingStore.delete(thingKey(region, thingName));
        for (String key : thingGroupMembershipStore.keys()) {
            if (key.startsWith("thing-group-membership:" + region + ":")) {
                Set<String> members = new HashSet<>(thingGroupMembershipStore.get(key).orElse(Set.of()));
                if (members.remove(thingName)) {
                    thingGroupMembershipStore.put(key, members);
                }
            }
        }
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        return new TreeMap<>(taggableForArn(resourceArn).tags());
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        TaggableResource resource = taggableForArn(resourceArn);
        Map<String, String> updatedTags = new TreeMap<>(resource.tags());
        updatedTags.putAll(tags);
        resource.updateTags().accept(updatedTags);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        TaggableResource resource = taggableForArn(resourceArn);
        Map<String, String> updatedTags = new TreeMap<>(resource.tags());
        tagKeys.forEach(updatedTags::remove);
        resource.updateTags().accept(updatedTags);
    }

    public IotCertificate createKeysAndCertificate(boolean setAsActive, String region) {
        startMqttIfEnabled();
        return createCertificate(setAsActive, null, region);
    }

    public IotCertificate createCertificateFromCsr(String certificateSigningRequest, boolean setAsActive, String region) {
        if (certificateSigningRequest == null || certificateSigningRequest.isBlank()) {
            throw new AwsException("InvalidRequestException", "certificateSigningRequest is required", 400);
        }
        return createCertificate(setAsActive, certificateSigningRequest, region);
    }

    private IotCertificate createCertificate(boolean setAsActive, String certificateSigningRequest, String region) {
        startMqttIfEnabled();
        String id = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        IotCertificate certificate = new IotCertificate();
        certificate.setCertificateId(id);
        certificate.setCertificateArn(regionResolver.buildArn("iot", region, "cert/" + id));
        certificate.setCertificatePem("-----BEGIN CERTIFICATE-----\n" + id + "\n-----END CERTIFICATE-----");
        certificate.setPublicKey("-----BEGIN PUBLIC KEY-----\n" + (certificateSigningRequest == null ? id : certificateSigningRequest.hashCode()) + "\n-----END PUBLIC KEY-----");
        certificate.setPrivateKey("-----BEGIN PRIVATE KEY-----\n" + id + "\n-----END PRIVATE KEY-----");
        certificate.setStatus(setAsActive ? "ACTIVE" : "INACTIVE");
        certificate.setCreationDate(Instant.now());
        certificateStore.put(certificateKey(region, id), certificate);
        return certificate;
    }

    public IotCertificate describeCertificate(String certificateId, String region) {
        return certificateStore.get(certificateKey(region, certificateId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Certificate not found: " + certificateId, 404));
    }

    public List<IotCertificate> listCertificates(String region) {
        String prefix = "cert:" + region + ":";
        return certificateStore.scan(key -> key.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(IotCertificate::getCertificateId))
                .toList();
    }

    public void updateCertificate(String certificateId, String status, String region) {
        IotCertificate certificate = describeCertificate(certificateId, region);
        validateCertificateStatus(status);
        certificate.setStatus(status);
        certificateStore.put(certificateKey(region, certificateId), certificate);
    }

    public void deleteCertificate(String certificateId, String region) {
        IotCertificate certificate = describeCertificate(certificateId, region);
        if ("ACTIVE".equals(certificate.getStatus())) {
            throw new AwsException("InvalidRequestException", "Cannot delete ACTIVE certificate", 400);
        }
        if (certificateHasAttachments(certificate.getCertificateArn(), region)) {
            throw new AwsException("InvalidRequestException", "Cannot delete attached certificate", 400);
        }
        certificateStore.delete(certificateKey(region, certificateId));
    }

    public IotPolicy createPolicy(String policyName, String policyDocument, String region) {
        startMqttIfEnabled();
        if (policyStore.get(policyKey(region, policyName)).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "Policy already exists: " + policyName, 409);
        }
        IotPolicy policy = new IotPolicy();
        policy.setPolicyName(policyName);
        policy.setPolicyArn(regionResolver.buildArn("iot", region, "policy/" + policyName));
        policy.setPolicyDocument(policyDocument);
        policy.setDefaultVersionId("1");
        policy.setCreationDate(Instant.now());
        IotPolicy.PolicyVersion version = new IotPolicy.PolicyVersion();
        version.setVersionId("1");
        version.setDocument(policyDocument);
        version.setCreateDate(policy.getCreationDate());
        policy.setVersions(List.of(version));
        policyStore.put(policyKey(region, policyName), policy);
        return policy;
    }

    public IotPolicy getPolicy(String policyName, String region) {
        return policyStore.get(policyKey(region, policyName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Policy not found: " + policyName, 404));
    }

    public List<IotPolicy> listPolicies(String region) {
        String prefix = "policy:" + region + ":";
        return policyStore.scan(key -> key.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(IotPolicy::getPolicyName))
                .toList();
    }

    public void deletePolicy(String policyName, String region) {
        getPolicy(policyName, region);
        if (!policyAttachmentStore.get(policyAttachmentKey(region, policyName)).orElse(Set.of()).isEmpty()) {
            throw new AwsException("InvalidRequestException", "Cannot delete attached policy", 400);
        }
        policyStore.delete(policyKey(region, policyName));
        policyAttachmentStore.delete(policyAttachmentKey(region, policyName));
    }

    public IotPolicy.PolicyVersion createPolicyVersion(String policyName, String policyDocument, boolean setAsDefault, String region) {
        IotPolicy policy = getPolicy(policyName, region);
        int next = policy.getVersions().stream()
                .map(IotPolicy.PolicyVersion::getVersionId)
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0) + 1;
        IotPolicy.PolicyVersion version = new IotPolicy.PolicyVersion();
        version.setVersionId(Integer.toString(next));
        version.setDocument(policyDocument);
        version.setCreateDate(Instant.now());
        List<IotPolicy.PolicyVersion> versions = new java.util.ArrayList<>(policy.getVersions());
        versions.add(version);
        policy.setVersions(versions);
        if (setAsDefault) {
            policy.setDefaultVersionId(version.getVersionId());
            policy.setPolicyDocument(policyDocument);
        }
        policyStore.put(policyKey(region, policyName), policy);
        return version;
    }

    public IotPolicy.PolicyVersion getPolicyVersion(String policyName, String versionId, String region) {
        IotPolicy policy = getPolicy(policyName, region);
        return policy.getVersions().stream()
                .filter(version -> versionId.equals(version.getVersionId()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Policy version not found: " + versionId, 404));
    }

    public List<IotPolicy.PolicyVersion> listPolicyVersions(String policyName, String region) {
        return getPolicy(policyName, region).getVersions().stream()
                .sorted(Comparator.comparing(IotPolicy.PolicyVersion::getVersionId))
                .toList();
    }

    public void setDefaultPolicyVersion(String policyName, String versionId, String region) {
        IotPolicy policy = getPolicy(policyName, region);
        IotPolicy.PolicyVersion version = getPolicyVersion(policyName, versionId, region);
        policy.setDefaultVersionId(versionId);
        policy.setPolicyDocument(version.getDocument());
        policyStore.put(policyKey(region, policyName), policy);
    }

    public void deletePolicyVersion(String policyName, String versionId, String region) {
        IotPolicy policy = getPolicy(policyName, region);
        if (versionId.equals(policy.getDefaultVersionId())) {
            throw new AwsException("InvalidRequestException", "Cannot delete default policy version", 400);
        }
        List<IotPolicy.PolicyVersion> versions = policy.getVersions().stream()
                .filter(version -> !versionId.equals(version.getVersionId()))
                .toList();
        if (versions.size() == policy.getVersions().size()) {
            throw new AwsException("ResourceNotFoundException", "Policy version not found: " + versionId, 404);
        }
        policy.setVersions(versions);
        policyStore.put(policyKey(region, policyName), policy);
    }

    public void attachPolicy(String policyName, String target, String region) {
        getPolicy(policyName, region);
        Set<String> targets = new HashSet<>(policyAttachmentStore.get(policyAttachmentKey(region, policyName)).orElse(Set.of()));
        targets.add(target);
        policyAttachmentStore.put(policyAttachmentKey(region, policyName), targets);
    }

    public void detachPolicy(String policyName, String target, String region) {
        Set<String> targets = new HashSet<>(policyAttachmentStore.get(policyAttachmentKey(region, policyName)).orElse(Set.of()));
        targets.remove(target);
        policyAttachmentStore.put(policyAttachmentKey(region, policyName), targets);
    }

    public void attachThingPrincipal(String thingName, String principal, String region) {
        describeThing(thingName, region);
        if (principal == null || principal.isBlank()) {
            throw new AwsException("InvalidRequestException", "Principal is required", 400);
        }
        Set<String> principals = new HashSet<>(thingPrincipalStore.get(thingPrincipalKey(region, thingName)).orElse(Set.of()));
        principals.add(principal);
        thingPrincipalStore.put(thingPrincipalKey(region, thingName), principals);
    }

    public void detachThingPrincipal(String thingName, String principal, String region) {
        Set<String> principals = new HashSet<>(thingPrincipalStore.get(thingPrincipalKey(region, thingName)).orElse(Set.of()));
        principals.remove(principal);
        thingPrincipalStore.put(thingPrincipalKey(region, thingName), principals);
    }

    public Set<String> listThingPrincipals(String thingName, String region) {
        describeThing(thingName, region);
        return new java.util.TreeSet<>(thingPrincipalStore.get(thingPrincipalKey(region, thingName)).orElse(Set.of()));
    }

    public Set<String> listPrincipalThings(String principal, String region) {
        String prefix = "thing-principal:" + region + ":";
        Set<String> things = new java.util.TreeSet<>();
        for (String key : thingPrincipalStore.keys()) {
            if (key.startsWith(prefix) && thingPrincipalStore.get(key).orElse(Set.of()).contains(principal)) {
                things.add(key.substring(prefix.length()));
            }
        }
        return things;
    }

    public List<IotPolicy> listAttachedPolicies(String target, String region) {
        String prefix = "policy-attachment:" + region + ":";
        return policyAttachmentStore.keys().stream()
                .filter(key -> key.startsWith(prefix) && policyAttachmentStore.get(key).orElse(Set.of()).contains(target))
                .map(key -> key.substring(prefix.length()))
                .map(policyName -> policyStore.get(policyKey(region, policyName)))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(IotPolicy::getPolicyName))
                .toList();
    }

    public Set<String> listTargetsForPolicy(String policyName, String region) {
        getPolicy(policyName, region);
        return new java.util.TreeSet<>(policyAttachmentStore.get(policyAttachmentKey(region, policyName)).orElse(Set.of()));
    }

    public JsonNode getThingShadow(String thingName, String shadowName, String region) {
        IotShadow shadow = shadowStore.get(shadowKey(region, thingName, shadowName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Shadow not found: " + thingName, 404));
        return readJson(shadow.getDocument());
    }

    public JsonNode updateThingShadow(String thingName, String shadowName, JsonNode request, String region) {
        String key = shadowKey(region, thingName, shadowName);
        IotShadow existing = shadowStore.get(key).orElse(null);
        ObjectNode document = existing == null ? objectMapper.createObjectNode() : (ObjectNode) readJson(existing.getDocument()).deepCopy();
        long currentVersion = existing == null ? 0 : existing.getVersion();
        if (request.hasNonNull("version") && request.path("version").asLong() != currentVersion) {
            throw new AwsException("VersionConflictException", "Shadow version does not match requested version", 409);
        }
        ObjectNode state = document.withObject("/state");
        JsonNode requestState = request.path("state");
        mergeObject(state, "desired", requestState.path("desired"));
        mergeObject(state, "reported", requestState.path("reported"));
        long version = currentVersion + 1;
        document.put("version", version);
        document.put("timestamp", Instant.now().getEpochSecond());

        IotShadow shadow = new IotShadow();
        shadow.setThingName(thingName);
        shadow.setShadowName(shadowName);
        shadow.setVersion(version);
        shadow.setDocument(document.toString());
        shadowStore.put(key, shadow);
        return document;
    }

    public JsonNode deleteThingShadow(String thingName, String shadowName, String region) {
        JsonNode document = getThingShadow(thingName, shadowName, region);
        shadowStore.delete(shadowKey(region, thingName, shadowName));
        return document;
    }

    public List<String> listNamedShadowsForThing(String thingName, String region) {
        String prefix = "shadow:" + region + ":" + thingName + ":";
        return shadowStore.scan(key -> key.startsWith(prefix)).stream()
                .map(IotShadow::getShadowName)
                .filter(name -> name != null && !name.isBlank())
                .sorted()
                .toList();
    }

    public void publish(String topic, byte[] payload) {
        publish(topic, payload, false, 0);
    }

    public void publish(String topic, byte[] payload, boolean retain, int qos) {
        byte[] eventPayload = payload == null ? new byte[0] : payload;
        if (retain) {
            if (eventPayload.length == 0) {
                retainedMessageStore.delete(retainedMessageKey(topic));
            } else {
                IotRetainedMessage retained = new IotRetainedMessage();
                retained.setTopic(topic);
                retained.setPayload(Base64.getEncoder().encodeToString(eventPayload));
                retained.setQos(qos);
                retained.setLastModifiedTime(Instant.now());
                retainedMessageStore.put(retainedMessageKey(topic), retained);
            }
        }
        handlePublish(topic, eventPayload, true);
    }

    public void deleteConnection(String clientId, boolean cleanSession) {
        validateMqttClientId(clientId);
        boolean disconnected = mqttBrokerService.disconnectClient(clientId, cleanSession);
        if (!disconnected) {
            throw new AwsException("ResourceNotFoundException", "MQTT client is not connected: " + clientId, 404);
        }
    }

    public IotMqttBrokerService.ConnectionInfo getConnection(String clientId) {
        validateMqttClientId(clientId);
        return mqttBrokerService.getConnection(clientId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "MQTT client is not connected: " + clientId, 404));
    }

    public Page<String> listSubscriptions(String clientId, Integer maxResults, String nextToken) {
        getConnection(clientId);
        return paginate(mqttBrokerService.listSubscriptions(clientId), maxResults, nextToken);
    }

    public void sendDirectMessage(String clientId, String topic, byte[] payload) {
        getConnection(clientId);
        if (topic == null || topic.isBlank()) {
            throw new AwsException("InvalidRequestException", "topic is required", 400);
        }
        mqttBrokerService.publish(topic, payload == null ? new byte[0] : payload);
    }

    public IotRetainedMessage getRetainedMessage(String topic) {
        return retainedMessageStore.get(retainedMessageKey(topic))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Retained message not found: " + topic, 404));
    }

    public Page<IotRetainedMessage> listRetainedMessages(Integer maxResults, String nextToken) {
        List<IotRetainedMessage> messages = retainedMessageStore.scan(key -> key.startsWith("retained:")).stream()
                .sorted(Comparator.comparing(IotRetainedMessage::getTopic))
                .toList();
        return paginate(messages, maxResults, nextToken);
    }

    public IotThingType createThingType(String thingTypeName, JsonNode properties, String region) {
        String key = thingTypeKey(region, thingTypeName);
        IotThingType existing = thingTypeStore.get(key).orElse(null);
        if (existing != null) {
            if (java.util.Objects.equals(existing.getDescription(), properties.path("thingTypeDescription").asText(null))
                    && existing.getSearchableAttributes().equals(stringList(properties.path("searchableAttributes")))) {
                return existing;
            }
            throw new AwsException("ResourceAlreadyExistsException", "Thing type already exists: " + thingTypeName, 409);
        }
        IotThingType type = new IotThingType();
        type.setThingTypeName(thingTypeName);
        type.setThingTypeArn(regionResolver.buildArn("iot", region, "thingtype/" + thingTypeName));
        type.setThingTypeId(UUID.randomUUID().toString());
        updateThingTypeProperties(type, properties);
        type.setCreationDate(Instant.now());
        thingTypeStore.put(key, type);
        return type;
    }

    public IotThingType describeThingType(String thingTypeName, String region) {
        return thingTypeStore.get(thingTypeKey(region, thingTypeName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Thing type not found: " + thingTypeName, 404));
    }

    public Page<IotThingType> listThingTypes(String region, Integer maxResults, String nextToken) {
        return paginate(thingTypeStore.scan(key -> key.startsWith("thing-type:" + region + ":")).stream()
                .sorted(Comparator.comparing(IotThingType::getThingTypeName))
                .toList(), maxResults, nextToken);
    }

    public IotThingType updateThingType(String thingTypeName, JsonNode properties, String region) {
        IotThingType type = describeThingType(thingTypeName, region);
        updateThingTypeProperties(type, properties);
        thingTypeStore.put(thingTypeKey(region, thingTypeName), type);
        return type;
    }

    public void deprecateThingType(String thingTypeName, String region) {
        IotThingType type = describeThingType(thingTypeName, region);
        type.setDeprecated(true);
        type.setDeprecatedDate(Instant.now());
        thingTypeStore.put(thingTypeKey(region, thingTypeName), type);
    }

    public void deleteThingType(String thingTypeName, String region) {
        describeThingType(thingTypeName, region);
        boolean inUse = listThings(region).stream().anyMatch(thing -> thingTypeName.equals(thing.getThingTypeName()));
        if (inUse) {
            throw new AwsException("InvalidRequestException", "Cannot delete thing type with associated things", 400);
        }
        thingTypeStore.delete(thingTypeKey(region, thingTypeName));
    }

    public IotThingGroup createThingGroup(String thingGroupName, JsonNode properties, String region) {
        String key = thingGroupKey(region, thingGroupName);
        IotThingGroup existing = thingGroupStore.get(key).orElse(null);
        if (existing != null) {
            if (java.util.Objects.equals(existing.getDescription(), properties.path("thingGroupDescription").asText(null))
                    && existing.getAttributes().equals(parseAttributePayload(properties.path("attributePayload")))) {
                return existing;
            }
            throw new AwsException("ResourceAlreadyExistsException", "Thing group already exists: " + thingGroupName, 409);
        }
        IotThingGroup group = new IotThingGroup();
        group.setThingGroupName(thingGroupName);
        group.setThingGroupArn(regionResolver.buildArn("iot", region, "thinggroup/" + thingGroupName));
        group.setThingGroupId(UUID.randomUUID().toString());
        group.setCreationDate(Instant.now());
        updateThingGroupProperties(group, properties);
        thingGroupStore.put(key, group);
        return group;
    }

    public IotThingGroup describeThingGroup(String thingGroupName, String region) {
        return thingGroupStore.get(thingGroupKey(region, thingGroupName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Thing group not found: " + thingGroupName, 404));
    }

    public Page<IotThingGroup> listThingGroups(String region, Integer maxResults, String nextToken) {
        return paginate(thingGroupStore.scan(key -> key.startsWith("thing-group:" + region + ":")).stream()
                .sorted(Comparator.comparing(IotThingGroup::getThingGroupName))
                .toList(), maxResults, nextToken);
    }

    public IotThingGroup updateThingGroup(String thingGroupName, JsonNode properties, Long expectedVersion, String region) {
        IotThingGroup group = describeThingGroup(thingGroupName, region);
        if (expectedVersion != null && group.getVersion() != expectedVersion) {
            throw new AwsException("VersionConflictException", "Thing group version does not match expectedVersion", 409);
        }
        updateThingGroupProperties(group, properties);
        group.setVersion(group.getVersion() + 1);
        thingGroupStore.put(thingGroupKey(region, thingGroupName), group);
        return group;
    }

    public void deleteThingGroup(String thingGroupName, String region) {
        describeThingGroup(thingGroupName, region);
        thingGroupStore.delete(thingGroupKey(region, thingGroupName));
        thingGroupMembershipStore.delete(thingGroupMembershipKey(region, thingGroupName));
    }

    public void addThingToThingGroup(String thingGroupName, String thingName, String region) {
        describeThingGroup(thingGroupName, region);
        describeThing(thingName, region);
        Set<String> members = new HashSet<>(thingGroupMembershipStore.get(thingGroupMembershipKey(region, thingGroupName)).orElse(Set.of()));
        members.add(thingName);
        thingGroupMembershipStore.put(thingGroupMembershipKey(region, thingGroupName), members);
    }

    public void removeThingFromThingGroup(String thingGroupName, String thingName, String region) {
        describeThingGroup(thingGroupName, region);
        Set<String> members = new HashSet<>(thingGroupMembershipStore.get(thingGroupMembershipKey(region, thingGroupName)).orElse(Set.of()));
        members.remove(thingName);
        thingGroupMembershipStore.put(thingGroupMembershipKey(region, thingGroupName), members);
    }

    public Set<String> listThingsInThingGroup(String thingGroupName, String region) {
        describeThingGroup(thingGroupName, region);
        return new java.util.TreeSet<>(thingGroupMembershipStore.get(thingGroupMembershipKey(region, thingGroupName)).orElse(Set.of()));
    }

    public List<IotThingGroup> listThingGroupsForThing(String thingName, String region) {
        describeThing(thingName, region);
        String prefix = "thing-group-membership:" + region + ":";
        return thingGroupMembershipStore.keys().stream()
                .filter(key -> key.startsWith(prefix) && thingGroupMembershipStore.get(key).orElse(Set.of()).contains(thingName))
                .map(key -> key.substring(prefix.length()))
                .map(groupName -> thingGroupStore.get(thingGroupKey(region, groupName)))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(IotThingGroup::getThingGroupName))
                .toList();
    }

    public IotJob createJob(String jobId, JsonNode request, String region) {
        startMqttIfEnabled();
        if (jobStore.get(jobKey(region, jobId)).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "Job already exists: " + jobId, 409);
        }
        JsonNode targetsNode = request.path("targets");
        if (!targetsNode.isArray() || targetsNode.isEmpty()) {
            throw new AwsException("InvalidRequestException", "targets is required", 400);
        }

        Instant now = Instant.now();
        IotJob job = new IotJob();
        job.setJobId(jobId);
        job.setJobArn(regionResolver.buildArn("iot", region, "job/" + jobId));
        job.setDocument(request.path("document").asText(null));
        job.setDocumentSource(request.path("documentSource").asText(null));
        job.setDescription(request.path("description").asText(null));
        job.setTargetSelection(request.path("targetSelection").asText("SNAPSHOT"));
        job.setCreatedAt(now);
        job.setLastUpdatedAt(now);

        List<String> targets = new java.util.ArrayList<>();
        Set<String> thingNames = new java.util.TreeSet<>();
        targetsNode.forEach(target -> {
            String value = target.asText();
            if (value != null && !value.isBlank()) {
                targets.add(value);
                thingNames.addAll(thingNamesForJobTarget(value, region));
            }
        });
        if (thingNames.isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "No job targets found", 404);
        }
        job.setTargets(targets);
        jobStore.put(jobKey(region, jobId), job);

        for (String thingName : thingNames) {
            Thing thing = describeThing(thingName, region);
            IotJobExecution execution = new IotJobExecution();
            execution.setJobId(jobId);
            execution.setThingName(thingName);
            execution.setThingArn(thing.getThingArn());
            execution.setStatus("QUEUED");
            execution.setQueuedAt(now);
            execution.setLastUpdatedAt(now);
            jobExecutionStore.put(jobExecutionKey(region, thingName, jobId), execution);
        }
        return job;
    }

    public IotJob describeJob(String jobId, String region) {
        return jobStore.get(jobKey(region, jobId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Job not found: " + jobId, 404));
    }

    public Page<IotJob> listJobs(String region, Integer maxResults, String nextToken) {
        List<IotJob> jobs = jobStore.scan(key -> key.startsWith("job:" + region + ":")).stream()
                .sorted(Comparator.comparing(IotJob::getJobId))
                .toList();
        return paginate(jobs, maxResults, nextToken);
    }

    public Page<IotJobExecution> listJobExecutionsForThing(String thingName, String region, Integer maxResults, String nextToken) {
        describeThing(thingName, region);
        List<IotJobExecution> executions = jobExecutionStore.scan(key -> key.startsWith("job-execution:" + region + ":" + thingName + ":")).stream()
                .sorted(Comparator.comparing(IotJobExecution::getJobId))
                .toList();
        return paginate(executions, maxResults, nextToken);
    }

    public IotJobExecution describeJobExecution(String thingName, String jobId, String region) {
        describeThing(thingName, region);
        describeJob(jobId, region);
        return jobExecutionStore.get(jobExecutionKey(region, thingName, jobId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Job execution not found: " + jobId, 404));
    }

    public IotJobExecution startNextPendingJobExecution(String thingName, Map<String, String> statusDetails, String region) {
        IotJobExecution execution = listJobExecutionsForThing(thingName, region, null, null).items().stream()
                .filter(item -> "QUEUED".equals(item.getStatus()) || "IN_PROGRESS".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "No pending job executions for thing: " + thingName, 404));
        if ("QUEUED".equals(execution.getStatus())) {
            execution.setStatus("IN_PROGRESS");
            execution.setStartedAt(Instant.now());
            execution.setVersionNumber(execution.getVersionNumber() + 1);
        }
        if (statusDetails != null && !statusDetails.isEmpty()) {
            execution.setStatusDetails(statusDetails);
        }
        execution.setLastUpdatedAt(Instant.now());
        jobExecutionStore.put(jobExecutionKey(region, thingName, execution.getJobId()), execution);
        return execution;
    }

    public IotJobExecution updateJobExecution(String thingName, String jobId, String status, Map<String, String> statusDetails,
                                             Long expectedVersion, String region) {
        IotJobExecution execution = describeJobExecution(thingName, jobId, region);
        validateJobExecutionStatus(status);
        if (expectedVersion != null && execution.getVersionNumber() != expectedVersion) {
            throw new AwsException("VersionConflictException", "Job execution version does not match expectedVersion", 409);
        }
        if (Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "REJECTED", "REMOVED", "CANCELED").contains(execution.getStatus())) {
            throw new AwsException("InvalidStateTransitionException", "Job execution is already terminal", 409);
        }
        execution.setStatus(status);
        if ("IN_PROGRESS".equals(status) && execution.getStartedAt() == null) {
            execution.setStartedAt(Instant.now());
        }
        if (statusDetails != null) {
            execution.setStatusDetails(statusDetails);
        }
        execution.setVersionNumber(execution.getVersionNumber() + 1);
        execution.setLastUpdatedAt(Instant.now());
        jobExecutionStore.put(jobExecutionKey(region, thingName, jobId), execution);
        return execution;
    }

    public IotTopicRule createTopicRule(String ruleName, JsonNode payload, String region) {
        if (topicRuleStore.get(topicRuleKey(region, ruleName)).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "Topic rule already exists: " + ruleName, 409);
        }
        return buildAndStoreTopicRule(ruleName, payload, region, Instant.now());
    }

    public IotTopicRule replaceTopicRule(String ruleName, JsonNode payload, String region) {
        IotTopicRule existing = getTopicRule(ruleName, region);
        IotTopicRule replacement = buildAndStoreTopicRule(ruleName, payload, region, existing.getCreatedAt());
        replacement.setTags(existing.getTags());
        topicRuleStore.put(topicRuleKey(region, ruleName), replacement);
        return replacement;
    }

    private IotTopicRule buildAndStoreTopicRule(String ruleName, JsonNode payload, String region, Instant createdAt) {
        IotTopicRule rule = new IotTopicRule();
        rule.setRuleName(ruleName);
        rule.setRuleArn(regionResolver.buildArn("iot", region, "rule/" + ruleName));
        rule.setSql(payload.path("sql").asText());
        rule.setDescription(payload.path("description").asText(null));
        rule.setRuleDisabled(payload.path("ruleDisabled").asBoolean(false));
        JsonNode actions = payload.path("actions");
        rule.setActionsJson(actions.isArray() ? actions.toString() : "[]");
        rule.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        topicRuleStore.put(topicRuleKey(region, ruleName), rule);
        return rule;
    }

    public IotTopicRule getTopicRule(String ruleName, String region) {
        return topicRuleStore.get(topicRuleKey(region, ruleName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Topic rule not found: " + ruleName, 404));
    }

    public List<IotTopicRule> listTopicRules(String region) {
        String prefix = "topic-rule:" + region + ":";
        return topicRuleStore.scan(key -> key.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(IotTopicRule::getRuleName))
                .toList();
    }

    public void deleteTopicRule(String ruleName, String region) {
        getTopicRule(ruleName, region);
        topicRuleStore.delete(topicRuleKey(region, ruleName));
    }

    public void setTopicRuleEnabled(String ruleName, boolean enabled, String region) {
        IotTopicRule rule = getTopicRule(ruleName, region);
        rule.setRuleDisabled(!enabled);
        topicRuleStore.put(topicRuleKey(region, ruleName), rule);
    }

    void handlePublish(String topic, byte[] payload, boolean evaluateRules) {
        byte[] eventPayload = payload == null ? new byte[0] : payload;
        publishEventRecorder.record(topic, eventPayload);
        if (!evaluateRules) {
            return;
        }
        for (IotTopicRule rule : listTopicRules(config.defaultRegion())) {
            if (!rule.isRuleDisabled() && topicMatches(extractTopicPattern(rule.getSql()), topic)) {
                executeTopicRule(rule, eventPayload);
            }
        }
    }

    void handleReservedMqttPublish(String topic, byte[] payload, BiConsumer<String, byte[]> publisher) {
        ReservedShadowTopic shadowTopic = parseReservedShadowTopic(topic);
        if (shadowTopic == null) {
            return;
        }

        try {
            JsonNode request = readJson(payload == null || payload.length == 0 ? "{}" : new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            String clientToken = request.path("clientToken").asText(null);
            String region = config.defaultRegion();
            switch (shadowTopic.operation()) {
                case "update" -> publishShadowUpdate(shadowTopic, request, clientToken, region, publisher);
                case "get" -> publishShadowGet(shadowTopic, clientToken, region, publisher);
                case "delete" -> publishShadowDelete(shadowTopic, clientToken, region, publisher);
                default -> publishRejected(shadowTopic.rejectedTopic(), "InvalidRequestException",
                        "Unsupported shadow operation: " + shadowTopic.operation(), clientToken, publisher);
            }
        } catch (AwsException e) {
            publishRejected(shadowTopic.rejectedTopic(), e.getErrorCode(), e.getMessage(), null, publisher);
        } catch (Exception e) {
            publishRejected(shadowTopic.rejectedTopic(), "InvalidRequestException", e.getMessage(), null, publisher);
        }
    }

    private void validateThingName(String thingName) {
        if (thingName == null || !THING_NAME_PATTERN.matcher(thingName).matches()) {
            throw new AwsException("InvalidRequestException", "Invalid thing name: " + thingName, 400);
        }
    }

    private void validateMqttClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 128 || clientId.startsWith("$")) {
            throw new AwsException("InvalidRequestException", "Invalid MQTT clientId: " + clientId, 400);
        }
    }

    private String thingKey(String region, String thingName) {
        return "thing:" + region + ":" + thingName;
    }

    private <T> Page<T> paginate(List<T> items, Integer maxResults, String nextToken) {
        int start = parseNextToken(nextToken);
        if (start > items.size()) {
            start = items.size();
        }
        int limit = maxResults == null ? items.size() - start : Math.max(0, maxResults);
        int end = Math.min(items.size(), start + limit);
        String followingToken = end < items.size() ? Integer.toString(end) : null;
        return new Page<>(items.subList(start, end), followingToken);
    }

    private int parseNextToken(String nextToken) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(nextToken));
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidRequestException", "Invalid nextToken", 400);
        }
    }

    private void startMqttIfEnabled() {
        mqttBrokerService.startIfEnabled();
    }

    private String certificateKey(String region, String certificateId) { return "cert:" + region + ":" + certificateId; }
    private String policyKey(String region, String policyName) { return "policy:" + region + ":" + policyName; }
    private String policyAttachmentKey(String region, String policyName) { return "policy-attachment:" + region + ":" + policyName; }
    private String thingPrincipalKey(String region, String thingName) { return "thing-principal:" + region + ":" + thingName; }
    private String shadowKey(String region, String thingName, String shadowName) { return "shadow:" + region + ":" + thingName + ":" + (shadowName == null ? "" : shadowName); }
    private String topicRuleKey(String region, String ruleName) { return "topic-rule:" + region + ":" + ruleName; }
    private String retainedMessageKey(String topic) { return "retained:" + topic; }
    private String jobKey(String region, String jobId) { return "job:" + region + ":" + jobId; }
    private String jobExecutionKey(String region, String thingName, String jobId) { return "job-execution:" + region + ":" + thingName + ":" + jobId; }
    private String thingTypeKey(String region, String thingTypeName) { return "thing-type:" + region + ":" + thingTypeName; }
    private String thingGroupKey(String region, String thingGroupName) { return "thing-group:" + region + ":" + thingGroupName; }
    private String thingGroupMembershipKey(String region, String thingGroupName) { return "thing-group-membership:" + region + ":" + thingGroupName; }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    private void executeTopicRule(IotTopicRule rule, byte[] payload) {
        try {
            JsonNode actions = objectMapper.readTree(rule.getActionsJson());
            if (!actions.isArray()) {
                return;
            }
            for (JsonNode action : actions) {
                JsonNode republish = action.path("republish");
                if (republish.isObject()) {
                    String targetTopic = republish.path("topic").asText(null);
                    if (targetTopic != null && !targetTopic.isBlank()) {
                        handlePublish(targetTopic, payload, false);
                        mqttBrokerService.publish(targetTopic, payload);
                    }
                }
                JsonNode sqs = action.path("sqs");
                if (sqs.isObject()) {
                    String queueUrl = sqs.path("queueUrl").asText(null);
                    if (queueUrl != null && !queueUrl.isBlank()) {
                        String body = sqs.path("useBase64").asBoolean(false)
                                ? Base64.getEncoder().encodeToString(payload)
                                : new String(payload, StandardCharsets.UTF_8);
                        sqsService.sendMessage(queueUrl, body, 0, config.defaultRegion());
                    }
                }
                JsonNode sns = action.path("sns");
                if (sns.isObject()) {
                    String targetArn = sns.path("targetArn").asText(null);
                    if (targetArn != null && !targetArn.isBlank()) {
                        snsService.publish(targetArn, null, new String(payload, StandardCharsets.UTF_8), null, config.defaultRegion());
                    }
                }
                JsonNode s3 = action.path("s3");
                if (s3.isObject()) {
                    String bucketName = s3.path("bucketName").asText(null);
                    String key = s3.path("key").asText(null);
                    if (bucketName != null && !bucketName.isBlank() && key != null && !key.isBlank()) {
                        s3Service.putObject(bucketName, key, payload, "application/octet-stream", Map.of());
                    }
                }
                JsonNode kinesis = action.path("kinesis");
                if (kinesis.isObject()) {
                    String streamName = kinesis.path("streamName").asText(null);
                    String partitionKey = kinesis.path("partitionKey").asText("floci-iot");
                    if (streamName != null && !streamName.isBlank()) {
                        kinesisService.putRecord(streamName, payload, partitionKey, config.defaultRegion());
                    }
                }
                JsonNode dynamoDBv2 = action.path("dynamoDBv2");
                if (dynamoDBv2.isObject()) {
                    String tableName = dynamoDBv2.path("putItem").path("tableName").asText(null);
                    if (tableName != null && !tableName.isBlank()) {
                        dynamoDbService.putItem(tableName, toDynamoDbItem(payload), config.defaultRegion());
                    }
                }
                JsonNode lambda = action.path("lambda");
                if (lambda.isObject()) {
                    String functionArn = lambda.path("functionArn").asText(null);
                    if (functionArn != null && !functionArn.isBlank()) {
                        lambdaService.invoke(config.defaultRegion(), functionArn, payload, InvocationType.Event);
                    }
                }
            }
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", "Invalid topic rule action for " + rule.getRuleName() + ": " + e.getMessage(), 400);
        }
    }

    private ObjectNode toDynamoDbItem(byte[] payload) {
        try {
            JsonNode document = objectMapper.readTree(payload);
            if (!document.isObject()) {
                throw new AwsException("InvalidRequestException", "dynamoDBv2 action payload must be a JSON object", 400);
            }
            ObjectNode item = objectMapper.createObjectNode();
            document.fields().forEachRemaining(entry -> item.set(entry.getKey(), toDynamoDbAttribute(entry.getValue())));
            return item;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", "dynamoDBv2 action payload must be valid JSON: " + e.getMessage(), 400);
        }
    }

    private ObjectNode toDynamoDbAttribute(JsonNode value) {
        ObjectNode attribute = objectMapper.createObjectNode();
        if (value == null || value.isNull()) {
            attribute.put("NULL", true);
        } else if (value.isBoolean()) {
            attribute.put("BOOL", value.asBoolean());
        } else if (value.isNumber()) {
            attribute.put("N", value.asText());
        } else {
            attribute.put("S", value.asText());
        }
        return attribute;
    }

    private String extractTopicPattern(String sql) {
        if (sql == null) {
            return null;
        }
        int from = sql.toUpperCase().indexOf(" FROM ");
        if (from < 0) {
            return null;
        }
        String tail = sql.substring(from + " FROM ".length()).trim();
        if (tail.length() >= 2 && (tail.charAt(0) == '\'' || tail.charAt(0) == '"')) {
            char quote = tail.charAt(0);
            int end = tail.indexOf(quote, 1);
            if (end > 1) {
                return tail.substring(1, end);
            }
        }
        return null;
    }

    private boolean topicMatches(String filter, String topic) {
        if (filter == null || topic == null) {
            return false;
        }
        String[] filterParts = filter.split("/", -1);
        String[] topicParts = topic.split("/", -1);
        int i = 0;
        for (; i < filterParts.length; i++) {
            String filterPart = filterParts[i];
            if ("#".equals(filterPart)) {
                return i == filterParts.length - 1;
            }
            if (i >= topicParts.length) {
                return false;
            }
            if (!"+".equals(filterPart) && !filterPart.equals(topicParts[i])) {
                return false;
            }
        }
        return i == topicParts.length;
    }

    private void publishShadowUpdate(ReservedShadowTopic topic, JsonNode request, String clientToken, String region,
                                     BiConsumer<String, byte[]> publisher) {
        JsonNode previous = shadowStore.get(shadowKey(region, topic.thingName(), topic.shadowName()))
                .map(IotShadow::getDocument)
                .map(this::readJson)
                .orElse(null);
        JsonNode current = updateThingShadow(topic.thingName(), topic.shadowName(), request, region);
        ObjectNode accepted = withClientToken(current.deepCopy(), clientToken);
        publishJson(topic.acceptedTopic(), accepted, publisher);

        ObjectNode documents = objectMapper.createObjectNode();
        if (previous != null) {
            documents.set("previous", previous);
        }
        documents.set("current", current);
        documents.put("timestamp", Instant.now().getEpochSecond());
        publishJson(topic.documentsTopic(), documents, publisher);

        ObjectNode deltaState = objectMapper.createObjectNode();
        JsonNode desired = current.path("state").path("desired");
        JsonNode reported = current.path("state").path("reported");
        if (desired.isObject()) {
            desired.fields().forEachRemaining(entry -> {
                JsonNode reportedValue = reported.path(entry.getKey());
                if (reportedValue.isMissingNode() || !reportedValue.equals(entry.getValue())) {
                    deltaState.set(entry.getKey(), entry.getValue());
                }
            });
        }
        if (!deltaState.isEmpty()) {
            ObjectNode delta = objectMapper.createObjectNode();
            delta.set("state", deltaState);
            delta.put("version", current.path("version").asLong());
            delta.put("timestamp", Instant.now().getEpochSecond());
            publishJson(topic.deltaTopic(), delta, publisher);
        }
    }

    private void publishShadowGet(ReservedShadowTopic topic, String clientToken, String region, BiConsumer<String, byte[]> publisher) {
        ObjectNode accepted = withClientToken(getThingShadow(topic.thingName(), topic.shadowName(), region).deepCopy(), clientToken);
        publishJson(topic.acceptedTopic(), accepted, publisher);
    }

    private void publishShadowDelete(ReservedShadowTopic topic, String clientToken, String region, BiConsumer<String, byte[]> publisher) {
        ObjectNode accepted = withClientToken(deleteThingShadow(topic.thingName(), topic.shadowName(), region).deepCopy(), clientToken);
        publishJson(topic.acceptedTopic(), accepted, publisher);
    }

    private ObjectNode withClientToken(JsonNode document, String clientToken) {
        ObjectNode node = document instanceof ObjectNode objectNode ? objectNode : objectMapper.createObjectNode();
        if (clientToken != null && !clientToken.isBlank()) {
            node.put("clientToken", clientToken);
        }
        return node;
    }

    private void publishRejected(String topic, String code, String message, String clientToken, BiConsumer<String, byte[]> publisher) {
        ObjectNode rejected = objectMapper.createObjectNode();
        rejected.put("code", code);
        rejected.put("message", message == null ? code : message);
        rejected.put("timestamp", Instant.now().getEpochSecond());
        if (clientToken != null && !clientToken.isBlank()) {
            rejected.put("clientToken", clientToken);
        }
        publishJson(topic, rejected, publisher);
    }

    private void publishJson(String topic, JsonNode payload, BiConsumer<String, byte[]> publisher) {
        try {
            publisher.accept(topic, objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    private ReservedShadowTopic parseReservedShadowTopic(String topic) {
        if (!topic.startsWith("$aws/things/")) {
            return null;
        }
        String[] parts = topic.split("/");
        if (parts.length == 5 && "things".equals(parts[1]) && "shadow".equals(parts[3])) {
            return new ReservedShadowTopic(parts[2], null, parts[4]);
        }
        if (parts.length == 7 && "things".equals(parts[1]) && "shadow".equals(parts[3]) && "name".equals(parts[4])) {
            return new ReservedShadowTopic(parts[2], parts[5], parts[6]);
        }
        return null;
    }

    private void mergeObject(ObjectNode parent, String field, JsonNode patch) {
        if (patch == null || patch.isMissingNode()) {
            return;
        }
        if (patch.isNull()) {
            parent.remove(field);
            return;
        }
        if (!patch.isObject()) {
            return;
        }
        ObjectNode target = parent.withObject("/" + field);
        patch.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNull()) {
                target.remove(entry.getKey());
            } else {
                target.set(entry.getKey(), entry.getValue());
            }
        });
    }

    private TaggableResource taggableForArn(String resourceArn) {
        String resource = resourceFromArn(resourceArn);
        String region = regionFromArn(resourceArn);
        if (resource.startsWith("thing/")) {
            String thingName = resource.substring("thing/".length());
            Thing thing = thingStore.get(thingKey(region, thingName))
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Resource not found: " + resourceArn, 404));
            return new TaggableResource(thing.getTags(), tags -> {
                thing.setTags(tags);
                thingStore.put(thingKey(region, thingName), thing);
            });
        }
        if (resource.startsWith("cert/")) {
            String certificateId = resource.substring("cert/".length());
            IotCertificate certificate = describeCertificate(certificateId, region);
            return new TaggableResource(certificate.getTags(), tags -> {
                certificate.setTags(tags);
                certificateStore.put(certificateKey(region, certificateId), certificate);
            });
        }
        if (resource.startsWith("policy/")) {
            String policyName = resource.substring("policy/".length());
            IotPolicy policy = getPolicy(policyName, region);
            return new TaggableResource(policy.getTags(), tags -> {
                policy.setTags(tags);
                policyStore.put(policyKey(region, policyName), policy);
            });
        }
        if (resource.startsWith("rule/")) {
            String ruleName = resource.substring("rule/".length());
            IotTopicRule rule = getTopicRule(ruleName, region);
            return new TaggableResource(rule.getTags(), tags -> {
                rule.setTags(tags);
                topicRuleStore.put(topicRuleKey(region, ruleName), rule);
            });
        }
        throw new AwsException("InvalidRequestException", "Invalid resource ARN: " + resourceArn, 400);
    }

    private void validateCertificateStatus(String status) {
        if (!Set.of("ACTIVE", "INACTIVE", "REVOKED").contains(status)) {
            throw new AwsException("InvalidRequestException", "Unsupported certificate status: " + status, 400);
        }
    }

    private boolean certificateHasAttachments(String certificateArn, String region) {
        boolean policyAttached = policyAttachmentStore.scan(key -> key.startsWith("policy-attachment:" + region + ":")).stream()
                .anyMatch(targets -> targets.contains(certificateArn));
        boolean thingAttached = thingPrincipalStore.scan(key -> key.startsWith("thing-principal:" + region + ":")).stream()
                .anyMatch(principals -> principals.contains(certificateArn));
        return policyAttached || thingAttached;
    }

    private Set<String> thingNamesForJobTarget(String target, String region) {
        String resource = target.startsWith("arn:") ? resourceFromArn(target) : target;
        if (resource.startsWith("thing/")) {
            String thingName = resource.substring("thing/".length());
            describeThing(thingName, region);
            return Set.of(thingName);
        }
        if (resource.startsWith("thinggroup/")) {
            String thingGroupName = resource.substring("thinggroup/".length());
            return listThingsInThingGroup(thingGroupName, region);
        }
        throw new AwsException("ResourceNotFoundException", "Unsupported or missing job target: " + target, 404);
    }

    private void updateThingTypeProperties(IotThingType type, JsonNode properties) {
        type.setDescription(properties.path("thingTypeDescription").asText(null));
        type.setSearchableAttributes(stringList(properties.path("searchableAttributes")));
    }

    private void updateThingGroupProperties(IotThingGroup group, JsonNode properties) {
        group.setDescription(properties.path("thingGroupDescription").asText(null));
        group.setAttributes(parseAttributePayload(properties.path("attributePayload")));
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new java.util.ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private Map<String, String> parseAttributePayload(JsonNode attributePayload) {
        Map<String, String> attributes = new TreeMap<>();
        JsonNode attributesNode = attributePayload.path("attributes");
        if (attributesNode.isObject()) {
            attributesNode.fields().forEachRemaining(entry -> attributes.put(entry.getKey(), entry.getValue().asText()));
        }
        return attributes;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void validateJobExecutionStatus(String status) {
        if (!Set.of("QUEUED", "IN_PROGRESS", "SUCCEEDED", "FAILED", "TIMED_OUT", "REJECTED", "REMOVED", "CANCELED").contains(status)) {
            throw new AwsException("InvalidRequestException", "Unsupported job execution status: " + status, 400);
        }
    }

    private String regionFromArn(String resourceArn) {
        return parseIotArn(resourceArn).region();
    }

    private String resourceFromArn(String resourceArn) {
        return parseIotArn(resourceArn).resource();
    }

    private AwsArnUtils.Arn parseIotArn(String resourceArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidRequestException", "Invalid resource ARN: " + resourceArn, 400);
        }
        if (!"iot".equals(arn.service()) || arn.region().isBlank() || arn.resource().isBlank()) {
            throw new AwsException("InvalidRequestException", "Invalid resource ARN: " + resourceArn, 400);
        }
        return arn;
    }

    private record ReservedShadowTopic(String thingName, String shadowName, String operation) {
        private String baseTopic() {
            if (shadowName != null && !shadowName.isBlank()) {
                return "$aws/things/" + thingName + "/shadow/name/" + shadowName + "/" + operation;
            }
            return "$aws/things/" + thingName + "/shadow/" + operation;
        }

        private String acceptedTopic() {
            return baseTopic() + "/accepted";
        }

        private String rejectedTopic() {
            return baseTopic() + "/rejected";
        }

        private String documentsTopic() {
            return updateBaseTopic() + "/documents";
        }

        private String deltaTopic() {
            return updateBaseTopic() + "/delta";
        }

        private String updateBaseTopic() {
            if (shadowName != null && !shadowName.isBlank()) {
                return "$aws/things/" + thingName + "/shadow/name/" + shadowName + "/update";
            }
            return "$aws/things/" + thingName + "/shadow/update";
        }
    }

    public record Page<T>(List<T> items, String nextToken) {
    }

    private record TaggableResource(Map<String, String> tags, java.util.function.Consumer<Map<String, String>> updateTags) {
    }
}
