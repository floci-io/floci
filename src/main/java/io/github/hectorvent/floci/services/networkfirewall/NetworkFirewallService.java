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
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class NetworkFirewallService {

    private static final int AVAILABILITY_ZONES_PER_REGION = 6;
    private static final Set<String> RULE_GROUP_TYPES =
            Set.of("STATELESS", "STATEFUL", "STATEFUL_DOMAIN");
    private static final Set<String> LOG_TYPES = Set.of("ALERT", "FLOW", "TLS");
    private static final Set<String> LOG_DESTINATION_TYPES =
            Set.of("S3", "CloudWatchLogs", "KinesisDataFirehose");

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
        return describeNamed(ruleGroups, arn, name, "RuleGroup");
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
        return describeNamed(firewallPolicies, arn, name, "FirewallPolicy");
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
        firewall.put("FirewallPolicyChangeProtection", request.path("FirewallPolicyChangeProtection").asBoolean(true));
        firewall.put("SubnetChangeProtection", request.path("SubnetChangeProtection").asBoolean(true));
        firewall.put("DeleteProtection", request.path("DeleteProtection").asBoolean(true));
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

    /**
     * Each UpdateFirewall* operation models exactly one mutable field (botocore
     * 2020-11-12). Anything else in the raw request — including another operation's
     * field, or unmodeled members like SubnetMappings — must not be persisted, matching
     * AWS ignoring unmodeled request members and scoping each op to its own field.
     */
    private static final Map<String, String> UPDATE_ACTION_FIELDS = Map.of(
            "UpdateFirewallDescription", "Description",
            "UpdateFirewallDeleteProtection", "DeleteProtection",
            "UpdateSubnetChangeProtection", "SubnetChangeProtection",
            "UpdateFirewallPolicyChangeProtection", "FirewallPolicyChangeProtection",
            "UpdateAvailabilityZoneChangeProtection", "AvailabilityZoneChangeProtection",
            "UpdateFirewallAnalysisSettings", "EnabledAnalysisTypes");

    /**
     * Botocore documents omission as removal for UpdateFirewallDescription alone:
     * "If you omit this setting, Network Firewall removes the description for the
     * firewall." EnabledAnalysisTypes carries no equivalent statement, so an omitted
     * value there is preserved rather than inferring behaviour the model never states.
     */
    private static final Set<String> CLEARED_WHEN_OMITTED = Set.of("UpdateFirewallDescription");

    public ObjectNode updateFirewall(String action, JsonNode request, String region, String accountId) {
        String arn = textOrNull(request, "FirewallArn");
        String name = textOrNull(request, "FirewallName");
        ObjectNode existing = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        String field = UPDATE_ACTION_FIELDS.get(action);
        JsonNode value = request.get(field);
        if (value != null) {
            existing.set(field, value.deepCopy());
        } else if (CLEARED_WHEN_OMITTED.contains(action)) {
            existing.remove(field);
        }
        firewalls.put(existing.path("FirewallArn").asText(), existing);

        // Each UpdateFirewall* response is a flat {FirewallArn, FirewallName,
        // <field>, UpdateToken} shape (botocore 2020-11-12) -- distinct from
        // CreateFirewall/DescribeFirewall's nested {Firewall, FirewallStatus}
        // envelope that firewallResponse() builds.
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", existing.path("FirewallArn").asText());
        response.put("FirewallName", existing.path("FirewallName").asText());
        // Description and EnabledAnalysisTypes are optional on their own update ops
        // (botocore 2020-11-12 marks no required members), so a firewall created
        // without one has nothing to echo -- omit the member rather than serialising
        // the MissingNode that path() returns as an explicit null.
        JsonNode current = existing.get(field);
        if (current != null) {
            response.set(field, current.deepCopy());
        }
        response.put("UpdateToken", UUID.randomUUID().toString());
        return response;
    }

    public ObjectNode deleteFirewall(String arn, String name, String region) {
        ObjectNode existing = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        String firewallArn = existing.path("FirewallArn").asText();
        if (existing.path("DeleteProtection").asBoolean(false)) {
            throw new AwsException("InvalidOperationException",
                    "Firewall has delete protection enabled: " + firewallArn, 400);
        }
        ObjectNode response = firewallResponse(existing, region);
        firewalls.delete(firewallArn);
        loggingConfigurations.delete(firewallArn);
        return response;
    }

    /**
     * The four association operations are synchronized because {@link #mappingsOf}
     * hands out a detached copy and {@link #storeAndRespond} replaces the whole
     * field with it: the read-modify-write is only atomic under a common lock, and
     * without one the later of two overlapping calls silently discards the earlier
     * caller's mapping.
     */
    public synchronized ObjectNode associateSubnets(JsonNode request) {
        ObjectNode firewall = firewallForChange(request, "SubnetChangeProtection", "subnet");
        ArrayNode requestedMappings = requiredArray(request, "SubnetMappings");
        ArrayNode mappings = mappingsOf(firewall, "SubnetMappings");
        for (JsonNode requested : requestedMappings) {
            addMapping(mappings, "SubnetId", requiredText(requested, "SubnetId"), requested);
        }
        return storeAndRespond(firewall, "SubnetMappings", mappings);
    }

    /** @see #associateSubnets for why this is synchronized. */
    public synchronized ObjectNode disassociateSubnets(JsonNode request) {
        ObjectNode firewall = firewallForChange(request, "SubnetChangeProtection", "subnet");
        ArrayNode requestedIds = requiredArray(request, "SubnetIds");
        ArrayNode mappings = mappingsOf(firewall, "SubnetMappings");
        for (JsonNode subnetId : requestedIds) {
            removeMapping(mappings, "SubnetId", subnetId.asText());
        }
        return storeAndRespond(firewall, "SubnetMappings", mappings);
    }

    /** @see #associateSubnets for why this is synchronized. */
    public synchronized ObjectNode associateAvailabilityZones(JsonNode request) {
        ObjectNode firewall =
                firewallForChange(request, "AvailabilityZoneChangeProtection", "Availability Zone");
        ArrayNode requestedMappings = requiredArray(request, "AvailabilityZoneMappings");
        ArrayNode mappings = mappingsOf(firewall, "AvailabilityZoneMappings");
        for (JsonNode requested : requestedMappings) {
            addMapping(mappings, "AvailabilityZone", requiredText(requested, "AvailabilityZone"), requested);
        }
        return storeAndRespond(firewall, "AvailabilityZoneMappings", mappings);
    }

    /** @see #associateSubnets for why this is synchronized. */
    public synchronized ObjectNode disassociateAvailabilityZones(JsonNode request) {
        ObjectNode firewall =
                firewallForChange(request, "AvailabilityZoneChangeProtection", "Availability Zone");
        ArrayNode requestedMappings = requiredArray(request, "AvailabilityZoneMappings");
        ArrayNode mappings = mappingsOf(firewall, "AvailabilityZoneMappings");
        for (JsonNode requested : requestedMappings) {
            removeMapping(mappings, "AvailabilityZone", requiredText(requested, "AvailabilityZone"));
        }
        return storeAndRespond(firewall, "AvailabilityZoneMappings", mappings);
    }

    private ObjectNode firewallForChange(JsonNode request, String protectionField, String protectedResource) {
        ObjectNode firewall = require(firewalls, textOrNull(request, "FirewallArn"),
                textOrNull(request, "FirewallName"), "Firewall", "FirewallArn", "FirewallName");
        if (firewall.path(protectionField).asBoolean(false)) {
            throw new AwsException("InvalidOperationException",
                    "Firewall has " + protectedResource + " change protection enabled: "
                            + firewall.path("FirewallArn").asText(), 400);
        }
        return firewall;
    }

    /**
     * Returns a DETACHED copy of the firewall's mapping array so per-element
     * validation can reject mid-loop without mutating stored state; the copy is
     * attached and persisted only by {@link #storeAndRespond}.
     */
    private ArrayNode mappingsOf(ObjectNode firewall, String field) {
        JsonNode existing = firewall.path(field);
        return existing.isArray() ? ((ArrayNode) existing).deepCopy() : objectMapper.createArrayNode();
    }

    private void addMapping(ArrayNode mappings, String memberField, String member, JsonNode mapping) {
        for (JsonNode existing : mappings) {
            if (member.equals(existing.path(memberField).asText(null))) {
                return;
            }
        }
        mappings.add(mapping.deepCopy());
    }

    private void removeMapping(ArrayNode mappings, String memberField, String member) {
        for (int index = mappings.size() - 1; index >= 0; index--) {
            if (member.equals(mappings.get(index).path(memberField).asText(null))) {
                mappings.remove(index);
            }
        }
    }

    private ObjectNode storeAndRespond(ObjectNode firewall, String field, ArrayNode mappings) {
        String firewallArn = firewall.path("FirewallArn").asText();
        firewall.set(field, mappings);
        firewalls.put(firewallArn, firewall);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", firewallArn);
        response.put("FirewallName", firewall.path("FirewallName").asText());
        response.set(field, mappings.deepCopy());
        response.put("UpdateToken", UUID.randomUUID().toString());
        return response;
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
        validateLoggingConfiguration(request.path("LoggingConfiguration"));
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

    /**
     * The whole LoggingConfiguration is stored verbatim, so the two enums inside each
     * LogDestinationConfig have to be checked here: LogType (ALERT/FLOW/TLS) and LogDestinationType
     * (S3/CloudWatchLogs/KinesisDataFirehose, case-sensitive as the model spells them).
     */
    private void validateLoggingConfiguration(JsonNode loggingConfiguration) {
        if (loggingConfiguration == null || !loggingConfiguration.isObject()) {
            return;
        }
        for (JsonNode config : loggingConfiguration.path("LogDestinationConfigs")) {
            requireEnum(textOrNull(config, "LogType"), LOG_TYPES, "LogType");
            requireEnum(textOrNull(config, "LogDestinationType"), LOG_DESTINATION_TYPES,
                    "LogDestinationType");
        }
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
        ensureUnique(store, resourceArn, name, requestBodyField);
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
                                     String bodyField) {
        ObjectNode stored = require(store, arn, name, bodyField, "ResourceArn", "ResourceName");
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
        // Only a firewall created without any SubnetMappings gets synthetic endpoints; once the
        // field exists, disassociating every subnet must leave the sync states empty.
        if (mappings.isArray()) {
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

    /**
     * The model enumerates {@code Type} as STATELESS, STATEFUL or STATEFUL_DOMAIN, and the value is
     * both persisted and folded into the ARN. Only STATELESS gets the stateless ARN prefix; the two
     * stateful variants share {@code stateful-rulegroup}, as AWS does. Left unchecked, any unmodelled
     * value silently fell through to the stateful prefix and was stored as a rule group AWS would
     * have rejected.
     */
    private String ruleGroupArn(JsonNode request, String region, String accountId) {
        String type = requiredText(request, "Type");
        requireEnum(type, RULE_GROUP_TYPES, "Type");
        String prefix = "STATELESS".equals(type) ? "stateless-rulegroup" : "stateful-rulegroup";
        return arn(region, accountId, prefix, requiredText(request, "RuleGroupName"));
    }

    private static void requireEnum(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw new AwsException("InvalidRequestException",
                    field + " must be one of " + allowed + ".", 400);
        }
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

    private static ArrayNode requiredArray(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new AwsException("InvalidRequestException", field + " is required.", 400);
        }
        return (ArrayNode) value;
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
        ObjectNode firewall =
                firewallForChange(request, "FirewallPolicyChangeProtection", "firewall policy");
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
