package io.github.hectorvent.floci.services.route53resolver;

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
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Route 53 Resolver: DNS Firewall (managed + custom domain lists), resolver
 * endpoints, resolver rules, and resolver rule / VPC associations.
 *
 * <p>LZA's {@code Custom::ResolverManagedDomainList} Lambda resolves a managed
 * list's Id by Name via {@code ListFirewallDomainLists}, so the AWS-managed
 * lists must exist without any create call. Their ids are derived
 * deterministically from region+name so they are stable across restarts
 * without needing storage — this predates the rest of this class and is left
 * untouched; only custom (created) resources use the stores below.</p>
 *
 * <p>All create/update operations complete synchronously: real AWS transitions
 * resources through CREATING/UPDATING before a terminal state; this emulator
 * returns the terminal state immediately (same convention as
 * {@code ServiceCatalogService.copyProduct} and others — see CS-021).</p>
 */
@ApplicationScoped
public class Route53ResolverService {

    public static final String MANAGED_OWNER_NAME = "Route 53 Resolver DNS Firewall";

    /**
     * Route 53 Resolver models two error vocabularies, and which one applies is per-operation.
     * The resolver endpoint / rule / association operations list the singular
     * {@code InvalidParameterException}; the later DNS Firewall operations list
     * {@code ValidationException} and do not model {@code InvalidParameterException} at all
     * (see {@code CreateFirewallDomainList} and {@code ListFirewallDomainLists} in botocore's
     * route53resolver/2018-04-01 model). The plural {@code InvalidParametersException} this
     * service used to raise belongs to Service Catalog and exists nowhere in this model.
     */
    private static final String INVALID_PARAMETER = "InvalidParameterException";
    /** The DNS Firewall family's parameter-rejection error. See {@link #INVALID_PARAMETER}. */
    private static final String VALIDATION = "ValidationException";

    /** The AWS-managed domain lists available in commercial regions. */
    static final List<String> AWS_MANAGED_DOMAIN_LIST_NAMES = List.of(
            "AWSManagedDomainsAggregateThreatList",
            "AWSManagedDomainsAmazonGuardDutyThreatList",
            "AWSManagedDomainsBotnetCommandandControl",
            "AWSManagedDomainsMalwareDomainList");

    /** botocore {@code RuleTypeOption}. */
    private static final List<String> RULE_TYPES = List.of("FORWARD", "SYSTEM", "RECURSIVE", "DELEGATE");

    /** botocore {@code ResolverEndpointType}. */
    private static final List<String> ENDPOINT_TYPES = List.of("IPV6", "IPV4", "DUALSTACK");

    public record FirewallDomainList(String id, String arn, String name, String managedOwnerName) {
    }

    private final StorageBackend<String, ObjectNode> domainListStore;
    private final StorageBackend<String, ObjectNode> endpointStore;
    private final StorageBackend<String, ObjectNode> ruleStore;
    private final StorageBackend<String, ObjectNode> ruleAssociationStore;
    /**
     * The {@code IpAddressRequests} each endpoint was created with, keyed by endpoint id.
     * Kept beside the endpoint rather than on it: the modeled {@code ResolverEndpoint} shape
     * carries {@code IpAddressCount} and no IP list, and the handlers return the stored node
     * verbatim, so holding the addresses on the resource would put an unmodeled member on the
     * wire. Only the CreatorRequestId conflict check reads this.
     */
    private final StorageBackend<String, ObjectNode> endpointIpRequestStore;
    private final ObjectMapper objectMapper;

