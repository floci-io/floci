package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

@ApplicationScoped
public class Ec2QueryHandler {

    private static final Logger LOG = Logger.getLogger(Ec2QueryHandler.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    /** ReplaceRoute targets AWS accepts that a stored {@code Route} cannot represent here. */
    private static final List<String> UNSUPPORTED_ROUTE_TARGETS = List.of(
            "CarrierGatewayId", "CoreNetworkArn", "EgressOnlyInternetGatewayId", "InstanceId",
            "LocalGatewayId", "NetworkInterfaceId", "OdbNetworkArn",
            "TransitGatewayId", "VpcEndpointId", "VpcPeeringConnectionId");

    /** The gateway id a route table's built-in route carries (see Ec2Service#createRouteTable). */
    private static final String LOCAL_GATEWAY_ID = "local";

    private final Ec2Service service;
    private final EmulatorConfig config;
    private final FlowLogService flowLogService;

    @Inject
    public Ec2QueryHandler(Ec2Service service, EmulatorConfig config, FlowLogService flowLogService) {
        this.service = service;
        this.config = config;
        this.flowLogService = flowLogService;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.debugv("EC2 action: {0}", action);
        try {
            return switch (action) {
                // Instances
                case "RunInstances" -> handleRunInstances(params, region);
                case "DescribeInstances" -> handleDescribeInstances(params, region);
                case "DescribeIamInstanceProfileAssociations" ->
                        handleDescribeIamInstanceProfileAssociations(params, region);
                case "TerminateInstances" -> handleTerminateInstances(params, region);
                case "StartInstances" -> handleStartInstances(params, region);
                case "StopInstances" -> handleStopInstances(params, region);
                case "RebootInstances" -> handleRebootInstances(params, region);
                case "DescribeInstanceStatus" -> handleDescribeInstanceStatus(params, region);
                case "DescribeInstanceAttribute" -> handleDescribeInstanceAttribute(params, region);
                case "ModifyInstanceAttribute" -> handleModifyInstanceAttribute(params, region);
                case "ModifyInstanceMetadataDefaults" -> handleModifyInstanceMetadataDefaults(params, region);
                case "ModifyInstanceMetadataOptions" -> handleModifyInstanceMetadataOptions(params, region);
                case "GetInstanceMetadataDefaults" -> handleGetInstanceMetadataDefaults(params, region);
                // VPCs
                case "CreateVpc" -> handleCreateVpc(params, region);
                case "DescribeVpcs" -> handleDescribeVpcs(params, region);
                case "DeleteVpc" -> handleDeleteVpc(params, region);
                case "ModifyVpcAttribute" -> handleModifyVpcAttribute(params, region);
                case "DescribeVpcAttribute" -> handleDescribeVpcAttribute(params, region);
                case "DescribeVpcEndpointServices" -> handleDescribeVpcEndpointServices(params, region);
                case "CreateVpcEndpoint" -> handleCreateVpcEndpoint(params, region);
                case "DescribeVpcEndpoints" -> handleDescribeVpcEndpoints(params, region);
                case "ModifyVpcEndpoint" -> handleModifyVpcEndpoint(params, region);
                case "DeleteVpcEndpoints" -> handleDeleteVpcEndpoints(params, region);
                // DHCP Options
                case "CreateDhcpOptions" -> handleCreateDhcpOptions(params, region);
                case "DescribeDhcpOptions" -> handleDescribeDhcpOptions(params, region);
                case "DeleteDhcpOptions" -> handleDeleteDhcpOptions(params, region);
                case "AssociateDhcpOptions" -> handleAssociateDhcpOptions(params, region);
                // Flow Logs
                case "CreateFlowLogs" -> handleCreateFlowLogs(params, region);
                case "DescribeFlowLogs" -> handleDescribeFlowLogs(params, region);
                case "DeleteFlowLogs" -> handleDeleteFlowLogs(params, region);
                case "DescribePrefixLists" -> handleDescribePrefixLists(params, region);
                case "CreateManagedPrefixList" -> handleCreateManagedPrefixList(params, region);
                case "DescribeManagedPrefixLists" -> handleDescribeManagedPrefixLists(params, region);
                case "GetManagedPrefixListEntries" -> handleGetManagedPrefixListEntries(params, region);
                case "ModifyManagedPrefixList" -> handleModifyManagedPrefixList(params, region);
                case "DeleteManagedPrefixList" -> handleDeleteManagedPrefixList(params, region);
                case "CreateDefaultVpc" -> handleCreateDefaultVpc(params, region);
                case "AssociateVpcCidrBlock" -> handleAssociateVpcCidrBlock(params, region);
                case "DisassociateVpcCidrBlock" -> handleDisassociateVpcCidrBlock(params, region);
                // Subnets
                case "CreateSubnet" -> handleCreateSubnet(params, region);
                case "DescribeSubnets" -> handleDescribeSubnets(params, region);
                case "DeleteSubnet" -> handleDeleteSubnet(params, region);
                case "ModifySubnetAttribute" -> handleModifySubnetAttribute(params, region);
                // Security Groups
                case "CreateSecurityGroup" -> handleCreateSecurityGroup(params, region);
                case "DescribeSecurityGroups" -> handleDescribeSecurityGroups(params, region);
                case "DeleteSecurityGroup" -> handleDeleteSecurityGroup(params, region);
                case "AuthorizeSecurityGroupIngress" -> handleAuthorizeSecurityGroupIngress(params, region);
                case "AuthorizeSecurityGroupEgress" -> handleAuthorizeSecurityGroupEgress(params, region);
                case "RevokeSecurityGroupIngress" -> handleRevokeSecurityGroupIngress(params, region);
                case "RevokeSecurityGroupEgress" -> handleRevokeSecurityGroupEgress(params, region);
                case "DescribeSecurityGroupRules" -> handleDescribeSecurityGroupRules(params, region);
                case "ModifySecurityGroupRules" -> handleModifySecurityGroupRules(params, region);
                case "UpdateSecurityGroupRuleDescriptionsIngress" ->
                        handleUpdateSgRuleDescriptionsIngress(params, region);
                case "UpdateSecurityGroupRuleDescriptionsEgress" ->
                        handleUpdateSgRuleDescriptionsEgress(params, region);
                // Key Pairs
                case "CreateKeyPair" -> handleCreateKeyPair(params, region);
                case "DescribeKeyPairs" -> handleDescribeKeyPairs(params, region);
                case "DeleteKeyPair" -> handleDeleteKeyPair(params, region);
                case "ImportKeyPair" -> handleImportKeyPair(params, region);
                // AMIs
                case "DescribeImages" -> handleDescribeImages(params, region);
                case "CreateImage" -> handleCreateImage(params, region);
                case "RegisterImage" -> handleRegisterImage(params, region);
                case "DescribeSnapshots" -> handleDescribeSnapshots(params, region);
                case "EnableSnapshotBlockPublicAccess" -> handleEnableSnapshotBlockPublicAccess(params, region);
                case "DisableSnapshotBlockPublicAccess" -> handleDisableSnapshotBlockPublicAccess(region);
                case "GetSnapshotBlockPublicAccessState" -> handleGetSnapshotBlockPublicAccessState(region);
                // Tags
                case "CreateTags" -> handleCreateTags(params, region);
                case "DeleteTags" -> handleDeleteTags(params, region);
                case "DescribeTags" -> handleDescribeTags(params, region);
                // Internet Gateways
                case "CreateInternetGateway" -> handleCreateInternetGateway(params, region);
                case "DescribeInternetGateways" -> handleDescribeInternetGateways(params, region);
                case "DeleteInternetGateway" -> handleDeleteInternetGateway(params, region);
                case "AttachInternetGateway" -> handleAttachInternetGateway(params, region);
                case "DetachInternetGateway" -> handleDetachInternetGateway(params, region);
                // VPN Gateways
                case "CreateVpnGateway" -> handleCreateVpnGateway(params, region);
                case "DescribeVpnGateways" -> handleDescribeVpnGateways(params, region);
                case "DeleteVpnGateway" -> handleDeleteVpnGateway(params, region);
                case "AttachVpnGateway" -> handleAttachVpnGateway(params, region);
                case "DetachVpnGateway" -> handleDetachVpnGateway(params, region);

                // Route Tables
                case "CreateRouteTable" -> handleCreateRouteTable(params, region);
                case "DescribeRouteTables" -> handleDescribeRouteTables(params, region);
                case "DeleteRouteTable" -> handleDeleteRouteTable(params, region);
                case "AssociateRouteTable" -> handleAssociateRouteTable(params, region);
                case "DisassociateRouteTable" -> handleDisassociateRouteTable(params, region);
                case "CreateRoute" -> handleCreateRoute(params, region);
                case "ReplaceRoute" -> handleReplaceRoute(params, region);
                case "DeleteRoute" -> handleDeleteRoute(params, region);
                // Network ACLs
                case "CreateNetworkAcl" -> handleCreateNetworkAcl(params, region);
                case "DescribeNetworkAcls" -> handleDescribeNetworkAcls(params, region);
                case "DeleteNetworkAcl" -> handleDeleteNetworkAcl(params, region);
                case "CreateNetworkAclEntry" -> handleNetworkAclEntry(params, region, "CreateNetworkAclEntry");
                case "ReplaceNetworkAclEntry" -> handleNetworkAclEntry(params, region, "ReplaceNetworkAclEntry");
                case "DeleteNetworkAclEntry" -> handleDeleteNetworkAclEntry(params, region);
                case "ReplaceNetworkAclAssociation" -> handleReplaceNetworkAclAssociation(params, region);
                // NAT Gateways
                case "CreateNatGateway" -> handleCreateNatGateway(params, region);
                case "DescribeNatGateways" -> handleDescribeNatGateways(params, region);
                case "DeleteNatGateway" -> handleDeleteNatGateway(params, region);
                // Customer Gateways
                case "CreateCustomerGateway" -> handleCreateCustomerGateway(params, region);
                case "DescribeCustomerGateways" -> handleDescribeCustomerGateways(params, region);
                case "DeleteCustomerGateway" -> handleDeleteCustomerGateway(params, region);
                // Capacity Reservations
                case "CreateCapacityReservation" -> handleCreateCapacityReservation(params, region);
                case "DescribeCapacityReservations" -> handleDescribeCapacityReservations(params, region);
                case "ModifyCapacityReservation" -> handleModifyCapacityReservation(params, region);
                case "CancelCapacityReservation" -> handleCancelCapacityReservation(params, region);
                // Transit Gateways
                case "CreateTransitGateway" -> handleCreateTransitGateway(params, region);
                case "DescribeTransitGateways" -> handleDescribeTransitGateways(params, region);
                case "ModifyTransitGateway" -> handleModifyTransitGateway(params, region);
                case "DeleteTransitGateway" -> handleDeleteTransitGateway(params, region);
                case "CreateTransitGatewayVpcAttachment" -> handleCreateTransitGatewayVpcAttachment(params, region);
                case "DescribeTransitGatewayVpcAttachments" ->
                        handleDescribeTransitGatewayVpcAttachments(params, region);
                case "ModifyTransitGatewayVpcAttachment" -> handleModifyTransitGatewayVpcAttachment(params, region);
                case "DeleteTransitGatewayVpcAttachment" -> handleDeleteTransitGatewayVpcAttachment(params, region);
                case "CreateTransitGatewayRouteTable" -> handleCreateTransitGatewayRouteTable(params, region);
                case "DescribeTransitGatewayRouteTables" -> handleDescribeTransitGatewayRouteTables(params, region);
                case "DeleteTransitGatewayRouteTable" -> handleDeleteTransitGatewayRouteTable(params, region);
                case "CreateTransitGatewayRoute" -> handleCreateTransitGatewayRoute(params, region);
                case "DeleteTransitGatewayRoute" -> handleDeleteTransitGatewayRoute(params, region);
                case "SearchTransitGatewayRoutes" -> handleSearchTransitGatewayRoutes(params, region);
                case "AssociateTransitGatewayRouteTable" -> handleAssociateTransitGatewayRouteTable(params, region);
                case "DisassociateTransitGatewayRouteTable" ->
                        handleDisassociateTransitGatewayRouteTable(params, region);
                case "EnableTransitGatewayRouteTablePropagation" ->
                        handleEnableTransitGatewayRouteTablePropagation(params, region);
                case "DisableTransitGatewayRouteTablePropagation" ->
                        handleDisableTransitGatewayRouteTablePropagation(params, region);
                case "GetTransitGatewayRouteTableAssociations" ->
                        handleGetTransitGatewayRouteTableAssociations(params, region);
                case "GetTransitGatewayRouteTablePropagations" ->
                        handleGetTransitGatewayRouteTablePropagations(params, region);
                // Elastic IPs
                case "AllocateAddress" -> handleAllocateAddress(params, region);
                case "AssociateAddress" -> handleAssociateAddress(params, region);
                case "DisassociateAddress" -> handleDisassociateAddress(params, region);
                case "ReleaseAddress" -> handleReleaseAddress(params, region);
                case "DescribeAddresses" -> handleDescribeAddresses(params, region);
                case "DescribeAddressesAttribute" -> handleDescribeAddressesAttribute(params, region);
                // Regions & Account
                case "DescribeAvailabilityZones" -> handleDescribeAvailabilityZones(params, region);
                case "DescribeRegions" -> handleDescribeRegions(params, region);
                case "DescribeAccountAttributes" -> handleDescribeAccountAttributes(params, region);
                // Instance Types
                case "DescribeInstanceTypes" -> handleDescribeInstanceTypes(params, region);
                case "DescribeInstanceTypeOfferings" -> handleDescribeInstanceTypeOfferings(params, region);
                // Launch Templates
                case "CreateLaunchTemplate" -> handleCreateLaunchTemplate(params, region);
                case "CreateLaunchTemplateVersion" -> handleCreateLaunchTemplateVersion(params, region);
                case "DescribeLaunchTemplates" -> handleDescribeLaunchTemplates(params, region);
                case "DescribeLaunchTemplateVersions" -> handleDescribeLaunchTemplateVersions(params, region);
                case "ModifyLaunchTemplate" -> handleModifyLaunchTemplate(params, region);
                case "DeleteLaunchTemplate" -> handleDeleteLaunchTemplate(params, region);
                // Network Interfaces
                case "DescribeNetworkInterfaces" -> handleDescribeNetworkInterfaces(params, region);
                // Volumes
                case "CreateVolume" -> handleCreateVolume(params, region);
                case "DescribeVolumes" -> handleDescribeVolumes(params, region);
                case "DeleteVolume" -> handleDeleteVolume(params, region);
                case "AttachVolume" -> handleAttachVolume(params, region);
                case "DetachVolume" -> handleDetachVolume(params, region);
                // Spot Instances
                case "RequestSpotInstances" -> handleRequestSpotInstances(params, region);
                case "DescribeSpotInstanceRequests" -> handleDescribeSpotInstanceRequests(params, region);
                case "CancelSpotInstanceRequests" -> handleCancelSpotInstanceRequests(params, region);
                default -> ec2Error("UnsupportedOperation",
                        "Operation " + action + " is not supported.", 400);
            };
        } catch (AwsException e) {
            return ec2Error(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    /**
     * EC2 uses a different error envelope than other Query-protocol services.
     * The AWS SDK v2 EC2 client parses {@code <Response><Errors><Error><Code>},
     * not the standard {@code <ErrorResponse><Error><Code>} shape.
     */
    private Response ec2Error(String code, String message, int status) {
        String xml = new XmlBuilder()
                .start("Response")
                .start("Errors")
                .start("Error")
                .elem("Code", code)
                .elem("Message", message)
                .end("Error")
                .end("Errors")
                .elem("RequestID", UUID.randomUUID().toString())
                .end("Response")
                .build();
        return Response.status(status).entity(xml).type(MediaType.APPLICATION_XML).build();
    }

    // ─── Parameter helpers ────────────────────────────────────────────────────

    private List<String> getList(MultivaluedMap<String, String> p, String prefix) {
        List<String> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String v = p.getFirst(prefix + "." + i);
            if (v == null) break;
            result.add(v);
        }
        return result;
    }

    private List<String> getList(MultivaluedMap<String, String> p, String... prefixes) {
        List<String> result = new ArrayList<>();
        for (String prefix : prefixes) {
            result.addAll(getList(p, prefix));
        }
        return result;
    }

    private String firstPresent(MultivaluedMap<String, String> p, String first, String second) {
        String value = p.getFirst(first);
        return value != null && !value.isBlank() ? value : p.getFirst(second);
    }

    // Option blocks carry AWS's own defaults on the model object, so an absent parameter must
    // leave the default in place rather than overwrite it with null.
    private void applyIfPresent(MultivaluedMap<String, String> p, String name, Consumer<String> setter) {
        String value = p.getFirst(name);
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    private int parseIntParam(MultivaluedMap<String, String> p, String name, int defaultValue) {
        String val = p.getFirst(name);
        if (val == null || val.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidMaxResults",
                    "The specified value for MaxResults is not valid.", 400);
        }
    }

    private Map<String, List<String>> getFilters(MultivaluedMap<String, String> p) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String name = p.getFirst("Filter." + i + ".Name");
            if (name == null) break;
            List<String> values = new ArrayList<>();
            for (int j = 1; ; j++) {
                String v = p.getFirst("Filter." + i + ".Value." + j);
                if (v == null) break;
                values.add(v);
            }
            filters.put(name, values);
        }
        return filters;
    }

    private List<BlockDeviceMapping> parseBlockDeviceMappings(MultivaluedMap<String, String> p) {
        List<BlockDeviceMapping> mappings = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = "BlockDeviceMapping." + i;
            String deviceName = p.getFirst(prefix + ".DeviceName");
            String snapshotId = p.getFirst(prefix + ".Ebs.SnapshotId");
            String volumeSize = p.getFirst(prefix + ".Ebs.VolumeSize");
            String volumeType = p.getFirst(prefix + ".Ebs.VolumeType");
            String deleteOnTermination = p.getFirst(prefix + ".Ebs.DeleteOnTermination");
            String encrypted = p.getFirst(prefix + ".Ebs.Encrypted");
            String iops = p.getFirst(prefix + ".Ebs.Iops");
            String throughput = p.getFirst(prefix + ".Ebs.Throughput");
            boolean hasEbs = snapshotId != null || volumeSize != null || volumeType != null
                    || deleteOnTermination != null || encrypted != null || iops != null || throughput != null;
            if (deviceName == null && !hasEbs) {
                break;
            }
            if (deviceName == null || deviceName.isBlank()) {
                throw new AwsException("InvalidParameterValue",
                        "BlockDeviceMapping." + i + ".DeviceName is required.", 400);
            }
            BlockDeviceMapping mapping = new BlockDeviceMapping();
            mapping.setDeviceName(deviceName);
            EbsBlockDevice ebs = new EbsBlockDevice();
            ebs.setSnapshotId(snapshotId);
            ebs.setVolumeSize(parseOptionalInt(volumeSize, prefix + ".Ebs.VolumeSize"));
            ebs.setVolumeType(volumeType);
            ebs.setDeleteOnTermination(parseOptionalBoolean(deleteOnTermination,
                    prefix + ".Ebs.DeleteOnTermination"));
            ebs.setEncrypted(parseOptionalBoolean(encrypted, prefix + ".Ebs.Encrypted"));
            ebs.setIops(parseOptionalInt(iops, prefix + ".Ebs.Iops"));
            ebs.setThroughput(parseOptionalInt(throughput, prefix + ".Ebs.Throughput"));
            mapping.setEbs(ebs);
            mappings.add(mapping);
        }
        return mappings;
    }

