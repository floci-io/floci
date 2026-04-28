package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.ec2.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@ApplicationScoped
public class Ec2QueryHandler {

    private static final Logger LOG = Logger.getLogger(Ec2QueryHandler.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final Ec2Service service;

    @Inject
    public Ec2QueryHandler(Ec2Service service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.debugv("EC2 action: {0}", action);
        try {
            return switch (action) {
                // Instances
                case "RunInstances" -> handleRunInstances(params, region);
                case "DescribeInstances" -> handleDescribeInstances(params, region);
                case "TerminateInstances" -> handleTerminateInstances(params, region);
                case "StartInstances" -> handleStartInstances(params, region);
                case "StopInstances" -> handleStopInstances(params, region);
                case "RebootInstances" -> handleRebootInstances(params, region);
                case "DescribeInstanceStatus" -> handleDescribeInstanceStatus(params, region);
                case "DescribeInstanceAttribute" -> handleDescribeInstanceAttribute(params, region);
                case "ModifyInstanceAttribute" -> handleModifyInstanceAttribute(params, region);
                // VPCs
                case "CreateVpc" -> handleCreateVpc(params, region);
                case "DescribeVpcs" -> handleDescribeVpcs(params, region);
                case "DeleteVpc" -> handleDeleteVpc(params, region);
                case "ModifyVpcAttribute" -> handleModifyVpcAttribute(params, region);
                case "DescribeVpcAttribute" -> handleDescribeVpcAttribute(params, region);
                case "DescribeVpcEndpointServices" -> handleDescribeVpcEndpointServices(params, region);
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
                case "UpdateSecurityGroupRuleDescriptionsIngress" -> handleUpdateSgRuleDescriptionsIngress(params, region);
                case "UpdateSecurityGroupRuleDescriptionsEgress" -> handleUpdateSgRuleDescriptionsEgress(params, region);
                // Key Pairs
                case "CreateKeyPair" -> handleCreateKeyPair(params, region);
                case "DescribeKeyPairs" -> handleDescribeKeyPairs(params, region);
                case "DeleteKeyPair" -> handleDeleteKeyPair(params, region);
                case "ImportKeyPair" -> handleImportKeyPair(params, region);
                // AMIs
                case "DescribeImages" -> handleDescribeImages(params, region);
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
                // Route Tables
                case "CreateRouteTable" -> handleCreateRouteTable(params, region);
                case "DescribeRouteTables" -> handleDescribeRouteTables(params, region);
                case "DeleteRouteTable" -> handleDeleteRouteTable(params, region);
                case "AssociateRouteTable" -> handleAssociateRouteTable(params, region);
                case "DisassociateRouteTable" -> handleDisassociateRouteTable(params, region);
                case "CreateRoute" -> handleCreateRoute(params, region);
                case "DeleteRoute" -> handleDeleteRoute(params, region);
                // Elastic IPs
                case "AllocateAddress" -> handleAllocateAddress(params, region);
                case "AssociateAddress" -> handleAssociateAddress(params, region);
                case "DisassociateAddress" -> handleDisassociateAddress(params, region);
                case "ReleaseAddress" -> handleReleaseAddress(params, region);
                case "DescribeAddresses" -> handleDescribeAddresses(params, region);
                // Regions & Account
                case "DescribeAvailabilityZones" -> handleDescribeAvailabilityZones(params, region);
                case "DescribeRegions" -> handleDescribeRegions(params, region);
                case "DescribeAccountAttributes" -> handleDescribeAccountAttributes(params, region);
                // Instance Types
                case "DescribeInstanceTypes" -> handleDescribeInstanceTypes(params, region);
                // Volumes
                case "CreateVolume" -> handleCreateVolume(params, region);
                case "DescribeVolumes" -> handleDescribeVolumes(params, region);
                case "DeleteVolume" -> handleDeleteVolume(params, region);
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
            perms.add(perm);
        }
        return perms;
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
        String clientToken = p.getFirst("ClientToken");
        List<String> sgIds = getList(p, "SecurityGroupId");

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

        Reservation res = service.runInstances(region, imageId, instanceType, minCount, maxCount,
                keyName, sgIds, subnetId, clientToken, instanceTags);

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
        return booleanResponse("ModifyInstanceAttribute");
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

    private Response handleDescribeVpcEndpointServices(MultivaluedMap<String, String> p, String region) {
        XmlBuilder xml = new XmlBuilder()
                .start("DescribeVpcEndpointServicesResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("serviceNameSet").end("serviceNameSet")
                .start("serviceDetailSet").end("serviceDetailSet")
                .end("DescribeVpcEndpointServicesResponse");
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
        String val = p.getFirst("MapPublicIpOnLaunch.Value");
        if (val != null) {
            service.modifySubnetAttribute(region, subnetId, "mapPublicIpOnLaunch", val);
        }
        return booleanResponse("ModifySubnetAttribute");
    }

    // ─── Security Group handlers ───────────────────────────────────────────────

    private Response handleCreateSecurityGroup(MultivaluedMap<String, String> p, String region) {
        String groupName = p.getFirst("GroupName");
        String description = p.getFirst("GroupDescription");
        String vpcId = p.getFirst("VpcId");
        SecurityGroup sg = service.createSecurityGroup(region, groupName, description, vpcId);
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
        String groupId = p.getFirst("Filter.1.Value.1");
        List<String> ruleIds = getList(p, "SecurityGroupRuleId");
        List<SecurityGroupRule> rules = service.describeSecurityGroupRules(region,
                groupId != null ? groupId : "", ruleIds);
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
        String publicKeyMaterial = p.getFirst("PublicKeyMaterial");
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
        List<Image> images = service.describeImages(region, imageIds, owners);
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
                    .end("item");
        }
        xml.end("imagesSet").end("DescribeImagesResponse");
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
        XmlBuilder xml = new XmlBuilder()
                .start("CreateRouteTableResponse", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString())
                .start("routeTable").raw(routeTableXml(rt)).end("routeTable")
                .end("CreateRouteTableResponse");
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
        service.createRoute(region, rtId, dest, gwId);
        return booleanResponse("CreateRoute");
    }

    private Response handleDeleteRoute(MultivaluedMap<String, String> p, String region) {
        String rtId = p.getFirst("RouteTableId");
        String dest = p.getFirst("DestinationCidrBlock");
        service.deleteRoute(region, rtId, dest);
        return booleanResponse("DeleteRoute");
    }

    // ─── Elastic IP handlers ──────────────────────────────────────────────────

    private Response handleAllocateAddress(MultivaluedMap<String, String> p, String region) {
        Address addr = service.allocateAddress(region);
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
                    .start("supportedArchitectures");
            for (String arch : (List<String>) t.get("supportedArchitectures")) {
                xml.start("item").elem("item", arch).end("item");
            }
            xml.end("supportedArchitectures").end("item");
        }
        xml.end("instanceTypeSet").end("DescribeInstanceTypesResponse");
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
            xml.end("groupSet").end("item");
        }
        xml.end("networkInterfaceSet");
        xml.raw(tagSetXml(inst.getTags()));
        return xml.build();
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
                .elem("description", rule.getDescription());
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
                encrypted, iops, snapshotId, volumeTags);
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
                xml.start("item").elem("cidrIpv6", r.getCidrIpv6()).end("item");
            }
            xml.end("ipv6Ranges")
                    .start("groups");
            for (UserIdGroupPair g : perm.getUserIdGroupPairs()) {
                xml.start("item")
                        .elem("userId", g.getUserId())
                        .elem("groupId", g.getGroupId())
                        .elem("groupName", g.getGroupName())
                        .end("item");
            }
            xml.end("groups").end("item");
        }
        xml.end(wrapperTag);
        return xml.build();
    }
}