    @Inject
    public Route53ResolverService(StorageFactory storageFactory, ObjectMapper objectMapper) {
        this.domainListStore = storageFactory.create("route53resolver", "route53resolver-domain-lists.json",
                new TypeReference<java.util.Map<String, ObjectNode>>() {});
        this.endpointStore = storageFactory.create("route53resolver", "route53resolver-endpoints.json",
                new TypeReference<java.util.Map<String, ObjectNode>>() {});
        this.ruleStore = storageFactory.create("route53resolver", "route53resolver-rules.json",
                new TypeReference<java.util.Map<String, ObjectNode>>() {});
        this.ruleAssociationStore = storageFactory.create("route53resolver",
                "route53resolver-rule-associations.json", new TypeReference<java.util.Map<String, ObjectNode>>() {});
        this.endpointIpRequestStore = storageFactory.create("route53resolver",
                "route53resolver-endpoint-ip-requests.json",
                new TypeReference<java.util.Map<String, ObjectNode>>() {});
        this.objectMapper = objectMapper;
    }

    // Package-private for hermetic tests: pass in-memory StorageBackends directly, so a test
    // can put a store into a state the public API cannot produce (e.g. an endpoint whose
    // IpAddressRequests record is missing).
    Route53ResolverService(StorageBackend<String, ObjectNode> domainListStore,
                           StorageBackend<String, ObjectNode> endpointStore,
                           StorageBackend<String, ObjectNode> ruleStore,
                           StorageBackend<String, ObjectNode> ruleAssociationStore,
                           StorageBackend<String, ObjectNode> endpointIpRequestStore,
                           ObjectMapper objectMapper) {
        this.domainListStore = domainListStore;
        this.endpointStore = endpointStore;
        this.ruleStore = ruleStore;
        this.ruleAssociationStore = ruleAssociationStore;
        this.endpointIpRequestStore = endpointIpRequestStore;
        this.objectMapper = objectMapper;
    }

    public List<FirewallDomainList> listFirewallDomainLists(String region) {
        return AWS_MANAGED_DOMAIN_LIST_NAMES.stream()
                .map(name -> managedList(region, name))
                .toList();
    }

    public FirewallDomainList getFirewallDomainList(String region, String id) {
        for (FirewallDomainList list : listFirewallDomainLists(region)) {
            if (list.id().equals(id)) {
                return list;
            }
        }
        throw new AwsException("ResourceNotFoundException", "Firewall domain list not found: " + id, 404);
    }

    // ---------- Custom firewall domain lists ----------

    public synchronized ObjectNode createFirewallDomainList(JsonNode request, String region, String accountId) {
        String name = requireText(request, "Name", VALIDATION);
        java.util.Optional<ObjectNode> replay = replayOf(domainListStore, request, region);
        if (replay.isPresent()) {
            return replay.get();
        }
        String id = id("rslvr-fdl");
        ObjectNode list = objectMapper.createObjectNode();
        list.put("Id", id);
        list.put("Arn", "arn:aws:route53resolver:" + region + ":" + accountId + ":firewall-domain-list/" + id);
        list.put("Name", name);
        list.put("DomainCount", 0);
        list.put("Status", "COMPLETE");
        list.put("CreatorRequestId", text(request, "CreatorRequestId"));
        list.put("ManagedOwnerName", (String) null);
        list.put("CreationTime", Instant.now().toString());
        list.put("ModificationTime", Instant.now().toString());
        domainListStore.put(id, list);
        return list.deepCopy();
    }

    public ObjectNode deleteFirewallDomainList(String id) {
        ObjectNode list = require(domainListStore, id, "firewall domain list");
        domainListStore.delete(id);
        ObjectNode result = list.deepCopy();
        result.put("Status", "DELETING");
        return result;
    }

    public List<ObjectNode> listCustomFirewallDomainLists() {
        return domainListStore.scan(key -> true).stream().map(ObjectNode::deepCopy).toList();
    }

    public java.util.Optional<ObjectNode> getCustomFirewallDomainList(String id) {
        return domainListStore.get(id).map(ObjectNode::deepCopy);
    }

    // ---------- Resolver endpoints ----------

