package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@ApplicationScoped
public class Ec2Service {

    private static final Logger LOG = Logger.getLogger(Ec2Service.class);

    private final String accountId;

    // region::id → resource
    private final Map<String, Vpc> vpcs = new ConcurrentHashMap<>();
    private final Map<String, Subnet> subnets = new ConcurrentHashMap<>();
    private final Map<String, SecurityGroup> securityGroups = new ConcurrentHashMap<>();
    private final Map<String, SecurityGroupRule> securityGroupRules = new ConcurrentHashMap<>();
    private final Map<String, InternetGateway> internetGateways = new ConcurrentHashMap<>();
    private final Map<String, RouteTable> routeTables = new ConcurrentHashMap<>();
    private final Map<String, KeyPair> keyPairs = new ConcurrentHashMap<>();
    private final Map<String, Address> addresses = new ConcurrentHashMap<>();
    private final Map<String, Instance> instances = new ConcurrentHashMap<>();
    private final Map<String, Volume> volumes = new ConcurrentHashMap<>();
    // resourceId → List<Tag>
    private final Map<String, List<Tag>> tags = new ConcurrentHashMap<>();
    private final Set<String> seededRegions = ConcurrentHashMap.newKeySet();
    // subnetId → counter for IP assignment
    private final Map<String, AtomicInteger> subnetIpCounters = new ConcurrentHashMap<>();

    @Inject
    public Ec2Service(EmulatorConfig config) {
        this.accountId = config.defaultAccountId();
    }

    // ─── Default resource seeding ──────────────────────────────────────────────

    void ensureDefaultResources(String region) {
        if (!seededRegions.add(region)) {
            return;
        }
        LOG.debugv("Seeding default EC2 resources for region {0}", region);

        // Default VPC
        String vpcId = "vpc-default";
        Vpc defaultVpc = new Vpc();
        defaultVpc.setVpcId(vpcId);
        defaultVpc.setCidrBlock("172.31.0.0/16");
        defaultVpc.setState("available");
        defaultVpc.setDefault(true);
        defaultVpc.setOwnerId(accountId);
        defaultVpc.setRegion(region);
        defaultVpc.getCidrBlockAssociationSet().add(
                new VpcCidrBlockAssociation("vpc-cidr-assoc-default", "172.31.0.0/16"));
        vpcs.put(key(region, vpcId), defaultVpc);

        // Default subnets (a/b/c)
        String[] azSuffixes = {"a", "b", "c"};
        String[] cidrBlocks = {"172.31.0.0/20", "172.31.16.0/20", "172.31.32.0/20"};
        String[] subnetIds = {"subnet-default-a", "subnet-default-b", "subnet-default-c"};
        for (int i = 0; i < 3; i++) {
            Subnet subnet = new Subnet();
            subnet.setSubnetId(subnetIds[i]);
            subnet.setVpcId(vpcId);
            subnet.setCidrBlock(cidrBlocks[i]);
            subnet.setState("available");
            subnet.setAvailabilityZone(region + azSuffixes[i]);
            subnet.setAvailabilityZoneId(region + "-az" + (i + 1));
            subnet.setAvailableIpAddressCount(4091);
            subnet.setDefaultForAz(true);
            subnet.setMapPublicIpOnLaunch(true);
            subnet.setOwnerId(accountId);
            subnet.setRegion(region);
            subnet.setSubnetArn("arn:aws:ec2:" + region + ":" + accountId + ":subnet/" + subnetIds[i]);
            subnets.put(key(region, subnetIds[i]), subnet);
        }

        // Default security group
        String sgId = "sg-default";
        SecurityGroup defaultSg = new SecurityGroup();
        defaultSg.setGroupId(sgId);
        defaultSg.setGroupName("default");
        defaultSg.setDescription("default VPC security group");
        defaultSg.setVpcId(vpcId);
        defaultSg.setOwnerId(accountId);
        defaultSg.setRegion(region);
        // Default egress: all traffic
        IpPermission egressAll = new IpPermission();
        egressAll.setIpProtocol("-1");
        egressAll.getIpRanges().add(new IpRange("0.0.0.0/0"));
        defaultSg.getIpPermissionsEgress().add(egressAll);
        securityGroups.put(key(region, sgId), defaultSg);

        // Default internet gateway
        String igwId = "igw-default";
        InternetGateway igw = new InternetGateway();
        igw.setInternetGatewayId(igwId);
        igw.setOwnerId(accountId);
        igw.setRegion(region);
        igw.getAttachments().add(new InternetGatewayAttachment(vpcId, "available"));
        internetGateways.put(key(region, igwId), igw);

        // Main route table for default VPC
        String rtId = "rtb-default";
        RouteTable mainRt = new RouteTable();
        mainRt.setRouteTableId(rtId);
        mainRt.setVpcId(vpcId);
        mainRt.setOwnerId(accountId);
        mainRt.setRegion(region);
        mainRt.getRoutes().add(new Route("172.31.0.0/16", "local", "CreateRouteTable"));
        mainRt.getRoutes().add(new Route("0.0.0.0/0", igwId, "CreateRoute"));
        RouteTableAssociation mainAssoc = new RouteTableAssociation();
        mainAssoc.setRouteTableAssociationId("rtbassoc-default");
        mainAssoc.setRouteTableId(rtId);
        mainAssoc.setMain(true);
        mainAssoc.setAssociationState("associated");
        mainRt.getAssociations().add(mainAssoc);
        routeTables.put(key(region, rtId), mainRt);
    }

    private String key(String region, String id) {
        return region + "::" + id;
    }

