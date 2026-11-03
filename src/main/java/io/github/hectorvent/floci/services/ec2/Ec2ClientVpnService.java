package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.CidrCanonicalizer;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint.AuthorizationRule;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint.Route;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint.TargetNetwork;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.github.hectorvent.floci.services.ec2.Ec2Ids.randomHex;

/**
 * EC2 Client VPN: endpoints, their target network associations, authorization rules and routes, and
 * the client configuration export. A configuration registry: no VPN server runs, so clients cannot
 * connect. Associations, rules and routes settle at once in the state AWS reaches after its
 * transitional one.
 *
 * <p>An endpoint's target networks, authorization rules and routes are replaced, never changed in
 * place, so a describe reading them while another request writes sees one whole list or the other.
 * An association pins its subnet and VPC, as the network interface AWS places in the subnet does:
 * {@code DeleteSubnet} and {@code DeleteVpc} refuse them until it is disassociated.
 */
@ApplicationScoped
public class Ec2ClientVpnService implements VpcDependencyProvider {

    static final String ENDPOINT_PREFIX = "cvpn-endpoint-";
    private static final String ASSOCIATION_PREFIX = "cvpn-assoc-";
    private static final Pattern ENDPOINT_ID = Pattern.compile("cvpn-endpoint-[0-9a-f]{8,17}");
    private static final String ORIGIN_ASSOCIATE = "associate";
    private static final String ORIGIN_ADD_ROUTE = "add-route";
    private static final int DEFAULT_VPN_PORT = 443;
    private static final int DEFAULT_SESSION_TIMEOUT_HOURS = 24;

    private final StorageBackend<String, ClientVpnEndpoint> endpoints;
    private final Ec2Service ec2;
    private final AcmService acm;

    @Inject
    public Ec2ClientVpnService(StorageFactory storageFactory, Ec2Service ec2, AcmService acm) {
        this(storageFactory.create("ec2", "ec2-client-vpn-endpoints.json",
                new TypeReference<Map<String, ClientVpnEndpoint>>() {}), ec2, acm);
    }

    Ec2ClientVpnService(StorageBackend<String, ClientVpnEndpoint> endpoints, Ec2Service ec2, AcmService acm) {
        this.endpoints = endpoints;
        this.ec2 = ec2;
        this.acm = acm;
    }

    /** What CreateClientVpnEndpoint takes; null members take AWS's defaults. */
    public record EndpointRequest(String clientCidrBlock, String serverCertificateArn,
                                  List<ClientVpnEndpoint.Authentication> authentication,
                                  Boolean connectionLogEnabled, String connectionLogGroup,
                                  String connectionLogStream, List<String> dnsServers, String transportProtocol,
                                  Integer vpnPort, String description, Boolean splitTunnel,
                                  List<String> securityGroupIds, String vpcId, String selfServicePortal,
                                  Boolean clientConnectEnabled, String clientConnectLambdaFunctionArn,
                                  Integer sessionTimeoutHours, Boolean clientLoginBannerEnabled,
                                  String clientLoginBannerText, Boolean clientRouteEnforced,
                                  Boolean disconnectOnSessionTimeout, String clientToken) {}

    // ── Endpoints ──

