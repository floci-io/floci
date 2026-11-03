package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint.AuthorizationRule;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint.Route;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint.TargetNetwork;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * The wire shapes of the Client VPN operations, reached through {@link Ec2QueryHandler}: query
 * members as the EC2 protocol names them ({@code Authentication.N}, {@code SecurityGroupId.N}),
 * responses as AWS answers them, including the members its responses carry beyond the model
 * ({@code routeAction}, {@code IncludeAuthorizationPolicyContext}).
 */
class Ec2ClientVpnHandler {

    /** AWS reports an endpoint's creation time without a zone or fraction. */
    private static final DateTimeFormatter CREATION_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final Set<String> ENDPOINT_FILTERS = Set.of("endpoint-id", "transport-protocol", "tag-key");
    private static final Set<String> TARGET_NETWORK_FILTERS = Set.of("association-id", "target-network-id", "vpc-id");
    private static final Set<String> AUTHORIZATION_RULE_FILTERS = Set.of("description", "destination-cidr", "group-id");
    private static final Set<String> ROUTE_FILTERS = Set.of("destination-cidr", "origin", "target-subnet");

    private final Ec2ClientVpnService clientVpn;
    private final Ec2Service ec2;

    Ec2ClientVpnHandler(Ec2ClientVpnService clientVpn, Ec2Service ec2) {
        this.clientVpn = clientVpn;
        this.ec2 = ec2;
    }

    Response create(MultivaluedMap<String, String> p, String region, List<Tag> tags) {
        Ec2ClientVpnService.EndpointRequest request = new Ec2ClientVpnService.EndpointRequest(
                p.getFirst("ClientCidrBlock"), p.getFirst("ServerCertificateArn"), authentication(p),
                bool(p, "ConnectionLogOptions.Enabled"), p.getFirst("ConnectionLogOptions.CloudwatchLogGroup"),
                p.getFirst("ConnectionLogOptions.CloudwatchLogStream"), listOrNull(p, "DnsServers"),
                p.getFirst("TransportProtocol"), integer(p, "VpnPort"), p.getFirst("Description"),
                bool(p, "SplitTunnel"), list(p, "SecurityGroupId"), p.getFirst("VpcId"),
                p.getFirst("SelfServicePortal"), bool(p, "ClientConnectOptions.Enabled"),
                p.getFirst("ClientConnectOptions.LambdaFunctionArn"), integer(p, "SessionTimeoutHours"),
                bool(p, "ClientLoginBannerOptions.Enabled"), p.getFirst("ClientLoginBannerOptions.BannerText"),
                bool(p, "ClientRouteEnforcementOptions.Enforced"), bool(p, "DisconnectOnSessionTimeout"),
                p.getFirst("ClientToken"));
        ClientVpnEndpoint endpoint = clientVpn.createEndpoint(region, request, tags);
        XmlBuilder xml = response("CreateClientVpnEndpointResponse")
                .elem("clientVpnEndpointId", endpoint.getClientVpnEndpointId())
                .start("status").elem("code", Ec2ClientVpnService.statusOf(endpoint)).end("status")
                .elem("dnsName", Ec2ClientVpnService.dnsNameOf(endpoint, region));
        return done(xml, "CreateClientVpnEndpointResponse");
    }

    Response describe(MultivaluedMap<String, String> p, String region) {
        List<Map.Entry<String, List<String>>> filters = filters(p, ENDPOINT_FILTERS);
        List<ClientVpnEndpoint> endpoints = clientVpn.describeEndpoints(region, list(p, "ClientVpnEndpointId"));
        XmlBuilder xml = response("DescribeClientVpnEndpointsResponse").start("clientVpnEndpoint");
        for (ClientVpnEndpoint endpoint : endpoints) {
            List<Tag> tags = ec2.resourceTags(region, endpoint.getClientVpnEndpointId());
            boolean matches = matches(filters, (name, values) -> switch (name) {
                case "endpoint-id" -> Ec2Service.matchesFilterValue(values, endpoint.getClientVpnEndpointId());
                case "transport-protocol" -> Ec2Service.matchesFilterValue(values, endpoint.getTransportProtocol());
                default -> Ec2Service.matchesTagFilter(tags, name, values, Ec2Service::matchesFilterValue);
            });
            if (!matches) {
                continue;
            }
            endpoint(xml, endpoint, region);
        }
        xml.end("clientVpnEndpoint");
        return done(xml, "DescribeClientVpnEndpointsResponse");
    }

