package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

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

    private static final Logger LOG = Logger.getLogger(Route53ResolverService.class);

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
     * wire. Read by the CreatorRequestId conflict check and ListResolverEndpointIpAddresses.
     * Also holds the endpoint's region and, in {@code NetworkInterfaces}, the EC2 interface
     * behind each address (see {@link #createEndpointInterfaces}).
     */
    private final StorageBackend<String, ObjectNode> endpointIpRequestStore;
    /**
     * Tags keyed by resource ARN, each an object of key to value. Kept apart from the resources
     * for the same reason as {@link #endpointIpRequestStore}: none of the modeled resource
     * shapes carries {@code Tags}, and the handlers return stored nodes verbatim.
     */
    private final StorageBackend<String, ObjectNode> tagStore;
    private final ObjectMapper objectMapper;
    /** Null only in hermetic tests built without one; subnets are then treated as unknown. */
    private final Ec2Service ec2Service;

    @Inject
    public Route53ResolverService(StorageFactory storageFactory, ObjectMapper objectMapper, Ec2Service ec2Service) {
        this.domainListStore = storageFactory.create("route53resolver", "route53resolver-domain-lists.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.endpointStore = storageFactory.create("route53resolver", "route53resolver-endpoints.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.ruleStore = storageFactory.create("route53resolver", "route53resolver-rules.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.ruleAssociationStore = storageFactory.create("route53resolver",
                "route53resolver-rule-associations.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.endpointIpRequestStore = storageFactory.create("route53resolver",
                "route53resolver-endpoint-ip-requests.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.tagStore = storageFactory.create("route53resolver", "route53resolver-tags.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.objectMapper = objectMapper;
        this.ec2Service = ec2Service;
    }

    // Package-private for hermetic tests: pass in-memory StorageBackends directly, so a test
    // can put a store into a state the public API cannot produce (e.g. an endpoint whose
    // IpAddressRequests record is missing).
    Route53ResolverService(StorageBackend<String, ObjectNode> domainListStore,
                           StorageBackend<String, ObjectNode> endpointStore,
                           StorageBackend<String, ObjectNode> ruleStore,
                           StorageBackend<String, ObjectNode> ruleAssociationStore,
                           StorageBackend<String, ObjectNode> endpointIpRequestStore,
                           StorageBackend<String, ObjectNode> tagStore,
                           ObjectMapper objectMapper) {
        this(domainListStore, endpointStore, ruleStore, ruleAssociationStore, endpointIpRequestStore, tagStore,
                objectMapper, null);
    }

    Route53ResolverService(StorageBackend<String, ObjectNode> domainListStore,
                           StorageBackend<String, ObjectNode> endpointStore,
                           StorageBackend<String, ObjectNode> ruleStore,
                           StorageBackend<String, ObjectNode> ruleAssociationStore,
                           StorageBackend<String, ObjectNode> endpointIpRequestStore,
                           StorageBackend<String, ObjectNode> tagStore,
                           ObjectMapper objectMapper, Ec2Service ec2Service) {
        this.domainListStore = domainListStore;
        this.endpointStore = endpointStore;
        this.ruleStore = ruleStore;
        this.ruleAssociationStore = ruleAssociationStore;
        this.endpointIpRequestStore = endpointIpRequestStore;
        this.tagStore = tagStore;
        this.objectMapper = objectMapper;
        this.ec2Service = ec2Service;
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
        JsonNode tags = validatedTags(request.get("Tags"), VALIDATION);
        Optional<ObjectNode> replay = replayOf(domainListStore, request, region);
        if (replay.isPresent()) {
            return replay.get();
        }
        String id = id("rslvr-fdl");
        ObjectNode list = objectMapper.createObjectNode();
        list.put("Id", id);
        list.put("Arn", AwsArnUtils.Arn.of("route53resolver", region, accountId, "firewall-domain-list/" + id).toString());
        list.put("Name", name);
        list.put("DomainCount", 0);
        list.put("Status", "COMPLETE");
        list.put("CreatorRequestId", text(request, "CreatorRequestId"));
        list.put("ManagedOwnerName", (String) null);
        list.put("CreationTime", Instant.now().toString());
        list.put("ModificationTime", Instant.now().toString());
        domainListStore.put(id, list);
        putTags(list, tags);
        return list.deepCopy();
    }

    public ObjectNode deleteFirewallDomainList(String id) {
        ObjectNode list = require(domainListStore, id, "firewall domain list");
        domainListStore.delete(id);
        tagStore.delete(text(list, "Arn"));
        ObjectNode result = list.deepCopy();
        result.put("Status", "DELETING");
        return result;
    }

    public List<ObjectNode> listCustomFirewallDomainLists() {
        return domainListStore.scan(key -> true).stream().map(ObjectNode::deepCopy).toList();
    }

    public Optional<ObjectNode> getCustomFirewallDomainList(String id) {
        return domainListStore.get(id).map(ObjectNode::deepCopy);
    }

    // ---------- Resolver endpoints ----------

    public synchronized ObjectNode createResolverEndpoint(JsonNode request, String region, String accountId) {
        requireText(request, "Name", INVALID_PARAMETER);
        String direction = requireText(request, "Direction", INVALID_PARAMETER);
        String idPrefix = endpointIdPrefix(direction);
        JsonNode ipAddresses = ipAddressesOf(request);
        JsonNode tags = validatedTags(request.get("Tags"), INVALID_PARAMETER);
        if (!ipAddresses.isArray() || ipAddresses.isEmpty()) {
            throw new AwsException(INVALID_PARAMETER, "IpAddresses is required", 400);
        }
        // IpAddresses is modeled list min 2 / max 20: a resolver endpoint needs at
        // least two addresses for redundancy.
        int ipAddressCount = ipAddresses.size();
        if (ipAddressCount < 2) {
            throw new AwsException(INVALID_PARAMETER,
                    "IpAddresses must contain at least two IP addresses.", 400);
        }
        if (ipAddressCount > 20) {
            throw new AwsException(INVALID_PARAMETER,
                    "IpAddresses must contain at most twenty IP addresses.", 400);
        }
        Optional<ObjectNode> replay = replayOf(endpointStore, request, region);
        if (replay.isPresent()) {
            ObjectNode existing = replay.get();
            requireReplayMatches(existing, request, "Name", "Direction", "SecurityGroupIds");
            requireSameIpRequests(existing, request, ipAddresses);
            return existing;
        }
        String id = id(idPrefix);
        ObjectNode endpoint = objectMapper.createObjectNode();
        endpoint.put("Id", id);
        endpoint.put("Arn", AwsArnUtils.Arn.of("route53resolver", region, accountId, "resolver-endpoint/" + id).toString());
        endpoint.put("Name", text(request, "Name"));
        endpoint.put("Direction", direction);
        endpoint.set("SecurityGroupIds", request.path("SecurityGroupIds").deepCopy());
        endpoint.put("IpAddressCount", ipAddresses.size());
        ArrayNode normalized = normalizedIpRequests(ipAddresses);
        Map<String, Subnet> subnets = subnetsOf(normalized, region);
        // AWS: HostVPCId is the VPC of the endpoint's subnets (first subnet in request order).
        // When EC2 knows none of them the invented vpc-<hex> is kept purely for back-compat with
        // Floci's earlier accept-any-subnet behaviour; what AWS does there is unverified.
        String hostVpc = null;
        for (JsonNode r : ipAddresses) {
            Subnet known = subnets.get(text(r, "SubnetId"));
            if (known != null) {
                hostVpc = known.getVpcId();
                break;
            }
        }
        endpoint.put("HostVPCId", hostVpc != null ? hostVpc : "vpc-" + deterministicHex(id, 8));
        endpoint.put("Status", "OPERATIONAL");
        endpoint.put("CreatorRequestId", text(request, "CreatorRequestId"));
        endpoint.put("CreationTime", Instant.now().toString());
        endpoint.put("ModificationTime", Instant.now().toString());
        // Before anything is stored, so an address EC2 refuses leaves no endpoint behind.
        ArrayNode interfaces = createEndpointInterfaces(id, normalized, subnets,
                securityGroupIdsOf(request), region);
        endpointStore.put(id, endpoint);
        ObjectNode record = objectMapper.createObjectNode();
        record.set("IpAddressRequests", normalized);
        record.put("Region", region);
        record.set("NetworkInterfaces", interfaces);
        endpointIpRequestStore.put(id, record);
        putTags(endpoint, tags);
        return endpoint.deepCopy();
    }

    private Map<String, Subnet> subnetsOf(JsonNode requests, String region) {
        List<String> ids = new ArrayList<>();
        requests.forEach(r -> {
            String subnetId = text(r, "SubnetId");
            if (subnetId != null && !ids.contains(subnetId)) {
                ids.add(subnetId);
            }
        });
        Map<String, Subnet> found = new LinkedHashMap<>();
        if (ec2Service != null && !ids.isEmpty()) {
            ec2Service.describeSubnets(region, ids, Map.of()).forEach(sn -> found.put(sn.getSubnetId(), sn));
        }
        return found;
    }

    private List<String> securityGroupIdsOf(JsonNode request) {
        List<String> ids = new ArrayList<>();
        request.path("SecurityGroupIds").forEach(sg -> ids.add(sg.asText()));
        return ids;
    }

    /**
     * One EC2 network interface per address, which is how AWS backs a resolver endpoint ("Resolver
     * creates a network interface for each subnet and IP address", Route 53 developer guide), made
     * the way ECS makes an awsvpc task's: created in the address's subnet with the request's
     * {@code Ip}, or one EC2 picks, and the endpoint's security groups, then held for the service so
     * the account cannot delete it from under the endpoint. EC2's allocator then keeps instances,
     * other interfaces and other endpoints off the address, and refuses an explicit one in use.
     *
     * <p>Entries line up with {@code requests}: the interface's id and address, or null where none
     * is made, for a subnet EC2 does not know (Floci has always accepted those) or an IPv6-only
     * request. ponytail: Floci's EC2 interfaces are IPv4 only; IPv6 addresses are kept as given.
     * A refusal deletes the interfaces already made and is raised in Route 53 Resolver's codes.
     */
    private ArrayNode createEndpointInterfaces(String endpointId, JsonNode requests, Map<String, Subnet> subnets,
                                               List<String> securityGroupIds, String region) {
        ArrayNode out = objectMapper.createArrayNode();
        requests.forEach(r -> out.addNull());
        List<String> created = new ArrayList<>();
        try {
            // Explicit addresses first, so an address EC2 picks for one entry is never one that a
            // later entry asks for; the stored list keeps the request order.
            for (boolean explicitPass : new boolean[] {true, false}) {
                for (int i = 0; i < requests.size(); i++) {
                    JsonNode r = requests.get(i);
                    String subnetId = text(r, "SubnetId");
                    String ip = text(r, "Ip");
                    if ((ip != null) != explicitPass || !subnets.containsKey(subnetId)
                            || (ip == null && text(r, "Ipv6") != null)) {
                        continue;
                    }
                    NetworkInterface eni = ec2Service.createNetworkInterface(region, subnetId,
                            "Route 53 Resolver: " + endpointId, ip, List.of(), securityGroupIds, List.of());
                    created.add(eni.getNetworkInterfaceId());
                    ec2Service.holdNetworkInterfaceForService(region, eni.getNetworkInterfaceId());
                    out.set(i, objectMapper.createObjectNode()
                            .put("NetworkInterfaceId", eni.getNetworkInterfaceId())
                            .put("Ip", eni.getPrivateIpAddress()));
                }
            }
        } catch (RuntimeException e) {
            // Any failure, not only EC2's refusals, would otherwise leave the interfaces made so far.
            deleteEndpointInterfaces(region, created);
            throw e instanceof AwsException refusal ? asResolverRejection(refusal) : e;
        }
        return out;
    }

    /**
     * EC2's refusal in a code CreateResolverEndpoint models. The model names no error for an
     * address already in use or a subnet with none left, so those take InvalidRequestException
     * ("The request is invalid"); anything else EC2 refuses here (an address outside the subnet, a
     * security group it does not know) is a parameter that is not valid.
     */
    private static AwsException asResolverRejection(AwsException e) {
        String code = switch (e.getErrorCode()) {
            case "InvalidIPAddress.InUse", "InsufficientFreeAddressesInSubnet" -> "InvalidRequestException";
            default -> INVALID_PARAMETER;
        };
        return new AwsException(code, e.getMessage(), 400);
    }

    /**
     * Releases and deletes the interfaces, the way ECS tears down a task's. One that is already gone
     * is skipped silently; any other failure is logged and the rest are still deleted, so one
     * interface EC2 will not delete does not strand the others.
     */
    private void deleteEndpointInterfaces(String region, List<String> interfaceIds) {
        for (String eniId : interfaceIds) {
            try {
                ec2Service.releaseNetworkInterfaceFromService(region, eniId);
                ec2Service.deleteNetworkInterface(region, eniId);
            } catch (AwsException e) {
                if ("InvalidNetworkInterfaceID.NotFound".equals(e.getErrorCode())) {
                    // Expected: the interface is already deleted, so there is nothing left to free.
                    continue;
                }
                LOG.warnv("Could not delete network interface {0} of a Route 53 Resolver endpoint: {1}",
                        eniId, e.getMessage());
            }
        }
    }

    // Synchronized with createResolverEndpoint, which stores the endpoint before its record: a delete
    // between the two would find no interfaces to delete, and the create would then store the record.
    public synchronized ObjectNode deleteResolverEndpoint(String id) {
        ObjectNode endpoint = require(endpointStore, id, "resolver endpoint");
        // A record written before endpoints had interfaces has no NetworkInterfaces, so none to delete.
        endpointIpRequestStore.get(id).ifPresent(record -> {
            List<String> interfaceIds = new ArrayList<>();
            record.path("NetworkInterfaces").forEach(eni -> {
                if (text(eni, "NetworkInterfaceId") != null) {
                    interfaceIds.add(text(eni, "NetworkInterfaceId"));
                }
            });
            if (ec2Service != null) {
                deleteEndpointInterfaces(text(record, "Region"), interfaceIds);
            }
        });
        endpointStore.delete(id);
        endpointIpRequestStore.delete(id);
        tagStore.delete(text(endpoint, "Arn"));
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

    /**
     * The endpoint's addresses as AWS {@code IpAddressResponse} entries, built from the
     * {@code IpAddresses} recorded at create time. {@code IpId} is derived from the endpoint,
     * the entry and its position in the recorded list, so it is stable across calls. {@code Ip}
     * is the address of the EC2 interface behind the entry; an entry with no interface (see
     * {@link #createEndpointInterfaces}) has the request's {@code Ip}, or none.
     */
    public List<ObjectNode> listResolverEndpointIpAddresses(String id) {
        ObjectNode endpoint = require(endpointStore, id, "resolver endpoint");
        // Read once, so the requests and their interfaces come from the same record.
        JsonNode record = endpointIpRequestStore.get(id).orElse(null);
        JsonNode recorded = record == null ? null : record.get("IpAddressRequests");
        // A missing record would otherwise read as an empty list beside a nonzero
        // IpAddressCount; fail loudly with the modeled InternalServiceErrorException instead.
        if (recorded == null || !recorded.isArray()) {
            throw new AwsException("InternalServiceErrorException",
                    "No IP addresses recorded for resolver endpoint: " + id, 400);
        }
        JsonNode interfaces = record.path("NetworkInterfaces");
        List<ObjectNode> addresses = new ArrayList<>();
        for (int i = 0; i < recorded.size(); i++) {
            JsonNode request = recorded.get(i);
            ObjectNode address = objectMapper.createObjectNode();
            // The index keeps repeated entries (two addresses in one subnet, both left for AWS
            // to pick) distinct; the stored list is normalised once at create, so it is stable.
            address.put("IpId", "rni-" + deterministicHex(id + "|" + i + "|" + canonicalKey(request), 17));
            String ip = text(interfaces.path(i), "Ip");
            putIfSet(address, "SubnetId", text(request, "SubnetId"));
            putIfSet(address, "Ip", ip != null ? ip : text(request, "Ip"));
            putIfSet(address, "Ipv6", text(request, "Ipv6"));
            address.put("Status", "ATTACHED");
            address.put("StatusMessage", "This IP address is operational.");
            address.set("CreationTime", endpoint.get("CreationTime"));
            address.set("ModificationTime", endpoint.get("CreationTime"));
            addresses.add(address);
        }
        return addresses;
    }

    private static void putIfSet(ObjectNode target, String field, String value) {
        if (value != null) {
            target.put(field, value);
        }
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
        JsonNode tags = validatedTags(request.get("Tags"), INVALID_PARAMETER);
        Optional<ObjectNode> replay = replayOf(ruleStore, request, region);
        if (replay.isPresent()) {
            requireReplayMatches(replay.get(), request,
                    "Name", "RuleType", "DomainName", "TargetIps", "ResolverEndpointId");
            return replay.get();
        }
        String id = id("rslvr-rr");
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("Id", id);
        rule.put("Arn", AwsArnUtils.Arn.of("route53resolver", region, accountId, "resolver-rule/" + id).toString());
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
        putTags(rule, tags);
        return rule.deepCopy();
    }

    public ObjectNode deleteResolverRule(String id) {
        ObjectNode rule = require(ruleStore, id, "resolver rule");
        ruleStore.delete(id);
        tagStore.delete(text(rule, "Arn"));
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
     * The ids of the rules {@code accountId} has associated with {@code vpcId}. The DNS server reads
     * these off the packet path, where there is no caller, so the account comes from the resource the
     * query originated on rather than from a request context.
     *
     * <p>Scoped to that one account deliberately. A {@code VPCId} is not proof of ownership:
     * {@code AssociateResolverRule} stores whatever VPC id the caller names, so an association made
     * by another account naming this account's VPC must not steer its queries. Restricting the
     * lookup to the querying account's partition is the boundary, and it costs nothing because
     * {@code AssociateResolverRule} can only reference a rule that exists in the caller's own
     * partition, so an association and its rule always share an account.
     *
     * <p>Returns ids rather than the association nodes so the DNS path does not copy a record per
     * association on every query when all it needs is which rules to look at.
     */
    public List<String> resolverRuleIdsAssociatedWith(String accountId, String vpcId) {
        if (accountId == null || accountId.isBlank() || vpcId == null || vpcId.isBlank()) {
            return List.of();
        }
        List<String> ruleIds = new ArrayList<>();
        for (ObjectNode association : associationsForAccount(accountId)) {
            String ruleId = text(association, "ResolverRuleId");
            if (ruleId != null && !ruleIds.contains(ruleId) && vpcId.equals(text(association, "VPCId"))) {
                ruleIds.add(ruleId);
            }
        }
        return List.copyOf(ruleIds);
    }

    private List<ObjectNode> associationsForAccount(String accountId) {
        if (ruleAssociationStore instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<ObjectNode> accountAware =
                    (AccountAwareStorageBackend<ObjectNode>) rawAccountAware;
            return accountAware.scanForAccount(accountId, key -> true);
        }
        return ruleAssociationStore.scan(key -> true);
    }

    /**
     * One of an account's resolver rules by id, but only if it belongs to {@code region}. Fetched
     * rather than scanned for, so the DNS path reads only the rules an association actually named.
     *
     * <p>Route 53 Resolver is regional: a rule created in one region governs nothing in another. The
     * stores carry no region in their keys, so the rule's own region comes from the ARN this service
     * minted for it, the same place {@link #replayOf} reads it from. A stored rule with no parseable
     * ARN has no region to claim and is treated as belonging to the region asking, which no create
     * path can produce and which cannot leak another region's rule.
     */
    public Optional<ObjectNode> resolverRuleIn(String accountId, String region, String ruleId) {
        if (accountId == null || accountId.isBlank() || region == null || region.isBlank()
                || ruleId == null || ruleId.isBlank()) {
            return Optional.empty();
        }
        return storedRule(accountId, ruleId)
                .filter(rule -> region.equals(AwsArnUtils.regionOrDefault(text(rule, "Arn"), region)))
                .map(ObjectNode::deepCopy);
    }

    private Optional<ObjectNode> storedRule(String accountId, String ruleId) {
        if (ruleStore instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<ObjectNode> accountAware =
                    (AccountAwareStorageBackend<ObjectNode>) rawAccountAware;
            return accountAware.getForAccount(accountId, ruleId);
        }
        return ruleStore.get(ruleId);
    }

    // ---------- Tagging ----------

    public synchronized void tagResource(String arn, JsonNode tags, String region, String accountId) {
        if (tags == null || tags.isNull()) {
            throw new AwsException(INVALID_PARAMETER, "Tags is required", 400);
        }
        requireAtMostPerRequest(tags, "Tags", INVALID_PARAMETER);
        JsonNode validated = validatedTags(tags, "InvalidTagException");
        putTags(requireTaggable(arn, region, accountId), validated);
    }

    public synchronized void untagResource(String arn, JsonNode tagKeys, String region, String accountId) {
        if (tagKeys == null || !tagKeys.isArray()) {
            throw new AwsException(INVALID_PARAMETER, "TagKeys is required", 400);
        }
        requireAtMostPerRequest(tagKeys, "TagKeys", INVALID_PARAMETER);
        for (JsonNode key : tagKeys) {
            if (!key.isTextual()) {
                throw new AwsException(INVALID_PARAMETER, "TagKeys must be a list of strings", 400);
            }
        }
        requireTaggable(arn, region, accountId);
        ObjectNode stored = tagStore.get(arn).orElse(null);
        if (stored == null) {
            return;
        }
        tagKeys.forEach(key -> stored.remove(key.asText()));
        tagStore.put(arn, stored);
    }

    /**
     * One page of a resource's tags as modeled {@code Tag} entries, paged by
     * {@link Pagination#paginate} on the tag key; {@code MaxResults} defaults to the modeled 100.
     * {@code MaxResults} arrives as a JSON value, so it is type-checked here rather than parsed
     * from a string: {@code 1.5}, {@code true} and {@code "1"} are not the modeled integer.
     *
     * <p>The stored tag node is copied under the monitor {@link #tagResource} and
     * {@link #untagResource} mutate it under, so a concurrent tag change can neither break the
     * iteration nor show a half-applied page.</p>
     */
    public ObjectNode listTagsForResource(String arn, JsonNode maxResults, String nextToken,
                                          String region, String accountId) {
        boolean absent = maxResults == null || maxResults.isNull();
        if (!absent && (!maxResults.isIntegralNumber() || !maxResults.canConvertToInt()
                || maxResults.intValue() < 1 || maxResults.intValue() > 100)) {
            throw new AwsException(INVALID_PARAMETER, "MaxResults must be an integer between 1 and 100", 400);
        }
        ObjectNode snapshot;
        synchronized (this) {
            requireTaggable(arn, region, accountId);
            snapshot = tagStore.get(arn).map(ObjectNode::deepCopy).orElse(null);
        }
        List<Map.Entry<String, JsonNode>> all = new ArrayList<>();
        if (snapshot != null) {
            snapshot.fields().forEachRemaining(all::add);
        }
        PaginatedResult<Map.Entry<String, JsonNode>> paged = Pagination.paginate(all, Map.Entry::getKey,
                absent ? 100 : maxResults.intValue(), nextToken, "tags",
                token -> new AwsException("InvalidNextTokenException", "Invalid NextToken: " + token, 400));
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode page = result.putArray("Tags");
        for (Map.Entry<String, JsonNode> tag : paged.items()) {
            page.addObject().put("Key", tag.getKey()).set("Value", tag.getValue());
        }
        if (paged.nextToken() != null) {
            result.put("NextToken", paged.nextToken());
        }
        return result;
    }

    /**
     * The stored resource an ARN names. The ARN must name the caller's own partition, region and
     * account before anything is read, the id is looked up in the caller's account partition,
     * and the stored resource's ARN must match the request exactly; anything else is not found
     * rather than resolved to a resource that happens to share its id. A pre-multi-account
     * (unprefixed) entry is migrated into the caller's partition only once its own ARN proves
     * the caller owns it, so another account cannot claim it by naming its ARN. AWS-managed
     * firewall domain lists are not stored and so are not taggable here.
     */
    private ObjectNode requireTaggable(String arn, String region, String accountId) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException(INVALID_PARAMETER, "ResourceArn is required", 400);
        }
        AwsArnUtils.Arn parsed = resolverArnIn(arn, region);
        String resource = parsed != null && parsed.accountId().equals(accountId) ? parsed.resource() : "";
        int slash = resource.indexOf('/');
        StorageBackend<String, ObjectNode> store = slash < 0 ? null : switch (resource.substring(0, slash)) {
            case "resolver-endpoint" -> endpointStore;
            case "resolver-rule" -> ruleStore;
            case "firewall-domain-list" -> domainListStore;
            default -> null;
        };
        return Optional.ofNullable(store)
                .flatMap(s -> ownedBy(s, accountId, resource.substring(slash + 1),
                        stored -> arn.equals(text(stored, "Arn"))))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + arn, 400));
    }

    /** The parsed ARN when it is a Route 53 Resolver ARN in {@code region} and its partition, else null. */
    private static AwsArnUtils.Arn resolverArnIn(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "route53resolver")) {
            return null;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        return parsed.partition().equals(AwsRegions.partitionFor(region)) && parsed.region().equals(region)
                ? parsed : null;
    }

    /**
     * The caller's resource by id. Unlike {@link StorageBackend#get}, which migrates an unprefixed
     * legacy entry into whichever account reads it first, a legacy entry moves only when
     * {@code owner} accepts it.
     */
    private static Optional<ObjectNode> ownedBy(StorageBackend<String, ObjectNode> store, String accountId,
                                                String id, Predicate<ObjectNode> owner) {
        if (store instanceof AccountAwareStorageBackend<?> rawAccountAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<ObjectNode> accountAware =
                    (AccountAwareStorageBackend<ObjectNode>) rawAccountAware;
            return accountAware.getForAccountMigratingLegacy(accountId, id, owner);
        }
        return store.get(id).filter(owner);
    }

    /**
     * Checks a modeled {@code TagList}: each {@code Tag} requires {@code Key} (1-128 chars) and
     * {@code Value} (0-256 chars). Returns {@code null} for an absent list. Called before a
     * create allocates anything, so an invalid tag never leaves a resource behind.
     *
     * <p>{@code InvalidTagException} is modeled only on {@code TagResource}; the create
     * operations reject with their own parameter error (see {@link #INVALID_PARAMETER}).</p>
     */
    private JsonNode validatedTags(JsonNode tags, String errorCode) {
        if (tags == null || tags.isNull()) {
            return null;
        }
        if (!tags.isArray()) {
            throw new AwsException(errorCode, "Tags must be a list", 400);
        }
        requireAtMostPerRequest(tags, "Tags", errorCode);
        for (JsonNode tag : tags) {
            if (!tag.path("Key").isTextual() || !tag.path("Value").isTextual()) {
                throw new AwsException(errorCode, "Each tag needs a string Key and a string Value.", 400);
            }
            String key = text(tag, "Key");
            String value = text(tag, "Value");
            if (key == null || key.isEmpty() || key.length() > 128 || value == null || value.length() > 256) {
                throw new AwsException(errorCode,
                        "Each tag needs a Key of 1-128 characters and a Value of at most 256 characters.", 400);
            }
        }
        return tags;
    }

    /**
     * The model caps {@code TagList} and {@code TagKeyList} at 200 entries per request. That is
     * the only tag-count limit the model states; a per-resource total is not modeled, so none
     * is enforced.
     */
    private static void requireAtMostPerRequest(JsonNode list, String name, String errorCode) {
        if (list.isArray() && list.size() > 200) {
            throw new AwsException(errorCode, name + " must contain at most 200 entries", 400);
        }
    }

    private void putTags(ObjectNode resource, JsonNode tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        String arn = text(resource, "Arn");
        ObjectNode stored = tagStore.get(arn).orElseGet(objectMapper::createObjectNode);
        tags.forEach(tag -> stored.put(text(tag, "Key"), text(tag, "Value")));
        tagStore.put(arn, stored);
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
    private Optional<ObjectNode> replayOf(StorageBackend<String, ObjectNode> store, JsonNode request,
                                                    String region) {
        String creatorRequestId = text(request, "CreatorRequestId");
        if (creatorRequestId == null || creatorRequestId.isBlank()) {
            return Optional.empty();
        }
        return store.scan(key -> true).stream()
                .filter(existing -> creatorRequestId.equals(text(existing, "CreatorRequestId")))
                .filter(existing -> resolverArnIn(text(existing, "Arn"), region) != null)
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
        // The store's member name is deliberately left as IpAddressRequests: it is internal
        // state, and renaming it would make every record written by an earlier build read as
        // absent, which this method reports as a replay conflict.
        JsonNode recorded = endpointIpRequestStore.get(text(existing, "Id"))
                .map(node -> node.get("IpAddressRequests"))
                .orElse(null);
        // Both sides are re-normalised here rather than trusting the stored order, so a record
        // written before the ordering was corrected still compares as equal.
        if (recorded == null
                || !normalizedIpRequests(recorded).equals(normalizedIpRequests(ipAddresses))) {
            throw replayConflict(request, existing, "IpAddresses");
        }
    }

    /**
     * The IP addresses a {@code CreateResolverEndpoint} request carries.
     *
     * <p>AWS names this member {@code IpAddresses}; its list shape is
     * {@code IpAddressesRequest} and each element is an {@code IpAddressRequest}, which
     * is the name the wrong wire spelling came from. {@code IpAddressRequests} is still
     * accepted so anything written against the emulator's earlier behaviour keeps
     * working, but it is undocumented and the error message names only the AWS member.</p>
     */
    private static JsonNode ipAddressesOf(JsonNode request) {
        JsonNode aws = request.path("IpAddresses");
        return aws.isMissingNode() || aws.isNull() ? request.path("IpAddressRequests") : aws;
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
        List<JsonNode> entries = new ArrayList<>();
        ipAddresses.forEach(entries::add);
        entries.sort(Comparator.comparing(Route53ResolverService::canonicalKey));
        ArrayNode normalized = objectMapper.createArrayNode();
        entries.forEach(normalized::add);
        return normalized;
    }

    /** A node's contents as a string that does not depend on the order its members were written in. */
    private static String canonicalKey(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
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
        String arn = AwsArnUtils.Arn.of("route53resolver", region, "", "firewall-domain-list/" + id).toString();
        return new FirewallDomainList(id, arn, name, MANAGED_OWNER_NAME);
    }

    private static String deterministicHex(String seed, int length) {
        return Digests.sha256Hex(seed).substring(0, length);
    }
}
