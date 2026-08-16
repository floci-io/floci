package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** The AWS-managed domain lists available in commercial regions. */
    static final List<String> AWS_MANAGED_DOMAIN_LIST_NAMES = List.of(
            "AWSManagedDomainsAggregateThreatList",
            "AWSManagedDomainsAmazonGuardDutyThreatList",
            "AWSManagedDomainsBotnetCommandandControl",
            "AWSManagedDomainsMalwareDomainList");

    public record FirewallDomainList(String id, String arn, String name, String managedOwnerName) {
    }

    private final StorageBackend<String, ObjectNode> domainListStore;
    private final StorageBackend<String, ObjectNode> endpointStore;
    private final StorageBackend<String, ObjectNode> ruleStore;
    private final StorageBackend<String, ObjectNode> ruleAssociationStore;
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

    public ObjectNode createFirewallDomainList(JsonNode request, String region, String accountId) {
        String name = requireText(request, "Name");
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

    public ObjectNode createResolverEndpoint(JsonNode request, String region, String accountId) {
        requireText(request, "Name");
        requireText(request, "Direction");
        JsonNode ipAddresses = request.path("IpAddressRequests");
        if (!ipAddresses.isArray() || ipAddresses.isEmpty()) {
            throw new AwsException("InvalidParametersException", "IpAddressRequests is required", 400);
        }
        String id = id("rslvr-in");
        ObjectNode endpoint = objectMapper.createObjectNode();
        endpoint.put("Id", id);
        endpoint.put("Arn", "arn:aws:route53resolver:" + region + ":" + accountId + ":resolver-endpoint/" + id);
        endpoint.put("Name", text(request, "Name"));
        endpoint.put("Direction", text(request, "Direction"));
        endpoint.set("SecurityGroupIds", request.path("SecurityGroupIds").deepCopy());
        endpoint.put("IpAddressCount", ipAddresses.size());
        endpoint.put("HostVPCId", "vpc-" + deterministicHex(id, 8));
        endpoint.put("Status", "OPERATIONAL");
        endpoint.put("CreatorRequestId", text(request, "CreatorRequestId"));
        endpoint.put("CreationTime", Instant.now().toString());
        endpoint.put("ModificationTime", Instant.now().toString());
        endpointStore.put(id, endpoint);
        return endpoint.deepCopy();
    }

    public ObjectNode deleteResolverEndpoint(String id) {
        ObjectNode endpoint = require(endpointStore, id, "resolver endpoint");
        endpointStore.delete(id);
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
        copyIfPresent(request, endpoint, "Name", "ResolverEndpointType");
        endpoint.put("ModificationTime", Instant.now().toString());
        endpointStore.put(id, endpoint);
        return endpoint.deepCopy();
    }

    // ---------- Resolver rules ----------

    public ObjectNode createResolverRule(JsonNode request, String region, String accountId) {
        requireText(request, "RuleType");
        String domainName = text(request, "DomainName");
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
        String ruleId = requireText(request, "ResolverRuleId");
        String vpcId = requireText(request, "VPCId");
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
        String ruleId = requireText(request, "ResolverRuleId");
        String vpcId = requireText(request, "VPCId");
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

    // ---------- Shared helpers ----------

    private ObjectNode require(StorageBackend<String, ObjectNode> store, String id, String type) {
        return store.get(id).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Unknown " + type + ": " + id, 400));
    }

    private String requireText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidParametersException", field + " is required", 400);
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
