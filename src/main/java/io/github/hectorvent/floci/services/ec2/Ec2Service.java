package io.github.hectorvent.floci.services.ec2;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfaceAssociation;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfaceAttachment;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfaceListResult;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterfacePrivateIpAddress;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.InternetGatewayAttachment;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.RouteTableAssociation;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcCidrBlockAssociation;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class Ec2Service {

    private static final Logger LOG = Logger.getLogger(Ec2Service.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final String accountId;
    private final EmulatorConfig config;
    private final Ec2ContainerManager containerManager;
    private final AmiImageResolver amiImageResolver;
    private final Ec2ImageCatalog imageCatalog;

    // region::id → resource (persisted via StorageFactory so state survives a restart in
    // persistent/hybrid/wal modes; see #1297 — CloudFormation persists stacks/exports that
    // reference these EC2 ids, so the ids must survive too)
    private final StorageBackend<String, Vpc> vpcs;
    private final StorageBackend<String, Subnet> subnets;
    private final StorageBackend<String, SecurityGroup> securityGroups;
    private final StorageBackend<String, SecurityGroupRule> securityGroupRules;
    private final StorageBackend<String, InternetGateway> internetGateways;
    private final StorageBackend<String, RouteTable> routeTables;
    private final StorageBackend<String, KeyPair> keyPairs;
    private final StorageBackend<String, Address> addresses;
    private final StorageBackend<String, Instance> instances;
    private final StorageBackend<String, Volume> volumes;
    private final StorageBackend<String, LaunchTemplate> launchTemplates;
    private final StorageBackend<String, VpcEndpoint> vpcEndpoints;
    private final StorageBackend<String, NatGateway> natGateways;
    // resourceId → List<Tag>
    private final StorageBackend<String, List<Tag>> tags;
    private final Set<String> seededRegions = ConcurrentHashMap.newKeySet();
    // subnetId → counter for IP assignment (runtime-only, not persisted)
    private final Map<String, AtomicInteger> subnetIpCounters = new ConcurrentHashMap<>();

    @Inject
    public Ec2Service(EmulatorConfig config, Ec2ContainerManager containerManager,
                      AmiImageResolver amiImageResolver, Ec2ImageCatalog imageCatalog,
                      StorageFactory storageFactory) {
        this(config, containerManager, amiImageResolver, imageCatalog,
                storageFactory.create("ec2", "ec2-vpcs.json", new TypeReference<Map<String, Vpc>>() {}),
                storageFactory.create("ec2", "ec2-subnets.json", new TypeReference<Map<String, Subnet>>() {}),
                storageFactory.create("ec2", "ec2-security-groups.json", new TypeReference<Map<String, SecurityGroup>>() {}),
                storageFactory.create("ec2", "ec2-security-group-rules.json", new TypeReference<Map<String, SecurityGroupRule>>() {}),
                storageFactory.create("ec2", "ec2-internet-gateways.json", new TypeReference<Map<String, InternetGateway>>() {}),
                storageFactory.create("ec2", "ec2-route-tables.json", new TypeReference<Map<String, RouteTable>>() {}),
                storageFactory.create("ec2", "ec2-key-pairs.json", new TypeReference<Map<String, KeyPair>>() {}),
                storageFactory.create("ec2", "ec2-addresses.json", new TypeReference<Map<String, Address>>() {}),
                storageFactory.create("ec2", "ec2-instances.json", new TypeReference<Map<String, Instance>>() {}),
                storageFactory.create("ec2", "ec2-volumes.json", new TypeReference<Map<String, Volume>>() {}),
                storageFactory.create("ec2", "ec2-launch-templates.json", new TypeReference<Map<String, LaunchTemplate>>() {}),
                storageFactory.create("ec2", "ec2-vpc-endpoints.json", new TypeReference<Map<String, VpcEndpoint>>() {}),
                storageFactory.create("ec2", "ec2-nat-gateways.json", new TypeReference<Map<String, NatGateway>>() {}),
                storageFactory.create("ec2", "ec2-tags.json", new TypeReference<Map<String, List<Tag>>>() {}));
    }

    // Package-private for hermetic tests (pass in-memory or temp-dir-backed StorageBackends directly).
    Ec2Service(EmulatorConfig config, Ec2ContainerManager containerManager,
               AmiImageResolver amiImageResolver, Ec2ImageCatalog imageCatalog,
               StorageBackend<String, Vpc> vpcs,
               StorageBackend<String, Subnet> subnets,
               StorageBackend<String, SecurityGroup> securityGroups,
               StorageBackend<String, SecurityGroupRule> securityGroupRules,
               StorageBackend<String, InternetGateway> internetGateways,
               StorageBackend<String, RouteTable> routeTables,
               StorageBackend<String, KeyPair> keyPairs,
               StorageBackend<String, Address> addresses,
               StorageBackend<String, Instance> instances,
               StorageBackend<String, Volume> volumes,
               StorageBackend<String, LaunchTemplate> launchTemplates,
               StorageBackend<String, VpcEndpoint> vpcEndpoints,
               StorageBackend<String, NatGateway> natGateways,
               StorageBackend<String, List<Tag>> tags) {
        this.accountId = config.defaultAccountId();
        this.config = config;
        this.containerManager = containerManager;
        this.amiImageResolver = amiImageResolver;
        this.imageCatalog = imageCatalog;
        this.vpcs = vpcs;
        this.subnets = subnets;
        this.securityGroups = securityGroups;
        this.securityGroupRules = securityGroupRules;
        this.internetGateways = internetGateways;
        this.routeTables = routeTables;
        this.keyPairs = keyPairs;
        this.addresses = addresses;
        this.instances = instances;
        this.volumes = volumes;
        this.launchTemplates = launchTemplates;
        this.vpcEndpoints = vpcEndpoints;
        this.natGateways = natGateways;
        this.tags = tags;
    }

    // ─── Default resource seeding ──────────────────────────────────────────────

    public void ensureDefaultResources(String region) {
        if (!seededRegions.add(region)) {
            return;
        }
        // Already provisioned in a previous run and reloaded from persistent storage: the default
        // VPC (and everything else) is present, so don't re-seed and create duplicates (#1297).
        if (!vpcs.scan(k -> k.startsWith(region + "::")).isEmpty()) {
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
            subnet.setSubnetArn(AwsArnUtils.Arn.of("ec2", region, accountId, "subnet/" + subnetIds[i]).toString());
            subnets.put(key(region, subnetIds[i]), subnet);
        }

        createDefaultSecurityGroup(region, vpcId, "sg-default");

        // Default internet gateway
        String igwId = "igw-default";
        InternetGateway igw = new InternetGateway();
        igw.setInternetGatewayId(igwId);
        igw.setOwnerId(accountId);
        igw.setRegion(region);
        igw.getAttachments().add(new InternetGatewayAttachment(vpcId, "available"));
        internetGateways.put(key(region, igwId), igw);

        String rtId = createMainRouteTable(region, defaultVpc, "rtb-default", "rtbassoc-default");

        RouteTable mainRt = routeTables.get(key(region, rtId)).orElse(null);
        if (mainRt != null) {
            mainRt.getRoutes().add(new Route("0.0.0.0/0", igwId, "CreateRoute"));
        }
    }

    private void createDefaultSecurityGroup(String region, String vpcId, String securityGroupId) {
        SecurityGroup defaultSg = new SecurityGroup();
        defaultSg.setGroupId(securityGroupId);
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
        securityGroups.put(key(region, securityGroupId), defaultSg);
    }

    private String createMainRouteTable(String region, Vpc vpc, String routeTableId, String associationId) {
        RouteTable mainRt = new RouteTable();
        mainRt.setRouteTableId(routeTableId);
        mainRt.setVpcId(vpc.getVpcId());
        mainRt.setOwnerId(accountId);
        mainRt.setRegion(region);
        mainRt.getRoutes().add(new Route(vpc.getCidrBlock(), "local", "CreateRouteTable"));

        RouteTableAssociation mainAssoc = new RouteTableAssociation();
        mainAssoc.setRouteTableAssociationId(associationId);
        mainAssoc.setRouteTableId(routeTableId);
        mainAssoc.setMain(true);
        mainAssoc.setAssociationState("associated");
        mainRt.getAssociations().add(mainAssoc);

        routeTables.put(key(region, routeTableId), mainRt);
        return routeTableId;
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
                                    String clientToken, List<Tag> instanceTags,
                                    String userData, String iamInstanceProfileArn) {
        ensureDefaultResources(region);

        // Resolve subnet
        Subnet subnet = null;
        if (subnetId != null && !subnetId.isEmpty()) {
            subnet = requireSubnet(region, subnetId);
        } else {
            // Pick first default subnet
            subnet = subnets.scan(k -> true).stream()
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
                SecurityGroup sg = getRequiredSecurityGroup(region, sgId);
                sgIdentifiers.add(new GroupIdentifier(sg.getGroupId(), sg.getGroupName()));
            }
        } else {
            // Use default SG
            SecurityGroup defaultSg = securityGroups.get(key(region, "sg-default")).orElse(null);
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
            inst.setState(InstanceState.pending());
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
            inst.setUserData(userData);
            inst.setIamInstanceProfileArn(iamInstanceProfileArn);
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
            eni.setAttachmentId("eni-attach-" + randomHex(17));
            eni.setDeviceIndex(0);
            if (inst.getLaunchTime() != null) {
                eni.setAttachTime(ISO_FMT.format(inst.getLaunchTime()));
            }
            inst.getNetworkInterfaces().add(eni);

            // Root EBS volume
            String rootVolId = "vol-" + randomHex(17);
            inst.setRootVolumeId(rootVolId);
            Volume rootVol = new Volume();
            rootVol.setVolumeId(rootVolId);
            rootVol.setAvailabilityZone(az);
            rootVol.setVolumeType("gp3");
            rootVol.setSize(8);
            rootVol.setState("in-use");
            rootVol.setRegion(region);
            rootVol.setCreateTime(Instant.now());
            VolumeAttachment att = new VolumeAttachment();
            att.setVolumeId(rootVolId);
            att.setInstanceId(instanceId);
            att.setDevice(inst.getRootDeviceName());
            att.setState("attached");
            att.setDeleteOnTermination(true);
            att.setAttachTime(Instant.now());
            rootVol.getAttachments().add(att);
            volumes.put(key(region, rootVolId), rootVol);

            instances.put(key(region, instanceId), inst);
            reservation.getInstances().add(inst);

            if (!config.services().ec2().mock()) {
                String dockerImage = amiImageResolver.resolve(imageId);
                String publicKey = null;
                if (keyName != null) {
                    KeyPair kp = findKeyPair(region, keyName);
                    if (kp != null) {
                        publicKey = kp.getPublicKey();
                    }
                }
                containerManager.launch(inst, dockerImage, publicKey, region);
            }
        }

        return reservation;
    }

    public Subnet requireSubnet(String region, String subnetId) {
        ensureDefaultResources(region);
        Subnet subnet = subnets.get(key(region, subnetId)).orElse(null);
        if (subnet == null)
            throw new AwsException("InvalidSubnetID.NotFound", "The subnet ID '" + subnetId + "' does not exist", 400);

        return subnet;
    }

    private String assignPrivateIp(String region, String subnetId) {
        if (subnetId == null) {
            return "172.31.0." + (10 + new Random().nextInt(200));
        }
        AtomicInteger counter = subnetIpCounters.computeIfAbsent(region + "::" + subnetId, k -> new AtomicInteger(10));
        int offset = counter.getAndIncrement();
        Subnet subnet = subnets.get(key(region, subnetId)).orElse(null);
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
                getRequiredInstance(region, id);
            }
        }

        if (config.services().ec2().mock()) {
            instances.scan(k -> true).stream()
                    .filter(i -> i.getRegion().equals(region) && "pending".equals(i.getState().getName()))
                    .forEach(i -> i.setState(InstanceState.running()));
        }
        List<Instance> matched = instances.scan(k -> true).stream()
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
            Instance inst = getRequiredInstance(region, id);

            if (config.services().ec2().mock() && "pending".equals(inst.getState().getName())) {
                inst.setState(InstanceState.running());
            }
            InstanceState prev = inst.getState();
            if (config.services().ec2().mock()) {
                inst.setState(InstanceState.terminated());
                inst.setTerminatedAt(System.currentTimeMillis());
            } else {
                containerManager.terminate(inst);
            }
            // Delete root volume if deleteOnTermination (matches real AWS behavior)
            if (inst.getRootVolumeId() != null) {
                volumes.delete(key(region, inst.getRootVolumeId()));
            }
            instances.put(key(region, id), inst);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("instanceId", id);
            entry.put("previousState", prev.getName());
            entry.put("previousCode", String.valueOf(prev.getCode()));
            entry.put("currentState", "shutting-down");
            entry.put("currentCode", "32");
            result.add(entry);
        }
        return result;
    }

    public List<Map<String, String>> stopInstances(String region, List<String> instanceIds) {
        ensureDefaultResources(region);
        List<Map<String, String>> result = new ArrayList<>();
        for (String id : instanceIds) {
            Instance inst = getRequiredInstance(region, id);

            if (config.services().ec2().mock() && "pending".equals(inst.getState().getName())) {
                inst.setState(InstanceState.running());
            }
            InstanceState prev = inst.getState();
            if (config.services().ec2().mock()) {
                inst.setState(InstanceState.stopped());
            } else {
                containerManager.stop(inst);
            }
            instances.put(key(region, id), inst);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("instanceId", id);
            entry.put("previousState", prev.getName());
            entry.put("previousCode", String.valueOf(prev.getCode()));
            entry.put("currentState", "stopping");
            entry.put("currentCode", "64");
            result.add(entry);
        }
        return result;
    }

    public List<Map<String, String>> startInstances(String region, List<String> instanceIds) {
        ensureDefaultResources(region);
        List<Map<String, String>> result = new ArrayList<>();
        for (String id : instanceIds) {
           Instance inst = getRequiredInstance(region, id);

            if ("terminated".equals(inst.getState().getName())) {
                throw new AwsException("IncorrectInstanceState",
                        "The instance '" + id + "' is not in a state from which it can be started.", 400);
            }
            InstanceState prev = inst.getState();
            if (config.services().ec2().mock()) {
                inst.setState(InstanceState.running());
            } else {
                containerManager.start(inst);
            }
            instances.put(key(region, id), inst);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("instanceId", id);
            entry.put("previousState", prev.getName());
            entry.put("previousCode", String.valueOf(prev.getCode()));
            entry.put("currentState", "pending");
            entry.put("currentCode", "0");
            result.add(entry);
        }
        return result;
    }

    public void rebootInstances(String region, List<String> instanceIds) {
        ensureDefaultResources(region);
        for (String id : instanceIds) {
            Instance inst = getRequiredInstance(region, id);

            if (!config.services().ec2().mock()) {
                containerManager.reboot(inst);
            }
        }
    }

    /** Removes terminated instances older than 1 hour. Called periodically by lifecycle. */
    public void pruneTerminatedInstances() {
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        for (String storeKey : new ArrayList<>(instances.keys())) {
            Instance inst = instances.get(storeKey).orElse(null);
            if (inst != null
                    && "terminated".equals(inst.getState().getName())
                    && inst.getTerminatedAt() > 0
                    && inst.getTerminatedAt() < cutoff) {
                instances.delete(storeKey);
            }
        }
    }

    public List<Instance> describeInstanceStatus(String region, List<String> instanceIds) {
        ensureDefaultResources(region);
        if (config.services().ec2().mock()) {
            instances.scan(k -> true).stream()
                    .filter(i -> i.getRegion().equals(region) && "pending".equals(i.getState().getName()))
                    .filter(i -> instanceIds.isEmpty() || instanceIds.contains(i.getInstanceId()))
                    .forEach(i -> i.setState(InstanceState.running()));
        }
        return instances.scan(k -> true).stream()
                .filter(i -> i.getRegion().equals(region))
                .filter(i -> instanceIds.isEmpty() || instanceIds.contains(i.getInstanceId()))
                .filter(i -> "running".equals(i.getState().getName()))
                .collect(Collectors.toList());
    }

    public Instance describeInstanceAttribute(String region, String instanceId, String attribute) {
        ensureDefaultResources(region);
        Instance inst = getRequiredInstance(region, instanceId);

        return inst;
    }

    public void modifyInstanceAttribute(String region, String instanceId, String attribute, String value) {
        ensureDefaultResources(region);
        Instance inst = getRequiredInstance(region, instanceId);

        // basic attribute modifications
        switch (attribute) {
            case "instanceType" -> inst.setInstanceType(value);
            case "sourceDestCheck" -> inst.setSourceDestCheck(Boolean.parseBoolean(value));
            case "ebsOptimized" -> inst.setEbsOptimized(Boolean.parseBoolean(value));
        }
        instances.put(key(region, instanceId), inst);
    }

    private Instance getRequiredInstance(String region, String instanceId) {
        Instance inst = instances.get(key(region, instanceId)).orElse(null);
        if (inst == null)
            throw new AwsException("InvalidInstanceID.NotFound", "The instance ID '" + instanceId + "' does not exist", 400);

        return inst;
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

        createDefaultSecurityGroup(region, vpcId, "sg-" + randomHex(17));
        createMainRouteTable(region, vpc, "rtb-" + randomHex(17), "rtbassoc-" + randomHex(17));
        return vpc;
    }

    public List<Vpc> describeVpcs(String region, List<String> vpcIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        if (!vpcIds.isEmpty()) {
            for (String id : vpcIds) {
                getRequiredVpc(region, id);
            }
        }
        return vpcs.scan(k -> true).stream()
                .filter(v -> v.getRegion().equals(region))
                .filter(v -> vpcIds.isEmpty() || vpcIds.contains(v.getVpcId()))
                .filter(v -> matchesFilters(v, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteVpc(String region, String vpcId) {
        ensureDefaultResources(region);
        getRequiredVpc(region, vpcId);

        vpcs.delete(key(region, vpcId));
    }

    public void modifyVpcAttribute(String region, String vpcId, String attribute, String value) {
        ensureDefaultResources(region);
        Vpc vpc = getRequiredVpc(region, vpcId);

        switch (attribute) {
            case "enableDnsSupport"                    -> vpc.setEnableDnsSupport(Boolean.parseBoolean(value));
            case "enableDnsHostnames"                  -> vpc.setEnableDnsHostnames(Boolean.parseBoolean(value));
            case "enableNetworkAddressUsageMetrics"    -> vpc.setEnableNetworkAddressUsageMetrics(Boolean.parseBoolean(value));
        }
        vpcs.put(key(region, vpcId), vpc);
    }

    public Vpc describeVpcAttribute(String region, String vpcId, String attribute) {
        ensureDefaultResources(region);
        Vpc vpc = getRequiredVpc(region, vpcId);

        return vpc;
    }

    public Vpc createDefaultVpc(String region) {
        ensureDefaultResources(region);
        // Return existing default or create one
        return vpcs.scan(k -> true).stream()
                .filter(v -> v.getRegion().equals(region) && v.isDefault())
                .findFirst()
                .orElseGet(() -> createVpc(region, "172.31.0.0/16", true));
    }

    public VpcCidrBlockAssociation associateVpcCidrBlock(String region, String vpcId, String cidrBlock) {
        ensureDefaultResources(region);
        Vpc vpc = getRequiredVpc(region, vpcId);

        VpcCidrBlockAssociation assoc = new VpcCidrBlockAssociation(
                "vpc-cidr-assoc-" + randomHex(8), cidrBlock);
        vpc.getCidrBlockAssociationSet().add(assoc);
        vpcs.put(key(region, vpcId), vpc);
        return assoc;
    }

    public void disassociateVpcCidrBlock(String region, String associationId) {
        ensureDefaultResources(region);
        for (Vpc vpc : vpcs.scan(k -> true)) {
            if (vpc.getRegion().equals(region)) {
                vpc.getCidrBlockAssociationSet().removeIf(a -> a.getAssociationId().equals(associationId));
                vpcs.put(key(region, vpc.getVpcId()), vpc);
            }
        }
    }

    // ─── VPC Endpoints ────────────────────────────────────────────────────────

    public VpcEndpoint createVpcEndpoint(String region, String vpcId, String serviceName, String endpointType,
                                         List<String> routeTableIds, List<String> subnetIds,
                                         List<String> securityGroupIds, List<Tag> endpointTags) {
        ensureDefaultResources(region);
        getRequiredVpc(region, vpcId);
        for (String routeTableId : routeTableIds) {
            getRequiredRouteTable(region, routeTableId);
        }
        for (String subnetId : subnetIds) {
            requireSubnet(region, subnetId);
        }
        for (String securityGroupId : securityGroupIds) {
            getRequiredSecurityGroup(region, securityGroupId);
        }

        VpcEndpoint endpoint = new VpcEndpoint();
        endpoint.setVpcEndpointId("vpce-" + randomHex(17));
        endpoint.setVpcId(vpcId);
        endpoint.setServiceName(serviceName);
        endpoint.setVpcEndpointType(endpointType != null && !endpointType.isBlank() ? endpointType : "Gateway");
        endpoint.setCreationTimestamp(Instant.now());
        endpoint.setRegion(region);
        endpoint.setRouteTableIds(new ArrayList<>(routeTableIds));
        endpoint.setSubnetIds(new ArrayList<>(subnetIds));
        endpoint.setSecurityGroupIds(new ArrayList<>(securityGroupIds));
        if (endpointTags != null && !endpointTags.isEmpty()) {
            endpoint.setTags(new ArrayList<>(endpointTags));
            tags.put(endpoint.getVpcEndpointId(), new ArrayList<>(endpointTags));
        }
        vpcEndpoints.put(key(region, endpoint.getVpcEndpointId()), endpoint);
        return endpoint;
    }

    public List<VpcEndpoint> describeVpcEndpoints(String region, List<String> endpointIds,
                                                  Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        if (!endpointIds.isEmpty()) {
            for (String endpointId : endpointIds) {
                getRequiredVpcEndpoint(region, endpointId);
            }
        }
        return vpcEndpoints.scan(k -> true).stream()
                .filter(endpoint -> endpoint.getRegion().equals(region))
                .filter(endpoint -> endpointIds.isEmpty() || endpointIds.contains(endpoint.getVpcEndpointId()))
                .filter(endpoint -> matchesFilters(endpoint, filters, region))
                .collect(Collectors.toList());
    }

    public List<VpcEndpoint> deleteVpcEndpoints(String region, List<String> endpointIds) {
        ensureDefaultResources(region);
        List<VpcEndpoint> deleted = new ArrayList<>();
        for (String endpointId : endpointIds) {
            VpcEndpoint endpoint = getRequiredVpcEndpoint(region, endpointId);
            endpoint.setState("deleted");
            vpcEndpoints.delete(key(region, endpointId));
            tags.delete(endpointId);
            deleted.add(endpoint);
        }
        return deleted;
    }

    private VpcEndpoint getRequiredVpcEndpoint(String region, String endpointId) {
        VpcEndpoint endpoint = vpcEndpoints.get(key(region, endpointId)).orElse(null);
        if (endpoint == null) {
            throw new AwsException("InvalidVpcEndpointId.NotFound",
                    "The vpcEndpoint ID '" + endpointId + "' does not exist", 400);
        }
        return endpoint;
    }

    // ─── Subnets ───────────────────────────────────────────────────────────────

    public Subnet createSubnet(String region, String vpcId, String cidrBlock, String availabilityZone) {
        ensureDefaultResources(region);
        getRequiredVpc(region, vpcId);

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
        subnet.setSubnetArn(AwsArnUtils.Arn.of("ec2", region, accountId, "subnet/" + subnetId).toString());
        subnets.put(key(region, subnetId), subnet);
        return subnet;
    }

    public List<Subnet> describeSubnets(String region, List<String> subnetIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        return subnets.scan(k -> true).stream()
                .filter(s -> s.getRegion().equals(region))
                .filter(s -> subnetIds.isEmpty() || subnetIds.contains(s.getSubnetId()))
                .filter(s -> matchesFilters(s, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteSubnet(String region, String subnetId) {
        ensureDefaultResources(region);
        if (subnets.get(key(region, subnetId)).isEmpty()) {
            throw new AwsException("InvalidSubnetID.NotFound", "The subnet ID '" + subnetId + "' does not exist", 400);
        }
        subnets.delete(key(region, subnetId));
    }

    public void modifySubnetAttribute(String region, String subnetId, String attribute, String value) {
        ensureDefaultResources(region);
        Subnet subnet = requireSubnet(region, subnetId);
        switch (attribute) {
            case "mapPublicIpOnLaunch"           -> subnet.setMapPublicIpOnLaunch(Boolean.parseBoolean(value));
            case "assignIpv6AddressOnCreation"   -> subnet.setAssignIpv6AddressOnCreation(Boolean.parseBoolean(value));
            case "enableDns64"                   -> subnet.setEnableDns64(Boolean.parseBoolean(value));
            case "mapCustomerOwnedIpOnLaunch"    -> subnet.setMapCustomerOwnedIpOnLaunch(Boolean.parseBoolean(value));
        }
        subnets.put(key(region, subnetId), subnet);
    }

    // ─── Security Groups ───────────────────────────────────────────────────────

    public SecurityGroup createSecurityGroup(String region, String groupName, String description, String vpcId) {
        ensureDefaultResources(region);
        if (vpcId != null && !vpcId.isEmpty()) {
            getRequiredVpc(region, vpcId);
        } else {
            vpcId = "vpc-default";
        }
        // Check duplicate
        String finalVpcId = vpcId;
        boolean exists = securityGroups.scan(k -> true).stream()
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
        return securityGroups.scan(k -> true).stream()
                .filter(sg -> sg.getRegion().equals(region))
                .filter(sg -> groupIds.isEmpty() || groupIds.contains(sg.getGroupId()))
                .filter(sg -> groupNames.isEmpty() || groupNames.contains(sg.getGroupName()))
                .filter(sg -> matchesFilters(sg, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteSecurityGroup(String region, String groupId) {
        ensureDefaultResources(region);
        if (securityGroups.get(key(region, groupId)).isEmpty()) {
            throw new AwsException("InvalidGroup.NotFound", "The security group '" + groupId + "' does not exist", 400);
        }
        securityGroups.delete(key(region, groupId));
    }

    public List<SecurityGroupRule> authorizeSecurityGroupIngress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        SecurityGroup sg = getRequiredSecurityGroup(region, groupId);

        List<SecurityGroupRule> rules = new ArrayList<>();
        for (IpPermission perm : permissions) {
            sg.getIpPermissions().add(perm);
            rules.addAll(createRules(region, groupId, perm, false));
        }
        securityGroups.put(key(region, groupId), sg);
        return rules;
    }

    public List<SecurityGroupRule> authorizeSecurityGroupEgress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        SecurityGroup sg = getRequiredSecurityGroup(region, groupId);

        List<SecurityGroupRule> rules = new ArrayList<>();
        for (IpPermission perm : permissions) {
            sg.getIpPermissionsEgress().add(perm);
            rules.addAll(createRules(region, groupId, perm, true));
        }
        securityGroups.put(key(region, groupId), sg);
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
        SecurityGroup sg = getRequiredSecurityGroup(region, groupId);

        sg.getIpPermissions().removeIf(p -> matchesAnyPermission(p, permissions));
        securityGroups.put(key(region, groupId), sg);
    }

    public void revokeSecurityGroupEgress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        SecurityGroup sg = getRequiredSecurityGroup(region, groupId);

        sg.getIpPermissionsEgress().removeIf(p -> matchesAnyPermission(p, permissions));
        securityGroups.put(key(region, groupId), sg);
    }

    private SecurityGroup getRequiredSecurityGroup(String region, String groupId) {
        SecurityGroup sg = securityGroups.get(key(region, groupId)).orElse(null);
        if (sg == null)
            throw new AwsException("InvalidGroup.NotFound", "The security group '" + groupId + "' does not exist", 400);

        return sg;
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
        return securityGroupRules.scan(k -> true).stream()
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
                SecurityGroupRule rule = securityGroupRules.get(key(region, ruleId)).orElse(null);
                if (rule != null && desc != null) {
                    rule.setDescription(desc);
                    securityGroupRules.put(key(region, ruleId), rule);
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
        boolean exists = keyPairs.scan(k -> true).stream()
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
        return keyPairs.scan(k -> true).stream()
                .filter(k -> k.getRegion().equals(region))
                .filter(k -> keyNames.isEmpty() || keyNames.contains(k.getKeyName()))
                .filter(k -> keyPairIds.isEmpty() || keyPairIds.contains(k.getKeyPairId()))
                .collect(Collectors.toList());
    }

    public void deleteKeyPair(String region, String keyName, String keyPairId) {
        ensureDefaultResources(region);
        if (keyPairId != null && !keyPairId.isEmpty()) {
            keyPairs.delete(key(region, keyPairId));
        } else {
            keyPairs.scan(k -> true).removeIf(k -> k.getRegion().equals(region) && k.getKeyName().equals(keyName));
        }
    }

    public KeyPair importKeyPair(String region, String keyName, String publicKeyMaterial) {
        ensureDefaultResources(region);
        String keyPairId = "key-" + randomHex(17);
        KeyPair kp = new KeyPair();
        kp.setKeyPairId(keyPairId);
        kp.setKeyName(keyName);
        kp.setKeyFingerprint("00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00");
        kp.setPublicKey(publicKeyMaterial);
        kp.setRegion(region);
        keyPairs.put(key(region, keyPairId), kp);
        return kp;
    }

    public Instance findInstanceById(String instanceId) {
        return instances.scan(k -> true).stream()
                .filter(i -> instanceId.equals(i.getInstanceId()))
                .findFirst()
                .orElse(null);
    }

    public boolean isInstanceContainerRunning(String instanceId) {
        Instance instance = findInstanceById(instanceId);
        return instance != null && containerManager.isContainerRunning(instance.getDockerContainerId());
    }

    public KeyPair findKeyPair(String region, String keyName) {
        if (keyName == null) {
            return null;
        }
        return keyPairs.scan(k -> true).stream()
                .filter(k -> k.getRegion().equals(region) && keyName.equals(k.getKeyName()))
                .findFirst()
                .orElse(null);
    }

    // ─── AMIs ──────────────────────────────────────────────────────────────────

    public List<Image> describeImages(String region, List<String> imageIds, List<String> owners) {
        return describeImages(region, imageIds, owners, Map.of());
    }

    public List<Image> describeImages(String region, List<String> imageIds, List<String> owners, Map<String, List<String>> filters) {
        return imageCatalog.images().stream()
                .filter(Ec2ImageCatalog.CatalogImage::advertised)
                .filter(img -> img.matchesIdOrAlias(imageIds))
                .filter(img -> img.matchesOwner(owners))
                .filter(img -> matchesImageFilters(img, filters))
                .map(Ec2ImageCatalog.CatalogImage::toImage)
                .collect(Collectors.toList());
    }

    // ─── Launch Templates ─────────────────────────────────────────────────────

    public LaunchTemplate createLaunchTemplate(String region, String name, String imageId,
                                               String instanceType, String keyName,
                                               List<String> securityGroupIds, String userData,
                                               List<Tag> launchTemplateTags) {
        ensureDefaultResources(region);
        if (name == null || name.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter LaunchTemplateName", 400);
        }
        boolean exists = launchTemplates.scan(k -> true).stream()
                .anyMatch(lt -> lt.getRegion().equals(region) && name.equals(lt.getLaunchTemplateName()));
        if (exists) {
            throw new AwsException("InvalidLaunchTemplateName.AlreadyExistsException",
                    "Launch template name already in use.", 400);
        }

        LaunchTemplate launchTemplate = new LaunchTemplate();
        launchTemplate.setLaunchTemplateId("lt-" + randomHex(17));
        launchTemplate.setLaunchTemplateName(name);
        launchTemplate.setCreateTime(Instant.now());
        launchTemplate.setCreatedBy("arn:aws:iam::" + accountId + ":root");
        launchTemplate.setRegion(region);
        launchTemplate.setImageId(imageId);
        launchTemplate.setInstanceType(instanceType);
        launchTemplate.setKeyName(keyName);
        launchTemplate.setUserData(userData);
        if (securityGroupIds != null) {
            launchTemplate.setSecurityGroupIds(new ArrayList<>(securityGroupIds));
        }
        if (launchTemplateTags != null && !launchTemplateTags.isEmpty()) {
            launchTemplate.setTags(new ArrayList<>(launchTemplateTags));
            tags.put(launchTemplate.getLaunchTemplateId(), new ArrayList<>(launchTemplateTags));
        }
        launchTemplate.getVersions().put("1", dataFrom(launchTemplate));
        launchTemplates.put(key(region, launchTemplate.getLaunchTemplateId()), launchTemplate);
        return launchTemplate;
    }

    public LaunchTemplate createLaunchTemplateVersion(String region, String id, String name,
                                                      String imageId, String instanceType, String keyName,
                                                      List<String> securityGroupIds, String userData,
                                                      String sourceVersion) {
        ensureDefaultResources(region);
        LaunchTemplate launchTemplate = findLaunchTemplate(region, id, name);
        int latestVersion = parseLaunchTemplateVersion(launchTemplate.getLatestVersionNumber()) + 1;
        LaunchTemplateData data = new LaunchTemplateData(versionData(launchTemplate,
                resolveLaunchTemplateVersion(launchTemplate, sourceVersion, launchTemplate.getLatestVersionNumber())));
        launchTemplate.setLatestVersionNumber(String.valueOf(latestVersion));
        if (imageId != null && !imageId.isBlank()) {
            data.setImageId(imageId);
        }
        if (instanceType != null && !instanceType.isBlank()) {
            data.setInstanceType(instanceType);
        }
        if (keyName != null && !keyName.isBlank()) {
            data.setKeyName(keyName);
        }
        if (userData != null && !userData.isBlank()) {
            data.setUserData(userData);
        }
        if (securityGroupIds != null && !securityGroupIds.isEmpty()) {
            data.setSecurityGroupIds(securityGroupIds);
        }
        launchTemplate.getVersions().put(String.valueOf(latestVersion), data);
        applyData(launchTemplate, data);
        return launchTemplate;
    }

    public List<LaunchTemplate> describeLaunchTemplateVersions(String region, String id, String name,
                                                               List<String> requestedVersions) {
        List<LaunchTemplate> templates = describeLaunchTemplates(
                region,
                id != null && !id.isBlank() ? List.of(id) : List.of(),
                name != null && !name.isBlank() ? List.of(name) : List.of(),
                Map.of());
        List<LaunchTemplate> versions = new ArrayList<>();
        for (LaunchTemplate launchTemplate : templates) {
            List<String> effectiveVersions = requestedVersions == null || requestedVersions.isEmpty()
                    ? List.of(launchTemplate.getLatestVersionNumber())
                    : requestedVersions;
            for (String requestedVersion : effectiveVersions) {
                String resolvedVersion = resolveLaunchTemplateVersion(
                        launchTemplate, requestedVersion, launchTemplate.getLatestVersionNumber());
                versions.add(copyForVersion(launchTemplate, resolvedVersion));
            }
        }
        return versions;
    }

    public LaunchTemplate modifyLaunchTemplate(String region, String id, String name, String defaultVersion) {
        ensureDefaultResources(region);
        LaunchTemplate launchTemplate = findLaunchTemplate(region, id, name);
        if (defaultVersion != null && !defaultVersion.isBlank()) {
            String resolved = switch (defaultVersion) {
                case "$Latest" -> launchTemplate.getLatestVersionNumber();
                case "$Default" -> launchTemplate.getDefaultVersionNumber();
                default -> defaultVersion;
            };
            int requested = parseLaunchTemplateVersion(resolved);
            int latest = parseLaunchTemplateVersion(launchTemplate.getLatestVersionNumber());
            if (requested < 1 || requested > latest) {
                throw new AwsException("InvalidLaunchTemplateVersion.NotFound",
                        "The specified launch template version does not exist.", 400);
            }
            launchTemplate.setDefaultVersionNumber(String.valueOf(requested));
        }
        return launchTemplate;
    }

    public List<LaunchTemplate> describeLaunchTemplates(String region, List<String> ids,
                                                        List<String> names, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        return launchTemplates.scan(k -> true).stream()
                .filter(lt -> lt.getRegion().equals(region))
                .filter(lt -> ids.isEmpty() || ids.contains(lt.getLaunchTemplateId()))
                .filter(lt -> names.isEmpty() || names.contains(lt.getLaunchTemplateName()))
                .filter(lt -> matchesFilters(lt, filters, region))
                .collect(Collectors.toList());
    }

    public LaunchTemplate deleteLaunchTemplate(String region, String id, String name) {
        ensureDefaultResources(region);
        LaunchTemplate launchTemplate = findLaunchTemplate(region, id, name);
        launchTemplates.delete(key(region, launchTemplate.getLaunchTemplateId()));
        tags.delete(launchTemplate.getLaunchTemplateId());
        return launchTemplate;
    }

    private LaunchTemplate findLaunchTemplate(String region, String id, String name) {
        if (id != null && !id.isBlank()) {
            LaunchTemplate launchTemplate = launchTemplates.get(key(region, id)).orElse(null);
            if (launchTemplate != null) {
                return launchTemplate;
            }
        } else if (name != null && !name.isBlank()) {
            return launchTemplates.scan(k -> true).stream()
                    .filter(lt -> lt.getRegion().equals(region) && name.equals(lt.getLaunchTemplateName()))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("InvalidLaunchTemplateName.NotFoundException",
                            "The specified launch template does not exist.", 400));
        }
        throw new AwsException("InvalidLaunchTemplateId.NotFoundException",
                "The specified launch template does not exist.", 400);
    }

    private int parseLaunchTemplateVersion(String version) {
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidLaunchTemplateVersion.Malformed",
                    "The specified launch template version is not valid.", 400);
        }
    }

    private String resolveLaunchTemplateVersion(LaunchTemplate launchTemplate, String requestedVersion,
                                                String defaultWhenMissing) {
        if (launchTemplate.getVersions().isEmpty()) {
            launchTemplate.getVersions().put(launchTemplate.getLatestVersionNumber(), dataFrom(launchTemplate));
        }
        String candidate = requestedVersion == null || requestedVersion.isBlank() ? defaultWhenMissing : requestedVersion;
        String resolved = switch (candidate) {
            case "$Latest" -> launchTemplate.getLatestVersionNumber();
            case "$Default" -> launchTemplate.getDefaultVersionNumber();
            default -> candidate;
        };
        int requested = parseLaunchTemplateVersion(resolved);
        int latest = parseLaunchTemplateVersion(launchTemplate.getLatestVersionNumber());
        if (requested < 1 || requested > latest || !launchTemplate.getVersions().containsKey(resolved)) {
            throw new AwsException("InvalidLaunchTemplateVersion.NotFound",
                    "The specified launch template version does not exist.", 400);
        }
        return resolved;
    }

    private LaunchTemplateData versionData(LaunchTemplate launchTemplate, String version) {
        return launchTemplate.getVersions().get(version);
    }

    private LaunchTemplateData dataFrom(LaunchTemplate launchTemplate) {
        LaunchTemplateData data = new LaunchTemplateData();
        data.setImageId(launchTemplate.getImageId());
        data.setInstanceType(launchTemplate.getInstanceType());
        data.setKeyName(launchTemplate.getKeyName());
        data.setUserData(launchTemplate.getUserData());
        data.setSecurityGroupIds(launchTemplate.getSecurityGroupIds());
        return data;
    }

    private void applyData(LaunchTemplate launchTemplate, LaunchTemplateData data) {
        launchTemplate.setImageId(data.getImageId());
        launchTemplate.setInstanceType(data.getInstanceType());
        launchTemplate.setKeyName(data.getKeyName());
        launchTemplate.setUserData(data.getUserData());
        launchTemplate.setSecurityGroupIds(new ArrayList<>(data.getSecurityGroupIds()));
    }

    private LaunchTemplate copyForVersion(LaunchTemplate source, String versionNumber) {
        LaunchTemplate copy = new LaunchTemplate();
        copy.setLaunchTemplateId(source.getLaunchTemplateId());
        copy.setLaunchTemplateName(source.getLaunchTemplateName());
        copy.setDefaultVersionNumber(source.getDefaultVersionNumber());
        copy.setLatestVersionNumber(versionNumber);
        copy.setCreateTime(source.getCreateTime());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setRegion(source.getRegion());
        copy.setTags(source.getTags());
        applyData(copy, versionData(source, versionNumber));
        return copy;
    }

    private boolean matchesImageFilters(Ec2ImageCatalog.CatalogImage image, Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
            if (!matchesImageFilter(image, filter.getKey(), filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesImageFilter(Ec2ImageCatalog.CatalogImage catalogImage, String name, List<String> values) {
        Image image = catalogImage.toImage();
        return switch (name) {
            case "architecture" -> matchesFilterValue(values, image.getArchitecture());
            case "hypervisor" -> matchesFilterValue(values, image.getHypervisor());
            case "image-id" -> catalogImage.idsAndAliases().stream().anyMatch(id -> matchesFilterValue(values, id));
            case "image-type" -> matchesFilterValue(values, "machine");
            case "is-public" -> matchesFilterValue(values, String.valueOf(image.isPublic()));
            case "name" -> matchesFilterValue(values, image.getName());
            case "owner-alias" -> matchesFilterValue(values, image.getImageOwnerAlias());
            case "owner-id" -> matchesFilterValue(values, image.getOwnerId());
            case "root-device-name" -> matchesFilterValue(values, image.getRootDeviceName());
            case "root-device-type" -> matchesFilterValue(values, image.getRootDeviceType());
            case "state" -> matchesFilterValue(values, image.getState());
            case "virtualization-type" -> matchesFilterValue(values, image.getVirtualizationType());
            default -> true;
        };
    }

    private boolean matchesFilterValue(List<String> patterns, String value) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return patterns.stream().anyMatch(pattern -> wildcardMatches(pattern, value));
    }

    private boolean wildcardMatches(String pattern, String value) {
        if (pattern == null) {
            return false;
        }
        if (!pattern.contains("*")) {
            return pattern.equals(value);
        }
        String regex = pattern.chars()
                .mapToObj(ch -> ch == '*' ? ".*" : java.util.regex.Pattern.quote(String.valueOf((char) ch)))
                .collect(Collectors.joining());
        return value.matches(regex);
    }

    // ─── Tags ──────────────────────────────────────────────────────────────────

    public void createTags(String region, List<String> resourceIds, List<Tag> tagList) {
        ensureDefaultResources(region);
        for (String resourceId : resourceIds) {
            List<Tag> existing = tags.get(resourceId).orElse(new ArrayList<>());
            for (Tag tag : tagList) {
                existing.removeIf(t -> t.getKey().equals(tag.getKey()));
                existing.add(tag);
            }
            tags.put(resourceId, existing);
            // Update resource objects
            updateResourceTags(region, resourceId, existing);
        }
    }

    public void deleteTags(String region, List<String> resourceIds, List<Tag> tagList) {
        ensureDefaultResources(region);
        for (String resourceId : resourceIds) {
            List<Tag> existing = tags.get(resourceId).orElse(null);
            if (existing != null) {
                for (Tag tag : tagList) {
                    existing.removeIf(t -> t.getKey().equals(tag.getKey())
                            && (tag.getValue() == null || tag.getValue().equals(t.getValue())));
                }
                tags.put(resourceId, existing);
                updateResourceTags(region, resourceId, existing);
            }
        }
    }

    private void updateResourceTags(String region, String resourceId, List<Tag> tagList) {
        String storeKey = key(region, resourceId);
        Instance inst = instances.get(storeKey).orElse(null);
        if (inst != null) { inst.setTags(new ArrayList<>(tagList)); instances.put(storeKey, inst); return; }
        Vpc vpc = vpcs.get(storeKey).orElse(null);
        if (vpc != null) { vpc.setTags(new ArrayList<>(tagList)); vpcs.put(storeKey, vpc); return; }
        Subnet subnet = subnets.get(storeKey).orElse(null);
        if (subnet != null) { subnet.setTags(new ArrayList<>(tagList)); subnets.put(storeKey, subnet); return; }
        SecurityGroup sg = securityGroups.get(storeKey).orElse(null);
        if (sg != null) { sg.setTags(new ArrayList<>(tagList)); securityGroups.put(storeKey, sg); return; }
        InternetGateway igw = internetGateways.get(storeKey).orElse(null);
        if (igw != null) { igw.setTags(new ArrayList<>(tagList)); internetGateways.put(storeKey, igw); return; }
        RouteTable rt = routeTables.get(storeKey).orElse(null);
        if (rt != null) { rt.setTags(new ArrayList<>(tagList)); routeTables.put(storeKey, rt); return; }
        KeyPair kp = keyPairs.get(storeKey).orElse(null);
        if (kp != null) { kp.setTags(new ArrayList<>(tagList)); keyPairs.put(storeKey, kp); return; }
        LaunchTemplate lt = launchTemplates.get(storeKey).orElse(null);
        if (lt != null) { lt.setTags(new ArrayList<>(tagList)); launchTemplates.put(storeKey, lt); return; }
        VpcEndpoint endpoint = vpcEndpoints.get(storeKey).orElse(null);
        if (endpoint != null) { endpoint.setTags(new ArrayList<>(tagList)); vpcEndpoints.put(storeKey, endpoint); return; }
        NatGateway natGateway = natGateways.get(storeKey).orElse(null);
        if (natGateway != null) { natGateway.setTags(new ArrayList<>(tagList)); natGateways.put(storeKey, natGateway); }
    }

    public List<Map<String, String>> describeTags(String region, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        List<String> filterResourceIds   = filters != null ? filters.get("resource-id")   : null;
        List<String> filterResourceTypes = filters != null ? filters.get("resource-type") : null;
        List<String> filterKeys          = filters != null ? filters.get("key")            : null;
        List<String> filterValues        = filters != null ? filters.get("value")          : null;

        List<Map<String, String>> result = new ArrayList<>();
        for (String resourceId : new ArrayList<>(tags.keys())) {
            String resourceType = inferResourceType(resourceId);

            if (filterResourceIds != null && !filterResourceIds.contains(resourceId)) {
                continue;
            }
            if (filterResourceTypes != null && !filterResourceTypes.contains(resourceType)) {
                continue;
            }
            for (Tag tag : tags.get(resourceId).orElse(List.of())) {
                if (filterKeys != null && !filterKeys.contains(tag.getKey())) {
                    continue;
                }
                if (filterValues != null && !filterValues.contains(tag.getValue())) {
                    continue;
                }
                Map<String, String> item = new LinkedHashMap<>();
                item.put("resourceId", resourceId);
                item.put("resourceType", resourceType);
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
        if (resourceId.startsWith("lt-")) return "launch-template";
        if (resourceId.startsWith("vpce-")) return "vpc-endpoint";
        if (resourceId.startsWith("nat-")) return "natgateway";
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
        return internetGateways.scan(k -> true).stream()
                .filter(igw -> igw.getRegion().equals(region))
                .filter(igw -> igwIds.isEmpty() || igwIds.contains(igw.getInternetGatewayId()))
                .filter(igw -> matchesFilters(igw, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteInternetGateway(String region, String igwId) {
        ensureDefaultResources(region);
        if (internetGateways.get(key(region, igwId)).isEmpty()) {
            throw new AwsException("InvalidInternetGatewayID.NotFound", "The internet gateway '" + igwId + "' does not exist", 400);
        }
        internetGateways.delete(key(region, igwId));
    }

    public void attachInternetGateway(String region, String igwId, String vpcId) {
        ensureDefaultResources(region);
        InternetGateway igw = getRequiredInternetGateway(region, igwId);

        igw.getAttachments().add(new InternetGatewayAttachment(vpcId, "available"));
        internetGateways.put(key(region, igwId), igw);
    }

    public void detachInternetGateway(String region, String igwId, String vpcId) {
        ensureDefaultResources(region);
        InternetGateway igw = getRequiredInternetGateway(region, igwId);

        igw.getAttachments().removeIf(a -> a.getVpcId().equals(vpcId));
        internetGateways.put(key(region, igwId), igw);
    }

    private InternetGateway getRequiredInternetGateway(String region, String igwId) {
        InternetGateway igw = internetGateways.get(key(region, igwId)).orElse(null);
        if (igw == null)
            throw new AwsException("InvalidInternetGatewayID.NotFound", "The internet gateway '" + igwId + "' does not exist", 400);

        return igw;
    }

    // ─── Route Tables ──────────────────────────────────────────────────────────

    public RouteTable createRouteTable(String region, String vpcId) {
        ensureDefaultResources(region);
        Vpc vpc = getRequiredVpc(region, vpcId);

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

    private Vpc getRequiredVpc(String region, String vpcId) {
        Vpc vpc = vpcs.get(key(region, vpcId)).orElse(null);
        if (vpc == null)
            throw new AwsException("InvalidVpcID.NotFound", "The vpc ID '" + vpcId + "' does not exist", 400);

        return vpc;
    }

    public List<RouteTable> describeRouteTables(String region, List<String> routeTableIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        return routeTables.scan(k -> true).stream()
                .filter(rt -> rt.getRegion().equals(region))
                .filter(rt -> routeTableIds.isEmpty() || routeTableIds.contains(rt.getRouteTableId()))
                .filter(rt -> matchesFilters(rt, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteRouteTable(String region, String routeTableId) {
        ensureDefaultResources(region);
        if (routeTables.get(key(region, routeTableId)).isEmpty()) {
            throw new AwsException("InvalidRouteTableID.NotFound", "The route table '" + routeTableId + "' does not exist", 400);
        }
        routeTables.delete(key(region, routeTableId));
    }

    public RouteTableAssociation associateRouteTable(String region, String routeTableId, String subnetId) {
        ensureDefaultResources(region);
        RouteTable rt = getRequiredRouteTable(region, routeTableId);

        String assocId = "rtbassoc-" + randomHex(8);
        RouteTableAssociation assoc = new RouteTableAssociation();
        assoc.setRouteTableAssociationId(assocId);
        assoc.setRouteTableId(routeTableId);
        assoc.setSubnetId(subnetId);
        assoc.setMain(false);
        assoc.setAssociationState("associated");
        rt.getAssociations().add(assoc);
        routeTables.put(key(region, routeTableId), rt);
        return assoc;
    }

    public void disassociateRouteTable(String region, String associationId) {
        ensureDefaultResources(region);
        for (RouteTable rt : routeTables.scan(k -> true)) {
            if (rt.getRegion().equals(region)) {
                rt.getAssociations().removeIf(a -> a.getRouteTableAssociationId().equals(associationId));
                routeTables.put(key(region, rt.getRouteTableId()), rt);
            }
        }
    }

    public void createRoute(String region, String routeTableId, String destinationCidrBlock, String gatewayId) {
        ensureDefaultResources(region);
        RouteTable rt = getRequiredRouteTable(region, routeTableId);

        rt.getRoutes().add(new Route(destinationCidrBlock, gatewayId, "CreateRoute"));
        routeTables.put(key(region, routeTableId), rt);
    }

    public void deleteRoute(String region, String routeTableId, String destinationCidrBlock) {
        ensureDefaultResources(region);
        RouteTable rt = getRequiredRouteTable(region, routeTableId);

        rt.getRoutes().removeIf(r -> r.getDestinationCidrBlock().equals(destinationCidrBlock));
        routeTables.put(key(region, routeTableId), rt);
    }

    private RouteTable getRequiredRouteTable(String region, String routeTableId) {
        RouteTable rt = routeTables.get(key(region, routeTableId)).orElse(null);
        if (rt == null)
            throw new AwsException("InvalidRouteTableID.NotFound", "The route table '" + routeTableId + "' does not exist", 400);

        return rt;
    }

    // ─── NAT Gateways ─────────────────────────────────────────────────────────

    public NatGateway createNatGateway(String region, String subnetId, String allocationId,
                                       String connectivityType, List<Tag> natGatewayTags) {
        ensureDefaultResources(region);
        Subnet subnet = requireSubnet(region, subnetId);
        if (allocationId != null && !allocationId.isBlank()) {
            getRequiredAddress(region, allocationId);
        }

        NatGateway natGateway = new NatGateway();
        natGateway.setNatGatewayId("nat-" + randomHex(17));
        natGateway.setSubnetId(subnetId);
        natGateway.setVpcId(subnet.getVpcId());
        natGateway.setAllocationId(allocationId);
        natGateway.setConnectivityType(connectivityType != null && !connectivityType.isBlank() ? connectivityType : "public");
        natGateway.setCreateTime(Instant.now());
        natGateway.setRegion(region);
        if (natGatewayTags != null && !natGatewayTags.isEmpty()) {
            natGateway.setTags(new ArrayList<>(natGatewayTags));
            tags.put(natGateway.getNatGatewayId(), new ArrayList<>(natGatewayTags));
        }
        natGateways.put(key(region, natGateway.getNatGatewayId()), natGateway);
        return natGateway;
    }

    public List<NatGateway> describeNatGateways(String region, List<String> natGatewayIds,
                                                Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        if (!natGatewayIds.isEmpty()) {
            for (String natGatewayId : natGatewayIds) {
                getRequiredNatGateway(region, natGatewayId);
            }
        }
        return natGateways.scan(k -> true).stream()
                .filter(natGateway -> natGateway.getRegion().equals(region))
                .filter(natGateway -> natGatewayIds.isEmpty()
                        || natGatewayIds.contains(natGateway.getNatGatewayId()))
                .filter(natGateway -> matchesFilters(natGateway, filters, region))
                .collect(Collectors.toList());
    }

    public NatGateway deleteNatGateway(String region, String natGatewayId) {
        ensureDefaultResources(region);
        NatGateway natGateway = getRequiredNatGateway(region, natGatewayId);
        natGateway.setState("deleted");
        natGateways.delete(key(region, natGatewayId));
        tags.delete(natGatewayId);
        return natGateway;
    }

    private NatGateway getRequiredNatGateway(String region, String natGatewayId) {
        NatGateway natGateway = natGateways.get(key(region, natGatewayId)).orElse(null);
        if (natGateway == null) {
            throw new AwsException("NatGatewayNotFound",
                    "NatGateway " + natGatewayId + " was not found", 400);
        }
        return natGateway;
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
        Address addr = getRequiredAddress(region, allocationId);

        addr.setInstanceId(instanceId);
        addr.setAssociationId("eipassoc-" + randomHex(17));
        addresses.put(key(region, allocationId), addr);
        return addr;
    }

    private Address getRequiredAddress(String region, String allocationId) {
        Address addr = addresses.get(key(region, allocationId)).orElse(null);
        if (addr == null)
            throw new AwsException("InvalidAllocationID.NotFound", "The allocation ID '" + allocationId + "' does not exist", 400);

        return addr;
    }

    public void disassociateAddress(String region, String associationId) {
        ensureDefaultResources(region);
        for (Address addr : addresses.scan(k -> true)) {
            if (addr.getRegion().equals(region) && associationId.equals(addr.getAssociationId())) {
                addr.setInstanceId(null);
                addr.setAssociationId(null);
                addresses.put(key(region, addr.getAllocationId()), addr);
                return;
            }
        }
    }

    public void releaseAddress(String region, String allocationId) {
        ensureDefaultResources(region);
        if (addresses.get(key(region, allocationId)).isEmpty()) {
            throw new AwsException("InvalidAllocationID.NotFound", "The allocation ID '" + allocationId + "' does not exist", 400);
        }
        addresses.delete(key(region, allocationId));
    }

    public List<Address> describeAddresses(String region, List<String> allocationIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        return addresses.scan(k -> true).stream()
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
        allTypes.add(buildInstanceType("t4g.micro", 2, 1024, List.of("arm64")));
        allTypes.add(buildInstanceType("t4g.small", 2, 2048, List.of("arm64")));
        allTypes.add(buildInstanceType("t4g.medium", 2, 4096, List.of("arm64")));
        allTypes.add(buildInstanceType("m6gd.2xlarge", 8, 32768, List.of("arm64")));
        allTypes.add(buildInstanceType("m7gd.2xlarge", 8, 32768, List.of("arm64")));
        allTypes.add(buildInstanceType("m8gd.medium", 1, 4096, List.of("arm64")));
        allTypes.add(buildInstanceType("m8gd.2xlarge", 8, 32768, List.of("arm64")));

        if (instanceTypeNames.isEmpty()) {
            return allTypes;
        }
        return allTypes.stream()
                .filter(t -> instanceTypeNames.contains(t.get("instanceType")))
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildInstanceType(String name, int vcpu, int memMib) {
        return buildInstanceType(name, vcpu, memMib, List.of("x86_64"));
    }

    private Map<String, Object> buildInstanceType(String name, int vcpu, int memMib,
                                                  List<String> supportedArchitectures) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("instanceType", name);
        t.put("vcpu", vcpu);
        t.put("memoryMib", memMib);
        t.put("supportedArchitectures", supportedArchitectures);
        t.put("currentGeneration", true);
        return t;
    }

    public List<Map<String, String>> describeInstanceTypeOfferings(String region, List<String> instanceTypeNames,
                                                                   String locationType,
                                                                   Map<String, List<String>> filters) {
        List<String> effectiveTypeNames = new ArrayList<>(new LinkedHashSet<>(instanceTypeNames));
        if (filters != null && filters.containsKey("instance-type")) {
            effectiveTypeNames.addAll(filters.get("instance-type"));
            effectiveTypeNames = new ArrayList<>(new LinkedHashSet<>(effectiveTypeNames));
        }
        String effectiveLocationType = locationType != null && !locationType.isBlank()
                ? locationType
                : "availability-zone";
        List<String> locations = "region".equals(effectiveLocationType)
                ? List.of(region)
                : describeAvailabilityZones(region).stream()
                        .map(zone -> zone.get("zoneName"))
                        .toList();
        List<String> locationFilter = filters != null ? filters.get("location") : null;

        List<Map<String, String>> offerings = new ArrayList<>();
        for (Map<String, Object> type : describeInstanceTypes(effectiveTypeNames)) {
            String instanceType = (String) type.get("instanceType");
            for (String location : locations) {
                if (locationFilter != null && !matchesValue(location, locationFilter)) {
                    continue;
                }
                Map<String, String> offering = new LinkedHashMap<>();
                offering.put("instanceType", instanceType);
                offering.put("locationType", effectiveLocationType);
                offering.put("location", location);
                offerings.add(offering);
            }
        }
        return offerings;
    }

    // ─── Filter matching ───────────────────────────────────────────────────────

    private boolean matchesValue(String resourceValue, List<String> filterValues) {
        String normalizedResourceValue = Objects.toString(resourceValue, "");
        return filterValues.stream()
                .map(filterValue -> Objects.toString(filterValue, ""))
                .anyMatch(filterValue -> normalizedResourceValue.matches(wildcardToRegex(filterValue)));
    }

    private String wildcardToRegex(String pattern) {
        String normalizedPattern = Objects.toString(pattern, "");
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < normalizedPattern.length(); i++) {
            char c = normalizedPattern.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append(".");
                    break;
                case '.':
                case '\\':
                case '^':
                case '$':
                case '+':
                case '{':
                case '}':
                case '[':
                case ']':
                case '(':
                case ')':
                case '|':
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        regex.append("$");
        return regex.toString();
    }

    private boolean matchesValue(List<String> patterns, String value) {
        return patterns.stream()
                .anyMatch(pattern -> value.matches(wildcardToRegex(pattern)));
    }

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
                    .anyMatch(t -> t.getKey().equals(tagKey) && matchesValue(values, t.getValue()));
        }
        if ("tag-key".equals(filterName)) {
            List<Tag> resourceTags = getResourceTags(resource);
            return resourceTags.stream().anyMatch(t -> matchesValue(values, t.getKey()));
        }
        if ("tag-value".equals(filterName)) {
            List<Tag> resourceTags = getResourceTags(resource);
            return resourceTags.stream().anyMatch(t -> matchesValue(values, t.getValue()));
        }
        // Resource-specific field filters
        if (resource instanceof Vpc vpc) {
            return switch (filterName) {
                case "vpc-id" -> matchesValue(values, vpc.getVpcId());
                case "state" -> matchesValue(values, vpc.getState());
                case "isDefault", "is-default" -> matchesValue(values, String.valueOf(vpc.isDefault()));
                case "cidr" -> matchesValue(values, vpc.getCidrBlock());
                default -> true;
            };
        }
        if (resource instanceof Subnet subnet) {
            return switch (filterName) {
                case "subnet-id" -> matchesValue(values, subnet.getSubnetId());
                case "vpc-id" -> matchesValue(values, subnet.getVpcId());
                case "state" -> matchesValue(values, subnet.getState());
                case "availabilityZone", "availability-zone" -> matchesValue(values, subnet.getAvailabilityZone());
                default -> true;
            };
        }
        if (resource instanceof SecurityGroup sg) {
            return switch (filterName) {
                case "group-id" -> matchesValue(values, sg.getGroupId());
                case "group-name" -> matchesValue(values, sg.getGroupName());
                case "vpc-id" -> matchesValue(values, sg.getVpcId());
                default -> true;
            };
        }
        if (resource instanceof Instance inst) {
            return switch (filterName) {
                case "instance-id" -> matchesValue(values, inst.getInstanceId());
                case "instance-state-name" -> matchesValue(values, inst.getState().getName());
                case "instance-type" -> matchesValue(values, inst.getInstanceType());
                case "vpc-id" -> matchesValue(values, inst.getVpcId());
                case "subnet-id" -> matchesValue(values, inst.getSubnetId());
                default -> true;
            };
        }
        if (resource instanceof InternetGateway igw) {
            return switch (filterName) {
                case "internet-gateway-id" -> matchesValue(values, igw.getInternetGatewayId());
                case "attachment.vpc-id" -> igw.getAttachments().stream()
                        .anyMatch(a -> matchesValue(values, a.getVpcId()));
                default -> true;
            };
        }
        if (resource instanceof RouteTable rt) {
            return switch (filterName) {
                case "route-table-id" -> matchesValue(values, rt.getRouteTableId());
                case "vpc-id" -> matchesValue(values, rt.getVpcId());
                case "association.route-table-association-id" -> rt.getAssociations().stream()
                        .anyMatch(a -> matchesValue(values, a.getRouteTableAssociationId()));
                case "association.subnet-id" -> rt.getAssociations().stream()
                        .anyMatch(a -> a.getSubnetId() != null && matchesValue(values, a.getSubnetId()));
                case "association.gateway-id" -> rt.getAssociations().stream()
                        .anyMatch(a -> a.getGatewayId() != null && matchesValue(values, a.getGatewayId()));
                case "association.main" -> rt.getAssociations().stream()
                        .anyMatch(a -> matchesValue(values, String.valueOf(a.isMain())));
                default -> true;
            };
        }
        if (resource instanceof LaunchTemplate lt) {
            return switch (filterName) {
                case "launch-template-id" -> matchesValue(values, lt.getLaunchTemplateId());
                case "launch-template-name" -> matchesValue(values, lt.getLaunchTemplateName());
                default -> true;
            };
        }
        if (resource instanceof VpcEndpoint endpoint) {
            return switch (filterName) {
                case "service-name" -> matchesValue(values, endpoint.getServiceName());
                case "vpc-endpoint-id" -> matchesValue(values, endpoint.getVpcEndpointId());
                case "vpc-endpoint-type" -> matchesValue(values, endpoint.getVpcEndpointType());
                case "vpc-id" -> matchesValue(values, endpoint.getVpcId());
                case "state" -> matchesValue(values, endpoint.getState());
                case "route-table-id" -> endpoint.getRouteTableIds().stream()
                        .anyMatch(routeTableId -> matchesValue(values, routeTableId));
                case "subnet-id" -> endpoint.getSubnetIds().stream()
                        .anyMatch(subnetId -> matchesValue(values, subnetId));
                default -> true;
            };
        }
        if (resource instanceof NatGateway natGateway) {
            return switch (filterName) {
                case "nat-gateway-id" -> matchesValue(values, natGateway.getNatGatewayId());
                case "subnet-id" -> matchesValue(values, natGateway.getSubnetId());
                case "vpc-id" -> matchesValue(values, natGateway.getVpcId());
                case "state" -> matchesValue(values, natGateway.getState());
                case "connectivity-type" -> matchesValue(values, natGateway.getConnectivityType());
                default -> true;
            };
        }
        if (resource instanceof Volume vol) {
            return switch (filterName) {
                case "volume-id" -> matchesValue(values, vol.getVolumeId());
                case "status" -> matchesValue(values, vol.getState());
                case "volume-type" -> matchesValue(values, vol.getVolumeType());
                case "availability-zone" -> matchesValue(values, vol.getAvailabilityZone());
                case "encrypted" -> matchesValue(values, String.valueOf(vol.isEncrypted()));
                default -> true;
            };
        }
        if (resource instanceof NetworkInterface ni) {
            return switch (filterName) {
                case "network-interface-id" -> matchesValue(values, ni.getNetworkInterfaceId());
                case "subnet-id" -> matchesValue(values, ni.getSubnetId());
                case "vpc-id" -> matchesValue(values, ni.getVpcId());
                case "group-id" -> ni.getGroups().stream()
                        .anyMatch(g -> matchesValue(values, g.getGroupId()));
                case "status" -> matchesValue(values, ni.getStatus());
                case "private-ip-address" ->
                    matchesValue(values, ni.getPrivateIpAddress()) ||
                    ni.getPrivateIpAddresses().stream()
                        .anyMatch(ip -> matchesValue(values, ip.getPrivateIpAddress()));
                case "description" -> matchesValue(values, ni.getDescription());
                case "owner-id" -> matchesValue(values, ni.getOwnerId());
                case "mac-address" -> matchesValue(values, ni.getMacAddress());
                case "private-dns-name" -> matchesValue(values, ni.getPrivateDnsName());
                default -> true;
            };
        }
        return true;
    }

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
        if (resource instanceof NetworkInterface ni) return ni.getTagSet();
        if (resource instanceof LaunchTemplate lt) return lt.getTags();
        if (resource instanceof VpcEndpoint endpoint) return endpoint.getTags();
        if (resource instanceof NatGateway natGateway) return natGateway.getTags();
        return Collections.emptyList();
    }

    // ─── Volumes ───────────────────────────────────────────────────────────────

    public Volume createVolume(String region, String availabilityZone, String volumeType,
                               int size, boolean encrypted, int iops, Integer throughput,
                               String snapshotId, List<Tag> volumeTags) {
        ensureDefaultResources(region);
        String volumeId = "vol-" + randomHex(17);
        String effectiveType = volumeType != null ? volumeType : "gp2";
        Volume vol = new Volume();
        vol.setVolumeId(volumeId);
        vol.setAvailabilityZone(availabilityZone != null ? availabilityZone : region + "a");
        vol.setVolumeType(effectiveType);
        vol.setSize(size > 0 ? size : 8);
        vol.setEncrypted(encrypted);
        vol.setIops(iops > 0 ? iops : (volumeType != null && volumeType.startsWith("io") ? iops : 0));
        // Throughput is a gp3-only attribute; AWS reports 125 MiB/s by default for gp3.
        if ("gp3".equals(effectiveType)) {
            vol.setThroughput(throughput != null && throughput > 0 ? throughput : 125);
        } else {
            vol.setThroughput(throughput);
        }
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
                if (volumes.get(key(region, id)).orElse(null) == null) {
                    throw new AwsException("InvalidVolume.NotFound",
                            "The volume '" + id + "' does not exist.", 400);
                }
            }
        }
        return volumes.scan(k -> true).stream()
                .filter(v -> v.getRegion().equals(region))
                .filter(v -> volumeIds == null || volumeIds.isEmpty() || volumeIds.contains(v.getVolumeId()))
                .filter(v -> matchesFilters(v, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteVolume(String region, String volumeId) {
        if (volumes.get(key(region, volumeId)).isEmpty()) {
            throw new AwsException("InvalidVolume.NotFound",
                    "The volume '" + volumeId + "' does not exist.", 400);
        }
        volumes.delete(key(region, volumeId));
    }

    // ─── Network Interfaces ─────────────────────────────────────────────────────

    public NetworkInterfaceListResult describeNetworkInterfaces(String region, List<String> networkInterfaceIds,
                                                                   Map<String, List<String>> filters,
                                                                   int maxResults, String nextToken) {
        // Validate pagination parameters
        if (maxResults > 0 && !networkInterfaceIds.isEmpty()) {
            throw new AwsException("InvalidParameterCombination",
                    "The parameter NetworkInterfaceId cannot be used with the parameter MaxResults.", 400);
        }
        if (maxResults > 0 && (maxResults < 5 || maxResults > 1000)) {
            throw new AwsException("InvalidMaxResults",
                    "Value (" + maxResults + ") for parameter MaxResults is invalid. "
                            + "Expecting a value between 5 and 1000.", 400);
        }
        int offset = decodeToken(nextToken);

        // Phase 6: validate NetworkInterfaceId format
        for (String id : networkInterfaceIds) {
            if (!id.startsWith("eni-")) {
                throw new AwsException("InvalidNetworkInterfaceID.Malformed",
                        "Invalid id: \"" + id + "\" (expecting \"eni-...\")", 400);
            }
        }

        ensureDefaultResources(region);
        List<NetworkInterface> result = new ArrayList<>();
        Set<String> foundIds = new HashSet<>();
        for (Instance inst : instances.scan(k -> true)) {
            if (!inst.getRegion().equals(region)) continue;
            if (inst.getState() != null
                    && inst.getState().getName() != null
                    && "terminated".equals(inst.getState().getName())) {
                continue;
            }
            for (InstanceNetworkInterface eni : inst.getNetworkInterfaces()) {
                if (!networkInterfaceIds.isEmpty()
                        && !networkInterfaceIds.contains(eni.getNetworkInterfaceId())) {
                    continue;
                }
                foundIds.add(eni.getNetworkInterfaceId());
                NetworkInterface ni = new NetworkInterface();
                ni.setNetworkInterfaceId(eni.getNetworkInterfaceId());
                ni.setSubnetId(eni.getSubnetId());
                ni.setVpcId(eni.getVpcId());
                ni.setDescription(eni.getDescription());
                ni.setOwnerId(eni.getOwnerId());
                ni.setStatus(eni.getStatus());
                ni.setMacAddress(eni.getMacAddress());
                ni.setPrivateIpAddress(eni.getPrivateIpAddress());
                ni.setPrivateDnsName(eni.getPrivateDnsName());
                ni.setSourceDestCheck(eni.isSourceDestCheck());
                ni.setGroups(new ArrayList<>(eni.getGroups()));
                // Phase 3: availability zone, tags, interface type
                if (inst.getPlacement() != null) {
                    ni.setAvailabilityZone(inst.getPlacement().getAvailabilityZone());
                }
                ni.getTagSet().addAll(inst.getTags());

                NetworkInterfaceAttachment att = new NetworkInterfaceAttachment();
                att.setAttachmentId(eni.getAttachmentId());
                att.setDeviceIndex(eni.getDeviceIndex());
                att.setStatus("attached");
                att.setInstanceId(inst.getInstanceId());
                att.setInstanceOwnerId(eni.getOwnerId());
                // Phase 3: attachTime from instance launchTime, deleteOnTermination
                if (inst.getLaunchTime() != null) {
                    att.setAttachTime(ISO_FMT.format(inst.getLaunchTime()));
                }
                att.setDeleteOnTermination(true);
                ni.setAttachment(att);

                // Phase 3: privateIpAddressesSet — primary IP
                NetworkInterfacePrivateIpAddress primaryIp = new NetworkInterfacePrivateIpAddress();
                primaryIp.setPrivateIpAddress(eni.getPrivateIpAddress());
                primaryIp.setPrivateDnsName(eni.getPrivateDnsName());
                primaryIp.setPrimary(true);
                // Look up EIP association for this instance
                addressForInstance(inst.getInstanceId()).ifPresent(addr -> {
                    NetworkInterfaceAssociation assoc = new NetworkInterfaceAssociation();
                    assoc.setPublicIp(addr.getPublicIp());
                    assoc.setAllocationId(addr.getAllocationId());
                    assoc.setAssociationId(addr.getAssociationId());
                    assoc.setIpOwnerId(eni.getOwnerId());
                    primaryIp.setAssociation(assoc);
                });
                ni.getPrivateIpAddresses().add(primaryIp);

                // Phase 4: apply filters
                if (!matchesFilters(ni, filters, region)) {
                    continue;
                }

                result.add(ni);
            }
        }

        // Phase 6: validate requested IDs exist
        for (String id : networkInterfaceIds) {
            if (!foundIds.contains(id)) {
                throw new AwsException("InvalidNetworkInterfaceID.NotFound",
                        "The network interface ID '" + id + "' does not exist", 400);
            }
        }

        // Phase 5: pagination
        if (maxResults > 0) {
            int total = result.size();
            int toIndex = Math.min(offset + maxResults, total);
            List<NetworkInterface> page = (offset < total)
                    ? result.subList(offset, toIndex)
                    : Collections.emptyList();
            String newNextToken = (toIndex < total)
                    ? encodeToken(toIndex)
                    : null;
            return new NetworkInterfaceListResult(new ArrayList<>(page), newNextToken);
        }

        return new NetworkInterfaceListResult(result, null);
    }

    // ─── Pagination token encoding / decoding ──────────────────────────────────

    private String encodeToken(int offset) {
        String json = "{\"offset\":" + offset + "}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private int decodeToken(String token) {
        if (token == null || token.isEmpty()) return 0;
        try {
            String json = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            int start = json.indexOf("\"offset\":") + 9;
            int end = json.indexOf('}', start);
            return Integer.parseInt(json.substring(start, end));
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid NextToken", 400);
        }
    }

    private Optional<Address> addressForInstance(String instanceId) {
        return addresses.scan(k -> true).stream()
                .filter(a -> instanceId.equals(a.getInstanceId()) && a.getAssociationId() != null)
                .findFirst();
    }
}
