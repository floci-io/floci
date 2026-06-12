package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iot.model.IotCertificate;
import io.github.hectorvent.floci.services.iot.model.IotPolicy;
import io.github.hectorvent.floci.services.iot.model.IotShadow;
import io.github.hectorvent.floci.services.iot.model.Thing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final IotPublishEventRecorder publishEventRecorder;
    private final IotMqttBrokerService mqttBrokerService;

    @Inject
    public IotService(StorageFactory storageFactory,
                      EmulatorConfig config,
                      RegionResolver regionResolver,
                      ObjectMapper objectMapper,
                      IotPublishEventRecorder publishEventRecorder,
                      IotMqttBrokerService mqttBrokerService) {
        this(storageFactory.create("iot", "iot-things.json", new TypeReference<Map<String, Thing>>() {}),
                storageFactory.create("iot", "iot-certificates.json", new TypeReference<Map<String, IotCertificate>>() {}),
                storageFactory.create("iot", "iot-policies.json", new TypeReference<Map<String, IotPolicy>>() {}),
                storageFactory.create("iot", "iot-policy-attachments.json", new TypeReference<Map<String, Set<String>>>() {}),
                storageFactory.create("iot", "iot-thing-principals.json", new TypeReference<Map<String, Set<String>>>() {}),
                storageFactory.create("iot", "iot-shadows.json", new TypeReference<Map<String, IotShadow>>() {}),
                config, regionResolver, objectMapper, publishEventRecorder, mqttBrokerService);
    }

    IotService(StorageBackend<String, Thing> thingStore,
               StorageBackend<String, IotCertificate> certificateStore,
               StorageBackend<String, IotPolicy> policyStore,
               StorageBackend<String, Set<String>> policyAttachmentStore,
               StorageBackend<String, Set<String>> thingPrincipalStore,
               StorageBackend<String, IotShadow> shadowStore,
               EmulatorConfig config,
               RegionResolver regionResolver,
               ObjectMapper objectMapper,
               IotPublishEventRecorder publishEventRecorder,
               IotMqttBrokerService mqttBrokerService) {
        this.thingStore = thingStore;
        this.certificateStore = certificateStore;
        this.policyStore = policyStore;
        this.policyAttachmentStore = policyAttachmentStore;
        this.thingPrincipalStore = thingPrincipalStore;
        this.shadowStore = shadowStore;
        this.config = config;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.publishEventRecorder = publishEventRecorder;
        this.mqttBrokerService = mqttBrokerService;
    }

    public String describeEndpoint(String endpointType) {
        String effectiveType = endpointType == null || endpointType.isBlank() ? DEFAULT_ENDPOINT_TYPE : endpointType;
        if (!DEFAULT_ENDPOINT_TYPE.equals(effectiveType)) {
            throw new AwsException("InvalidRequestException", "Unsupported endpoint type: " + effectiveType, 400);
        }
        startMqttIfEnabled();
        URI baseUri = URI.create(config.effectiveBaseUrl());
        return baseUri.getAuthority();
    }

    public Thing createThing(String thingName, Map<String, String> attributes, String region) {
        startMqttIfEnabled();
        validateThingName(thingName);
        String key = thingKey(region, thingName);
        if (thingStore.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "Thing already exists: " + thingName, 409);
        }

        Instant now = Instant.now();
        Thing thing = new Thing();
        thing.setThingName(thingName);
        thing.setThingArn(regionResolver.buildArn("iot", region, "thing/" + thingName));
        thing.setThingId(UUID.randomUUID().toString());
        thing.setAttributes(attributes);
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

    public Thing updateThing(String thingName, Map<String, String> attributes, String region) {
        Thing thing = describeThing(thingName, region);
        thing.setAttributes(attributes);
        thing.setVersion(thing.getVersion() + 1);
        thing.setLastModifiedDate(Instant.now());
        thingStore.put(thingKey(region, thingName), thing);
        return thing;
    }

    public void deleteThing(String thingName, String region) {
        describeThing(thingName, region);
        thingStore.delete(thingKey(region, thingName));
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        Thing thing = thingForArn(resourceArn);
        return new TreeMap<>(thing.getTags());
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        Thing thing = thingForArn(resourceArn);
        Map<String, String> updatedTags = new TreeMap<>(thing.getTags());
        updatedTags.putAll(tags);
        thing.setTags(updatedTags);
        thingStore.put(thingKey(regionFromArn(resourceArn), thing.getThingName()), thing);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        Thing thing = thingForArn(resourceArn);
        Map<String, String> updatedTags = new TreeMap<>(thing.getTags());
        tagKeys.forEach(updatedTags::remove);
        thing.setTags(updatedTags);
        thingStore.put(thingKey(regionFromArn(resourceArn), thing.getThingName()), thing);
    }

    public IotCertificate createKeysAndCertificate(boolean setAsActive, String region) {
        startMqttIfEnabled();
        String id = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        IotCertificate certificate = new IotCertificate();
        certificate.setCertificateId(id);
        certificate.setCertificateArn(regionResolver.buildArn("iot", region, "cert/" + id));
        certificate.setCertificatePem("-----BEGIN CERTIFICATE-----\n" + id + "\n-----END CERTIFICATE-----");
        certificate.setPublicKey("-----BEGIN PUBLIC KEY-----\n" + id + "\n-----END PUBLIC KEY-----");
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
        certificate.setStatus(status);
        certificateStore.put(certificateKey(region, certificateId), certificate);
    }

    public IotPolicy createPolicy(String policyName, String policyDocument, String region) {
        startMqttIfEnabled();
        IotPolicy policy = new IotPolicy();
        policy.setPolicyName(policyName);
        policy.setPolicyArn(regionResolver.buildArn("iot", region, "policy/" + policyName));
        policy.setPolicyDocument(policyDocument);
        policy.setDefaultVersionId("1");
        policy.setCreationDate(Instant.now());
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

    public JsonNode getThingShadow(String thingName, String shadowName, String region) {
        IotShadow shadow = shadowStore.get(shadowKey(region, thingName, shadowName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Shadow not found: " + thingName, 404));
        return readJson(shadow.getDocument());
    }

    public JsonNode updateThingShadow(String thingName, String shadowName, JsonNode request, String region) {
        String key = shadowKey(region, thingName, shadowName);
        IotShadow existing = shadowStore.get(key).orElse(null);
        ObjectNode document = existing == null ? objectMapper.createObjectNode() : (ObjectNode) readJson(existing.getDocument()).deepCopy();
        ObjectNode state = document.withObject("/state");
        JsonNode requestState = request.path("state");
        mergeObject(state, "desired", requestState.path("desired"));
        mergeObject(state, "reported", requestState.path("reported"));
        long version = existing == null ? 1 : existing.getVersion() + 1;
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
        publishEventRecorder.record(topic, payload);
    }

    private void validateThingName(String thingName) {
        if (thingName == null || !THING_NAME_PATTERN.matcher(thingName).matches()) {
            throw new AwsException("InvalidRequestException", "Invalid thing name: " + thingName, 400);
        }
    }

    private String thingKey(String region, String thingName) {
        return "thing:" + region + ":" + thingName;
    }

    private void startMqttIfEnabled() {
        mqttBrokerService.startIfEnabled();
    }

    private String certificateKey(String region, String certificateId) { return "cert:" + region + ":" + certificateId; }
    private String policyKey(String region, String policyName) { return "policy:" + region + ":" + policyName; }
    private String policyAttachmentKey(String region, String policyName) { return "policy-attachment:" + region + ":" + policyName; }
    private String thingPrincipalKey(String region, String thingName) { return "thing-principal:" + region + ":" + thingName; }
    private String shadowKey(String region, String thingName, String shadowName) { return "shadow:" + region + ":" + thingName + ":" + (shadowName == null ? "" : shadowName); }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }

    private void mergeObject(ObjectNode parent, String field, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            return;
        }
        ObjectNode target = parent.withObject("/" + field);
        patch.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
    }

    private Thing thingForArn(String resourceArn) {
        String thingName = thingNameFromArn(resourceArn);
        String region = regionFromArn(resourceArn);
        return thingStore.get(thingKey(region, thingName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Resource not found: " + resourceArn, 404));
    }

    private String thingNameFromArn(String resourceArn) {
        String resource = resourceFromArn(resourceArn);
        if (!resource.startsWith("thing/") || resource.length() == "thing/".length()) {
            throw new AwsException("InvalidRequestException", "Invalid resource ARN: " + resourceArn, 400);
        }
        return resource.substring("thing/".length());
    }

    private String regionFromArn(String resourceArn) {
        String[] parts = arnParts(resourceArn);
        return parts[3];
    }

    private String resourceFromArn(String resourceArn) {
        String[] parts = arnParts(resourceArn);
        return parts[5];
    }

    private String[] arnParts(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidRequestException", "Invalid resource ARN: " + resourceArn, 400);
        }
        String[] parts = resourceArn.split(":", 6);
        if (parts.length != 6 || !"arn".equals(parts[0]) || !"iot".equals(parts[2]) || parts[3].isBlank() || parts[5].isBlank()) {
            throw new AwsException("InvalidRequestException", "Invalid resource ARN: " + resourceArn, 400);
        }
        return parts;
    }
}