    public synchronized ObjectNode createResolverEndpoint(JsonNode request, String region, String accountId) {
        requireText(request, "Name", INVALID_PARAMETER);
        String direction = requireText(request, "Direction", INVALID_PARAMETER);
        String idPrefix = endpointIdPrefix(direction);
        JsonNode ipAddresses = request.path("IpAddressRequests");
        if (!ipAddresses.isArray() || ipAddresses.isEmpty()) {
            throw new AwsException(INVALID_PARAMETER, "IpAddressRequests is required", 400);
        }
        java.util.Optional<ObjectNode> replay = replayOf(endpointStore, request, region);
        if (replay.isPresent()) {
            ObjectNode existing = replay.get();
            requireReplayMatches(existing, request, "Name", "Direction", "SecurityGroupIds");
            requireSameIpRequests(existing, request, ipAddresses);
            return existing;
        }
        String id = id(idPrefix);
        ObjectNode endpoint = objectMapper.createObjectNode();
        endpoint.put("Id", id);
        endpoint.put("Arn", "arn:aws:route53resolver:" + region + ":" + accountId + ":resolver-endpoint/" + id);
        endpoint.put("Name", text(request, "Name"));
        endpoint.put("Direction", direction);
        endpoint.set("SecurityGroupIds", request.path("SecurityGroupIds").deepCopy());
        endpoint.put("IpAddressCount", ipAddresses.size());
        endpoint.put("HostVPCId", "vpc-" + deterministicHex(id, 8));
        endpoint.put("Status", "OPERATIONAL");
        endpoint.put("CreatorRequestId", text(request, "CreatorRequestId"));
        endpoint.put("CreationTime", Instant.now().toString());
        endpoint.put("ModificationTime", Instant.now().toString());
        endpointStore.put(id, endpoint);
        endpointIpRequestStore.put(id,
                objectMapper.createObjectNode().set("IpAddressRequests", normalizedIpRequests(ipAddresses)));
        return endpoint.deepCopy();
    }

    public ObjectNode deleteResolverEndpoint(String id) {
        ObjectNode endpoint = require(endpointStore, id, "resolver endpoint");
        endpointStore.delete(id);
        endpointIpRequestStore.delete(id);
        ObjectNode result = endpoint.deepCopy();
        result.put("Status", "DELETING");
        return result;
    }

    public ObjectNode getResolverEndpoint(String id) {
        return require(endpointStore, id, "resolver endpoint").deepCopy();
    }

    public List<ObjectNode> listResolverEndpoints() {
        return endpointStore.scan(key -> true).stream().map(ObjectNode::deepCopy).toList();
    }

    public ObjectNode updateResolverEndpoint(String id, JsonNode request) {
        ObjectNode endpoint = require(endpointStore, id, "resolver endpoint");
        String endpointType = text(request, "ResolverEndpointType");
        if (endpointType != null) {
            requireEnum(endpointType, "ResolverEndpointType", ENDPOINT_TYPES, INVALID_PARAMETER);
        }
        copyIfPresent(request, endpoint, "Name", "ResolverEndpointType");
        endpoint.put("ModificationTime", Instant.now().toString());
        endpointStore.put(id, endpoint);
        return endpoint.deepCopy();
    }

    // ---------- Resolver rules ----------

    public synchronized ObjectNode createResolverRule(JsonNode request, String region, String accountId) {
        requireEnum(requireText(request, "RuleType", INVALID_PARAMETER), "RuleType", RULE_TYPES,
                INVALID_PARAMETER);
        // TargetIps is modeled list min 1: present-but-empty is invalid, absent is
        // allowed (SYSTEM rules carry no targets).
        JsonNode targetIps = request.path("TargetIps");
        if (targetIps.isArray() && targetIps.isEmpty()) {
            throw new AwsException(INVALID_PARAMETER,
                    "TargetIps must contain at least one target address.", 400);
        }
        String domainName = text(request, "DomainName");
        java.util.Optional<ObjectNode> replay = replayOf(ruleStore, request, region);
        if (replay.isPresent()) {
            requireReplayMatches(replay.get(), request,
                    "Name", "RuleType", "DomainName", "TargetIps", "ResolverEndpointId");
            return replay.get();
        }
        String id = id("rslvr-rr");
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("Id", id);
        rule.put("Arn", "arn:aws:route53resolver:" + region + ":" + accountId + ":resolver-rule/" + id);
        rule.put("DomainName", domainName);
        rule.put("Status", "COMPLETE");
        rule.put("RuleType", text(request, "RuleType"));
        rule.put("Name", text(request, "Name"));
        rule.set("TargetIps", request.path("TargetIps").deepCopy());
        rule.put("ResolverEndpointId", text(request, "ResolverEndpointId"));
        rule.put("OwnerId", accountId);
        rule.put("ShareStatus", "NOT_SHARED");
        rule.put("CreatorRequestId", text(request, "CreatorRequestId"));
        rule.put("CreationTime", Instant.now().toString());
        rule.put("ModificationTime", Instant.now().toString());
        ruleStore.put(id, rule);
        return rule.deepCopy();
    }