    Response modify(MultivaluedMap<String, String> p, String region) {
        Ec2ClientVpnService.EndpointChange change = new Ec2ClientVpnService.EndpointChange(
                p.getFirst("ServerCertificateArn"), bool(p, "ConnectionLogOptions.Enabled"),
                p.getFirst("ConnectionLogOptions.CloudwatchLogGroup"),
                p.getFirst("ConnectionLogOptions.CloudwatchLogStream"), bool(p, "DnsServers.Enabled"),
                listOrNull(p, "DnsServers.CustomDnsServers"), integer(p, "VpnPort"), p.getFirst("Description"),
                bool(p, "SplitTunnel"), listOrNull(p, "SecurityGroupId"), p.getFirst("VpcId"),
                p.getFirst("SelfServicePortal"), bool(p, "ClientConnectOptions.Enabled"),
                p.getFirst("ClientConnectOptions.LambdaFunctionArn"), integer(p, "SessionTimeoutHours"),
                bool(p, "ClientLoginBannerOptions.Enabled"), p.getFirst("ClientLoginBannerOptions.BannerText"),
                bool(p, "ClientRouteEnforcementOptions.Enforced"), bool(p, "DisconnectOnSessionTimeout"));
        clientVpn.modifyEndpoint(region, p.getFirst("ClientVpnEndpointId"), change);
        return done(response("ModifyClientVpnEndpointResponse").elem("return", true),
                "ModifyClientVpnEndpointResponse");
    }

    Response delete(MultivaluedMap<String, String> p, String region) {
        clientVpn.deleteEndpoint(region, p.getFirst("ClientVpnEndpointId"));
        return done(status(response("DeleteClientVpnEndpointResponse"), "deleting"),
                "DeleteClientVpnEndpointResponse");
    }

    Response associate(MultivaluedMap<String, String> p, String region) {
        TargetNetwork network = clientVpn.associate(region, p.getFirst("ClientVpnEndpointId"),
                p.getFirst("SubnetId"), p.getFirst("ClientToken"));
        XmlBuilder xml = response("AssociateClientVpnTargetNetworkResponse")
                .elem("associationId", network.getAssociationId());
        return done(status(xml, "associating"), "AssociateClientVpnTargetNetworkResponse");
    }

    Response describeTargetNetworks(MultivaluedMap<String, String> p, String region) {
        String id = p.getFirst("ClientVpnEndpointId");
        List<Map.Entry<String, List<String>>> filters = filters(p, TARGET_NETWORK_FILTERS);
        List<TargetNetwork> networks = clientVpn.describeTargetNetworks(region, id, list(p, "AssociationIds"));
        List<String> securityGroups = clientVpn.require(region, id).getSecurityGroupIds();
        XmlBuilder xml = response("DescribeClientVpnTargetNetworksResponse").start("clientVpnTargetNetworks");
        for (TargetNetwork network : networks) {
            boolean matches = matches(filters, (name, values) -> Ec2Service.matchesFilterValue(values,
                    switch (name) {
                        case "association-id" -> network.getAssociationId();
                        case "target-network-id" -> network.getSubnetId();
                        default -> network.getVpcId();
                    }));
            if (!matches) {
                continue;
            }
            xml.start("item")
                    .elem("associationId", network.getAssociationId())
                    .elem("clientVpnEndpointId", id)
                    .elem("targetNetworkId", network.getSubnetId());
            items(xml, "securityGroups", securityGroups);
            status(xml, "associated")
                    .elem("vpcId", network.getVpcId())
                    .end("item");
        }
        xml.end("clientVpnTargetNetworks");
        return done(xml, "DescribeClientVpnTargetNetworksResponse");
    }

    Response disassociate(MultivaluedMap<String, String> p, String region) {
        TargetNetwork network = clientVpn.disassociate(region, p.getFirst("ClientVpnEndpointId"),
                p.getFirst("AssociationId"));
        XmlBuilder xml = response("DisassociateClientVpnTargetNetworkResponse")
                .elem("associationId", network.getAssociationId());
        return done(status(xml, "disassociating"), "DisassociateClientVpnTargetNetworkResponse");
    }

