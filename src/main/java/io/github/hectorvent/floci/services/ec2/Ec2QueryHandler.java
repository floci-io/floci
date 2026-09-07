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
            "TransitGatewayId", "VpcEndpointId");

    /** The gateway id a route table's built-in route carries (see Ec2Service#createRouteTable). */
    private static final String LOCAL_GATEWAY_ID = "local";

    private final Ec2Service service;
    private final EmulatorConfig config;
    private final FlowLogService flowLogService;
    private final Ec2EbsEncryptionService ebsEncryptionService;
    private final Ec2IpamService ipamService;

    @Inject
    public Ec2QueryHandler(Ec2Service service, EmulatorConfig config, FlowLogService flowLogService,
                           Ec2EbsEncryptionService ebsEncryptionService, Ec2IpamService ipamService) {
        this.service = service;
        this.config = config;
        this.flowLogService = flowLogService;
        this.ebsEncryptionService = ebsEncryptionService;
        this.ipamService = ipamService;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.debugv("EC2 action: {0}", action);
        try {
            return switch (action) {
                // Instances
                case "RunInstances" -> handleRunInstances(params, region);
                case "CreateFleet" -> handleCreateFleet(params, region);
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
                // EBS encryption defaults
                case "GetEbsEncryptionByDefault" -> handleGetEbsEncryptionByDefault(region);
                case "EnableEbsEncryptionByDefault" -> handleEnableEbsEncryptionByDefault(region);
                case "DisableEbsEncryptionByDefault" -> handleDisableEbsEncryptionByDefault(region);
                case "GetEbsDefaultKmsKeyId" -> handleGetEbsDefaultKmsKeyId(region);
                case "ModifyEbsDefaultKmsKeyId" -> handleModifyEbsDefaultKmsKeyId(params, region);
                case "ResetEbsDefaultKmsKeyId" -> handleResetEbsDefaultKmsKeyId(region);
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
                // Transit Gateways
                case "CreateTransitGateway" -> handleCreateTransitGateway(params, region);
                case "DescribeTransitGateways" -> handleDescribeTransitGateways(params, region);
                case "ModifyTransitGateway" -> handleModifyTransitGateway(params, region);
                case "DeleteTransitGateway" -> handleDeleteTransitGateway(params, region);
                case "CreateTransitGatewayVpcAttachment" ->
                        handleCreateTransitGatewayVpcAttachment(params, region);
                case "DescribeTransitGatewayVpcAttachments" ->
                        handleDescribeTransitGatewayVpcAttachments(params, region);
                case "DescribeTransitGatewayAttachments" ->
                        handleDescribeTransitGatewayAttachments(params, region);
                case "ModifyTransitGatewayVpcAttachment" ->
                        handleModifyTransitGatewayVpcAttachment(params, region);
                case "DeleteTransitGatewayVpcAttachment" ->
                        handleDeleteTransitGatewayVpcAttachment(params, region);
                // Transit Gateway route tables, associations, propagations and routes
                case "CreateTransitGatewayRouteTable" -> handleCreateTransitGatewayRouteTable(params, region);
                case "DescribeTransitGatewayRouteTables" ->
                        handleDescribeTransitGatewayRouteTables(params, region);
                case "DeleteTransitGatewayRouteTable" -> handleDeleteTransitGatewayRouteTable(params, region);
                case "AssociateTransitGatewayRouteTable" ->
                        handleAssociateTransitGatewayRouteTable(params, region);
                case "DisassociateTransitGatewayRouteTable" ->
                        handleDisassociateTransitGatewayRouteTable(params, region);
                case "GetTransitGatewayRouteTableAssociations" ->
                        handleGetTransitGatewayRouteTableAssociations(params, region);
                case "EnableTransitGatewayRouteTablePropagation" ->
                        handleEnableTransitGatewayRouteTablePropagation(params, region);
                case "DisableTransitGatewayRouteTablePropagation" ->
                        handleDisableTransitGatewayRouteTablePropagation(params, region);
                case "GetTransitGatewayRouteTablePropagations" ->
                        handleGetTransitGatewayRouteTablePropagations(params, region);
                case "CreateTransitGatewayRoute" -> handleCreateTransitGatewayRoute(params, region);
                case "DeleteTransitGatewayRoute" -> handleDeleteTransitGatewayRoute(params, region);
                case "ReplaceTransitGatewayRoute" -> handleReplaceTransitGatewayRoute(params, region);
                case "SearchTransitGatewayRoutes" -> handleSearchTransitGatewayRoutes(params, region);
                case "ExportTransitGatewayRoutes" -> handleExportTransitGatewayRoutes(params, region);
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
                case "GetSecurityGroupsForVpc" -> handleGetSecurityGroupsForVpc(params, region);
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
                // VPN Gateways. There is no VPN gateway model; an empty set is
                // AWS-accurate for an account without VPN gateways and unblocks the
                // CDK VPC context provider, which always issues this describe.
                case "DescribeVpnGateways" -> handleDescribeVpnGateways();

                // Route Tables
                case "CreateRouteTable" -> handleCreateRouteTable(params, region);
                case "DescribeRouteTables" -> handleDescribeRouteTables(params, region);
                case "DeleteRouteTable" -> handleDeleteRouteTable(params, region);
                case "AssociateRouteTable" -> handleAssociateRouteTable(params, region);
                case "DisassociateRouteTable" -> handleDisassociateRouteTable(params, region);
                case "CreateVpcPeeringConnection" -> handleCreateVpcPeeringConnection(params, region);
                case "AcceptVpcPeeringConnection" -> handleAcceptVpcPeeringConnection(params, region);
                case "DescribeVpcPeeringConnections" -> handleDescribeVpcPeeringConnections(params, region);
                case "ModifyVpcPeeringConnectionOptions" -> handleModifyVpcPeeringConnectionOptions(params, region);
                case "DeleteVpcPeeringConnection" -> handleDeleteVpcPeeringConnection(params, region);

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
                // Capacity Reservations
                case "CreateCapacityReservation" -> handleCreateCapacityReservation(params, region);
                case "DescribeCapacityReservations" -> handleDescribeCapacityReservations(params, region);
                case "ModifyCapacityReservation" -> handleModifyCapacityReservation(params, region);
                case "CancelCapacityReservation" -> handleCancelCapacityReservation(params, region);
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
                case "CreateNetworkInterface" -> handleCreateNetworkInterface(params, region);
                case "DeleteNetworkInterface" -> handleDeleteNetworkInterface(params, region);
                case "AttachNetworkInterface" -> handleAttachNetworkInterface(params, region);
                case "DetachNetworkInterface" -> handleDetachNetworkInterface(params, region);
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
                // IPAM
                case "EnableIpamOrganizationAdminAccount" -> handleEnableIpamOrgAdmin(params);
                case "DisableIpamOrganizationAdminAccount" -> handleDisableIpamOrgAdmin(params);
                case "CreateIpam" -> handleCreateIpam(params, region);
                case "DescribeIpams" -> handleDescribeIpams(params, region);
                case "DeleteIpam" -> handleDeleteIpam(params, region);
                case "ModifyIpam" -> handleModifyIpam(params, region);
                case "CreateIpamPool" -> handleCreateIpamPool(params, region);
                case "DescribeIpamPools" -> handleDescribeIpamPools(params, region);
                case "DeleteIpamPool" -> handleDeleteIpamPool(params, region);
                case "ModifyIpamPool" -> handleModifyIpamPool(params, region);
                case "AssociateIpamByoasn" -> handleAssociateIpamByoasn(params, region);
                case "DescribeIpamByoasn" -> handleDescribeIpamByoasn(params, region);
                case "DisassociateIpamByoasn" -> handleDisassociateIpamByoasn(params, region);
                case "ProvisionIpamPoolCidr" -> handleProvisionIpamPoolCidr(params, region);
                case "GetIpamPoolCidrs" -> handleGetIpamPoolCidrs(params, region);
                case "AllocateIpamPoolCidr" -> handleAllocateIpamPoolCidr(params, region);
                case "ReleaseIpamPoolAllocation" -> handleReleaseIpamPoolAllocation(params, region);
                case "GetIpamPoolAllocations" -> handleGetIpamPoolAllocations(params, region);
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
            boolean hasEbs = snapshotId != null || volumeSize != null || volumeType != null
                    || deleteOnTermination != null || encrypted != null;
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
            if (proto == null) {
                break;
            }
            IpPermission perm = new IpPermission();
            perm.setIpProtocol(proto);
            String fromPort = p.getFirst(prefix + "." + i + ".FromPort");
            String toPort = p.getFirst(prefix + "." + i + ".ToPort");
            if (fromPort != null) {
                perm.setFromPort(Integer.parseInt(fromPort));
            }
            if (toPort != null) {
                perm.setToPort(Integer.parseInt(toPort));
            }
            for (int j = 1; ; j++) {
                String cidr = p.getFirst(prefix + "." + i + ".IpRanges." + j + ".CidrIp");
                if (cidr == null) {
                    cidr = p.getFirst(prefix + "." + i + ".IpRanges." + j);
                }
                if (cidr == null) {
                    break;
                }
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
                String prefixListId = p.getFirst(prefix + "." + i + ".PrefixListIds." + j + ".PrefixListId");
                if (prefixListId == null) {
                    break;
                }
                String desc = p.getFirst(prefix + "." + i + ".PrefixListIds." + j + ".Description");
                perm.getPrefixListIds().add(new PrefixListId(prefixListId, desc));
            }
            perms.add(perm);
        }
        return perms;
    }

    /**
     * Reads a request's tag specifications. Most EC2 actions carry them under the wire name
     * {@code TagSpecification}, but a handful — {@code CreateTransitGatewayVpcAttachment} among
     * them — declare no {@code locationName} and so serialize as {@code TagSpecifications}. Both
     * spellings are read, because an action that used the plural silently lost every tag.
     */
    private List<Tag> parseTagsForResource(MultivaluedMap<String, String> p, String resourceType) {
        List<Tag> tags = new ArrayList<>();
        for (String prefix : new String[] {"TagSpecification", "TagSpecifications"}) {
            for (int i = 1; ; i++) {
                String resType = p.getFirst(prefix + "." + i + ".ResourceType");
                if (resType == null) break;
                if (resourceType.equals(resType)) {
                    for (int j = 1; ; j++) {
                        String key = p.getFirst(prefix + "." + i + ".Tag." + j + ".Key");
                        if (key == null) break;
                        String value = p.getFirst(prefix + "." + i + ".Tag." + j + ".Value");
                        tags.add(new Tag(key, value));
                    }
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

    // ─── EBS encryption defaults ─────────────────────────────────────────────

    private Response handleGetEbsEncryptionByDefault(String region) {
        return ebsEncryptionBooleanResponse("GetEbsEncryptionByDefaultResponse",
                ebsEncryptionService.getEbsEncryptionByDefault(region));
    }

    private Response handleEnableEbsEncryptionByDefault(String region) {
        return ebsEncryptionBooleanResponse("EnableEbsEncryptionByDefaultResponse",
                ebsEncryptionService.enableEbsEncryptionByDefault(region));
    }

    private Response handleDisableEbsEncryptionByDefault(String region) {
        return ebsEncryptionBooleanResponse("DisableEbsEncryptionByDefaultResponse",
                ebsEncryptionService.disableEbsEncryptionByDefault(region));
    }

    private Response handleGetEbsDefaultKmsKeyId(String region) {
        return ebsKmsKeyResponse("GetEbsDefaultKmsKeyIdResponse",
                ebsEncryptionService.getEbsDefaultKmsKeyId(region));
    }

    private Response handleModifyEbsDefaultKmsKeyId(MultivaluedMap<String, String> p, String region) {
        return ebsKmsKeyResponse("ModifyEbsDefaultKmsKeyIdResponse",
                ebsEncryptionService.modifyEbsDefaultKmsKeyId(region, p.getFirst("KmsKeyId")));
    }

    private Response handleResetEbsDefaultKmsKeyId(String region) {
        return ebsKmsKeyResponse("ResetEbsDefaultKmsKeyIdResponse",
                ebsEncryptionService.resetEbsDefaultKmsKeyId(region));
    }

    private Response ebsEncryptionBooleanResponse(String rootElement, boolean enabled) {
        XmlBuilder xml = new XmlBuilder()
                .start(rootElement, AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("ebsEncryptionByDefault", String.valueOf(enabled))
                .end(rootElement);
        return xmlResponse(xml.build());
    }

    private Response ebsKmsKeyResponse(String rootElement, String kmsKeyId) {
        XmlBuilder xml = new XmlBuilder()
                .start(rootElement, AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("kmsKeyId", kmsKeyId)
                .end(rootElement);
        return xmlResponse(xml.build());
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
        // floci-kt9: override-default-eni hands RunInstances a pre-existing standalone ENI as
        // the instance's primary interface (network_interface { network_interface_id = ... }).
        String networkInterfaceId = p.getFirst("NetworkInterface.1.NetworkInterfaceId");
        int networkInterfaceDeviceIndex = parseIntParam(p, "NetworkInterface.1.DeviceIndex", 0);
        String clientToken = p.getFirst("ClientToken");
        List<String> sgIds = getList(p, "SecurityGroupId");

        // UserData is base64-encoded in the wire format
        String userDataEncoded = p.getFirst("UserData");
        String userData = null;
        if (userDataEncoded != null && !userDataEncoded.isBlank()) {
            userData = decodeUserData(userDataEncoded);
        }

        String iamInstanceProfileArn = resolveIamInstanceProfileArn(p);

        // Parse TagSpecifications. AWS applies each specification to exactly the resource
        // type it names, so the interfaces RunInstances creates are tagged only by a
        // ResourceType=network-interface specification - never by the instance's own tags.
        List<Tag> instanceTags = new ArrayList<>();
        List<Tag> networkInterfaceTags = new ArrayList<>();
        for (int i = 1; ; i++) {
            String resType = p.getFirst("TagSpecification." + i + ".ResourceType");
            if (resType == null) break;
            List<Tag> target = switch (resType) {
                case "instance" -> instanceTags;
                case "network-interface" -> networkInterfaceTags;
                default -> null;
            };
            if (target != null) {
                for (int j = 1; ; j++) {
                    String k = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Key");
                    if (k == null) break;
                    String v = p.getFirst("TagSpecification." + i + ".Tag." + j + ".Value");
                    target.add(new Tag(k, v));
                }
            }
        }

        LaunchTemplateData launchTemplateData = resolveRunInstancesLaunchTemplateData(p, region);
        if (launchTemplateData != null) {
            imageId = firstNonBlank(imageId, launchTemplateData.getImageId());
            instanceType = firstNonBlank(instanceType, launchTemplateData.getInstanceType());
            keyName = firstNonBlank(keyName, launchTemplateData.getKeyName());
            userData = firstNonBlank(userData, launchTemplateData.getUserData());
            iamInstanceProfileArn = firstNonBlank(iamInstanceProfileArn,
                    service.iamInstanceProfileArn(launchTemplateData));
            if (sgIds.isEmpty()) {
                sgIds = new ArrayList<>(launchTemplateData.effectiveSecurityGroupIds());
            }
            if (!launchTemplateData.getInstanceTags().isEmpty()) {
                Map<String, Tag> mergedTags = new LinkedHashMap<>();
                launchTemplateData.getInstanceTags().forEach(tag -> mergedTags.put(tag.getKey(), tag));
                instanceTags.forEach(tag -> mergedTags.put(tag.getKey(), tag));
                instanceTags = new ArrayList<>(mergedTags.values());
            }
        }

        Reservation res = service.runInstances(region, imageId, instanceType, minCount, maxCount,
                keyName, sgIds, subnetId, clientToken, instanceTags, userData, iamInstanceProfileArn,
                associatePublicIp, networkInterfaceId, networkInterfaceDeviceIndex);

        if (!networkInterfaceTags.isEmpty()) {
            List<String> eniIds = new ArrayList<>();
            for (Instance inst : res.getInstances()) {
                inst.getNetworkInterfaces().forEach(eni -> eniIds.add(eni.getNetworkInterfaceId()));
            }
            if (!eniIds.isEmpty()) {
                service.createTags(region, eniIds, networkInterfaceTags);
            }
        }

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

    /**
     * Handles the synchronous EC2 Fleet form used by Karpenter for node launches and
     * authorization dry-runs. Floci does not model fleet requests as a separate long-lived
     * resource: an instant fleet is represented by the instances it launches, which keeps
     * DescribeInstances and termination behavior consistent with RunInstances.
     *
     * <p>The EC2 Query model calls this member {@code LaunchTemplateConfigs}, while the wire
     * location name emitted by SDK serializers is {@code LaunchTemplateConfig.N}. Accept both
     * spellings because callers in the ecosystem use both forms.</p>
     */
    private Response handleCreateFleet(MultivaluedMap<String, String> p, String region) {
        String fleetType = firstNonBlank(p.getFirst("Type"), "instant");
        if (!"instant".equals(fleetType)) {
            throw new AwsException("InvalidParameterValue",
                    "Only instant fleets are supported for local EC2 launches.", 400);
        }
        String capacityType = firstNonBlank(
                p.getFirst("TargetCapacitySpecification.DefaultTargetCapacityType"), "on-demand");
        if (!Set.of("on-demand", "spot").contains(capacityType)) {
            throw new AwsException("InvalidParameterValue",
                    "DefaultTargetCapacityType must be on-demand or spot.", 400);
        }

        int targetCapacity = parseIntParam(p, "TargetCapacitySpecification.TotalTargetCapacity", 0);
        if (targetCapacity <= 0) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter TargetCapacitySpecification.TotalTargetCapacity.", 400);
        }

        List<FleetLaunch> launches = parseFleetLaunches(p, region);
        if (launches.isEmpty()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter LaunchTemplateConfigs.", 400);
        }

        // Resolve and validate every launch template before honoring DryRun. AWS returns
        // DryRunOperation only after it has established that the request could succeed.
        for (FleetLaunch launch : launches) {
            if (launch.imageId() == null || launch.imageId().isBlank()) {
                throw new AwsException("MissingParameter",
                        "Each fleet launch must specify an ImageId in the launch template or override.", 400);
            }
            if (launch.instanceType() == null || launch.instanceType().isBlank()) {
                throw new AwsException("MissingParameter",
                        "Each fleet launch must specify an InstanceType in the launch template or override.", 400);
            }
        }
        checkDryRun(p);

        List<FleetLaunchResult> results = new ArrayList<>(targetCapacity);
        List<String> launchedInstanceIds = new ArrayList<>(targetCapacity);
        try {
            for (int i = 0; i < targetCapacity; i++) {
                FleetLaunch launch = launches.get(i % launches.size());
                Reservation reservation = service.runInstances(
                        region,
                        launch.imageId(),
                        launch.instanceType(),
                        1,
                        1,
                        launch.keyName(),
                        launch.securityGroupIds(),
                        launch.subnetId(),
                        p.getFirst("ClientToken"),
                        launch.instanceTags(),
                        launch.userData(),
                        launch.iamInstanceProfileArn(),
                        null,
                        null,
                        0,
                        launch.availabilityZone());
                Instance instance = reservation.getInstances().get(0);
                launchedInstanceIds.add(instance.getInstanceId());
                results.add(new FleetLaunchResult(instance, launch));
            }
        } catch (RuntimeException e) {
            if (!launchedInstanceIds.isEmpty()) {
                LOG.warnv("CreateFleet failed after launching instances {0}; rolling them back: {1}",
                        launchedInstanceIds, e.getMessage());
                List<String> rollbackFailureInstanceIds = new ArrayList<>();
                List<RuntimeException> rollbackFailures = new ArrayList<>();
                for (String instanceId : launchedInstanceIds) {
                    try {
                        service.terminateInstances(region, List.of(instanceId));
                    } catch (RuntimeException cleanupFailure) {
                        rollbackFailureInstanceIds.add(instanceId);
                        rollbackFailures.add(cleanupFailure);
                        LOG.errorv(cleanupFailure, "CreateFleet rollback failed for instance {0}: {1}",
                                instanceId, cleanupFailure.getMessage());
                    }
                }
                if (!rollbackFailures.isEmpty()) {
                    String rollbackMessage = "CreateFleet rollback incomplete for instance(s): "
                            + String.join(", ", rollbackFailureInstanceIds);
                    if (e instanceof AwsException awsFailure) {
                        AwsException enrichedFailure = new AwsException(
                                awsFailure.getErrorCode(),
                                awsFailure.getMessage() + " " + rollbackMessage,
                                awsFailure.getHttpStatus());
                        enrichedFailure.addSuppressed(e);
                        rollbackFailures.forEach(enrichedFailure::addSuppressed);
                        throw enrichedFailure;
                    }
                    rollbackFailures.forEach(e::addSuppressed);
                }
            }
            throw e;
        }

        XmlBuilder xml = new XmlBuilder()
                .start("CreateFleetResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("fleetId", "fleet-" + UUID.randomUUID().toString().replace("-", ""))
                .start("fleetInstanceSet");
        // AWS groups instances that share a launch template and overrides into one fleet
        // instance entry. Keep the first-seen order stable while collecting all instance IDs.
        Map<FleetLaunchKey, List<FleetLaunchResult>> groupedResults = new LinkedHashMap<>();
        for (FleetLaunchResult result : results) {
            groupedResults.computeIfAbsent(FleetLaunchKey.from(result.launch()), ignored -> new ArrayList<>())
                    .add(result);
        }
        for (List<FleetLaunchResult> group : groupedResults.values()) {
            FleetLaunchResult first = group.getFirst();
            Instance instance = first.instance();
            FleetLaunch launch = first.launch();
            xml.start("item")
                    .start("instanceIds");
            for (FleetLaunchResult result : group) {
                xml.elem("item", result.instance().getInstanceId());
            }
            xml.end("instanceIds")
                    .elem("instanceType", instance.getInstanceType())
                    .elem("availabilityZone", instance.getPlacement() != null
                            ? instance.getPlacement().getAvailabilityZone() : null)
                    .elem("subnetId", instance.getSubnetId())
                    .elem("lifecycle", capacityType)
                    .start("launchTemplateAndOverrides")
                    .start("launchTemplateSpecification")
                    .elem("launchTemplateId", launch.launchTemplateId())
                    .elem("launchTemplateName", launch.launchTemplateName())
                    .elem("version", launch.launchTemplateVersion())
                    .end("launchTemplateSpecification")
                    .start("overrides")
                    .elem("instanceType", launch.instanceType())
                    .elem("imageId", launch.imageId())
                    .elem("subnetId", launch.subnetId())
                    .elem("availabilityZone", launch.availabilityZone())
                    .end("overrides")
                    .end("launchTemplateAndOverrides")
                    .end("item");
        }
        xml.end("fleetInstanceSet")
                .end("CreateFleetResponse");
        return xmlResponse(xml.build());
    }

    private List<FleetLaunch> parseFleetLaunches(MultivaluedMap<String, String> p, String region) {
        List<FleetLaunch> launches = new ArrayList<>();
        for (int i = 1; ; i++) {
            String base = fleetConfigBase(p, i);
            if (base == null) {
                break;
            }
            String launchTemplateId = p.getFirst(base + ".LaunchTemplateSpecification.LaunchTemplateId");
            String launchTemplateName = p.getFirst(base + ".LaunchTemplateSpecification.LaunchTemplateName");
            String version = p.getFirst(base + ".LaunchTemplateSpecification.Version");
            LaunchTemplateData template = service.resolveLaunchTemplateData(
                    region, launchTemplateId, launchTemplateName, version);
            List<Tag> fleetInstanceTags = parseTagsForResource(p, "instance");

            boolean hasOverride = false;
            for (int j = 1; ; j++) {
                String overrideBase = base + ".Overrides." + j;
                String instanceType = p.getFirst(overrideBase + ".InstanceType");
                String imageId = p.getFirst(overrideBase + ".ImageId");
                String subnetId = p.getFirst(overrideBase + ".SubnetId");
                String availabilityZone = p.getFirst(overrideBase + ".AvailabilityZone");
                if (instanceType == null && imageId == null && subnetId == null && availabilityZone == null) {
                    break;
                }
                hasOverride = true;
                launches.add(fleetLaunch(region, template, launchTemplateId, launchTemplateName, version,
                        instanceType, imageId, subnetId, availabilityZone, fleetInstanceTags));
            }
            if (!hasOverride) {
                launches.add(fleetLaunch(region, template, launchTemplateId, launchTemplateName, version,
                        null, null, null, null, fleetInstanceTags));
            }
        }
        return launches;
    }

    private FleetLaunch fleetLaunch(String region, LaunchTemplateData template, String launchTemplateId,
                                    String launchTemplateName, String version,
                                    String overrideInstanceType, String overrideImageId,
                                    String overrideSubnetId, String overrideAvailabilityZone,
                                    List<Tag> fleetInstanceTags) {
        Map<String, Tag> tags = new LinkedHashMap<>();
        for (Tag tag : template.getInstanceTags()) {
            tags.put(tag.getKey(), tag);
        }
        for (Tag tag : fleetInstanceTags) {
            tags.put(tag.getKey(), tag);
        }
        String templateSubnetId = template.getNetworkInterfaces().stream()
                .map(networkInterface -> networkInterface.getSubnetId())
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
        String subnetId = firstNonBlank(overrideSubnetId, templateSubnetId);
        String templateAvailabilityZone = template.getPlacement() != null
                ? template.getPlacement().getAvailabilityZone() : null;
        String availabilityZone = resolveFleetAvailabilityZone(
                region, templateSubnetId, templateAvailabilityZone, overrideSubnetId, overrideAvailabilityZone);
        return new FleetLaunch(
                launchTemplateId,
                launchTemplateName,
                version,
                firstNonBlank(overrideInstanceType, template.getInstanceType()),
                firstNonBlank(overrideImageId, template.getImageId()),
                subnetId,
                availabilityZone,
                template.getKeyName(),
                template.getUserData(),
                service.iamInstanceProfileArn(template),
                template.effectiveSecurityGroupIds(),
                new ArrayList<>(tags.values()));
    }

    /**
     * Resolves the placement represented by one CreateFleet launch override.
     *
     * <p>A subnet supplied by an override replaces the subnet inherited from the launch template.
     * Its availability zone therefore also replaces a template placement zone when the override
     * does not carry an explicit zone. Passing the template zone through in that case creates a
     * valid subnet-only AWS override that is incorrectly rejected as a subnet/AZ mismatch. Resolve
     * the subnet here so both the launch request and the response describe the same placement.</p>
     */
    private String resolveFleetAvailabilityZone(String region, String templateSubnetId,
                                                String templateAvailabilityZone, String overrideSubnetId,
                                                String overrideAvailabilityZone) {
        if (isSet(overrideAvailabilityZone)) {
            return overrideAvailabilityZone;
        }
        if (isSet(overrideSubnetId)) {
            return service.requireSubnet(region, overrideSubnetId).getAvailabilityZone();
        }
        if (isSet(templateAvailabilityZone)) {
            return templateAvailabilityZone;
        }
        if (isSet(templateSubnetId)) {
            return service.requireSubnet(region, templateSubnetId).getAvailabilityZone();
        }
        return null;
    }

    private String fleetConfigBase(MultivaluedMap<String, String> p, int index) {
        String singular = "LaunchTemplateConfig." + index;
        String plural = "LaunchTemplateConfigs." + index;
        if (anyParamStartsWith(p, singular + ".")) {
            return singular;
        }
        if (anyParamStartsWith(p, plural + ".")) {
            return plural;
        }
        return null;
    }

    private record FleetLaunch(String launchTemplateId, String launchTemplateName, String launchTemplateVersion,
                               String instanceType, String imageId, String subnetId, String availabilityZone,
                               String keyName, String userData, String iamInstanceProfileArn,
                               List<String> securityGroupIds, List<Tag> instanceTags) {}

    private record FleetLaunchKey(String launchTemplateId, String launchTemplateName, String launchTemplateVersion,
                                  String instanceType, String imageId, String subnetId, String availabilityZone) {
        private static FleetLaunchKey from(FleetLaunch launch) {
            return new FleetLaunchKey(launch.launchTemplateId(), launch.launchTemplateName(),
                    launch.launchTemplateVersion(), launch.instanceType(), launch.imageId(), launch.subnetId(),
                    launch.availabilityZone());
        }
    }

    private record FleetLaunchResult(Instance instance, FleetLaunch launch) {}

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

    // ─── VPC handlers ─────────────────────────────────────────────────────────

    private Response handleCreateVpc(MultivaluedMap<String, String> p, String region) {
        String cidrBlock = p.getFirst("CidrBlock");
        boolean amazonProvidedIpv6 = "true".equalsIgnoreCase(p.getFirst("AmazonProvidedIpv6CidrBlock"));
        Vpc vpc = service.createVpc(region, cidrBlock, false, amazonProvidedIpv6);
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

    // The common AWS interface-endpoint services, as short names. Rendered as
    // com.amazonaws.<region>.<name>. CDK's InterfaceVpcEndpoint (lookupSupportedAzs)
    // calls DescribeVpcEndpointServices at synth time; an empty set aborts synth.
    private static final List<String> INTERFACE_ENDPOINT_SERVICES = List.of(
            "ec2", "ec2messages", "ssm", "ssmmessages", "logs", "monitoring", "sts",
            "secretsmanager", "kms", "ecr.api", "ecr.dkr", "ecs", "ecs-agent", "ecs-telemetry",
            "elasticloadbalancing", "sns", "sqs", "kinesis-streams", "kinesis-firehose",
            "states", "events", "lambda", "glue", "athena", "iot.data", "execute-api");

    private Response handleDescribeVpcEndpointServices(MultivaluedMap<String, String> p, String region) {
        List<String> requested = getList(p, "ServiceName");
        List<String> azNames = service.describeAvailabilityZones(region).stream()
                .map(z -> z.get("zoneName")).toList();

        XmlBuilder xml = new XmlBuilder()
                .start("DescribeVpcEndpointServicesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("serviceNameSet");
        List<String> fullNames = new ArrayList<>();
        // An explicit ServiceName filter wins: the emulator supports any
        // interface service in every AZ, so echo exactly what was asked. CDK's
        // InterfaceVpcEndpoint queries a specific (often custom) service name
        // and fails if the response omits it or lists no AZs.
        if (!requested.isEmpty()) {
            fullNames.addAll(requested);
        } else {
            // S3 has both a Gateway and an Interface offering; keep it in the set.
            for (String name : INTERFACE_ENDPOINT_SERVICES) {
                fullNames.add("com.amazonaws." + region + "." + name);
            }
            fullNames.add("com.amazonaws." + region + ".s3");
        }
        for (String full : fullNames) {
            xml.elem("item", full);
        }
        xml.end("serviceNameSet").start("serviceDetailSet");
        for (String full : fullNames) {
            // S3 is the one service with both offerings, and AWS reports both
            // types on its single service detail. Everything else is Interface.
            List<String> serviceTypes = full.endsWith(".s3")
                    ? List.of("Gateway", "Interface")
                    : List.of("Interface");
            xml.start("item")
                    .elem("serviceName", full)
                    .start("serviceType");
            for (String serviceType : serviceTypes) {
                xml.start("item").elem("serviceType", serviceType).end("item");
            }
            xml.end("serviceType")
                    .start("availabilityZoneSet");
            for (String az : azNames) xml.elem("item", az);
            xml.end("availabilityZoneSet")
                    .elem("owner", "amazon")
                    .elem("acceptanceRequired", "false")
                    .elem("managesVpcEndpoints", "false")
                    .end("item");
        }
        xml.end("serviceDetailSet").end("DescribeVpcEndpointServicesResponse");
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
                    logDestinationType, logDestination, logFormat, maxAgg);
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
                    .elem("flowLogStatus", fl.getFlowLogStatus())
                    .elem("deliverLogsStatus", fl.getDeliverLogsStatus())
                    .elem("maxAggregationInterval", String.valueOf(fl.getMaxAggregationInterval()))
                    .elem("creationTime", ISO_FMT.format(fl.getCreationTime()))
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

    // ─── IPAM ─────────────────────────────────────────────────────────────────

    private Response handleEnableIpamOrgAdmin(MultivaluedMap<String, String> p) {
        ipamService.enableIpamOrganizationAdminAccount(p.getFirst("DelegatedAdminAccountId"));
        XmlBuilder xml = new XmlBuilder()
                .start("EnableIpamOrganizationAdminAccountResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("success", "true")
                .end("EnableIpamOrganizationAdminAccountResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDisableIpamOrgAdmin(MultivaluedMap<String, String> p) {
        ipamService.disableIpamOrganizationAdminAccount(p.getFirst("DelegatedAdminAccountId"));
        XmlBuilder xml = new XmlBuilder()
                .start("DisableIpamOrganizationAdminAccountResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("success", "true")
                .end("DisableIpamOrganizationAdminAccountResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateIpam(MultivaluedMap<String, String> p, String region) {
        List<String> operatingRegions = new ArrayList<>();
        for (int i = 1; p.getFirst("OperatingRegion." + i + ".RegionName") != null; i++) {
            operatingRegions.add(p.getFirst("OperatingRegion." + i + ".RegionName"));
        }
        if (operatingRegions.isEmpty()) {
            operatingRegions.add(region);
        }
        checkDryRun(p);
        Ipam ipam = ipamService.createIpam(region, p.getFirst("Description"), operatingRegions,
                null,
                p.getFirst("EnablePrivateGua") == null ? false : Boolean.parseBoolean(p.getFirst("EnablePrivateGua")),
                p.getFirst("MeteredAccount"), p.getFirst("Tier"), p.getFirst("ClientToken"),
                parseTagsForResource(p, "ipam"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateIpamResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString());
        writeIpam(xml, "ipam", ipam);
        xml.end("CreateIpamResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeIpams(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        List<String> ids = getList(p, "IpamId");
        if (ids.isEmpty()) {
            ids = getList(p, "IpamIds.member");
        }
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeIpamsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("ipamSet");
        for (Ipam ipam : ipamService.describeIpams(region, ids)) {
            writeIpam(xml, "item", ipam);
        }
        xml.end("ipamSet").end("DescribeIpamsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteIpam(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        Ipam ipam = ipamService.deleteIpam(region, p.getFirst("IpamId"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteIpamResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString());
        writeIpam(xml, "ipam", ipam);
        xml.end("DeleteIpamResponse");
        return xmlResponse(xml.build());
    }

    private Response handleAssociateIpamByoasn(MultivaluedMap<String, String> params, String region) {
        checkDryRun(params);
        AsnAssociation association = ipamService.associateIpamByoasn(region, params.getFirst("Asn"), params.getFirst("Cidr"));
        XmlBuilder xml = new XmlBuilder()
                .start("AssociateIpamByoasnResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("asnAssociation")
                .elem("asn", association.getAsn())
                .elem("cidr", association.getCidr())
                .elem("state", association.getState())
                .elem("statusMessage", association.getStatusMessage())
                .end("asnAssociation")
                .end("AssociateIpamByoasnResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeIpamByoasn(MultivaluedMap<String, String> params, String region) {
        checkDryRun(params);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeIpamByoasnResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("byoasnSet");
        for (AsnAssociation association : ipamService.describeIpamByoasn(region)) {
            xml.start("item")
                    .elem("asn", association.getAsn())
                    .elem("ipamId", association.getIpamId())
                    .elem("state", association.getState())
                    .elem("statusMessage", association.getStatusMessage())
                    .end("item");
        }
        xml.end("byoasnSet").end("DescribeIpamByoasnResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDisassociateIpamByoasn(MultivaluedMap<String, String> params, String region) {
        checkDryRun(params);
        AsnAssociation association = ipamService.disassociateIpamByoasn(
                region, params.getFirst("Asn"), params.getFirst("Cidr"));
        XmlBuilder xml = new XmlBuilder()
                .start("DisassociateIpamByoasnResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("asnAssociation")
                .elem("asn", association.getAsn())
                .elem("cidr", association.getCidr())
                .elem("state", association.getState())
                .elem("statusMessage", association.getStatusMessage())
                .end("asnAssociation")
                .end("DisassociateIpamByoasnResponse");
        return xmlResponse(xml.build());
    }

    private void checkDryRun(MultivaluedMap<String, String> params) {
        if (Boolean.parseBoolean(params.getFirst("DryRun"))) {
            throw new AwsException("DryRunOperation", "Request would have succeeded, but DryRun flag is set.", 412);
        }
    }

    private Response handleModifyIpam(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        List<String> addOperatingRegions = new ArrayList<>();
        for (int i = 1; p.getFirst("AddOperatingRegion." + i + ".RegionName") != null; i++) {
            addOperatingRegions.add(p.getFirst("AddOperatingRegion." + i + ".RegionName"));
        }
        List<String> removeOperatingRegions = new ArrayList<>();
        for (int i = 1; p.getFirst("RemoveOperatingRegion." + i + ".RegionName") != null; i++) {
            removeOperatingRegions.add(p.getFirst("RemoveOperatingRegion." + i + ".RegionName"));
        }
        Ipam ipam = ipamService.modifyIpam(
                region,
                p.getFirst("IpamId"),
                p.getFirst("Description"),
                addOperatingRegions,
                removeOperatingRegions,
                p.getFirst("EnablePrivateGua") == null ? null : Boolean.parseBoolean(p.getFirst("EnablePrivateGua")),
                p.getFirst("MeteredAccount"),
                p.getFirst("Tier"));
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyIpamResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString());
        writeIpam(xml, "ipam", ipam);
        xml.end("ModifyIpamResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateIpamPool(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        IpamPool pool = ipamService.createIpamPool(region,
                p.getFirst("IpamScopeId"),
                p.getFirst("Locale"),
                p.getFirst("SourceIpamPoolId"),
                p.getFirst("AddressFamily"),
                p.getFirst("Description"),
                null,
                p.getFirst("ClientToken"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateIpamPoolResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString());
        writeIpamPool(xml, "ipamPool", pool);
        xml.end("CreateIpamPoolResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeIpamPools(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        List<String> ids = getList(p, "IpamPoolId");
        if (ids.isEmpty()) {
            ids = getList(p, "IpamPoolIds.member");
        }
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeIpamPoolsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("ipamPoolSet");
        for (IpamPool pool : ipamService.describeIpamPools(region, ids)) {
            writeIpamPool(xml, "item", pool);
        }
        xml.end("ipamPoolSet").end("DescribeIpamPoolsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteIpamPool(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        IpamPool pool = ipamService.deleteIpamPool(region, p.getFirst("IpamPoolId"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteIpamPoolResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString());
        writeIpamPool(xml, "ipamPool", pool);
        xml.end("DeleteIpamPoolResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyIpamPool(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        IpamPool pool = ipamService.modifyIpamPool(
                region,
                p.getFirst("IpamPoolId"),
                p.getFirst("Description"),
                p.getFirst("AutoImport") == null ? null : Boolean.parseBoolean(p.getFirst("AutoImport")),
                parseOptionalInt(p.getFirst("AllocationMinNetmaskLength"), "AllocationMinNetmaskLength"),
                parseOptionalInt(p.getFirst("AllocationMaxNetmaskLength"), "AllocationMaxNetmaskLength"),
                parseOptionalInt(p.getFirst("AllocationDefaultNetmaskLength"), "AllocationDefaultNetmaskLength"),
                Boolean.parseBoolean(p.getFirst("ClearAllocationDefaultNetmaskLength")));
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyIpamPoolResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString());
        writeIpamPool(xml, "ipamPool", pool);
        xml.end("ModifyIpamPoolResponse");
        return xmlResponse(xml.build());
    }

    private Response handleProvisionIpamPoolCidr(MultivaluedMap<String, String> p, String region) {
        IpamPoolCidr cidr = ipamService.provisionIpamPoolCidr(region,
                p.getFirst("IpamPoolId"), p.getFirst("Cidr"), p.getFirst("ClientToken"));
        XmlBuilder xml = new XmlBuilder()
                .start("ProvisionIpamPoolCidrResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("ipamPoolCidr")
                .elem("cidr", cidr.getCidr())
                .elem("state", cidr.getState())
                .end("ipamPoolCidr")
                .end("ProvisionIpamPoolCidrResponse");
        return xmlResponse(xml.build());
    }

    private Response handleGetIpamPoolCidrs(MultivaluedMap<String, String> p, String region) {
        XmlBuilder xml = new XmlBuilder()
                .start("GetIpamPoolCidrsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("ipamPoolCidrSet");
        for (IpamPoolCidr cidr : ipamService.getIpamPoolCidrs(region, p.getFirst("IpamPoolId"))) {
            xml.start("item")
                    .elem("cidr", cidr.getCidr())
                    .elem("state", cidr.getState())
                    .end("item");
        }
        xml.end("ipamPoolCidrSet").end("GetIpamPoolCidrsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleAllocateIpamPoolCidr(MultivaluedMap<String, String> p, String region) {
        IpamPoolAllocation allocation = ipamService.allocateIpamPoolCidr(region,
                p.getFirst("IpamPoolId"),
                parseOptionalInt(p.getFirst("NetmaskLength"), "NetmaskLength"),
                p.getFirst("Cidr"),
                p.getFirst("Description"),
                p.getFirst("ClientToken"));
        XmlBuilder xml = new XmlBuilder()
                .start("AllocateIpamPoolCidrResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString());
        writeIpamPoolAllocation(xml, "ipamPoolAllocation", allocation);
        xml.end("AllocateIpamPoolCidrResponse");
        return xmlResponse(xml.build());
    }

    private Response handleReleaseIpamPoolAllocation(MultivaluedMap<String, String> p, String region) {
        ipamService.releaseIpamPoolAllocation(region,
                p.getFirst("IpamPoolId"),
                p.getFirst("IpamPoolAllocationId"),
                p.getFirst("Cidr"));
        XmlBuilder xml = new XmlBuilder()
                .start("ReleaseIpamPoolAllocationResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("success", "true")
                .end("ReleaseIpamPoolAllocationResponse");
        return xmlResponse(xml.build());
    }

    private Response handleGetIpamPoolAllocations(MultivaluedMap<String, String> p, String region) {
        XmlBuilder xml = new XmlBuilder()
                .start("GetIpamPoolAllocationsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("ipamPoolAllocationSet");
        for (IpamPoolAllocation allocation
                : ipamService.getIpamPoolAllocations(region, p.getFirst("IpamPoolId"))) {
            writeIpamPoolAllocation(xml, "item", allocation);
        }
        xml.end("ipamPoolAllocationSet").end("GetIpamPoolAllocationsResponse");
        return xmlResponse(xml.build());
    }

    private void writeIpam(XmlBuilder xml, String wrapper, Ipam ipam) {
        xml.start(wrapper)
                .elem("ipamId", ipam.getIpamId())
                .elem("ipamArn", ipam.getIpamArn())
                .elem("ipamRegion", ipam.getRegion())
                .elem("ownerId", ipam.getOwnerId())
                .elem("publicDefaultScopeId", ipam.getPublicDefaultScopeId())
                .elem("privateDefaultScopeId", ipam.getPrivateDefaultScopeId())
                .elem("scopeCount", String.valueOf(ipam.getScopes().size()))
                .elem("state", ipam.getState())
                .elem("enablePrivateGua", String.valueOf(Boolean.TRUE.equals(ipam.getEnablePrivateGua())))
                .elem("meteredAccount", ipam.getMeteredAccount())
                .elem("tier", ipam.getTier());
        if (ipam.getDescription() != null) {
            xml.elem("description", ipam.getDescription());
        }
        xml.start("operatingRegionSet");
        for (String operatingRegion : ipam.getOperatingRegions()) {
            xml.start("item").elem("regionName", operatingRegion).end("item");
        }
        xml.end("operatingRegionSet")
                .raw(tagSetXml(ipam.getTags()))
                .end(wrapper);
    }

    private void writeIpamPool(XmlBuilder xml, String wrapper, IpamPool pool) {
        xml.start(wrapper)
                .elem("ipamPoolId", pool.getIpamPoolId())
                .elem("ipamPoolArn", pool.getIpamPoolArn())
                .elem("ipamScopeId", pool.getIpamScopeId())
                .elem("ownerId", pool.getOwnerId())
                .elem("locale", pool.getLocale())
                .elem("addressFamily", pool.getAddressFamily())
                .elem("state", pool.getState())
                .elem("autoImport", String.valueOf(pool.isAutoImport()));
        if (pool.getSourceIpamPoolId() != null) {
            xml.elem("sourceIpamPoolId", pool.getSourceIpamPoolId());
        }
        if (pool.getDescription() != null) {
            xml.elem("description", pool.getDescription());
        }
        if (pool.getAllocationMinNetmaskLength() != null) {
            xml.elem("allocationMinNetmaskLength", String.valueOf(pool.getAllocationMinNetmaskLength()));
        }
        if (pool.getAllocationMaxNetmaskLength() != null) {
            xml.elem("allocationMaxNetmaskLength", String.valueOf(pool.getAllocationMaxNetmaskLength()));
        }
        if (pool.getAllocationDefaultNetmaskLength() != null) {
            xml.elem("allocationDefaultNetmaskLength", String.valueOf(pool.getAllocationDefaultNetmaskLength()));
        }
        xml.end(wrapper);
    }

    private void writeIpamPoolAllocation(XmlBuilder xml, String wrapper, IpamPoolAllocation allocation) {
        xml.start(wrapper)
                .elem("ipamPoolAllocationId", allocation.getIpamPoolAllocationId())
                .elem("cidr", allocation.getCidr())
                .elem("resourceType", allocation.getResourceType());
        if (allocation.getDescription() != null) {
            xml.elem("description", allocation.getDescription());
        }
        xml.end(wrapper);
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
                parseTagsForResource(p, "vpc-endpoint"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateVpcEndpointResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpcEndpoint").raw(vpcEndpointXml(endpoint)).end("vpcEndpoint")
                .end("CreateVpcEndpointResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyVpcEndpoint(MultivaluedMap<String, String> p, String region) {
        service.modifyVpcEndpoint(
                region,
                p.getFirst("VpcEndpointId"),
                getList(p, "AddRouteTableId"),
                getList(p, "RemoveRouteTableId"),
                getList(p, "AddSubnetId"),
                getList(p, "RemoveSubnetId"),
                getList(p, "AddSecurityGroupId"),
                getList(p, "RemoveSecurityGroupId"),
                p.getFirst("PolicyDocument"),
                p.getFirst("ResetPolicy") != null ? Boolean.valueOf(p.getFirst("ResetPolicy")) : null,
                p.getFirst("PrivateDnsEnabled") != null ? Boolean.valueOf(p.getFirst("PrivateDnsEnabled")) : null);
        // ModifyVpcEndpoint returns only a boolean; the caller re-reads the endpoint
        // through DescribeVpcEndpoints to see the result.
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyVpcEndpointResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("return", true)
                .end("ModifyVpcEndpointResponse");
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

    private Response handleCreateTransitGateway(MultivaluedMap<String, String> p, String region) {
        TransitGateway gateway = service.createTransitGateway(
                region,
                p.getFirst("Description"),
                parseTransitGatewayOptions(p, "Options"),
                parseTagsForResource(p, "transit-gateway"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateTransitGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGateway").raw(transitGatewayXml(gateway)).end("transitGateway")
                .end("CreateTransitGatewayResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeTransitGateways(MultivaluedMap<String, String> p, String region) {
        List<TransitGateway> gateways = service.describeTransitGateways(
                region, getList(p, "TransitGatewayIds"), getFilters(p));
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
                parseTransitGatewayOptions(p, "Options"),
                getList(p, "Options.AddTransitGatewayCidrBlocks"),
                getList(p, "Options.RemoveTransitGatewayCidrBlocks"));
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyTransitGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                // Verified against a live account: modify echoes the gateway without its tagSet,
                // unlike create and describe.
                .start("transitGateway").raw(transitGatewayXml(gateway, false)).end("transitGateway")
                .end("ModifyTransitGatewayResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteTransitGateway(MultivaluedMap<String, String> p, String region) {
        TransitGateway gateway = service.deleteTransitGateway(region, p.getFirst("TransitGatewayId"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteTransitGatewayResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGateway").raw(transitGatewayXml(gateway, false)).end("transitGateway")
                .end("DeleteTransitGatewayResponse");
        return xmlResponse(xml.build());
    }

    private TransitGatewayOptions parseTransitGatewayOptions(MultivaluedMap<String, String> p, String prefix) {
        TransitGatewayOptions options = new TransitGatewayOptions();
        options.setAmazonSideAsn(longOrNull(p, prefix + ".AmazonSideAsn"));
        options.setAutoAcceptSharedAttachments(p.getFirst(prefix + ".AutoAcceptSharedAttachments"));
        options.setDefaultRouteTableAssociation(p.getFirst(prefix + ".DefaultRouteTableAssociation"));
        options.setAssociationDefaultRouteTableId(p.getFirst(prefix + ".AssociationDefaultRouteTableId"));
        options.setDefaultRouteTablePropagation(p.getFirst(prefix + ".DefaultRouteTablePropagation"));
        options.setPropagationDefaultRouteTableId(p.getFirst(prefix + ".PropagationDefaultRouteTableId"));
        options.setVpnEcmpSupport(p.getFirst(prefix + ".VpnEcmpSupport"));
        options.setDnsSupport(p.getFirst(prefix + ".DnsSupport"));
        options.setSecurityGroupReferencingSupport(p.getFirst(prefix + ".SecurityGroupReferencingSupport"));
        options.setMulticastSupport(p.getFirst(prefix + ".MulticastSupport"));
        options.setTransitGatewayCidrBlocks(getList(p, prefix + ".TransitGatewayCidrBlocks"));
        return options;
    }

    private Response handleCreateTransitGatewayVpcAttachment(MultivaluedMap<String, String> p, String region) {
        TransitGatewayVpcAttachment attachment = service.createTransitGatewayVpcAttachment(
                region,
                p.getFirst("TransitGatewayId"),
                p.getFirst("VpcId"),
                getList(p, "SubnetIds"),
                parseVpcAttachmentOptions(p),
                parseTagsForResource(p, "transit-gateway-attachment"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateTransitGatewayVpcAttachmentResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayVpcAttachment").raw(vpcAttachmentXml(attachment, true, true))
                .end("transitGatewayVpcAttachment")
                .end("CreateTransitGatewayVpcAttachmentResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeTransitGatewayVpcAttachments(MultivaluedMap<String, String> p, String region) {
        List<TransitGatewayVpcAttachment> attachments = service.describeTransitGatewayVpcAttachments(
                region, getList(p, "TransitGatewayAttachmentIds"), getFilters(p));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTransitGatewayVpcAttachmentsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayVpcAttachments");
        for (TransitGatewayVpcAttachment attachment : attachments) {
            xml.start("item").raw(vpcAttachmentXml(attachment, true, true)).end("item");
        }
        xml.end("transitGatewayVpcAttachments").end("DescribeTransitGatewayVpcAttachmentsResponse");
        return xmlResponse(xml.build());
    }

    /**
     * The resource-agnostic view of the same attachments. It is a different shape rather than a
     * superset: the subnets and options are gone, the VPC appears as a typed resource, and the
     * route table association shows up here and nowhere else.
     */
    private Response handleDescribeTransitGatewayAttachments(MultivaluedMap<String, String> p, String region) {
        List<TransitGatewayVpcAttachment> attachments = service.describeTransitGatewayVpcAttachments(
                region, getList(p, "TransitGatewayAttachmentIds"), getFilters(p));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTransitGatewayAttachmentsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayAttachments");
        for (TransitGatewayVpcAttachment attachment : attachments) {
            XmlBuilder item = new XmlBuilder()
                    .elem("transitGatewayAttachmentId", attachment.getTransitGatewayAttachmentId())
                    .elem("transitGatewayId", attachment.getTransitGatewayId())
                    .elem("transitGatewayOwnerId", attachment.getTransitGatewayOwnerId())
                    .elem("resourceOwnerId", attachment.getVpcOwnerId())
                    .elem("resourceType", "vpc")
                    .elem("resourceId", attachment.getVpcId())
                    .elem("state", attachment.getState());
            if (attachment.getAssociationRouteTableId() != null) {
                item.start("association")
                        .elem("transitGatewayRouteTableId", attachment.getAssociationRouteTableId())
                        .elem("state", attachment.getAssociationState())
                        .end("association");
            }
            item.elem("creationTime", attachment.getCreationTime())
                    .raw(tagSetXml(attachment.getTags()));
            xml.start("item").raw(item.build()).end("item");
        }
        xml.end("transitGatewayAttachments").end("DescribeTransitGatewayAttachmentsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyTransitGatewayVpcAttachment(MultivaluedMap<String, String> p, String region) {
        TransitGatewayVpcAttachment attachment = service.modifyTransitGatewayVpcAttachment(
                region,
                p.getFirst("TransitGatewayAttachmentId"),
                getList(p, "AddSubnetIds"),
                getList(p, "RemoveSubnetIds"),
                parseVpcAttachmentOptions(p));
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyTransitGatewayVpcAttachmentResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                // Verified live: modify echoes the attachment without its tagSet.
                .start("transitGatewayVpcAttachment").raw(vpcAttachmentXml(attachment, true, false))
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
                // Verified live: delete drops the subnets and the tagSet from the echo.
                .start("transitGatewayVpcAttachment").raw(vpcAttachmentXml(attachment, false, false))
                .end("transitGatewayVpcAttachment")
                .end("DeleteTransitGatewayVpcAttachmentResponse");
        return xmlResponse(xml.build());
    }

    private TransitGatewayVpcAttachmentOptions parseVpcAttachmentOptions(MultivaluedMap<String, String> p) {
        TransitGatewayVpcAttachmentOptions options = new TransitGatewayVpcAttachmentOptions();
        options.setDnsSupport(p.getFirst("Options.DnsSupport"));
        options.setSecurityGroupReferencingSupport(p.getFirst("Options.SecurityGroupReferencingSupport"));
        options.setIpv6Support(p.getFirst("Options.Ipv6Support"));
        options.setApplianceModeSupport(p.getFirst("Options.ApplianceModeSupport"));
        return options;
    }

    private Response handleCreateTransitGatewayRouteTable(MultivaluedMap<String, String> p, String region) {
        TransitGatewayRouteTable routeTable = service.createTransitGatewayRouteTable(
                region, p.getFirst("TransitGatewayId"),
                parseTagsForResource(p, "transit-gateway-route-table"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateTransitGatewayRouteTableResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayRouteTable").raw(routeTableXml(routeTable, true))
                .end("transitGatewayRouteTable")
                .end("CreateTransitGatewayRouteTableResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeTransitGatewayRouteTables(MultivaluedMap<String, String> p, String region) {
        List<TransitGatewayRouteTable> routeTables = service.describeTransitGatewayRouteTables(
                region, getList(p, "TransitGatewayRouteTableIds"), getFilters(p));
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeTransitGatewayRouteTablesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayRouteTables");
        for (TransitGatewayRouteTable routeTable : routeTables) {
            xml.start("item").raw(routeTableXml(routeTable, true)).end("item");
        }
        xml.end("transitGatewayRouteTables").end("DescribeTransitGatewayRouteTablesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteTransitGatewayRouteTable(MultivaluedMap<String, String> p, String region) {
        TransitGatewayRouteTable routeTable = service.deleteTransitGatewayRouteTable(
                region, p.getFirst("TransitGatewayRouteTableId"));
        XmlBuilder xml = new XmlBuilder()
                .start("DeleteTransitGatewayRouteTableResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayRouteTable").raw(routeTableXml(routeTable, false))
                .end("transitGatewayRouteTable")
                .end("DeleteTransitGatewayRouteTableResponse");
        return xmlResponse(xml.build());
    }

    private Response handleAssociateTransitGatewayRouteTable(MultivaluedMap<String, String> p, String region) {
        TransitGatewayVpcAttachment attachment = service.associateTransitGatewayRouteTable(
                region, p.getFirst("TransitGatewayRouteTableId"), p.getFirst("TransitGatewayAttachmentId"));
        // Verified live: the call reports the transitional state even though the association is
        // already recorded.
        return xmlResponse(new XmlBuilder()
                .start("AssociateTransitGatewayRouteTableResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("association").raw(associationXml(attachment, p.getFirst("TransitGatewayRouteTableId"),
                        "associating", true)).end("association")
                .end("AssociateTransitGatewayRouteTableResponse").build());
    }

    private Response handleDisassociateTransitGatewayRouteTable(MultivaluedMap<String, String> p, String region) {
        String routeTableId = p.getFirst("TransitGatewayRouteTableId");
        TransitGatewayVpcAttachment attachment = service.disassociateTransitGatewayRouteTable(
                region, routeTableId, p.getFirst("TransitGatewayAttachmentId"));
        return xmlResponse(new XmlBuilder()
                .start("DisassociateTransitGatewayRouteTableResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("association").raw(associationXml(attachment, routeTableId, "disassociating", true))
                .end("association")
                .end("DisassociateTransitGatewayRouteTableResponse").build());
    }

    private Response handleGetTransitGatewayRouteTableAssociations(
            MultivaluedMap<String, String> p, String region) {
        String routeTableId = p.getFirst("TransitGatewayRouteTableId");
        List<TransitGatewayVpcAttachment> associated = service.associationsOf(region, routeTableId);
        XmlBuilder xml = new XmlBuilder()
                .start("GetTransitGatewayRouteTableAssociationsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("associations");
        for (TransitGatewayVpcAttachment attachment : associated) {
            // The listing form carries no route table id: the caller named it in the request.
            xml.start("item").raw(associationXml(attachment, routeTableId, attachment.getAssociationState(), false))
                    .end("item");
        }
        xml.end("associations").end("GetTransitGatewayRouteTableAssociationsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleEnableTransitGatewayRouteTablePropagation(
            MultivaluedMap<String, String> p, String region) {
        TransitGatewayRouteTablePropagation propagation = service.enableTransitGatewayRouteTablePropagation(
                region, p.getFirst("TransitGatewayRouteTableId"), p.getFirst("TransitGatewayAttachmentId"));
        return xmlResponse(new XmlBuilder()
                .start("EnableTransitGatewayRouteTablePropagationResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("propagation").raw(propagationXml(propagation, true)).end("propagation")
                .end("EnableTransitGatewayRouteTablePropagationResponse").build());
    }

    private Response handleDisableTransitGatewayRouteTablePropagation(
            MultivaluedMap<String, String> p, String region) {
        TransitGatewayRouteTablePropagation propagation = service.disableTransitGatewayRouteTablePropagation(
                region, p.getFirst("TransitGatewayRouteTableId"), p.getFirst("TransitGatewayAttachmentId"));
        return xmlResponse(new XmlBuilder()
                .start("DisableTransitGatewayRouteTablePropagationResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("propagation").raw(propagationXml(propagation, true)).end("propagation")
                .end("DisableTransitGatewayRouteTablePropagationResponse").build());
    }

    private Response handleGetTransitGatewayRouteTablePropagations(
            MultivaluedMap<String, String> p, String region) {
        List<TransitGatewayRouteTablePropagation> propagations =
                service.propagationsOf(region, p.getFirst("TransitGatewayRouteTableId"));
        XmlBuilder xml = new XmlBuilder()
                .start("GetTransitGatewayRouteTablePropagationsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("transitGatewayRouteTablePropagations");
        for (TransitGatewayRouteTablePropagation propagation : propagations) {
            // The listing form drops the route table id that enable and disable both carry.
            xml.start("item").raw(propagationXml(propagation, false)).end("item");
        }
        xml.end("transitGatewayRouteTablePropagations")
                .end("GetTransitGatewayRouteTablePropagationsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateTransitGatewayRoute(MultivaluedMap<String, String> p, String region) {
        TransitGatewayRoute route = service.createTransitGatewayRoute(
                region,
                p.getFirst("TransitGatewayRouteTableId"),
                p.getFirst("DestinationCidrBlock"),
                p.getFirst("TransitGatewayAttachmentId"),
                Boolean.parseBoolean(p.getFirst("Blackhole")));
        return xmlResponse(new XmlBuilder()
                .start("CreateTransitGatewayRouteResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("route").raw(routeXml(route)).end("route")
                .end("CreateTransitGatewayRouteResponse").build());
    }

    private Response handleReplaceTransitGatewayRoute(MultivaluedMap<String, String> p, String region) {
        TransitGatewayRoute route = service.replaceTransitGatewayRoute(
                region,
                p.getFirst("TransitGatewayRouteTableId"),
                p.getFirst("DestinationCidrBlock"),
                p.getFirst("TransitGatewayAttachmentId"),
                Boolean.parseBoolean(p.getFirst("Blackhole")));
        return xmlResponse(new XmlBuilder()
                .start("ReplaceTransitGatewayRouteResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("route").raw(routeXml(route)).end("route")
                .end("ReplaceTransitGatewayRouteResponse").build());
    }

    private Response handleDeleteTransitGatewayRoute(MultivaluedMap<String, String> p, String region) {
        TransitGatewayRoute route = service.deleteTransitGatewayRoute(
                region, p.getFirst("TransitGatewayRouteTableId"), p.getFirst("DestinationCidrBlock"));
        return xmlResponse(new XmlBuilder()
                .start("DeleteTransitGatewayRouteResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("route").raw(routeXml(route)).end("route")
                .end("DeleteTransitGatewayRouteResponse").build());
    }

    private Response handleSearchTransitGatewayRoutes(MultivaluedMap<String, String> p, String region) {
        List<TransitGatewayRoute> routes = service.searchTransitGatewayRoutes(
                region, p.getFirst("TransitGatewayRouteTableId"), getFilters(p));
        XmlBuilder xml = new XmlBuilder()
                .start("SearchTransitGatewayRoutesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("routeSet");
        for (TransitGatewayRoute route : routes) {
            xml.start("item").raw(routeXml(route)).end("item");
        }
        xml.end("routeSet")
                .elem("additionalRoutesAvailable", "false")
                .end("SearchTransitGatewayRoutesResponse");
        return xmlResponse(xml.build());
    }

    private Response handleExportTransitGatewayRoutes(MultivaluedMap<String, String> p, String region) {
        String s3Location = service.exportTransitGatewayRoutes(
                region, p.getFirst("TransitGatewayRouteTableId"), p.getFirst("S3Bucket"));
        XmlBuilder xml = new XmlBuilder()
                .start("ExportTransitGatewayRoutesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("s3Location", s3Location)
                .end("ExportTransitGatewayRoutesResponse");
        return xmlResponse(xml.build());
    }

    private String routeTableXml(TransitGatewayRouteTable routeTable, boolean includeTags) {
        XmlBuilder xml = new XmlBuilder()
                .elem("transitGatewayRouteTableId", routeTable.getTransitGatewayRouteTableId())
                .elem("transitGatewayId", routeTable.getTransitGatewayId())
                .elem("state", routeTable.getState())
                .elem("defaultAssociationRouteTable", String.valueOf(routeTable.isDefaultAssociationRouteTable()))
                .elem("defaultPropagationRouteTable", String.valueOf(routeTable.isDefaultPropagationRouteTable()))
                .elem("creationTime", routeTable.getCreationTime());
        if (includeTags) {
            xml.raw(tagSetXml(routeTable.getTags()));
        }
        return xml.build();
    }

    private String associationXml(TransitGatewayVpcAttachment attachment, String routeTableId,
                                  String state, boolean includeRouteTableId) {
        XmlBuilder xml = new XmlBuilder();
        if (includeRouteTableId) {
            xml.elem("transitGatewayRouteTableId", routeTableId);
        }
        return xml.elem("transitGatewayAttachmentId", attachment.getTransitGatewayAttachmentId())
                .elem("resourceId", attachment.getVpcId())
                .elem("resourceType", "vpc")
                .elem("state", state)
                .build();
    }

    private String propagationXml(TransitGatewayRouteTablePropagation propagation, boolean includeRouteTableId) {
        XmlBuilder xml = new XmlBuilder()
                .elem("transitGatewayAttachmentId", propagation.getTransitGatewayAttachmentId())
                .elem("resourceId", propagation.getResourceId())
                .elem("resourceType", propagation.getResourceType());
        if (includeRouteTableId) {
            xml.elem("transitGatewayRouteTableId", propagation.getTransitGatewayRouteTableId());
        }
        return xml.elem("state", propagation.getState()).build();
    }

    private String routeXml(TransitGatewayRoute route) {
        XmlBuilder xml = new XmlBuilder()
                .elem("destinationCidrBlock", route.getDestinationCidrBlock());
        // A blackhole route, and a deleted one, carry no attachment at all.
        if (route.getTransitGatewayAttachmentId() != null) {
            xml.start("transitGatewayAttachments").start("item")
                    .elem("resourceId", route.getResourceId())
                    .elem("transitGatewayAttachmentId", route.getTransitGatewayAttachmentId())
                    .elem("resourceType", route.getResourceType())
                    .end("item").end("transitGatewayAttachments");
        }
        return xml.elem("type", route.getType())
                .elem("state", route.getState())
                .build();
    }

    private String vpcAttachmentXml(TransitGatewayVpcAttachment attachment, boolean includeSubnets,
                                    boolean includeTags) {
        XmlBuilder xml = new XmlBuilder()
                .elem("transitGatewayAttachmentId", attachment.getTransitGatewayAttachmentId())
                .elem("transitGatewayId", attachment.getTransitGatewayId())
                .elem("vpcId", attachment.getVpcId())
                .elem("vpcOwnerId", attachment.getVpcOwnerId())
                .elem("state", attachment.getState());
        if (includeSubnets) {
            xml.start("subnetIds");
            for (String subnetId : attachment.getSubnetIds()) {
                xml.elem("item", subnetId);
            }
            xml.end("subnetIds");
        }
        xml.elem("creationTime", attachment.getCreationTime())
                .start("options")
                .elem("dnsSupport", attachment.getOptions().getDnsSupport())
                .elem("securityGroupReferencingSupport", attachment.getOptions().getSecurityGroupReferencingSupport())
                .elem("ipv6Support", attachment.getOptions().getIpv6Support())
                .elem("applianceModeSupport", attachment.getOptions().getApplianceModeSupport())
                .end("options");
        if (includeTags) {
            xml.raw(tagSetXml(attachment.getTags()));
        }
        return xml.build();
    }

    private String transitGatewayXml(TransitGateway gateway) {
        return transitGatewayXml(gateway, true);
    }

    private String transitGatewayXml(TransitGateway gateway, boolean includeTags) {
        XmlBuilder xml = new XmlBuilder()
                .elem("transitGatewayId", gateway.getTransitGatewayId())
                .elem("transitGatewayArn", gateway.getTransitGatewayArn())
                .elem("state", gateway.getState())
                .elem("ownerId", gateway.getOwnerId())
                .elem("description", gateway.getDescription())
                .elem("creationTime", gateway.getCreationTime())
                .start("options")
                .elem("amazonSideAsn", String.valueOf(gateway.getOptions().getAmazonSideAsn()));
        List<String> cidrBlocks = gateway.getOptions().getTransitGatewayCidrBlocks();
        // AWS omits the member entirely rather than sending an empty set.
        if (cidrBlocks != null && !cidrBlocks.isEmpty()) {
            // A plain string list (ValueStringList), so each item carries the CIDR as its own text
            // rather than wrapping it in an element.
            xml.start("transitGatewayCidrBlocks");
            for (String cidr : cidrBlocks) {
                xml.elem("item", cidr);
            }
            xml.end("transitGatewayCidrBlocks");
        }
        xml.elem("autoAcceptSharedAttachments", gateway.getOptions().getAutoAcceptSharedAttachments())
                .elem("defaultRouteTableAssociation", gateway.getOptions().getDefaultRouteTableAssociation())
                .elem("associationDefaultRouteTableId", gateway.getOptions().getAssociationDefaultRouteTableId())
                .elem("defaultRouteTablePropagation", gateway.getOptions().getDefaultRouteTablePropagation())
                .elem("propagationDefaultRouteTableId", gateway.getOptions().getPropagationDefaultRouteTableId())
                .elem("vpnEcmpSupport", gateway.getOptions().getVpnEcmpSupport())
                .elem("dnsSupport", gateway.getOptions().getDnsSupport())
                .elem("securityGroupReferencingSupport", gateway.getOptions().getSecurityGroupReferencingSupport())
                .elem("multicastSupport", gateway.getOptions().getMulticastSupport())
                .end("options");
        if (includeTags) {
            xml.raw(tagSetXml(gateway.getTags()));
        }
        return xml.build();
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
        if ("true".equalsIgnoreCase(p.getFirst("AmazonProvidedIpv6CidrBlock"))) {
            VpcIpv6CidrBlockAssociation ipv6 = service.associateAmazonProvidedIpv6CidrBlock(region, vpcId);
            XmlBuilder ipv6Xml = new XmlBuilder()
                    .start("AssociateVpcCidrBlockResponse", AwsNamespaces.EC2)
                    .elem("requestId", UUID.randomUUID().toString())
                    .elem("vpcId", vpcId)
                    .start("ipv6CidrBlockAssociation")
                    .raw(vpcIpv6AssociationXml(ipv6))
                    .end("ipv6CidrBlockAssociation")
                    .end("AssociateVpcCidrBlockResponse");
            return xmlResponse(ipv6Xml.build());
        }
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

    private String vpcIpv6AssociationXml(VpcIpv6CidrBlockAssociation assoc) {
        return new XmlBuilder()
                .elem("associationId", assoc.getAssociationId())
                .elem("ipv6CidrBlock", assoc.getIpv6CidrBlock())
                .start("ipv6CidrBlockState").elem("state", assoc.getIpv6CidrBlockState()).end("ipv6CidrBlockState")
                .elem("ipv6Pool", assoc.getIpv6Pool())
                .elem("networkBorderGroup", assoc.getNetworkBorderGroup())
                .build();
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
        String azId = p.getFirst("AvailabilityZoneId");
        Subnet subnet = service.createSubnet(region, vpcId, cidrBlock, az, azId);
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

    private Response handleGetSecurityGroupsForVpc(MultivaluedMap<String, String> p, String region) {
        String vpcId = p.getFirst("VpcId");
        Map<String, List<String>> filters = getFilters(p);
        List<SecurityGroup> sgs = service.getSecurityGroupsForVpc(region, vpcId, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("GetSecurityGroupsForVpcResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("securityGroupForVpcSet");
        for (SecurityGroup sg : sgs) {
            xml.start("item")
                    .elem("groupId", sg.getGroupId())
                    .elem("groupName", sg.getGroupName())
                    .elem("description", sg.getDescription())
                    .elem("ownerId", sg.getOwnerId())
                    .elem("primaryVpcId", sg.getVpcId())
                    .raw(tagSetXml(sg.getTags()))
                    .end("item");
        }
        xml.end("securityGroupForVpcSet").end("GetSecurityGroupsForVpcResponse");
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
        // CDK's MachineImage.lookup is a synth-time context provider that queries
        // by a `name` wildcard and aborts `cdk deploy` if the response is empty.
        // When a wildcard name filter matches no seeded AMI, synthesize one that
        // satisfies it, so the lookup resolves — the exact id is a runtime detail.
        if (images.isEmpty() && imageIds.isEmpty() && filters.containsKey("name")) {
            // A filter's values are an OR, so the wildcard is whichever value carries one rather
            // than whichever comes first. Taking the first outright meant an exact value ahead of
            // a wildcard skipped synthesis and the lookup got nothing.
            String namePattern = filters.get("name").stream()
                    .filter(v -> v != null && (v.contains("*") || v.contains("?")))
                    .findFirst()
                    .orElse(null);
            if (namePattern != null) {
                Image synthesized = synthesizeLookupImage(namePattern, filters, owners);
                // Only hand it back if it satisfies everything that was asked for. Returning an
                // AMI that violates the request is worse than the empty result the lookup would
                // otherwise get, and the caller cannot tell the difference. Owner.N is carried
                // outside the filter set, so it has to be checked separately.
                if (service.imageMatchesFilters(synthesized, filters)
                        && service.imageMatchesOwners(synthesized, owners)) {
                    images = List.of(synthesized);
                }
            }
        }
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

    /** Stands in for a {@code *} when turning a lookup pattern into a concrete name. */
    private static final String SYNTH_WILDCARD_TOKEN = "20260101";

    /** Stands in for a {@code ?}, which matches exactly one character. */
    private static final String SYNTH_SINGLE_CHAR_TOKEN = "0";

    /** AWS's own account for the Amazon-owned AMIs, and the default when no owner is requested. */
    private static final String AMAZON_OWNER_ID = "137112412989";

    /** The account behind the {@code aws-marketplace} owner alias. */
    private static final String AWS_MARKETPLACE_OWNER_ID = "679593333241";

    /**
     * The owner the synthesized image should carry. {@code Owner.N} takes the aliases {@code self},
     * {@code amazon} and {@code aws-marketplace} as well as bare account ids, and an alias written
     * through verbatim produced an image owned by the literal string. AWS always reports an account
     * id in {@code imageOwnerId}, so each alias resolves to the account it names.
     */
    private String resolveSynthOwner(Map<String, List<String>> filters, List<String> owners) {
        // An owner-alias filter names an account just as much as Owner.N does, so it is consulted
        // before the default. Without that, filtering on owner-alias alone produced an image
        // carrying that alias beside the default Amazon account id, contradicting itself the same
        // way an unresolved alias did in the other direction.
        String requested = filters.getOrDefault("owner-id", owners == null ? List.of() : owners)
                .stream().findFirst()
                .orElseGet(() -> firstFilterValue(filters, "owner-alias", AMAZON_OWNER_ID));
        return switch (requested) {
            case "self" -> config.defaultAccountId();
            case "amazon" -> AMAZON_OWNER_ID;
            case "aws-marketplace" -> AWS_MARKETPLACE_OWNER_ID;
            default -> requested;
        };
    }

    /**
     * The alias that belongs to a resolved owner account, or null when the account has none. AWS
     * only sets imageOwnerAlias for its own published images, so defaulting it to amazon reported
     * ownership contradicting the owner id whenever the scope named anything else.
     */
    private static String aliasForOwner(String ownerId) {
        return switch (ownerId) {
            case AMAZON_OWNER_ID -> "amazon";
            case AWS_MARKETPLACE_OWNER_ID -> "aws-marketplace";
            default -> null;
        };
    }

    /** The requested value for a scalar filter, so the synthesized image satisfies it. */
    private static String firstFilterValue(Map<String, List<String>> filters, String name, String fallback) {
        return filters.getOrDefault(name, List.of()).stream()
                .filter(v -> v != null && !v.contains("*") && !v.contains("?"))
                .findFirst()
                .orElse(fallback);
    }

    /**
     * Builds an AMI that satisfies a lookup's name wildcard and its owner and architecture filters.
     * The id is a hash of the pattern, so repeated lookups resolve to the same image.
     */
    private Image synthesizeLookupImage(String namePattern, Map<String, List<String>> filters, List<String> owners) {
        Image img = new Image();
        // 17 hex chars after "ami-", deterministic from the pattern.
        String hash = String.format("%08x", namePattern.hashCode() & 0x7fffffff);
        String id17 = (hash + hash + hash).substring(0, 17);
        img.setImageId("ami-" + id17);
        // Substitute each wildcard rather than truncating at the first one, so an infix pattern
        // like ubuntu-*-20.04-* yields a name that still satisfies it. Truncating produced
        // "ubuntu-20260101", which does not. A ? takes exactly one character, so leaving it in
        // place would hand back a name the requesting filter no longer matches.
        img.setName(namePattern
                .replace("*", SYNTH_WILDCARD_TOKEN)
                .replace("?", SYNTH_SINGLE_CHAR_TOKEN));
        img.setState(firstFilterValue(filters, "state", "available"));
        String ownerId = resolveSynthOwner(filters, owners);
        img.setOwnerId(ownerId);
        img.setImageOwnerAlias(firstFilterValue(filters, "owner-alias", aliasForOwner(ownerId)));
        img.setPublic(true);
        img.setArchitecture(firstFilterValue(filters, "architecture", "x86_64"));
        img.setRootDeviceType(firstFilterValue(filters, "root-device-type", "ebs"));
        img.setRootDeviceName(firstFilterValue(filters, "root-device-name", "/dev/xvda"));
        img.setVirtualizationType(firstFilterValue(filters, "virtualization-type", "hvm"));
        img.setHypervisor(firstFilterValue(filters, "hypervisor", "xen"));
        img.setDescription("Synthesized AMI for MachineImage.lookup(" + namePattern + ")");
        img.setCreationDate("2026-01-01T00:00:00.000Z");
        return img;
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

    private Response handleDescribeVpnGateways() {
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeVpnGatewaysResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpnGatewaySet")
                .end("vpnGatewaySet")
                .end("DescribeVpnGatewaysResponse");
        return xmlResponse(xml.build());
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
        String destIpv6 = p.getFirst("DestinationIpv6CidrBlock");
        // A prefix list is a destination in its own right per the CreateRoute reference. The
        // prefix list itself is not modelled, but the id is stored and reported so the route
        // stays addressable by DeleteRoute and ReplaceRoute.
        String destPrefixList = p.getFirst("DestinationPrefixListId");
        String gwId = p.getFirst("GatewayId");
        String natGwId = p.getFirst("NatGatewayId");
        // The gateway itself is not modelled (CreateEgressOnlyInternetGateway is still
        // unimplemented), but the id the caller sent is stored and reported back so an IPv6
        // egress route is not silently rewritten into a targetless one.
        String eigwId = p.getFirst("EgressOnlyInternetGatewayId");
        String pcxId = p.getFirst("VpcPeeringConnectionId");
        service.createRoute(region, rtId, dest, destIpv6, destPrefixList, gwId, natGwId, eigwId, pcxId);
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
        String destIpv6 = p.getFirst("DestinationIpv6CidrBlock");
        String destPrefixList = p.getFirst("DestinationPrefixListId");
        String gwId = p.getFirst("GatewayId");
        String natGwId = p.getFirst("NatGatewayId");
        String pcxId = p.getFirst("VpcPeeringConnectionId");
        // Resetting a route to the local target is expressible: `local` is the gateway id the
        // route table's built-in route already carries, so it needs no new field on Route.
        if (Boolean.parseBoolean(p.getFirst("LocalTarget"))) {
            if (gwId != null || natGwId != null || pcxId != null) {
                throw new AwsException("InvalidParameterCombination",
                        "ReplaceRoute takes exactly one target.", 400);
            }
            gwId = LOCAL_GATEWAY_ID;
        }
        service.replaceRoute(region, rtId, dest, destIpv6, destPrefixList, gwId, natGwId, pcxId);
        return booleanResponse("ReplaceRoute");
    }

    private Response handleDeleteRoute(MultivaluedMap<String, String> p, String region) {
        String rtId = p.getFirst("RouteTableId");
        String dest = p.getFirst("DestinationCidrBlock");
        String destIpv6 = p.getFirst("DestinationIpv6CidrBlock");
        String destPrefixList = p.getFirst("DestinationPrefixListId");
        service.deleteRoute(region, rtId, dest, destIpv6, destPrefixList);
        return booleanResponse("DeleteRoute");
    }

    // ─── VPC Peering Connection handlers ────────────────────────────────────────

    private Response handleCreateVpcPeeringConnection(MultivaluedMap<String, String> p, String region) {
        String vpcId = p.getFirst("VpcId");
        String peerVpcId = p.getFirst("PeerVpcId");
        String peerOwnerId = p.getFirst("PeerOwnerId");
        String peerRegion = p.getFirst("PeerRegion");
        List<Tag> pcxTags = parseTagsForResource(p, "vpc-peering-connection");
        VpcPeeringConnection pcx = service.createVpcPeeringConnection(region, vpcId, peerVpcId, peerOwnerId,
                peerRegion, pcxTags);
        XmlBuilder xml = new XmlBuilder()
                .start("CreateVpcPeeringConnectionResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpcPeeringConnection").raw(vpcPeeringConnectionXml(pcx)).end("vpcPeeringConnection")
                .end("CreateVpcPeeringConnectionResponse");
        return xmlResponse(xml.build());
    }

    private Response handleAcceptVpcPeeringConnection(MultivaluedMap<String, String> p, String region) {
        VpcPeeringConnection pcx = service.acceptVpcPeeringConnection(region, p.getFirst("VpcPeeringConnectionId"));
        XmlBuilder xml = new XmlBuilder()
                .start("AcceptVpcPeeringConnectionResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpcPeeringConnection").raw(vpcPeeringConnectionXml(pcx)).end("vpcPeeringConnection")
                .end("AcceptVpcPeeringConnectionResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDescribeVpcPeeringConnections(MultivaluedMap<String, String> p, String region) {
        List<String> ids = getList(p, "VpcPeeringConnectionId");
        Map<String, List<String>> filters = getFilters(p);
        List<VpcPeeringConnection> connections = service.describeVpcPeeringConnections(region, ids, filters);
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeVpcPeeringConnectionsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("vpcPeeringConnectionSet");
        for (VpcPeeringConnection pcx : connections) {
            xml.start("item").raw(vpcPeeringConnectionXml(pcx)).end("item");
        }
        xml.end("vpcPeeringConnectionSet").end("DescribeVpcPeeringConnectionsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleModifyVpcPeeringConnectionOptions(MultivaluedMap<String, String> p, String region) {
        String pcxId = p.getFirst("VpcPeeringConnectionId");
        Boolean accepterDns = parseOptionalBoolean(
                p.getFirst("AccepterPeeringConnectionOptions.AllowDnsResolutionFromRemoteVpc"),
                "AccepterPeeringConnectionOptions.AllowDnsResolutionFromRemoteVpc");
        Boolean requesterDns = parseOptionalBoolean(
                p.getFirst("RequesterPeeringConnectionOptions.AllowDnsResolutionFromRemoteVpc"),
                "RequesterPeeringConnectionOptions.AllowDnsResolutionFromRemoteVpc");
        VpcPeeringConnection pcx = service.modifyVpcPeeringConnectionOptions(region, pcxId, accepterDns, requesterDns);
        XmlBuilder xml = new XmlBuilder()
                .start("ModifyVpcPeeringConnectionOptionsResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("accepterPeeringConnectionOptions")
                .elem("allowDnsResolutionFromRemoteVpc", String.valueOf(pcx.isAccepterAllowRemoteVpcDnsResolution()))
                .end("accepterPeeringConnectionOptions")
                .start("requesterPeeringConnectionOptions")
                .elem("allowDnsResolutionFromRemoteVpc", String.valueOf(pcx.isRequesterAllowRemoteVpcDnsResolution()))
                .end("requesterPeeringConnectionOptions")
                .end("ModifyVpcPeeringConnectionOptionsResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteVpcPeeringConnection(MultivaluedMap<String, String> p, String region) {
        service.deleteVpcPeeringConnection(region, p.getFirst("VpcPeeringConnectionId"));
        return booleanResponse("DeleteVpcPeeringConnection");
    }

    private String vpcPeeringConnectionXml(VpcPeeringConnection pcx) {
        XmlBuilder xml = new XmlBuilder()
                .elem("vpcPeeringConnectionId", pcx.getVpcPeeringConnectionId());
        xml.raw(vpcPeeringConnectionVpcInfoXml("requesterVpcInfo", pcx.getRequesterVpcInfo(),
                pcx.isRequesterAllowRemoteVpcDnsResolution()));
        xml.raw(vpcPeeringConnectionVpcInfoXml("accepterVpcInfo", pcx.getAccepterVpcInfo(),
                pcx.isAccepterAllowRemoteVpcDnsResolution()));
        if (pcx.getStatus() != null) {
            xml.start("status")
                    .elem("code", pcx.getStatus().getCode())
                    .elem("message", pcx.getStatus().getMessage())
                    .end("status");
        }
        xml.raw(tagSetXml(pcx.getTags()));
        return xml.build();
    }

    private String vpcPeeringConnectionVpcInfoXml(String elementName, VpcPeeringConnectionVpcInfo info,
            boolean allowRemoteVpcDnsResolution) {
        if (info == null) {
            return "";
        }
        XmlBuilder xml = new XmlBuilder()
                .start(elementName)
                .elem("vpcId", info.getVpcId())
                .elem("ownerId", info.getOwnerId())
                .elem("region", info.getRegion());
        if (info.getCidrBlock() != null) {
            xml.elem("cidrBlock", info.getCidrBlock());
        }
        xml.start("peeringOptions")
                .elem("allowDnsResolutionFromRemoteVpc", String.valueOf(allowRemoteVpcDnsResolution))
                .end("peeringOptions");
        xml.end(elementName);
        return xml.build();
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

    // ─── Capacity Reservation handlers ────────────────────────────────────────

    private Response handleCreateCapacityReservation(MultivaluedMap<String, String> p, String region) {
        CapacityReservation reservation = service.createCapacityReservation(
                region,
                p.getFirst("InstanceType"),
                p.getFirst("InstancePlatform"),
                p.getFirst("AvailabilityZone"),
                p.getFirst("AvailabilityZoneId"),
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
                .elem("availabilityZoneId", reservation.getAvailabilityZoneId())
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

    // Unlike intOrNull's silent skip of absent values, a present but unparseable timestamp is a
    // caller mistake and is rejected rather than coerced to null.
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
            xml.end("supportedArchitectures").end("processorInfo");
            Map<String, Object> networkInfo = (Map<String, Object>) t.get("networkInfo");
            xml.start("networkInfo")
                    .elem("encryptionInTransitSupported",
                            String.valueOf(networkInfo.get("encryptionInTransitSupported")))
                    .end("networkInfo")
                    .end("item");
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
        LaunchTemplate launchTemplate = service.createLaunchTemplate(
                region,
                p.getFirst("LaunchTemplateName"),
                parseLaunchTemplateData(p),
                parseTagsForResource(p, "launch-template"),
                p.getFirst("VersionDescription"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateLaunchTemplateResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("launchTemplate").raw(launchTemplateXml(launchTemplate)).end("launchTemplate")
                .end("CreateLaunchTemplateResponse");
        return xmlResponse(xml.build());
    }

    private Response handleCreateLaunchTemplateVersion(MultivaluedMap<String, String> p, String region) {
        LaunchTemplate launchTemplate = service.createLaunchTemplateVersion(
                region,
                p.getFirst("LaunchTemplateId"),
                p.getFirst("LaunchTemplateName"),
                p.getFirst("SourceVersion"),
                parseLaunchTemplateData(p),
                p.getFirst("VersionDescription"));
        XmlBuilder xml = new XmlBuilder()
                .start("CreateLaunchTemplateVersionResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("launchTemplateVersion").raw(launchTemplateVersionXml(launchTemplate)).end("launchTemplateVersion")
                .end("CreateLaunchTemplateVersionResponse");
        return xmlResponse(xml.build());
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
            xml.start("item").raw(networkInterfaceXml(ni)).end("item");
        }
        xml.end("networkInterfaceSet");
        if (result.nextToken() != null) {
            xml.elem("nextToken", result.nextToken());
        }
        xml.end("DescribeNetworkInterfacesResponse");
        return xmlResponse(xml.build());
    }

    /** Shared field emission for a {@code networkInterface} item, used by Describe and Create. */
    private String networkInterfaceXml(NetworkInterface ni) {
        XmlBuilder xml = new XmlBuilder()
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
        return xml.build();
    }

    private Response handleCreateNetworkInterface(MultivaluedMap<String, String> p, String region) {
        String subnetId = p.getFirst("SubnetId");
        String description = p.getFirst("Description");
        String privateIpAddress = p.getFirst("PrivateIpAddress");
        List<String> privateIpAddresses = new ArrayList<>();
        for (int i = 1; ; i++) {
            String addr = p.getFirst("PrivateIpAddresses." + i + ".PrivateIpAddress");
            if (addr == null) {
                break;
            }
            privateIpAddresses.add(addr);
        }
        List<String> securityGroupIds = getList(p, "SecurityGroupId");
        if (securityGroupIds.isEmpty()) {
            securityGroupIds = getList(p, "Groups.SecurityGroupId");
        }
        List<Tag> tagList = parseTagsForResource(p, "network-interface");

        NetworkInterface ni = service.createNetworkInterface(region, subnetId, description,
                privateIpAddress, privateIpAddresses, securityGroupIds, tagList);

        XmlBuilder xml = new XmlBuilder()
                .start("CreateNetworkInterfaceResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("networkInterface").raw(networkInterfaceXml(ni)).end("networkInterface")
                .end("CreateNetworkInterfaceResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDeleteNetworkInterface(MultivaluedMap<String, String> p, String region) {
        service.deleteNetworkInterface(region, p.getFirst("NetworkInterfaceId"));
        return booleanResponse("DeleteNetworkInterface");
    }

    private Response handleAttachNetworkInterface(MultivaluedMap<String, String> p, String region) {
        String networkInterfaceId = p.getFirst("NetworkInterfaceId");
        String instanceId = p.getFirst("InstanceId");
        int deviceIndex = parseIntParam(p, "DeviceIndex", 0);
        NetworkInterfaceAttachment attachment =
                service.attachNetworkInterface(region, networkInterfaceId, instanceId, deviceIndex);
        XmlBuilder xml = new XmlBuilder()
                .start("AttachNetworkInterfaceResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .elem("attachmentId", attachment.getAttachmentId())
                .end("AttachNetworkInterfaceResponse");
        return xmlResponse(xml.build());
    }

    private Response handleDetachNetworkInterface(MultivaluedMap<String, String> p, String region) {
        String attachmentId = p.getFirst("AttachmentId");
        boolean force = "true".equalsIgnoreCase(p.getFirst("Force"));
        service.detachNetworkInterface(region, attachmentId, force);
        return booleanResponse("DetachNetworkInterface");
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
                .elem("httpTokens", "optional")
                .elem("httpPutResponseHopLimit", "1")
                .elem("httpEndpoint", "enabled")
                .elem("httpProtocolIpv6", "disabled")
                .elem("instanceMetadataTags", "disabled")
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
                    .elem("deleteOnTermination", "true")
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
        xml.end("cidrBlockAssociationSet");
        xml.start("ipv6CidrBlockAssociationSet");
        for (VpcIpv6CidrBlockAssociation assoc : vpc.getIpv6CidrBlockAssociationSet()) {
            xml.start("item").raw(vpcIpv6AssociationXml(assoc)).end("item");
        }
        xml.end("ipv6CidrBlockAssociationSet")
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
                .elem("cidrIpv6", rule.getCidrIpv6())
                .elem("prefixListId", rule.getPrefixListId());
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
                    .elem("destinationIpv6CidrBlock", r.getDestinationIpv6CidrBlock())
                    .elem("destinationPrefixListId", r.getDestinationPrefixListId())
                    .elem("gatewayId", r.getGatewayId())
                    .elem("natGatewayId", r.getNatGatewayId())
                    .elem("egressOnlyInternetGatewayId", r.getEgressOnlyInternetGatewayId())
                    .elem("vpcPeeringConnectionId", r.getVpcPeeringConnectionId())
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
                    .elem("cidrBlock", e.getCidrBlock());
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
                .elem("connectivityType", natGateway.getConnectivityType());
        if (natGateway.getCreateTime() != null) {
            xml.elem("createTime", ISO_FMT.format(natGateway.getCreateTime()));
        }
        xml.start("natGatewayAddressSet");
        for (NatGatewayAddress address : natGateway.getNatGatewayAddresses()) {
            xml.start("item");
            if (address.getAllocationId() != null) {
                xml.elem("allocationId", address.getAllocationId());
            }
            if (address.getAssociationId() != null) {
                xml.elem("associationId", address.getAssociationId());
            }
            xml.elem("networkInterfaceId", address.getNetworkInterfaceId())
                    .elem("privateIp", address.getPrivateIp());
            if (address.getPublicIp() != null) {
                xml.elem("publicIp", address.getPublicIp());
            }
            xml.elem("isPrimary", String.valueOf(address.isPrimary()))
                    .elem("status", address.getStatus())
                    .end("item");
        }
        // A gateway restored from a store written before addresses were modelled has none, but
        // still knows its allocation id, report what it does know rather than an empty set.
        if (natGateway.getNatGatewayAddresses().isEmpty() && natGateway.getAllocationId() != null) {
            xml.start("item")
                    .elem("allocationId", natGateway.getAllocationId())
                    .end("item");
        }
        xml.end("natGatewayAddressSet");
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
        xml.elem("createdBy", launchTemplate.getCreatedBy())
                .elem("versionDescription", launchTemplate.getVersionDescription())
                .start("launchTemplateData")
                .raw(launchTemplateDataXml(launchTemplate.getData()))
                .end("launchTemplateData");
        return xml.build();
    }

    /**
     * Renders {@code ResponseLaunchTemplateData}. Element names are the {@code locationName}s the
     * EC2 service model declares, so what the provider reads back matches what it submitted.
     */
    private String launchTemplateDataXml(LaunchTemplateData data) {
        XmlBuilder xml = new XmlBuilder()
                .elem("imageId", data.getImageId())
                .elem("instanceType", data.getInstanceType())
                .elem("kernelId", data.getKernelId())
                .elem("ramDiskId", data.getRamDiskId())
                .elem("keyName", data.getKeyName())
                .elem("userData", data.getEncodedUserData())
                .elem("ebsOptimized", str(data.getEbsOptimized()))
                .elem("disableApiTermination", str(data.getDisableApiTermination()))
                .elem("disableApiStop", str(data.getDisableApiStop()))
                .elem("instanceInitiatedShutdownBehavior", data.getInstanceInitiatedShutdownBehavior());

        LaunchTemplateData.IamInstanceProfile profile = data.getIamInstanceProfile();
        if (profile != null && (profile.getArn() != null || profile.getName() != null)) {
            xml.start("iamInstanceProfile")
                    .elem("arn", profile.getArn())
                    .elem("name", profile.getName())
                    .end("iamInstanceProfile");
        }

        if (!data.getBlockDeviceMappings().isEmpty()) {
            xml.start("blockDeviceMappingSet");
            for (LaunchTemplateData.BlockDeviceMapping mapping : data.getBlockDeviceMappings()) {
                xml.start("item")
                        .elem("deviceName", mapping.getDeviceName())
                        .elem("virtualName", mapping.getVirtualName())
                        .elem("noDevice", mapping.getNoDevice());
                LaunchTemplateData.Ebs ebs = mapping.getEbs();
                if (ebs != null) {
                    xml.start("ebs")
                            .elem("encrypted", str(ebs.getEncrypted()))
                            .elem("deleteOnTermination", str(ebs.getDeleteOnTermination()))
                            .elem("iops", str(ebs.getIops()))
                            .elem("kmsKeyId", ebs.getKmsKeyId())
                            .elem("snapshotId", ebs.getSnapshotId())
                            .elem("volumeSize", str(ebs.getVolumeSize()))
                            .elem("volumeType", ebs.getVolumeType())
                            .elem("throughput", str(ebs.getThroughput()))
                            .end("ebs");
                }
                xml.end("item");
            }
            xml.end("blockDeviceMappingSet");
        }

        if (!data.getNetworkInterfaces().isEmpty()) {
            xml.start("networkInterfaceSet");
            for (LaunchTemplateData.NetworkInterface networkInterface : data.getNetworkInterfaces()) {
                xml.start("item")
                        .elem("associatePublicIpAddress", str(networkInterface.getAssociatePublicIpAddress()))
                        .elem("associateCarrierIpAddress", str(networkInterface.getAssociateCarrierIpAddress()))
                        .elem("deleteOnTermination", str(networkInterface.getDeleteOnTermination()))
                        .elem("description", networkInterface.getDescription())
                        .elem("deviceIndex", str(networkInterface.getDeviceIndex()))
                        .elem("interfaceType", networkInterface.getInterfaceType())
                        .elem("ipv6AddressCount", str(networkInterface.getIpv6AddressCount()))
                        .elem("networkInterfaceId", networkInterface.getNetworkInterfaceId())
                        .elem("privateIpAddress", networkInterface.getPrivateIpAddress())
                        .elem("secondaryPrivateIpAddressCount", str(networkInterface.getSecondaryPrivateIpAddressCount()))
                        .elem("subnetId", networkInterface.getSubnetId())
                        .elem("networkCardIndex", str(networkInterface.getNetworkCardIndex()));
                if (!networkInterface.getGroups().isEmpty()) {
                    xml.start("groupSet");
                    for (String group : networkInterface.getGroups()) {
                        xml.elem("item", group);
                    }
                    xml.end("groupSet");
                }
                xml.end("item");
            }
            xml.end("networkInterfaceSet");
        }

        LaunchTemplateData.MetadataOptions metadataOptions = data.getMetadataOptions();
        if (metadataOptions != null) {
            xml.start("metadataOptions")
                    .elem("state", metadataOptions.getState() != null ? metadataOptions.getState() : "applied")
                    .elem("httpTokens", metadataOptions.getHttpTokens())
                    .elem("httpPutResponseHopLimit", str(metadataOptions.getHttpPutResponseHopLimit()))
                    .elem("httpEndpoint", metadataOptions.getHttpEndpoint())
                    .elem("httpProtocolIpv6", metadataOptions.getHttpProtocolIpv6())
                    .elem("instanceMetadataTags", metadataOptions.getInstanceMetadataTags())
                    .end("metadataOptions");
        }

        LaunchTemplateData.Monitoring monitoring = data.getMonitoring();
        if (monitoring != null) {
            xml.start("monitoring").elem("enabled", str(monitoring.getEnabled())).end("monitoring");
        }

        LaunchTemplateData.Placement placement = data.getPlacement();
        if (placement != null) {
            xml.start("placement")
                    .elem("availabilityZone", placement.getAvailabilityZone())
                    .elem("availabilityZoneId", placement.getAvailabilityZoneId())
                    .elem("affinity", placement.getAffinity())
                    .elem("groupName", placement.getGroupName())
                    .elem("groupId", placement.getGroupId())
                    .elem("hostId", placement.getHostId())
                    .elem("tenancy", placement.getTenancy())
                    .elem("spreadDomain", placement.getSpreadDomain())
                    .elem("hostResourceGroupArn", placement.getHostResourceGroupArn())
                    .elem("partitionNumber", str(placement.getPartitionNumber()))
                    .end("placement");
        }

        LaunchTemplateData.CpuOptions cpuOptions = data.getCpuOptions();
        if (cpuOptions != null) {
            xml.start("cpuOptions")
                    .elem("coreCount", str(cpuOptions.getCoreCount()))
                    .elem("threadsPerCore", str(cpuOptions.getThreadsPerCore()))
                    .elem("amdSevSnp", cpuOptions.getAmdSevSnp())
                    .end("cpuOptions");
        }

        LaunchTemplateData.CreditSpecification creditSpecification = data.getCreditSpecification();
        if (creditSpecification != null) {
            xml.start("creditSpecification")
                    .elem("cpuCredits", creditSpecification.getCpuCredits())
                    .end("creditSpecification");
        }

        LaunchTemplateData.EnclaveOptions enclaveOptions = data.getEnclaveOptions();
        if (enclaveOptions != null) {
            xml.start("enclaveOptions").elem("enabled", str(enclaveOptions.getEnabled())).end("enclaveOptions");
        }

        LaunchTemplateData.HibernationOptions hibernationOptions = data.getHibernationOptions();
        if (hibernationOptions != null) {
            xml.start("hibernationOptions")
                    .elem("configured", str(hibernationOptions.getConfigured()))
                    .end("hibernationOptions");
        }

        LaunchTemplateData.MaintenanceOptions maintenanceOptions = data.getMaintenanceOptions();
        if (maintenanceOptions != null) {
            xml.start("maintenanceOptions")
                    .elem("autoRecovery", maintenanceOptions.getAutoRecovery())
                    .end("maintenanceOptions");
        }

        LaunchTemplateData.PrivateDnsNameOptions privateDnsNameOptions = data.getPrivateDnsNameOptions();
        if (privateDnsNameOptions != null) {
            xml.start("privateDnsNameOptions")
                    .elem("hostnameType", privateDnsNameOptions.getHostnameType())
                    .elem("enableResourceNameDnsARecord", str(privateDnsNameOptions.getEnableResourceNameDnsARecord()))
                    .elem("enableResourceNameDnsAAAARecord", str(privateDnsNameOptions.getEnableResourceNameDnsAAAARecord()))
                    .end("privateDnsNameOptions");
        }

        LaunchTemplateData.CapacityReservationSpecification capacityReservation =
                data.getCapacityReservationSpecification();
        if (capacityReservation != null) {
            xml.start("capacityReservationSpecification")
                    .elem("capacityReservationPreference", capacityReservation.getCapacityReservationPreference());
            LaunchTemplateData.CapacityReservationTarget target = capacityReservation.getCapacityReservationTarget();
            if (target != null) {
                xml.start("capacityReservationTarget")
                        .elem("capacityReservationId", target.getCapacityReservationId())
                        .elem("capacityReservationResourceGroupArn", target.getCapacityReservationResourceGroupArn())
                        .end("capacityReservationTarget");
            }
            xml.end("capacityReservationSpecification");
        }

        if (!data.getSecurityGroupIds().isEmpty()) {
            xml.start("securityGroupIdSet");
            for (String securityGroupId : data.getSecurityGroupIds()) {
                xml.elem("item", securityGroupId);
            }
            xml.end("securityGroupIdSet");
        }

        if (!data.getTagSpecifications().isEmpty()) {
            xml.start("tagSpecificationSet");
            for (LaunchTemplateData.TagSpecification spec : data.getTagSpecifications()) {
                xml.start("item")
                        .elem("resourceType", spec.getResourceType())
                        .raw(tagSetXml(spec.getTags()))
                        .end("item");
            }
            xml.end("tagSpecificationSet");
        }
        return xml.build();
    }

    /**
     * Parses {@code RequestLaunchTemplateData} from the EC2 query wire format. Parameter names are
     * the ones botocore's EC2 serializer emits — list members carry the model's singular
     * {@code locationName}, which is why the prefixes are {@code BlockDeviceMapping.N} rather than
     * {@code BlockDeviceMappings.N}.
     */
    private LaunchTemplateData parseLaunchTemplateData(MultivaluedMap<String, String> p) {
        String prefix = "LaunchTemplateData";
        LaunchTemplateData data = new LaunchTemplateData();
        data.setImageId(p.getFirst(prefix + ".ImageId"));
        data.setInstanceType(p.getFirst(prefix + ".InstanceType"));
        data.setKeyName(p.getFirst(prefix + ".KeyName"));
        data.setKernelId(p.getFirst(prefix + ".KernelId"));
        data.setRamDiskId(p.getFirst(prefix + ".RamDiskId"));
        data.setInstanceInitiatedShutdownBehavior(p.getFirst(prefix + ".InstanceInitiatedShutdownBehavior"));
        data.setEbsOptimized(boolParam(p, prefix + ".EbsOptimized"));
        data.setDisableApiTermination(boolParam(p, prefix + ".DisableApiTermination"));
        data.setDisableApiStop(boolParam(p, prefix + ".DisableApiStop"));

        String encodedUserData = p.getFirst(prefix + ".UserData");
        data.setEncodedUserData(encodedUserData);
        data.setUserData(decodeUserData(encodedUserData));

        String profileArn = p.getFirst(prefix + ".IamInstanceProfile.Arn");
        String profileName = p.getFirst(prefix + ".IamInstanceProfile.Name");
        if (isSet(profileArn) || isSet(profileName)) {
            data.setIamInstanceProfile(new LaunchTemplateData.IamInstanceProfile(
                    isSet(profileArn) ? profileArn : null,
                    isSet(profileName) ? profileName : null));
        }

        // SecurityGroups (the by-name form, as opposed to SecurityGroupId) is deliberately not
        // parsed here. Resolving names to IDs would need real lookup machinery — scanning the
        // security-group store, handling "not found", and handling ambiguity across VPCs — that
        // RunInstances itself doesn't have today (it only accepts SecurityGroupId); see
        // docs/services/ec2.md's launch-template section.
        data.setSecurityGroupIds(getList(p, prefix + ".SecurityGroupId"));
        data.setBlockDeviceMappings(parseLaunchTemplateBlockDeviceMappings(p, prefix));
        data.setNetworkInterfaces(parseLaunchTemplateNetworkInterfaces(p, prefix));
        data.setTagSpecifications(parseLaunchTemplateTagSpecifications(p, prefix));

        if (anyParamStartsWith(p, prefix + ".MetadataOptions.")) {
            LaunchTemplateData.MetadataOptions options = new LaunchTemplateData.MetadataOptions();
            options.setHttpTokens(p.getFirst(prefix + ".MetadataOptions.HttpTokens"));
            options.setHttpPutResponseHopLimit(intParam(p, prefix + ".MetadataOptions.HttpPutResponseHopLimit"));
            options.setHttpEndpoint(p.getFirst(prefix + ".MetadataOptions.HttpEndpoint"));
            options.setHttpProtocolIpv6(p.getFirst(prefix + ".MetadataOptions.HttpProtocolIpv6"));
            options.setInstanceMetadataTags(p.getFirst(prefix + ".MetadataOptions.InstanceMetadataTags"));
            data.setMetadataOptions(options);
        }

        Boolean monitoringEnabled = boolParam(p, prefix + ".Monitoring.Enabled");
        if (monitoringEnabled != null) {
            LaunchTemplateData.Monitoring monitoring = new LaunchTemplateData.Monitoring();
            monitoring.setEnabled(monitoringEnabled);
            data.setMonitoring(monitoring);
        }

        if (anyParamStartsWith(p, prefix + ".Placement.")) {
            LaunchTemplateData.Placement placement = new LaunchTemplateData.Placement();
            placement.setAvailabilityZone(p.getFirst(prefix + ".Placement.AvailabilityZone"));
            placement.setAvailabilityZoneId(p.getFirst(prefix + ".Placement.AvailabilityZoneId"));
            placement.setAffinity(p.getFirst(prefix + ".Placement.Affinity"));
            placement.setGroupName(p.getFirst(prefix + ".Placement.GroupName"));
            placement.setGroupId(p.getFirst(prefix + ".Placement.GroupId"));
            placement.setHostId(p.getFirst(prefix + ".Placement.HostId"));
            placement.setTenancy(p.getFirst(prefix + ".Placement.Tenancy"));
            placement.setSpreadDomain(p.getFirst(prefix + ".Placement.SpreadDomain"));
            placement.setHostResourceGroupArn(p.getFirst(prefix + ".Placement.HostResourceGroupArn"));
            placement.setPartitionNumber(intParam(p, prefix + ".Placement.PartitionNumber"));
            data.setPlacement(placement);
        }

        if (anyParamStartsWith(p, prefix + ".CpuOptions.")) {
            LaunchTemplateData.CpuOptions cpuOptions = new LaunchTemplateData.CpuOptions();
            cpuOptions.setCoreCount(intParam(p, prefix + ".CpuOptions.CoreCount"));
            cpuOptions.setThreadsPerCore(intParam(p, prefix + ".CpuOptions.ThreadsPerCore"));
            cpuOptions.setAmdSevSnp(p.getFirst(prefix + ".CpuOptions.AmdSevSnp"));
            data.setCpuOptions(cpuOptions);
        }

        String cpuCredits = p.getFirst(prefix + ".CreditSpecification.CpuCredits");
        if (isSet(cpuCredits)) {
            LaunchTemplateData.CreditSpecification creditSpecification = new LaunchTemplateData.CreditSpecification();
            creditSpecification.setCpuCredits(cpuCredits);
            data.setCreditSpecification(creditSpecification);
        }

        Boolean enclaveEnabled = boolParam(p, prefix + ".EnclaveOptions.Enabled");
        if (enclaveEnabled != null) {
            LaunchTemplateData.EnclaveOptions enclaveOptions = new LaunchTemplateData.EnclaveOptions();
            enclaveOptions.setEnabled(enclaveEnabled);
            data.setEnclaveOptions(enclaveOptions);
        }

        Boolean hibernationConfigured = boolParam(p, prefix + ".HibernationOptions.Configured");
        if (hibernationConfigured != null) {
            LaunchTemplateData.HibernationOptions hibernationOptions = new LaunchTemplateData.HibernationOptions();
            hibernationOptions.setConfigured(hibernationConfigured);
            data.setHibernationOptions(hibernationOptions);
        }

        String autoRecovery = p.getFirst(prefix + ".MaintenanceOptions.AutoRecovery");
        if (isSet(autoRecovery)) {
            LaunchTemplateData.MaintenanceOptions maintenanceOptions = new LaunchTemplateData.MaintenanceOptions();
            maintenanceOptions.setAutoRecovery(autoRecovery);
            data.setMaintenanceOptions(maintenanceOptions);
        }

        if (anyParamStartsWith(p, prefix + ".PrivateDnsNameOptions.")) {
            LaunchTemplateData.PrivateDnsNameOptions options = new LaunchTemplateData.PrivateDnsNameOptions();
            options.setHostnameType(p.getFirst(prefix + ".PrivateDnsNameOptions.HostnameType"));
            options.setEnableResourceNameDnsARecord(
                    boolParam(p, prefix + ".PrivateDnsNameOptions.EnableResourceNameDnsARecord"));
            options.setEnableResourceNameDnsAAAARecord(
                    boolParam(p, prefix + ".PrivateDnsNameOptions.EnableResourceNameDnsAAAARecord"));
            data.setPrivateDnsNameOptions(options);
        }

        if (anyParamStartsWith(p, prefix + ".CapacityReservationSpecification.")) {
            LaunchTemplateData.CapacityReservationSpecification spec =
                    new LaunchTemplateData.CapacityReservationSpecification();
            spec.setCapacityReservationPreference(
                    p.getFirst(prefix + ".CapacityReservationSpecification.CapacityReservationPreference"));
            String targetPrefix = prefix + ".CapacityReservationSpecification.CapacityReservationTarget.";
            if (anyParamStartsWith(p, targetPrefix)) {
                LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
                target.setCapacityReservationId(p.getFirst(targetPrefix + "CapacityReservationId"));
                target.setCapacityReservationResourceGroupArn(
                        p.getFirst(targetPrefix + "CapacityReservationResourceGroupArn"));
                spec.setCapacityReservationTarget(target);
            }
            data.setCapacityReservationSpecification(spec);
        }
        return data;
    }

    private List<LaunchTemplateData.BlockDeviceMapping> parseLaunchTemplateBlockDeviceMappings(
            MultivaluedMap<String, String> p, String prefix) {
        List<LaunchTemplateData.BlockDeviceMapping> mappings = new ArrayList<>();
        for (int i = 1; ; i++) {
            String base = prefix + ".BlockDeviceMapping." + i;
            if (!anyParamStartsWith(p, base + ".")) {
                break;
            }
            LaunchTemplateData.BlockDeviceMapping mapping = new LaunchTemplateData.BlockDeviceMapping();
            mapping.setDeviceName(p.getFirst(base + ".DeviceName"));
            mapping.setVirtualName(p.getFirst(base + ".VirtualName"));
            mapping.setNoDevice(p.getFirst(base + ".NoDevice"));
            if (anyParamStartsWith(p, base + ".Ebs.")) {
                LaunchTemplateData.Ebs ebs = new LaunchTemplateData.Ebs();
                ebs.setEncrypted(boolParam(p, base + ".Ebs.Encrypted"));
                ebs.setDeleteOnTermination(boolParam(p, base + ".Ebs.DeleteOnTermination"));
                ebs.setIops(intParam(p, base + ".Ebs.Iops"));
                ebs.setKmsKeyId(p.getFirst(base + ".Ebs.KmsKeyId"));
                ebs.setSnapshotId(p.getFirst(base + ".Ebs.SnapshotId"));
                ebs.setVolumeSize(intParam(p, base + ".Ebs.VolumeSize"));
                ebs.setVolumeType(p.getFirst(base + ".Ebs.VolumeType"));
                ebs.setThroughput(intParam(p, base + ".Ebs.Throughput"));
                mapping.setEbs(ebs);
            }
            mappings.add(mapping);
        }
        return mappings;
    }

    private List<LaunchTemplateData.NetworkInterface> parseLaunchTemplateNetworkInterfaces(
            MultivaluedMap<String, String> p, String prefix) {
        List<LaunchTemplateData.NetworkInterface> interfaces = new ArrayList<>();
        for (int i = 1; ; i++) {
            String base = prefix + ".NetworkInterface." + i;
            if (!anyParamStartsWith(p, base + ".")) {
                break;
            }
            LaunchTemplateData.NetworkInterface networkInterface = new LaunchTemplateData.NetworkInterface();
            networkInterface.setAssociatePublicIpAddress(boolParam(p, base + ".AssociatePublicIpAddress"));
            networkInterface.setAssociateCarrierIpAddress(boolParam(p, base + ".AssociateCarrierIpAddress"));
            networkInterface.setDeleteOnTermination(boolParam(p, base + ".DeleteOnTermination"));
            networkInterface.setDescription(p.getFirst(base + ".Description"));
            networkInterface.setDeviceIndex(intParam(p, base + ".DeviceIndex"));
            networkInterface.setInterfaceType(p.getFirst(base + ".InterfaceType"));
            networkInterface.setIpv6AddressCount(intParam(p, base + ".Ipv6AddressCount"));
            networkInterface.setNetworkInterfaceId(p.getFirst(base + ".NetworkInterfaceId"));
            networkInterface.setPrivateIpAddress(p.getFirst(base + ".PrivateIpAddress"));
            networkInterface.setSecondaryPrivateIpAddressCount(intParam(p, base + ".SecondaryPrivateIpAddressCount"));
            networkInterface.setSubnetId(p.getFirst(base + ".SubnetId"));
            networkInterface.setNetworkCardIndex(intParam(p, base + ".NetworkCardIndex"));
            networkInterface.setGroups(getList(p, base + ".SecurityGroupId", base + ".Groups", base + ".GroupId"));
            interfaces.add(networkInterface);
        }
        return interfaces;
    }

    private List<LaunchTemplateData.TagSpecification> parseLaunchTemplateTagSpecifications(
            MultivaluedMap<String, String> p, String prefix) {
        List<LaunchTemplateData.TagSpecification> specs = new ArrayList<>();
        for (int i = 1; ; i++) {
            String base = prefix + ".TagSpecification." + i;
            String resourceType = p.getFirst(base + ".ResourceType");
            if (resourceType == null) {
                break;
            }
            List<Tag> tagList = new ArrayList<>();
            for (int j = 1; ; j++) {
                String key = p.getFirst(base + ".Tag." + j + ".Key");
                if (key == null) {
                    break;
                }
                tagList.add(new Tag(key, p.getFirst(base + ".Tag." + j + ".Value")));
            }
            specs.add(new LaunchTemplateData.TagSpecification(resourceType, tagList));
        }
        return specs;
    }

    private boolean anyParamStartsWith(MultivaluedMap<String, String> p, String prefix) {
        for (String name : p.keySet()) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private Boolean boolParam(MultivaluedMap<String, String> p, String name) {
        String value = p.getFirst(name);
        return isSet(value) ? Boolean.valueOf(Boolean.parseBoolean(value)) : null;
    }

    private Integer intParam(MultivaluedMap<String, String> p, String name) {
        String value = p.getFirst(name);
        if (!isSet(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", name + " is not a valid integer.", 400);
        }
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : null;
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
                .elem("privateDnsEnabled", String.valueOf(endpoint.isPrivateDnsEnabled()));
        if (endpoint.getCreationTimestamp() != null) {
            xml.elem("creationTimestamp", ISO_FMT.format(endpoint.getCreationTimestamp()));
        }
        if (endpoint.getPolicyDocument() != null) {
            xml.elem("policyDocument", endpoint.getPolicyDocument());
        }
        xml.start("routeTableIdSet");
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
        xml.end("groupSet");
        xml.raw(tagSetXml(endpoint.getTags()));
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
            xml.end("groups").start("prefixListIds");
            for (PrefixListId prefixList : perm.getPrefixListIds()) {
                xml.start("item")
                        .elem("prefixListId", prefixList.getPrefixListId())
                        .elem("description", prefixList.getDescription())
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