    public ObjectNode deleteResolverRule(String id) {
        ObjectNode rule = require(ruleStore, id, "resolver rule");
        ruleStore.delete(id);
        ObjectNode result = rule.deepCopy();
        result.put("Status", "DELETING");
        return result;
    }

    public ObjectNode getResolverRule(String id) {
        return require(ruleStore, id, "resolver rule").deepCopy();
    }

    public List<ObjectNode> listResolverRules() {
        return ruleStore.scan(key -> true).stream().map(ObjectNode::deepCopy).toList();
    }

    public ObjectNode updateResolverRule(String id, JsonNode config) {
        ObjectNode rule = require(ruleStore, id, "resolver rule");
        copyIfPresent(config, rule, "Name", "TargetIps", "ResolverEndpointId");
        rule.put("ModificationTime", Instant.now().toString());
        ruleStore.put(id, rule);
        return rule.deepCopy();
    }

    // ---------- Resolver rule associations ----------

    public ObjectNode associateResolverRule(JsonNode request) {
        String ruleId = requireText(request, "ResolverRuleId", INVALID_PARAMETER);
        String vpcId = requireText(request, "VPCId", INVALID_PARAMETER);
        require(ruleStore, ruleId, "resolver rule");
        String id = id("rslvr-rrassoc");
        ObjectNode association = objectMapper.createObjectNode();
        association.put("Id", id);
        association.put("ResolverRuleId", ruleId);
        association.put("Name", text(request, "Name"));
        association.put("VPCId", vpcId);
        association.put("Status", "COMPLETE");
        ruleAssociationStore.put(id, association);
        return association.deepCopy();
    }

    public ObjectNode disassociateResolverRule(JsonNode request) {
        String ruleId = requireText(request, "ResolverRuleId", INVALID_PARAMETER);
        String vpcId = requireText(request, "VPCId", INVALID_PARAMETER);
        ObjectNode association = ruleAssociationStore.scan(key -> true).stream()
                .filter(a -> ruleId.equals(text(a, "ResolverRuleId")) && vpcId.equals(text(a, "VPCId")))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No association between resolver rule " + ruleId + " and VPC " + vpcId, 400));
        ruleAssociationStore.delete(text(association, "Id"));
        ObjectNode result = association.deepCopy();
        result.put("Status", "DELETING");
        return result;
    }

    public ObjectNode getResolverRuleAssociation(String id) {
        return require(ruleAssociationStore, id, "resolver rule association").deepCopy();
    }

    public List<ObjectNode> listResolverRuleAssociations() {
        return ruleAssociationStore.scan(key -> true).stream().map(ObjectNode::deepCopy).toList();
    }

    /**
     * AWS gives a resolver endpoint a direction-specific id prefix: {@code rslvr-in-} for
     * inbound endpoints, {@code rslvr-out-} for outbound. {@code INBOUND_DELEGATION} is an
     * inbound variant and shares the inbound prefix. Anything outside
     * {@code ResolverEndpointDirection} is rejected rather than defaulted.
     */
    private static String endpointIdPrefix(String direction) {
        return switch (direction) {
            case "INBOUND", "INBOUND_DELEGATION" -> "rslvr-in";
            case "OUTBOUND" -> "rslvr-out";
            default -> throw new AwsException(INVALID_PARAMETER,
                    "Direction must be one of INBOUND, OUTBOUND, INBOUND_DELEGATION: " + direction, 400);
        };
    }

