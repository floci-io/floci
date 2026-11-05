package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Client VPN endpoint with its target network associations, authorization rules and routes,
 * stored together so a change to any of them is one write.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientVpnEndpoint {

    /** One entry of {@code authenticationOptions}. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Authentication {
        private String type;
        private String directoryId;
        private String clientRootCertificateChain;
        private String samlProviderArn;
        private String selfServiceSamlProviderArn;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDirectoryId() { return directoryId; }
        public void setDirectoryId(String directoryId) { this.directoryId = directoryId; }
        public String getClientRootCertificateChain() { return clientRootCertificateChain; }
        public void setClientRootCertificateChain(String arn) { this.clientRootCertificateChain = arn; }
        public String getSamlProviderArn() { return samlProviderArn; }
        public void setSamlProviderArn(String arn) { this.samlProviderArn = arn; }
        public String getSelfServiceSamlProviderArn() { return selfServiceSamlProviderArn; }
        public void setSelfServiceSamlProviderArn(String arn) { this.selfServiceSamlProviderArn = arn; }
    }

    /** A subnet the endpoint is associated with. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetNetwork {
        private String associationId;
        private String subnetId;
        private String vpcId;
        private String availabilityZone;
        private String clientToken;

        public String getAssociationId() { return associationId; }
        public void setAssociationId(String associationId) { this.associationId = associationId; }
        public String getSubnetId() { return subnetId; }
        public void setSubnetId(String subnetId) { this.subnetId = subnetId; }
        public String getVpcId() { return vpcId; }
        public void setVpcId(String vpcId) { this.vpcId = vpcId; }
        public String getAvailabilityZone() { return availabilityZone; }
        public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }
        public String getClientToken() { return clientToken; }
        public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    }

    /** A network clients may reach, for all groups or one. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthorizationRule {
        private String destinationCidr;
        private String groupId;
        private boolean accessAll;
        private String description;

        public String getDestinationCidr() { return destinationCidr; }
        public void setDestinationCidr(String destinationCidr) { this.destinationCidr = destinationCidr; }
        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
        public boolean isAccessAll() { return accessAll; }
        public void setAccessAll(boolean accessAll) { this.accessAll = accessAll; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    /** A route in the endpoint's route table; {@code origin} is {@code associate} or {@code add-route}. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Route {
        private String destinationCidr;
        private String targetSubnet;
        private String origin;
        private String description;

        public String getDestinationCidr() { return destinationCidr; }
        public void setDestinationCidr(String destinationCidr) { this.destinationCidr = destinationCidr; }
        public String getTargetSubnet() { return targetSubnet; }
        public void setTargetSubnet(String targetSubnet) { this.targetSubnet = targetSubnet; }
        public String getOrigin() { return origin; }
        public void setOrigin(String origin) { this.origin = origin; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    private String clientVpnEndpointId;
    private String description;
    private Instant creationTime;
    private String clientCidrBlock;
    private List<String> dnsServers = new ArrayList<>();
    private boolean splitTunnel;
    private String transportProtocol;
    private int vpnPort;
    private String serverCertificateArn;
    private List<Authentication> authentication = new ArrayList<>();
    private boolean connectionLogEnabled;
    private String connectionLogGroup;
    private String connectionLogStream;
    private List<String> securityGroupIds = new ArrayList<>();
    private String vpcId;
    private String selfServicePortal;
    private boolean clientConnectEnabled;
    private String clientConnectLambdaFunctionArn;
    private int sessionTimeoutHours;
    private boolean clientLoginBannerEnabled;
    private String clientLoginBannerText;
    private boolean clientRouteEnforced;
    private boolean disconnectOnSessionTimeout;
    private String endpointIpAddressType;
    private String trafficIpAddressType;
    private String clientToken;
    private List<TargetNetwork> targetNetworks = new ArrayList<>();
    private List<AuthorizationRule> authorizationRules = new ArrayList<>();
    private List<Route> routes = new ArrayList<>();

    public String getClientVpnEndpointId() { return clientVpnEndpointId; }
    public void setClientVpnEndpointId(String id) { this.clientVpnEndpointId = id; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public String getClientCidrBlock() { return clientCidrBlock; }
    public void setClientCidrBlock(String clientCidrBlock) { this.clientCidrBlock = clientCidrBlock; }
    public List<String> getDnsServers() { return dnsServers; }
    public void setDnsServers(List<String> dnsServers) { this.dnsServers = dnsServers != null ? dnsServers : new ArrayList<>(); }
    public boolean isSplitTunnel() { return splitTunnel; }
    public void setSplitTunnel(boolean splitTunnel) { this.splitTunnel = splitTunnel; }
    public String getTransportProtocol() { return transportProtocol; }
    public void setTransportProtocol(String transportProtocol) { this.transportProtocol = transportProtocol; }
    public int getVpnPort() { return vpnPort; }
    public void setVpnPort(int vpnPort) { this.vpnPort = vpnPort; }
    public String getServerCertificateArn() { return serverCertificateArn; }
    public void setServerCertificateArn(String arn) { this.serverCertificateArn = arn; }
    public List<Authentication> getAuthentication() { return authentication; }
    public void setAuthentication(List<Authentication> authentication) {
        this.authentication = authentication != null ? authentication : new ArrayList<>();
    }
    public boolean isConnectionLogEnabled() { return connectionLogEnabled; }
    public void setConnectionLogEnabled(boolean enabled) { this.connectionLogEnabled = enabled; }
    public String getConnectionLogGroup() { return connectionLogGroup; }
    public void setConnectionLogGroup(String group) { this.connectionLogGroup = group; }
    public String getConnectionLogStream() { return connectionLogStream; }
    public void setConnectionLogStream(String stream) { this.connectionLogStream = stream; }
    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> ids) { this.securityGroupIds = ids != null ? ids : new ArrayList<>(); }
    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }
    public String getSelfServicePortal() { return selfServicePortal; }
    public void setSelfServicePortal(String selfServicePortal) { this.selfServicePortal = selfServicePortal; }
    public boolean isClientConnectEnabled() { return clientConnectEnabled; }
    public void setClientConnectEnabled(boolean enabled) { this.clientConnectEnabled = enabled; }
    public String getClientConnectLambdaFunctionArn() { return clientConnectLambdaFunctionArn; }
    public void setClientConnectLambdaFunctionArn(String arn) { this.clientConnectLambdaFunctionArn = arn; }
    public int getSessionTimeoutHours() { return sessionTimeoutHours; }
    public void setSessionTimeoutHours(int hours) { this.sessionTimeoutHours = hours; }
    public boolean isClientLoginBannerEnabled() { return clientLoginBannerEnabled; }
    public void setClientLoginBannerEnabled(boolean enabled) { this.clientLoginBannerEnabled = enabled; }
    public String getClientLoginBannerText() { return clientLoginBannerText; }
    public void setClientLoginBannerText(String text) { this.clientLoginBannerText = text; }
    public boolean isClientRouteEnforced() { return clientRouteEnforced; }
    public void setClientRouteEnforced(boolean enforced) { this.clientRouteEnforced = enforced; }
    public boolean isDisconnectOnSessionTimeout() { return disconnectOnSessionTimeout; }
    public void setDisconnectOnSessionTimeout(boolean disconnect) { this.disconnectOnSessionTimeout = disconnect; }
    public String getEndpointIpAddressType() { return endpointIpAddressType; }
    public void setEndpointIpAddressType(String type) { this.endpointIpAddressType = type; }
    public String getTrafficIpAddressType() { return trafficIpAddressType; }
    public void setTrafficIpAddressType(String type) { this.trafficIpAddressType = type; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public List<TargetNetwork> getTargetNetworks() { return targetNetworks; }
    public void setTargetNetworks(List<TargetNetwork> networks) {
        this.targetNetworks = networks != null ? networks : new ArrayList<>();
    }
    public List<AuthorizationRule> getAuthorizationRules() { return authorizationRules; }
    public void setAuthorizationRules(List<AuthorizationRule> rules) {
        this.authorizationRules = rules != null ? rules : new ArrayList<>();
    }
    public List<Route> getRoutes() { return routes; }
    public void setRoutes(List<Route> routes) { this.routes = routes != null ? routes : new ArrayList<>(); }
}