    Response applySecurityGroups(MultivaluedMap<String, String> p, String region) {
        List<String> groups = clientVpn.applySecurityGroups(region, p.getFirst("ClientVpnEndpointId"),
                p.getFirst("VpcId"), list(p, "SecurityGroupId"));
        XmlBuilder xml = response("ApplySecurityGroupsToClientVpnTargetNetworkResponse");
        items(xml, "securityGroupIds", groups);
        return done(xml, "ApplySecurityGroupsToClientVpnTargetNetworkResponse");
    }

    Response authorize(MultivaluedMap<String, String> p, String region) {
        clientVpn.authorize(region, p.getFirst("ClientVpnEndpointId"), p.getFirst("TargetNetworkCidr"),
                p.getFirst("AccessGroupId"), bool(p, "AuthorizeAllGroups"), p.getFirst("Description"));
        return done(status(response("AuthorizeClientVpnIngressResponse"), "authorizing"),
                "AuthorizeClientVpnIngressResponse");
    }

    Response describeAuthorizationRules(MultivaluedMap<String, String> p, String region) {
        String id = p.getFirst("ClientVpnEndpointId");
        List<Map.Entry<String, List<String>>> filters = filters(p, AUTHORIZATION_RULE_FILTERS);
        XmlBuilder xml = response("DescribeClientVpnAuthorizationRulesResponse").start("authorizationRule");
        for (AuthorizationRule rule : clientVpn.describeAuthorizationRules(region, id)) {
            boolean matches = matches(filters, (name, values) -> Ec2Service.matchesFilterValue(values,
                    switch (name) {
                        case "description" -> rule.getDescription();
                        case "destination-cidr" -> rule.getDestinationCidr();
                        default -> rule.getGroupId();
                    }));
            if (!matches) {
                continue;
            }
            xml.start("item")
                    .elem("accessAll", rule.isAccessAll())
                    .elem("description", rule.getDescription())
                    .elem("destinationCidr", rule.getDestinationCidr())
                    .elem("clientVpnEndpointId", id);
            // An empty <groupId/> for a rule that applies to all groups, as AWS answers.
            if (rule.getGroupId() == null || rule.getGroupId().isEmpty()) {
                xml.raw("<groupId/>");
            } else {
                xml.elem("groupId", rule.getGroupId());
            }
            status(xml, "active").end("item");
        }
        xml.end("authorizationRule");
        return done(xml, "DescribeClientVpnAuthorizationRulesResponse");
    }

    Response revoke(MultivaluedMap<String, String> p, String region) {
        clientVpn.revoke(region, p.getFirst("ClientVpnEndpointId"), p.getFirst("TargetNetworkCidr"),
                p.getFirst("AccessGroupId"), bool(p, "RevokeAllGroups"));
        return done(status(response("RevokeClientVpnIngressResponse"), "revoking"), "RevokeClientVpnIngressResponse");
    }

    Response createRoute(MultivaluedMap<String, String> p, String region) {
        clientVpn.createRoute(region, p.getFirst("ClientVpnEndpointId"), p.getFirst("DestinationCidrBlock"),
                p.getFirst("TargetVpcSubnetId"), p.getFirst("Description"));
        return done(status(response("CreateClientVpnRouteResponse"), "creating"), "CreateClientVpnRouteResponse");
    }

    Response describeRoutes(MultivaluedMap<String, String> p, String region) {
        String id = p.getFirst("ClientVpnEndpointId");
        List<Map.Entry<String, List<String>>> filters = filters(p, ROUTE_FILTERS);
        XmlBuilder xml = response("DescribeClientVpnRoutesResponse").start("routes");
        for (Route route : clientVpn.describeRoutes(region, id)) {
            boolean matches = matches(filters, (name, values) -> Ec2Service.matchesFilterValue(values,
                    switch (name) {
                        case "destination-cidr" -> route.getDestinationCidr();
                        case "origin" -> route.getOrigin();
                        default -> route.getTargetSubnet();
                    }));
            if (!matches) {
                continue;
            }
            xml.start("item")
                    .elem("description", route.getDescription())
                    .elem("destinationCidr", route.getDestinationCidr())
                    .elem("clientVpnEndpointId", id)
                    .elem("origin", route.getOrigin())
                    .elem("routeAction", "include");
            status(xml, "active")
                    .elem("targetSubnet", route.getTargetSubnet())
                    .elem("type", "Nat")
                    .end("item");
        }
        xml.end("routes");
        return done(xml, "DescribeClientVpnRoutesResponse");
    }