    public synchronized ClientVpnEndpoint createEndpoint(String region, EndpointRequest request, List<Tag> tags) {
        if (request.clientToken() != null) {
            Optional<ClientVpnEndpoint> replay = all(region).stream()
                    .filter(endpoint -> request.clientToken().equals(endpoint.getClientToken()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (request.clientCidrBlock() == null || !Ipv4Cidrs.isIpv4(request.clientCidrBlock())) {
            throw invalid("ClientCidrBlock must be an IPv4 CIDR block.");
        }
        if (blank(request.serverCertificateArn())) {
            throw missing("ServerCertificateArn");
        }
        if (request.authentication() == null || request.authentication().isEmpty()) {
            throw missing("Authentication");
        }
        if (request.connectionLogEnabled() == null) {
            throw missing("ConnectionLogOptions.Enabled");
        }
        String transport = Objects.requireNonNullElse(request.transportProtocol(), "udp");
        if (!transport.equals("udp") && !transport.equals("tcp")) {
            throw invalid("TransportProtocol must be udp or tcp.");
        }
        List<String> securityGroups = request.securityGroupIds() == null ? List.of() : request.securityGroupIds();
        if (!securityGroups.isEmpty() && blank(request.vpcId())) {
            throw invalid("VpcId is required when SecurityGroupIds are specified.");
        }
        String vpcId = request.vpcId();
        if (vpcId != null) {
            ec2.requireVpc(region, vpcId);
            securityGroups = securityGroupsOf(region, vpcId, securityGroups);
        }

        ClientVpnEndpoint endpoint = new ClientVpnEndpoint();
        endpoint.setClientVpnEndpointId(ENDPOINT_PREFIX + randomHex(17));
        endpoint.setDescription(request.description());
        endpoint.setCreationTime(Instant.now());
        endpoint.setClientCidrBlock(request.clientCidrBlock());
        endpoint.setServerCertificateArn(request.serverCertificateArn());
        endpoint.setAuthentication(new ArrayList<>(request.authentication()));
        endpoint.setConnectionLogEnabled(request.connectionLogEnabled());
        endpoint.setConnectionLogGroup(request.connectionLogGroup());
        endpoint.setConnectionLogStream(request.connectionLogStream());
        endpoint.setDnsServers(request.dnsServers() == null ? null : new ArrayList<>(request.dnsServers()));
        endpoint.setTransportProtocol(transport);
        endpoint.setVpnPort(Objects.requireNonNullElse(request.vpnPort(), DEFAULT_VPN_PORT));
        endpoint.setSplitTunnel(Boolean.TRUE.equals(request.splitTunnel()));
        endpoint.setVpcId(vpcId);
        endpoint.setSecurityGroupIds(new ArrayList<>(securityGroups));
        endpoint.setSelfServicePortal(Objects.requireNonNullElse(request.selfServicePortal(), "disabled"));
        endpoint.setClientConnectEnabled(Boolean.TRUE.equals(request.clientConnectEnabled()));
        endpoint.setClientConnectLambdaFunctionArn(request.clientConnectLambdaFunctionArn());
        endpoint.setSessionTimeoutHours(Objects.requireNonNullElse(request.sessionTimeoutHours(),
                DEFAULT_SESSION_TIMEOUT_HOURS));
        endpoint.setClientLoginBannerEnabled(Boolean.TRUE.equals(request.clientLoginBannerEnabled()));
        endpoint.setClientLoginBannerText(request.clientLoginBannerText());
        endpoint.setClientRouteEnforced(Boolean.TRUE.equals(request.clientRouteEnforced()));
        endpoint.setDisconnectOnSessionTimeout(Boolean.TRUE.equals(request.disconnectOnSessionTimeout()));
        endpoint.setEndpointIpAddressType("ipv4");
        endpoint.setTrafficIpAddressType("ipv4");
        endpoint.setClientToken(request.clientToken());
        endpoints.put(key(region, endpoint.getClientVpnEndpointId()), endpoint);
        if (tags != null && !tags.isEmpty()) {
            ec2.createTags(region, List.of(endpoint.getClientVpnEndpointId()), tags);
        }
        return endpoint;
    }

    /** The endpoints the ids name, or all of them; an unknown or malformed id is refused, as AWS does. */
    public synchronized List<ClientVpnEndpoint> describeEndpoints(String region, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return all(region);
        }
        List<ClientVpnEndpoint> found = new ArrayList<>();
        for (String id : ids) {
            found.add(require(region, id));
        }
        return found;
    }

    /** What ModifyClientVpnEndpoint changes; null members are left as they are. */
    public record EndpointChange(String serverCertificateArn, Boolean connectionLogEnabled, String connectionLogGroup,
                                 String connectionLogStream, Boolean dnsServersEnabled, List<String> dnsServers,
                                 Integer vpnPort, String description, Boolean splitTunnel,
                                 List<String> securityGroupIds, String vpcId, String selfServicePortal,
                                 Boolean clientConnectEnabled, String clientConnectLambdaFunctionArn,
                                 Integer sessionTimeoutHours, Boolean clientLoginBannerEnabled,
                                 String clientLoginBannerText, Boolean clientRouteEnforced,
                                 Boolean disconnectOnSessionTimeout) {}

    public synchronized void modifyEndpoint(String region, String id, EndpointChange change) {
        ClientVpnEndpoint endpoint = require(region, id);
        String vpcId = change.vpcId() != null ? change.vpcId() : endpoint.getVpcId();
        if (change.vpcId() != null && !change.vpcId().equals(endpoint.getVpcId())) {
            ec2.requireVpc(region, change.vpcId());
            if (!endpoint.getTargetNetworks().isEmpty()) {
                throw invalid("The VPC of an endpoint with associated target networks cannot be changed.");
            }
        }
        List<String> securityGroups = null;
        if (change.securityGroupIds() != null || change.vpcId() != null) {
            if (vpcId == null) {
                throw invalid("VpcId is required when SecurityGroupIds are specified.");
            }
            securityGroups = securityGroupsOf(region, vpcId,
                    change.securityGroupIds() != null ? change.securityGroupIds() : List.of());
        }
        if (change.serverCertificateArn() != null) {
            endpoint.setServerCertificateArn(change.serverCertificateArn());
        }
        if (change.connectionLogEnabled() != null) {
            endpoint.setConnectionLogEnabled(change.connectionLogEnabled());
            endpoint.setConnectionLogGroup(change.connectionLogGroup());
            endpoint.setConnectionLogStream(change.connectionLogStream());
        }
        if (Boolean.FALSE.equals(change.dnsServersEnabled())) {
            endpoint.setDnsServers(null);
        } else if (change.dnsServers() != null) {
            endpoint.setDnsServers(new ArrayList<>(change.dnsServers()));
        }
        if (change.vpnPort() != null) {
            endpoint.setVpnPort(change.vpnPort());
        }
        if (change.description() != null) {
            endpoint.setDescription(change.description());
        }
        if (change.splitTunnel() != null) {
            endpoint.setSplitTunnel(change.splitTunnel());
        }
        if (securityGroups != null) {
            endpoint.setVpcId(vpcId);
            endpoint.setSecurityGroupIds(new ArrayList<>(securityGroups));
        }
        if (change.selfServicePortal() != null) {
            endpoint.setSelfServicePortal(change.selfServicePortal());
        }
        if (change.clientConnectEnabled() != null) {
            endpoint.setClientConnectEnabled(change.clientConnectEnabled());
            endpoint.setClientConnectLambdaFunctionArn(change.clientConnectLambdaFunctionArn());
        }
        if (change.sessionTimeoutHours() != null) {
            endpoint.setSessionTimeoutHours(change.sessionTimeoutHours());
        }
        if (change.clientLoginBannerEnabled() != null) {
            endpoint.setClientLoginBannerEnabled(change.clientLoginBannerEnabled());
            endpoint.setClientLoginBannerText(change.clientLoginBannerText());
        }
        if (change.clientRouteEnforced() != null) {
            endpoint.setClientRouteEnforced(change.clientRouteEnforced());
        }
        if (change.disconnectOnSessionTimeout() != null) {
            endpoint.setDisconnectOnSessionTimeout(change.disconnectOnSessionTimeout());
        }
        endpoints.put(key(region, id), endpoint);
    }

    /** Refused while target networks are associated, as AWS requires them disassociated first. */
    public synchronized void deleteEndpoint(String region, String id) {
        ClientVpnEndpoint endpoint = require(region, id);
        if (!endpoint.getTargetNetworks().isEmpty()) {
            throw new AwsException("IncorrectState",
                    "Client VPN endpoint " + id + " has associated target networks; disassociate them first.", 400);
        }
        endpoints.delete(key(region, id));
        ec2.deleteTags(region, List.of(id), List.of());
    }

    /** {@code pending-associate} until a target network is associated, then {@code available}. */
    public static String statusOf(ClientVpnEndpoint endpoint) {
        return endpoint.getTargetNetworks().isEmpty() ? "pending-associate" : "available";
    }

    /** {@code *.cvpn-endpoint-<id>.prod.clientvpn.<region>.<dns suffix>}, as AWS names it. */
    public static String dnsNameOf(ClientVpnEndpoint endpoint, String region) {
        return "*." + endpoint.getClientVpnEndpointId() + ".prod.clientvpn." + region + "."
                + AwsRegions.dnsSuffixFor(region);
    }

    // ── Target networks ──

    /**
     * Associates a subnet of the endpoint's VPC (or, for an endpoint without one, sets the VPC and its
     * default security group), one per Availability Zone, and adds the route to the VPC through it.
     */
    public synchronized TargetNetwork associate(String region, String id, String subnetId, String clientToken) {
        // Held while the subnet is checked and the association saved, so a DeleteSubnet cannot pass its
        // dependency check in between.
        return ec2.supplyWithSubnetReferencesHeld(region, () -> associateWithSubnetsHeld(region, id, subnetId,
                clientToken));
    }

    private TargetNetwork associateWithSubnetsHeld(String region, String id, String subnetId, String clientToken) {
        ClientVpnEndpoint endpoint = require(region, id);
        if (clientToken != null) {
            Optional<TargetNetwork> replay = endpoint.getTargetNetworks().stream()
                    .filter(network -> clientToken.equals(network.getClientToken())).findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (blank(subnetId)) {
            throw missing("SubnetId");
        }
        Subnet subnet = ec2.requireSubnet(region, subnetId);
        if (endpoint.getVpcId() != null && !endpoint.getVpcId().equals(subnet.getVpcId())) {
            throw invalid("Subnet " + subnetId + " is not in the endpoint's VPC " + endpoint.getVpcId() + ".");
        }
        for (TargetNetwork network : endpoint.getTargetNetworks()) {
            if (network.getSubnetId().equals(subnetId)) {
                throw invalid("Subnet " + subnetId + " is already associated with " + id + ".");
            }
            if (Objects.equals(network.getAvailabilityZone(), subnet.getAvailabilityZone())) {
                throw invalid("A subnet in Availability Zone " + subnet.getAvailabilityZone()
                        + " is already associated with " + id + ".");
            }
        }
        if (endpoint.getVpcId() == null) {
            endpoint.setVpcId(subnet.getVpcId());
            endpoint.setSecurityGroupIds(new ArrayList<>(securityGroupsOf(region, subnet.getVpcId(), List.of())));
        }
        TargetNetwork network = new TargetNetwork();
        network.setAssociationId(ASSOCIATION_PREFIX + randomHex(17));
        network.setSubnetId(subnetId);
        network.setVpcId(subnet.getVpcId());
        network.setAvailabilityZone(subnet.getAvailabilityZone());
        network.setClientToken(clientToken);
        endpoint.setTargetNetworks(with(endpoint.getTargetNetworks(), network));

        Route route = new Route();
        route.setDestinationCidr(ec2.requireVpc(region, subnet.getVpcId()).getCidrBlock());
        route.setTargetSubnet(subnetId);
        route.setOrigin(ORIGIN_ASSOCIATE);
        route.setDescription("Default Route");
        endpoint.setRoutes(with(endpoint.getRoutes(), route));
        endpoints.put(key(region, id), endpoint);
        return network;
    }

    public synchronized List<TargetNetwork> describeTargetNetworks(String region, String id, List<String> associationIds) {
        ClientVpnEndpoint endpoint = require(region, id);
        if (associationIds == null || associationIds.isEmpty()) {
            return endpoint.getTargetNetworks();
        }
        return endpoint.getTargetNetworks().stream()
                .filter(network -> associationIds.contains(network.getAssociationId())).toList();
    }

    /** Removes the association and every route through its subnet. */
    public synchronized TargetNetwork disassociate(String region, String id, String associationId) {
        ClientVpnEndpoint endpoint = require(region, id);
        TargetNetwork network = endpoint.getTargetNetworks().stream()
                .filter(candidate -> candidate.getAssociationId().equals(associationId)).findFirst()
                .orElseThrow(() -> new AwsException("InvalidClientVpnAssociationId.NotFound",
                        "Association " + associationId + " does not exist", 400));
        endpoint.setTargetNetworks(without(endpoint.getTargetNetworks(), candidate -> candidate == network));
        endpoint.setRoutes(without(endpoint.getRoutes(),
                route -> network.getSubnetId().equals(route.getTargetSubnet())));
        endpoints.put(key(region, id), endpoint);
        return network;
    }

    /** Sets the security groups the endpoint applies in its VPC, for every associated network. */
    public synchronized List<String> applySecurityGroups(String region, String id, String vpcId,
                                                         List<String> securityGroupIds) {
        ClientVpnEndpoint endpoint = require(region, id);
        if (blank(vpcId)) {
            throw missing("VpcId");
        }
        if (securityGroupIds == null || securityGroupIds.isEmpty()) {
            throw missing("SecurityGroupId");
        }
        if (endpoint.getVpcId() != null && !endpoint.getVpcId().equals(vpcId)) {
            throw invalid("VPC " + vpcId + " is not the endpoint's VPC " + endpoint.getVpcId() + ".");
        }
        List<String> groups = securityGroupsOf(region, vpcId, securityGroupIds);
        endpoint.setVpcId(vpcId);
        endpoint.setSecurityGroupIds(new ArrayList<>(groups));
        endpoints.put(key(region, id), endpoint);
        return groups;
    }

    // ── Authorization rules ──

    public synchronized void authorize(String region, String id, String targetNetworkCidr, String accessGroupId,
                                       Boolean authorizeAllGroups, String description) {
        ClientVpnEndpoint endpoint = require(region, id);
        requireCidr(targetNetworkCidr, "TargetNetworkCidr");
        boolean all = Boolean.TRUE.equals(authorizeAllGroups);
        if (all == !blank(accessGroupId)) {
            throw invalid("Specify either AccessGroupId or AuthorizeAllGroups.");
        }
        boolean exists = endpoint.getAuthorizationRules().stream()
                .anyMatch(rule -> CidrCanonicalizer.sameBlock(rule.getDestinationCidr(), targetNetworkCidr)
                        && rule.isAccessAll() == all && Objects.equals(rule.getGroupId(), all ? "" : accessGroupId));
        if (exists) {
            throw invalid("An authorization rule for " + targetNetworkCidr + " already exists.");
        }
        AuthorizationRule rule = new AuthorizationRule();
        rule.setDestinationCidr(targetNetworkCidr);
        rule.setGroupId(all ? "" : accessGroupId);
        rule.setAccessAll(all);
        rule.setDescription(description);
        endpoint.setAuthorizationRules(with(endpoint.getAuthorizationRules(), rule));
        endpoints.put(key(region, id), endpoint);
    }

    public synchronized List<AuthorizationRule> describeAuthorizationRules(String region, String id) {
        return require(region, id).getAuthorizationRules();
    }

    public synchronized void revoke(String region, String id, String targetNetworkCidr, String accessGroupId,
                                    Boolean revokeAllGroups) {
        ClientVpnEndpoint endpoint = require(region, id);
        requireCidr(targetNetworkCidr, "TargetNetworkCidr");
        boolean all = Boolean.TRUE.equals(revokeAllGroups);
        List<AuthorizationRule> kept = without(endpoint.getAuthorizationRules(), rule ->
                CidrCanonicalizer.sameBlock(rule.getDestinationCidr(), targetNetworkCidr)
                        && (all ? rule.isAccessAll() : Objects.equals(rule.getGroupId(), accessGroupId)));
        if (kept.size() == endpoint.getAuthorizationRules().size()) {
            throw new AwsException("InvalidClientVpnEndpointAuthorizationRuleNotFound",
                    "Authorization rule for " + targetNetworkCidr + " does not exist", 400);
        }
        endpoint.setAuthorizationRules(kept);
        endpoints.put(key(region, id), endpoint);
    }

    // ── Routes ──

    /** Adds a route through an associated subnet, or {@code local}. */
    public synchronized void createRoute(String region, String id, String destinationCidr, String targetSubnet,
                                         String description) {
        ClientVpnEndpoint endpoint = require(region, id);
        requireCidr(destinationCidr, "DestinationCidrBlock");
        if (blank(targetSubnet)) {
            throw missing("TargetVpcSubnetId");
        }
        boolean associated = "local".equals(targetSubnet) || endpoint.getTargetNetworks().stream()
                .anyMatch(network -> network.getSubnetId().equals(targetSubnet));
        if (!associated) {
            throw invalid("Subnet " + targetSubnet + " is not associated with " + id + ".");
        }
        boolean exists = endpoint.getRoutes().stream()
                .anyMatch(route -> CidrCanonicalizer.sameBlock(route.getDestinationCidr(), destinationCidr)
                        && route.getTargetSubnet().equals(targetSubnet));
        if (exists) {
            throw new AwsException("InvalidClientVpnDuplicateRoute",
                    "Route " + destinationCidr + " through " + targetSubnet + " already exists", 400);
        }
        Route route = new Route();
        route.setDestinationCidr(destinationCidr);
        route.setTargetSubnet(targetSubnet);
        route.setOrigin(ORIGIN_ADD_ROUTE);
        route.setDescription(description);
        endpoint.setRoutes(with(endpoint.getRoutes(), route));
        endpoints.put(key(region, id), endpoint);
    }

    public synchronized List<Route> describeRoutes(String region, String id) {
        return require(region, id).getRoutes();
    }

    /** Removes the matching routes; a route an association added goes with the association only. */
    public synchronized void deleteRoute(String region, String id, String destinationCidr, String targetSubnet) {
        ClientVpnEndpoint endpoint = require(region, id);
        requireCidr(destinationCidr, "DestinationCidrBlock");
        List<Route> kept = without(endpoint.getRoutes(), route -> ORIGIN_ADD_ROUTE.equals(route.getOrigin())
                && CidrCanonicalizer.sameBlock(route.getDestinationCidr(), destinationCidr)
                && (targetSubnet == null || route.getTargetSubnet().equals(targetSubnet)));
        if (kept.size() == endpoint.getRoutes().size()) {
            throw new AwsException("InvalidClientVpnRouteNotFound",
                    "Route " + destinationCidr + " does not exist", 400);
        }
        endpoint.setRoutes(kept);
        endpoints.put(key(region, id), endpoint);
    }

    // ── Client configuration ──

    /**
     * The OpenVPN configuration AWS exports: the endpoint's address and port, its transport, the
     * server certificate's chain as the CA, and its common name to verify. The chain and name come
     * from the server certificate in ACM; a certificate ACM does not hold leaves them out.
     */
    public String exportClientConfiguration(String region, String id) {
        ClientVpnEndpoint endpoint = require(region, id);
        Optional<Certificate> certificate = serverCertificate(endpoint, region);
        StringBuilder config = new StringBuilder()
                .append("client\n")
                .append("dev tun\n")
                .append("proto ").append(endpoint.getTransportProtocol()).append('\n')
                .append("remote ").append(dnsNameOf(endpoint, region).substring(2)).append(' ')
                .append(endpoint.getVpnPort()).append('\n')
                .append("remote-random-hostname\n")
                .append("resolv-retry infinite\n")
                .append("nobind\n")
                .append("remote-cert-tls server\n")
                .append("cipher AES-256-GCM\n")
                .append("verb 3\n");
        certificate.map(Certificate::getCertificateChain).filter(chain -> !chain.isBlank())
                .ifPresent(chain -> config.append("<ca>\n").append(chain.strip()).append("\n\n</ca>\n\n\n"));
        config.append("reneg-sec 0\n\n");
        certificate.map(Certificate::getDomainName).filter(name -> !name.isBlank())
                .ifPresent(name -> config.append("verify-x509-name ").append(name).append(" name\n\n"));
        return config.toString();
    }

    private Optional<Certificate> serverCertificate(ClientVpnEndpoint endpoint, String region) {
        try {
            return Optional.ofNullable(acm.getCertificate(endpoint.getServerCertificateArn(), region));
        } catch (AwsException expected) {
            // A certificate ACM does not hold: the export goes without the CA. Anything else surfaces.
            if (!"ResourceNotFoundException".equals(expected.getErrorCode())) {
                throw expected;
            }
            return Optional.empty();
        }
    }

    // ── Helpers ──

    /** The endpoint an id names: AWS's error for a malformed id and for an unknown one. */
    public ClientVpnEndpoint require(String region, String id) {
        if (id == null || !ENDPOINT_ID.matcher(id).matches()) {
            throw invalid("ClientVpnEndpointId you provided is not valid");
        }
        return endpoints.get(key(region, id)).orElseThrow(() -> new AwsException(
                "InvalidClientVpnEndpointId.NotFound", "Endpoint " + id + " does not exist", 400));
    }

    /** The groups given, each of the VPC; none given means the VPC's default security group. */
    private List<String> securityGroupsOf(String region, String vpcId, List<String> requested) {
        if (requested.isEmpty()) {
            return ec2.defaultSecurityGroupOf(region, vpcId).map(SecurityGroup::getGroupId)
                    .map(List::of).orElse(List.of());
        }
        List<SecurityGroup> found = ec2.describeSecurityGroups(region, requested, List.of(), Map.of());
        for (String groupId : requested) {
            SecurityGroup group = found.stream().filter(candidate -> groupId.equals(candidate.getGroupId()))
                    .findFirst().orElseThrow(() -> new AwsException("InvalidGroup.NotFound",
                            "The security group '" + groupId + "' does not exist", 400));
            if (!vpcId.equals(group.getVpcId())) {
                throw invalid("Security group " + groupId + " is not in VPC " + vpcId + ".");
            }
        }
        return List.copyOf(requested);
    }

    // ── Subnet and VPC references ──

    /** A plain read over the stored endpoints, taking no lock of this service, as the interface requires. */
    @Override
    public boolean hasSubnetDependency(String region, String subnetId) {
        return associations(region).anyMatch(network -> subnetId.equals(network.getSubnetId()));
    }

    @Override
    public boolean hasVpcDependency(String region, String vpcId) {
        return associations(region).anyMatch(network -> vpcId.equals(network.getVpcId()));
    }

    private Stream<TargetNetwork> associations(String region) {
        return endpoints.scan(key -> key.startsWith(region + "::")).stream()
                .flatMap(endpoint -> endpoint.getTargetNetworks().stream());
    }

    private static <T> List<T> with(List<T> list, T item) {
        List<T> copy = new ArrayList<>(list);
        copy.add(item);
        return copy;
    }

    private static <T> List<T> without(List<T> list, Predicate<T> drop) {
        return new ArrayList<>(list.stream().filter(drop.negate()).toList());
    }

    private List<ClientVpnEndpoint> all(String region) {
        return endpoints.scan(key -> key.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(ClientVpnEndpoint::getClientVpnEndpointId)).toList();
    }

    private static void requireCidr(String cidr, String member) {
        if (blank(cidr)) {
            throw missing(member);
        }
        if (!Ipv4Cidrs.isIpv4(cidr)) {
            throw invalid(member + " must be an IPv4 CIDR block.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValue", message, 400);
    }

    private static AwsException missing(String member) {
        return new AwsException("MissingParameter", "The request must contain the parameter " + member, 400);
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }
}
