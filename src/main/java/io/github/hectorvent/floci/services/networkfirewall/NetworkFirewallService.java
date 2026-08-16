package io.github.hectorvent.floci.services.networkfirewall;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class NetworkFirewallService {

    private static final int AVAILABILITY_ZONES_PER_REGION = 6;

    private final ObjectMapper objectMapper;
    private final StorageBackend<String, ObjectNode> ruleGroups;
    private final StorageBackend<String, ObjectNode> firewallPolicies;
    private final StorageBackend<String, ObjectNode> firewalls;
    private final StorageBackend<String, ObjectNode> loggingConfigurations;

    @Inject
    public NetworkFirewallService(ObjectMapper objectMapper, StorageFactory storageFactory) {
        this(objectMapper,
                storageFactory.create("networkfirewall", "network-firewall-rule-groups.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("networkfirewall", "network-firewall-policies.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("networkfirewall", "network-firewall-firewalls.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("networkfirewall", "network-firewall-logging.json",
                        new TypeReference<Map<String, ObjectNode>>() {}));
    }

    NetworkFirewallService(ObjectMapper objectMapper,
                           StorageBackend<String, ObjectNode> ruleGroups,
                           StorageBackend<String, ObjectNode> firewallPolicies,
                           StorageBackend<String, ObjectNode> firewalls,
                           StorageBackend<String, ObjectNode> loggingConfigurations) {
        this.objectMapper = objectMapper;
        this.ruleGroups = ruleGroups;
        this.firewallPolicies = firewallPolicies;
        this.firewalls = firewalls;
        this.loggingConfigurations = loggingConfigurations;
    }

    public ObjectNode createRuleGroup(JsonNode request, String region, String accountId) {
        return createNamed(request, "RuleGroupName", "RuleGroup", "RuleGroupResponse",
                "RuleGroupArn", "RuleGroupId", ruleGroupArn(request, region, accountId), ruleGroups);
    }

    public ObjectNode describeRuleGroup(String arn, String name) {
        return describeNamed(ruleGroups, arn, name, "RuleGroup", "RuleGroupResponse");
    }

    public ObjectNode updateRuleGroup(JsonNode request, String region, String accountId) {
        deleteIfPresent(ruleGroups, textOrNull(request, "RuleGroupArn"), textOrNull(request, "RuleGroupName"));
        return createRuleGroup(request, region, accountId);
    }

    public ObjectNode deleteRuleGroup(String arn, String name) {
        deleteRequired(ruleGroups, arn, name, "RuleGroup");
        return objectMapper.createObjectNode();
    }

    public ObjectNode listRuleGroups(String type) {
        return listNamed(ruleGroups, "RuleGroups", "Arn", "Name", type, "Type");
    }

    public ObjectNode createFirewallPolicy(JsonNode request, String region, String accountId) {
        String name = requiredText(request, "FirewallPolicyName");
        String arn = arn(region, accountId, "firewall-policy", name);
        return createNamed(request, "FirewallPolicyName", "FirewallPolicy", "FirewallPolicyResponse",
                "FirewallPolicyArn", "FirewallPolicyId", arn, firewallPolicies);
    }

    public ObjectNode describeFirewallPolicy(String arn, String name) {
        return describeNamed(firewallPolicies, arn, name, "FirewallPolicy", "FirewallPolicyResponse");
    }

    public ObjectNode updateFirewallPolicy(JsonNode request, String region, String accountId) {
        deleteIfPresent(firewallPolicies, textOrNull(request, "FirewallPolicyArn"),
                textOrNull(request, "FirewallPolicyName"));
        return createFirewallPolicy(request, region, accountId);
    }

    public ObjectNode deleteFirewallPolicy(String arn, String name) {
        deleteRequired(firewallPolicies, arn, name, "FirewallPolicy");
        return objectMapper.createObjectNode();
    }

    public ObjectNode listFirewallPolicies() {
        return listNamed(firewallPolicies, "FirewallPolicies", "Arn", "Name", null, null);
    }

    public ObjectNode createFirewall(JsonNode request, String region, String accountId) {
        String name = requiredText(request, "FirewallName");
        String firewallArn = arn(region, accountId, "firewall", name);
        ensureUnique(firewalls, firewallArn, name, "Firewall");

        ObjectNode firewall = copyObject(request);
        firewall.remove("UpdateToken");
        firewall.put("FirewallArn", firewallArn);
        firewall.put("FirewallId", deterministicHex(firewallArn, 32));
        firewall.put("FirewallName", name);
        firewall.put("FirewallPolicyChangeProtection", request.path("FirewallPolicyChangeProtection").asBoolean(false));
        firewall.put("SubnetChangeProtection", request.path("SubnetChangeProtection").asBoolean(false));
        firewall.put("DeleteProtection", request.path("DeleteProtection").asBoolean(false));
        firewall.put("AvailabilityZoneChangeProtection",
                request.path("AvailabilityZoneChangeProtection").asBoolean(false));
        firewalls.put(firewallArn, firewall);
        return firewallResponse(firewall, region);
    }

    public ObjectNode describeFirewall(String firewallArn, String firewallName, String region, String accountId) {
        requireIdentifier(firewallArn, firewallName);
        ObjectNode firewall = find(firewalls, firewallArn, firewallName, "FirewallArn", "FirewallName");
        if (firewall == null) {
            throw notFound("Firewall", firewallArn == null ? firewallName : firewallArn);
        }
        return firewallResponse(firewall, region);
    }

    public ObjectNode updateFirewall(JsonNode request, String region, String accountId) {
        String arn = textOrNull(request, "FirewallArn");
        String name = textOrNull(request, "FirewallName");
        ObjectNode existing = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        request.fields().forEachRemaining(entry -> {
            if (!"FirewallArn".equals(entry.getKey()) && !"FirewallName".equals(entry.getKey())) {
                existing.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        firewalls.put(existing.path("FirewallArn").asText(), existing);
        return firewallResponse(existing, region);
    }

    public ObjectNode deleteFirewall(String arn, String name) {
        ObjectNode existing = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        String firewallArn = existing.path("FirewallArn").asText();
        firewalls.delete(firewallArn);
        loggingConfigurations.delete(firewallArn);
        return objectMapper.createObjectNode().put("FirewallArn", firewallArn)
                .put("FirewallName", existing.path("FirewallName").asText());
    }

    public ObjectNode listFirewalls(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode result = response.putArray("Firewalls");
        List<String> vpcIds = request != null && request.path("VpcIds").isArray()
                ? java.util.stream.StreamSupport.stream(request.path("VpcIds").spliterator(), false)
                        .map(JsonNode::asText).toList()
                : List.of();
        firewalls.scan(key -> true).stream()
                .filter(firewall -> vpcIds.isEmpty() || vpcIds.contains(firewall.path("VpcId").asText()))
                .sorted(Comparator.comparing(firewall -> firewall.path("FirewallName").asText()))
                .forEach(firewall -> result.add(objectMapper.createObjectNode()
                        .put("FirewallArn", firewall.path("FirewallArn").asText())
                        .put("FirewallName", firewall.path("FirewallName").asText())));
        return response;
    }

    public ObjectNode putLoggingConfiguration(JsonNode request) {
        String arn = resolveFirewallArn(request);
        ObjectNode existing = require(firewalls, arn, textOrNull(request, "FirewallName"),
                "Firewall", "FirewallArn", "FirewallName");
        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("FirewallArn", existing.path("FirewallArn").asText());
        stored.put("FirewallName", existing.path("FirewallName").asText());
        stored.set("LoggingConfiguration", request.path("LoggingConfiguration").deepCopy());
        if (request.has("EnableMonitoringDashboard")) {
            stored.set("EnableMonitoringDashboard", request.get("EnableMonitoringDashboard").deepCopy());
        }
        loggingConfigurations.put(existing.path("FirewallArn").asText(), stored);
        return stored.deepCopy();
    }

    public ObjectNode describeLoggingConfiguration(String arn, String name) {
        ObjectNode firewall = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        ObjectNode logging = loggingConfigurations.get(firewall.path("FirewallArn").asText()).orElse(null);
        if (logging == null) {
            ObjectNode empty = objectMapper.createObjectNode();
            empty.put("FirewallArn", firewall.path("FirewallArn").asText());
            empty.put("FirewallName", firewall.path("FirewallName").asText());
            empty.set("LoggingConfiguration", objectMapper.createObjectNode()
                    .set("LogDestinationConfigs", objectMapper.createArrayNode()));
            return empty;
        }
        return logging.deepCopy();
    }

    public ObjectNode deleteLoggingConfiguration(String arn, String name) {
        ObjectNode firewall = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        loggingConfigurations.delete(firewall.path("FirewallArn").asText());
        return objectMapper.createObjectNode();
    }

    public ObjectNode findFirewall(String arn) {
        return firewalls.get(arn).map(ObjectNode::deepCopy).orElse(null);
    }

    private ObjectNode createNamed(JsonNode request, String nameField, String requestBodyField,
                                   String responseField, String arnField, String idField,
                                   String resourceArn, StorageBackend<String, ObjectNode> store) {
        String name = requiredText(request, nameField);
        ensureUnique(store, resourceArn, name, responseField);
        ObjectNode responseInfo = copyObject(request);
        JsonNode body = responseInfo.remove(requestBodyField);
        responseInfo.remove("UpdateToken");
        responseInfo.put(arnField, resourceArn);
        responseInfo.put(idField, deterministicHex(resourceArn, 32));
        responseInfo.put(nameField, name);
        responseInfo.put("ResourceArn", resourceArn);
        responseInfo.put("ResourceName", name);
        ObjectNode stored = objectMapper.createObjectNode();
        if (body != null && !body.isMissingNode()) {
            stored.set(requestBodyField, body.deepCopy());
        }
        stored.set(responseField, responseInfo);
        stored.put("UpdateToken", UUID.randomUUID().toString());
        store.put(resourceArn, stored);
        return stored.deepCopy();
    }

    private ObjectNode describeNamed(StorageBackend<String, ObjectNode> store, String arn, String name,
                                     String bodyField, String responseField) {
        ObjectNode stored = require(store, arn, name, responseField, "ResourceArn", "ResourceName");
        return stored.deepCopy();
    }

    private ObjectNode listNamed(StorageBackend<String, ObjectNode> store, String responseField,
                                 String arnField, String nameField, String filter, String filterField) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray(responseField);
        store.scan(key -> true).stream()
                .map(this::resourceMetadata)
                .filter(node -> filter == null || filter.equals(node.path(filterField).asText()))
                .sorted(Comparator.comparing(node -> node.path("ResourceName").asText()))
                .forEach(node -> array.add(objectMapper.createObjectNode()
                        .put(arnField, node.path("ResourceArn").asText())
                        .put(nameField, node.path("ResourceName").asText())));
        return response;
    }

    private JsonNode resourceMetadata(JsonNode stored) {
        if (stored.hasNonNull("ResourceArn")) {
            return stored;
        }
        for (JsonNode child : stored) {
            if (child.isObject() && child.hasNonNull("ResourceArn")) {
                return child;
            }
        }
        return objectMapper.createObjectNode();
    }

    private ObjectNode firewallResponse(ObjectNode firewall, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Firewall", firewall.deepCopy());
        ObjectNode status = response.putObject("FirewallStatus");
        status.put("ConfigurationSyncStateSummary", "IN_SYNC");
        status.put("Status", "READY");
        ObjectNode syncStates = status.putObject("SyncStates");
        JsonNode mappings = firewall.path("SubnetMappings");
        if (mappings.isArray() && !mappings.isEmpty()) {
            int index = 0;
            for (JsonNode mapping : mappings) {
                addAttachment(syncStates, region + (char) ('a' + index++),
                        mapping.path("SubnetId").asText(), firewall.path("FirewallArn").asText());
            }
        } else {
            for (int index = 0; index < AVAILABILITY_ZONES_PER_REGION; index++) {
                String availabilityZone = region + (char) ('a' + index);
                String subnetId = "subnet-" + deterministicHex(
                        firewall.path("FirewallArn").asText() + "|subnet|" + availabilityZone, 17);
                addAttachment(syncStates, availabilityZone, subnetId, firewall.path("FirewallArn").asText());
            }
        }
        return response;
    }

    private void addAttachment(ObjectNode syncStates, String availabilityZone, String subnetId, String arn) {
        ObjectNode attachment = syncStates.putObject(availabilityZone).putObject("Attachment");
        attachment.put("EndpointId", "vpce-" + deterministicHex(arn + "|" + availabilityZone, 17));
        attachment.put("Status", "READY");
        attachment.put("SubnetId", subnetId);
    }

    private void ensureUnique(StorageBackend<String, ObjectNode> store, String arn, String name, String kind) {
        if (store.get(arn).isPresent() || find(store, null, name, "ResourceArn", "ResourceName") != null) {
            throw new AwsException("ResourceAlreadyExistsException", kind + " already exists: " + name, 400);
        }
    }

    private ObjectNode require(StorageBackend<String, ObjectNode> store, String arn, String name,
                               String kind, String arnField, String nameField) {
        requireIdentifier(arn, name);
        ObjectNode result = find(store, arn, name, arnField, nameField);
        if (result == null) {
            throw notFound(kind, arn == null ? name : arn);
        }
        return result;
    }

    private ObjectNode find(StorageBackend<String, ObjectNode> store, String arn, String name,
                            String arnField, String nameField) {
        if (arn != null && !arn.isBlank()) {
            ObjectNode direct = store.get(arn).orElse(null);
            if (direct != null) {
                return direct;
            }
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        return store.scan(key -> true).stream()
                .filter(node -> containsValue(node, nameField, name))
                .findFirst().orElse(null);
    }

    private boolean containsValue(JsonNode node, String field, String expected) {
        if (expected.equals(node.path(field).asText(null))) {
            return true;
        }
        for (JsonNode child : node) {
            if (child.isObject() && expected.equals(child.path(field).asText(null))) {
                return true;
            }
        }
        return false;
    }

    private void deleteRequired(StorageBackend<String, ObjectNode> store, String arn, String name, String kind) {
        ObjectNode stored = require(store, arn, name, kind, "ResourceArn", "ResourceName");
        store.delete(resourceArn(stored));
    }

    private void deleteIfPresent(StorageBackend<String, ObjectNode> store, String arn, String name) {
        ObjectNode stored = find(store, arn, name, "ResourceArn", "ResourceName");
        if (stored != null) {
            store.delete(resourceArn(stored));
        }
    }

    private String resourceArn(JsonNode stored) {
        if (stored.hasNonNull("ResourceArn")) {
            return stored.path("ResourceArn").asText();
        }
        for (JsonNode child : stored) {
            if (child.isObject() && child.hasNonNull("ResourceArn")) {
                return child.path("ResourceArn").asText();
            }
        }
        throw new IllegalStateException("Stored Network Firewall resource has no ARN");
    }

    private String ruleGroupArn(JsonNode request, String region, String accountId) {
        String type = requiredText(request, "Type");
        String prefix = "STATELESS".equals(type) ? "stateless-rulegroup" : "stateful-rulegroup";
        return arn(region, accountId, prefix, requiredText(request, "RuleGroupName"));
    }

    private String resolveFirewallArn(JsonNode request) {
        String arn = textOrNull(request, "FirewallArn");
        if (arn != null) {
            return arn;
        }
        String name = textOrNull(request, "FirewallName");
        ObjectNode firewall = find(firewalls, null, name, "FirewallArn", "FirewallName");
        return firewall == null ? null : firewall.path("FirewallArn").asText();
    }

    private ObjectNode copyObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new AwsException("InvalidRequestException", "A JSON request object is required.", 400);
        }
        return ((ObjectNode) request).deepCopy();
    }

    private static String requiredText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidRequestException", field + " is required.", 400);
        }
        return value;
    }

    private static void requireIdentifier(String arn, String name) {
        if ((arn == null || arn.isBlank()) && (name == null || name.isBlank())) {
            throw new AwsException("InvalidRequestException",
                    "Either a resource name or ARN must be specified.", 400);
        }
    }

    private static AwsException notFound(String kind, String identifier) {
        return new AwsException("ResourceNotFoundException", kind + " not found: " + identifier, 400);
    }

    private static String arn(String region, String accountId, String type, String name) {
        return "arn:aws:network-firewall:" + region + ":" + accountId + ":" + type + "/" + name;
    }

    static String textOrNull(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String deterministicHex(String value, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public ObjectNode associateFirewallPolicy(JsonNode request, String region, String accountId) {
        String policyArn = requiredText(request, "FirewallPolicyArn");
        String arn = textOrNull(request, "FirewallArn");
        String name = textOrNull(request, "FirewallName");
        ObjectNode firewall = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        require(firewallPolicies, policyArn, null, "FirewallPolicy", "ResourceArn", "ResourceName");
        String firewallArn = firewall.path("FirewallArn").asText();
        firewall.put("FirewallPolicyArn", policyArn);
        firewalls.put(firewallArn, firewall);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", firewallArn);
        response.put("FirewallName", firewall.path("FirewallName").asText());
        response.put("FirewallPolicyArn", policyArn);
        return response;
    }
}