    // ---------- Shared helpers ----------

    /**
     * Route 53 Resolver creates are idempotent on {@code CreatorRequestId}: replaying a
     * token returns the resource it originally created rather than allocating a second
     * one. Called after the request's own validation so a replayed token never excuses a
     * malformed body. A blank/absent token opts out — those creates always allocate.
     *
     * <p>The scan-then-put pair is only atomic because every create method is
     * {@code synchronized} — two overlapping retries with the same token would otherwise
     * both miss the scan and persist twice.</p>
     *
     * <p>Idempotency is scoped to one region, as in AWS: Route 53 Resolver is regional, so
     * the same token in {@code us-east-1} and {@code us-west-2} identifies two independent
     * resources. The stores are account-partitioned by {@code AccountAwareStorageBackend}
     * but carry no region in their keys, so the candidate's region comes from the ARN this
     * service built for it — keeping the region off the wire response, which the modeled
     * shapes have no field for. Same intent as {@code FisService.idempotencyKey} and
     * {@code BedrockAgentCoreControlService.tokenKey}, which fold the region into the key.</p>
     */
    private java.util.Optional<ObjectNode> replayOf(StorageBackend<String, ObjectNode> store, JsonNode request,
                                                    String region) {
        String creatorRequestId = text(request, "CreatorRequestId");
        if (creatorRequestId == null || creatorRequestId.isBlank()) {
            return java.util.Optional.empty();
        }
        String regionPrefix = "arn:aws:route53resolver:" + region + ":";
        return store.scan(key -> true).stream()
                .filter(existing -> creatorRequestId.equals(text(existing, "CreatorRequestId")))
                .filter(existing -> {
                    String arn = text(existing, "Arn");
                    return arn != null && arn.startsWith(regionPrefix);
                })
                .findFirst()
                .map(ObjectNode::deepCopy);
    }

    /**
     * A retried create must describe the same resource it originally created. AWS models
     * {@code ResourceExistsException} on {@code CreateResolverEndpoint} and
     * {@code CreateResolverRule} for a {@code CreatorRequestId} replayed with different
     * parameters, rather than returning the original and leaving the caller holding a
     * success response whose attributes are not the ones it asked for.
     *
     * <p>Only members the retry actually supplies are compared, so omitting an optional
     * one is not read as a disagreement.</p>
     *
     * <p>{@code CreateFirewallDomainList} deliberately does not call this: its modeled error
     * list carries no conflict error at all, so there is nothing faithful to raise and the
     * lenient replay stands. Tracked in
     * {@code issues/route53resolver-firewall-domain-list-retry-conflict.md} and pinned by a
     * test so it cannot drift silently.</p>
     */
    private void requireReplayMatches(ObjectNode existing, JsonNode request, String... fields) {
        for (String field : fields) {
            JsonNode requested = request.get(field);
            if (requested == null || requested.isNull()) {
                continue;
            }
            JsonNode stored = existing.get(field);
            if (stored == null || !stored.equals(requested)) {
                throw replayConflict(request, existing, field);
            }
        }
    }

    /**
     * A retry that keeps the IP-request count but changes a subnet or address describes a
     * different endpoint, so it is a conflict rather than a replay. Compared against the
     * addresses recorded at create time, since the stored resource keeps only
     * {@code IpAddressCount}.
     *
     * <p>Order is not significant — the request list is a set of addresses, and a retry that
     * merely reorders it is the same request — so both sides are normalised before comparing.</p>
     *
     * <p>A stored endpoint with no recorded addresses is reported as a conflict rather than
     * waved through. Falling back to comparing {@code IpAddressCount} would accept a retry
     * that kept the count but changed a subnet or address — the precise case this check
     * exists to catch — so the weaker comparison is not a lenient version of this check, it
     * is a silently wrong one. With nothing to compare against, the honest answer is that
     * sameness cannot be established: a spurious {@code ResourceExistsException} is loud and
     * recoverable, a wrong success is neither. No released build can reach this state — the
     * endpoint store itself is new in the same change as this record — so the only ways in
     * are a write interrupted between the two stores, or state left by an intermediate build
     * of this branch.</p>
     */
    private void requireSameIpRequests(ObjectNode existing, JsonNode request, JsonNode ipAddresses) {
        JsonNode recorded = endpointIpRequestStore.get(text(existing, "Id"))
                .map(node -> node.get("IpAddressRequests"))
                .orElse(null);
        // Both sides are re-normalised here rather than trusting the stored order, so a record
        // written before the ordering was corrected still compares as equal.
        if (recorded == null
                || !normalizedIpRequests(recorded).equals(normalizedIpRequests(ipAddresses))) {
            throw replayConflict(request, existing, "IpAddressRequests");
        }
    }