    private Integer parseOptionalInt(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", name + " is not a valid integer.", 400);
        }
    }

    private Boolean parseOptionalBoolean(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new AwsException("InvalidParameterValue", name + " is not a valid boolean.", 400);
    }

    private Double parseOptionalDouble(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", name + " is not a valid number.", 400);
        }
    }

    /**
     * Reads the {@code IpPermissions.N} list of an authorize/revoke request.
     *
     * <p>Each permission carries up to four kinds of source, and every one of them has to be read
     * here or it is silently dropped before the service layer ever sees it (#2190). Note the EC2
     * Query protocol serializes the {@code UserIdGroupPairs} member under the wire name
     * {@code Groups}, which is why a group reference arrives as
     * {@code IpPermissions.1.Groups.1.GroupId} rather than under the SDK-facing field name.
     */
    private List<IpPermission> parseIpPermissions(MultivaluedMap<String, String> p, String prefix) {
        List<IpPermission> perms = new ArrayList<>();
        for (int i = 1; ; i++) {
            String proto = p.getFirst(prefix + "." + i + ".IpProtocol");
            if (proto == null) break;
            IpPermission perm = new IpPermission();
            perm.setIpProtocol(proto);
            String fromPort = p.getFirst(prefix + "." + i + ".FromPort");
            String toPort = p.getFirst(prefix + "." + i + ".ToPort");
            if (fromPort != null) perm.setFromPort(Integer.parseInt(fromPort));
            if (toPort != null) perm.setToPort(Integer.parseInt(toPort));
            for (int j = 1; ; j++) {
                String cidr = p.getFirst(prefix + "." + i + ".IpRanges." + j + ".CidrIp");
                if (cidr == null) cidr = p.getFirst(prefix + "." + i + ".IpRanges." + j);
                if (cidr == null) break;
                String desc = p.getFirst(prefix + "." + i + ".IpRanges." + j + ".Description");
                perm.getIpRanges().add(new IpRange(cidr, desc));
            }
            for (int j = 1; ; j++) {
                String cidr = p.getFirst(prefix + "." + i + ".Ipv6Ranges." + j + ".CidrIpv6");
                if (cidr == null) {
                    break;
                }
                String desc = p.getFirst(prefix + "." + i + ".Ipv6Ranges." + j + ".Description");
                perm.getIpv6Ranges().add(new Ipv6Range(cidr, desc));
            }
            for (int j = 1; ; j++) {
                String base = prefix + "." + i + ".Groups." + j;
                String groupId = p.getFirst(base + ".GroupId");
                String groupName = p.getFirst(base + ".GroupName");
                String userId = p.getFirst(base + ".UserId");
                String desc = p.getFirst(base + ".Description");
                // A default-VPC caller may reference a group by name alone, so the loop cannot end
                // on a missing GroupId.
                if (groupId == null && groupName == null && userId == null && desc == null) {
                    break;
                }
                UserIdGroupPair pair = new UserIdGroupPair();
                pair.setGroupId(groupId);
                pair.setGroupName(groupName);
                pair.setUserId(userId);
                pair.setDescription(desc);
                perm.getUserIdGroupPairs().add(pair);
            }
            for (int j = 1; ; j++) {
                String base = prefix + "." + i + ".PrefixListIds." + j;
                String prefixListId = p.getFirst(base + ".PrefixListId");
                if (prefixListId == null) break;
                String desc = p.getFirst(base + ".Description");
                perm.getPrefixListIds().add(new PrefixListIdReference(prefixListId, desc));
            }
            perms.add(perm);
        }
        return perms;
    }

    private List<Tag> parseTagsForResource(MultivaluedMap<String, String> p, String resourceType) {
        List<Tag> tags = new ArrayList<>();
        for (int i = 1; ; i++) {
            String resType = p.getFirst("TagSpecification." + i + ".ResourceType");
            if (resType == null) break;
            if (resourceType.equals(resType)) {
                for (int j = 1; ; j++) {
                    String key = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Key");
                    if (key == null) break;
                    String value = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Value");
                    tags.add(new Tag(key, value));
                }
            }
        }
        return tags;
    }

    /**
     * Reads {@code CreateVpcEndpoint}'s {@code SubnetConfiguration.N} list: the per-subnet private
     * IP(s) AWS assigns to the endpoint's network interfaces, fixed at creation time
     * (API_SubnetConfiguration.html). Dropping this silently, as floci previously did, throws away
     * an address the caller may have pinned in their own IaC config, so a later describe answers
     * with a different address than the one that was asked for.
     */
    private List<VpcEndpointSubnetConfiguration> parseSubnetConfigurations(MultivaluedMap<String, String> p) {
        List<VpcEndpointSubnetConfiguration> configs = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = "SubnetConfiguration." + i;
            String subnetId = p.getFirst(prefix + ".SubnetId");
            if (subnetId == null) break;
            configs.add(new VpcEndpointSubnetConfiguration(
                    subnetId, p.getFirst(prefix + ".Ipv4"), p.getFirst(prefix + ".Ipv6")));
        }
        return configs;
    }

    private List<Tag> parseLaunchTemplateDataTagsForResource(MultivaluedMap<String, String> p, String resourceType) {
        List<Tag> tags = new ArrayList<>();
        for (int i = 1; ; i++) {
            String resType = p.getFirst("LaunchTemplateData.TagSpecification." + i + ".ResourceType");
            if (resType == null) break;
            if (resourceType.equals(resType)) {
                for (int j = 1; ; j++) {
                    String key = p.getFirst("LaunchTemplateData.TagSpecification." + i + ".Tag." + j + ".Key");
                    if (key == null) break;
                    String value = p.getFirst("LaunchTemplateData.TagSpecification." + i + ".Tag." + j + ".Value");
                    tags.add(new Tag(key, value));
                }
            }
        }
        return tags;
    }

    // Apply tags supplied inline on a create call (TagSpecification) to the resource, so
    // they round-trip on the next Describe* — otherwise the provider sees phantom tag drift.
    private void applyResourceTags(MultivaluedMap<String, String> p, String region, String resourceType, String resourceId) {
        List<Tag> tagList = parseTagsForResource(p, resourceType);
        if (!tagList.isEmpty()) {
            service.createTags(region, List.of(resourceId), tagList);
        }
    }

    // Each rule gets its own copy so mutating one rule's tag list can never leak into the
    // others authorized in the same batch, and the copy also feeds the response XML.
    private void applySecurityGroupRuleTags(MultivaluedMap<String, String> p, String region,
                                            List<SecurityGroupRule> rules) {
        List<Tag> ruleTags = parseTagsForResource(p, "security-group-rule");
        if (ruleTags.isEmpty()) {
            return;
        }
        for (SecurityGroupRule rule : rules) {
            service.createTags(region, List.of(rule.getSecurityGroupRuleId()), ruleTags);
            rule.setTags(new ArrayList<>(ruleTags));
        }
    }

    private Response xmlResponse(String xml) {
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    private Response booleanResponse(String action) {
        String xml = new XmlBuilder()
                .start(action + "Response", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("return", "true")
                .end(action + "Response")
                .build();
        return xmlResponse(xml);
    }

    // ─── Instance handlers ────────────────────────────────────────────────────

    private Response handleRunInstances(MultivaluedMap<String, String> p, String region) {
        String imageId = p.getFirst("ImageId");
        String instanceType = p.getFirst("InstanceType");
        int minCount = Integer.parseInt(p.getOrDefault("MinCount", List.of("1")).get(0));
        int maxCount = Integer.parseInt(p.getOrDefault("MaxCount", List.of("1")).get(0));
        String keyName = p.getFirst("KeyName");
        String subnetId = p.getFirst("SubnetId");
        // The launch-time public-IP override arrives either on the primary
        // network interface spec (the shape Terraform's
        // associate_public_ip_address sends) or as the legacy top-level
        // parameter. AWS rejects both at once, so the interface spec wins when
        // present. Absent means "use the subnet's MapPublicIpOnLaunch default".
        String assocParam = p.getFirst("NetworkInterface.1.AssociatePublicIpAddress");
        if (assocParam == null) {
            assocParam = p.getFirst("AssociatePublicIpAddress");
        }
        Boolean associatePublicIp = assocParam == null ? null : Boolean.parseBoolean(assocParam);
        if (subnetId == null) {
            subnetId = p.getFirst("NetworkInterface.1.SubnetId");
        }
        String clientToken = p.getFirst("ClientToken");
        List<String> sgIds = getList(p, "SecurityGroupId");

        // UserData is base64-encoded in the wire format
        String userDataEncoded = p.getFirst("UserData");
        String userData = null;
        if (userDataEncoded != null && !userDataEncoded.isBlank()) {
            userData = decodeUserData(userDataEncoded);
        }

        String iamInstanceProfileArn = resolveIamInstanceProfileArn(p);

        // Parse TagSpecifications
        List<Tag> instanceTags = new ArrayList<>();
        for (int i = 1; ; i++) {
            String resType = p.getFirst("TagSpecification." + i + ".ResourceType");
            if (resType == null) break;
            if ("instance".equals(resType)) {
                for (int j = 1; ; j++) {
                    String k = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Key");
                    if (k == null) break;
                    String v = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Value");
                    instanceTags.add(new Tag(k, v));
                }
            }
        }

        List<BlockDeviceMapping> blockDeviceMappings = parseBlockDeviceMappings(p);

        LaunchTemplateData launchTemplateData = resolveRunInstancesLaunchTemplateData(p, region);
        if (launchTemplateData != null) {
            imageId = firstNonBlank(imageId, launchTemplateData.getImageId());
            instanceType = firstNonBlank(instanceType, launchTemplateData.getInstanceType());
            keyName = firstNonBlank(keyName, launchTemplateData.getKeyName());
            userData = firstNonBlank(userData, launchTemplateData.getUserData());
            iamInstanceProfileArn = firstNonBlank(iamInstanceProfileArn, launchTemplateData.getIamInstanceProfileArn());
            if (sgIds.isEmpty()) {
                sgIds = new ArrayList<>(launchTemplateData.getEffectiveSecurityGroupIds());
            }
            if (!launchTemplateData.getInstanceTags().isEmpty()) {
                Map<String, Tag> mergedTags = new LinkedHashMap<>();
                launchTemplateData.getInstanceTags().forEach(tag -> mergedTags.put(tag.getKey(), tag));
                instanceTags.forEach(tag -> mergedTags.put(tag.getKey(), tag));
                instanceTags = new ArrayList<>(mergedTags.values());
            }
        }

        InstanceMetadataRequest metadataOptions = parseInstanceMetadataRequest(p, "MetadataOptions.");

        Reservation res = service.runInstances(region, imageId, instanceType, minCount, maxCount,
                keyName, sgIds, subnetId, clientToken, instanceTags, userData, iamInstanceProfileArn,
                associatePublicIp, blockDeviceMappings, metadataOptions);

        XmlBuilder xml = new XmlBuilder()
                .start("RunInstancesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("reservationId", res.getReservationId())
                .elem("ownerId", res.getOwnerId())
                .start("groupSet").end("groupSet")
                .start("instancesSet");
        for (Instance inst : res.getInstances()) {
            xml.start("item").raw(instanceXml(inst)).end("item");
        }
        xml.end("instancesSet")
                .end("RunInstancesResponse");
        return xmlResponse(xml.build());
    }

    private LaunchTemplateData resolveRunInstancesLaunchTemplateData(MultivaluedMap<String, String> p, String region) {
        String id = p.getFirst("LaunchTemplate.LaunchTemplateId");
        String name = p.getFirst("LaunchTemplate.LaunchTemplateName");
        String version = p.getFirst("LaunchTemplate.Version");
        if ((id == null || id.isBlank()) && (name == null || name.isBlank())) {
            return null;
        }
        return service.resolveLaunchTemplateData(region, id, name, version);
    }

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private Response handleDescribeIamInstanceProfileAssociations(MultivaluedMap<String, String> p, String region) {
        List<String> associationIds = getList(p, "AssociationId");
        Map<String, List<String>> filters = getFilters(p);
        List<String> instanceFilter = filters.get("instance-id");

        List<Reservation> reservations = service.describeInstances(region, List.of(), Map.of());
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeIamInstanceProfileAssociationsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("iamInstanceProfileAssociationSet");
        for (Reservation res : reservations) {
            for (Instance inst : res.getInstances()) {
                if (inst.getIamInstanceProfileArn() == null) {
                    continue;
                }
                String assocId = iamInstanceProfileAssociationId(inst.getInstanceId());
                if (instanceFilter != null && !instanceFilter.contains(inst.getInstanceId())) {
                    continue;
                }
                if (!associationIds.isEmpty() && !associationIds.contains(assocId)) {
                    continue;
                }
                xml.start("item")
                        .elem("associationId", assocId)
                        .elem("instanceId", inst.getInstanceId())
                        .start("iamInstanceProfile")
                        .elem("arn", inst.getIamInstanceProfileArn())
                        .elem("id", iamInstanceProfileId(inst.getInstanceId()))
                        .end("iamInstanceProfile")
                        .elem("state", "associated")
                        .end("item");
            }
        }
        xml.end("iamInstanceProfileAssociationSet")
                .end("DescribeIamInstanceProfileAssociationsResponse");
        return xmlResponse(xml.build());
    }

    /**
     * Deterministic instance-profile id derived from the instance id so repeated describes are stable.
     */
    private static String iamInstanceProfileId(String instanceId) {
        return "AIPA" + stableSuffix(instanceId, 17).toUpperCase();
    }

    /**
     * Deterministic association id derived from the instance id so repeated describes are stable.
     */
    private static String iamInstanceProfileAssociationId(String instanceId) {
        return "iip-assoc-" + stableSuffix(instanceId, 17);
    }

    private static String stableSuffix(String seed, int length) {
        StringBuilder sb = new StringBuilder();
        int h = seed.hashCode();
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        long v = ((long) h) & 0xFFFFFFFFL;
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt((int) (v % alphabet.length())));
            v = v * 1103515245L + 12345L + i;
            v &= 0xFFFFFFFFL;
        }
        return sb.toString();
    }

    private Response handleDescribeInstances(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "InstanceId");
        Map<String, List<String>> filters = getFilters(p);
        List<Reservation> reservations = service.describeInstances(region, ids, filters);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeInstancesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("reservationSet");
        for (Reservation res : reservations) {
            xml.start("item")
                    .elem("reservationId", res.getReservationId())
                    .elem("ownerId", res.getOwnerId())
                    .start("groupSet").end("groupSet")
                    .start("instancesSet");
            for (Instance inst : res.getInstances()) {
                xml.start("item").raw(instanceXml(inst)).end("item");
            }
            xml.end("instancesSet").end("item");
        }
        xml.end("reservationSet").end("DescribeInstancesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleTerminateInstances(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "InstanceId");
        List<Map<String, String>> changes = service.terminateInstances(region, ids);
        XmlBuilder xml = new XmlBuilder()
                .start("TerminateInstancesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("instancesSet");
        for (Map<String, String> c : changes) {
            xml.start("item")
                    .elem("instanceId", c.get("instanceId"))
                    .start("currentState")
                    .elem("code", c.get("currentCode"))
                    .elem("name", c.get("currentState"))
                    .end("currentState")
                    .start("previousState")
                    .elem("code", c.get("previousCode"))
                    .elem("name", c.get("previousState"))
                    .end("previousState")
                    .end("item");
        }
        xml.end("instancesSet").end("TerminateInstancesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleStartInstances(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "InstanceId");
        List<Map<String, String>> changes = service.startInstances(region, ids);
        XmlBuilder xml = new XmlBuilder()
                .start("StartInstancesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("instancesSet");
        for (Map<String, String> c : changes) {
            xml.start("item")
                    .elem("instanceId", c.get("instanceId"))
                    .start("currentState")
                    .elem("code", c.get("currentCode"))
                    .elem("name", c.get("currentState"))
                    .end("currentState")
                    .start("previousState")
                    .elem("code", c.get("previousCode"))
                    .elem("name", c.get("previousState"))
                    .end("previousState")
                    .end("item");
        }
        xml.end("instancesSet").end("StartInstancesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleStopInstances(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "InstanceId");
        List<Map<String, String>> changes = service.stopInstances(region, ids);
        XmlBuilder xml = new XmlBuilder()
                .start("StopInstancesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("instancesSet");
        for (Map<String, String> c : changes) {
            xml.start("item")
                    .elem("instanceId", c.get("instanceId"))
                    .start("currentState")
                    .elem("code", c.get("currentCode"))
                    .elem("name", c.get("currentState"))
                    .end("currentState")
                    .start("previousState")
                    .elem("code", c.get("previousCode"))
                    .elem("name", c.get("previousState"))
                    .end("previousState")
                    .end("item");
        }
        xml.end("instancesSet").end("StopInstancesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleRebootInstances(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "InstanceId");
        service.rebootInstances(region, ids);
        return booleanResponse("RebootInstances");
    }

    private Response handleDescribeInstanceStatus(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "InstanceId");
        List<Instance> runningInstances = service.describeInstanceStatus(region, ids);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeInstanceStatusResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("instanceStatusSet");
        for (Instance inst : runningInstances) {
            xml.start("item")
                    .elem("instanceId", inst.getInstanceId())
                    .elem("availabilityZone", inst.getPlacement() != null ? inst.getPlacement().getAvailabilityZone() : "")
                    .start("instanceState")
                    .elem("code", String.valueOf(inst.getState().getCode()))
                    .elem("name", inst.getState().getName())
                    .end("instanceState")
                    .start("systemStatus")
                    .elem("status", "ok")
                    .start("details").start("item")
                    .elem("name", "reachability").elem("status", "passed")
                    .end("item").end("details")
                    .end("systemStatus")
                    .start("instanceStatus")
                    .elem("status", "ok")
                    .start("details").start("item")
                    .elem("name", "reachability").elem("status", "passed")
                    .end("item").end("details")
                    .end("instanceStatus")
                    .end("item");
        }
        xml.end("instanceStatusSet").end("DescribeInstanceStatusResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeInstanceAttribute(MultivaluedMap<String, String> p, String region) {
        String instanceId = p.getFirst("InstanceId");
        String attribute = p.getFirst("Attribute");
        Instance inst = service.describeInstanceAttribute(region, instanceId, attribute);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeInstanceAttributeResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("instanceId", instanceId);
        if ("instanceType".equals(attribute)) {
            xml.start("instanceType").elem("value", inst.getInstanceType()).end("instanceType");
        } else if ("sourceDestCheck".equals(attribute)) {
            xml.start("sourceDestCheck").elem("value", String.valueOf(inst.isSourceDestCheck())).end("sourceDestCheck");
        } else if ("ebsOptimized".equals(attribute)) {
            xml.start("ebsOptimized").elem("value", String.valueOf(inst.isEbsOptimized())).end("ebsOptimized");
        } else if ("disableApiStop".equals(attribute)) {
            xml.start("disableApiStop").elem("value", String.valueOf(inst.isDisableApiStop())).end("disableApiStop");
        } else if ("disableApiTermination".equals(attribute)) {
            xml.start("disableApiTermination").elem("value", String.valueOf(inst.isDisableApiTermination())).end("disableApiTermination");
        } else if ("groupSet".equals(attribute)) {
            xml.start("groupSet");
            for (GroupIdentifier gi : inst.getSecurityGroups()) {
                xml.start("item")
                        .elem("groupId", gi.getGroupId())
                        .elem("groupName", gi.getGroupName())
                        .end("item");
            }
            xml.end("groupSet");
        }
        xml.end("DescribeInstanceAttributeResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyInstanceAttribute(MultivaluedMap<String, String> p, String region) {
        String instanceId = p.getFirst("InstanceId");
        // Find which attribute is being modified
        for (String attr : List.of("InstanceType.Value", "SourceDestCheck.Value", "EbsOptimized.Value")) {
            String val = p.getFirst(attr);
            if (val != null) {
                String attrName = attr.replace(".Value", "");
                attrName = Character.toLowerCase(attrName.charAt(0)) + attrName.substring(1);
                service.modifyInstanceAttribute(region, instanceId, attrName, val);
                break;
            }
        }
        // Security group reassignment: --groups maps to GroupId.1, GroupId.2, ...
        List<String> groupIds = new ArrayList<>();
        for (int i = 1; ; i++) {
            String groupId = p.getFirst("GroupId." + i);
            if (groupId == null) {
                break;
            }
            groupIds.add(groupId);
        }
        if (!groupIds.isEmpty()) {
            service.modifyInstanceGroups(region, instanceId, groupIds);
        }
        return booleanResponse("ModifyInstanceAttribute");
    }

    // lex00/floci#76: region-level defaults new instances inherit unless they set their own
    // metadata options (aws_ec2_instance_metadata_defaults) - one singleton per region, no
    // resource id, the same shape as ModifyVpcAttribute/DescribeVpcAttribute above but scoped to
    // the region rather than to a single resource.
    private Response handleModifyInstanceMetadataDefaults(MultivaluedMap<String, String> p, String region) {
        service.modifyInstanceMetadataDefaults(region, p.getFirst("HttpTokens"),
                p.containsKey("HttpPutResponseHopLimit") ? parseIntParam(p, "HttpPutResponseHopLimit", -1) : null,
                p.getFirst("HttpEndpoint"), p.getFirst("InstanceMetadataTags"));
        return booleanResponse("ModifyInstanceMetadataDefaults");
    }

    // lex00/floci#114 remainder: a launch (or ModifyInstanceMetadataOptions call) that names
    // MetadataOptions.HttpTokens explicitly must be honoured per-instance rather than answered
    // with a hardcoded "optional" regardless of what was requested - real AWS's own documented
    // default only applies when the caller specifies nothing at all.
    private InstanceMetadataRequest parseInstanceMetadataRequest(MultivaluedMap<String, String> p, String prefix) {
        String httpTokens = p.getFirst(prefix + "HttpTokens");
        Integer hopLimit = p.containsKey(prefix + "HttpPutResponseHopLimit")
                ? parseIntParam(p, prefix + "HttpPutResponseHopLimit", 1) : null;
        String httpEndpoint = p.getFirst(prefix + "HttpEndpoint");
        String httpProtocolIpv6 = p.getFirst(prefix + "HttpProtocolIpv6");
        String instanceMetadataTags = p.getFirst(prefix + "InstanceMetadataTags");
        return new InstanceMetadataRequest(httpTokens, hopLimit, httpEndpoint, httpProtocolIpv6, instanceMetadataTags);
    }

    private Response handleModifyInstanceMetadataOptions(MultivaluedMap<String, String> p, String region) {
        String instanceId = p.getFirst("InstanceId");
        Instance inst = service.modifyInstanceMetadataOptions(region, instanceId, parseInstanceMetadataRequest(p, ""));
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyInstanceMetadataOptionsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("instanceId", instanceId)
                .start("metadataOptions")
                .elem("state", "applied")
                .elem("httpTokens", inst.getMetadataHttpTokens())
                .elem("httpPutResponseHopLimit", String.valueOf(inst.getMetadataHttpPutResponseHopLimit()))
                .elem("httpEndpoint", inst.getMetadataHttpEndpoint())
                .elem("httpProtocolIpv6", inst.getMetadataHttpProtocolIpv6())
                .elem("instanceMetadataTags", inst.getMetadataInstanceMetadataTags())
                .end("metadataOptions")
                .end("ModifyInstanceMetadataOptionsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleGetInstanceMetadataDefaults(MultivaluedMap<String, String> p, String region) {
        InstanceMetadataDefaults defaults = service.getInstanceMetadataDefaults(region);
        XmlBuilder xml = new XmlBuilder()
                .start("GetInstanceMetadataDefaultsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("accountLevel")
                .elem("httpTokens", defaults.getHttpTokens())
                .elem("httpPutResponseHopLimit", String.valueOf(defaults.getHttpPutResponseHopLimit()))
                .elem("httpEndpoint", defaults.getHttpEndpoint())
                .elem("instanceMetadataTags", defaults.getInstanceMetadataTags())
                .elem("managedBy", defaults.getManagedBy())
                .end("accountLevel")
                .end("GetInstanceMetadataDefaultsResponse");
        return xmlResponse(xml.build());
    }

    // ─── VPC handlers ─────────────────────────────────────────────────────────

    private Response handleCreateVpc(MultivaluedMap<String, String> p, String region) {
        String cidrBlock = p.getFirst("CidrBlock");
        Vpc vpc = service.createVpc(region, cidrBlock, false);
        List<Tag> vpcTags = new ArrayList<>();
        for (int i = 1; ; i++) {
            String resType = p.getFirst("TagSpecification." + i + ".ResourceType");
            if (resType == null) break;
            if ("vpc".equals(resType)) {
                for (int j = 1; ; j++) {
                    String k = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Key");
                    if (k == null) break;
                    String v = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Value");
                    vpcTags.add(new Tag(k, v));
                }
            }
        }
        if (!vpcTags.isEmpty()) {
            service.createTags(region, List.of(vpc.getVpcId()), vpcTags);
        }
        XmlBuilder xml = new XmlBuilder()
                .start("CreateVpcResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpc").raw(vpcXml(vpc)).end("vpc")
                .end("CreateVpcResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeVpcs(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "VpcId");
        Map<String, List<String>> filters = getFilters(p);
        List<Vpc> vpcs = service.describeVpcs(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeVpcsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpcSet");
        for (Vpc vpc : vpcs) {
            xml.start("item").raw(vpcXml(vpc)).end("item");
        }
        xml.end("vpcSet").end("DescribeVpcsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteVpc(MultivaluedMap<String, String> p, String region) {
        service.deleteVpc(region, p.getFirst("VpcId"));
        return booleanResponse("DeleteVpc");
    }

    private Response handleModifyVpcAttribute(MultivaluedMap<String, String> p, String region) {
        String vpcId = p.getFirst("VpcId");
        if (p.containsKey("EnableDnsSupport.Value")) {
            service.modifyVpcAttribute(region, vpcId, "enableDnsSupport", p.getFirst("EnableDnsSupport.Value"));
        } else if (p.containsKey("EnableDnsHostnames.Value")) {
            service.modifyVpcAttribute(region, vpcId, "enableDnsHostnames", p.getFirst("EnableDnsHostnames.Value"));
        } else if (p.containsKey("EnableNetworkAddressUsageMetrics.Value")) {
            service.modifyVpcAttribute(region, vpcId, "enableNetworkAddressUsageMetrics", p.getFirst("EnableNetworkAddressUsageMetrics.Value"));
        }
        return booleanResponse("ModifyVpcAttribute");
    }

    private Response handleDescribeVpcAttribute(MultivaluedMap<String, String> p, String region) {
        String vpcId = p.getFirst("VpcId");
        String attribute = p.getFirst("Attribute");
        Vpc vpc = service.describeVpcAttribute(region, vpcId, attribute);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeVpcAttributeResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("vpcId", vpcId);
        if ("enableDnsSupport".equals(attribute)) {
            xml.start("enableDnsSupport").elem("value", String.valueOf(vpc.isEnableDnsSupport())).end("enableDnsSupport");
        } else if ("enableDnsHostnames".equals(attribute)) {
            xml.start("enableDnsHostnames").elem("value", String.valueOf(vpc.isEnableDnsHostnames())).end("enableDnsHostnames");
        } else if ("enableNetworkAddressUsageMetrics".equals(attribute)) {
            xml.start("enableNetworkAddressUsageMetrics").elem("value", String.valueOf(vpc.isEnableNetworkAddressUsageMetrics())).end("enableNetworkAddressUsageMetrics");
        }
        xml.end("DescribeVpcAttributeResponse");
        return xmlResponse(xml.build());
    }

    // ─── DHCP Options handlers ──────────────────────────────────────────────────

    private Response handleCreateDhcpOptions(MultivaluedMap<String, String> p, String region) {
        DhcpOptions options = service.createDhcpOptions(
                region,
                parseDhcpConfigurations(p),
                parseTagsForResource(p, "dhcp-options"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateDhcpOptionsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("dhcpOptions").raw(dhcpOptionsXml(options)).end("dhcpOptions")
                .end("CreateDhcpOptionsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeDhcpOptions(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "DhcpOptionsId");
        Map<String, List<String>> filters = getFilters(p);
        List<DhcpOptions> optionSets = service.describeDhcpOptions(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeDhcpOptionsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("dhcpOptionsSet");
        for (DhcpOptions options : optionSets) {
            xml.start("item").raw(dhcpOptionsXml(options)).end("item");
        }
        xml.end("dhcpOptionsSet").end("DescribeDhcpOptionsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteDhcpOptions(MultivaluedMap<String, String> p, String region) {
        service.deleteDhcpOptions(region, p.getFirst("DhcpOptionsId"));
        return booleanResponse("DeleteDhcpOptions");
    }

    private Response handleAssociateDhcpOptions(MultivaluedMap<String, String> p, String region) {
        service.associateDhcpOptions(region, p.getFirst("DhcpOptionsId"), p.getFirst("VpcId"));
        return booleanResponse("AssociateDhcpOptions");
    }

    private List<DhcpConfiguration> parseDhcpConfigurations(MultivaluedMap<String, String> p) {
        List<DhcpConfiguration> configurations = new ArrayList<>();
        for (int i = 1; ; i++) {
            String key = p.getFirst("DhcpConfiguration." + i + ".Key");
            if (key == null) break;
            List<String> values = new ArrayList<>();
            for (int j = 1; ; j++) {
                String value = p.getFirst("DhcpConfiguration." + i + ".Value." + j);
                if (value == null) break;
                values.add(value);
            }
            configurations.add(new DhcpConfiguration(key, values));
        }
        return configurations;
    }

    private String dhcpOptionsXml(DhcpOptions options) {
        XmlBuilder xml = new XmlBuilder()
                .elem("dhcpOptionsId", options.getDhcpOptionsId())
                .elem("ownerId", options.getOwnerId())
                .start("dhcpConfigurationSet");
        for (DhcpConfiguration config : options.getDhcpConfigurationSet()) {
            xml.start("item")
                    .elem("key", config.getKey())
                    .start("valueSet");
            for (String value : config.getValues()) {
                xml.start("item").elem("value", value).end("item");
            }
            xml.end("valueSet").end("item");
        }
        xml.end("dhcpConfigurationSet")
                .raw(tagSetXml(options.getTags()));
        return xml.build();
    }

    private Response handleDescribeVpcEndpointServices(MultivaluedMap<String, String> p, String region) {
        List<String> serviceNames = getList(p, "ServiceName");
        Map<String, List<String>> filters = getFilters(p);
        List<Map<String, String>> details = service.describeVpcEndpointServices(region, serviceNames, filters);
        List<Map<String, String>> azs = service.describeAvailabilityZones(region);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeVpcEndpointServicesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("serviceNameSet");
        for (Map<String, String> d : details) {
            xml.elem("item", d.get("serviceName"));
        }
        xml.end("serviceNameSet")
                .start("serviceDetailSet");
        for (Map<String, String> d : details) {
            xml.start("item")
                    .elem("serviceName", d.get("serviceName"))
                    .elem("serviceId", d.get("serviceId"))
                    .start("serviceType")
                        .start("item")
                            .elem("serviceType", d.get("serviceType"))
                        .end("item")
                    .end("serviceType")
                    .start("availabilityZoneSet");
            for (Map<String, String> az : azs) {
                xml.elem("item", az.get("zoneName"));
            }
            xml.end("availabilityZoneSet")
                    .elem("owner", d.get("owner"))
                    .start("baseEndpointDnsNameSet")
                        .elem("item", d.get("baseEndpointDnsName"))
                    .end("baseEndpointDnsNameSet")
                    .elem("privateDnsName", d.get("privateDnsName"))
                    .start("privateDnsNames")
                        .start("item")
                            .elem("privateDnsName", d.get("privateDnsName"))
                        .end("item")
                    .end("privateDnsNames")
                    .elem("vpcEndpointPolicySupported", d.get("vpcEndpointPolicySupported"))
                    .elem("acceptanceRequired", d.get("acceptanceRequired"))
                    .elem("managesVpcEndpoints", d.get("managesVpcEndpoints"))
                    .elem("privateDnsNameVerificationState", d.get("privateDnsNameVerificationState"))
                    .start("tagSet").end("tagSet")
                    .end("item");
        }
        xml.end("serviceDetailSet")
                .end("DescribeVpcEndpointServicesResponse");
        return xmlResponse(xml.build());
    }

    // ─── Flow Logs ────────────────────────────────────────────────────────────

    private Response handleCreateFlowLogs(MultivaluedMap<String, String> p, String region) {
        String resourceType = p.getFirst("ResourceType");
        List<String> resourceIds = getList(p, "ResourceId");
        String trafficType = p.getFirst("TrafficType");
        String logDestinationType = p.getFirst("LogDestinationType");
        String logDestination = p.getFirst("LogDestination");
        if (logDestination == null) {
            logDestination = p.getFirst("LogDestinationArn");
        }
        String deliverLogsPermissionArn = p.getFirst("DeliverLogsPermissionArn");
        String logFormat = p.getFirst("LogFormat");
        int maxAgg = parseIntParam(p, "MaxAggregationInterval", 600);

        if (resourceIds.isEmpty()) {
            // Some SDKs send ResourceIds.member.N — fall back to that prefix.
            resourceIds = getList(p, "ResourceIds.member");
        }
        if (resourceIds.isEmpty()) {
            return ec2Error("MissingParameter", "The request must contain at least one ResourceId.", 400);
        }

        XmlBuilder xml = new XmlBuilder()
                .start("CreateFlowLogsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("flowLogIdSet");
        for (String resourceId : resourceIds) {
            FlowLog fl = flowLogService.createFlowLog(region, resourceId, resourceType, trafficType,
                    logDestinationType, logDestination, deliverLogsPermissionArn, logFormat, maxAgg);
            // TagSpecification.N with ResourceType=vpc-flow-log, same shape every other
            // create handler in this file applies via applyResourceTags. FlowLog itself has no
            // model store registered in Ec2Service#tagTargets (it lives in FlowLogService), so
            // this lands in the generic CreateTags side-store, which handleDescribeFlowLogs
            // below reads back via Ec2Service#effectiveTags.
            applyResourceTags(p, region, "vpc-flow-log", fl.getFlowLogId());
            xml.elem("item", fl.getFlowLogId());
        }
        xml.end("flowLogIdSet")
                .start("unsuccessful").end("unsuccessful")
                .end("CreateFlowLogsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeFlowLogs(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "FlowLogId");
        if (ids.isEmpty()) {
            ids = getList(p, "FlowLogIds.member");
        }
        List<FlowLog> logs = flowLogService.describeFlowLogs(region, ids);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeFlowLogsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("flowLogSet");
        for (FlowLog fl : logs) {
            xml.start("item")
                    .elem("flowLogId", fl.getFlowLogId())
                    .elem("resourceId", fl.getResourceId())
                    .elem("trafficType", fl.getTrafficType())
                    .elem("logDestinationType", fl.getLogDestinationType())
                    .elem("logDestination", fl.getLogDestination())
                    .elem("deliverLogsPermissionArn", fl.getDeliverLogsPermissionArn())
                    .elem("flowLogStatus", fl.getFlowLogStatus())
                    .elem("deliverLogsStatus", fl.getDeliverLogsStatus())
                    .elem("maxAggregationInterval", String.valueOf(fl.getMaxAggregationInterval()))
                    .elem("creationTime", ISO_FMT.format(fl.getCreationTime()))
                    .raw(tagSetXml(service.effectiveTags(region, fl.getFlowLogId())))
                    .end("item");
        }
        xml.end("flowLogSet").end("DescribeFlowLogsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteFlowLogs(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "FlowLogId");
        if (ids.isEmpty()) {
            ids = getList(p, "FlowLogIds.member");
        }
        flowLogService.deleteFlowLogs(region, ids);
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteFlowLogsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("unsuccessful").end("unsuccessful")
                .end("DeleteFlowLogsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateVpcEndpoint(MultivaluedMap<String, String> p, String region) {
        VpcEndpoint endpoint = service.createVpcEndpoint(
                region,
                p.getFirst("VpcId"),
                p.getFirst("ServiceName"),
                p.getFirst("VpcEndpointType"),
                getList(p, "RouteTableId"),
                getList(p, "SubnetId"),
                getList(p, "SecurityGroupId"),
                p.getFirst("PrivateDnsEnabled") != null ? Boolean.valueOf(p.getFirst("PrivateDnsEnabled")) : null,
                p.getFirst("PolicyDocument"),
                parseTagsForResource(p, "vpc-endpoint"),
                parseSubnetConfigurations(p));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateVpcEndpointResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpcEndpoint").raw(vpcEndpointXml(endpoint)).end("vpcEndpoint")
                .end("CreateVpcEndpointResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeVpcEndpoints(MultivaluedMap<String, String> p, String region) {
        List<String> endpointIds = getList(p, "VpcEndpointId");
        Map<String, List<String>> filters = getFilters(p);
        List<VpcEndpoint> endpoints = service.describeVpcEndpoints(region, endpointIds, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeVpcEndpointsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpcEndpointSet");
        for (VpcEndpoint endpoint : endpoints) {
            xml.start("item").raw(vpcEndpointXml(endpoint)).end("item");
        }
        xml.end("vpcEndpointSet").end("DescribeVpcEndpointsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyVpcEndpoint(MultivaluedMap<String, String> p, String region) {
        service.modifyVpcEndpoint(
                region,
                p.getFirst("VpcEndpointId"),
                Boolean.parseBoolean(p.getFirst("ResetPolicy")),
                p.getFirst("PolicyDocument"),
                getList(p, "AddRouteTableId"),
                getList(p, "RemoveRouteTableId"),
                getList(p, "AddSubnetId"),
                getList(p, "RemoveSubnetId"),
                getList(p, "AddSecurityGroupId"),
                getList(p, "RemoveSecurityGroupId"),
                p.getFirst("PrivateDnsEnabled") != null ? Boolean.valueOf(p.getFirst("PrivateDnsEnabled")) : null,
                p.getFirst("IpAddressType"),
                p.getFirst("DnsOptions.DnsRecordIpType"));
        return booleanResponse("ModifyVpcEndpoint");
    }

    private Response handleDescribePrefixLists(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "PrefixListId");
        Map<String, List<String>> filters = getFilters(p);
        List<PrefixList> lists = service.describePrefixLists(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribePrefixListsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("prefixListSet");
        for (PrefixList pl : lists) {
            xml.start("item")
                    .elem("prefixListId", pl.getPrefixListId())
                    .elem("prefixListName", pl.getPrefixListName())
                    .start("cidrSet");
            for (String cidr : pl.getCidrs()) {
                xml.elem("item", cidr);
            }
            xml.end("cidrSet").end("item");
        }
        xml.end("prefixListSet").end("DescribePrefixListsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateManagedPrefixList(MultivaluedMap<String, String> p, String region) {
        ManagedPrefixList list = service.createManagedPrefixList(
                region,
                p.getFirst("PrefixListName"),
                p.getFirst("AddressFamily"),
                intOrNull(p, "MaxEntries"),
                parsePrefixListEntries(p, "Entry"),
                parseTagsForResource(p, "prefix-list"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateManagedPrefixListResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("prefixList").raw(managedPrefixListXml(list)).end("prefixList")
                .end("CreateManagedPrefixListResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeManagedPrefixLists(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "PrefixListId");
        Map<String, List<String>> filters = getFilters(p);
        List<ManagedPrefixList> lists = service.describeManagedPrefixLists(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeManagedPrefixListsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("prefixListSet");
        for (ManagedPrefixList list : lists) {
            xml.start("item").raw(managedPrefixListXml(list)).end("item");
        }
        xml.end("prefixListSet").end("DescribeManagedPrefixListsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleGetManagedPrefixListEntries(MultivaluedMap<String, String> p, String region) {
        List<PrefixListEntry> entries = service.getManagedPrefixListEntries(
                region, p.getFirst("PrefixListId"), longOrNull(p, "TargetVersion"));
        XmlBuilder xml = new XmlBuilder()
                .start("GetManagedPrefixListEntriesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("entrySet");
        for (PrefixListEntry entry : entries) {
            xml.start("item").elem("cidr", entry.getCidr());
            if (entry.getDescription() != null) {
                xml.elem("description", entry.getDescription());
            }
            xml.end("item");
        }
        xml.end("entrySet").end("GetManagedPrefixListEntriesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyManagedPrefixList(MultivaluedMap<String, String> p, String region) {
        List<PrefixListEntry> removeEntries = parsePrefixListEntries(p, "RemoveEntry");
        ManagedPrefixList list = service.modifyManagedPrefixList(
                region,
                p.getFirst("PrefixListId"),
                longOrNull(p, "CurrentVersion"),
                p.getFirst("PrefixListName"),
                intOrNull(p, "MaxEntries"),
                parsePrefixListEntries(p, "AddEntry"),
                removeEntries.stream().map(PrefixListEntry::getCidr).toList());
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyManagedPrefixListResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("prefixList").raw(managedPrefixListXml(list)).end("prefixList")
                .end("ModifyManagedPrefixListResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteManagedPrefixList(MultivaluedMap<String, String> p, String region) {
        ManagedPrefixList list = service.deleteManagedPrefixList(region, p.getFirst("PrefixListId"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteManagedPrefixListResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("prefixList").raw(managedPrefixListXml(list)).end("prefixList")
                .end("DeleteManagedPrefixListResponse");
        return xmlResponse(xml.build());
    }

    private String managedPrefixListXml(ManagedPrefixList list) {
        XmlBuilder xml = new XmlBuilder()
                .elem("prefixListId", list.getPrefixListId())
                .elem("addressFamily", list.getAddressFamily())
                .elem("state", list.getState());
        if (list.getStateMessage() != null) {
            xml.elem("stateMessage", list.getStateMessage());
        }
        xml.elem("prefixListArn", list.getPrefixListArn())
                .elem("prefixListName", list.getPrefixListName())
                .elem("maxEntries", list.getMaxEntries() == null ? 0 : list.getMaxEntries())
                .elem("version", list.getVersion())
                .elem("ownerId", list.getOwnerId());
        xml.raw(tagSetXml(list.getTags()));
        return xml.build();
    }

    // Entry lists arrive as Entry.N.Cidr / AddEntry.N.Cidr / RemoveEntry.N.Cidr. RemoveEntry
    // carries only a Cidr on the wire, which parses here as an entry with a null description.
    private List<PrefixListEntry> parsePrefixListEntries(MultivaluedMap<String, String> p, String prefix) {
        List<PrefixListEntry> entries = new ArrayList<>();
        for (int i = 1; ; i++) {
            String cidr = p.getFirst(prefix + "." + i + ".Cidr");
            if (cidr == null) break;
            entries.add(new PrefixListEntry(cidr, p.getFirst(prefix + "." + i + ".Description")));
        }
        return entries;
    }

    // Malformed numerics must surface as a client error, not escape the handler as an
    // unchecked NumberFormatException and turn into a 500. Only an absent parameter is null: a
    // present but blank value is malformed input, and treating it as absent would quietly drop
    // the conditional-version check on ModifyManagedPrefixList.
    private Integer intOrNull(MultivaluedMap<String, String> p, String name) {
        String value = p.getFirst(name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid value '" + value + "' for " + name + ".", 400);
        }
    }

    private Long longOrNull(MultivaluedMap<String, String> p, String name) {
        String value = p.getFirst(name);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid value '" + value + "' for " + name + ".", 400);
        }
    }

    private Response handleDeleteVpcEndpoints(MultivaluedMap<String, String> p, String region) {
        List<String> endpointIds = getList(p, "VpcEndpointId");
        service.deleteVpcEndpoints(region, endpointIds);
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteVpcEndpointsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("unsuccessful").end("unsuccessful")
                .end("DeleteVpcEndpointsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateDefaultVpc(MultivaluedMap<String, String> p, String region) {
        Vpc vpc = service.createDefaultVpc(region);
        XmlBuilder xml = new XmlBuilder()
                .start("CreateDefaultVpcResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpc").raw(vpcXml(vpc)).end("vpc")
                .end("CreateDefaultVpcResponse");
        return xmlResponse(xml.build());
    }

    private Response handleAssociateVpcCidrBlock(MultivaluedMap<String, String> p, String region) {
        String vpcId = p.getFirst("VpcId");
        String cidrBlock = p.getFirst("CidrBlock");
        VpcCidrBlockAssociation assoc = service.associateVpcCidrBlock(region, vpcId, cidrBlock);
        XmlBuilder xml = new XmlBuilder()
                .start("AssociateVpcCidrBlockResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("vpcId", vpcId)
                .start("cidrBlockAssociation")
                .elem("associationId", assoc.getAssociationId())
                .elem("cidrBlock", assoc.getCidrBlock())
                .elem("cidrBlockState", assoc.getCidrBlockState())
                .end("cidrBlockAssociation")
                .end("AssociateVpcCidrBlockResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDisassociateVpcCidrBlock(MultivaluedMap<String, String> p, String region) {
        String associationId = p.getFirst("AssociationId");
        service.disassociateVpcCidrBlock(region, associationId);
        return booleanResponse("DisassociateVpcCidrBlock");
    }

    // ─── Subnet handlers ──────────────────────────────────────────────────────

    private Response handleCreateSubnet(MultivaluedMap<String, String> p, String region) {
        String vpcId = p.getFirst("VpcId");
        String cidrBlock = p.getFirst("CidrBlock");
        String az = p.getFirst("AvailabilityZone");
        Subnet subnet = service.createSubnet(region, vpcId, cidrBlock, az);
        applyResourceTags(p, region, "subnet", subnet.getSubnetId());
        XmlBuilder xml = new XmlBuilder()
                .start("CreateSubnetResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("subnet").raw(subnetXml(subnet)).end("subnet")
                .end("CreateSubnetResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeSubnets(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "SubnetId");
        Map<String, List<String>> filters = getFilters(p);
        List<Subnet> subnets = service.describeSubnets(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeSubnetsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("subnetSet");
        for (Subnet s : subnets) {
            xml.start("item").raw(subnetXml(s)).end("item");
        }
        xml.end("subnetSet").end("DescribeSubnetsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteSubnet(MultivaluedMap<String, String> p, String region) {
        service.deleteSubnet(region, p.getFirst("SubnetId"));
        return booleanResponse("DeleteSubnet");
    }

    private Response handleModifySubnetAttribute(MultivaluedMap<String, String> p, String region) {
        String subnetId = p.getFirst("SubnetId");
        for (String attr : List.of(
                "MapPublicIpOnLaunch",
                "AssignIpv6AddressOnCreation",
                "EnableDns64",
                "MapCustomerOwnedIpOnLaunch")) {
            String val = p.getFirst(attr + ".Value");
            if (val != null) {
                String camel = Character.toLowerCase(attr.charAt(0)) + attr.substring(1);
                service.modifySubnetAttribute(region, subnetId, camel, val);
                break;
            }
        }
        return booleanResponse("ModifySubnetAttribute");
    }

    // ─── Security Group handlers ───────────────────────────────────────────────

    private Response handleCreateSecurityGroup(MultivaluedMap<String, String> p, String region) {
        String groupName = p.getFirst("GroupName");
        String description = p.getFirst("GroupDescription");
        String vpcId = p.getFirst("VpcId");
        SecurityGroup sg = service.createSecurityGroup(region, groupName, description, vpcId);
        applyResourceTags(p, region, "security-group", sg.getGroupId());
        XmlBuilder xml = new XmlBuilder()
                .start("CreateSecurityGroupResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("groupId", sg.getGroupId())
                .elem("return", "true")
                .end("CreateSecurityGroupResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeSecurityGroups(MultivaluedMap<String, String> p, String region) {
        List<String> groupIds = getList(p, "GroupId");
        List<String> groupNames = getList(p, "GroupName");
        Map<String, List<String>> filters = getFilters(p);
        List<SecurityGroup> sgs = service.describeSecurityGroups(region, groupIds, groupNames, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeSecurityGroupsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("securityGroupInfo");
        for (SecurityGroup sg : sgs) {
            xml.start("item").raw(sgXml(sg)).end("item");
        }
        xml.end("securityGroupInfo").end("DescribeSecurityGroupsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteSecurityGroup(MultivaluedMap<String, String> p, String region) {
        String groupId = p.getFirst("GroupId");
        if (groupId == null) groupId = p.getFirst("GroupName");
        service.deleteSecurityGroup(region, groupId);
        return booleanResponse("DeleteSecurityGroup");
    }

    private Response handleAuthorizeSecurityGroupIngress(MultivaluedMap<String, String> p, String region) {
        String groupId = p.getFirst("GroupId");
        List<IpPermission> perms = parseIpPermissions(p, "IpPermissions");
        List<SecurityGroupRule> rules = service.authorizeSecurityGroupIngress(region, groupId, perms);
        applySecurityGroupRuleTags(p, region, rules);
        XmlBuilder xml = new XmlBuilder()
                .start("AuthorizeSecurityGroupIngressResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("return", "true")
                .start("securityGroupRuleSet");
        for (SecurityGroupRule rule : rules) {
            xml.start("item").raw(sgRuleXml(rule)).end("item");
        }
        xml.end("securityGroupRuleSet").end("AuthorizeSecurityGroupIngressResponse");
        return xmlResponse(xml.build());
    }

    private Response handleAuthorizeSecurityGroupEgress(MultivaluedMap<String, String> p, String region) {
        String groupId = p.getFirst("GroupId");
        List<IpPermission> perms = parseIpPermissions(p, "IpPermissions");
        List<SecurityGroupRule> rules = service.authorizeSecurityGroupEgress(region, groupId, perms);
        applySecurityGroupRuleTags(p, region, rules);
        XmlBuilder xml = new XmlBuilder()
                .start("AuthorizeSecurityGroupEgressResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("return", "true")
                .start("securityGroupRuleSet");
        for (SecurityGroupRule rule : rules) {
            xml.start("item").raw(sgRuleXml(rule)).end("item");
        }
        xml.end("securityGroupRuleSet").end("AuthorizeSecurityGroupEgressResponse");
        return xmlResponse(xml.build());
    }

    private Response handleRevokeSecurityGroupIngress(MultivaluedMap<String, String> p, String region) {
        String groupId = p.getFirst("GroupId");
        List<IpPermission> perms = parseIpPermissions(p, "IpPermissions");
        service.revokeSecurityGroupIngress(region, groupId, perms);
        return booleanResponse("RevokeSecurityGroupIngress");
    }

    private Response handleRevokeSecurityGroupEgress(MultivaluedMap<String, String> p, String region) {
        String groupId = p.getFirst("GroupId");
        List<IpPermission> perms = parseIpPermissions(p, "IpPermissions");
        service.revokeSecurityGroupEgress(region, groupId, perms);
        return booleanResponse("RevokeSecurityGroupEgress");
    }

    private Response handleDescribeSecurityGroupRules(MultivaluedMap<String, String> p, String region) {
        Map<String, List<String>> filters = getFilters(p);
        // The AWS SDK sends the security group id as a filter with name "group-id". Rule ids can
        // arrive as the SecurityGroupRuleId.N parameter or the "security-group-rule-id" filter;
        // filters are conjunctive with parameters, so intersect rather than union when both appear.
        List<String> paramRuleIds = getList(p, "SecurityGroupRuleId");
        List<String> filterRuleIds = filters.getOrDefault("security-group-rule-id", List.of());
        List<String> ruleIds = paramRuleIds.isEmpty() || filterRuleIds.isEmpty()
                ? (paramRuleIds.isEmpty() ? filterRuleIds : paramRuleIds)
                : paramRuleIds.stream().filter(filterRuleIds::contains).toList();
        boolean unsatisfiable = !paramRuleIds.isEmpty() && !filterRuleIds.isEmpty() && ruleIds.isEmpty();
        List<String> groupIds = filters.getOrDefault("group-id", List.of());
        List<SecurityGroupRule> rules = unsatisfiable
                ? List.of()
                : service.describeSecurityGroupRules(region, groupIds, ruleIds);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeSecurityGroupRulesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("securityGroupRuleSet");
        for (SecurityGroupRule rule : rules) {
            xml.start("item").raw(sgRuleXml(rule)).end("item");
        }
        xml.end("securityGroupRuleSet").end("DescribeSecurityGroupRulesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifySecurityGroupRules(MultivaluedMap<String, String> p, String region) {
        String groupId = p.getFirst("GroupId");
        List<Map<String, String>> updates = new ArrayList<>();
        for (int i = 1; ; i++) {
            String ruleId = p.getFirst("SecurityGroupRule." + i + ".SecurityGroupRuleId");
            if (ruleId == null) break;
            Map<String, String> update = new LinkedHashMap<>();
            update.put("SecurityGroupRuleId", ruleId);
            String desc = p.getFirst("SecurityGroupRule." + i + ".SecurityGroupRuleRequest.Description");
            if (desc != null) update.put("Description", desc);
            updates.add(update);
        }
        service.modifySecurityGroupRules(region, groupId, updates);
        return booleanResponse("ModifySecurityGroupRules");
    }

    private Response handleUpdateSgRuleDescriptionsIngress(MultivaluedMap<String, String> p, String region) {
        String groupId = p.getFirst("GroupId");
        service.updateSecurityGroupRuleDescriptionsIngress(region, groupId, Collections.emptyList());
        return booleanResponse("UpdateSecurityGroupRuleDescriptionsIngress");
    }

    private Response handleUpdateSgRuleDescriptionsEgress(MultivaluedMap<String, String> p, String region) {
        String groupId = p.getFirst("GroupId");
        service.updateSecurityGroupRuleDescriptionsEgress(region, groupId, Collections.emptyList());
        return booleanResponse("UpdateSecurityGroupRuleDescriptionsEgress");
    }

    // ─── Key Pair handlers ────────────────────────────────────────────────────

    private Response handleCreateKeyPair(MultivaluedMap<String, String> p, String region) {
        String keyName = p.getFirst("KeyName");
        KeyPair kp = service.createKeyPair(region, keyName);
        XmlBuilder xml = new XmlBuilder()
                .start("CreateKeyPairResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("keyName", kp.getKeyName())
                .elem("keyFingerprint", kp.getKeyFingerprint())
                .elem("keyMaterial", kp.getKeyMaterial())
                .elem("keyPairId", kp.getKeyPairId())
                .end("CreateKeyPairResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeKeyPairs(MultivaluedMap<String, String> p, String region) {
        List<String> keyNames = getList(p, "KeyName");
        List<String> keyPairIds = getList(p, "KeyPairId");
        List<KeyPair> kps = service.describeKeyPairs(region, keyNames, keyPairIds);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeKeyPairsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("keySet");
        for (KeyPair kp : kps) {
            xml.start("item")
                    .elem("keyPairId", kp.getKeyPairId())
                    .elem("keyName", kp.getKeyName())
                    .elem("keyFingerprint", kp.getKeyFingerprint())
                    .raw(tagSetXml(kp.getTags()))
                    .end("item");
        }
        xml.end("keySet").end("DescribeKeyPairsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteKeyPair(MultivaluedMap<String, String> p, String region) {
        String keyName = p.getFirst("KeyName");
        String keyPairId = p.getFirst("KeyPairId");
        service.deleteKeyPair(region, keyName, keyPairId);
        return booleanResponse("DeleteKeyPair");
    }

    private Response handleImportKeyPair(MultivaluedMap<String, String> p, String region) {
        String keyName = p.getFirst("KeyName");
        String encoded = p.getFirst("PublicKeyMaterial");
        String publicKeyMaterial = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        KeyPair kp = service.importKeyPair(region, keyName, publicKeyMaterial);
        XmlBuilder xml = new XmlBuilder()
                .start("ImportKeyPairResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("keyName", kp.getKeyName())
                .elem("keyFingerprint", kp.getKeyFingerprint())
                .elem("keyPairId", kp.getKeyPairId())
                .end("ImportKeyPairResponse");
        return xmlResponse(xml.build());
    }

    // ─── AMI handlers ─────────────────────────────────────────────────────────

    private Response handleDescribeImages(MultivaluedMap<String, String> p, String region) {
        List<String> imageIds = getList(p, "ImageId");
        List<String> owners = getList(p, "Owner");
        Map<String, List<String>> filters = getFilters(p);
        List<Image> images = service.describeImages(region, imageIds, owners, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeImagesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("imagesSet");
        for (Image img : images) {
            xml.start("item")
                    .elem("imageId", img.getImageId())
                    .elem("imageLocation", img.getOwnerId() + "/" + img.getName())
                    .elem("imageState", img.getState())
                    .elem("imageOwnerId", img.getOwnerId())
                    .elem("isPublic", String.valueOf(img.isPublic()))
                    .elem("architecture", img.getArchitecture())
                    .elem("imageType", "machine")
                    .elem("name", img.getName())
                    .elem("description", img.getDescription())
                    .elem("rootDeviceType", img.getRootDeviceType())
                    .elem("rootDeviceName", img.getRootDeviceName())
                    .elem("virtualizationType", img.getVirtualizationType())
                    .elem("hypervisor", img.getHypervisor())
                    .elem("imageOwnerAlias", img.getImageOwnerAlias())
                    .elem("creationDate", img.getCreationDate())
                    .raw(blockDeviceMappingXml(img.getBlockDeviceMappings()))
                    .end("item");
        }
        xml.end("imagesSet").end("DescribeImagesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateImage(MultivaluedMap<String, String> p, String region) {
        Image image = service.createImage(
                region,
                p.getFirst("InstanceId"),
                p.getFirst("Name"),
                p.getFirst("Description"),
                Boolean.parseBoolean(p.getFirst("NoReboot")));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateImageResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("imageId", image.getImageId())
                .end("CreateImageResponse");
        return xmlResponse(xml.build());
    }

    private Response handleRegisterImage(MultivaluedMap<String, String> p, String region) {
        Image image = service.registerImage(
                region,
                p.getFirst("Name"),
                p.getFirst("Description"),
                p.getFirst("Architecture"),
                p.getFirst("RootDeviceName"),
                parseBlockDeviceMappings(p));
        XmlBuilder xml = new XmlBuilder()
                .start("RegisterImageResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("imageId", image.getImageId())
                .end("RegisterImageResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeSnapshots(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "SnapshotId");
        List<String> owners = getList(p, "Owner", "OwnerId", "OwnerIds");
        Map<String, List<String>> filters = getFilters(p);
        List<Snapshot> snapshots = service.describeSnapshots(region, ids, owners, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeSnapshotsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("snapshotSet");
        for (Snapshot snapshot : snapshots) {
            xml.start("item").raw(snapshotXml(snapshot)).end("item");
        }
        xml.end("snapshotSet").end("DescribeSnapshotsResponse");
        return xmlResponse(xml.build());
    }

    // ─── Snapshot block-public-access handlers ────────────────────────────────

    private Response handleEnableSnapshotBlockPublicAccess(MultivaluedMap<String, String> p, String region) {
        String state = service.enableSnapshotBlockPublicAccess(region, p.getFirst("State"));
        return snapshotBlockPublicAccessResponse("EnableSnapshotBlockPublicAccess", state, null);
    }

    private Response handleDisableSnapshotBlockPublicAccess(String region) {
        String state = service.disableSnapshotBlockPublicAccess(region);
        return snapshotBlockPublicAccessResponse("DisableSnapshotBlockPublicAccess", state, null);
    }

    private Response handleGetSnapshotBlockPublicAccessState(String region) {
        String state = service.getSnapshotBlockPublicAccessState(region);
        // The emulator has no declarative-policy layer, so the account always owns the state.
        return snapshotBlockPublicAccessResponse("GetSnapshotBlockPublicAccessState", state, "account");
    }

    private Response snapshotBlockPublicAccessResponse(String action, String state, String managedBy) {
        XmlBuilder xml = new XmlBuilder()
                .start(action + "Response", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("state", state)
                .elem("managedBy", managedBy)
                .end(action + "Response");
        return xmlResponse(xml.build());
    }

    // ─── Tag handlers ─────────────────────────────────────────────────────────

    private Response handleCreateTags(MultivaluedMap<String, String> p, String region) {
        List<String> resourceIds = getList(p, "ResourceId");
        List<Tag> tagList = new ArrayList<>();
        for (int i = 1; ; i++) {
            String k = p.getFirst("Tag." + i + ".Key");
            if (k == null) break;
            String v = p.getFirst("Tag." + i + ".Value");
            tagList.add(new Tag(k, v));
        }
        service.createTags(region, resourceIds, tagList);
        return booleanResponse("CreateTags");
    }

    private Response handleDeleteTags(MultivaluedMap<String, String> p, String region) {
        List<String> resourceIds = getList(p, "ResourceId");
        List<Tag> tagList = new ArrayList<>();
        for (int i = 1; ; i++) {
            String k = p.getFirst("Tag." + i + ".Key");
            if (k == null) break;
            String v = p.getFirst("Tag." + i + ".Value");
            tagList.add(new Tag(k, v));
        }
        service.deleteTags(region, resourceIds, tagList);
        return booleanResponse("DeleteTags");
    }

    private Response handleDescribeTags(MultivaluedMap<String, String> p, String region) {
        Map<String, List<String>> filters = getFilters(p);
        List<Map<String, String>> tagItems = service.describeTags(region, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTagsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("tagSet");
        for (Map<String, String> item : tagItems) {
            xml.start("item")
                    .elem("resourceId", item.get("resourceId"))
                    .elem("resourceType", item.get("resourceType"))
                    .elem("key", item.get("key"))
                    .elem("value", item.get("value"))
                    .end("item");
        }
        xml.end("tagSet").end("DescribeTagsResponse");
        return xmlResponse(xml.build());
    }

    // ─── Internet Gateway handlers ────────────────────────────────────────────

    private Response handleCreateInternetGateway(MultivaluedMap<String, String> p, String region) {
        InternetGateway igw = service.createInternetGateway(region);
        applyResourceTags(p, region, "internet-gateway", igw.getInternetGatewayId());
        XmlBuilder xml = new XmlBuilder()
                .start("CreateInternetGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("internetGateway").raw(igwXml(igw)).end("internetGateway")
                .end("CreateInternetGatewayResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeInternetGateways(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "InternetGatewayId");
        Map<String, List<String>> filters = getFilters(p);
        List<InternetGateway> igws = service.describeInternetGateways(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeInternetGatewaysResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("internetGatewaySet");
        for (InternetGateway igw : igws) {
            xml.start("item").raw(igwXml(igw)).end("item");
        }
        xml.end("internetGatewaySet").end("DescribeInternetGatewaysResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteInternetGateway(MultivaluedMap<String, String> p, String region) {
        service.deleteInternetGateway(region, p.getFirst("InternetGatewayId"));
        return booleanResponse("DeleteInternetGateway");
    }

    private Response handleAttachInternetGateway(MultivaluedMap<String, String> p, String region) {
        service.attachInternetGateway(region, p.getFirst("InternetGatewayId"), p.getFirst("VpcId"));
        return booleanResponse("AttachInternetGateway");
    }

    private Response handleDetachInternetGateway(MultivaluedMap<String, String> p, String region) {
        service.detachInternetGateway(region, p.getFirst("InternetGatewayId"), p.getFirst("VpcId"));
        return booleanResponse("DetachInternetGateway");
    }

    // ─── Route Table handlers ─────────────────────────────────────────────────

    private Response handleCreateRouteTable(MultivaluedMap<String, String> p, String region) {
        String vpcId = p.getFirst("VpcId");
        RouteTable rt = service.createRouteTable(region, vpcId);
        applyResourceTags(p, region, "route-table", rt.getRouteTableId());
        XmlBuilder xml = new XmlBuilder()
                .start("CreateRouteTableResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("routeTable").raw(routeTableXml(rt)).end("routeTable")
                .end("CreateRouteTableResponse");
        return xmlResponse(xml.build());
    }

    // ─── VPN Gateway handlers ─────────────────────────────────────────────────

    private Response handleCreateVpnGateway(MultivaluedMap<String, String> p, String region) {
        VpnGateway gateway = service.createVpnGateway(
                region,
                p.getFirst("Type"),
                p.getFirst("AvailabilityZone"),
                p.getFirst("AmazonSideAsn"));
        applyResourceTags(p, region, "vpn-gateway", gateway.getVpnGatewayId());
        XmlBuilder xml = new XmlBuilder()
                .start("CreateVpnGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpnGateway").raw(vpnGatewayXml(gateway)).end("vpnGateway")
                .end("CreateVpnGatewayResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeVpnGateways(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "VpnGatewayId");
        Map<String, List<String>> filters = getFilters(p);
        List<VpnGateway> gateways = service.describeVpnGateways(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeVpnGatewaysResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpnGatewaySet");
        for (VpnGateway gateway : gateways) {
            xml.start("item").raw(vpnGatewayXml(gateway)).end("item");
        }
        xml.end("vpnGatewaySet").end("DescribeVpnGatewaysResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteVpnGateway(MultivaluedMap<String, String> p, String region) {
        service.deleteVpnGateway(region, p.getFirst("VpnGatewayId"));
        return booleanResponse("DeleteVpnGateway");
    }

    private Response handleAttachVpnGateway(MultivaluedMap<String, String> p, String region) {
        VpcAttachment attachment = service.attachVpnGateway(region, p.getFirst("VpnGatewayId"), p.getFirst("VpcId"));
        XmlBuilder xml = new XmlBuilder()
                .start("AttachVpnGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("attachment")
                .elem("vpcId", attachment.getVpcId())
                .elem("state", attachment.getState())
                .end("attachment")
                .end("AttachVpnGatewayResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDetachVpnGateway(MultivaluedMap<String, String> p, String region) {
        service.detachVpnGateway(region, p.getFirst("VpnGatewayId"), p.getFirst("VpcId"));
        return booleanResponse("DetachVpnGateway");
    }

    private String vpnGatewayXml(VpnGateway gateway) {
        XmlBuilder xml = new XmlBuilder()
                .elem("vpnGatewayId", gateway.getVpnGatewayId())
                .elem("state", gateway.getState())
                .elem("type", gateway.getType())
                .elem("availabilityZone", gateway.getAvailabilityZone())
                .elem("amazonSideAsn", gateway.getAmazonSideAsn())
                .start("attachments");
        for (VpcAttachment attachment : gateway.getVpcAttachments()) {
            xml.start("item")
                    .elem("vpcId", attachment.getVpcId())
                    .elem("state", attachment.getState())
                    .end("item");
        }
        xml.end("attachments")
                .raw(tagSetXml(gateway.getTags()));
        return xml.build();
    }

    // ─── Customer Gateway handlers ────────────────────────────────────────────

    private Response handleCreateCustomerGateway(MultivaluedMap<String, String> p, String region) {
        CustomerGateway gateway = service.createCustomerGateway(
                region,
                p.getFirst("Type"),
                firstPresent(p, "IpAddress", "PublicIp"),
                p.getFirst("BgpAsn"),
                p.getFirst("BgpAsnExtended"),
                p.getFirst("CertificateArn"),
                p.getFirst("DeviceName"));
        applyResourceTags(p, region, "customer-gateway", gateway.getCustomerGatewayId());
        XmlBuilder xml = new XmlBuilder()
                .start("CreateCustomerGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("customerGateway").raw(customerGatewayXml(gateway)).end("customerGateway")
                .end("CreateCustomerGatewayResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeCustomerGateways(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "CustomerGatewayId");
        Map<String, List<String>> filters = getFilters(p);
        List<CustomerGateway> gateways = service.describeCustomerGateways(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeCustomerGatewaysResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("customerGatewaySet");
        for (CustomerGateway gateway : gateways) {
            xml.start("item").raw(customerGatewayXml(gateway)).end("item");
        }
        xml.end("customerGatewaySet").end("DescribeCustomerGatewaysResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteCustomerGateway(MultivaluedMap<String, String> p, String region) {
        service.deleteCustomerGateway(region, p.getFirst("CustomerGatewayId"));
        return booleanResponse("DeleteCustomerGateway");
    }

    private String customerGatewayXml(CustomerGateway gateway) {
        XmlBuilder xml = new XmlBuilder()
                .elem("customerGatewayId", gateway.getCustomerGatewayId())
                .elem("state", gateway.getState())
                .elem("type", gateway.getType())
                .elem("ipAddress", gateway.getIpAddress())
                .elem("bgpAsn", gateway.getBgpAsn())
                .elem("bgpAsnExtended", gateway.getBgpAsnExtended())
                .elem("certificateArn", gateway.getCertificateArn())
                .elem("deviceName", gateway.getDeviceName())
                .raw(tagSetXml(gateway.getTags()));
        return xml.build();
    }

    // ─── Capacity Reservation handlers ─────────────────────────────────────────

    private Response handleCreateCapacityReservation(MultivaluedMap<String, String> p, String region) {
        CapacityReservation reservation = service.createCapacityReservation(
                region,
                p.getFirst("InstanceType"),
                p.getFirst("InstancePlatform"),
                p.getFirst("AvailabilityZone"),
                intOrNull(p, "InstanceCount"),
                p.getFirst("Tenancy"),
                p.getFirst("EbsOptimized") != null ? Boolean.valueOf(p.getFirst("EbsOptimized")) : null,
                p.getFirst("EphemeralStorage") != null ? Boolean.valueOf(p.getFirst("EphemeralStorage")) : null,
                p.getFirst("EndDateType"),
                instantOrNull(p, "EndDate"),
                p.getFirst("InstanceMatchCriteria"),
                p.getFirst("OutpostArn"),
                p.getFirst("PlacementGroupArn"));
        applyResourceTags(p, region, "capacity-reservation", reservation.getCapacityReservationId());
        XmlBuilder xml = new XmlBuilder()
                .start("CreateCapacityReservationResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("capacityReservation").raw(capacityReservationXml(reservation)).end("capacityReservation")
                .end("CreateCapacityReservationResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeCapacityReservations(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "CapacityReservationId");
        Map<String, List<String>> filters = getFilters(p);
        List<CapacityReservation> reservations = service.describeCapacityReservations(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeCapacityReservationsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("capacityReservationSet");
        for (CapacityReservation reservation : reservations) {
            xml.start("item").raw(capacityReservationXml(reservation)).end("item");
        }
        xml.end("capacityReservationSet").end("DescribeCapacityReservationsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyCapacityReservation(MultivaluedMap<String, String> p, String region) {
        service.modifyCapacityReservation(
                region,
                p.getFirst("CapacityReservationId"),
                intOrNull(p, "InstanceCount"),
                instantOrNull(p, "EndDate"),
                p.getFirst("EndDateType"),
                p.getFirst("InstanceMatchCriteria"));
        return booleanResponse("ModifyCapacityReservation");
    }

    private Response handleCancelCapacityReservation(MultivaluedMap<String, String> p, String region) {
        service.cancelCapacityReservation(region, p.getFirst("CapacityReservationId"));
        return booleanResponse("CancelCapacityReservation");
    }

    private String capacityReservationXml(CapacityReservation reservation) {
        XmlBuilder xml = new XmlBuilder()
                .elem("capacityReservationId", reservation.getCapacityReservationId())
                .elem("ownerId", reservation.getOwnerId())
                .elem("capacityReservationArn", reservation.getCapacityReservationArn())
                .elem("availabilityZone", reservation.getAvailabilityZone())
                .elem("instanceType", reservation.getInstanceType())
                .elem("instancePlatform", reservation.getInstancePlatform())
                .elem("tenancy", reservation.getTenancy())
                .elem("totalInstanceCount", reservation.getTotalInstanceCount())
                .elem("availableInstanceCount", reservation.getAvailableInstanceCount())
                .elem("ebsOptimized", reservation.isEbsOptimized())
                .elem("ephemeralStorage", reservation.isEphemeralStorage())
                .elem("state", reservation.getState())
                .elem("startDate", reservation.getStartDate() != null ? ISO_FMT.format(reservation.getStartDate()) : null)
                .elem("endDate", reservation.getEndDate() != null ? ISO_FMT.format(reservation.getEndDate()) : null)
                .elem("endDateType", reservation.getEndDateType())
                .elem("instanceMatchCriteria", reservation.getInstanceMatchCriteria())
                .elem("createDate", reservation.getCreateDate() != null ? ISO_FMT.format(reservation.getCreateDate()) : null)
                .elem("outpostArn", reservation.getOutpostArn())
                .elem("placementGroupArn", reservation.getPlacementGroupArn())
                .raw(tagSetXml(reservation.getTags()));
        return xml.build();
    }

    private java.time.Instant instantOrNull(MultivaluedMap<String, String> p, String name) {
        String value = p.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new AwsException("InvalidParameterValue",
                    "The specified value for " + name + " is not valid.", 400);
        }
    }

    private Response handleDescribeRouteTables(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "RouteTableId");
        Map<String, List<String>> filters = getFilters(p);
        List<RouteTable> rts = service.describeRouteTables(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeRouteTablesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("routeTableSet");
        for (RouteTable rt : rts) {
            xml.start("item").raw(routeTableXml(rt)).end("item");
        }
        xml.end("routeTableSet").end("DescribeRouteTablesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteRouteTable(MultivaluedMap<String, String> p, String region) {
        service.deleteRouteTable(region, p.getFirst("RouteTableId"));
        return booleanResponse("DeleteRouteTable");
    }

    private Response handleAssociateRouteTable(MultivaluedMap<String, String> p, String region) {
        String rtId = p.getFirst("RouteTableId");
        String subnetId = p.getFirst("SubnetId");
        RouteTableAssociation assoc = service.associateRouteTable(region, rtId, subnetId);
        XmlBuilder xml = new XmlBuilder()
                .start("AssociateRouteTableResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("associationId", assoc.getRouteTableAssociationId())
                .start("associationState")
                .elem("state", assoc.getAssociationState())
                .end("associationState")
                .end("AssociateRouteTableResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDisassociateRouteTable(MultivaluedMap<String, String> p, String region) {
        service.disassociateRouteTable(region, p.getFirst("AssociationId"));
        return booleanResponse("DisassociateRouteTable");
    }

    private Response handleCreateRoute(MultivaluedMap<String, String> p, String region) {
        String rtId = p.getFirst("RouteTableId");
        String dest = p.getFirst("DestinationCidrBlock");
        String gwId = p.getFirst("GatewayId");
        String natGwId = p.getFirst("NatGatewayId");
        service.createRoute(region, rtId, dest, gwId, natGwId);
        return booleanResponse("CreateRoute");
    }

    private Response handleReplaceRoute(MultivaluedMap<String, String> p, String region) {
        // A route holds only a gateway or a NAT gateway here. Every other AWS target keeps the
        // UnsupportedOperation it returned before this action existed, rather than being accepted
        // and quietly clearing the route it was meant to repoint.
        for (String target : UNSUPPORTED_ROUTE_TARGETS) {
            if (p.getFirst(target) != null) {
                throw new AwsException("UnsupportedOperation",
                        "ReplaceRoute with " + target + " is not supported.", 400);
            }
        }
        String rtId = p.getFirst("RouteTableId");
        String dest = p.getFirst("DestinationCidrBlock");
        String gwId = p.getFirst("GatewayId");
        String natGwId = p.getFirst("NatGatewayId");
        // Resetting a route to the local target is expressible: `local` is the gateway id the
        // route table's built-in route already carries, so it needs no new field on Route.
        if (Boolean.parseBoolean(p.getFirst("LocalTarget"))) {
            if (gwId != null || natGwId != null) {
                throw new AwsException("InvalidParameterCombination",
                        "ReplaceRoute takes exactly one target.", 400);
            }
            gwId = LOCAL_GATEWAY_ID;
        }
        service.replaceRoute(region, rtId, dest, gwId, natGwId);
        return booleanResponse("ReplaceRoute");
    }

    private Response handleDeleteRoute(MultivaluedMap<String, String> p, String region) {
        String rtId = p.getFirst("RouteTableId");
        String dest = p.getFirst("DestinationCidrBlock");
        service.deleteRoute(region, rtId, dest);
        return booleanResponse("DeleteRoute");
    }

    // ─── Network ACL handlers ─────────────────────────────────────────────────

    private Response handleCreateNetworkAcl(MultivaluedMap<String, String> p, String region) {
        NetworkAcl acl = service.createNetworkAcl(region, p.getFirst("VpcId"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateNetworkAclResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("networkAcl").raw(networkAclXml(acl)).end("networkAcl")
                .end("CreateNetworkAclResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeNetworkAcls(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "NetworkAclId");
        Map<String, List<String>> filters = getFilters(p);
        List<NetworkAcl> acls = service.describeNetworkAcls(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeNetworkAclsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("networkAclSet");
        for (NetworkAcl acl : acls) {
            xml.start("item").raw(networkAclXml(acl)).end("item");
        }
        xml.end("networkAclSet").end("DescribeNetworkAclsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteNetworkAcl(MultivaluedMap<String, String> p, String region) {
        service.deleteNetworkAcl(region, p.getFirst("NetworkAclId"));
        return booleanResponse("DeleteNetworkAcl");
    }

    private Response handleNetworkAclEntry(MultivaluedMap<String, String> p, String region, String action) {
        String fromStr = p.getFirst("PortRange.From");
        String toStr = p.getFirst("PortRange.To");
        service.createNetworkAclEntry(region,
                p.getFirst("NetworkAclId"),
                Integer.parseInt(p.getFirst("RuleNumber")),
                p.getFirst("Protocol"),
                p.getFirst("RuleAction"),
                Boolean.parseBoolean(p.getFirst("Egress")),
                p.getFirst("CidrBlock"),
                p.getFirst("Ipv6CidrBlock"),
                fromStr != null ? Integer.valueOf(fromStr) : null,
                toStr != null ? Integer.valueOf(toStr) : null,
                "ReplaceNetworkAclEntry".equals(action));
        return booleanResponse(action);
    }

    private Response handleDeleteNetworkAclEntry(MultivaluedMap<String, String> p, String region) {
        service.deleteNetworkAclEntry(region,
                p.getFirst("NetworkAclId"),
                Integer.parseInt(p.getFirst("RuleNumber")),
                Boolean.parseBoolean(p.getFirst("Egress")));
        return booleanResponse("DeleteNetworkAclEntry");
    }

    private Response handleReplaceNetworkAclAssociation(MultivaluedMap<String, String> p, String region) {
        NetworkAclAssociation assoc = service.replaceNetworkAclAssociation(region,
                p.getFirst("AssociationId"), p.getFirst("NetworkAclId"));
        XmlBuilder xml = new XmlBuilder()
                .start("ReplaceNetworkAclAssociationResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("newAssociationId", assoc.getNetworkAclAssociationId())
                .end("ReplaceNetworkAclAssociationResponse");
        return xmlResponse(xml.build());
    }

    // ─── NAT Gateway handlers ─────────────────────────────────────────────────

    private Response handleCreateNatGateway(MultivaluedMap<String, String> p, String region) {
        NatGateway natGateway = service.createNatGateway(
                region,
                p.getFirst("SubnetId"),
                p.getFirst("AllocationId"),
                p.getFirst("ConnectivityType"),
                parseTagsForResource(p, "natgateway"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateNatGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("natGateway").raw(natGatewayXml(natGateway)).end("natGateway")
                .end("CreateNatGatewayResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeNatGateways(MultivaluedMap<String, String> p, String region) {
        List<String> natGatewayIds = getList(p, "NatGatewayId");
        Map<String, List<String>> filters = getFilters(p);
        List<NatGateway> natGateways = service.describeNatGateways(region, natGatewayIds, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeNatGatewaysResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("natGatewaySet");
        for (NatGateway natGateway : natGateways) {
            xml.start("item").raw(natGatewayXml(natGateway)).end("item");
        }
        xml.end("natGatewaySet").end("DescribeNatGatewaysResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteNatGateway(MultivaluedMap<String, String> p, String region) {
        NatGateway natGateway = service.deleteNatGateway(region, p.getFirst("NatGatewayId"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteNatGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("natGateway").raw(natGatewayXml(natGateway)).end("natGateway")
                .end("DeleteNatGatewayResponse");
        return xmlResponse(xml.build());
    }

    // ─── Transit Gateway handlers ─────────────────────────────────────────────

    private Response handleCreateTransitGateway(MultivaluedMap<String, String> p, String region) {
        TransitGatewayOptions options = new TransitGatewayOptions();
        Long amazonSideAsn = longOrNull(p, "Options.AmazonSideAsn");
        if (amazonSideAsn != null) {
            options.setAmazonSideAsn(amazonSideAsn);
        }
        applyIfPresent(p, "Options.AutoAcceptSharedAttachments", options::setAutoAcceptSharedAttachments);
        applyIfPresent(p, "Options.DefaultRouteTableAssociation", options::setDefaultRouteTableAssociation);
        applyIfPresent(p, "Options.DefaultRouteTablePropagation", options::setDefaultRouteTablePropagation);
        applyIfPresent(p, "Options.VpnEcmpSupport", options::setVpnEcmpSupport);
        applyIfPresent(p, "Options.DnsSupport", options::setDnsSupport);
        applyIfPresent(p, "Options.SecurityGroupReferencingSupport", options::setSecurityGroupReferencingSupport);
        applyIfPresent(p, "Options.MulticastSupport", options::setMulticastSupport);
        options.setTransitGatewayCidrBlocks(getList(p, "Options.TransitGatewayCidrBlocks"));

        TransitGateway gateway = service.createTransitGateway(region, p.getFirst("Description"), options,
                parseTagsForResource(p, "transit-gateway"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateTransitGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGateway").raw(transitGatewayXml(gateway)).end("transitGateway")
                .end("CreateTransitGatewayResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeTransitGateways(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "TransitGatewayIds", "TransitGatewayId");
        Map<String, List<String>> filters = getFilters(p);
        List<TransitGateway> gateways = service.describeTransitGateways(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTransitGatewaysResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewaySet");
        for (TransitGateway gateway : gateways) {
            xml.start("item").raw(transitGatewayXml(gateway)).end("item");
        }
        xml.end("transitGatewaySet").end("DescribeTransitGatewaysResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyTransitGateway(MultivaluedMap<String, String> p, String region) {
        TransitGateway gateway = service.modifyTransitGateway(
                region,
                p.getFirst("TransitGatewayId"),
                p.getFirst("Description"),
                getList(p, "Options.AddTransitGatewayCidrBlocks"),
                getList(p, "Options.RemoveTransitGatewayCidrBlocks"),
                longOrNull(p, "Options.AmazonSideAsn"),
                p.getFirst("Options.AutoAcceptSharedAttachments"),
                p.getFirst("Options.DefaultRouteTableAssociation"),
                p.getFirst("Options.AssociationDefaultRouteTableId"),
                p.getFirst("Options.DefaultRouteTablePropagation"),
                p.getFirst("Options.PropagationDefaultRouteTableId"),
                p.getFirst("Options.VpnEcmpSupport"),
                p.getFirst("Options.DnsSupport"),
                p.getFirst("Options.SecurityGroupReferencingSupport"));
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyTransitGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGateway").raw(transitGatewayXml(gateway)).end("transitGateway")
                .end("ModifyTransitGatewayResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteTransitGateway(MultivaluedMap<String, String> p, String region) {
        TransitGateway gateway = service.deleteTransitGateway(region, p.getFirst("TransitGatewayId"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteTransitGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGateway").raw(transitGatewayXml(gateway)).end("transitGateway")
                .end("DeleteTransitGatewayResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateTransitGatewayVpcAttachment(MultivaluedMap<String, String> p, String region) {
        TransitGatewayVpcAttachmentOptions options = new TransitGatewayVpcAttachmentOptions();
        applyIfPresent(p, "Options.DnsSupport", options::setDnsSupport);
        applyIfPresent(p, "Options.SecurityGroupReferencingSupport", options::setSecurityGroupReferencingSupport);
        applyIfPresent(p, "Options.Ipv6Support", options::setIpv6Support);
        applyIfPresent(p, "Options.ApplianceModeSupport", options::setApplianceModeSupport);

        TransitGatewayVpcAttachment attachment = service.createTransitGatewayVpcAttachment(
                region,
                p.getFirst("TransitGatewayId"),
                p.getFirst("VpcId"),
                getList(p, "SubnetIds", "SubnetId"),
                options,
                parseTagsForResource(p, "transit-gateway-attachment"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateTransitGatewayVpcAttachmentResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayVpcAttachment").raw(transitGatewayVpcAttachmentXml(attachment))
                .end("transitGatewayVpcAttachment")
                .end("CreateTransitGatewayVpcAttachmentResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeTransitGatewayVpcAttachments(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "TransitGatewayAttachmentIds", "TransitGatewayAttachmentId");
        Map<String, List<String>> filters = getFilters(p);
        List<TransitGatewayVpcAttachment> attachments =
                service.describeTransitGatewayVpcAttachments(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTransitGatewayVpcAttachmentsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayVpcAttachments");
        for (TransitGatewayVpcAttachment attachment : attachments) {
            xml.start("item").raw(transitGatewayVpcAttachmentXml(attachment)).end("item");
        }
        xml.end("transitGatewayVpcAttachments").end("DescribeTransitGatewayVpcAttachmentsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyTransitGatewayVpcAttachment(MultivaluedMap<String, String> p, String region) {
        TransitGatewayVpcAttachment attachment = service.modifyTransitGatewayVpcAttachment(
                region,
                p.getFirst("TransitGatewayAttachmentId"),
                getList(p, "AddSubnetIds", "AddSubnetId"),
                getList(p, "RemoveSubnetIds", "RemoveSubnetId"),
                p.getFirst("Options.DnsSupport"),
                p.getFirst("Options.SecurityGroupReferencingSupport"),
                p.getFirst("Options.Ipv6Support"),
                p.getFirst("Options.ApplianceModeSupport"));
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyTransitGatewayVpcAttachmentResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayVpcAttachment").raw(transitGatewayVpcAttachmentXml(attachment))
                .end("transitGatewayVpcAttachment")
                .end("ModifyTransitGatewayVpcAttachmentResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteTransitGatewayVpcAttachment(MultivaluedMap<String, String> p, String region) {
        TransitGatewayVpcAttachment attachment = service.deleteTransitGatewayVpcAttachment(
                region, p.getFirst("TransitGatewayAttachmentId"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteTransitGatewayVpcAttachmentResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayVpcAttachment").raw(transitGatewayVpcAttachmentXml(attachment))
                .end("transitGatewayVpcAttachment")
                .end("DeleteTransitGatewayVpcAttachmentResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateTransitGatewayRouteTable(MultivaluedMap<String, String> p, String region) {
        TransitGatewayRouteTable table = service.createTransitGatewayRouteTable(
                region, p.getFirst("TransitGatewayId"),
                parseTagsForResource(p, "transit-gateway-route-table"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateTransitGatewayRouteTableResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayRouteTable").raw(transitGatewayRouteTableXml(table))
                .end("transitGatewayRouteTable")
                .end("CreateTransitGatewayRouteTableResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeTransitGatewayRouteTables(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "TransitGatewayRouteTableIds", "TransitGatewayRouteTableId");
        Map<String, List<String>> filters = getFilters(p);
        List<TransitGatewayRouteTable> routeTables =
                service.describeTransitGatewayRouteTables(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTransitGatewayRouteTablesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayRouteTables");
        for (TransitGatewayRouteTable table : routeTables) {
            xml.start("item").raw(transitGatewayRouteTableXml(table)).end("item");
        }
        xml.end("transitGatewayRouteTables").end("DescribeTransitGatewayRouteTablesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteTransitGatewayRouteTable(MultivaluedMap<String, String> p, String region) {
        TransitGatewayRouteTable table = service.deleteTransitGatewayRouteTable(
                region, p.getFirst("TransitGatewayRouteTableId"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteTransitGatewayRouteTableResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayRouteTable").raw(transitGatewayRouteTableXml(table))
                .end("transitGatewayRouteTable")
                .end("DeleteTransitGatewayRouteTableResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateTransitGatewayRoute(MultivaluedMap<String, String> p, String region) {
        TransitGatewayRoute route = service.createTransitGatewayRoute(
                region,
                p.getFirst("TransitGatewayRouteTableId"),
                p.getFirst("DestinationCidrBlock"),
                p.getFirst("TransitGatewayAttachmentId"),
                Boolean.parseBoolean(p.getFirst("Blackhole")));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateTransitGatewayRouteResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("route").raw(transitGatewayRouteXml(route)).end("route")
                .end("CreateTransitGatewayRouteResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteTransitGatewayRoute(MultivaluedMap<String, String> p, String region) {
        TransitGatewayRoute route = service.deleteTransitGatewayRoute(
                region, p.getFirst("TransitGatewayRouteTableId"), p.getFirst("DestinationCidrBlock"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteTransitGatewayRouteResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("route").raw(transitGatewayRouteXml(route)).end("route")
                .end("DeleteTransitGatewayRouteResponse");
        return xmlResponse(xml.build());
    }

    private Response handleSearchTransitGatewayRoutes(MultivaluedMap<String, String> p, String region) {
        List<TransitGatewayRoute> routes = service.searchTransitGatewayRoutes(
                region, p.getFirst("TransitGatewayRouteTableId"), getFilters(p));
        XmlBuilder xml = new XmlBuilder()
                .start("SearchTransitGatewayRoutesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("routeSet");
        for (TransitGatewayRoute route : routes) {
            xml.start("item").raw(transitGatewayRouteXml(route)).end("item");
        }
        // Every match is in this page, so there is never a second one to fetch.
        xml.end("routeSet")
                .elem("additionalRoutesAvailable", false)
                .end("SearchTransitGatewayRoutesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleAssociateTransitGatewayRouteTable(MultivaluedMap<String, String> p, String region) {
        String routeTableId = p.getFirst("TransitGatewayRouteTableId");
        TransitGatewayRouteTableAssociation association = service.associateTransitGatewayRouteTable(
                region, routeTableId, p.getFirst("TransitGatewayAttachmentId"));
        return transitGatewayAssociationResponse("AssociateTransitGatewayRouteTable", routeTableId, association);
    }

    private Response handleDisassociateTransitGatewayRouteTable(MultivaluedMap<String, String> p, String region) {
        String routeTableId = p.getFirst("TransitGatewayRouteTableId");
        TransitGatewayRouteTableAssociation association = service.disassociateTransitGatewayRouteTable(
                region, routeTableId, p.getFirst("TransitGatewayAttachmentId"));
        return transitGatewayAssociationResponse("DisassociateTransitGatewayRouteTable", routeTableId, association);
    }

    private Response transitGatewayAssociationResponse(String action, String routeTableId,
                                                       TransitGatewayRouteTableAssociation association) {
        XmlBuilder xml = new XmlBuilder()
                .start(action + "Response", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("association")
                .elem("transitGatewayRouteTableId", routeTableId)
                .elem("transitGatewayAttachmentId", association.getTransitGatewayAttachmentId())
                .elem("resourceId", association.getResourceId())
                .elem("resourceType", association.getResourceType())
                .elem("state", association.getState())
                .end("association")
                .end(action + "Response");
        return xmlResponse(xml.build());
    }

    private Response handleEnableTransitGatewayRouteTablePropagation(MultivaluedMap<String, String> p,
                                                                     String region) {
        String routeTableId = p.getFirst("TransitGatewayRouteTableId");
        TransitGatewayRouteTablePropagation propagation = service.enableTransitGatewayRouteTablePropagation(
                region, routeTableId, p.getFirst("TransitGatewayAttachmentId"));
        return transitGatewayPropagationResponse("EnableTransitGatewayRouteTablePropagation",
                routeTableId, propagation);
    }

    private Response handleDisableTransitGatewayRouteTablePropagation(MultivaluedMap<String, String> p,
                                                                      String region) {
        String routeTableId = p.getFirst("TransitGatewayRouteTableId");
        TransitGatewayRouteTablePropagation propagation = service.disableTransitGatewayRouteTablePropagation(
                region, routeTableId, p.getFirst("TransitGatewayAttachmentId"));
        return transitGatewayPropagationResponse("DisableTransitGatewayRouteTablePropagation",
                routeTableId, propagation);
    }

    private Response transitGatewayPropagationResponse(String action, String routeTableId,
                                                       TransitGatewayRouteTablePropagation propagation) {
        XmlBuilder xml = new XmlBuilder()
                .start(action + "Response", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("propagation")
                .elem("transitGatewayAttachmentId", propagation.getTransitGatewayAttachmentId())
                .elem("resourceId", propagation.getResourceId())
                .elem("resourceType", propagation.getResourceType())
                .elem("transitGatewayRouteTableId", routeTableId)
                .elem("state", propagation.getState())
                .end("propagation")
                .end(action + "Response");
        return xmlResponse(xml.build());
    }

    private Response handleGetTransitGatewayRouteTableAssociations(MultivaluedMap<String, String> p,
                                                                   String region) {
        List<TransitGatewayRouteTableAssociation> associations =
                service.getTransitGatewayRouteTableAssociations(
                        region, p.getFirst("TransitGatewayRouteTableId"), getFilters(p));
        XmlBuilder xml = new XmlBuilder()
                .start("GetTransitGatewayRouteTableAssociationsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("associations");
        for (TransitGatewayRouteTableAssociation association : associations) {
            xml.start("item")
                    .elem("transitGatewayAttachmentId", association.getTransitGatewayAttachmentId())
                    .elem("resourceId", association.getResourceId())
                    .elem("resourceType", association.getResourceType())
                    .elem("state", association.getState())
                    .end("item");
        }
        xml.end("associations").end("GetTransitGatewayRouteTableAssociationsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleGetTransitGatewayRouteTablePropagations(MultivaluedMap<String, String> p,
                                                                   String region) {
        List<TransitGatewayRouteTablePropagation> propagations =
                service.getTransitGatewayRouteTablePropagations(
                        region, p.getFirst("TransitGatewayRouteTableId"), getFilters(p));
        XmlBuilder xml = new XmlBuilder()
                .start("GetTransitGatewayRouteTablePropagationsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayRouteTablePropagations");
        for (TransitGatewayRouteTablePropagation propagation : propagations) {
            xml.start("item")
                    .elem("transitGatewayAttachmentId", propagation.getTransitGatewayAttachmentId())
                    .elem("resourceId", propagation.getResourceId())
                    .elem("resourceType", propagation.getResourceType())
                    .elem("state", propagation.getState())
                    .end("item");
        }
        xml.end("transitGatewayRouteTablePropagations")
                .end("GetTransitGatewayRouteTablePropagationsResponse");
        return xmlResponse(xml.build());
    }

    private String transitGatewayXml(TransitGateway gateway) {
        XmlBuilder xml = new XmlBuilder()
                .elem("transitGatewayId", gateway.getTransitGatewayId())
                .elem("transitGatewayArn", gateway.getTransitGatewayArn())
                .elem("state", gateway.getState())
                .elem("ownerId", gateway.getOwnerId())
                .elem("description", gateway.getDescription());
        if (gateway.getCreationTime() != null) {
            xml.elem("creationTime", ISO_FMT.format(gateway.getCreationTime()));
        }
        TransitGatewayOptions options = gateway.getOptions();
        xml.start("options")
                .elem("amazonSideAsn", options.getAmazonSideAsn() == null ? 0 : options.getAmazonSideAsn())
                .start("transitGatewayCidrBlocks");
        for (String cidrBlock : options.getTransitGatewayCidrBlocks()) {
            xml.elem("item", cidrBlock);
        }
        xml.end("transitGatewayCidrBlocks")
                .elem("autoAcceptSharedAttachments", options.getAutoAcceptSharedAttachments())
                .elem("defaultRouteTableAssociation", options.getDefaultRouteTableAssociation())
                .elem("associationDefaultRouteTableId", options.getAssociationDefaultRouteTableId())
                .elem("defaultRouteTablePropagation", options.getDefaultRouteTablePropagation())
                .elem("propagationDefaultRouteTableId", options.getPropagationDefaultRouteTableId())
                .elem("vpnEcmpSupport", options.getVpnEcmpSupport())
                .elem("dnsSupport", options.getDnsSupport())
                .elem("securityGroupReferencingSupport", options.getSecurityGroupReferencingSupport())
                .elem("multicastSupport", options.getMulticastSupport())
                .end("options")
                .raw(tagSetXml(gateway.getTags()));
        return xml.build();
    }

    private String transitGatewayVpcAttachmentXml(TransitGatewayVpcAttachment attachment) {
        XmlBuilder xml = new XmlBuilder()
                .elem("transitGatewayAttachmentId", attachment.getTransitGatewayAttachmentId())
                .elem("transitGatewayId", attachment.getTransitGatewayId())
                .elem("vpcId", attachment.getVpcId())
                .elem("vpcOwnerId", attachment.getVpcOwnerId())
                .elem("state", attachment.getState())
                .start("subnetIds");
        for (String subnetId : attachment.getSubnetIds()) {
            xml.elem("item", subnetId);
        }
        xml.end("subnetIds");
        if (attachment.getCreationTime() != null) {
            xml.elem("creationTime", ISO_FMT.format(attachment.getCreationTime()));
        }
        TransitGatewayVpcAttachmentOptions options = attachment.getOptions();
        xml.start("options")
                .elem("dnsSupport", options.getDnsSupport())
                .elem("securityGroupReferencingSupport", options.getSecurityGroupReferencingSupport())
                .elem("ipv6Support", options.getIpv6Support())
                .elem("applianceModeSupport", options.getApplianceModeSupport())
                .end("options")
                .raw(tagSetXml(attachment.getTags()));
        return xml.build();
    }

    private String transitGatewayRouteTableXml(TransitGatewayRouteTable table) {
        XmlBuilder xml = new XmlBuilder()
                .elem("transitGatewayRouteTableId", table.getTransitGatewayRouteTableId())
                .elem("transitGatewayId", table.getTransitGatewayId())
                .elem("state", table.getState())
                .elem("defaultAssociationRouteTable", table.isDefaultAssociationRouteTable())
                .elem("defaultPropagationRouteTable", table.isDefaultPropagationRouteTable());
        if (table.getCreationTime() != null) {
            xml.elem("creationTime", ISO_FMT.format(table.getCreationTime()));
        }
        xml.raw(tagSetXml(table.getTags()));
        return xml.build();
    }

    private String transitGatewayRouteXml(TransitGatewayRoute route) {
        XmlBuilder xml = new XmlBuilder()
                .elem("destinationCidrBlock", route.getDestinationCidrBlock())
                .elem("prefixListId", route.getPrefixListId())
                .start("transitGatewayAttachments");
        for (TransitGatewayRouteAttachment attachment : route.getTransitGatewayAttachments()) {
            xml.start("item")
                    .elem("resourceId", attachment.getResourceId())
                    .elem("transitGatewayAttachmentId", attachment.getTransitGatewayAttachmentId())
                    .elem("resourceType", attachment.getResourceType())
                    .end("item");
        }
        xml.end("transitGatewayAttachments")
                .elem("type", route.getType())
                .elem("state", route.getState());
        return xml.build();
    }

    // ─── Elastic IP handlers ──────────────────────────────────────────────────

    private Response handleAllocateAddress(MultivaluedMap<String, String> p, String region) {
        Address addr = service.allocateAddress(region);
        applyResourceTags(p, region, "elastic-ip", addr.getAllocationId());
        XmlBuilder xml = new XmlBuilder()
                .start("AllocateAddressResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("publicIp", addr.getPublicIp())
                .elem("domain", addr.getDomain())
                .elem("allocationId", addr.getAllocationId())
                .end("AllocateAddressResponse");
        return xmlResponse(xml.build());
    }

    private Response handleAssociateAddress(MultivaluedMap<String, String> p, String region) {
        String allocationId = p.getFirst("AllocationId");
        String instanceId = p.getFirst("InstanceId");
        Address addr = service.associateAddress(region, allocationId, instanceId);
        XmlBuilder xml = new XmlBuilder()
                .start("AssociateAddressResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("associationId", addr.getAssociationId())
                .end("AssociateAddressResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDisassociateAddress(MultivaluedMap<String, String> p, String region) {
        service.disassociateAddress(region, p.getFirst("AssociationId"));
        return booleanResponse("DisassociateAddress");
    }

    private Response handleReleaseAddress(MultivaluedMap<String, String> p, String region) {
        service.releaseAddress(region, p.getFirst("AllocationId"));
        return booleanResponse("ReleaseAddress");
    }

    private Response handleDescribeAddresses(MultivaluedMap<String, String> p, String region) {
        List<String> allocationIds = getList(p, "AllocationId");
        Map<String, List<String>> filters = getFilters(p);
        List<Address> addrs = service.describeAddresses(region, allocationIds, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeAddressesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("addressesSet");
        for (Address addr : addrs) {
            xml.start("item").raw(addressXml(addr)).end("item");
        }
        xml.end("addressesSet").end("DescribeAddressesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeAddressesAttribute(MultivaluedMap<String, String> p, String region) {
        List<String> allocationIds = getList(p, "AllocationId");
        List<Address> addrs = service.describeAddresses(region, allocationIds, Map.of());
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeAddressesAttributeResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("addressSet");
        for (Address addr : addrs) {
            // AddressAttribute carries allocationId, publicIp and (optionally) ptrRecord.
            // Floci does not model reverse DNS, so ptrRecord is omitted (null), matching
            // real EC2 behaviour for EIPs without a configured PTR record.
            xml.start("item")
                    .elem("allocationId", addr.getAllocationId())
                    .elem("publicIp", addr.getPublicIp())
                    .end("item");
        }
        xml.end("addressSet").end("DescribeAddressesAttributeResponse");
        return xmlResponse(xml.build());
    }

    // ─── Region / AZ / Account handlers ──────────────────────────────────────

    private Response handleDescribeAvailabilityZones(MultivaluedMap<String, String> p, String region) {
        List<Map<String, String>> zones = service.describeAvailabilityZones(region);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeAvailabilityZonesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("availabilityZoneInfo");
        for (Map<String, String> az : zones) {
            xml.start("item")
                    .elem("zoneName", az.get("zoneName"))
                    .elem("zoneState", az.get("state"))
                    .elem("regionName", az.get("regionName"))
                    .elem("zoneId", az.get("zoneId"))
                    .elem("zoneType", az.get("zoneType"))
                    .start("messageSet").end("messageSet")
                    .end("item");
        }
        xml.end("availabilityZoneInfo").end("DescribeAvailabilityZonesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeRegions(MultivaluedMap<String, String> p, String region) {
        List<String> regions = service.describeRegions();
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeRegionsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("regionInfo");
        for (String r : regions) {
            xml.start("item")
                    .elem("regionName", r)
                    .elem("regionEndpoint", "ec2." + r + ".amazonaws.com")
                    .elem("optInStatus", "opt-in-not-required")
                    .end("item");
        }
        xml.end("regionInfo").end("DescribeRegionsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeAccountAttributes(MultivaluedMap<String, String> p, String region) {
        Map<String, String> attrs = service.describeAccountAttributes(region);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeAccountAttributesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("accountAttributeSet");
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            xml.start("item")
                    .elem("attributeName", entry.getKey())
                    .start("attributeValueSet")
                    .start("item").elem("attributeValue", entry.getValue()).end("item")
                    .end("attributeValueSet")
                    .end("item");
        }
        xml.end("accountAttributeSet").end("DescribeAccountAttributesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeInstanceTypes(MultivaluedMap<String, String> p, String region) {
        List<String> typeNames = getList(p, "InstanceType");
        List<Map<String, Object>> types = service.describeInstanceTypes(typeNames);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeInstanceTypesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("instanceTypeSet");
        for (Map<String, Object> t : types) {
            xml.start("item")
                    .elem("instanceType", (String) t.get("instanceType"))
                    .elem("currentGeneration", String.valueOf(t.get("currentGeneration")))
                    .start("vCpuInfo")
                    .elem("defaultVCpus", String.valueOf(t.get("vcpu")))
                    .end("vCpuInfo")
                    .start("memoryInfo")
                    .elem("sizeInMiB", String.valueOf(t.get("memoryMib")))
                    .end("memoryInfo")
                    .elem("instanceStorageSupported", String.valueOf(t.get("instanceStorageSupported")));
            if (Boolean.TRUE.equals(t.get("instanceStorageSupported"))) {
                xml.start("instanceStorageInfo")
                        .elem("totalSizeInGB", String.valueOf(t.get("localStorageGiB")))
                        .end("instanceStorageInfo");
            }
            xml.start("processorInfo")
                    .start("supportedArchitectures");
            for (String arch : (List<String>) t.get("supportedArchitectures")) {
                xml.elem("item", arch);
            }
            xml.end("supportedArchitectures").end("processorInfo").end("item");
        }
        xml.end("instanceTypeSet").end("DescribeInstanceTypesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeInstanceTypeOfferings(MultivaluedMap<String, String> p, String region) {
        List<String> typeNames = getList(p, "InstanceType");
        Map<String, List<String>> filters = getFilters(p);
        List<Map<String, String>> offerings = service.describeInstanceTypeOfferings(
                region, typeNames, p.getFirst("LocationType"), filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeInstanceTypeOfferingsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("instanceTypeOfferingSet");
        for (Map<String, String> offering : offerings) {
            xml.start("item")
                    .elem("instanceType", offering.get("instanceType"))
                    .elem("locationType", offering.get("locationType"))
                    .elem("location", offering.get("location"))
                    .end("item");
        }
        xml.end("instanceTypeOfferingSet").end("DescribeInstanceTypeOfferingsResponse");
        return xmlResponse(xml.build());
    }

    // ─── Launch Template handlers ─────────────────────────────────────────────

    private Response handleCreateLaunchTemplate(MultivaluedMap<String, String> p, String region) {
        LaunchTemplateData data = parseLaunchTemplateData(p);
        data.setVersionDescription(p.getFirst("VersionDescription"));
        LaunchTemplate launchTemplate = service.createLaunchTemplate(
                region,
                p.getFirst("LaunchTemplateName"),
                data,
                parseTagsForResource(p, "launch-template"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateLaunchTemplateResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("launchTemplate").raw(launchTemplateXml(launchTemplate)).end("launchTemplate")
                .end("CreateLaunchTemplateResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateLaunchTemplateVersion(MultivaluedMap<String, String> p, String region) {
        LaunchTemplateData data = parseLaunchTemplateData(p);
        data.setVersionDescription(p.getFirst("VersionDescription"));
        LaunchTemplate launchTemplate = service.createLaunchTemplateVersion(
                region,
                p.getFirst("LaunchTemplateId"),
                p.getFirst("LaunchTemplateName"),
                p.getFirst("SourceVersion"),
                data);
        XmlBuilder xml = new XmlBuilder()
                .start("CreateLaunchTemplateVersionResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("launchTemplateVersion").raw(launchTemplateVersionXml(launchTemplate)).end("launchTemplateVersion")
                .end("CreateLaunchTemplateVersionResponse");
        return xmlResponse(xml.build());
    }

    // Builds the full LaunchTemplateData from a CreateLaunchTemplate[Version] request - see
    // LaunchTemplateData's own doc comment (lex00/floci#119) for which AWS fields this covers
    // and which are deliberately left out.
    private LaunchTemplateData parseLaunchTemplateData(MultivaluedMap<String, String> p) {
        LaunchTemplateData data = new LaunchTemplateData();
        String encodedUserData = p.getFirst("LaunchTemplateData.UserData");
        data.setImageId(p.getFirst("LaunchTemplateData.ImageId"));
        data.setInstanceType(p.getFirst("LaunchTemplateData.InstanceType"));
        data.setKeyName(p.getFirst("LaunchTemplateData.KeyName"));
        data.setUserData(decodeUserData(encodedUserData));
        data.setEncodedUserData(encodedUserData);
        data.setIamInstanceProfileArn(resolveIamInstanceProfileArn(p, "LaunchTemplateData.IamInstanceProfile"));
        data.setSecurityGroupIds(parseLaunchTemplateSecurityGroupIds(p));
        data.setInstanceTags(parseLaunchTemplateDataTagsForResource(p, "instance"));
        data.setMetadataOptions(parseLaunchTemplateMetadataOptions(p));
        data.setMonitoringEnabled(parseLaunchTemplateMonitoring(p));
        data.setEbsOptimized(parseOptionalBoolean(
                p.getFirst("LaunchTemplateData.EbsOptimized"), "LaunchTemplateData.EbsOptimized"));
        data.setBlockDeviceMappings(parseLaunchTemplateBlockDeviceMappings(p));
        data.setCapacityReservationSpecification(parseLaunchTemplateCapacityReservationSpecification(p));
        data.setCpuOptions(parseLaunchTemplateCpuOptions(p));
        data.setInstanceMarketOptions(parseLaunchTemplateInstanceMarketOptions(p));
        data.setMaintenanceOptions(parseLaunchTemplateMaintenanceOptions(p));
        data.setNetworkInterfaces(parseLaunchTemplateNetworkInterfaces(p));
        data.setPlacement(parseLaunchTemplatePlacement(p));
        data.setTagSpecifications(parseLaunchTemplateTagSpecifications(p));
        data.setInstanceRequirements(parseLaunchTemplateInstanceRequirements(p));
        return data;
    }

    private Response handleDescribeLaunchTemplates(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "LaunchTemplateId");
        List<String> names = getList(p, "LaunchTemplateName");
        Map<String, List<String>> filters = getFilters(p);
        List<LaunchTemplate> launchTemplates = service.describeLaunchTemplates(region, ids, names, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeLaunchTemplatesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("launchTemplates");
        for (LaunchTemplate launchTemplate : launchTemplates) {
            xml.start("item").raw(launchTemplateXml(launchTemplate)).end("item");
        }
        xml.end("launchTemplates").end("DescribeLaunchTemplatesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeLaunchTemplateVersions(MultivaluedMap<String, String> p, String region) {
        String id = p.getFirst("LaunchTemplateId");
        String name = p.getFirst("LaunchTemplateName");
        List<LaunchTemplate> launchTemplates = service.describeLaunchTemplateVersions(
                region,
                id,
                name,
                getList(p, "Versions"));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeLaunchTemplateVersionsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("launchTemplateVersionSet");
        for (LaunchTemplate launchTemplate : launchTemplates) {
            xml.start("item").raw(launchTemplateVersionXml(launchTemplate)).end("item");
        }
        xml.end("launchTemplateVersionSet").end("DescribeLaunchTemplateVersionsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyLaunchTemplate(MultivaluedMap<String, String> p, String region) {
        LaunchTemplate launchTemplate = service.modifyLaunchTemplate(
                region,
                p.getFirst("LaunchTemplateId"),
                p.getFirst("LaunchTemplateName"),
                firstPresent(p, "SetDefaultVersion", "DefaultVersion"));
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyLaunchTemplateResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("launchTemplate").raw(launchTemplateXml(launchTemplate)).end("launchTemplate")
                .end("ModifyLaunchTemplateResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteLaunchTemplate(MultivaluedMap<String, String> p, String region) {
        LaunchTemplate launchTemplate = service.deleteLaunchTemplate(
                region, p.getFirst("LaunchTemplateId"), p.getFirst("LaunchTemplateName"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteLaunchTemplateResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("launchTemplate").raw(launchTemplateXml(launchTemplate)).end("launchTemplate")
                .end("DeleteLaunchTemplateResponse");
        return xmlResponse(xml.build());
    }

    // ─── Network Interface handlers ───────────────────────────────────────────

    private Response handleDescribeNetworkInterfaces(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "NetworkInterfaceId");
        Map<String, List<String>> filters = getFilters(p);

        // Phase 5: pagination parameters
        int maxResults = parseIntParam(p, "MaxResults", 0);
        String nextToken = p.getFirst("NextToken");

        NetworkInterfaceListResult result = service.describeNetworkInterfaces(region, ids, filters, maxResults, nextToken);
        List<NetworkInterface> nis = result.networkInterfaces();

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeNetworkInterfacesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("networkInterfaceSet");
        for (NetworkInterface ni : nis) {
            xml.start("item")
                    .elem("networkInterfaceId", ni.getNetworkInterfaceId())
                    .elem("subnetId", ni.getSubnetId())
                    .elem("vpcId", ni.getVpcId())
                    .elem("availabilityZone", ni.getAvailabilityZone())
                    .elem("description", ni.getDescription())
                    .elem("ownerId", ni.getOwnerId())
                    .elem("status", ni.getStatus())
                    .elem("interfaceType", ni.getInterfaceType())
                    .elem("macAddress", ni.getMacAddress())
                    .elem("privateIpAddress", ni.getPrivateIpAddress())
                    .elem("privateDnsName", ni.getPrivateDnsName())
                    .elem("sourceDestCheck", String.valueOf(ni.isSourceDestCheck()))
                    .start("groupSet");
            for (GroupIdentifier gi : ni.getGroups()) {
                xml.start("item")
                        .elem("groupId", gi.getGroupId())
                        .elem("groupName", gi.getGroupName())
                        .end("item");
            }
            xml.end("groupSet");
            // Phase 3: tagSet from instance tags
            xml.raw(tagSetXml(ni.getTagSet()));
            if (ni.getAttachment() != null) {
                xml.start("attachment")
                        .elem("attachmentId", ni.getAttachment().getAttachmentId())
                        .elem("deviceIndex", String.valueOf(ni.getAttachment().getDeviceIndex()))
                        .elem("status", ni.getAttachment().getStatus())
                        .elem("attachTime", ni.getAttachment().getAttachTime())
                        .elem("deleteOnTermination", String.valueOf(ni.getAttachment().isDeleteOnTermination()))
                        .elem("instanceId", ni.getAttachment().getInstanceId())
                        .elem("instanceOwnerId", ni.getAttachment().getInstanceOwnerId())
                        .end("attachment");
            }
            // Phase 3: privateIpAddressesSet with association
            if (!ni.getPrivateIpAddresses().isEmpty()) {
                xml.start("privateIpAddressesSet");
                for (NetworkInterfacePrivateIpAddress ip : ni.getPrivateIpAddresses()) {
                    xml.start("item")
                            .elem("privateIpAddress", ip.getPrivateIpAddress())
                            .elem("privateDnsName", ip.getPrivateDnsName())
                            .elem("primary", String.valueOf(ip.isPrimary()));
                    if (ip.getAssociation() != null) {
                        xml.start("association")
                                .elem("publicIp", ip.getAssociation().getPublicIp())
                                .elem("allocationId", ip.getAssociation().getAllocationId())
                                .elem("associationId", ip.getAssociation().getAssociationId())
                                .elem("ipOwnerId", ip.getAssociation().getIpOwnerId())
                                .end("association");
                    }
                    xml.end("item");
                }
                xml.end("privateIpAddressesSet");
            }
            xml.end("item");
        }
        xml.end("networkInterfaceSet");
        if (result.nextToken() != null) {
            xml.elem("nextToken", result.nextToken());
        }
        xml.end("DescribeNetworkInterfacesResponse");
        return xmlResponse(xml.build());
    }

    // ─── XML fragment builders ────────────────────────────────────────────────

    private String instanceXml(Instance inst) {
        XmlBuilder xml = new XmlBuilder()
                .elem("instanceId", inst.getInstanceId())
                .elem("imageId", inst.getImageId())
                .start("instanceState")
                .elem("code", inst.getState() != null ? String.valueOf(inst.getState().getCode()) : "16")
                .elem("name", inst.getState() != null ? inst.getState().getName() : "running")
                .end("instanceState")
                .elem("privateDnsName", inst.getPrivateDnsName())
                .elem("dnsName", inst.getPublicDnsName())
                .elem("reason", inst.getStateTransitionReason())
                .elem("keyName", inst.getKeyName())
                .elem("amiLaunchIndex", String.valueOf(inst.getAmiLaunchIndex()))
                .elem("instanceType", inst.getInstanceType())
                .elem("launchTime", inst.getLaunchTime() != null ? ISO_FMT.format(inst.getLaunchTime()) : "");

        if (inst.getPlacement() != null) {
            xml.start("placement")
                    .elem("availabilityZone", inst.getPlacement().getAvailabilityZone())
                    .elem("tenancy", inst.getPlacement().getTenancy())
                    .end("placement");
        }

        xml.start("monitoring").elem("state", inst.getMonitoring()).end("monitoring")
                .elem("subnetId", inst.getSubnetId())
                .elem("vpcId", inst.getVpcId())
                .elem("privateIpAddress", inst.getPrivateIpAddress())
                .elem("ipAddress", inst.getPublicIpAddress())
                .elem("sourceDestCheck", String.valueOf(inst.isSourceDestCheck()))
                .start("groupSet");
        for (GroupIdentifier gi : inst.getSecurityGroups()) {
            xml.start("item")
                    .elem("groupId", gi.getGroupId())
                    .elem("groupName", gi.getGroupName())
                    .end("item");
        }
        xml.end("groupSet")
                .elem("architecture", inst.getArchitecture())
                .elem("rootDeviceType", inst.getRootDeviceType())
                .elem("rootDeviceName", inst.getRootDeviceName())
                .elem("virtualizationType", inst.getVirtualizationType())
                .elem("hypervisor", inst.getHypervisor())
                .elem("ebsOptimized", String.valueOf(inst.isEbsOptimized()))
                .elem("enaSupport", String.valueOf(inst.isEnaSupport()))
                .start("networkInterfaceSet");
        for (InstanceNetworkInterface eni : inst.getNetworkInterfaces()) {
            xml.start("item")
                    .elem("networkInterfaceId", eni.getNetworkInterfaceId())
                    .elem("subnetId", eni.getSubnetId())
                    .elem("vpcId", eni.getVpcId())
                    .elem("description", eni.getDescription())
                    .elem("ownerId", eni.getOwnerId())
                    .elem("status", eni.getStatus())
                    .elem("macAddress", eni.getMacAddress())
                    .elem("privateIpAddress", eni.getPrivateIpAddress())
                    .elem("privateDnsName", eni.getPrivateDnsName())
                    .elem("sourceDestCheck", String.valueOf(eni.isSourceDestCheck()))
                    .start("groupSet");
            for (GroupIdentifier gi : eni.getGroups()) {
                xml.start("item")
                        .elem("groupId", gi.getGroupId())
                        .elem("groupName", gi.getGroupName())
                        .end("item");
            }
            xml.end("groupSet")
                    .start("attachment")
                    .elem("attachmentId", eni.getAttachmentId())
                    .elem("deviceIndex", String.valueOf(eni.getDeviceIndex()))
                    .elem("status", "attached");
            if (eni.getAttachTime() != null) {
                xml.elem("attachTime", eni.getAttachTime());
            }
            xml.elem("deleteOnTermination", "true")
                    .end("attachment")
                    .start("privateIpAddressesSet")
                    .start("item")
                    .elem("privateIpAddress", eni.getPrivateIpAddress())
                    .elem("privateDnsName", eni.getPrivateDnsName())
                    .elem("primary", "true")
                    .end("item")
                    .end("privateIpAddressesSet")
                    .end("item");
        }
        xml.end("networkInterfaceSet");
        xml.elem("clientToken", inst.getClientToken());
        if (inst.getStateReasonCode() != null || inst.getStateReasonMessage() != null) {
            xml.start("stateReason")
                    .elem("code", inst.getStateReasonCode())
                    .elem("message", inst.getStateReasonMessage())
                    .end("stateReason");
        }
        xml.start("cpuOptions")
                .elem("coreCount", "1")
                .elem("threadsPerCore", "1")
                .end("cpuOptions")
                .start("metadataOptions")
                .elem("state", "applied")
                .elem("httpTokens", inst.getMetadataHttpTokens())
                .elem("httpPutResponseHopLimit", String.valueOf(inst.getMetadataHttpPutResponseHopLimit()))
                .elem("httpEndpoint", inst.getMetadataHttpEndpoint())
                .elem("httpProtocolIpv6", inst.getMetadataHttpProtocolIpv6())
                .elem("instanceMetadataTags", inst.getMetadataInstanceMetadataTags())
                .end("metadataOptions")
                .start("maintenanceOptions")
                .elem("autoRecovery", "default")
                .end("maintenanceOptions")
                .start("enclaveOptions")
                .elem("enabled", "false")
                .end("enclaveOptions")
                .start("hibernationOptions")
                .elem("configured", "false")
                .end("hibernationOptions")
                .start("privateDnsNameOptions")
                .elem("hostnameType", "ip-name")
                .elem("enableResourceNameDnsARecord", "false")
                .elem("enableResourceNameDnsAAAARecord", "false")
                .end("privateDnsNameOptions")
                .start("capacityReservationSpecification")
                .elem("capacityReservationPreference", "open")
                .end("capacityReservationSpecification");
        if (inst.getRootVolumeId() != null) {
            xml.start("blockDeviceMapping")
                    .start("item")
                    .elem("deviceName", inst.getRootDeviceName())
                    .start("ebs")
                    .elem("volumeId", inst.getRootVolumeId())
                    .elem("status", "attached")
                    .elem("deleteOnTermination", String.valueOf(inst.isRootVolumeDeleteOnTermination()))
                    .elem("attachTime", inst.getLaunchTime() != null ? ISO_FMT.format(inst.getLaunchTime()) : "")
                    .end("ebs")
                    .end("item")
                    .end("blockDeviceMapping");
        }
        if (inst.getIamInstanceProfileArn() != null) {
            xml.start("iamInstanceProfile")
                    .elem("arn", inst.getIamInstanceProfileArn())
                    .elem("id", iamInstanceProfileId(inst.getInstanceId()))
                    .end("iamInstanceProfile");
        }
        xml.raw(tagSetXml(inst.getTags()));
        return xml.build();
    }

    private String resolveIamInstanceProfileArn(MultivaluedMap<String, String> p) {
        return resolveIamInstanceProfileArn(p, "IamInstanceProfile");
    }

    private String resolveIamInstanceProfileArn(MultivaluedMap<String, String> p, String prefix) {
        String arn = p.getFirst(prefix + ".Arn");
        if (arn != null && !arn.isBlank()) {
            return arn;
        }
        String name = p.getFirst(prefix + ".Name");
        if (name == null || name.isBlank()) {
            return null;
        }
        return AwsArnUtils.Arn.of("iam", "", config.defaultAccountId(), "instance-profile/" + name).toString();
    }

    private String vpcXml(Vpc vpc) {
        XmlBuilder xml = new XmlBuilder()
                .elem("vpcId", vpc.getVpcId())
                .elem("state", vpc.getState())
                .elem("cidrBlock", vpc.getCidrBlock())
                .elem("dhcpOptionsId", vpc.getDhcpOptionsId())
                .elem("instanceTenancy", vpc.getInstanceTenancy())
                .elem("isDefault", String.valueOf(vpc.isDefault()))
                .elem("ownerId", vpc.getOwnerId())
                .start("cidrBlockAssociationSet");
        for (VpcCidrBlockAssociation assoc : vpc.getCidrBlockAssociationSet()) {
            xml.start("item")
                    .elem("associationId", assoc.getAssociationId())
                    .elem("cidrBlock", assoc.getCidrBlock())
                    .start("cidrBlockState").elem("state", assoc.getCidrBlockState()).end("cidrBlockState")
                    .end("item");
        }
        xml.end("cidrBlockAssociationSet")
                .raw(tagSetXml(vpc.getTags()));
        return xml.build();
    }

    private String subnetXml(Subnet s) {
        XmlBuilder xml = new XmlBuilder()
                .elem("subnetId", s.getSubnetId())
                .elem("subnetArn", s.getSubnetArn())
                .elem("state", s.getState())
                .elem("vpcId", s.getVpcId())
                .elem("cidrBlock", s.getCidrBlock())
                .elem("availableIpAddressCount", String.valueOf(s.getAvailableIpAddressCount()))
                .elem("availabilityZone", s.getAvailabilityZone())
                .elem("availabilityZoneId", s.getAvailabilityZoneId())
                .elem("defaultForAz", String.valueOf(s.isDefaultForAz()))
                .elem("mapPublicIpOnLaunch", String.valueOf(s.isMapPublicIpOnLaunch()))
                .elem("assignIpv6AddressOnCreation", String.valueOf(s.isAssignIpv6AddressOnCreation()))
                .elem("enableDns64", String.valueOf(s.isEnableDns64()))
                .elem("mapCustomerOwnedIpOnLaunch", String.valueOf(s.isMapCustomerOwnedIpOnLaunch()))
                .start("ipv6CidrBlockAssociationSet").end("ipv6CidrBlockAssociationSet")
                .elem("ownerId", s.getOwnerId())
                .raw(tagSetXml(s.getTags()));
        return xml.build();
    }

    private String sgXml(SecurityGroup sg) {
        XmlBuilder xml = new XmlBuilder()
                .elem("ownerId", sg.getOwnerId())
                .elem("groupId", sg.getGroupId())
                .elem("groupName", sg.getGroupName())
                .elem("groupDescription", sg.getDescription())
                .elem("vpcId", sg.getVpcId());
        xml.raw(ipPermissionsXml(sg.getIpPermissions(), "ipPermissions"));
        xml.raw(ipPermissionsXml(sg.getIpPermissionsEgress(), "ipPermissionsEgress"));
        xml.raw(tagSetXml(sg.getTags()));
        return xml.build();
    }

    private String sgRuleXml(SecurityGroupRule rule) {
        XmlBuilder xml = new XmlBuilder()
                .elem("securityGroupRuleId", rule.getSecurityGroupRuleId())
                .elem("groupId", rule.getGroupId())
                .elem("groupOwnerId", rule.getGroupOwnerId())
                .elem("isEgress", String.valueOf(rule.isEgress()))
                .elem("ipProtocol", rule.getIpProtocol());
        if (rule.getFromPort() != null) xml.elem("fromPort", String.valueOf(rule.getFromPort()));
        if (rule.getToPort() != null) xml.elem("toPort", String.valueOf(rule.getToPort()));
        xml.elem("cidrIpv4", rule.getCidrIpv4())
                .elem("cidrIpv6", rule.getCidrIpv6());
        // Guarded: unlike elem(), start()/end() emit even when every child is null, which would put
        // an empty <referencedGroupInfo/> on every CIDR rule.
        ReferencedSecurityGroup ref = rule.getReferencedGroupInfo();
        if (ref != null) {
            xml.start("referencedGroupInfo")
                    .elem("groupId", ref.getGroupId())
                    .elem("peeringStatus", ref.getPeeringStatus())
                    .elem("userId", ref.getUserId())
                    .elem("vpcId", ref.getVpcId())
                    .elem("vpcPeeringConnectionId", ref.getVpcPeeringConnectionId())
                    .end("referencedGroupInfo");
        }
        xml.elem("prefixListId", rule.getPrefixListId());
        xml.elem("description", rule.getDescription())
                .raw(tagSetXml(rule.getTags()));
        return xml.build();
    }

    private String igwXml(InternetGateway igw) {
        XmlBuilder xml = new XmlBuilder()
                .elem("internetGatewayId", igw.getInternetGatewayId())
                .elem("ownerId", igw.getOwnerId())
                .start("attachmentSet");
        for (InternetGatewayAttachment att : igw.getAttachments()) {
            xml.start("item")
                    .elem("vpcId", att.getVpcId())
                    .elem("state", att.getState())
                    .end("item");
        }
        xml.end("attachmentSet")
                .raw(tagSetXml(igw.getTags()));
        return xml.build();
    }

    private String routeTableXml(RouteTable rt) {
        XmlBuilder xml = new XmlBuilder()
                .elem("routeTableId", rt.getRouteTableId())
                .elem("vpcId", rt.getVpcId())
                .elem("ownerId", rt.getOwnerId())
                .start("routeSet");
        for (Route r : rt.getRoutes()) {
            xml.start("item")
                    .elem("destinationCidrBlock", r.getDestinationCidrBlock())
                    .elem("gatewayId", r.getGatewayId())
                    .elem("natGatewayId", r.getNatGatewayId())
                    .elem("state", r.getState())
                    .elem("origin", r.getOrigin())
                    .end("item");
        }
        xml.end("routeSet").start("associationSet");
        for (RouteTableAssociation assoc : rt.getAssociations()) {
            xml.start("item")
                    .elem("routeTableAssociationId", assoc.getRouteTableAssociationId())
                    .elem("routeTableId", assoc.getRouteTableId())
                    .elem("subnetId", assoc.getSubnetId())
                    .elem("main", String.valueOf(assoc.isMain()))
                    .start("associationState").elem("state", assoc.getAssociationState()).end("associationState")
                    .end("item");
        }
        xml.end("associationSet")
                .raw(tagSetXml(rt.getTags()));
        return xml.build();
    }

    private String networkAclXml(NetworkAcl acl) {
        XmlBuilder xml = new XmlBuilder()
                .elem("networkAclId", acl.getNetworkAclId())
                .elem("vpcId", acl.getVpcId())
                .elem("default", String.valueOf(acl.isDefault()))
                .elem("ownerId", acl.getOwnerId())
                .start("entrySet");
        for (NetworkAclEntry e : acl.getEntries()) {
            xml.start("item")
                    .elem("ruleNumber", String.valueOf(e.getRuleNumber()))
                    .elem("protocol", e.getProtocol())
                    .elem("ruleAction", e.getRuleAction())
                    .elem("egress", String.valueOf(e.isEgress()))
                    .elem("cidrBlock", e.getCidrBlock())
                    .elem("ipv6CidrBlock", e.getIpv6CidrBlock());
            if (e.getPortRangeFrom() != null || e.getPortRangeTo() != null) {
                xml.start("portRange")
                        .elem("from", String.valueOf(e.getPortRangeFrom()))
                        .elem("to", String.valueOf(e.getPortRangeTo()))
                        .end("portRange");
            }
            xml.end("item");
        }
        xml.end("entrySet").start("associationSet");
        for (NetworkAclAssociation a : acl.getAssociations()) {
            xml.start("item")
                    .elem("networkAclAssociationId", a.getNetworkAclAssociationId())
                    .elem("networkAclId", a.getNetworkAclId())
                    .elem("subnetId", a.getSubnetId())
                    .end("item");
        }
        xml.end("associationSet")
                .raw(tagSetXml(acl.getTags()));
        return xml.build();
    }

    private String natGatewayXml(NatGateway natGateway) {
        XmlBuilder xml = new XmlBuilder()
                .elem("natGatewayId", natGateway.getNatGatewayId())
                .elem("subnetId", natGateway.getSubnetId())
                .elem("vpcId", natGateway.getVpcId())
                .elem("state", natGateway.getState())
                .elem("connectivityType", natGateway.getConnectivityType())
                .elem("availabilityMode", natGateway.getAvailabilityMode());
        if (natGateway.getCreateTime() != null) {
            xml.elem("createTime", ISO_FMT.format(natGateway.getCreateTime()));
        }
        // One primary address, the way AWS answers for a zonal gateway. isPrimary is what the
        // Terraform provider keys on when it splits primary from secondary addresses.
        xml.start("natGatewayAddressSet")
                .start("item")
                .elem("allocationId", natGateway.getAllocationId())
                .elem("associationId", natGateway.getAssociationId())
                .elem("networkInterfaceId", natGateway.getNetworkInterfaceId())
                .elem("privateIp", natGateway.getPrivateIp())
                .elem("publicIp", natGateway.getPublicIp())
                .elem("isPrimary", "true")
                .elem("status", natGateway.getAddressStatus())
                .end("item")
                .end("natGatewayAddressSet");
        xml.raw(tagSetXml(natGateway.getTags()));
        return xml.build();
    }

    private String launchTemplateXml(LaunchTemplate launchTemplate) {
        XmlBuilder xml = new XmlBuilder()
                .elem("launchTemplateId", launchTemplate.getLaunchTemplateId())
                .elem("launchTemplateName", launchTemplate.getLaunchTemplateName());
        if (launchTemplate.getCreateTime() != null) {
            xml.elem("createTime", ISO_FMT.format(launchTemplate.getCreateTime()));
        }
        xml.elem("createdBy", launchTemplate.getCreatedBy())
                .elem("defaultVersionNumber", launchTemplate.getDefaultVersionNumber())
                .elem("latestVersionNumber", launchTemplate.getLatestVersionNumber())
                .raw(tagSetXml(launchTemplate.getTags()));
        return xml.build();
    }

    // The generic tagSpecifications field is what the query-param path (CreateLaunchTemplate[Version])
    // populates, and is the accurate source once it's set. Templates created only through the CFN
    // provisioner (which never parses LaunchTemplateData.TagSpecification.N.*, only the plain
    // instanceTags convenience field) still need their "instance" tags to show up here, so fall
    // back to synthesizing that one entry when tagSpecifications is empty but instanceTags is not.
    private List<LaunchTemplateData.TagSpecification> effectiveTagSpecifications(LaunchTemplate launchTemplate) {
        if (launchTemplate.getTagSpecifications() != null && !launchTemplate.getTagSpecifications().isEmpty()) {
            return launchTemplate.getTagSpecifications();
        }
        if (launchTemplate.getInstanceTags() != null && !launchTemplate.getInstanceTags().isEmpty()) {
            return List.of(new LaunchTemplateData.TagSpecification("instance", launchTemplate.getInstanceTags()));
        }
        return List.of();
    }

    private String instanceRequirementsXml(LaunchTemplateData.InstanceRequirements req) {
        XmlBuilder xml = new XmlBuilder().start("instanceRequirements");
        writeIntRange(xml, "vCpuCount", req.getVCpuCount());
        writeIntRange(xml, "memoryMiB", req.getMemoryMiB());
        writeStringSet(xml, "cpuManufacturerSet", req.getCpuManufacturers());
        writeDoubleRange(xml, "memoryGiBPerVCpu", req.getMemoryGiBPerVCpu());
        writeStringSet(xml, "excludedInstanceTypeSet", req.getExcludedInstanceTypes());
        writeStringSet(xml, "instanceGenerationSet", req.getInstanceGenerations());
        if (req.getSpotMaxPricePercentageOverLowestPrice() != null) {
            xml.elem("spotMaxPricePercentageOverLowestPrice", String.valueOf(req.getSpotMaxPricePercentageOverLowestPrice()));
        }
        if (req.getOnDemandMaxPricePercentageOverLowestPrice() != null) {
            xml.elem("onDemandMaxPricePercentageOverLowestPrice", String.valueOf(req.getOnDemandMaxPricePercentageOverLowestPrice()));
        }
        if (req.getBareMetal() != null) xml.elem("bareMetal", req.getBareMetal());
        if (req.getBurstablePerformance() != null) xml.elem("burstablePerformance", req.getBurstablePerformance());
        if (req.getRequireHibernateSupport() != null) {
            xml.elem("requireHibernateSupport", String.valueOf(req.getRequireHibernateSupport()));
        }
        writeIntRange(xml, "networkInterfaceCount", req.getNetworkInterfaceCount());
        if (req.getLocalStorage() != null) xml.elem("localStorage", req.getLocalStorage());
        writeStringSet(xml, "localStorageTypeSet", req.getLocalStorageTypes());
        writeDoubleRange(xml, "totalLocalStorageGB", req.getTotalLocalStorageGB());
        writeIntRange(xml, "baselineEbsBandwidthMbps", req.getBaselineEbsBandwidthMbps());
        writeStringSet(xml, "acceleratorTypeSet", req.getAcceleratorTypes());
        writeIntRange(xml, "acceleratorCount", req.getAcceleratorCount());
        writeStringSet(xml, "acceleratorManufacturerSet", req.getAcceleratorManufacturers());
        writeStringSet(xml, "acceleratorNameSet", req.getAcceleratorNames());
        writeIntRange(xml, "acceleratorTotalMemoryMiB", req.getAcceleratorTotalMemoryMiB());
        writeDoubleRange(xml, "networkBandwidthGbps", req.getNetworkBandwidthGbps());
        writeStringSet(xml, "allowedInstanceTypeSet", req.getAllowedInstanceTypes());
        if (req.getMaxSpotPriceAsPercentageOfOptimalOnDemandPrice() != null) {
            xml.elem("maxSpotPriceAsPercentageOfOptimalOnDemandPrice",
                    String.valueOf(req.getMaxSpotPriceAsPercentageOfOptimalOnDemandPrice()));
        }
        xml.end("instanceRequirements");
        return xml.build();
    }

    private void writeIntRange(XmlBuilder xml, String name, LaunchTemplateData.IntRange range) {
        if (range == null || range.isEmpty()) return;
        xml.start(name);
        if (range.getMin() != null) xml.elem("min", String.valueOf(range.getMin()));
        if (range.getMax() != null) xml.elem("max", String.valueOf(range.getMax()));
        xml.end(name);
    }

    private void writeDoubleRange(XmlBuilder xml, String name, LaunchTemplateData.DoubleRange range) {
        if (range == null || range.isEmpty()) return;
        xml.start(name);
        if (range.getMin() != null) xml.elem("min", String.valueOf(range.getMin()));
        if (range.getMax() != null) xml.elem("max", String.valueOf(range.getMax()));
        xml.end(name);
    }

    private void writeStringSet(XmlBuilder xml, String name, List<String> values) {
        if (values == null || values.isEmpty()) return;
        xml.start(name);
        for (String v : values) xml.elem("item", v);
        xml.end(name);
    }

    private String launchTemplateVersionXml(LaunchTemplate launchTemplate) {
        XmlBuilder xml = new XmlBuilder()
                .elem("launchTemplateId", launchTemplate.getLaunchTemplateId())
                .elem("launchTemplateName", launchTemplate.getLaunchTemplateName())
                .elem("versionNumber", launchTemplate.getLatestVersionNumber())
                .elem("defaultVersion", String.valueOf(Objects.equals(
                        launchTemplate.getDefaultVersionNumber(), launchTemplate.getLatestVersionNumber())));
        if (launchTemplate.getCreateTime() != null) {
            xml.elem("createTime", ISO_FMT.format(launchTemplate.getCreateTime()));
        }
        xml.elem("createdBy", launchTemplate.getCreatedBy());
        if (launchTemplate.getVersionDescription() != null) {
            xml.elem("versionDescription", launchTemplate.getVersionDescription());
        }
        xml.start("launchTemplateData")
                .elem("imageId", launchTemplate.getImageId())
                .elem("instanceType", launchTemplate.getInstanceType());
        if (launchTemplate.getKeyName() != null) {
            xml.elem("keyName", launchTemplate.getKeyName());
        }
        if (launchTemplate.getEncodedUserData() != null) {
            xml.elem("userData", launchTemplate.getEncodedUserData());
        }
        if (launchTemplate.getIamInstanceProfileArn() != null) {
            xml.start("iamInstanceProfile")
                    .elem("arn", launchTemplate.getIamInstanceProfileArn())
                    .end("iamInstanceProfile");
        }
        xml.start("securityGroupIdSet");
        for (String securityGroupId : launchTemplate.getSecurityGroupIds()) {
            xml.elem("item", securityGroupId);
        }
        xml.end("securityGroupIdSet");
        List<LaunchTemplateData.TagSpecification> tagSpecifications = effectiveTagSpecifications(launchTemplate);
        if (!tagSpecifications.isEmpty()) {
            xml.start("tagSpecificationSet");
            for (LaunchTemplateData.TagSpecification spec : tagSpecifications) {
                xml.start("item")
                        .elem("resourceType", spec.getResourceType())
                        .raw(tagSetXml(spec.getTags()))
                        .end("item");
            }
            xml.end("tagSpecificationSet");
        }
        if (launchTemplate.getEbsOptimized() != null) {
            xml.elem("ebsOptimized", String.valueOf(launchTemplate.getEbsOptimized()));
        }
        if (!launchTemplate.getBlockDeviceMappings().isEmpty()) {
            xml.start("blockDeviceMappingSet");
            for (BlockDeviceMapping mapping : launchTemplate.getBlockDeviceMappings()) {
                xml.start("item");
                if (mapping.getDeviceName() != null) {
                    xml.elem("deviceName", mapping.getDeviceName());
                }
                if (mapping.getVirtualName() != null) {
                    xml.elem("virtualName", mapping.getVirtualName());
                }
                if (mapping.getNoDevice() != null) {
                    xml.elem("noDevice", mapping.getNoDevice());
                }
                EbsBlockDevice ebs = mapping.getEbs();
                if (ebs != null) {
                    xml.start("ebs");
                    if (ebs.getSnapshotId() != null) xml.elem("snapshotId", ebs.getSnapshotId());
                    if (ebs.getVolumeSize() != null) xml.elem("volumeSize", String.valueOf(ebs.getVolumeSize()));
                    if (ebs.getVolumeType() != null) xml.elem("volumeType", ebs.getVolumeType());
                    if (ebs.getDeleteOnTermination() != null) xml.elem("deleteOnTermination", String.valueOf(ebs.getDeleteOnTermination()));
                    if (ebs.getEncrypted() != null) xml.elem("encrypted", String.valueOf(ebs.getEncrypted()));
                    if (ebs.getIops() != null) xml.elem("iops", String.valueOf(ebs.getIops()));
                    if (ebs.getThroughput() != null) xml.elem("throughput", String.valueOf(ebs.getThroughput()));
                    if (ebs.getKmsKeyId() != null) xml.elem("kmsKeyId", ebs.getKmsKeyId());
                    xml.end("ebs");
                }
                xml.end("item");
            }
            xml.end("blockDeviceMappingSet");
        }
        LaunchTemplateData.CapacityReservationSpecification crs = launchTemplate.getCapacityReservationSpecification();
        if (crs != null && !crs.isEmpty()) {
            xml.start("capacityReservationSpecification");
            if (crs.getCapacityReservationPreference() != null) {
                xml.elem("capacityReservationPreference", crs.getCapacityReservationPreference());
            }
            LaunchTemplateData.CapacityReservationTarget target = crs.getCapacityReservationTarget();
            if (target != null && !target.isEmpty()) {
                xml.start("capacityReservationTarget");
                if (target.getCapacityReservationId() != null) {
                    xml.elem("capacityReservationId", target.getCapacityReservationId());
                }
                if (target.getCapacityReservationResourceGroupArn() != null) {
                    xml.elem("capacityReservationResourceGroupArn", target.getCapacityReservationResourceGroupArn());
                }
                xml.end("capacityReservationTarget");
            }
            xml.end("capacityReservationSpecification");
        }
        LaunchTemplateData.CpuOptions cpuOptions = launchTemplate.getCpuOptions();
        if (cpuOptions != null && !cpuOptions.isEmpty()) {
            xml.start("cpuOptions");
            if (cpuOptions.getCoreCount() != null) xml.elem("coreCount", String.valueOf(cpuOptions.getCoreCount()));
            if (cpuOptions.getThreadsPerCore() != null) xml.elem("threadsPerCore", String.valueOf(cpuOptions.getThreadsPerCore()));
            if (cpuOptions.getAmdSevSnp() != null) xml.elem("amdSevSnp", cpuOptions.getAmdSevSnp());
            xml.end("cpuOptions");
        }
        LaunchTemplateData.InstanceMarketOptions marketOptions = launchTemplate.getInstanceMarketOptions();
        if (marketOptions != null && !marketOptions.isEmpty()) {
            xml.start("instanceMarketOptions");
            if (marketOptions.getMarketType() != null) xml.elem("marketType", marketOptions.getMarketType());
            LaunchTemplateData.SpotOptions spot = marketOptions.getSpotOptions();
            if (spot != null && !spot.isEmpty()) {
                xml.start("spotOptions");
                if (spot.getMaxPrice() != null) xml.elem("maxPrice", spot.getMaxPrice());
                if (spot.getSpotInstanceType() != null) xml.elem("spotInstanceType", spot.getSpotInstanceType());
                if (spot.getBlockDurationMinutes() != null) xml.elem("blockDurationMinutes", String.valueOf(spot.getBlockDurationMinutes()));
                if (spot.getValidUntil() != null) xml.elem("validUntil", spot.getValidUntil());
                if (spot.getInstanceInterruptionBehavior() != null) xml.elem("instanceInterruptionBehavior", spot.getInstanceInterruptionBehavior());
                xml.end("spotOptions");
            }
            xml.end("instanceMarketOptions");
        }
        LaunchTemplateData.MaintenanceOptions maintenanceOptions = launchTemplate.getMaintenanceOptions();
        if (maintenanceOptions != null && !maintenanceOptions.isEmpty()) {
            xml.start("maintenanceOptions")
                    .elem("autoRecovery", maintenanceOptions.getAutoRecovery())
                    .end("maintenanceOptions");
        }
        if (!launchTemplate.getNetworkInterfaces().isEmpty()) {
            xml.start("networkInterfaceSet");
            for (LaunchTemplateData.NetworkInterfaceSpecification ni : launchTemplate.getNetworkInterfaces()) {
                xml.start("item");
                if (ni.getDeviceIndex() != null) xml.elem("deviceIndex", String.valueOf(ni.getDeviceIndex()));
                if (ni.getSubnetId() != null) xml.elem("subnetId", ni.getSubnetId());
                if (ni.getNetworkInterfaceId() != null) xml.elem("networkInterfaceId", ni.getNetworkInterfaceId());
                if (!ni.getGroups().isEmpty()) {
                    xml.start("groupSet");
                    for (String g : ni.getGroups()) xml.elem("groupId", g);
                    xml.end("groupSet");
                }
                if (ni.getAssociatePublicIpAddress() != null) xml.elem("associatePublicIpAddress", String.valueOf(ni.getAssociatePublicIpAddress()));
                if (ni.getDeleteOnTermination() != null) xml.elem("deleteOnTermination", String.valueOf(ni.getDeleteOnTermination()));
                if (ni.getDescription() != null) xml.elem("description", ni.getDescription());
                if (ni.getPrivateIpAddress() != null) xml.elem("privateIpAddress", ni.getPrivateIpAddress());
                if (ni.getNetworkCardIndex() != null) xml.elem("networkCardIndex", String.valueOf(ni.getNetworkCardIndex()));
                if (ni.getInterfaceType() != null) xml.elem("interfaceType", ni.getInterfaceType());
                if (ni.getIpv6AddressCount() != null) xml.elem("ipv6AddressCount", String.valueOf(ni.getIpv6AddressCount()));
                LaunchTemplateData.ConnectionTrackingSpecification connectionTracking = ni.getConnectionTrackingSpecification();
                if (connectionTracking != null && !connectionTracking.isEmpty()) {
                    xml.start("connectionTrackingSpecification");
                    if (connectionTracking.getTcpEstablishedTimeout() != null) {
                        xml.elem("tcpEstablishedTimeout", String.valueOf(connectionTracking.getTcpEstablishedTimeout()));
                    }
                    if (connectionTracking.getUdpStreamTimeout() != null) {
                        xml.elem("udpStreamTimeout", String.valueOf(connectionTracking.getUdpStreamTimeout()));
                    }
                    if (connectionTracking.getUdpTimeout() != null) {
                        xml.elem("udpTimeout", String.valueOf(connectionTracking.getUdpTimeout()));
                    }
                    xml.end("connectionTrackingSpecification");
                }
                xml.end("item");
            }
            xml.end("networkInterfaceSet");
        }
        Placement placement = launchTemplate.getPlacement();
        if (placement != null && !placement.isEmpty()) {
            xml.start("placement");
            if (placement.getAvailabilityZone() != null) xml.elem("availabilityZone", placement.getAvailabilityZone());
            if (placement.getAvailabilityZoneId() != null) xml.elem("availabilityZoneId", placement.getAvailabilityZoneId());
            if (placement.getAffinity() != null) xml.elem("affinity", placement.getAffinity());
            if (placement.getGroupName() != null) xml.elem("groupName", placement.getGroupName());
            if (placement.getHostId() != null) xml.elem("hostId", placement.getHostId());
            if (placement.getTenancy() != null) xml.elem("tenancy", placement.getTenancy());
            if (placement.getSpreadDomain() != null) xml.elem("spreadDomain", placement.getSpreadDomain());
            if (placement.getHostResourceGroupArn() != null) xml.elem("hostResourceGroupArn", placement.getHostResourceGroupArn());
            if (placement.getPartitionNumber() != null) xml.elem("partitionNumber", String.valueOf(placement.getPartitionNumber()));
            if (placement.getGroupId() != null) xml.elem("groupId", placement.getGroupId());
            xml.end("placement");
        }
        LaunchTemplateData.InstanceRequirements requirements = launchTemplate.getInstanceRequirements();
        if (requirements != null && !requirements.isEmpty()) {
            xml.raw(instanceRequirementsXml(requirements));
        }
        LaunchTemplateData.MetadataOptions metadataOptions = launchTemplate.getMetadataOptions();
        if (metadataOptions != null && !metadataOptions.isEmpty()) {
            xml.start("metadataOptions");
            if (metadataOptions.getHttpEndpoint() != null) {
                xml.elem("httpEndpoint", metadataOptions.getHttpEndpoint());
            }
            if (metadataOptions.getHttpProtocolIpv6() != null) {
                xml.elem("httpProtocolIpv6", metadataOptions.getHttpProtocolIpv6());
            }
            if (metadataOptions.getHttpPutResponseHopLimit() != null) {
                xml.elem("httpPutResponseHopLimit", String.valueOf(metadataOptions.getHttpPutResponseHopLimit()));
            }
            if (metadataOptions.getHttpTokens() != null) {
                xml.elem("httpTokens", metadataOptions.getHttpTokens());
            }
            if (metadataOptions.getInstanceMetadataTags() != null) {
                xml.elem("instanceMetadataTags", metadataOptions.getInstanceMetadataTags());
            }
            xml.end("metadataOptions");
        }
        if (launchTemplate.getMonitoringEnabled() != null) {
            xml.start("monitoring")
                    .elem("enabled", String.valueOf(launchTemplate.getMonitoringEnabled()))
                    .end("monitoring");
        }
        xml.end("launchTemplateData");
        return xml.build();
    }

    // parseLaunchTemplateMetadataOptions and parseLaunchTemplateMonitoring:
    // aws_launch_template's metadata_options and monitoring blocks - see
    // LaunchTemplateData.MetadataOptions's own doc comment for why these
    // exist and what surfaced the gap (corpus-autoscaling-complete).
    private LaunchTemplateData.MetadataOptions parseLaunchTemplateMetadataOptions(MultivaluedMap<String, String> p) {
        LaunchTemplateData.MetadataOptions opts = new LaunchTemplateData.MetadataOptions();
        opts.setHttpEndpoint(p.getFirst("LaunchTemplateData.MetadataOptions.HttpEndpoint"));
        opts.setHttpProtocolIpv6(p.getFirst("LaunchTemplateData.MetadataOptions.HttpProtocolIpv6"));
        String hopLimit = p.getFirst("LaunchTemplateData.MetadataOptions.HttpPutResponseHopLimit");
        if (hopLimit != null && !hopLimit.isBlank()) {
            opts.setHttpPutResponseHopLimit(Integer.parseInt(hopLimit));
        }
        opts.setHttpTokens(p.getFirst("LaunchTemplateData.MetadataOptions.HttpTokens"));
        opts.setInstanceMetadataTags(p.getFirst("LaunchTemplateData.MetadataOptions.InstanceMetadataTags"));
        return opts.isEmpty() ? null : opts;
    }

    private Boolean parseLaunchTemplateMonitoring(MultivaluedMap<String, String> p) {
        String enabled = p.getFirst("LaunchTemplateData.Monitoring.Enabled");
        return enabled == null || enabled.isBlank() ? null : Boolean.parseBoolean(enabled);
    }

    // lex00/floci#123: this used to also fold in every
    // LaunchTemplateData.NetworkInterface.N.{Groups,GroupId,SecurityGroupId}
    // value, so a launch template whose security groups were set ONLY on a
    // network interface (never at the top level) echoed those same group
    // IDs back on the top-level SecurityGroupIds field too on
    // DescribeLaunchTemplateVersions - which real AWS does not do; the two
    // are documented as mutually exclusive. This must stay the literal
    // top-level SecurityGroupId list only - see
    // LaunchTemplateData.getEffectiveSecurityGroupIds() for the merged view
    // RunInstances' own template resolution still legitimately needs.
    private List<String> parseLaunchTemplateSecurityGroupIds(MultivaluedMap<String, String> p) {
        return getList(p, "LaunchTemplateData.SecurityGroupId");
    }

    private List<BlockDeviceMapping> parseLaunchTemplateBlockDeviceMappings(MultivaluedMap<String, String> p) {
        List<BlockDeviceMapping> mappings = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = "LaunchTemplateData.BlockDeviceMapping." + i;
            String deviceName = p.getFirst(prefix + ".DeviceName");
            String virtualName = p.getFirst(prefix + ".VirtualName");
            String noDevice = p.getFirst(prefix + ".NoDevice");
            String snapshotId = p.getFirst(prefix + ".Ebs.SnapshotId");
            String volumeSize = p.getFirst(prefix + ".Ebs.VolumeSize");
            String volumeType = p.getFirst(prefix + ".Ebs.VolumeType");
            String deleteOnTermination = p.getFirst(prefix + ".Ebs.DeleteOnTermination");
            String encrypted = p.getFirst(prefix + ".Ebs.Encrypted");
            String iops = p.getFirst(prefix + ".Ebs.Iops");
            String throughput = p.getFirst(prefix + ".Ebs.Throughput");
            String kmsKeyId = p.getFirst(prefix + ".Ebs.KmsKeyId");
            boolean hasEbs = snapshotId != null || volumeSize != null || volumeType != null
                    || deleteOnTermination != null || encrypted != null || iops != null
                    || throughput != null || kmsKeyId != null;
            if (deviceName == null && virtualName == null && noDevice == null && !hasEbs) {
                break;
            }
            BlockDeviceMapping mapping = new BlockDeviceMapping();
            mapping.setDeviceName(deviceName);
            mapping.setVirtualName(virtualName);
            mapping.setNoDevice(noDevice);
            if (hasEbs) {
                EbsBlockDevice ebs = new EbsBlockDevice();
                ebs.setSnapshotId(snapshotId);
                ebs.setVolumeSize(parseOptionalInt(volumeSize, prefix + ".Ebs.VolumeSize"));
                ebs.setVolumeType(volumeType);
                ebs.setDeleteOnTermination(parseOptionalBoolean(deleteOnTermination, prefix + ".Ebs.DeleteOnTermination"));
                ebs.setEncrypted(parseOptionalBoolean(encrypted, prefix + ".Ebs.Encrypted"));
                ebs.setIops(parseOptionalInt(iops, prefix + ".Ebs.Iops"));
                ebs.setThroughput(parseOptionalInt(throughput, prefix + ".Ebs.Throughput"));
                ebs.setKmsKeyId(kmsKeyId);
                mapping.setEbs(ebs);
            }
            mappings.add(mapping);
        }
        return mappings;
    }

    private LaunchTemplateData.CapacityReservationSpecification parseLaunchTemplateCapacityReservationSpecification(
            MultivaluedMap<String, String> p) {
        String prefix = "LaunchTemplateData.CapacityReservationSpecification";
        String preference = p.getFirst(prefix + ".CapacityReservationPreference");
        String targetId = p.getFirst(prefix + ".CapacityReservationTarget.CapacityReservationId");
        String targetArn = p.getFirst(prefix + ".CapacityReservationTarget.CapacityReservationResourceGroupArn");
        if (preference == null && targetId == null && targetArn == null) {
            return null;
        }
        LaunchTemplateData.CapacityReservationSpecification spec = new LaunchTemplateData.CapacityReservationSpecification();
        spec.setCapacityReservationPreference(preference);
        if (targetId != null || targetArn != null) {
            LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
            target.setCapacityReservationId(targetId);
            target.setCapacityReservationResourceGroupArn(targetArn);
            spec.setCapacityReservationTarget(target);
        }
        return spec;
    }

    private LaunchTemplateData.CpuOptions parseLaunchTemplateCpuOptions(MultivaluedMap<String, String> p) {
        String prefix = "LaunchTemplateData.CpuOptions";
        LaunchTemplateData.CpuOptions opts = new LaunchTemplateData.CpuOptions();
        opts.setCoreCount(parseOptionalInt(p.getFirst(prefix + ".CoreCount"), prefix + ".CoreCount"));
        opts.setThreadsPerCore(parseOptionalInt(p.getFirst(prefix + ".ThreadsPerCore"), prefix + ".ThreadsPerCore"));
        opts.setAmdSevSnp(p.getFirst(prefix + ".AmdSevSnp"));
        return opts.isEmpty() ? null : opts;
    }

    private LaunchTemplateData.InstanceMarketOptions parseLaunchTemplateInstanceMarketOptions(
            MultivaluedMap<String, String> p) {
        String prefix = "LaunchTemplateData.InstanceMarketOptions";
        LaunchTemplateData.InstanceMarketOptions opts = new LaunchTemplateData.InstanceMarketOptions();
        opts.setMarketType(p.getFirst(prefix + ".MarketType"));
        String spotPrefix = prefix + ".SpotOptions";
        LaunchTemplateData.SpotOptions spot = new LaunchTemplateData.SpotOptions();
        spot.setMaxPrice(p.getFirst(spotPrefix + ".MaxPrice"));
        spot.setSpotInstanceType(p.getFirst(spotPrefix + ".SpotInstanceType"));
        spot.setBlockDurationMinutes(parseOptionalInt(
                p.getFirst(spotPrefix + ".BlockDurationMinutes"), spotPrefix + ".BlockDurationMinutes"));
        spot.setValidUntil(p.getFirst(spotPrefix + ".ValidUntil"));
        spot.setInstanceInterruptionBehavior(p.getFirst(spotPrefix + ".InstanceInterruptionBehavior"));
        if (!spot.isEmpty()) {
            opts.setSpotOptions(spot);
        }
        return opts.isEmpty() ? null : opts;
    }

    private LaunchTemplateData.MaintenanceOptions parseLaunchTemplateMaintenanceOptions(MultivaluedMap<String, String> p) {
        String autoRecovery = p.getFirst("LaunchTemplateData.MaintenanceOptions.AutoRecovery");
        if (autoRecovery == null) {
            return null;
        }
        LaunchTemplateData.MaintenanceOptions opts = new LaunchTemplateData.MaintenanceOptions();
        opts.setAutoRecovery(autoRecovery);
        return opts;
    }

    private List<LaunchTemplateData.NetworkInterfaceSpecification> parseLaunchTemplateNetworkInterfaces(
            MultivaluedMap<String, String> p) {
        List<LaunchTemplateData.NetworkInterfaceSpecification> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = "LaunchTemplateData.NetworkInterface." + i;
            String deviceIndex = p.getFirst(prefix + ".DeviceIndex");
            String subnetId = p.getFirst(prefix + ".SubnetId");
            String associatePublicIp = p.getFirst(prefix + ".AssociatePublicIpAddress");
            String deleteOnTermination = p.getFirst(prefix + ".DeleteOnTermination");
            String description = p.getFirst(prefix + ".Description");
            String privateIpAddress = p.getFirst(prefix + ".PrivateIpAddress");
            String networkCardIndex = p.getFirst(prefix + ".NetworkCardIndex");
            String interfaceType = p.getFirst(prefix + ".InterfaceType");
            String ipv6AddressCount = p.getFirst(prefix + ".Ipv6AddressCount");
            String networkInterfaceId = p.getFirst(prefix + ".NetworkInterfaceId");
            List<String> groups = getList(p, prefix + ".SecurityGroupId", prefix + ".Groups", prefix + ".GroupId");
            LaunchTemplateData.ConnectionTrackingSpecification connectionTracking =
                    parseLaunchTemplateConnectionTrackingSpecification(p, prefix + ".ConnectionTrackingSpecification");
            boolean present = deviceIndex != null || subnetId != null || associatePublicIp != null
                    || deleteOnTermination != null || description != null || privateIpAddress != null
                    || networkCardIndex != null || interfaceType != null || ipv6AddressCount != null
                    || networkInterfaceId != null || !groups.isEmpty() || connectionTracking != null;
            if (!present) {
                break;
            }
            LaunchTemplateData.NetworkInterfaceSpecification spec = new LaunchTemplateData.NetworkInterfaceSpecification();
            spec.setDeviceIndex(parseOptionalInt(deviceIndex, prefix + ".DeviceIndex"));
            spec.setSubnetId(subnetId);
            spec.setGroups(groups);
            spec.setAssociatePublicIpAddress(parseOptionalBoolean(associatePublicIp, prefix + ".AssociatePublicIpAddress"));
            spec.setDeleteOnTermination(parseOptionalBoolean(deleteOnTermination, prefix + ".DeleteOnTermination"));
            spec.setDescription(description);
            spec.setPrivateIpAddress(privateIpAddress);
            spec.setNetworkCardIndex(parseOptionalInt(networkCardIndex, prefix + ".NetworkCardIndex"));
            spec.setInterfaceType(interfaceType);
            spec.setIpv6AddressCount(parseOptionalInt(ipv6AddressCount, prefix + ".Ipv6AddressCount"));
            spec.setNetworkInterfaceId(networkInterfaceId);
            spec.setConnectionTrackingSpecification(connectionTracking);
            result.add(spec);
        }
        return result;
    }

    // lex00/floci#119's round-5 re-measure: see LaunchTemplateData.NetworkInterfaceSpecification's
    // own doc comment on connectionTrackingSpecification for why this exists.
    private LaunchTemplateData.ConnectionTrackingSpecification parseLaunchTemplateConnectionTrackingSpecification(
            MultivaluedMap<String, String> p, String prefix) {
        String tcpEstablishedTimeout = p.getFirst(prefix + ".TcpEstablishedTimeout");
        String udpStreamTimeout = p.getFirst(prefix + ".UdpStreamTimeout");
        String udpTimeout = p.getFirst(prefix + ".UdpTimeout");
        if (tcpEstablishedTimeout == null && udpStreamTimeout == null && udpTimeout == null) {
            return null;
        }
        LaunchTemplateData.ConnectionTrackingSpecification spec = new LaunchTemplateData.ConnectionTrackingSpecification();
        spec.setTcpEstablishedTimeout(parseOptionalInt(tcpEstablishedTimeout, prefix + ".TcpEstablishedTimeout"));
        spec.setUdpStreamTimeout(parseOptionalInt(udpStreamTimeout, prefix + ".UdpStreamTimeout"));
        spec.setUdpTimeout(parseOptionalInt(udpTimeout, prefix + ".UdpTimeout"));
        return spec;
    }

    private Placement parseLaunchTemplatePlacement(MultivaluedMap<String, String> p) {
        String prefix = "LaunchTemplateData.Placement";
        String availabilityZone = p.getFirst(prefix + ".AvailabilityZone");
        String availabilityZoneId = p.getFirst(prefix + ".AvailabilityZoneId");
        String affinity = p.getFirst(prefix + ".Affinity");
        String groupName = p.getFirst(prefix + ".GroupName");
        String hostId = p.getFirst(prefix + ".HostId");
        String tenancy = p.getFirst(prefix + ".Tenancy");
        String spreadDomain = p.getFirst(prefix + ".SpreadDomain");
        String hostResourceGroupArn = p.getFirst(prefix + ".HostResourceGroupArn");
        String partitionNumber = p.getFirst(prefix + ".PartitionNumber");
        String groupId = p.getFirst(prefix + ".GroupId");
        if (availabilityZone == null && availabilityZoneId == null && affinity == null && groupName == null
                && hostId == null && tenancy == null && spreadDomain == null && hostResourceGroupArn == null
                && partitionNumber == null && groupId == null) {
            return null;
        }
        Placement placement = new Placement();
        placement.setAvailabilityZone(availabilityZone);
        placement.setAvailabilityZoneId(availabilityZoneId);
        placement.setAffinity(affinity);
        placement.setGroupName(groupName);
        placement.setHostId(hostId);
        // lex00/floci#126: Placement.tenancy defaults to "default" on the shared model class
        // (legitimate for a running Instance's Placement, which always reports a tenancy) but a
        // launch template is a stored document, not a running instance - AWS's Describe leaves an
        // unset field absent rather than inventing a value. Always assign the parsed value here
        // (null when the request never carried LaunchTemplateData.Placement.Tenancy) so it
        // overrides the class field's "default" instead of leaving it in place unset.
        placement.setTenancy(tenancy);
        placement.setSpreadDomain(spreadDomain);
        placement.setHostResourceGroupArn(hostResourceGroupArn);
        placement.setPartitionNumber(parseOptionalInt(partitionNumber, prefix + ".PartitionNumber"));
        placement.setGroupId(groupId);
        return placement;
    }

    // Generic across every resource type, unlike parseLaunchTemplateDataTagsForResource (which
    // only extracts one resourceType at a time for the instanceTags convenience field) - this is
    // what lets DescribeLaunchTemplateVersions echo back TagSpecifications for resource types
    // other than "instance" (e.g. "volume", "network-interface").
    private List<LaunchTemplateData.TagSpecification> parseLaunchTemplateTagSpecifications(MultivaluedMap<String, String> p) {
        List<LaunchTemplateData.TagSpecification> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String resType = p.getFirst("LaunchTemplateData.TagSpecification." + i + ".ResourceType");
            if (resType == null) break;
            List<Tag> tagList = new ArrayList<>();
            for (int j = 1; ; j++) {
                String key = p.getFirst("LaunchTemplateData.TagSpecification." + i + ".Tag." + j + ".Key");
                if (key == null) break;
                String value = p.getFirst("LaunchTemplateData.TagSpecification." + i + ".Tag." + j + ".Value");
                tagList.add(new Tag(key, value));
            }
            result.add(new LaunchTemplateData.TagSpecification(resType, tagList));
        }
        return result;
    }

    private LaunchTemplateData.IntRange parseIntRange(MultivaluedMap<String, String> p, String prefix) {
        String min = p.getFirst(prefix + ".Min");
        String max = p.getFirst(prefix + ".Max");
        if (min == null && max == null) {
            return null;
        }
        return new LaunchTemplateData.IntRange(parseOptionalInt(min, prefix + ".Min"), parseOptionalInt(max, prefix + ".Max"));
    }

    private LaunchTemplateData.DoubleRange parseDoubleRange(MultivaluedMap<String, String> p, String prefix) {
        String min = p.getFirst(prefix + ".Min");
        String max = p.getFirst(prefix + ".Max");
        if (min == null && max == null) {
            return null;
        }
        return new LaunchTemplateData.DoubleRange(
                parseOptionalDouble(min, prefix + ".Min"), parseOptionalDouble(max, prefix + ".Max"));
    }

    // Covers the InstanceRequirements(Request) subset terraform's aws_launch_template
    // instance_requirements block commonly sets - see LaunchTemplateData.InstanceRequirements's
    // own doc comment for what's deliberately out of scope.
    private LaunchTemplateData.InstanceRequirements parseLaunchTemplateInstanceRequirements(MultivaluedMap<String, String> p) {
        String prefix = "LaunchTemplateData.InstanceRequirements";
        LaunchTemplateData.InstanceRequirements req = new LaunchTemplateData.InstanceRequirements();
        req.setVCpuCount(parseIntRange(p, prefix + ".VCpuCount"));
        req.setMemoryMiB(parseIntRange(p, prefix + ".MemoryMiB"));
        req.setCpuManufacturers(getList(p, prefix + ".CpuManufacturer"));
        req.setMemoryGiBPerVCpu(parseDoubleRange(p, prefix + ".MemoryGiBPerVCpu"));
        req.setExcludedInstanceTypes(getList(p, prefix + ".ExcludedInstanceType"));
        req.setInstanceGenerations(getList(p, prefix + ".InstanceGeneration"));
        req.setSpotMaxPricePercentageOverLowestPrice(parseOptionalInt(
                p.getFirst(prefix + ".SpotMaxPricePercentageOverLowestPrice"),
                prefix + ".SpotMaxPricePercentageOverLowestPrice"));
        req.setOnDemandMaxPricePercentageOverLowestPrice(parseOptionalInt(
                p.getFirst(prefix + ".OnDemandMaxPricePercentageOverLowestPrice"),
                prefix + ".OnDemandMaxPricePercentageOverLowestPrice"));
        req.setBareMetal(p.getFirst(prefix + ".BareMetal"));
        req.setBurstablePerformance(p.getFirst(prefix + ".BurstablePerformance"));
        req.setRequireHibernateSupport(parseOptionalBoolean(
                p.getFirst(prefix + ".RequireHibernateSupport"), prefix + ".RequireHibernateSupport"));
        req.setNetworkInterfaceCount(parseIntRange(p, prefix + ".NetworkInterfaceCount"));
        req.setLocalStorage(p.getFirst(prefix + ".LocalStorage"));
        req.setLocalStorageTypes(getList(p, prefix + ".LocalStorageType"));
        req.setTotalLocalStorageGB(parseDoubleRange(p, prefix + ".TotalLocalStorageGB"));
        req.setBaselineEbsBandwidthMbps(parseIntRange(p, prefix + ".BaselineEbsBandwidthMbps"));
        req.setAcceleratorTypes(getList(p, prefix + ".AcceleratorType"));
        req.setAcceleratorCount(parseIntRange(p, prefix + ".AcceleratorCount"));
        req.setAcceleratorManufacturers(getList(p, prefix + ".AcceleratorManufacturer"));
        req.setAcceleratorNames(getList(p, prefix + ".AcceleratorName"));
        req.setAcceleratorTotalMemoryMiB(parseIntRange(p, prefix + ".AcceleratorTotalMemoryMiB"));
        req.setNetworkBandwidthGbps(parseDoubleRange(p, prefix + ".NetworkBandwidthGbps"));
        req.setAllowedInstanceTypes(getList(p, prefix + ".AllowedInstanceType"));
        req.setMaxSpotPriceAsPercentageOfOptimalOnDemandPrice(parseOptionalInt(
                p.getFirst(prefix + ".MaxSpotPriceAsPercentageOfOptimalOnDemandPrice"),
                prefix + ".MaxSpotPriceAsPercentageOfOptimalOnDemandPrice"));
        return req.isEmpty() ? null : req;
    }

    private String decodeUserData(String userDataEncoded) {
        if (userDataEncoded == null || userDataEncoded.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(userDataEncoded);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", "UserData is not valid base64 content.", 400);
        }
        if (decoded.length >= 2 && (decoded[0] & 0xff) == 0x1f && (decoded[1] & 0xff) == 0x8b) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(decoded))) {
                decoded = gzip.readAllBytes();
            }
            catch (IOException e) {
                throw new AwsException("InvalidParameterValue", "UserData is not valid gzip content.", 400);
            }
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private String vpcEndpointXml(VpcEndpoint endpoint) {
        XmlBuilder xml = new XmlBuilder()
                .elem("vpcEndpointId", endpoint.getVpcEndpointId())
                .elem("vpcEndpointType", endpoint.getVpcEndpointType())
                .elem("vpcId", endpoint.getVpcId())
                .elem("serviceName", endpoint.getServiceName())
                .elem("state", endpoint.getState())
                .elem("ownerId", endpoint.getOwnerId())
                .elem("serviceRegion", endpoint.getServiceRegion())
                .elem("ipAddressType", endpoint.getIpAddressType())
                .elem("requesterManaged", String.valueOf(endpoint.isRequesterManaged()))
                .elem("privateDnsEnabled", String.valueOf(endpoint.isPrivateDnsEnabled()))
                .elem("policyDocument", endpoint.getPolicyDocument());
        if (endpoint.getDnsRecordIpType() != null) {
            xml.start("dnsOptions")
                    .elem("dnsRecordIpType", endpoint.getDnsRecordIpType())
                    .end("dnsOptions");
        }
        if (endpoint.getCreationTimestamp() != null) {
            xml.elem("creationTimestamp", ISO_FMT.format(endpoint.getCreationTimestamp()));
        }
        xml.start("networkInterfaceIdSet");
        for (String eniId : Ec2Service.endpointNetworkInterfaceIds(endpoint)) {
            xml.elem("item", eniId);
        }
        xml.end("networkInterfaceIdSet")
                .start("routeTableIdSet");
        for (String routeTableId : endpoint.getRouteTableIds()) {
            xml.elem("item", routeTableId);
        }
        xml.end("routeTableIdSet")
                .start("subnetIdSet");
        for (String subnetId : endpoint.getSubnetIds()) {
            xml.elem("item", subnetId);
        }
        xml.end("subnetIdSet")
                .start("groupSet");
        for (String securityGroupId : endpoint.getSecurityGroupIds()) {
            xml.start("item").elem("groupId", securityGroupId).end("item");
        }
        xml.end("groupSet")
                .raw(tagSetXml(endpoint.getTags()));
        return xml.build();
    }

    private String addressXml(Address addr) {
        XmlBuilder xml = new XmlBuilder()
                .elem("publicIp", addr.getPublicIp())
                .elem("allocationId", addr.getAllocationId())
                .elem("domain", addr.getDomain())
                .elem("instanceId", addr.getInstanceId())
                .elem("associationId", addr.getAssociationId())
                .elem("networkInterfaceId", addr.getNetworkInterfaceId())
                .elem("privateIpAddress", addr.getPrivateIpAddress())
                .raw(tagSetXml(addr.getTags()));
        return xml.build();
    }

    private String tagSetXml(List<Tag> tagList) {
        if (tagList == null || tagList.isEmpty()) {
            return "<tagSet/>";
        }
        XmlBuilder xml = new XmlBuilder().start("tagSet");
        for (Tag tag : tagList) {
            xml.start("item")
                    .elem("key", tag.getKey())
                    .elem("value", tag.getValue())
                    .end("item");
        }
        xml.end("tagSet");
        return xml.build();
    }

    private String blockDeviceMappingXml(List<BlockDeviceMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return "<blockDeviceMapping/>";
        }
        XmlBuilder xml = new XmlBuilder().start("blockDeviceMapping");
        for (BlockDeviceMapping mapping : mappings) {
            xml.start("item")
                    .elem("deviceName", mapping.getDeviceName());
            EbsBlockDevice ebs = mapping.getEbs();
            if (ebs != null) {
                xml.start("ebs");
                if (ebs.getSnapshotId() != null) {
                    xml.elem("snapshotId", ebs.getSnapshotId());
                }
                if (ebs.getVolumeSize() != null) {
                    xml.elem("volumeSize", String.valueOf(ebs.getVolumeSize()));
                }
                if (ebs.getVolumeType() != null) {
                    xml.elem("volumeType", ebs.getVolumeType());
                }
                if (ebs.getDeleteOnTermination() != null) {
                    xml.elem("deleteOnTermination", String.valueOf(ebs.getDeleteOnTermination()));
                }
                if (ebs.getEncrypted() != null) {
                    xml.elem("encrypted", String.valueOf(ebs.getEncrypted()));
                }
                xml.end("ebs");
            }
            xml.end("item");
        }
        xml.end("blockDeviceMapping");
        return xml.build();
    }

    private String snapshotXml(Snapshot snapshot) {
        XmlBuilder xml = new XmlBuilder()
                .elem("snapshotId", snapshot.getSnapshotId())
                .elem("ownerId", snapshot.getOwnerId())
                .elem("status", snapshot.getState())
                .elem("progress", snapshot.getProgress())
                .elem("encrypted", String.valueOf(snapshot.isEncrypted()))
                .elem("description", snapshot.getDescription());
        if (snapshot.getVolumeId() != null) {
            xml.elem("volumeId", snapshot.getVolumeId());
        }
        if (snapshot.getVolumeSize() != null) {
            xml.elem("volumeSize", String.valueOf(snapshot.getVolumeSize()));
        }
        if (snapshot.getStartTime() != null) {
            xml.elem("startTime", ISO_FMT.format(snapshot.getStartTime()));
        }
        xml.raw(tagSetXml(snapshot.getTags()));
        return xml.build();
    }

    // ─── Volume handlers ──────────────────────────────────────────────────────

    private Response handleCreateVolume(MultivaluedMap<String, String> p, String region) {
        String availabilityZone = p.getFirst("AvailabilityZone");
        String volumeType = p.getFirst("VolumeType");
        String sizeStr = p.getFirst("Size");
        int size = sizeStr != null ? Integer.parseInt(sizeStr) : 8;
        String encryptedStr = p.getFirst("Encrypted");
        boolean encrypted = "true".equalsIgnoreCase(encryptedStr);
        String iopsStr = p.getFirst("Iops");
        int iops = iopsStr != null ? Integer.parseInt(iopsStr) : 0;
        String throughputStr = p.getFirst("Throughput");
        Integer throughput = null;
        if (throughputStr != null) {
            try {
                throughput = Integer.parseInt(throughputStr);
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationException", "Invalid Throughput value: " + throughputStr, 400);
            }
        }

        String snapshotId = p.getFirst("SnapshotId");

        List<Tag> volumeTags = new ArrayList<>();
        for (int i = 1; ; i++) {
            String resType = p.getFirst("TagSpecification." + i + ".ResourceType");
            if (resType == null) break;
            if ("volume".equals(resType)) {
                for (int j = 1; ; j++) {
                    String k = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Key");
                    if (k == null) break;
                    String v = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Value");
                    volumeTags.add(new Tag(k, v));
                }
            }
        }

        Volume vol = service.createVolume(region, availabilityZone, volumeType, size,
                encrypted, iops, throughput, snapshotId, volumeTags);
        XmlBuilder xml = new XmlBuilder()
                .start("CreateVolumeResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .raw(volumeXml(vol))
                .end("CreateVolumeResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeVolumes(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "VolumeId");
        Map<String, List<String>> filters = getFilters(p);
        List<Volume> volList = service.describeVolumes(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeVolumesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("volumeSet");
        for (Volume vol : volList) {
            xml.start("item").raw(volumeXml(vol)).end("item");
        }
        xml.end("volumeSet")
                .elem("nextToken", "")
                .end("DescribeVolumesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteVolume(MultivaluedMap<String, String> p, String region) {
        service.deleteVolume(region, p.getFirst("VolumeId"));
        return booleanResponse("DeleteVolume");
    }

    private Response handleAttachVolume(MultivaluedMap<String, String> p, String region) {
        String volumeId = p.getFirst("VolumeId");
        String instanceId = p.getFirst("InstanceId");
        String device = p.getFirst("Device");
        VolumeAttachment attachment = service.attachVolume(region, volumeId, instanceId, device);
        return volumeAttachmentResponse("AttachVolume", attachment, "attaching");
    }

    private Response handleDetachVolume(MultivaluedMap<String, String> p, String region) {
        String volumeId = p.getFirst("VolumeId");
        String instanceId = p.getFirst("InstanceId");
        String device = p.getFirst("Device");
        boolean force = "true".equalsIgnoreCase(p.getFirst("Force"));
        VolumeAttachment attachment = service.detachVolume(region, volumeId, instanceId, device, force);
        return volumeAttachmentResponse("DetachVolume", attachment, "detaching");
    }

    private Response volumeAttachmentResponse(String action, VolumeAttachment attachment, String status) {
        XmlBuilder xml = new XmlBuilder()
                .start(action + "Response", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("volumeId", attachment.getVolumeId())
                .elem("instanceId", attachment.getInstanceId())
                .elem("device", attachment.getDevice())
                .elem("status", status)
                .elem("deleteOnTermination", String.valueOf(attachment.isDeleteOnTermination()));
        if (attachment.getAttachTime() != null) {
            xml.elem("attachTime", ISO_FMT.format(attachment.getAttachTime()));
        }
        xml.end(action + "Response");
        return xmlResponse(xml.build());
    }

    private String volumeXml(Volume vol) {
        XmlBuilder xml = new XmlBuilder()
                .elem("volumeId", vol.getVolumeId())
                .elem("size", String.valueOf(vol.getSize()))
                .elem("volumeType", vol.getVolumeType())
                .elem("status", vol.getState())
                .elem("availabilityZone", vol.getAvailabilityZone())
                .elem("encrypted", String.valueOf(vol.isEncrypted()));
        if (vol.getIops() > 0) {
            xml.elem("iops", String.valueOf(vol.getIops()));
        }
        if (vol.getThroughput() != null) {
            xml.elem("throughput", String.valueOf(vol.getThroughput()));
        }
        if (vol.getSnapshotId() != null) {
            xml.elem("snapshotId", vol.getSnapshotId());
        }
        if (vol.getCreateTime() != null) {
            xml.elem("createTime", ISO_FMT.format(vol.getCreateTime()));
        }
        xml.start("attachmentSet");
        for (VolumeAttachment att : vol.getAttachments()) {
            xml.start("item")
                    .elem("volumeId", att.getVolumeId())
                    .elem("instanceId", att.getInstanceId())
                    .elem("device", att.getDevice())
                    .elem("status", att.getState())
                    .elem("deleteOnTermination", String.valueOf(att.isDeleteOnTermination()));
            if (att.getAttachTime() != null) {
                xml.elem("attachTime", ISO_FMT.format(att.getAttachTime()));
            }
            xml.end("item");
        }
        xml.end("attachmentSet")
                .raw(tagSetXml(vol.getTags()));
        return xml.build();
    }

    private String ipPermissionsXml(List<IpPermission> perms, String wrapperTag) {
        XmlBuilder xml = new XmlBuilder().start(wrapperTag);
        for (IpPermission perm : perms) {
            xml.start("item")
                    .elem("ipProtocol", perm.getIpProtocol());
            if (perm.getFromPort() != null) xml.elem("fromPort", String.valueOf(perm.getFromPort()));
            if (perm.getToPort() != null) xml.elem("toPort", String.valueOf(perm.getToPort()));
            xml.start("ipRanges");
            for (IpRange r : perm.getIpRanges()) {
                xml.start("item").elem("cidrIp", r.getCidrIp()).elem("description", r.getDescription()).end("item");
            }
            xml.end("ipRanges")
                    .start("ipv6Ranges");
            for (Ipv6Range r : perm.getIpv6Ranges()) {
                xml.start("item")
                        .elem("cidrIpv6", r.getCidrIpv6())
                        .elem("description", r.getDescription())
                        .end("item");
            }
            xml.end("ipv6Ranges")
                    .start("groups");
            for (UserIdGroupPair g : perm.getUserIdGroupPairs()) {
                xml.start("item")
                        .elem("userId", g.getUserId())
                        .elem("groupId", g.getGroupId())
                        .elem("groupName", g.getGroupName())
                        .elem("description", g.getDescription())
                        .end("item");
            }
            xml.end("groups")
                    .start("prefixListIds");
            for (PrefixListIdReference ref : perm.getPrefixListIds()) {
                xml.start("item")
                        .elem("prefixListId", ref.getPrefixListId())
                        .elem("description", ref.getDescription())
                        .end("item");
            }
            xml.end("prefixListIds").end("item");
        }
        xml.end(wrapperTag);
        return xml.build();
    }

    private Response handleRequestSpotInstances(MultivaluedMap<String, String> p, String region) {
        String spotPrice = p.getFirst("SpotPrice");
        Integer instanceCount = parseIntParam(p, "InstanceCount", 1);
        String type = p.getFirst("Type");
        String productDescription = p.getFirst("ProductDescription");

        String imageId = p.getFirst("LaunchSpecification.ImageId");
        String instanceType = p.getFirst("LaunchSpecification.InstanceType");
        String keyName = p.getFirst("LaunchSpecification.KeyName");
        String subnetId = p.getFirst("LaunchSpecification.SubnetId");
        List<String> securityGroupIds = getList(p, "LaunchSpecification.SecurityGroupId");
        String userDataEncoded = p.getFirst("LaunchSpecification.UserData");
        String userData = null;
        if (userDataEncoded != null && !userDataEncoded.isBlank()) {
            userData = new String(Base64.getDecoder().decode(userDataEncoded), StandardCharsets.UTF_8);
        }
        String iamInstanceProfileArn = p.getFirst("LaunchSpecification.IamInstanceProfile.Arn");

        // Parse TagSpecifications
        List<Tag> spotRequestTags = new ArrayList<>();
        List<Tag> instanceTags = new ArrayList<>();
        for (int i = 1; ; i++) {
            String resType = p.getFirst("TagSpecification." + i + ".ResourceType");
            if (resType == null) break;
            if ("spot-instances-request".equals(resType)) {
                for (int j = 1; ; j++) {
                    String k = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Key");
                    if (k == null) break;
                    String v = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Value");
                    spotRequestTags.add(new Tag(k, v));
                }
            } else if ("instance".equals(resType)) {
                for (int j = 1; ; j++) {
                    String k = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Key");
                    if (k == null) break;
                    String v = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Value");
                    instanceTags.add(new Tag(k, v));
                }
            }
        }

        List<SpotInstanceRequest> requests = service.requestSpotInstances(region, spotPrice, instanceCount,
                type, productDescription, imageId, instanceType, keyName, subnetId, securityGroupIds, userData, iamInstanceProfileArn,
                spotRequestTags, instanceTags);

        XmlBuilder xml = new XmlBuilder()
                .start("RequestSpotInstancesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("spotInstanceRequestSet");
        for (SpotInstanceRequest sir : requests) {
            xml.start("item").raw(spotInstanceRequestXml(sir)).end("item");
        }
        xml.end("spotInstanceRequestSet")
                .end("RequestSpotInstancesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeSpotInstanceRequests(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "SpotInstanceRequestId");
        Map<String, List<String>> filters = getFilters(p);
        List<SpotInstanceRequest> requests = service.describeSpotInstanceRequests(region, ids, filters);

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeSpotInstanceRequestsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("spotInstanceRequestSet");
        for (SpotInstanceRequest sir : requests) {
            xml.start("item").raw(spotInstanceRequestXml(sir)).end("item");
        }
        xml.end("spotInstanceRequestSet")
                .end("DescribeSpotInstanceRequestsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCancelSpotInstanceRequests(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "SpotInstanceRequestId");
        List<SpotInstanceRequest> requests = service.cancelSpotInstanceRequests(region, ids);

        XmlBuilder xml = new XmlBuilder()
                .start("CancelSpotInstanceRequestsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("spotInstanceRequestSet");
        for (SpotInstanceRequest sir : requests) {
            xml.start("item")
                    .elem("spotInstanceRequestId", sir.getSpotInstanceRequestId())
                    .elem("state", sir.getState())
                    .end("item");
        }
        xml.end("spotInstanceRequestSet")
                .end("CancelSpotInstanceRequestsResponse");
        return xmlResponse(xml.build());
    }

    private String spotInstanceRequestXml(SpotInstanceRequest sir) {
        XmlBuilder xml = new XmlBuilder()
                .elem("spotInstanceRequestId", sir.getSpotInstanceRequestId())
                .elem("spotPrice", sir.getSpotPrice())
                .elem("type", sir.getType())
                .elem("state", sir.getState())
                .start("status")
                .elem("code", sir.getStatusCode())
                .elem("updateTime", sir.getStatusUpdateTime() != null ? ISO_FMT.format(sir.getStatusUpdateTime()) : "")
                .elem("message", sir.getStatusMessage())
                .end("status");

        if (sir.getLaunchSpecification() != null) {
            LaunchSpecification spec = sir.getLaunchSpecification();
            xml.start("launchSpecification")
                    .elem("imageId", spec.getImageId())
                    .elem("instanceType", spec.getInstanceType())
                    .elem("keyName", spec.getKeyName())
                    .elem("subnetId", spec.getSubnetId());

            xml.start("groupSet");
            for (GroupIdentifier gi : spec.getSecurityGroups()) {
                xml.start("item")
                        .elem("groupId", gi.getGroupId())
                        .elem("groupName", gi.getGroupName())
                        .end("item");
            }
            xml.end("groupSet");

            if (spec.getUserData() != null) {
                String encodedUserData = Base64.getEncoder().encodeToString(spec.getUserData().getBytes(StandardCharsets.UTF_8));
                xml.elem("userData", encodedUserData);
            }
            if (spec.getIamInstanceProfileArn() != null) {
                xml.start("iamInstanceProfile")
                        .elem("arn", spec.getIamInstanceProfileArn())
                        .end("iamInstanceProfile");
            }
            xml.end("launchSpecification");
        }

        if (sir.getInstanceId() != null) {
            xml.elem("instanceId", sir.getInstanceId());
        }
        xml.elem("createTime", sir.getCreateTime() != null ? ISO_FMT.format(sir.getCreateTime()) : "")
                .elem("productDescription", sir.getProductDescription());

        if (sir.getTags() != null && !sir.getTags().isEmpty()) {
            xml.start("tagSet");
            for (Tag t : sir.getTags()) {
                xml.start("item")
                        .elem("key", t.getKey())
                        .elem("value", t.getValue())
                        .end("item");
            }
            xml.end("tagSet");
        }

        return xml.build();
    }
}