    Response deleteRoute(MultivaluedMap<String, String> p, String region) {
        clientVpn.deleteRoute(region, p.getFirst("ClientVpnEndpointId"), p.getFirst("DestinationCidrBlock"),
                p.getFirst("TargetVpcSubnetId"));
        return done(status(response("DeleteClientVpnRouteResponse"), "deleting"), "DeleteClientVpnRouteResponse");
    }

    Response exportClientConfiguration(MultivaluedMap<String, String> p, String region) {
        String configuration = clientVpn.exportClientConfiguration(region, p.getFirst("ClientVpnEndpointId"));
        return done(response("ExportClientVpnClientConfigurationResponse")
                .elem("clientConfiguration", configuration), "ExportClientVpnClientConfigurationResponse");
    }

    /** One endpoint as DescribeClientVpnEndpoints lists it, members in AWS's order. */
    private void endpoint(XmlBuilder xml, ClientVpnEndpoint endpoint, String region) {
        String id = endpoint.getClientVpnEndpointId();
        xml.start("item").start("authenticationOptions");
        for (ClientVpnEndpoint.Authentication option : endpoint.getAuthentication()) {
            xml.start("item");
            if (option.getDirectoryId() != null) {
                xml.start("activeDirectory").elem("directoryId", option.getDirectoryId()).end("activeDirectory");
            }
            if (option.getSamlProviderArn() != null || option.getSelfServiceSamlProviderArn() != null) {
                xml.start("federatedAuthentication")
                        .elem("samlProviderArn", option.getSamlProviderArn())
                        .elem("selfServiceSamlProviderArn", option.getSelfServiceSamlProviderArn())
                        .end("federatedAuthentication");
            }
            if (option.getClientRootCertificateChain() != null) {
                xml.start("mutualAuthentication")
                        .elem("clientRootCertificateChain", option.getClientRootCertificateChain())
                        .end("mutualAuthentication");
            }
            xml.elem("type", option.getType()).end("item");
        }
        xml.end("authenticationOptions")
                .elem("clientCidrBlock", endpoint.getClientCidrBlock())
                .start("clientConnectOptions")
                .elem("enabled", endpoint.isClientConnectEnabled())
                .elem("lambdaFunctionArn", endpoint.getClientConnectLambdaFunctionArn())
                .start("status").elem("code", "applied").end("status")
                .end("clientConnectOptions")
                .start("clientLoginBannerOptions")
                .elem("enabled", endpoint.isClientLoginBannerEnabled())
                .elem("bannerText", endpoint.getClientLoginBannerText())
                .end("clientLoginBannerOptions")
                .start("clientRouteEnforcementOptions")
                .elem("enforced", endpoint.isClientRouteEnforced())
                .end("clientRouteEnforcementOptions")
                // AWS capitalizes these members on the wire, unlike the rest of the shape.
                .start("connectionLogOptions")
                .elem("Enabled", endpoint.isConnectionLogEnabled())
                .elem("CloudwatchLogGroup", endpoint.getConnectionLogGroup())
                .elem("CloudwatchLogStream", endpoint.getConnectionLogStream())
                .elem("IncludeAuthorizationPolicyContext", false)
                .end("connectionLogOptions")
                .elem("creationTime", CREATION_TIME.format(endpoint.getCreationTime()))
                .elem("description", endpoint.getDescription())
                .elem("disconnectOnSessionTimeout", endpoint.isDisconnectOnSessionTimeout())
                .elem("dnsName", Ec2ClientVpnService.dnsNameOf(endpoint, region));
        if (!endpoint.getDnsServers().isEmpty()) {
            items(xml, "dnsServer", endpoint.getDnsServers());
        }
        xml.elem("clientVpnEndpointId", id)
                .elem("endpointIpAddressType", endpoint.getEndpointIpAddressType());
        status(xml, Ec2ClientVpnService.statusOf(endpoint));
        items(xml, "securityGroupIdSet", endpoint.getSecurityGroupIds());
        // The model reports the portal only as its URL, present while the portal is enabled.
        if ("enabled".equals(endpoint.getSelfServicePortal())) {
            xml.elem("selfServicePortalUrl", "https://self-service.clientvpn." + AwsRegions.dnsSuffixFor(region)
                    + "/endpoints/" + id);
        }
        xml.elem("serverCertificateArn", endpoint.getServerCertificateArn())
                .elem("sessionTimeoutHours", endpoint.getSessionTimeoutHours())
                .elem("splitTunnel", endpoint.isSplitTunnel());
        tagSet(xml, ec2.resourceTags(region, id));
        xml.elem("trafficIpAddressType", endpoint.getTrafficIpAddressType())
                .elem("transportProtocol", endpoint.getTransportProtocol())
                .elem("vpcId", endpoint.getVpcId())
                .elem("vpnPort", endpoint.getVpnPort())
                .elem("vpnProtocol", "openvpn")
                .end("item");
    }