    /**
     * The IP requests in a stable order, so a reordered retry is not read as a change.
     *
     * <p>Ordered by {@link #canonicalKey}, not by {@code toString}: a node serialises its
     * members in insertion order, so two requests carrying the same addresses written with
     * their members in a different order sort differently and compare unequal — an
     * equivalent retry rejected as a conflict. JSON member order is not significant, so the
     * key must not depend on it.</p>
     */
    private ArrayNode normalizedIpRequests(JsonNode ipAddresses) {
        List<JsonNode> entries = new java.util.ArrayList<>();
        ipAddresses.forEach(entries::add);
        entries.sort(java.util.Comparator.comparing(Route53ResolverService::canonicalKey));
        ArrayNode normalized = objectMapper.createArrayNode();
        entries.forEach(normalized::add);
        return normalized;
    }

    /** A node's contents as a string that does not depend on the order its members were written in. */
    private static String canonicalKey(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new java.util.ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            java.util.Collections.sort(names);
            StringBuilder key = new StringBuilder("{");
            for (String name : names) {
                key.append(name).append('=').append(canonicalKey(node.get(name))).append(';');
            }
            return key.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder key = new StringBuilder("[");
            node.forEach(element -> key.append(canonicalKey(element)).append(';'));
            return key.append(']').toString();
        }
        return node.asText();
    }

    private AwsException replayConflict(JsonNode request, ObjectNode existing, String field) {
        return new AwsException("ResourceExistsException",
                "CreatorRequestId " + text(request, "CreatorRequestId") + " was already used to create "
                        + text(existing, "Id") + " with a different " + field + ".", 400);
    }

    private ObjectNode require(StorageBackend<String, ObjectNode> store, String id, String type) {
        return store.get(id).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Unknown " + type + ": " + id, 400));
    }

    /**
     * Rejects a value the botocore model does not list in the member's enum. Accepting one
     * stores a resource AWS would never have created — a rule whose {@code RuleType} is not
     * a {@code RuleTypeOption} is then handed back by Get/List as though it were real.
     *
     * <p>The caller passes the error code its own operation models, because the two families
     * in this service do not share one — see {@link #INVALID_PARAMETER} and
     * {@link #VALIDATION}.</p>
     */
    private static void requireEnum(String value, String field, List<String> allowed, String errorCode) {
        if (!allowed.contains(value)) {
            throw new AwsException(errorCode,
                    field + " must be one of " + String.join(", ", allowed) + ": " + value, 400);
        }
    }

    private String requireText(JsonNode node, String field, String errorCode) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new AwsException(errorCode, field + " is required", 400);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            if (source.has(field)) {
                target.set(field, source.get(field).deepCopy());
            }
        }
    }

    private String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 13);
    }

    private static FirewallDomainList managedList(String region, String name) {
        String id = "rslvr-fdl-" + deterministicHex(region + "|" + name, 17);
        // Managed lists are AWS-owned: their ARNs carry no account id.
        String arn = "arn:aws:route53resolver:" + region + "::firewall-domain-list/" + id;
        return new FirewallDomainList(id, arn, name, MANAGED_OWNER_NAME);
    }

    private static String deterministicHex(String seed, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