    private String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        Random rand = new Random();
        for (int i = 0; i < len; i++) {
            sb.append(Integer.toHexString(rand.nextInt(16)));
        }
        return sb.toString();
    }

    // ─── Instances ─────────────────────────────────────────────────────────────

    public Reservation runInstances(String region, String imageId, String instanceType,
                                    int minCount, int maxCount, String keyName,
                                    List<String> securityGroupIds, String subnetId,
                                    String clientToken, List<Tag> instanceTags) {
        ensureDefaultResources(region);

        // Resolve subnet
        Subnet subnet = null;
        if (subnetId != null && !subnetId.isEmpty()) {
            subnet = subnets.get(key(region, subnetId));
            if (subnet == null) {
                throw new AwsException("InvalidSubnetID.NotFound", "The subnet ID '" + subnetId + "' does not exist", 400);
            }
        } else {
            // Pick first default subnet
            subnet = subnets.values().stream()
                    .filter(s -> s.getRegion().equals(region) && s.isDefaultForAz())
                    .findFirst()
                    .orElse(null);
        }

        String vpcId = subnet != null ? subnet.getVpcId() : "vpc-default";
        String az = subnet != null ? subnet.getAvailabilityZone() : region + "a";
        String finalSubnetId = subnet != null ? subnet.getSubnetId() : null;

        // Resolve security groups
        List<GroupIdentifier> sgIdentifiers = new ArrayList<>();
        if (securityGroupIds != null && !securityGroupIds.isEmpty()) {
            for (String sgId : securityGroupIds) {
                SecurityGroup sg = securityGroups.get(key(region, sgId));
                if (sg == null) {
                    throw new AwsException("InvalidGroup.NotFound", "The security group '" + sgId + "' does not exist", 400);
                }
                sgIdentifiers.add(new GroupIdentifier(sg.getGroupId(), sg.getGroupName()));
            }
        } else {
            // Use default SG
            SecurityGroup defaultSg = securityGroups.get(key(region, "sg-default"));
            if (defaultSg != null) {
                sgIdentifiers.add(new GroupIdentifier(defaultSg.getGroupId(), defaultSg.getGroupName()));
            }
        }

        String reservationId = "r-" + randomHex(17);
        Reservation reservation = new Reservation();
        reservation.setReservationId(reservationId);
        reservation.setOwnerId(accountId);

        int count = Math.min(maxCount, Math.max(minCount, 1));
        for (int i = 0; i < count; i++) {
            String instanceId = "i-" + randomHex(17);
            String privateIp = assignPrivateIp(region, finalSubnetId);

            Instance inst = new Instance();
            inst.setInstanceId(instanceId);
            inst.setImageId(imageId != null ? imageId : "ami-default");
            inst.setState(InstanceState.running());
            inst.setInstanceType(instanceType != null ? instanceType : "t2.micro");
            inst.setPlacement(new Placement(az));
            inst.setSubnetId(finalSubnetId);
            inst.setVpcId(vpcId);
            inst.setPrivateIpAddress(privateIp);
            inst.setPrivateDnsName("ip-" + privateIp.replace('.', '-') + ".ec2.internal");
            inst.setKeyName(keyName);
            inst.setSecurityGroups(new ArrayList<>(sgIdentifiers));
            inst.setArchitecture("x86_64");
            inst.setLaunchTime(Instant.now());
            inst.setAmiLaunchIndex(i);
            inst.setClientToken(clientToken);
            inst.setRegion(region);
            if (instanceTags != null && !instanceTags.isEmpty()) {
                inst.setTags(new ArrayList<>(instanceTags));
                tags.put(instanceId, new ArrayList<>(instanceTags));
            }

            // Network interface
            InstanceNetworkInterface eni = new InstanceNetworkInterface();
            eni.setNetworkInterfaceId("eni-" + randomHex(17));
            eni.setSubnetId(finalSubnetId);
            eni.setVpcId(vpcId);
            eni.setOwnerId(accountId);
            eni.setPrivateIpAddress(privateIp);
            eni.setPrivateDnsName(inst.getPrivateDnsName());
            eni.setGroups(new ArrayList<>(sgIdentifiers));
            inst.getNetworkInterfaces().add(eni);

            instances.put(key(region, instanceId), inst);
            reservation.getInstances().add(inst);
        }

        return reservation;
    }

    private String assignPrivateIp(String region, String subnetId) {
        if (subnetId == null) {
            return "172.31.0." + (10 + new Random().nextInt(200));
        }
        AtomicInteger counter = subnetIpCounters.computeIfAbsent(region + "::" + subnetId, k -> new AtomicInteger(10));
        int offset = counter.getAndIncrement();
        Subnet subnet = subnets.get(key(region, subnetId));
        if (subnet == null) {
            return "172.31.0." + offset;
        }
        // Parse base IP from CIDR
        String cidr = subnet.getCidrBlock();
        String baseIp = cidr.split("/")[0];
        String[] parts = baseIp.split("\\.");
        return parts[0] + "." + parts[1] + "." + parts[2] + "." + offset;
    }

    public List<Reservation> describeInstances(String region, List<String> instanceIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        if (!instanceIds.isEmpty()) {
            for (String id : instanceIds) {
                if (instances.get(key(region, id)) == null) {
                    throw new AwsException("InvalidInstanceID.NotFound",
                            "The instance ID '" + id + "' does not exist", 400);
                }
            }
        }
        List<Instance> matched = instances.values().stream()
                .filter(i -> i.getRegion().equals(region))
                .filter(i -> instanceIds.isEmpty() || instanceIds.contains(i.getInstanceId()))
                .filter(i -> matchesFilters(i, filters, region))
                .collect(Collectors.toList());

        // Group into reservations (one instance per reservation for simplicity)
        Map<String, Reservation> reservationMap = new LinkedHashMap<>();
        for (Instance inst : matched) {
            Reservation res = new Reservation();
            res.setReservationId("r-" + randomHex(17));
            res.setOwnerId(accountId);
            res.getInstances().add(inst);
            reservationMap.put(inst.getInstanceId(), res);
        }
        return new ArrayList<>(reservationMap.values());
    }

    public List<Map<String, String>> terminateInstances(String region, List<String> instanceIds) {
        ensureDefaultResources(region);
        List<Map<String, String>> result = new ArrayList<>();
        for (String id : instanceIds) {
            Instance inst = instances.get(key(region, id));
            if (inst == null) {
                throw new AwsException("InvalidInstanceID.NotFound", "The instance ID '" + id + "' does not exist", 400);
            }
            InstanceState prev = inst.getState();
            inst.setState(InstanceState.terminated());
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("instanceId", id);
            entry.put("previousState", prev.getName());
            entry.put("previousCode", String.valueOf(prev.getCode()));
            entry.put("currentState", "terminated");
            entry.put("currentCode", "48");
            result.add(entry);
        }
        return result;
    }

    public List<Map<String, String>> stopInstances(String region, List<String> instanceIds) {
        ensureDefaultResources(region);
        List<Map<String, String>> result = new ArrayList<>();
        for (String id : instanceIds) {
            Instance inst = instances.get(key(region, id));
            if (inst == null) {
                throw new AwsException("InvalidInstanceID.NotFound", "The instance ID '" + id + "' does not exist", 400);
            }
            InstanceState prev = inst.getState();
            inst.setState(InstanceState.stopped());
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("instanceId", id);
            entry.put("previousState", prev.getName());
            entry.put("previousCode", String.valueOf(prev.getCode()));
            entry.put("currentState", "stopped");
            entry.put("currentCode", "80");
            result.add(entry);
        }
        return result;
    }

    public List<Map<String, String>> startInstances(String region, List<String> instanceIds) {
        ensureDefaultResources(region);
        List<Map<String, String>> result = new ArrayList<>();
        for (String id : instanceIds) {
            Instance inst = instances.get(key(region, id));
            if (inst == null) {
                throw new AwsException("InvalidInstanceID.NotFound", "The instance ID '" + id + "' does not exist", 400);
            }
            InstanceState prev = inst.getState();
            inst.setState(InstanceState.running());
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("instanceId", id);
            entry.put("previousState", prev.getName());
            entry.put("previousCode", String.valueOf(prev.getCode()));
            entry.put("currentState", "running");
            entry.put("currentCode", "16");
            result.add(entry);
        }
        return result;
    }

    public void rebootInstances(String region, List<String> instanceIds) {
        ensureDefaultResources(region);
        for (String id : instanceIds) {
            Instance inst = instances.get(key(region, id));
            if (inst == null) {
                throw new AwsException("InvalidInstanceID.NotFound", "The instance ID '" + id + "' does not exist", 400);
            }
            // no-op in mock mode
        }
    }

    public List<Instance> describeInstanceStatus(String region, List<String> instanceIds) {
        ensureDefaultResources(region);
        return instances.values().stream()
                .filter(i -> i.getRegion().equals(region))
                .filter(i -> instanceIds.isEmpty() || instanceIds.contains(i.getInstanceId()))
                .filter(i -> "running".equals(i.getState().getName()))
                .collect(Collectors.toList());
    }

    public Instance describeInstanceAttribute(String region, String instanceId, String attribute) {
        ensureDefaultResources(region);
        Instance inst = instances.get(key(region, instanceId));
        if (inst == null) {
            throw new AwsException("InvalidInstanceID.NotFound", "The instance ID '" + instanceId + "' does not exist", 400);
        }
        return inst;
    }

    public void modifyInstanceAttribute(String region, String instanceId, String attribute, String value) {
        ensureDefaultResources(region);
        Instance inst = instances.get(key(region, instanceId));
        if (inst == null) {
            throw new AwsException("InvalidInstanceID.NotFound", "The instance ID '" + instanceId + "' does not exist", 400);
        }
        // basic attribute modifications
        switch (attribute) {
            case "instanceType" -> inst.setInstanceType(value);
            case "sourceDestCheck" -> inst.setSourceDestCheck(Boolean.parseBoolean(value));
            case "ebsOptimized" -> inst.setEbsOptimized(Boolean.parseBoolean(value));
        }
    }

    // ─── VPCs ──────────────────────────────────────────────────────────────────

    public Vpc createVpc(String region, String cidrBlock, boolean isDefault) {
        ensureDefaultResources(region);
        String vpcId = "vpc-" + randomHex(8);
        Vpc vpc = new Vpc();
        vpc.setVpcId(vpcId);
        vpc.setCidrBlock(cidrBlock);
        vpc.setState("available");
        vpc.setDefault(isDefault);
        vpc.setOwnerId(accountId);
        vpc.setRegion(region);
        vpc.getCidrBlockAssociationSet().add(
                new VpcCidrBlockAssociation("vpc-cidr-assoc-" + randomHex(8), cidrBlock));
        vpcs.put(key(region, vpcId), vpc);
        return vpc;
    }

    public List<Vpc> describeVpcs(String region, List<String> vpcIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        if (!vpcIds.isEmpty()) {
            for (String id : vpcIds) {
                if (vpcs.get(key(region, id)) == null) {
                    throw new AwsException("InvalidVpcID.NotFound",
                            "The vpc ID '" + id + "' does not exist", 400);
                }
            }
        }
        return vpcs.values().stream()
                .filter(v -> v.getRegion().equals(region))
                .filter(v -> vpcIds.isEmpty() || vpcIds.contains(v.getVpcId()))
                .filter(v -> matchesFilters(v, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteVpc(String region, String vpcId) {
        ensureDefaultResources(region);
        Vpc vpc = vpcs.get(key(region, vpcId));
        if (vpc == null) {
            throw new AwsException("InvalidVpcID.NotFound", "The vpc ID '" + vpcId + "' does not exist", 400);
        }
        vpcs.remove(key(region, vpcId));
    }

    public void modifyVpcAttribute(String region, String vpcId, String attribute, String value) {
        ensureDefaultResources(region);
        Vpc vpc = vpcs.get(key(region, vpcId));
        if (vpc == null) {
            throw new AwsException("InvalidVpcID.NotFound", "The vpc ID '" + vpcId + "' does not exist", 400);
        }
        switch (attribute) {
            case "enableDnsSupport"                    -> vpc.setEnableDnsSupport(Boolean.parseBoolean(value));
            case "enableDnsHostnames"                  -> vpc.setEnableDnsHostnames(Boolean.parseBoolean(value));
            case "enableNetworkAddressUsageMetrics"    -> vpc.setEnableNetworkAddressUsageMetrics(Boolean.parseBoolean(value));
        }
        vpcs.put(key(region, vpcId), vpc);
    }

    public Vpc describeVpcAttribute(String region, String vpcId, String attribute) {
        ensureDefaultResources(region);
        Vpc vpc = vpcs.get(key(region, vpcId));
        if (vpc == null) {
            throw new AwsException("InvalidVpcID.NotFound", "The vpc ID '" + vpcId + "' does not exist", 400);
        }
        return vpc;
    }

    public Vpc createDefaultVpc(String region) {
        ensureDefaultResources(region);
        // Return existing default or create one
        return vpcs.values().stream()
                .filter(v -> v.getRegion().equals(region) && v.isDefault())
                .findFirst()
                .orElseGet(() -> createVpc(region, "172.31.0.0/16", true));
    }

    public VpcCidrBlockAssociation associateVpcCidrBlock(String region, String vpcId, String cidrBlock) {
        ensureDefaultResources(region);
        Vpc vpc = vpcs.get(key(region, vpcId));
        if (vpc == null) {
            throw new AwsException("InvalidVpcID.NotFound", "The vpc ID '" + vpcId + "' does not exist", 400);
        }
        VpcCidrBlockAssociation assoc = new VpcCidrBlockAssociation(
                "vpc-cidr-assoc-" + randomHex(8), cidrBlock);
        vpc.getCidrBlockAssociationSet().add(assoc);
        return assoc;
    }

    public void disassociateVpcCidrBlock(String region, String associationId) {
        ensureDefaultResources(region);
        for (Vpc vpc : vpcs.values()) {
            if (vpc.getRegion().equals(region)) {
                vpc.getCidrBlockAssociationSet().removeIf(a -> a.getAssociationId().equals(associationId));
            }
        }
    }

    // ─── Subnets ───────────────────────────────────────────────────────────────

    public Subnet createSubnet(String region, String vpcId, String cidrBlock, String availabilityZone) {
        ensureDefaultResources(region);
        Vpc vpc = vpcs.get(key(region, vpcId));
        if (vpc == null) {
            throw new AwsException("InvalidVpcID.NotFound", "The vpc ID '" + vpcId + "' does not exist", 400);
        }
        String subnetId = "subnet-" + randomHex(8);
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(vpcId);
        subnet.setCidrBlock(cidrBlock);
        subnet.setState("available");
        subnet.setAvailabilityZone(availabilityZone != null ? availabilityZone : region + "a");
        subnet.setAvailabilityZoneId(region + "-az1");
        subnet.setAvailableIpAddressCount(251);
        subnet.setOwnerId(accountId);
        subnet.setRegion(region);
        subnet.setSubnetArn("arn:aws:ec2:" + region + ":" + accountId + ":subnet/" + subnetId);
        subnets.put(key(region, subnetId), subnet);
        return subnet;
    }

    public List<Subnet> describeSubnets(String region, List<String> subnetIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        return subnets.values().stream()
                .filter(s -> s.getRegion().equals(region))
                .filter(s -> subnetIds.isEmpty() || subnetIds.contains(s.getSubnetId()))
                .filter(s -> matchesFilters(s, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteSubnet(String region, String subnetId) {
        ensureDefaultResources(region);
        if (subnets.remove(key(region, subnetId)) == null) {
            throw new AwsException("InvalidSubnetID.NotFound", "The subnet ID '" + subnetId + "' does not exist", 400);
        }
    }

    public void modifySubnetAttribute(String region, String subnetId, String attribute, String value) {
        ensureDefaultResources(region);
        Subnet subnet = subnets.get(key(region, subnetId));
        if (subnet == null) {
            throw new AwsException("InvalidSubnetID.NotFound", "The subnet ID '" + subnetId + "' does not exist", 400);
        }
        if ("mapPublicIpOnLaunch".equals(attribute)) {
            subnet.setMapPublicIpOnLaunch(Boolean.parseBoolean(value));
        }
    }

    // ─── Security Groups ───────────────────────────────────────────────────────

    public SecurityGroup createSecurityGroup(String region, String groupName, String description, String vpcId) {
        ensureDefaultResources(region);
        if (vpcId != null && !vpcId.isEmpty()) {
            if (vpcs.get(key(region, vpcId)) == null) {
                throw new AwsException("InvalidVpcID.NotFound", "The vpc ID '" + vpcId + "' does not exist", 400);
            }
        } else {
            vpcId = "vpc-default";
        }
        // Check duplicate
        String finalVpcId = vpcId;
        boolean exists = securityGroups.values().stream()
                .anyMatch(sg -> sg.getRegion().equals(region) && sg.getGroupName().equals(groupName)
                        && finalVpcId.equals(sg.getVpcId()));
        if (exists) {
            throw new AwsException("InvalidGroup.Duplicate", "The security group '" + groupName + "' already exists", 400);
        }
        String sgId = "sg-" + randomHex(17);
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId(sgId);
        sg.setGroupName(groupName);
        sg.setDescription(description);
        sg.setVpcId(vpcId);
        sg.setOwnerId(accountId);
        sg.setRegion(region);
        // Default egress all
        IpPermission egressAll = new IpPermission();
        egressAll.setIpProtocol("-1");
        egressAll.getIpRanges().add(new IpRange("0.0.0.0/0"));
        sg.getIpPermissionsEgress().add(egressAll);
        securityGroups.put(key(region, sgId), sg);
        return sg;
    }

    public List<SecurityGroup> describeSecurityGroups(String region, List<String> groupIds,
                                                       List<String> groupNames, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        return securityGroups.values().stream()
                .filter(sg -> sg.getRegion().equals(region))
                .filter(sg -> groupIds.isEmpty() || groupIds.contains(sg.getGroupId()))
                .filter(sg -> groupNames.isEmpty() || groupNames.contains(sg.getGroupName()))
                .filter(sg -> matchesFilters(sg, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteSecurityGroup(String region, String groupId) {
        ensureDefaultResources(region);
        if (securityGroups.remove(key(region, groupId)) == null) {
            throw new AwsException("InvalidGroup.NotFound", "The security group '" + groupId + "' does not exist", 400);
        }
    }

    public List<SecurityGroupRule> authorizeSecurityGroupIngress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        SecurityGroup sg = securityGroups.get(key(region, groupId));
        if (sg == null) {
            throw new AwsException("InvalidGroup.NotFound", "The security group '" + groupId + "' does not exist", 400);
        }
        List<SecurityGroupRule> rules = new ArrayList<>();
        for (IpPermission perm : permissions) {
            sg.getIpPermissions().add(perm);
            rules.addAll(createRules(region, groupId, perm, false));
        }
        return rules;
    }

    public List<SecurityGroupRule> authorizeSecurityGroupEgress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        SecurityGroup sg = securityGroups.get(key(region, groupId));
        if (sg == null) {
            throw new AwsException("InvalidGroup.NotFound", "The security group '" + groupId + "' does not exist", 400);
        }
        List<SecurityGroupRule> rules = new ArrayList<>();
        for (IpPermission perm : permissions) {
            sg.getIpPermissionsEgress().add(perm);
            rules.addAll(createRules(region, groupId, perm, true));
        }
        return rules;
    }

    private List<SecurityGroupRule> createRules(String region, String groupId, IpPermission perm, boolean egress) {
        List<SecurityGroupRule> rules = new ArrayList<>();
        List<IpRange> ranges = perm.getIpRanges();
        if (ranges == null || ranges.isEmpty()) {
            SecurityGroupRule rule = new SecurityGroupRule();
            rule.setSecurityGroupRuleId("sgr-" + randomHex(17));
            rule.setGroupId(groupId);
            rule.setGroupOwnerId(accountId);
            rule.setEgress(egress);
            rule.setIpProtocol(perm.getIpProtocol());
            rule.setFromPort(perm.getFromPort());
            rule.setToPort(perm.getToPort());
            securityGroupRules.put(key(region, rule.getSecurityGroupRuleId()), rule);
            rules.add(rule);
        } else {
            for (IpRange range : ranges) {
                SecurityGroupRule rule = new SecurityGroupRule();
                rule.setSecurityGroupRuleId("sgr-" + randomHex(17));
                rule.setGroupId(groupId);
                rule.setGroupOwnerId(accountId);
                rule.setEgress(egress);
                rule.setIpProtocol(perm.getIpProtocol());
                rule.setFromPort(perm.getFromPort());
                rule.setToPort(perm.getToPort());
                rule.setCidrIpv4(range.getCidrIp());
                rule.setDescription(range.getDescription());
                securityGroupRules.put(key(region, rule.getSecurityGroupRuleId()), rule);
                rules.add(rule);
            }
        }
        return rules;
    }

    public void revokeSecurityGroupIngress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        SecurityGroup sg = securityGroups.get(key(region, groupId));
        if (sg == null) {
            throw new AwsException("InvalidGroup.NotFound", "The security group '" + groupId + "' does not exist", 400);
        }
        sg.getIpPermissions().removeIf(p -> matchesAnyPermission(p, permissions));
    }

    public void revokeSecurityGroupEgress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        SecurityGroup sg = securityGroups.get(key(region, groupId));
        if (sg == null) {
            throw new AwsException("InvalidGroup.NotFound", "The security group '" + groupId + "' does not exist", 400);
        }
        sg.getIpPermissionsEgress().removeIf(p -> matchesAnyPermission(p, permissions));
    }

    private boolean matchesAnyPermission(IpPermission existing, List<IpPermission> toRemove) {
        for (IpPermission perm : toRemove) {
            if (Objects.equals(existing.getIpProtocol(), perm.getIpProtocol())
                    && Objects.equals(existing.getFromPort(), perm.getFromPort())
                    && Objects.equals(existing.getToPort(), perm.getToPort())) {
                return true;
            }
        }
        return false;
    }

    public List<SecurityGroupRule> describeSecurityGroupRules(String region, String groupId, List<String> ruleIds) {
        ensureDefaultResources(region);
        return securityGroupRules.values().stream()
                .filter(r -> r.getGroupId().equals(groupId))
                .filter(r -> ruleIds.isEmpty() || ruleIds.contains(r.getSecurityGroupRuleId()))
                .collect(Collectors.toList());
    }

    public void modifySecurityGroupRules(String region, String groupId, List<Map<String, String>> ruleUpdates) {
        ensureDefaultResources(region);
        // Update description on matching rules
        for (Map<String, String> update : ruleUpdates) {
            String ruleId = update.get("SecurityGroupRuleId");
            String desc = update.get("Description");
            if (ruleId != null) {
                SecurityGroupRule rule = securityGroupRules.get(key(region, ruleId));
                if (rule != null && desc != null) {
                    rule.setDescription(desc);
                }
            }
        }
    }

    public void updateSecurityGroupRuleDescriptionsIngress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        // no-op for mock
    }

    public void updateSecurityGroupRuleDescriptionsEgress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        // no-op for mock
    }

    // ─── Key Pairs ─────────────────────────────────────────────────────────────

    public KeyPair createKeyPair(String region, String keyName) {
        ensureDefaultResources(region);
        boolean exists = keyPairs.values().stream()
                .anyMatch(k -> k.getRegion().equals(region) && k.getKeyName().equals(keyName));
        if (exists) {
            throw new AwsException("InvalidKeyPair.Duplicate", "The keypair '" + keyName + "' already exists", 400);
        }
        String keyPairId = "key-" + randomHex(17);
        KeyPair kp = new KeyPair();
        kp.setKeyPairId(keyPairId);
        kp.setKeyName(keyName);
        kp.setKeyFingerprint("00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00");
        kp.setKeyMaterial("-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA0Z3VS5JJcds3xHn/ygWep4Ib/ue7YiKbCIZgYpYDe0+FAKE\n-----END RSA PRIVATE KEY-----");
        kp.setRegion(region);
        keyPairs.put(key(region, keyPairId), kp);
        return kp;
    }

    public List<KeyPair> describeKeyPairs(String region, List<String> keyNames, List<String> keyPairIds) {
        ensureDefaultResources(region);
        return keyPairs.values().stream()
                .filter(k -> k.getRegion().equals(region))
                .filter(k -> keyNames.isEmpty() || keyNames.contains(k.getKeyName()))
                .filter(k -> keyPairIds.isEmpty() || keyPairIds.contains(k.getKeyPairId()))
                .collect(Collectors.toList());
    }

    public void deleteKeyPair(String region, String keyName, String keyPairId) {
        ensureDefaultResources(region);
        if (keyPairId != null && !keyPairId.isEmpty()) {
            keyPairs.remove(key(region, keyPairId));
        } else {
            keyPairs.values().removeIf(k -> k.getRegion().equals(region) && k.getKeyName().equals(keyName));
        }
    }

    public KeyPair importKeyPair(String region, String keyName, String publicKeyMaterial) {
        ensureDefaultResources(region);
        String keyPairId = "key-" + randomHex(17);
        KeyPair kp = new KeyPair();
        kp.setKeyPairId(keyPairId);
        kp.setKeyName(keyName);
        kp.setKeyFingerprint("00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00");
        kp.setRegion(region);
        keyPairs.put(key(region, keyPairId), kp);
        return kp;
    }

    // ─── AMIs ──────────────────────────────────────────────────────────────────

    public List<Image> describeImages(String region, List<String> imageIds, List<String> owners) {
        List<Image> staticImages = new ArrayList<>();

        Image al2 = new Image();
        al2.setImageId("ami-0abcdef1234567890");
        al2.setName("amzn2-ami-hvm-2.0.20230404.0-x86_64-gp2");
        al2.setDescription("Amazon Linux 2 AMI");
        al2.setArchitecture("x86_64");
        al2.setCreationDate("2023-04-04T00:00:00.000Z");
        staticImages.add(al2);

        Image al2023 = new Image();
        al2023.setImageId("ami-0abcdef1234567891");
        al2023.setName("al2023-ami-2023.0.20230315.0-kernel-6.1-x86_64");
        al2023.setDescription("Amazon Linux 2023 AMI");
        al2023.setArchitecture("x86_64");
        al2023.setCreationDate("2023-03-15T00:00:00.000Z");
        staticImages.add(al2023);

        Image ubuntu = new Image();
        ubuntu.setImageId("ami-0abcdef1234567892");
        ubuntu.setName("ubuntu/images/hvm-ssd/ubuntu-focal-20.04-amd64-server-20230324");
        ubuntu.setDescription("Canonical, Ubuntu, 20.04 LTS");
        ubuntu.setArchitecture("x86_64");
        ubuntu.setCreationDate("2023-03-24T00:00:00.000Z");
        staticImages.add(ubuntu);

        Image windows = new Image();
        windows.setImageId("ami-0abcdef1234567893");
        windows.setName("Windows_Server-2022-English-Full-Base-2023.04.12");
        windows.setDescription("Microsoft Windows Server 2022 Full Locale English AMI");
        windows.setArchitecture("x86_64");
        windows.setPlatform("windows");
        windows.setCreationDate("2023-04-12T00:00:00.000Z");
        staticImages.add(windows);

        return staticImages.stream()
                .filter(img -> imageIds.isEmpty() || imageIds.contains(img.getImageId()))
                .collect(Collectors.toList());
    }

    // ─── Tags ──────────────────────────────────────────────────────────────────

    public void createTags(String region, List<String> resourceIds, List<Tag> tagList) {
        ensureDefaultResources(region);
        for (String resourceId : resourceIds) {
            tags.computeIfAbsent(resourceId, k -> new ArrayList<>());
            List<Tag> existing = tags.get(resourceId);
            for (Tag tag : tagList) {
                existing.removeIf(t -> t.getKey().equals(tag.getKey()));
                existing.add(tag);
            }
            // Update resource objects
            updateResourceTags(region, resourceId, existing);
        }
    }

    public void deleteTags(String region, List<String> resourceIds, List<Tag> tagList) {
        ensureDefaultResources(region);
        for (String resourceId : resourceIds) {
            List<Tag> existing = tags.get(resourceId);
            if (existing != null) {
                for (Tag tag : tagList) {
                    existing.removeIf(t -> t.getKey().equals(tag.getKey())
                            && (tag.getValue() == null || tag.getValue().equals(t.getValue())));
                }
                updateResourceTags(region, resourceId, existing);
            }
        }
    }

    private void updateResourceTags(String region, String resourceId, List<Tag> tagList) {
        Instance inst = instances.get(key(region, resourceId));
        if (inst != null) { inst.setTags(new ArrayList<>(tagList)); return; }
        Vpc vpc = vpcs.get(key(region, resourceId));
        if (vpc != null) { vpc.setTags(new ArrayList<>(tagList)); return; }
        Subnet subnet = subnets.get(key(region, resourceId));
        if (subnet != null) { subnet.setTags(new ArrayList<>(tagList)); return; }
        SecurityGroup sg = securityGroups.get(key(region, resourceId));
        if (sg != null) { sg.setTags(new ArrayList<>(tagList)); return; }
        InternetGateway igw = internetGateways.get(key(region, resourceId));
        if (igw != null) { igw.setTags(new ArrayList<>(tagList)); return; }
        RouteTable rt = routeTables.get(key(region, resourceId));
        if (rt != null) { rt.setTags(new ArrayList<>(tagList)); return; }
        KeyPair kp = keyPairs.get(key(region, resourceId));
        if (kp != null) { kp.setTags(new ArrayList<>(tagList)); }
    }

    public List<Map<String, String>> describeTags(String region, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        List<Map<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, List<Tag>> entry : tags.entrySet()) {
            String resourceId = entry.getKey();
            for (Tag tag : entry.getValue()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("resourceId", resourceId);
                item.put("resourceType", inferResourceType(resourceId));
                item.put("key", tag.getKey());
                item.put("value", tag.getValue());
                result.add(item);
            }
        }
        return result;
    }

    private String inferResourceType(String resourceId) {
        if (resourceId.startsWith("i-")) return "instance";
        if (resourceId.startsWith("vpc-")) return "vpc";
        if (resourceId.startsWith("subnet-")) return "subnet";
        if (resourceId.startsWith("sg-")) return "security-group";
        if (resourceId.startsWith("igw-")) return "internet-gateway";
        if (resourceId.startsWith("rtb-")) return "route-table";
        if (resourceId.startsWith("key-")) return "key-pair";
        if (resourceId.startsWith("eipalloc-")) return "elastic-ip";
        return "unknown";
    }

    // ─── Internet Gateways ─────────────────────────────────────────────────────

    public InternetGateway createInternetGateway(String region) {
        ensureDefaultResources(region);
        String igwId = "igw-" + randomHex(8);
        InternetGateway igw = new InternetGateway();
        igw.setInternetGatewayId(igwId);
        igw.setOwnerId(accountId);
        igw.setRegion(region);
        internetGateways.put(key(region, igwId), igw);
        return igw;
    }

    public List<InternetGateway> describeInternetGateways(String region, List<String> igwIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        return internetGateways.values().stream()
                .filter(igw -> igw.getRegion().equals(region))
                .filter(igw -> igwIds.isEmpty() || igwIds.contains(igw.getInternetGatewayId()))
                .filter(igw -> matchesFilters(igw, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteInternetGateway(String region, String igwId) {
        ensureDefaultResources(region);
        if (internetGateways.remove(key(region, igwId)) == null) {
            throw new AwsException("InvalidInternetGatewayID.NotFound", "The internet gateway '" + igwId + "' does not exist", 400);
        }
    }

    public void attachInternetGateway(String region, String igwId, String vpcId) {
        ensureDefaultResources(region);
        InternetGateway igw = internetGateways.get(key(region, igwId));
        if (igw == null) {
            throw new AwsException("InvalidInternetGatewayID.NotFound", "The internet gateway '" + igwId + "' does not exist", 400);
        }
        igw.getAttachments().add(new InternetGatewayAttachment(vpcId, "available"));
    }

    public void detachInternetGateway(String region, String igwId, String vpcId) {
        ensureDefaultResources(region);
        InternetGateway igw = internetGateways.get(key(region, igwId));
        if (igw == null) {
            throw new AwsException("InvalidInternetGatewayID.NotFound", "The internet gateway '" + igwId + "' does not exist", 400);
        }
        igw.getAttachments().removeIf(a -> a.getVpcId().equals(vpcId));
    }

    // ─── Route Tables ──────────────────────────────────────────────────────────

    public RouteTable createRouteTable(String region, String vpcId) {
        ensureDefaultResources(region);
        Vpc vpc = vpcs.get(key(region, vpcId));
        if (vpc == null) {
            throw new AwsException("InvalidVpcID.NotFound", "The vpc ID '" + vpcId + "' does not exist", 400);
        }
        String rtId = "rtb-" + randomHex(8);
        RouteTable rt = new RouteTable();
        rt.setRouteTableId(rtId);
        rt.setVpcId(vpcId);
        rt.setOwnerId(accountId);
        rt.setRegion(region);
        rt.getRoutes().add(new Route(vpc.getCidrBlock(), "local", "CreateRouteTable"));
        routeTables.put(key(region, rtId), rt);
        return rt;
    }

    public List<RouteTable> describeRouteTables(String region, List<String> routeTableIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        return routeTables.values().stream()
                .filter(rt -> rt.getRegion().equals(region))
                .filter(rt -> routeTableIds.isEmpty() || routeTableIds.contains(rt.getRouteTableId()))
                .filter(rt -> matchesFilters(rt, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteRouteTable(String region, String routeTableId) {
        ensureDefaultResources(region);
        if (routeTables.remove(key(region, routeTableId)) == null) {
            throw new AwsException("InvalidRouteTableID.NotFound", "The route table '" + routeTableId + "' does not exist", 400);
        }
    }

    public RouteTableAssociation associateRouteTable(String region, String routeTableId, String subnetId) {
        ensureDefaultResources(region);
        RouteTable rt = routeTables.get(key(region, routeTableId));
        if (rt == null) {
            throw new AwsException("InvalidRouteTableID.NotFound", "The route table '" + routeTableId + "' does not exist", 400);
        }
        String assocId = "rtbassoc-" + randomHex(8);
        RouteTableAssociation assoc = new RouteTableAssociation();
        assoc.setRouteTableAssociationId(assocId);
        assoc.setRouteTableId(routeTableId);
        assoc.setSubnetId(subnetId);
        assoc.setMain(false);
        assoc.setAssociationState("associated");
        rt.getAssociations().add(assoc);
        return assoc;
    }

    public void disassociateRouteTable(String region, String associationId) {
        ensureDefaultResources(region);
        for (RouteTable rt : routeTables.values()) {
            if (rt.getRegion().equals(region)) {
                rt.getAssociations().removeIf(a -> a.getRouteTableAssociationId().equals(associationId));
            }
        }
    }

    public void createRoute(String region, String routeTableId, String destinationCidrBlock, String gatewayId) {
        ensureDefaultResources(region);
        RouteTable rt = routeTables.get(key(region, routeTableId));
        if (rt == null) {
            throw new AwsException("InvalidRouteTableID.NotFound", "The route table '" + routeTableId + "' does not exist", 400);
        }
        rt.getRoutes().add(new Route(destinationCidrBlock, gatewayId, "CreateRoute"));
    }

    public void deleteRoute(String region, String routeTableId, String destinationCidrBlock) {
        ensureDefaultResources(region);
        RouteTable rt = routeTables.get(key(region, routeTableId));
        if (rt == null) {
            throw new AwsException("InvalidRouteTableID.NotFound", "The route table '" + routeTableId + "' does not exist", 400);
        }
        rt.getRoutes().removeIf(r -> r.getDestinationCidrBlock().equals(destinationCidrBlock));
    }

    // ─── Elastic IPs ───────────────────────────────────────────────────────────

    public Address allocateAddress(String region) {
        ensureDefaultResources(region);
        String allocId = "eipalloc-" + randomHex(17);
        String ip = "54." + (new Random().nextInt(256)) + "." + (new Random().nextInt(256)) + "." + (new Random().nextInt(256));
        Address addr = new Address();
        addr.setAllocationId(allocId);
        addr.setPublicIp(ip);
        addr.setRegion(region);
        addresses.put(key(region, allocId), addr);
        return addr;
    }

    public Address associateAddress(String region, String allocationId, String instanceId) {
        ensureDefaultResources(region);
        Address addr = addresses.get(key(region, allocationId));
        if (addr == null) {
            throw new AwsException("InvalidAllocationID.NotFound", "The allocation ID '" + allocationId + "' does not exist", 400);
        }
        addr.setInstanceId(instanceId);
        addr.setAssociationId("eipassoc-" + randomHex(17));
        return addr;
    }

    public void disassociateAddress(String region, String associationId) {
        ensureDefaultResources(region);
        for (Address addr : addresses.values()) {
            if (addr.getRegion().equals(region) && associationId.equals(addr.getAssociationId())) {
                addr.setInstanceId(null);
                addr.setAssociationId(null);
                return;
            }
        }
    }

    public void releaseAddress(String region, String allocationId) {
        ensureDefaultResources(region);
        if (addresses.remove(key(region, allocationId)) == null) {
            throw new AwsException("InvalidAllocationID.NotFound", "The allocation ID '" + allocationId + "' does not exist", 400);
        }
    }

    public List<Address> describeAddresses(String region, List<String> allocationIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        return addresses.values().stream()
                .filter(a -> a.getRegion().equals(region))
                .filter(a -> allocationIds.isEmpty() || allocationIds.contains(a.getAllocationId()))
                .collect(Collectors.toList());
    }

    // ─── Availability Zones & Regions ─────────────────────────────────────────

    public List<Map<String, String>> describeAvailabilityZones(String region) {
        List<Map<String, String>> zones = new ArrayList<>();
        String[] azSuffixes = {"a", "b", "c"};
        for (String suffix : azSuffixes) {
            Map<String, String> az = new LinkedHashMap<>();
            az.put("zoneName", region + suffix);
            az.put("state", "available");
            az.put("regionName", region);
            az.put("zoneId", region + "-az" + (suffix.charAt(0) - 'a' + 1));
            az.put("zoneType", "availability-zone");
            zones.add(az);
        }
        return zones;
    }

    public List<String> describeRegions() {
        return List.of(
                "us-east-1", "us-east-2", "us-west-1", "us-west-2",
                "eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1",
                "ap-northeast-1", "ap-northeast-2", "ap-southeast-1", "ap-southeast-2",
                "ap-south-1", "sa-east-1", "ca-central-1"
        );
    }

    public Map<String, String> describeAccountAttributes(String region) {
        ensureDefaultResources(region);
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("supported-platforms", "VPC");
        attrs.put("default-vpc", "vpc-default");
        return attrs;
    }

    // ─── Instance Types ────────────────────────────────────────────────────────

    public List<Map<String, Object>> describeInstanceTypes(List<String> instanceTypeNames) {
        List<Map<String, Object>> allTypes = new ArrayList<>();
        allTypes.add(buildInstanceType("t2.micro", 1, 1024));
        allTypes.add(buildInstanceType("t3.micro", 2, 1024));
        allTypes.add(buildInstanceType("t3.small", 2, 2048));
        allTypes.add(buildInstanceType("t3.medium", 2, 4096));
        allTypes.add(buildInstanceType("m5.large", 2, 8192));

        if (instanceTypeNames.isEmpty()) {
            return allTypes;
        }
        return allTypes.stream()
                .filter(t -> instanceTypeNames.contains(t.get("instanceType")))
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildInstanceType(String name, int vcpu, int memMib) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("instanceType", name);
        t.put("vcpu", vcpu);
        t.put("memoryMib", memMib);
        t.put("supportedArchitectures", List.of("x86_64"));
        t.put("currentGeneration", true);
        return t;
    }

    // ─── Filter matching ───────────────────────────────────────────────────────

    private boolean matchesFilters(Object resource, Map<String, List<String>> filters, String region) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
            String name = filter.getKey();
            List<String> values = filter.getValue();
            if (!matchesFilter(resource, name, values, region)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesFilter(Object resource, String filterName, List<String> values, String region) {
        if (filterName.startsWith("tag:")) {
            String tagKey = filterName.substring(4);
            List<Tag> resourceTags = getResourceTags(resource);
            return resourceTags.stream()
                    .anyMatch(t -> t.getKey().equals(tagKey) && values.contains(t.getValue()));
        }
        if ("tag-key".equals(filterName)) {
            List<Tag> resourceTags = getResourceTags(resource);
            return resourceTags.stream().anyMatch(t -> values.contains(t.getKey()));
        }
        if ("tag-value".equals(filterName)) {
            List<Tag> resourceTags = getResourceTags(resource);
            return resourceTags.stream().anyMatch(t -> values.contains(t.getValue()));
        }
        // Resource-specific field filters
        if (resource instanceof Vpc vpc) {
            return switch (filterName) {
                case "vpc-id" -> values.contains(vpc.getVpcId());
                case "state" -> values.contains(vpc.getState());
                case "isDefault", "is-default" -> values.contains(String.valueOf(vpc.isDefault()));
                case "cidr" -> values.contains(vpc.getCidrBlock());
                default -> true;
            };
        }
        if (resource instanceof Subnet subnet) {
            return switch (filterName) {
                case "subnet-id" -> values.contains(subnet.getSubnetId());
                case "vpc-id" -> values.contains(subnet.getVpcId());
                case "state" -> values.contains(subnet.getState());
                case "availabilityZone", "availability-zone" -> values.contains(subnet.getAvailabilityZone());
                default -> true;
            };
        }
        if (resource instanceof SecurityGroup sg) {
            return switch (filterName) {
                case "group-id" -> values.contains(sg.getGroupId());
                case "group-name" -> values.contains(sg.getGroupName());
                case "vpc-id" -> values.contains(sg.getVpcId());
                default -> true;
            };
        }
        if (resource instanceof Instance inst) {
            return switch (filterName) {
                case "instance-id" -> values.contains(inst.getInstanceId());
                case "instance-state-name" -> values.contains(inst.getState().getName());
                case "instance-type" -> values.contains(inst.getInstanceType());
                case "vpc-id" -> values.contains(inst.getVpcId());
                case "subnet-id" -> values.contains(inst.getSubnetId());
                default -> true;
            };
        }
        if (resource instanceof InternetGateway igw) {
            return switch (filterName) {
                case "internet-gateway-id" -> values.contains(igw.getInternetGatewayId());
                case "attachment.vpc-id" -> igw.getAttachments().stream()
                        .anyMatch(a -> values.contains(a.getVpcId()));
                default -> true;
            };
        }
        if (resource instanceof RouteTable rt) {
            return switch (filterName) {
                case "route-table-id" -> values.contains(rt.getRouteTableId());
                case "vpc-id" -> values.contains(rt.getVpcId());
                default -> true;
            };
        }
        if (resource instanceof Volume vol) {
            return switch (filterName) {
                case "volume-id" -> values.contains(vol.getVolumeId());
                case "status" -> values.contains(vol.getState());
                case "volume-type" -> values.contains(vol.getVolumeType());
                case "availability-zone" -> values.contains(vol.getAvailabilityZone());
                case "encrypted" -> values.contains(String.valueOf(vol.isEncrypted()));
                default -> true;
            };
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<Tag> getResourceTags(Object resource) {
        if (resource instanceof Instance inst) return inst.getTags();
        if (resource instanceof Vpc vpc) return vpc.getTags();
        if (resource instanceof Subnet subnet) return subnet.getTags();
        if (resource instanceof SecurityGroup sg) return sg.getTags();
        if (resource instanceof InternetGateway igw) return igw.getTags();
        if (resource instanceof RouteTable rt) return rt.getTags();
        if (resource instanceof KeyPair kp) return kp.getTags();
        if (resource instanceof Address addr) return addr.getTags();
        if (resource instanceof Volume vol) return vol.getTags();
        return Collections.emptyList();
    }

    // ─── Volumes ───────────────────────────────────────────────────────────────

    public Volume createVolume(String region, String availabilityZone, String volumeType,
                               int size, boolean encrypted, int iops, String snapshotId,
                               List<Tag> volumeTags) {
        ensureDefaultResources(region);
        String volumeId = "vol-" + randomHex(17);
        Volume vol = new Volume();
        vol.setVolumeId(volumeId);
        vol.setAvailabilityZone(availabilityZone != null ? availabilityZone : region + "a");
        vol.setVolumeType(volumeType != null ? volumeType : "gp2");
        vol.setSize(size > 0 ? size : 8);
        vol.setEncrypted(encrypted);
        vol.setIops(iops > 0 ? iops : (volumeType != null && volumeType.startsWith("io") ? iops : 0));
        vol.setSnapshotId(snapshotId);
        vol.setCreateTime(Instant.now());
        vol.setState("available");
        vol.setRegion(region);
        if (volumeTags != null) vol.setTags(new ArrayList<>(volumeTags));
        volumes.put(key(region, volumeId), vol);
        return vol;
    }

    public List<Volume> describeVolumes(String region, List<String> volumeIds,
                                        Map<String, List<String>> filters) {
        if (volumeIds != null && !volumeIds.isEmpty()) {
            for (String id : volumeIds) {
                if (volumes.get(key(region, id)) == null) {
                    throw new AwsException("InvalidVolume.NotFound",
                            "The volume '" + id + "' does not exist.", 400);
                }
            }
        }
        return volumes.values().stream()
                .filter(v -> v.getRegion().equals(region))
                .filter(v -> volumeIds == null || volumeIds.isEmpty() || volumeIds.contains(v.getVolumeId()))
                .filter(v -> matchesFilters(v, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteVolume(String region, String volumeId) {
        if (volumes.remove(key(region, volumeId)) == null) {
            throw new AwsException("InvalidVolume.NotFound",
                    "The volume '" + volumeId + "' does not exist.", 400);
        }
    }
}