    /** {@code Authentication.N.*}, the members CreateClientVpnEndpoint takes its options in. */
    private static List<ClientVpnEndpoint.Authentication> authentication(MultivaluedMap<String, String> p) {
        List<ClientVpnEndpoint.Authentication> options = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = "Authentication." + i + ".";
            String type = p.getFirst(prefix + "Type");
            if (type == null) {
                break;
            }
            if (!List.of("certificate-authentication", "directory-service-authentication",
                    "federated-authentication").contains(type)) {
                throw new AwsException("InvalidParameterValue", "Authentication type " + type + " is not valid.", 400);
            }
            ClientVpnEndpoint.Authentication option = new ClientVpnEndpoint.Authentication();
            option.setType(type);
            option.setDirectoryId(p.getFirst(prefix + "ActiveDirectory.DirectoryId"));
            option.setClientRootCertificateChain(p.getFirst(prefix + "MutualAuthentication.ClientRootCertificateChainArn"));
            option.setSamlProviderArn(p.getFirst(prefix + "FederatedAuthentication.SAMLProviderArn"));
            option.setSelfServiceSamlProviderArn(
                    p.getFirst(prefix + "FederatedAuthentication.SelfServiceSAMLProviderArn"));
            options.add(option);
        }
        return options;
    }

    /**
     * The request's {@code Filter.N} entries, each name one the operation supports (a {@code tag:}
     * name where it takes {@code tag-key}); an unsupported name is refused as AWS refuses it.
     */
    private static List<Map.Entry<String, List<String>>> filters(MultivaluedMap<String, String> p,
                                                                Set<String> supported) {
        List<Map.Entry<String, List<String>>> filters = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = p.getFirst("Filter." + i + ".Name");
            if (name == null) {
                break;
            }
            filters.add(new AbstractMap.SimpleEntry<>(name, list(p, "Filter." + i + ".Value")));
        }
        Ec2Service.requireSupportedFilters(filters.stream().map(Map.Entry::getKey).toList(), supported);
        return filters;
    }

    /** Every filter matches: AND across filters, OR across one filter's values. */
    private static boolean matches(List<Map.Entry<String, List<String>>> filters,
                                   BiPredicate<String, List<String>> matcher) {
        return filters.stream().allMatch(filter -> matcher.test(filter.getKey(), filter.getValue()));
    }

    private static XmlBuilder response(String element) {
        return new XmlBuilder().start(element, AwsNamespaces.EC2).elem("requestId", UUID.randomUUID().toString());
    }

    private static Response done(XmlBuilder xml, String element) {
        return Response.ok(xml.end(element).build()).type(MediaType.APPLICATION_XML).build();
    }

    private static XmlBuilder status(XmlBuilder xml, String code) {
        return xml.start("status").elem("code", code).end("status");
    }

    private static void items(XmlBuilder xml, String element, List<String> values) {
        xml.start(element);
        values.forEach(value -> xml.elem("item", value));
        xml.end(element);
    }

    private static void tagSet(XmlBuilder xml, List<Tag> tags) {
        xml.start("tagSet");
        for (Tag tag : tags) {
            xml.start("item").elem("key", tag.getKey()).elem("value", tag.getValue()).end("item");
        }
        xml.end("tagSet");
    }

    private static List<String> list(MultivaluedMap<String, String> p, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = p.getFirst(prefix + "." + i);
            if (value == null) {
                return values;
            }
            values.add(value);
        }
    }

    private static List<String> listOrNull(MultivaluedMap<String, String> p, String prefix) {
        List<String> values = list(p, prefix);
        return values.isEmpty() ? null : values;
    }

    private static Boolean bool(MultivaluedMap<String, String> p, String name) {
        String value = p.getFirst(name);
        return value == null ? null : Boolean.valueOf(value);
    }

    private static Integer integer(MultivaluedMap<String, String> p, String name) {
        String value = p.getFirst(name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", name + " must be an integer.", 400);
        }
    }
}
