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
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.CustomerGateway;
import io.github.hectorvent.floci.services.ec2.model.DhcpConfiguration;
import io.github.hectorvent.floci.services.ec2.model.DhcpOptions;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceMetadataDefaults;
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
import io.github.hectorvent.floci.services.ec2.model.Ipv6Range;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.NetworkAclAssociation;
import io.github.hectorvent.floci.services.ec2.model.NetworkAclEntry;
import io.github.hectorvent.floci.services.ec2.model.PrefixList;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.ReferencedSecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.RouteTableAssociation;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.PrefixListIdReference;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcAttachment;
import io.github.hectorvent.floci.services.ec2.model.VpcCidrBlockAssociation;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpointSubnetConfiguration;
import io.github.hectorvent.floci.services.ec2.model.VpnGateway;
import jakarta.annotation.PostConstruct;
import io.github.hectorvent.floci.services.ec2.model.LaunchSpecification;
import io.github.hectorvent.floci.services.ec2.model.SpotInstanceRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class Ec2Service implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(Ec2Service.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final int DEFAULT_ROOT_VOLUME_SIZE_GIB = 8;
    private static final String DEFAULT_ROOT_VOLUME_TYPE = "gp3";

    private final String accountId;
    private final EmulatorConfig config;
    private final Ec2ContainerManager containerManager;
    private final Ec2PortForwardManager portForwardManager;
    private final AmiImageResolver amiImageResolver;
    private final Ec2ImageCatalog imageCatalog;
    private final Ec2InstanceTypeCatalog instanceTypeCatalog;

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
    private final StorageBackend<String, Image> registeredImages;
    private final StorageBackend<String, Snapshot> snapshots;
    private final StorageBackend<String, LaunchTemplate> launchTemplates;
    private final StorageBackend<String, VpcEndpoint> vpcEndpoints;
    private final StorageBackend<String, NatGateway> natGateways;
    private final StorageBackend<String, SpotInstanceRequest> spotInstanceRequests;
    private final StorageBackend<String, NetworkAcl> networkAcls;
    private final StorageBackend<String, ManagedPrefixList> managedPrefixLists;
    private final StorageBackend<String, DhcpOptions> dhcpOptionsSets;
    private final StorageBackend<String, CustomerGateway> customerGateways;
    private final StorageBackend<String, VpnGateway> vpnGateways;
    private final StorageBackend<String, CapacityReservation> capacityReservations;
    // resourceId → List<Tag>
    private final StorageBackend<String, List<Tag>> tags;
    private final Set<String> seededRegions = ConcurrentHashMap.newKeySet();
    // subnetId → counter for IP assignment (runtime-only, not persisted)
    private final Map<String, AtomicInteger> subnetIpCounters = new ConcurrentHashMap<>();
    // region → the region's instance metadata defaults (runtime-only, not persisted - same
    // choice as subnetIpCounters above; a region absent from this map reads as AWS's own
    // "never configured" defaults, see InstanceMetadataDefaults's own no-arg field values).
    private final Map<String, InstanceMetadataDefaults> instanceMetadataDefaults = new ConcurrentHashMap<>();

    @Inject
    public Ec2Service(EmulatorConfig config, Ec2ContainerManager containerManager,
                      Ec2PortForwardManager portForwardManager,
                      AmiImageResolver amiImageResolver, Ec2ImageCatalog imageCatalog,
                      Ec2InstanceTypeCatalog instanceTypeCatalog, StorageFactory storageFactory) {
        this(config, containerManager, portForwardManager, amiImageResolver, imageCatalog, instanceTypeCatalog,
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
                storageFactory.create("ec2", "ec2-registered-images.json", new TypeReference<Map<String, Image>>() {}),
                storageFactory.create("ec2", "ec2-snapshots.json", new TypeReference<Map<String, Snapshot>>() {}),
                storageFactory.create("ec2", "ec2-launch-templates.json", new TypeReference<Map<String, LaunchTemplate>>() {}),
                storageFactory.create("ec2", "ec2-vpc-endpoints.json", new TypeReference<Map<String, VpcEndpoint>>() {}),
                storageFactory.create("ec2", "ec2-nat-gateways.json", new TypeReference<Map<String, NatGateway>>() {}),
                storageFactory.create("ec2", "ec2-spot-instance-requests.json", new TypeReference<Map<String, SpotInstanceRequest>>() {}),
                storageFactory.create("ec2", "ec2-network-acls.json", new TypeReference<Map<String, NetworkAcl>>() {}),
                storageFactory.create("ec2", "ec2-managed-prefix-lists.json", new TypeReference<Map<String, ManagedPrefixList>>() {}),
                storageFactory.create("ec2", "ec2-dhcp-options.json", new TypeReference<Map<String, DhcpOptions>>() {}),
                storageFactory.create("ec2", "ec2-customer-gateways.json", new TypeReference<Map<String, CustomerGateway>>() {}),
                storageFactory.create("ec2", "ec2-vpn-gateways.json", new TypeReference<Map<String, VpnGateway>>() {}),
                storageFactory.create("ec2", "ec2-capacity-reservations.json", new TypeReference<Map<String, CapacityReservation>>() {}),
                storageFactory.create("ec2", "ec2-tags.json", new TypeReference<Map<String, List<Tag>>>() {}));
    }

    // Package-private for hermetic tests (pass in-memory or temp-dir-backed StorageBackends directly).
    Ec2Service(EmulatorConfig config, Ec2ContainerManager containerManager,
               Ec2PortForwardManager portForwardManager,
               AmiImageResolver amiImageResolver, Ec2ImageCatalog imageCatalog,
               Ec2InstanceTypeCatalog instanceTypeCatalog,
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
               StorageBackend<String, Image> registeredImages,
               StorageBackend<String, Snapshot> snapshots,
               StorageBackend<String, LaunchTemplate> launchTemplates,
               StorageBackend<String, VpcEndpoint> vpcEndpoints,
               StorageBackend<String, NatGateway> natGateways,
               StorageBackend<String, SpotInstanceRequest> spotInstanceRequests,
               StorageBackend<String, NetworkAcl> networkAcls,
               StorageBackend<String, ManagedPrefixList> managedPrefixLists,
               StorageBackend<String, DhcpOptions> dhcpOptionsSets,
               StorageBackend<String, CustomerGateway> customerGateways,
               StorageBackend<String, VpnGateway> vpnGateways,
               StorageBackend<String, CapacityReservation> capacityReservations,
               StorageBackend<String, List<Tag>> tags) {
        this.accountId = config.defaultAccountId();
        this.config = config;
        this.containerManager = containerManager;
        this.portForwardManager = portForwardManager;
        this.amiImageResolver = amiImageResolver;
        this.imageCatalog = imageCatalog;
        this.instanceTypeCatalog = instanceTypeCatalog;
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
        this.registeredImages = registeredImages;
        this.snapshots = snapshots;
        this.launchTemplates = launchTemplates;
        this.vpcEndpoints = vpcEndpoints;
        this.natGateways = natGateways;
        this.spotInstanceRequests = spotInstanceRequests;
        this.networkAcls = networkAcls;
        this.managedPrefixLists = managedPrefixLists;
        this.dhcpOptionsSets = dhcpOptionsSets;
        this.customerGateways = customerGateways;
        this.vpnGateways = vpnGateways;
        this.capacityReservations = capacityReservations;
        this.tags = tags;
    }

    @PostConstruct
    void restoreMetadataRegistrations() {
        if (portForwardManager != null) {
            portForwardManager.setPersister(inst -> {
                if (inst != null && inst.getRegion() != null && inst.getInstanceId() != null) {
                    instances.put(key(inst.getRegion(), inst.getInstanceId()), inst);
                }
            });
        }
        if (config.services().ec2().mock()) {
            return;
        }

        int restored = 0;
        for (String key : instances.keys()) {
            Instance instance = instances.get(key).orElse(null);
            if (!needsMetadataRegistration(instance)) {
                continue;
            }
            if (containerManager.restoreMetadataRegistration(instance)) {
                instances.put(key, instance);
                restored++;
                // Container is running: re-reserve host ports and recreate any missing socat sidecars.
                if (portForwardManager != null) {
                    portForwardManager.restore(instance);
                }
            }
        }
        if (restored > 0) {
            LOG.infov("Restored IMDS metadata registration for {0} EC2 container(s)", restored);
        }
    }

    private static boolean needsMetadataRegistration(Instance instance) {
        if (instance == null || instance.getDockerContainerId() == null) {
            return false;
        }
        String state = instance.getState() != null ? instance.getState().getName() : null;
        return state == null
                || (!"shutting-down".equals(state) && !"terminated".equals(state) && !"stopping".equals(state));
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

        // Default DHCP options set, matching what AWS provisions alongside every VPC:
        // Amazon-provided DNS plus the region's default DNS suffix.
        String dhcpOptionsId = "dopt-default";
        DhcpOptions defaultDhcpOptions = new DhcpOptions();
        defaultDhcpOptions.setDhcpOptionsId(dhcpOptionsId);
        defaultDhcpOptions.setOwnerId(accountId);
        defaultDhcpOptions.setRegion(region);
        defaultDhcpOptions.getDhcpConfigurationSet().add(
                new DhcpConfiguration("domain-name", List.of(defaultDomainName(region))));
        defaultDhcpOptions.getDhcpConfigurationSet().add(
                new DhcpConfiguration("domain-name-servers", List.of("AmazonProvidedDNS")));
        dhcpOptionsSets.put(key(region, dhcpOptionsId), defaultDhcpOptions);

        // Default VPC
        String vpcId = "vpc-default";
        Vpc defaultVpc = new Vpc();
        defaultVpc.setVpcId(vpcId);
        defaultVpc.setCidrBlock("172.31.0.0/16");
        defaultVpc.setState("available");
        defaultVpc.setDefault(true);
        defaultVpc.setOwnerId(accountId);
        defaultVpc.setRegion(region);
        defaultVpc.setDhcpOptionsId(dhcpOptionsId);
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

        // Default NACL, with the default subnets associated to it.
        String defaultAclId = createDefaultNetworkAcl(region, vpcId, "acl-default");
        NetworkAcl defaultAcl = networkAcls.get(key(region, defaultAclId)).orElse(null);
        if (defaultAcl != null) {
            for (String subnetId : subnetIds) {
                NetworkAclAssociation assoc = new NetworkAclAssociation();
                assoc.setNetworkAclAssociationId("aclassoc-" + subnetId);
                assoc.setNetworkAclId(defaultAclId);
                assoc.setSubnetId(subnetId);
                defaultAcl.getAssociations().add(assoc);
            }
            networkAcls.put(key(region, defaultAclId), defaultAcl);
        }

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
        // Persist the default egress rule as a SecurityGroupRule so that
        // DescribeSecurityGroupRules can find it immediately (#1093).
        createRules(region, securityGroupId, egressAll, true);
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

    private NetworkAclEntry naclEntry(int ruleNumber, String protocol, String action, boolean egress, String cidr) {
        return naclEntry(ruleNumber, protocol, action, egress, cidr, null);
    }

    private NetworkAclEntry naclEntry(int ruleNumber, String protocol, String action, boolean egress, String cidr,
                                       String ipv6Cidr) {
        NetworkAclEntry entry = new NetworkAclEntry();
        entry.setRuleNumber(ruleNumber);
        entry.setProtocol(protocol);
        entry.setRuleAction(action);
        entry.setEgress(egress);
        entry.setCidrBlock(cidr);
        entry.setIpv6CidrBlock(ipv6Cidr);
        return entry;
    }

    // The default NACL allows all traffic (rule 100) and ends with the implicit deny (32767),
    // for both ingress and egress — matching what AWS provisions with every VPC.
    private String createDefaultNetworkAcl(String region, String vpcId, String networkAclId) {
        NetworkAcl acl = new NetworkAcl();
        acl.setNetworkAclId(networkAclId);
        acl.setVpcId(vpcId);
        acl.setOwnerId(accountId);
        acl.setRegion(region);
        acl.setDefault(true);
        acl.getEntries().add(naclEntry(100, "-1", "allow", false, "0.0.0.0/0"));
        acl.getEntries().add(naclEntry(32767, "-1", "deny", false, "0.0.0.0/0"));
        acl.getEntries().add(naclEntry(100, "-1", "allow", true, "0.0.0.0/0"));
        acl.getEntries().add(naclEntry(32767, "-1", "deny", true, "0.0.0.0/0"));
        networkAcls.put(key(region, networkAclId), acl);
        return networkAclId;
    }

    private NetworkAcl findDefaultNetworkAcl(String region, String vpcId) {
        return networkAcls.scan(k -> true).stream()
                .filter(a -> region.equals(a.getRegion()) && vpcId.equals(a.getVpcId()) && a.isDefault())
                .findFirst().orElse(null);
    }

    private NetworkAcl getRequiredNetworkAcl(String region, String networkAclId) {
        return networkAcls.get(key(region, networkAclId)).orElseThrow(() ->
                new AwsException("InvalidNetworkAclID.NotFound",
                        "The network ACL ID '" + networkAclId + "' does not exist", 400));
    }

    // A brand-new custom NACL starts closed: only the implicit deny rules, no allows.
    public NetworkAcl createNetworkAcl(String region, String vpcId) {
        ensureDefaultResources(region);
        getRequiredVpc(region, vpcId);
        String networkAclId = "acl-" + randomHex(17);
        NetworkAcl acl = new NetworkAcl();
        acl.setNetworkAclId(networkAclId);
        acl.setVpcId(vpcId);
        acl.setOwnerId(accountId);
        acl.setRegion(region);
        acl.setDefault(false);
        acl.getEntries().add(naclEntry(32767, "-1", "deny", false, "0.0.0.0/0"));
        acl.getEntries().add(naclEntry(32767, "-1", "deny", true, "0.0.0.0/0"));
        networkAcls.put(key(region, networkAclId), acl);
        return acl;
    }

    public List<NetworkAcl> describeNetworkAcls(String region, List<String> ids, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        return networkAcls.scan(k -> true).stream()
                .filter(a -> region.equals(a.getRegion()))
                .filter(a -> ids.isEmpty() || ids.contains(a.getNetworkAclId()))
                .filter(a -> matchesNetworkAclFilters(a, filters))
                .collect(Collectors.toList());
    }

    private boolean matchesNetworkAclFilters(NetworkAcl acl, Map<String, List<String>> filters) {
        for (Map.Entry<String, List<String>> f : filters.entrySet()) {
            List<String> values = f.getValue();
            boolean matches = switch (f.getKey()) {
                case "network-acl-id" -> values.contains(acl.getNetworkAclId());
                case "vpc-id" -> values.contains(acl.getVpcId());
                case "default" -> values.contains(String.valueOf(acl.isDefault()));
                case "association.subnet-id" ->
                        acl.getAssociations().stream().anyMatch(a -> values.contains(a.getSubnetId()));
                case "association.network-acl-association-id" ->
                        acl.getAssociations().stream().anyMatch(a -> values.contains(a.getNetworkAclAssociationId()));
                default -> true;
            };
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    public void createNetworkAclEntry(String region, String networkAclId, int ruleNumber, String protocol,
                                      String ruleAction, boolean egress, String cidrBlock, String ipv6CidrBlock,
                                      Integer from, Integer to, boolean replace) {
        synchronized (lockFor(key(region, networkAclId))) {
            NetworkAcl acl = getRequiredNetworkAcl(region, networkAclId);
            boolean exists = acl.getEntries().stream()
                    .anyMatch(e -> e.getRuleNumber() == ruleNumber && e.isEgress() == egress);
            if (!replace && exists) {
                throw new AwsException("NetworkAclEntryAlreadyExists",
                        "The network acl entry identified by " + ruleNumber + " already exists.", 400);
            }
            List<NetworkAclEntry> next = new ArrayList<>(acl.getEntries());
            next.removeIf(e -> e.getRuleNumber() == ruleNumber && e.isEgress() == egress);
            NetworkAclEntry entry = naclEntry(ruleNumber, protocol, ruleAction, egress, cidrBlock, ipv6CidrBlock);
            entry.setPortRangeFrom(from);
            entry.setPortRangeTo(to);
            next.add(entry);
            acl.setEntries(next);
            networkAcls.put(key(region, networkAclId), acl);
        }
    }

    public void deleteNetworkAclEntry(String region, String networkAclId, int ruleNumber, boolean egress) {
        synchronized (lockFor(key(region, networkAclId))) {
            NetworkAcl acl = getRequiredNetworkAcl(region, networkAclId);
            List<NetworkAclEntry> next = new ArrayList<>(acl.getEntries());
            next.removeIf(e -> e.getRuleNumber() == ruleNumber && e.isEgress() == egress);
            acl.setEntries(next);
            networkAcls.put(key(region, networkAclId), acl);
        }
    }

    public NetworkAclAssociation replaceNetworkAclAssociation(String region, String associationId, String networkAclId) {
        NetworkAcl target = getRequiredNetworkAcl(region, networkAclId);
        for (NetworkAcl acl : networkAcls.scan(k -> true)) {
            if (!region.equals(acl.getRegion())
                    || acl.getAssociations().stream()
                            .noneMatch(a -> a.getNetworkAclAssociationId().equals(associationId))) {
                continue;
            }
            String sourceKey = key(region, acl.getNetworkAclId());
            String targetKey = key(region, networkAclId);
            // The move must be atomic across both ACLs, or a describe could observe the subnet
            // associated with neither. Locks are taken in stripe order so two callers moving
            // associations in opposite directions cannot deadlock; one stripe re-enters.
            synchronized (lowerLockOf(sourceKey, targetKey)) {
                synchronized (higherLockOf(sourceKey, targetKey)) {
                    List<NetworkAclAssociation> remaining = new ArrayList<>(acl.getAssociations());
                    NetworkAclAssociation claimed = remaining.stream()
                            .filter(a -> a.getNetworkAclAssociationId().equals(associationId))
                            .findFirst()
                            .orElse(null);
                    // The scan above ran unlocked, so a concurrent replace of the same association
                    // may already have moved it. That caller minted the new id; this one sees the
                    // requested id no longer exist.
                    if (claimed == null) {
                        break;
                    }
                    remaining.remove(claimed);
                    acl.setAssociations(remaining);
                    networkAcls.put(sourceKey, acl);

                    NetworkAclAssociation moved = new NetworkAclAssociation();
                    moved.setNetworkAclAssociationId("aclassoc-" + randomHex(17));
                    moved.setNetworkAclId(networkAclId);
                    moved.setSubnetId(claimed.getSubnetId());
                    List<NetworkAclAssociation> next = new ArrayList<>(target.getAssociations());
                    next.add(moved);
                    target.setAssociations(next);
                    networkAcls.put(targetKey, target);
                    return moved;
                }
            }
        }
        throw new AwsException("InvalidAssociationID.NotFound",
                "The network ACL association ID '" + associationId + "' does not exist", 400);
    }

    public void deleteNetworkAcl(String region, String networkAclId) {
        NetworkAcl acl = getRequiredNetworkAcl(region, networkAclId);
        if (acl.isDefault()) {
            throw new AwsException("InvalidParameterValue",
                    "The network ACL '" + networkAclId + "' is the default network ACL and cannot be deleted", 400);
        }
        if (!acl.getAssociations().isEmpty()) {
            throw new AwsException("DependencyViolation",
                    "The network ACL '" + networkAclId + "' has dependencies and cannot be deleted.", 400);
        }
        networkAcls.delete(key(region, networkAclId));
    }

    // AWS-managed prefix lists for the gateway-endpoint services (S3, DynamoDB). These are
    // not user-created, so they're returned as static managed data per region. Querying any
    // other service name (e.g. an interface endpoint) correctly yields no match.
    //
    // The legacy DescribePrefixLists surface projects the same objects that
    // DescribeManagedPrefixLists serves, so the two APIs cannot report different CIDRs for the
    // same list.
    public List<PrefixList> describePrefixLists(String region, List<String> ids, Map<String, List<String>> filters) {
        List<String> names = filters.getOrDefault("prefix-list-name", List.of());
        List<String> filterIds = filters.getOrDefault("prefix-list-id", List.of());
        return awsManagedPrefixLists(region).stream()
                .filter(pl -> ids.isEmpty() || ids.contains(pl.getPrefixListId()))
                .filter(pl -> filterIds.isEmpty() || filterIds.contains(pl.getPrefixListId()))
                .filter(pl -> names.isEmpty() || names.contains(pl.getPrefixListName()))
                .map(pl -> new PrefixList(pl.getPrefixListId(), pl.getPrefixListName(),
                        pl.currentEntries().stream()
                                .map(PrefixListEntry::getCidr)
                                .collect(Collectors.toCollection(ArrayList::new))))
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Managed prefix lists
    // =========================================================================

    /**
     * Name prefixes AWS reserves for its own gateway-endpoint lists. The trailing dot is part of
     * each: {@code com.amazonaws-probe} is accepted on AWS, so matching without it over-rejects.
     */
    private static final List<String> RESERVED_PREFIX_LIST_NAME_PREFIXES =
            List.of("com.amazonaws.", "com.amazon.", "com.aws.");

    /** AWS applies the reserved-name rule to a rename as well as a create. */
    private void requireUnreservedPrefixListName(String prefixListName) {
        for (String reserved : RESERVED_PREFIX_LIST_NAME_PREFIXES) {
            if (prefixListName.startsWith(reserved)) {
                throw new AwsException("InvalidParameterValue",
                        "The prefix list name cannot begin with (com.amazonaws., com.amazon., com.aws.).", 400);
            }
        }
    }

    private List<ManagedPrefixList> awsManagedPrefixLists(String region) {
        return List.of(
                awsManagedPrefixList(region, "pl-63a5400a", "com.amazonaws." + region + ".s3",
                        List.of("52.216.0.0/15", "54.231.0.0/16")),
                awsManagedPrefixList(region, "pl-02cd2c6b", "com.amazonaws." + region + ".dynamodb",
                        List.of("3.218.182.0/24", "52.94.0.0/22")));
    }

    private ManagedPrefixList awsManagedPrefixList(String region, String id, String name, List<String> cidrs) {
        ManagedPrefixList list = new ManagedPrefixList();
        list.setPrefixListId(id);
        list.setPrefixListName(name);
        // AWS-managed lists are owned by AWS itself, not by the calling account.
        list.setOwnerId("AWS");
        list.setPrefixListArn(AwsArnUtils.Arn.of("ec2", region, "aws", "prefix-list/" + id).toString());
        list.setAddressFamily("IPv4");
        list.setState("create-complete");
        list.setMaxEntries(cidrs.size());
        list.setVersion(1);
        list.setRegion(region);
        list.setAwsManaged(true);
        list.getEntriesByVersion().put("1", cidrs.stream()
                .map(cidr -> new PrefixListEntry(cidr, null))
                .collect(Collectors.toList()));
        return list;
    }

    public ManagedPrefixList createManagedPrefixList(String region, String prefixListName, String addressFamily,
                                                     Integer maxEntries, List<PrefixListEntry> entries,
                                                     List<Tag> prefixListTags) {
        if (prefixListName == null || prefixListName.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter PrefixListName.", 400);
        }
        requireUnreservedPrefixListName(prefixListName);
        if (!"IPv4".equals(addressFamily) && !"IPv6".equals(addressFamily)) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid value '" + addressFamily + "' for addressFamily. Valid values are IPv4 and IPv6.", 400);
        }
        if (maxEntries == null || maxEntries < 1) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid value for maxEntries. It must be greater than 0.", 400);
        }
        List<PrefixListEntry> initial = entries == null ? List.of() : entries;
        if (initial.size() > maxEntries) {
            throw new AwsException("InvalidParameterValue",
                    "The number of entries exceeds the maximum of " + maxEntries + ".", 400);
        }
        initial.forEach(entry -> validatePrefixListEntry(entry, addressFamily));

        ManagedPrefixList list = new ManagedPrefixList();
        String prefixListId = "pl-" + randomHex(17);
        list.setPrefixListId(prefixListId);
        list.setPrefixListName(prefixListName);
        list.setPrefixListArn(AwsArnUtils.Arn.of("ec2", region, accountId, "prefix-list/" + prefixListId).toString());
        list.setAddressFamily(addressFamily);
        list.setMaxEntries(maxEntries);
        list.setOwnerId(accountId);
        list.setRegion(region);
        // AWS creates asynchronously (create-in-progress then create-complete). Nothing here is
        // slow, so the list is complete by the time the caller sees it.
        list.setState("create-complete");
        list.setVersion(1);
        list.getEntriesByVersion().put("1", new ArrayList<>(initial));
        if (prefixListTags != null && !prefixListTags.isEmpty()) {
            list.setTags(new ArrayList<>(prefixListTags));
            tags.put(prefixListId, new ArrayList<>(prefixListTags));
        }
        managedPrefixLists.put(key(region, prefixListId), list);
        return list;
    }

    public List<ManagedPrefixList> describeManagedPrefixLists(String region, List<String> prefixListIds,
                                                              Map<String, List<String>> filters) {
        List<ManagedPrefixList> all = new ArrayList<>(awsManagedPrefixLists(region));
        managedPrefixLists.scan(k -> true).stream()
                .filter(list -> region.equals(list.getRegion()))
                .forEach(all::add);

        if (!prefixListIds.isEmpty()) {
            for (String prefixListId : prefixListIds) {
                if (all.stream().noneMatch(list -> list.getPrefixListId().equals(prefixListId))) {
                    throw new AwsException("InvalidPrefixListID.NotFound",
                            "The prefix list ID '" + prefixListId + "' does not exist.", 400);
                }
            }
        }
        return all.stream()
                .filter(list -> prefixListIds.isEmpty() || prefixListIds.contains(list.getPrefixListId()))
                .filter(list -> matchesFilters(list, filters, region))
                .collect(Collectors.toList());
    }

    public List<PrefixListEntry> getManagedPrefixListEntries(String region, String prefixListId, Long targetVersion) {
        ManagedPrefixList list = getRequiredManagedPrefixList(region, prefixListId);
        long version = targetVersion != null ? targetVersion : list.getVersion();
        List<PrefixListEntry> entries = list.getEntriesByVersion().get(String.valueOf(version));
        if (entries == null) {
            throw new AwsException("InvalidParameterValue",
                    "Version " + version + " does not exist for prefix list " + prefixListId + ".", 400);
        }
        return entries;
    }

    /**
     * Applies removals before additions, matching AWS, so a single call can replace an entry's
     * description by removing and re-adding the same CIDR. Only an entry change produces a new
     * version — renaming the list leaves the version untouched.
     */
    public ManagedPrefixList modifyManagedPrefixList(String region, String prefixListId, Long currentVersion,
                                                     String prefixListName, Integer maxEntries,
                                                     List<PrefixListEntry> addEntries, List<String> removeCidrs) {
        synchronized (lockFor(key(region, prefixListId))) {
            ManagedPrefixList list = getRequiredManagedPrefixList(region, prefixListId);
            requireCustomerManaged(list, "modified");
            if (currentVersion != null && currentVersion != list.getVersion()) {
                throw new AwsException("PrefixListVersionMismatch",
                        "The prefix list has the incorrect version number.", 400);
            }

            List<PrefixListEntry> updated = new ArrayList<>(list.currentEntries());
            if (removeCidrs != null && !removeCidrs.isEmpty()) {
                updated.removeIf(entry -> removeCidrs.contains(entry.getCidr()));
            }
            if (addEntries != null) {
                for (PrefixListEntry entry : addEntries) {
                    validatePrefixListEntry(entry, list.getAddressFamily());
                    updated.removeIf(existing -> existing.getCidr().equals(entry.getCidr()));
                    updated.add(entry);
                }
            }

            int effectiveMax = maxEntries != null ? maxEntries : list.getMaxEntries();
            if (effectiveMax < 1) {
                throw new AwsException("InvalidParameterValue",
                        "Invalid value for maxEntries. It must be greater than 0.", 400);
            }
            if (updated.size() > effectiveMax) {
                throw new AwsException("InvalidParameterValue",
                        "The number of entries exceeds the maximum of " + effectiveMax + ".", 400);
            }
            if (maxEntries != null) {
                list.setMaxEntries(maxEntries);
            }
            if (prefixListName != null && !prefixListName.isBlank()) {
                requireUnreservedPrefixListName(prefixListName);
                list.setPrefixListName(prefixListName);
            }

            boolean entriesChanged = (addEntries != null && !addEntries.isEmpty())
                    || (removeCidrs != null && !removeCidrs.isEmpty());
            if (entriesChanged) {
                long nextVersion = list.getVersion() + 1;
                list.getEntriesByVersion().put(String.valueOf(nextVersion), updated);
                list.setVersion(nextVersion);
            }
            list.setState("modify-complete");
            managedPrefixLists.put(key(region, prefixListId), list);
            return list;
        }
    }

    public ManagedPrefixList deleteManagedPrefixList(String region, String prefixListId) {
        synchronized (lockFor(key(region, prefixListId))) {
            ManagedPrefixList list = getRequiredManagedPrefixList(region, prefixListId);
            requireCustomerManaged(list, "deleted");
            managedPrefixLists.delete(key(region, prefixListId));
            tags.delete(prefixListId);
            // AWS reports delete-complete on the returned object even though it is now gone.
            list.setState("delete-complete");
            return list;
        }
    }

    private ManagedPrefixList getRequiredManagedPrefixList(String region, String prefixListId) {
        if (prefixListId == null || prefixListId.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter PrefixListId.", 400);
        }
        return describeManagedPrefixLists(region, List.of(prefixListId), Map.of()).stream()
                .findFirst()
                .orElseThrow(() -> new AwsException("InvalidPrefixListID.NotFound",
                        "The prefix list ID '" + prefixListId + "' does not exist.", 400));
    }

    private void requireCustomerManaged(ManagedPrefixList list, String verb) {
        if (list.isAwsManaged()) {
            throw new AwsException("UnsupportedOperation",
                    "The prefix list " + list.getPrefixListId()
                            + " is an AWS-managed prefix list and cannot be " + verb + ".", 400);
        }
    }

    private void validatePrefixListEntry(PrefixListEntry entry, String addressFamily) {
        if (entry.getCidr() == null || entry.getCidr().isBlank()) {
            throw new AwsException("MissingParameter", "Every prefix list entry must specify a Cidr.", 400);
        }
        boolean ipv6 = entry.getCidr().contains(":");
        if (ipv6 != "IPv6".equals(addressFamily)) {
            throw new AwsException("InvalidParameterValue",
                    "The CIDR '" + entry.getCidr() + "' does not match the address family " + addressFamily + ".", 400);
        }
    }

    private String key(String region, String id) {
        return region + "::" + id;
    }

    // Per-resource mutation locks (#1464): storage get() returns the live stored object, so
    // unsynchronized list mutations race under parallel clients (Terraform runs 10-wide) and
    // drop entries. Mutators take the resource's stripe and swap collections copy-on-write so
    // concurrent describes only ever see a complete list. A fixed stripe array keeps this
    // bounded — a lock per storage key would never evict — at the cost of unrelated resources
    // sharing a monitor on hash collision.
    private static final int LOCK_STRIPES = 512;
    private final Object[] resourceLocks = newLockStripes();

    private static Object[] newLockStripes() {
        Object[] stripes = new Object[LOCK_STRIPES];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }

    private int stripeOf(String storeKey) {
        return Math.floorMod(storeKey.hashCode(), LOCK_STRIPES);
    }

    private Object lockFor(String storeKey) {
        return resourceLocks[stripeOf(storeKey)];
    }

    // Stripe index, not key order, is the total order two-lock callers must agree on: distinct
    // keys can share a stripe, so ordering by key could have two callers take the same pair of
    // monitors in opposite orders.
    private Object lowerLockOf(String keyA, String keyB) {
        return resourceLocks[Math.min(stripeOf(keyA), stripeOf(keyB))];
    }

    private Object higherLockOf(String keyA, String keyB) {
        return resourceLocks[Math.max(stripeOf(keyA), stripeOf(keyB))];
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
        return runInstances(region, imageId, instanceType, minCount, maxCount, keyName,
                securityGroupIds, subnetId, clientToken, instanceTags, userData,
                iamInstanceProfileArn, null);
    }

    public Reservation runInstances(String region, String imageId, String instanceType,
                                    int minCount, int maxCount, String keyName,
                                    List<String> securityGroupIds, String subnetId,
                                    String clientToken, List<Tag> instanceTags,
                                    String userData, String iamInstanceProfileArn,
                                    Boolean associatePublicIp) {
        return runInstances(region, imageId, instanceType, minCount, maxCount, keyName,
                securityGroupIds, subnetId, clientToken, instanceTags, userData,
                iamInstanceProfileArn, associatePublicIp, List.of());
    }

    public Reservation runInstances(String region, String imageId, String instanceType,
                                    int minCount, int maxCount, String keyName,
                                    List<String> securityGroupIds, String subnetId,
                                    String clientToken, List<Tag> instanceTags,
                                    String userData, String iamInstanceProfileArn,
                                    Boolean associatePublicIp,
                                    List<BlockDeviceMapping> blockDeviceMappings) {
        if (imageId == null || imageId.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter ImageId", 400);
        }
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

        String effectiveInstanceType = instanceType != null ? instanceType : "t2.micro";
        validateArchitectureCompatibility(imageId, effectiveInstanceType);
        int count = Math.min(maxCount, Math.max(minCount, 1));
        String architecture = architectureFor(imageId, effectiveInstanceType);
        List<BlockDeviceMapping> effectiveBlockDeviceMappings =
                blockDeviceMappings != null ? blockDeviceMappings : List.of();
        Image sourceImage = findImageForCapture(region, imageId);
        String effectiveRootDeviceName = sourceImage != null && sourceImage.getRootDeviceName() != null
                ? sourceImage.getRootDeviceName() : "/dev/xvda";
        BlockDeviceMapping rootMapping = effectiveBlockDeviceMappings.stream()
                .filter(m -> m.getDeviceName() != null && m.getDeviceName().equalsIgnoreCase(effectiveRootDeviceName))
                .findFirst()
                .orElse(null);
        EbsBlockDevice rootEbs = rootMapping != null ? rootMapping.getEbs() : null;
        for (int i = 0; i < count; i++) {
            String instanceId = "i-" + randomHex(17);
            String privateIp = assignPrivateIp(region, finalSubnetId);

            Instance inst = new Instance();
            inst.setInstanceId(instanceId);
            inst.setImageId(imageId);
            inst.setRootDeviceName(effectiveRootDeviceName);
            inst.setState(InstanceState.pending());
            inst.setInstanceType(effectiveInstanceType);
            inst.setPlacement(new Placement(az));
            inst.setSubnetId(finalSubnetId);
            inst.setVpcId(vpcId);
            // AWS precedence (#1984): the launch-time AssociatePublicIpAddress
            // override wins in both directions; the subnet's MapPublicIpOnLaunch
            // attribute is only the default when the launch does not specify it.
            inst.setAssociatePublicIp(associatePublicIp != null
                    ? associatePublicIp
                    : subnet != null && subnet.isMapPublicIpOnLaunch());
            inst.setPrivateIpAddress(privateIp);
            inst.setPrivateDnsName("ip-" + privateIp.replace('.', '-') + ".ec2.internal");
            inst.setKeyName(keyName);
            inst.setSecurityGroups(new ArrayList<>(sgIdentifiers));
            inst.setArchitecture(architecture);
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

            // Root EBS volume. BlockDeviceMapping.Ebs fields for the AMI's actual root
            // device name (resolved above, not assumed to be /dev/xvda) override the
            // defaults below, matching AWS's own CreateVolume-time behavior.
            String rootVolId = "vol-" + randomHex(17);
            inst.setRootVolumeId(rootVolId);
            Volume rootVol = new Volume();
            rootVol.setVolumeId(rootVolId);
            rootVol.setAvailabilityZone(az);
            String rootVolumeType = rootEbs != null && rootEbs.getVolumeType() != null
                    ? rootEbs.getVolumeType() : DEFAULT_ROOT_VOLUME_TYPE;
            rootVol.setVolumeType(rootVolumeType);
            rootVol.setSize(rootEbs != null && rootEbs.getVolumeSize() != null
                    ? rootEbs.getVolumeSize() : DEFAULT_ROOT_VOLUME_SIZE_GIB);
            rootVol.setEncrypted(rootEbs != null && Boolean.TRUE.equals(rootEbs.getEncrypted()));
            if (rootEbs != null && rootEbs.getIops() != null) {
                rootVol.setIops(rootEbs.getIops());
            }
            // Throughput is a gp3-only attribute; AWS reports 125 MiB/s by default for gp3.
            if ("gp3".equals(rootVolumeType)) {
                rootVol.setThroughput(rootEbs != null && rootEbs.getThroughput() != null
                        ? rootEbs.getThroughput() : 125);
            } else if (rootEbs != null) {
                rootVol.setThroughput(rootEbs.getThroughput());
            }
            if (rootEbs != null) {
                rootVol.setSnapshotId(rootEbs.getSnapshotId());
            }
            rootVol.setState("in-use");
            rootVol.setRegion(region);
            rootVol.setCreateTime(Instant.now());
            boolean rootDeleteOnTermination = rootEbs == null || rootEbs.getDeleteOnTermination() == null
                    || rootEbs.getDeleteOnTermination();
            inst.setRootVolumeDeleteOnTermination(rootDeleteOnTermination);
            VolumeAttachment att = new VolumeAttachment();
            att.setVolumeId(rootVolId);
            att.setInstanceId(instanceId);
            att.setDevice(inst.getRootDeviceName());
            att.setState("attached");
            att.setDeleteOnTermination(rootDeleteOnTermination);
            att.setAttachTime(Instant.now());
            rootVol.getAttachments().add(att);
            volumes.put(key(region, rootVolId), rootVol);

            // Additional (non-root) EBS block device mappings each get their own volume,
            // created and attached the same way a standalone CreateVolume + AttachVolume
            // would. Previously RunInstances dropped every BlockDeviceMapping entry other
            // than the (hardcoded) root volume, so these never existed at all.
            for (BlockDeviceMapping mapping : effectiveBlockDeviceMappings) {
                if (mapping == rootMapping || mapping.getEbs() == null) {
                    continue;
                }
                EbsBlockDevice extraEbs = mapping.getEbs();
                String extraType = extraEbs.getVolumeType() != null ? extraEbs.getVolumeType() : "gp2";
                int extraSize = extraEbs.getVolumeSize() != null ? extraEbs.getVolumeSize() : 8;
                Volume extraVol = createVolume(region, az, extraType, extraSize,
                        Boolean.TRUE.equals(extraEbs.getEncrypted()),
                        extraEbs.getIops() != null ? extraEbs.getIops() : 0,
                        extraEbs.getThroughput(), extraEbs.getSnapshotId(), List.of());
                VolumeAttachment extraAtt = new VolumeAttachment();
                extraAtt.setVolumeId(extraVol.getVolumeId());
                extraAtt.setInstanceId(instanceId);
                extraAtt.setDevice(mapping.getDeviceName());
                extraAtt.setState("attached");
                extraAtt.setDeleteOnTermination(extraEbs.getDeleteOnTermination() == null
                        || extraEbs.getDeleteOnTermination());
                extraAtt.setAttachTime(Instant.now());
                extraVol.getAttachments().add(extraAtt);
                extraVol.setState("in-use");
                volumes.put(key(region, extraVol.getVolumeId()), extraVol);
            }

            instances.put(key(region, instanceId), inst);
            reservation.getInstances().add(inst);

            if (!config.services().ec2().mock()) {
                // A CreateImage AMI is not in the catalog, so resolve through its source.
                ResolvedAmiImage dockerImage =
                        amiImageResolver.resolveImage(resolveLaunchableImageId(region, imageId));
                String publicKey = null;
                if (keyName != null) {
                    KeyPair kp = findKeyPair(region, keyName);
                    if (kp != null) {
                        publicKey = kp.getPublicKey();
                    }
                }
                containerManager.launch(inst, dockerImage, publicKey, region, desiredPublishedPorts(region, inst));
            }
        }

        return reservation;
    }

    /**
     * Resolves the TCP ingress ports Floci should publish on the host for an instance, aggregated
     * across its attached security groups. Empty when publishing is disabled or nothing is opened.
     */
    private Set<Integer> desiredPublishedPorts(String region, Instance inst) {
        if (!config.services().ec2().publishSecurityGroupPorts()) {
            return Set.of();
        }
        List<SecurityGroup> sgs = new ArrayList<>();
        if (inst.getSecurityGroups() != null) {
            for (GroupIdentifier gi : inst.getSecurityGroups()) {
                securityGroups.get(key(region, gi.getGroupId())).ifPresent(sgs::add);
            }
        }
        return Ec2PortForwardManager.extractPublishablePorts(
                sgs, config.services().ec2().maxPublishedPortsPerInstance());
    }

    /**
     * Re-publishes host forwards for every running instance attached to the given security group,
     * so ports opened or closed via authorize/revoke ingress take effect on already-running
     * instances. No-op in mock mode or when publishing is disabled.
     */
    private void reconcilePublishedPortsForGroup(String region, String groupId) {
        if (!config.services().ec2().publishSecurityGroupPorts() || config.services().ec2().mock()) {
            return;
        }
        String prefix = region + "::";
        for (Instance inst : instances.scan(k -> k.startsWith(prefix))) {
            if (inst.getSecurityGroups() == null || inst.getDockerContainerId() == null) {
                continue;
            }
            boolean attached = inst.getSecurityGroups().stream()
                    .anyMatch(g -> groupId.equals(g.getGroupId()));
            if (!attached) {
                continue;
            }
            String state = inst.getState() != null ? inst.getState().getName() : null;
            if (!"running".equals(state)) {
                continue;
            }
            portForwardManager.reconcile(inst, desiredPublishedPorts(region, inst));
            instances.put(key(region, inst.getInstanceId()), inst);
        }
    }

    private void validateArchitectureCompatibility(String imageId, String instanceType) {
        Optional<String> imageArchitecture = imageCatalog.findByIdOrAlias(imageId)
                .map(image -> image.architecture)
                .filter(value -> !value.isBlank());
        if (imageArchitecture.isEmpty()) {
            return;
        }
        instanceTypeCatalog.find(instanceType)
                .filter(type -> type.supportedArchitectures.stream()
                        .noneMatch(imageArchitecture.get()::equals))
                .ifPresent(type -> {
                    throw new AwsException("InvalidParameterValue",
                            "The architecture '" + imageArchitecture.get()
                                    + "' of the specified image does not match the architecture supported by instance type '"
                                    + instanceType + "'.",
                            400);
                });
    }

    private String architectureFor(String imageId, String instanceType) {
        Optional<Ec2ImageCatalog.CatalogImage> image = imageCatalog.findByIdOrAlias(imageId);
        return image.map(catalogImage -> catalogImage.architecture)
                .filter(value -> !value.isBlank())
                .or(() -> instanceTypeCatalog.find(instanceType)
                        .flatMap(type -> type.supportedArchitectures.stream()
                                .filter(value -> value != null && !value.isBlank())
                                .findFirst()))
                .orElse("x86_64");
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
                    .forEach(i -> {
                        i.setState(InstanceState.running());
                        instances.put(key(i.getRegion(), i.getInstanceId()), i);
                    });
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

    /**
     * Stops the Docker containers of running instances on emulator shutdown. Without this
     * they outlive the process as orphans. Instances flip to {@code stopped} — the container
     * really is stopped, and the id is kept so StartInstances can revive it after a restart.
     * Runs during the ShutdownEvent phase, so the state change is captured by the final flush.
     */
    @Override
    public void stopManagedContainers() {
        if (config.services().ec2().mock()) {
            return;
        }
        for (String storeKey : Set.copyOf(instances.keys())) {
            Instance inst = instances.get(storeKey).orElse(null);
            if (inst == null || inst.getDockerContainerId() == null
                    || inst.getState() == null || !"running".equals(inst.getState().getName())) {
                continue;
            }
            try {
                containerManager.stopForShutdown(inst);
                inst.setState(InstanceState.stopped());
                instances.put(storeKey, inst);
            } catch (Exception e) {
                LOG.warnv("Failed to stop EC2 instance container {0} on shutdown: {1}",
                        inst.getDockerContainerId(), e.getMessage());
            }
        }
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
                    .forEach(i -> {
                        i.setState(InstanceState.running());
                        instances.put(key(i.getRegion(), i.getInstanceId()), i);
                    });
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

    /**
     * Replaces the security groups attached to an instance (ModifyInstanceAttribute with
     * {@code GroupId.N}). Validates each group, updates the instance and its network interfaces,
     * and re-publishes host forwards so ports opened by the newly attached groups take effect.
     */
    public void modifyInstanceGroups(String region, String instanceId, List<String> groupIds) {
        ensureDefaultResources(region);
        Instance inst = getRequiredInstance(region, instanceId);

        List<GroupIdentifier> identifiers = new ArrayList<>();
        for (String groupId : groupIds) {
            SecurityGroup sg = getRequiredSecurityGroup(region, groupId);
            identifiers.add(new GroupIdentifier(sg.getGroupId(), sg.getGroupName()));
        }

        inst.setSecurityGroups(new ArrayList<>(identifiers));
        if (inst.getNetworkInterfaces() != null) {
            inst.getNetworkInterfaces().forEach(eni -> eni.setGroups(new ArrayList<>(identifiers)));
        }
        instances.put(key(region, instanceId), inst);

        if (config.services().ec2().publishSecurityGroupPorts() && !config.services().ec2().mock()
                && inst.getDockerContainerId() != null
                && inst.getState() != null && "running".equals(inst.getState().getName())) {
            portForwardManager.reconcile(inst, desiredPublishedPorts(region, inst));
        }
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
        createDefaultNetworkAcl(region, vpcId, "acl-" + randomHex(17));
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

    // ─── DHCP Options ──────────────────────────────────────────────────────────

    private static final Set<String> VALID_DHCP_OPTION_KEYS = Set.of(
            "domain-name", "domain-name-servers", "ntp-servers",
            "netbios-name-servers", "netbios-node-type");
    private static final Set<String> LIMITED_DHCP_OPTION_KEYS = Set.of(
            "domain-name-servers", "ntp-servers", "netbios-name-servers");
    private static final Set<String> VALID_NETBIOS_NODE_TYPES = Set.of("1", "2", "4", "8");

    // AWS assigns the default DHCP options set's domain-name based on region: us-east-1 gets
    // the historical "ec2.internal", every other region gets "<region>.compute.internal".
    private String defaultDomainName(String region) {
        return "us-east-1".equals(region) ? "ec2.internal" : region + ".compute.internal";
    }

    private void validateDhcpConfiguration(DhcpConfiguration configuration) {
        String key = configuration.getKey();
        if (key == null || !VALID_DHCP_OPTION_KEYS.contains(key)) {
            throw new AwsException("InvalidParameterValue",
                    "The value '" + key + "' is invalid for dhcpConfiguration.key. Allowed values are: "
                            + "domain-name-servers, domain-name, ntp-servers, netbios-name-servers, "
                            + "netbios-node-type", 400);
        }
        List<String> values = configuration.getValues();
        if (values == null || values.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "The dhcpConfiguration '" + key + "' must have at least one value.", 400);
        }
        if (LIMITED_DHCP_OPTION_KEYS.contains(key) && values.size() > 4) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + String.join(",", values) + ") for parameter " + key
                            + " is invalid. The maximum number of values is 4.", 400);
        }
        if ("netbios-node-type".equals(key) && (values.size() > 1 || !VALID_NETBIOS_NODE_TYPES.contains(values.get(0)))) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + String.join(",", values) + ") for parameter netbios-node-type is invalid. "
                            + "Valid values are 1, 2, 4, 8.", 400);
        }
    }

    public DhcpOptions createDhcpOptions(String region, List<DhcpConfiguration> configurations, List<Tag> dhcpTags) {
        ensureDefaultResources(region);
        if (configurations == null || configurations.isEmpty()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter DhcpConfiguration.", 400);
        }
        configurations.forEach(this::validateDhcpConfiguration);

        DhcpOptions options = new DhcpOptions();
        String dhcpOptionsId = "dopt-" + randomHex(17);
        options.setDhcpOptionsId(dhcpOptionsId);
        options.setOwnerId(accountId);
        options.setRegion(region);
        options.setDhcpConfigurationSet(new ArrayList<>(configurations));
        if (dhcpTags != null && !dhcpTags.isEmpty()) {
            options.setTags(new ArrayList<>(dhcpTags));
            tags.put(dhcpOptionsId, new ArrayList<>(dhcpTags));
        }
        dhcpOptionsSets.put(key(region, dhcpOptionsId), options);
        return options;
    }

    public List<DhcpOptions> describeDhcpOptions(String region, List<String> dhcpOptionsIds,
                                                 Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        if (!dhcpOptionsIds.isEmpty()) {
            for (String id : dhcpOptionsIds) {
                getRequiredDhcpOptions(region, id);
            }
        }
        return dhcpOptionsSets.scan(k -> true).stream()
                .filter(o -> region.equals(o.getRegion()))
                .filter(o -> dhcpOptionsIds.isEmpty() || dhcpOptionsIds.contains(o.getDhcpOptionsId()))
                .filter(o -> matchesFilters(o, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteDhcpOptions(String region, String dhcpOptionsId) {
        ensureDefaultResources(region);
        getRequiredDhcpOptions(region, dhcpOptionsId);
        boolean stillAssociated = vpcs.scan(k -> true).stream()
                .anyMatch(v -> region.equals(v.getRegion()) && dhcpOptionsId.equals(v.getDhcpOptionsId()));
        if (stillAssociated) {
            throw new AwsException("DependencyViolation",
                    "The dhcpOptions '" + dhcpOptionsId + "' has dependencies and cannot be deleted.", 400);
        }
        dhcpOptionsSets.delete(key(region, dhcpOptionsId));
        tags.delete(dhcpOptionsId);
    }

    // AssociateDhcpOptions accepts the literal "default" to fall back to the region's default
    // DHCP options set instead of a real dopt-* id (see AWS API docs for AssociateDhcpOptions).
    public void associateDhcpOptions(String region, String dhcpOptionsId, String vpcId) {
        ensureDefaultResources(region);
        Vpc vpc = getRequiredVpc(region, vpcId);
        String resolvedId = "default".equals(dhcpOptionsId)
                ? "dopt-default"
                : getRequiredDhcpOptions(region, dhcpOptionsId).getDhcpOptionsId();
        vpc.setDhcpOptionsId(resolvedId);
        vpcs.put(key(region, vpcId), vpc);
    }

    private DhcpOptions getRequiredDhcpOptions(String region, String dhcpOptionsId) {
        return dhcpOptionsSets.get(key(region, dhcpOptionsId)).orElseThrow(() ->
                new AwsException("InvalidDhcpOptionID.NotFound",
                        "The dhcpOptions ID '" + dhcpOptionsId + "' does not exist", 400));
    }

    // ─── VPC Endpoints ────────────────────────────────────────────────────────

    public VpcEndpoint createVpcEndpoint(String region, String vpcId, String serviceName, String endpointType,
                                         List<String> routeTableIds, List<String> subnetIds,
                                         List<String> securityGroupIds, Boolean privateDnsEnabled,
                                         String policyDocument, List<Tag> endpointTags) {
        return createVpcEndpoint(region, vpcId, serviceName, endpointType, routeTableIds, subnetIds,
                securityGroupIds, privateDnsEnabled, policyDocument, endpointTags, List.of());
    }

    public VpcEndpoint createVpcEndpoint(String region, String vpcId, String serviceName, String endpointType,
                                         List<String> routeTableIds, List<String> subnetIds,
                                         List<String> securityGroupIds, Boolean privateDnsEnabled,
                                         String policyDocument, List<Tag> endpointTags,
                                         List<VpcEndpointSubnetConfiguration> subnetConfigurations) {
        ensureDefaultResources(region);
        getRequiredVpc(region, vpcId);
        for (String routeTableId : routeTableIds) {
            getRequiredRouteTable(region, routeTableId);
        }
        // A caller may name a subnet only through SubnetConfiguration and omit the flat SubnetId
        // list entirely; AWS accepts either form.
        List<String> effectiveSubnetIds = !subnetIds.isEmpty() ? subnetIds
                : subnetConfigurations.stream().map(VpcEndpointSubnetConfiguration::getSubnetId).toList();
        for (String subnetId : effectiveSubnetIds) {
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
        boolean isInterface = "Interface".equalsIgnoreCase(endpoint.getVpcEndpointType());
        endpoint.setPrivateDnsEnabled(privateDnsEnabled != null ? privateDnsEnabled : isInterface);
        endpoint.setCreationTimestamp(Instant.now());
        endpoint.setRegion(region);
        endpoint.setRouteTableIds(new ArrayList<>(routeTableIds));
        endpoint.setSubnetIds(new ArrayList<>(effectiveSubnetIds));
        endpoint.setSecurityGroupIds(new ArrayList<>(securityGroupIds));
        endpoint.setSubnetConfigurations(new ArrayList<>(subnetConfigurations));
        endpoint.setPolicyDocument(policyDocument != null && !policyDocument.isBlank()
                ? policyDocument : VpcEndpoint.DEFAULT_POLICY_DOCUMENT);
        endpoint.setOwnerId(accountId);
        endpoint.setServiceRegion(region);
        // AWS reports service-defined DNS records on gateway endpoints and ipv4 on interface ones.
        endpoint.setDnsRecordIpType(isInterface ? "ipv4" : "service-defined");
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

    /**
     * Applies a {@code ModifyVpcEndpoint} request. Every list parameter is an add/remove delta
     * against the stored endpoint, matching AWS: adds are idempotent (re-adding an attached route
     * table is a no-op that still returns {@code true}) and every referenced id is validated, so a
     * typo fails the call instead of silently changing nothing.
     *
     * <p>Route-table and subnet parameters are rejected against the wrong endpoint type with the
     * same {@code InvalidParameter} messages AWS returns.
     */
    public VpcEndpoint modifyVpcEndpoint(String region, String endpointId, boolean resetPolicy,
                                         String policyDocument,
                                         List<String> addRouteTableIds, List<String> removeRouteTableIds,
                                         List<String> addSubnetIds, List<String> removeSubnetIds,
                                         List<String> addSecurityGroupIds, List<String> removeSecurityGroupIds,
                                         Boolean privateDnsEnabled, String ipAddressType,
                                         String dnsRecordIpType) {
        ensureDefaultResources(region);
        if (endpointId == null || endpointId.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter VpcEndpointId.", 400);
        }
        synchronized (lockFor(key(region, endpointId))) {
            VpcEndpoint endpoint = getRequiredVpcEndpoint(region, endpointId);
            boolean isGateway = "Gateway".equalsIgnoreCase(endpoint.getVpcEndpointType());

            if (!isGateway && !(addRouteTableIds.isEmpty() && removeRouteTableIds.isEmpty())) {
                throw new AwsException("InvalidParameter",
                        "Route table IDs are only supported for Gateway type VPC Endpoint.", 400);
            }
            if (isGateway && !(addSubnetIds.isEmpty() && removeSubnetIds.isEmpty())) {
                throw new AwsException("InvalidParameter",
                        "Subnet IDs are only supported for Interface and GatewayLoadBalancer type VPC Endpoints.",
                        400);
            }
            for (String routeTableId : addRouteTableIds) {
                getRequiredRouteTable(region, routeTableId);
            }
            for (String routeTableId : removeRouteTableIds) {
                getRequiredRouteTable(region, routeTableId);
            }
            for (String subnetId : addSubnetIds) {
                requireSubnet(region, subnetId);
            }
            for (String subnetId : removeSubnetIds) {
                requireSubnet(region, subnetId);
            }
            for (String securityGroupId : addSecurityGroupIds) {
                getRequiredSecurityGroup(region, securityGroupId);
            }
            for (String securityGroupId : removeSecurityGroupIds) {
                getRequiredSecurityGroup(region, securityGroupId);
            }

            if (resetPolicy) {
                endpoint.setPolicyDocument(VpcEndpoint.DEFAULT_POLICY_DOCUMENT);
            } else if (policyDocument != null && !policyDocument.isBlank()) {
                endpoint.setPolicyDocument(policyDocument);
            }
            endpoint.setRouteTableIds(applyIdDelta(endpoint.getRouteTableIds(), addRouteTableIds, removeRouteTableIds));
            endpoint.setSubnetIds(applyIdDelta(endpoint.getSubnetIds(), addSubnetIds, removeSubnetIds));
            endpoint.setSecurityGroupIds(
                    applyIdDelta(endpoint.getSecurityGroupIds(), addSecurityGroupIds, removeSecurityGroupIds));
            if (privateDnsEnabled != null) {
                endpoint.setPrivateDnsEnabled(privateDnsEnabled);
            }
            if (ipAddressType != null && !ipAddressType.isBlank()) {
                endpoint.setIpAddressType(ipAddressType);
            }
            if (dnsRecordIpType != null && !dnsRecordIpType.isBlank()) {
                endpoint.setDnsRecordIpType(dnsRecordIpType);
            }
            vpcEndpoints.put(key(region, endpointId), endpoint);
            return endpoint;
        }
    }

    /** Removals first, then adds, keeping insertion order and never duplicating an existing id. */
    private static List<String> applyIdDelta(List<String> current, List<String> add, List<String> remove) {
        List<String> result = new ArrayList<>(current != null ? current : List.of());
        result.removeAll(remove);
        for (String id : add) {
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /** The ENIs an interface endpoint owns, as reported by {@code DescribeVpcEndpoints}. */
    public static List<String> endpointNetworkInterfaceIds(VpcEndpoint endpoint) {
        if (!"Interface".equalsIgnoreCase(endpoint.getVpcEndpointType())) {
            return List.of();
        }
        return endpoint.getSubnetIds().stream()
                .map(subnetId -> endpointEniId(endpoint.getVpcEndpointId(), subnetId))
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

    /**
     * Network interfaces owned by interface VPC endpoints (PrivateLink ENIs).
     * Floci does not persist per-endpoint ENIs; they are synthesized
     * deterministically from the endpoint's subnets so flow-log generation can
     * attribute AWS-service traffic to a stable endpoint address.
     */
    public List<NetworkInterface> endpointNetworkInterfaces(String region) {
        List<NetworkInterface> result = new ArrayList<>();
        for (VpcEndpoint endpoint : vpcEndpoints.scan(k -> true)) {
            if (!region.equals(endpoint.getRegion())
                    || !"Interface".equalsIgnoreCase(endpoint.getVpcEndpointType())) {
                continue;
            }
            for (String subnetId : endpoint.getSubnetIds()) {
                Subnet subnet = subnets.get(key(region, subnetId)).orElse(null);
                if (subnet == null) {
                    continue;
                }
                NetworkInterface ni = new NetworkInterface();
                ni.setNetworkInterfaceId(endpointEniId(endpoint.getVpcEndpointId(), subnetId));
                ni.setSubnetId(subnetId);
                ni.setVpcId(endpoint.getVpcId());
                ni.setAvailabilityZone(subnet.getAvailabilityZone());
                ni.setDescription("VPC Endpoint Interface " + endpoint.getVpcEndpointId());
                ni.setInterfaceType("vpc_endpoint");
                ni.setPrivateIpAddress(endpointPrivateIp(subnet, endpoint, subnetId));
                ni.setStatus("in-use");
                ni.setOwnerId(accountId);
                ni.setGroups(endpoint.getSecurityGroupIds().stream()
                        .map(groupId -> {
                            GroupIdentifier group = new GroupIdentifier();
                            group.setGroupId(groupId);
                            SecurityGroup sg = securityGroups.get(key(region, groupId)).orElse(null);
                            group.setGroupName(sg != null ? sg.getGroupName() : null);
                            return group;
                        })
                        .collect(Collectors.toList()));
                NetworkInterfacePrivateIpAddress primaryIp = new NetworkInterfacePrivateIpAddress();
                primaryIp.setPrivateIpAddress(ni.getPrivateIpAddress());
                primaryIp.setPrimary(true);
                ni.getPrivateIpAddresses().add(primaryIp);
                result.add(ni);
            }
        }
        return result;
    }

    private static String endpointEniId(String endpointId, String subnetId) {
        String hex = java.util.UUID.nameUUIDFromBytes(
                (endpointId + "|" + subnetId).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return "eni-" + hex.substring(0, 17);
    }

    /**
     * The ENI's private IP for one of the endpoint's subnets: the address the caller pinned via
     * {@code CreateVpcEndpoint}'s {@code SubnetConfiguration} for that subnet, if any, else a
     * stable host address near the top of the subnet range, clear of the instance counter (starts
     * at 10). A pinned address must win here and never fall back to the synthesized one, or a
     * caller who set it explicitly sees it change out from under them on every describe.
     */
    private static String endpointPrivateIp(Subnet subnet, VpcEndpoint endpoint, String subnetId) {
        for (VpcEndpointSubnetConfiguration config : endpoint.getSubnetConfigurations()) {
            if (subnetId.equals(config.getSubnetId()) && config.getIpv4() != null && !config.getIpv4().isBlank()) {
                return config.getIpv4();
            }
        }
        String cidr = subnet.getCidrBlock();
        String baseIp = cidr != null ? cidr.split("/")[0] : "172.31.0.0";
        String[] parts = baseIp.split("\\.");
        int host = 200 + Math.floorMod(endpoint.getVpcEndpointId().hashCode(), 50);
        return parts[0] + "." + parts[1] + "." + parts[2] + "." + host;
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
        if (vpcId == null || vpcId.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter VpcId", 400);
        }
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

        // Every subnet starts associated with its VPC's default NACL. ReplaceNetworkAclAssociation
        // later moves it onto a custom NACL, so this association must exist for that lookup to work.
        NetworkAcl defaultAcl = findDefaultNetworkAcl(region, vpcId);
        if (defaultAcl != null) {
            NetworkAclAssociation assoc = new NetworkAclAssociation();
            assoc.setNetworkAclAssociationId("aclassoc-" + randomHex(17));
            assoc.setNetworkAclId(defaultAcl.getNetworkAclId());
            assoc.setSubnetId(subnetId);
            defaultAcl.getAssociations().add(assoc);
            networkAcls.put(key(region, defaultAcl.getNetworkAclId()), defaultAcl);
        }
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
        // Persist the default egress rule as a SecurityGroupRule so that
        // DescribeSecurityGroupRules can find it immediately (#1093).
        createRules(region, sgId, egressAll, true);
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
        List<SecurityGroupRule> rules = authorizeSecurityGroupRules(region, groupId, permissions, false);
        reconcilePublishedPortsForGroup(region, groupId);
        return rules;
    }

    public List<SecurityGroupRule> authorizeSecurityGroupEgress(String region, String groupId, List<IpPermission> permissions) {
        return authorizeSecurityGroupRules(region, groupId, permissions, true);
    }

    /**
     * Adds permissions in either direction.
     *
     * <p>A permission is stored one source at a time, because that is the granularity AWS both
     * authorizes and revokes at: a rule is (protocol, port range, one source), and its description
     * is metadata hanging off it rather than part of its identity. Re-authorizing a rule that
     * already exists is {@code InvalidPermission.Duplicate} even when the description differs, and
     * the whole request fails without adding any of it.
     */
    private List<SecurityGroupRule> authorizeSecurityGroupRules(String region, String groupId,
                                                                List<IpPermission> permissions, boolean egress) {
        ensureDefaultResources(region);
        List<SecurityGroupRule> rules = new ArrayList<>();
        synchronized (lockFor(key(region, groupId))) {
            SecurityGroup sg = getRequiredSecurityGroup(region, groupId);
            List<IpPermission> current = explodePermissions(egress ? sg.getIpPermissionsEgress() : sg.getIpPermissions());
            Set<String> seen = current.stream().map(Ec2Service::permissionSourceKey).collect(Collectors.toSet());

            List<IpPermission> incoming = new ArrayList<>();
            for (IpPermission perm : permissions) {
                resolveGroupReferences(region, sg.getVpcId(), perm);
                incoming.addAll(explodePermissions(List.of(perm)));
            }
            // Checked in full before anything is stored: AWS rejects the whole call, and a request
            // that repeats a rule inside itself is just as duplicate as one repeating a stored rule.
            for (IpPermission atom : incoming) {
                if (!seen.add(permissionSourceKey(atom))) {
                    throw new AwsException("InvalidPermission.Duplicate",
                            "the specified rule \"" + describeRule(atom, egress) + "\" already exists", 400);
                }
            }

            current.addAll(incoming);
            List<IpPermission> next = regroupPermissions(current);
            if (egress) {
                sg.setIpPermissionsEgress(next);
            } else {
                sg.setIpPermissions(next);
            }
            securityGroups.put(key(region, groupId), sg);
            for (IpPermission atom : incoming) {
                rules.addAll(createRules(region, groupId, atom, egress));
            }
        }
        return rules;
    }

    /**
     * Splits permissions into one entry per source, so that two rules sharing a port range but not
     * a source stay separable. A permission carrying no source at all survives as one sourceless
     * entry, matching what Floci has always accepted.
     */
    private static List<IpPermission> explodePermissions(List<IpPermission> permissions) {
        List<IpPermission> atoms = new ArrayList<>();
        if (permissions == null) {
            return atoms;
        }
        for (IpPermission perm : permissions) {
            if (perm == null) {
                continue;
            }
            int before = atoms.size();
            if (perm.getIpRanges() != null) {
                for (IpRange range : perm.getIpRanges()) {
                    IpPermission atom = copyPorts(perm);
                    atom.getIpRanges().add(range);
                    atoms.add(atom);
                }
            }
            if (perm.getIpv6Ranges() != null) {
                for (Ipv6Range range : perm.getIpv6Ranges()) {
                    IpPermission atom = copyPorts(perm);
                    atom.getIpv6Ranges().add(range);
                    atoms.add(atom);
                }
            }
            if (perm.getUserIdGroupPairs() != null) {
                for (UserIdGroupPair pair : perm.getUserIdGroupPairs()) {
                    IpPermission atom = copyPorts(perm);
                    atom.getUserIdGroupPairs().add(pair);
                    atoms.add(atom);
                }
            }
            if (perm.getPrefixListIds() != null) {
                for (PrefixListIdReference ref : perm.getPrefixListIds()) {
                    IpPermission atom = copyPorts(perm);
                    atom.getPrefixListIds().add(ref);
                    atoms.add(atom);
                }
            }
            if (atoms.size() == before) {
                atoms.add(copyPorts(perm));
            }
        }
        return atoms;
    }

    /**
     * Collects single-source entries back into the shape DescribeSecurityGroups returns: one
     * permission per (protocol, from port, to port), carrying every source authorized for it.
     */
    private static List<IpPermission> regroupPermissions(List<IpPermission> atoms) {
        Map<String, IpPermission> byPorts = new LinkedHashMap<>();
        for (IpPermission atom : atoms) {
            IpPermission group = byPorts.computeIfAbsent(permissionPortKey(atom), k -> copyPorts(atom));
            group.getIpRanges().addAll(atom.getIpRanges());
            group.getIpv6Ranges().addAll(atom.getIpv6Ranges());
            group.getUserIdGroupPairs().addAll(atom.getUserIdGroupPairs());
            group.getPrefixListIds().addAll(atom.getPrefixListIds());
        }
        return new ArrayList<>(byPorts.values());
    }

    /**
     * Copies the protocol and port range of a permission, canonicalized the way AWS reports them,
     * so that a caller's spelling never decides whether two rules are the same rule.
     *
     * <p>The all-protocols rule is the one that matters in practice: AWS returns it with no ports
     * at all, but a client that keeps its own normalized copy sends the revoke back as ports 0 to
     * 0. Held apart, those two never match, and a group's default egress rule survives every
     * attempt to remove it.
     */
    private static IpPermission copyPorts(IpPermission perm) {
        IpPermission copy = new IpPermission();
        String protocol = canonicalProtocol(perm.getIpProtocol());
        copy.setIpProtocol(protocol);
        if (!ALL_PROTOCOLS.equals(protocol)) {
            copy.setFromPort(perm.getFromPort());
            copy.setToPort(perm.getToPort());
        }
        return copy;
    }

    private static final String ALL_PROTOCOLS = "-1";

    /**
     * The protocol spelling AWS returns. A caller may give a name or an IANA number; AWS answers
     * with the name for the three protocols that have one, the number otherwise, and {@code -1}
     * for every protocol at once.
     */
    private static String canonicalProtocol(String protocol) {
        if (protocol == null) {
            return null;
        }
        String lower = protocol.toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "all" -> ALL_PROTOCOLS;
            case "1" -> "icmp";
            case "6" -> "tcp";
            case "17" -> "udp";
            case "58" -> "icmpv6";
            default -> lower;
        };
    }

    private static String permissionPortKey(IpPermission perm) {
        return portKey(perm.getIpProtocol(), perm.getFromPort(), perm.getToPort());
    }

    private static String portKey(String protocol, Integer fromPort, Integer toPort) {
        String canonical = canonicalProtocol(protocol);
        if (ALL_PROTOCOLS.equals(canonical)) {
            return canonical + "|all";
        }
        return canonical + "|" + fromPort + "|" + toPort;
    }

    /**
     * Identity of a single-source permission. Descriptions are left out on purpose: AWS calls two
     * rules the same rule when they agree on protocol, ports and source, whatever they say about
     * themselves.
     */
    private static String permissionSourceKey(IpPermission atom) {
        return permissionPortKey(atom) + "|" + permissionSource(atom);
    }

    private static String permissionSource(IpPermission atom) {
        if (!atom.getIpRanges().isEmpty()) {
            return "cidr4:" + atom.getIpRanges().get(0).getCidrIp();
        }
        if (!atom.getIpv6Ranges().isEmpty()) {
            return "cidr6:" + atom.getIpv6Ranges().get(0).getCidrIpv6();
        }
        if (!atom.getUserIdGroupPairs().isEmpty()) {
            UserIdGroupPair pair = atom.getUserIdGroupPairs().get(0);
            return "sg:" + pair.getUserId() + "/" + (pair.getGroupId() != null ? pair.getGroupId() : pair.getGroupName());
        }
        if (!atom.getPrefixListIds().isEmpty()) {
            return "pl:" + atom.getPrefixListIds().get(0).getPrefixListId();
        }
        return "none";
    }

    private static String ruleSourceKey(SecurityGroupRule rule) {
        String source;
        if (rule.getCidrIpv4() != null) {
            source = "cidr4:" + rule.getCidrIpv4();
        } else if (rule.getCidrIpv6() != null) {
            source = "cidr6:" + rule.getCidrIpv6();
        } else if (rule.getReferencedGroupInfo() != null) {
            source = "sg:" + rule.getReferencedGroupInfo().getUserId() + "/" + rule.getReferencedGroupInfo().getGroupId();
        } else if (rule.getPrefixListId() != null) {
            source = "pl:" + rule.getPrefixListId();
        } else {
            source = "none";
        }
        return portKey(rule.getIpProtocol(), rule.getFromPort(), rule.getToPort()) + "|" + source;
    }

    /** The rule wording AWS puts in an InvalidPermission.Duplicate message. */
    private static String describeRule(IpPermission atom, boolean egress) {
        String peer = permissionSource(atom);
        int colon = peer.indexOf(':');
        String peerValue = colon >= 0 ? peer.substring(colon + 1) : peer;
        StringBuilder sb = new StringBuilder("peer: ").append(peerValue)
                .append(", ").append(atom.getIpProtocol());
        if (atom.getFromPort() != null) {
            sb.append(", from port: ").append(atom.getFromPort());
        }
        if (atom.getToPort() != null) {
            sb.append(", to port: ").append(atom.getToPort());
        }
        return sb.append(egress ? ", EGRESS, ALLOW" : ", ALLOW").toString();
    }

    /**
     * Flattens one permission into the {@link SecurityGroupRule} entries DescribeSecurityGroupRules
     * serves. AWS gives every rule exactly one source, so a permission carrying several sources fans
     * out into one rule each.
     */
    private List<SecurityGroupRule> createRules(String region, String groupId, IpPermission perm, boolean egress) {
        List<SecurityGroupRule> rules = new ArrayList<>();
        if (perm.getIpRanges() != null) {
            for (IpRange range : perm.getIpRanges()) {
                SecurityGroupRule rule = newRule(groupId, perm, egress);
                rule.setCidrIpv4(range.getCidrIp());
                rule.setDescription(range.getDescription());
                rules.add(rule);
            }
        }
        if (perm.getIpv6Ranges() != null) {
            for (Ipv6Range range : perm.getIpv6Ranges()) {
                SecurityGroupRule rule = newRule(groupId, perm, egress);
                rule.setCidrIpv6(range.getCidrIpv6());
                rule.setDescription(range.getDescription());
                rules.add(rule);
            }
        }
        if (perm.getUserIdGroupPairs() != null) {
            for (UserIdGroupPair pair : perm.getUserIdGroupPairs()) {
                SecurityGroupRule rule = newRule(groupId, perm, egress);
                ReferencedSecurityGroup ref = new ReferencedSecurityGroup();
                ref.setGroupId(pair.getGroupId());
                ref.setUserId(pair.getUserId());
                rule.setReferencedGroupInfo(ref);
                rule.setDescription(pair.getDescription());
                rules.add(rule);
            }
        }
        if (perm.getPrefixListIds() != null) {
            for (PrefixListIdReference ref : perm.getPrefixListIds()) {
                SecurityGroupRule rule = newRule(groupId, perm, egress);
                rule.setPrefixListId(ref.getPrefixListId());
                rule.setDescription(ref.getDescription());
                rules.add(rule);
            }
        }
        // Real AWS rejects a permission with no source at all; Floci keeps accepting it, so it still
        // needs a rule to describe.
        if (rules.isEmpty()) {
            rules.add(newRule(groupId, perm, egress));
        }
        for (SecurityGroupRule rule : rules) {
            securityGroupRules.put(key(region, rule.getSecurityGroupRuleId()), rule);
        }
        return rules;
    }

    private SecurityGroupRule newRule(String groupId, IpPermission perm, boolean egress) {
        SecurityGroupRule rule = new SecurityGroupRule();
        rule.setSecurityGroupRuleId("sgr-" + randomHex(17));
        rule.setGroupId(groupId);
        rule.setGroupOwnerId(accountId);
        rule.setEgress(egress);
        rule.setIpProtocol(perm.getIpProtocol());
        rule.setFromPort(perm.getFromPort());
        rule.setToPort(perm.getToPort());
        return rule;
    }

    /**
     * Fills in the source details AWS returns but a caller may leave out: an absent {@code UserId}
     * is this account, and a reference made by group name is resolved to its group id so the
     * flattened rule can carry a {@code referencedGroupInfo} (the AWS shape has no group name).
     *
     * <p>Group names are unique per VPC rather than per region, so resolution is confined to the
     * VPC of the group being authorized. A name matching nothing there stays unresolved: Floci does
     * not check that a referenced group exists, for ids either.
     */
    private void resolveGroupReferences(String region, String vpcId, IpPermission perm) {
        if (perm.getUserIdGroupPairs() == null) {
            return;
        }
        for (UserIdGroupPair pair : perm.getUserIdGroupPairs()) {
            if (pair.getUserId() == null) {
                pair.setUserId(accountId);
            }
            if (pair.getGroupId() == null && pair.getGroupName() != null) {
                securityGroups.scan(k -> true).stream()
                        .filter(sg -> sg.getRegion().equals(region)
                                && Objects.equals(vpcId, sg.getVpcId())
                                && pair.getGroupName().equals(sg.getGroupName()))
                        .findFirst()
                        .ifPresent(sg -> pair.setGroupId(sg.getGroupId()));
            }
        }
    }

    public void revokeSecurityGroupIngress(String region, String groupId, List<IpPermission> permissions) {
        revokeSecurityGroupRules(region, groupId, permissions, false);
        reconcilePublishedPortsForGroup(region, groupId);
    }

    public void revokeSecurityGroupEgress(String region, String groupId, List<IpPermission> permissions) {
        revokeSecurityGroupRules(region, groupId, permissions, true);
    }

    /**
     * Removes permissions in either direction, one source at a time. Revoking tcp/443 from
     * 10.0.0.0/8 leaves tcp/443 from 10.1.0.0/16 alone, and the flattened rules
     * DescribeSecurityGroupRules serves go with the permission they came from.
     */
    private void revokeSecurityGroupRules(String region, String groupId,
                                          List<IpPermission> permissions, boolean egress) {
        ensureDefaultResources(region);
        synchronized (lockFor(key(region, groupId))) {
            SecurityGroup sg = getRequiredSecurityGroup(region, groupId);
            Set<String> doomed = new HashSet<>();
            for (IpPermission perm : permissions) {
                resolveGroupReferences(region, sg.getVpcId(), perm);
                for (IpPermission atom : explodePermissions(List.of(perm))) {
                    doomed.add(permissionSourceKey(atom));
                }
            }
            List<IpPermission> current = explodePermissions(egress ? sg.getIpPermissionsEgress() : sg.getIpPermissions());
            current.removeIf(atom -> doomed.contains(permissionSourceKey(atom)));
            List<IpPermission> next = regroupPermissions(current);
            if (egress) {
                sg.setIpPermissionsEgress(next);
            } else {
                sg.setIpPermissions(next);
            }
            securityGroups.put(key(region, groupId), sg);

            String regionPrefix = region + "::";
            for (SecurityGroupRule rule : securityGroupRules.scan(k -> k.startsWith(regionPrefix))) {
                if (groupId.equals(rule.getGroupId()) && rule.isEgress() == egress
                        && doomed.contains(ruleSourceKey(rule))) {
                    securityGroupRules.delete(key(region, rule.getSecurityGroupRuleId()));
                }
            }
        }
    }

    private SecurityGroup getRequiredSecurityGroup(String region, String groupId) {
        SecurityGroup sg = securityGroups.get(key(region, groupId)).orElse(null);
        if (sg == null)
            throw new AwsException("InvalidGroup.NotFound", "The security group '" + groupId + "' does not exist", 400);

        return sg;
    }

    public List<SecurityGroupRule> describeSecurityGroupRules(String region, List<String> groupIds, List<String> ruleIds) {
        ensureDefaultResources(region);
        String regionPrefix = region + "::";
        return securityGroupRules.scan(k -> k.startsWith(regionPrefix)).stream()
                .filter(r -> groupIds.isEmpty() || groupIds.contains(r.getGroupId()))
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
        List<KeyPair> regionKeyPairs = keyPairs.scan(k -> true).stream()
                .filter(k -> k.getRegion().equals(region))
                .collect(Collectors.toList());

        // A named/id lookup for a key pair that does not exist is an error in real
        // AWS (InvalidKeyPair.NotFound), not an empty result — otherwise idempotent
        // callers that treat exit 0 as "present" skip creating the key.
        for (String keyName : keyNames) {
            if (regionKeyPairs.stream().noneMatch(k -> keyName.equals(k.getKeyName()))) {
                throw new AwsException("InvalidKeyPair.NotFound",
                        "The key pair '" + keyName + "' does not exist", 400);
            }
        }
        for (String keyPairId : keyPairIds) {
            if (regionKeyPairs.stream().noneMatch(k -> keyPairId.equals(k.getKeyPairId()))) {
                throw new AwsException("InvalidKeyPair.NotFound",
                        "The key pair ID '" + keyPairId + "' does not exist", 400);
            }
        }

        return regionKeyPairs.stream()
                .filter(k -> keyNames.isEmpty() || keyNames.contains(k.getKeyName()))
                .filter(k -> keyPairIds.isEmpty() || keyPairIds.contains(k.getKeyPairId()))
                .collect(Collectors.toList());
    }

    public void deleteKeyPair(String region, String keyName, String keyPairId) {
        ensureDefaultResources(region);
        if (keyPairId != null && !keyPairId.isEmpty()) {
            keyPairs.delete(key(region, keyPairId));
        } else {
            // scan() returns a detached copy, so the key pair has to be resolved to its
            // store key and deleted through the backend — mutating the scan result does
            // not touch the store.
            keyPairs.scan(k -> true).stream()
                    .filter(k -> k.getRegion().equals(region) && k.getKeyName().equals(keyName))
                    .map(KeyPair::getKeyPairId)
                    .forEach(id -> keyPairs.delete(key(region, id)));
        }
    }

    public KeyPair importKeyPair(String region, String keyName, String publicKeyMaterial) {
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
        if (instance == null) {
            return false;
        }
        // Mock mode and instances launched with no Docker daemon reachable have nothing to
        // inspect, so the recorded lifecycle state is the answer. Reporting those as not
        // running would make AutoScaling replace healthy instances in a loop.
        if (config.services().ec2().mock() || instance.getDockerContainerId() == null) {
            String state = instance.getState() != null ? instance.getState().getName() : null;
            return state == null
                    || (!"shutting-down".equals(state) && !"terminated".equals(state) && !"stopping".equals(state));
        }
        return containerManager.isContainerRunning(instance.getDockerContainerId());
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
        List<Image> catalogImages = imageCatalog.images().stream()
                .filter(Ec2ImageCatalog.CatalogImage::advertised)
                .filter(img -> img.matchesIdOrAlias(imageIds))
                .filter(img -> img.matchesOwner(owners))
                .filter(img -> matchesImageFilters(img, filters))
                .map(Ec2ImageCatalog.CatalogImage::toImage)
                .collect(Collectors.toList());
        List<Image> createdImages = registeredImages.scan(k -> true).stream()
                .filter(img -> region.equals(img.getRegion()))
                .filter(img -> matchesImageIds(img, imageIds))
                .filter(img -> matchesImageOwners(img, owners))
                .filter(img -> matchesRegisteredImageFilters(img, filters))
                .collect(Collectors.toList());
        List<Image> images = new ArrayList<>(catalogImages);
        images.addAll(createdImages);
        return images;
    }

    public Image createImage(String region, String instanceId, String name, String description,
                             boolean noReboot) {
        ensureDefaultResources(region);
        if (instanceId == null || instanceId.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter InstanceId", 400);
        }
        Instance source = getRequiredInstance(region, instanceId);

        // AWS reboots the source instance by default so the image is captured from a quiesced
        // file system; NoReboot=true opts out and accepts the integrity risk.
        if (!noReboot) {
            rebootInstances(region, List.of(instanceId));
        }

        // The new AMI inherits what it was captured from rather than the registerImage defaults,
        // so DescribeImages does not report a generic x86_64 / /dev/sda1 image with no devices.
        Image sourceImage = findImageForCapture(region, source.getImageId());
        Image image = registerImage(region, name, description,
                sourceImage != null ? sourceImage.getArchitecture() : null,
                sourceImage != null ? sourceImage.getRootDeviceName() : null,
                captureBlockDeviceMappings(region, source, sourceImage));

        // Carry the launchable ancestor so RunInstances on this AMI starts the same guest instead
        // of falling through to the catalog default.
        image.setSourceImageId(resolveLaunchableImageId(region, source.getImageId()));
        registeredImages.put(key(region, image.getImageId()), image);
        return image;
    }

    /**
     * The devices the captured AMI reports. AWS captures what the source AMI describes plus any
     * volume attached to the instance afterwards, so a data volume added post-launch is part of
     * the image rather than being dropped.
     */
    private List<BlockDeviceMapping> captureBlockDeviceMappings(String region, Instance source,
                                                                Image sourceImage) {
        List<BlockDeviceMapping> mappings = new ArrayList<>(sourceImageMappings(sourceImage));
        Set<String> devices = mappings.stream()
                .map(BlockDeviceMapping::getDeviceName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (Volume volume : volumes.scan(k -> true)) {
            if (!region.equals(volume.getRegion())
                    || volume.getVolumeId().equals(source.getRootVolumeId())) {
                continue;
            }
            for (VolumeAttachment attachment : volume.getAttachments()) {
                if (!source.getInstanceId().equals(attachment.getInstanceId())
                        || !devices.add(attachment.getDevice())) {
                    continue;
                }
                mappings.add(attachedMapping(volume, attachment));
            }
        }
        return mappings.isEmpty() ? null : mappings;
    }

    /** The device an attached volume contributes, snapshotted as of the capture. */
    private BlockDeviceMapping attachedMapping(Volume volume, VolumeAttachment attachment) {
        EbsBlockDevice ebs = new EbsBlockDevice();
        ebs.setSnapshotId("snap-" + randomHex(17));
        ebs.setVolumeSize(volume.getSize());
        ebs.setVolumeType(volume.getVolumeType());
        ebs.setDeleteOnTermination(attachment.isDeleteOnTermination());
        ebs.setEncrypted(volume.isEncrypted());
        BlockDeviceMapping mapping = new BlockDeviceMapping();
        mapping.setDeviceName(attachment.getDevice());
        mapping.setEbs(ebs);
        return mapping;
    }

    /**
     * A registered source carries its own mappings, while a catalog entry describes only its root
     * device, so the root is rebuilt from that rather than leaving the capture with no devices.
     */
    private List<BlockDeviceMapping> sourceImageMappings(Image sourceImage) {
        if (sourceImage == null) {
            return List.of();
        }
        List<BlockDeviceMapping> declared = sourceImage.getBlockDeviceMappings();
        if (declared != null && !declared.isEmpty()) {
            return declared.stream().map(this::recapture).toList();
        }
        String rootDeviceName = sourceImage.getRootDeviceName();
        if (rootDeviceName == null || rootDeviceName.isBlank()) {
            return List.of();
        }
        EbsBlockDevice ebs = new EbsBlockDevice();
        ebs.setSnapshotId("snap-" + randomHex(17));
        ebs.setVolumeSize(DEFAULT_ROOT_VOLUME_SIZE_GIB);
        ebs.setVolumeType(DEFAULT_ROOT_VOLUME_TYPE);
        ebs.setDeleteOnTermination(true);
        BlockDeviceMapping mapping = new BlockDeviceMapping();
        mapping.setDeviceName(rootDeviceName);
        mapping.setEbs(ebs);
        return List.of(mapping);
    }

    /**
     * A capture takes its own snapshot of each device. Handing back the source AMI's snapshot ids
     * would leave two images sharing one snapshot, so deleting either would appear to take the
     * other's backing with it.
     */
    private BlockDeviceMapping recapture(BlockDeviceMapping source) {
        BlockDeviceMapping mapping = new BlockDeviceMapping();
        mapping.setDeviceName(source.getDeviceName());
        EbsBlockDevice sourceEbs = source.getEbs();
        if (sourceEbs == null) {
            return mapping;
        }
        EbsBlockDevice ebs = new EbsBlockDevice();
        ebs.setSnapshotId(sourceEbs.getSnapshotId() != null ? "snap-" + randomHex(17) : null);
        ebs.setVolumeSize(sourceEbs.getVolumeSize());
        ebs.setVolumeType(sourceEbs.getVolumeType());
        ebs.setDeleteOnTermination(sourceEbs.getDeleteOnTermination());
        ebs.setEncrypted(sourceEbs.getEncrypted());
        mapping.setEbs(ebs);
        return mapping;
    }

    /** The image a CreateImage source was launched from, whether catalog-backed or registered. */
    private Image findImageForCapture(String region, String imageId) {
        if (imageId == null || imageId.isBlank()) {
            return null;
        }
        Image registered = registeredImages.get(key(region, imageId)).orElse(null);
        if (registered != null) {
            return registered;
        }
        return imageCatalog.findByIdOrAlias(imageId)
                .map(Ec2ImageCatalog.CatalogImage::toImage)
                .orElse(null);
    }

    /**
     * Follows CreateImage ancestry back to an id the AMI resolver can map to a guest image.
     * Images from RegisterImage have no source and stop the walk, as does a catalog id.
     */
    private String resolveLaunchableImageId(String region, String imageId) {
        String current = imageId;
        for (int hops = 0; hops < 16 && current != null; hops++) {
            Image registered = registeredImages.get(key(region, current)).orElse(null);
            if (registered == null || registered.getSourceImageId() == null) {
                return current;
            }
            current = registered.getSourceImageId();
        }
        return current;
    }

    public Image registerImage(String region, String name, String description, String architecture,
                               String rootDeviceName, List<BlockDeviceMapping> blockDeviceMappings) {
        if (name == null || name.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter Name", 400);
        }
        boolean duplicateName = registeredImages.scan(k -> true).stream()
                .filter(img -> region.equals(img.getRegion()))
                .anyMatch(img -> name.equals(img.getName()));
        if (duplicateName) {
            throw new AwsException("InvalidAMIName.Duplicate",
                    "AMI name '" + name + "' is already in use.", 400);
        }
        Image image = new Image();
        image.setImageId("ami-" + randomHex(17));
        image.setName(name);
        image.setDescription(description != null ? description : name);
        image.setOwnerId(accountId);
        image.setImageOwnerAlias(null);
        image.setPublic(false);
        image.setArchitecture(architecture != null ? architecture : "x86_64");
        image.setRootDeviceName(rootDeviceName != null ? rootDeviceName : "/dev/sda1");
        image.setRootDeviceType("ebs");
        image.setVirtualizationType("hvm");
        image.setHypervisor("xen");
        image.setCreationDate(ISO_FMT.format(Instant.now()));
        image.setRegion(region);
        image.setBlockDeviceMappings(blockDeviceMappings != null ? new ArrayList<>(blockDeviceMappings) : List.of());
        registeredImages.put(key(region, image.getImageId()), image);
        for (BlockDeviceMapping mapping : image.getBlockDeviceMappings()) {
            EbsBlockDevice ebs = mapping.getEbs();
            if (ebs != null && ebs.getSnapshotId() != null) {
                String snapshotKey = key(region, ebs.getSnapshotId());
                if (snapshots.get(snapshotKey).isEmpty()) {
                    snapshots.put(snapshotKey, snapshotFrom(region, ebs.getSnapshotId(), image, mapping));
                }
            }
        }
        return image;
    }

    public List<Snapshot> describeSnapshots(String region, List<String> snapshotIds,
                                            List<String> ownerIds, Map<String, List<String>> filters) {
        if (snapshotIds != null && !snapshotIds.isEmpty()) {
            for (String id : snapshotIds) {
                if (snapshots.get(key(region, id)).isEmpty()) {
                    throw new AwsException("InvalidSnapshot.NotFound",
                            "The snapshot '" + id + "' does not exist.", 400);
                }
            }
        }
        return snapshots.scan(k -> true).stream()
                .filter(snapshot -> region.equals(snapshot.getRegion()))
                .filter(snapshot -> snapshotIds == null || snapshotIds.isEmpty() || snapshotIds.contains(snapshot.getSnapshotId()))
                .filter(snapshot -> matchesSnapshotOwners(snapshot, ownerIds))
                .filter(snapshot -> matchesSnapshotFilters(snapshot, filters))
                .collect(Collectors.toList());
    }

    // ─── Launch Templates ─────────────────────────────────────────────────────

    public LaunchTemplate createLaunchTemplate(String region, String name, String imageId,
                                               String instanceType, String keyName,
                                               List<String> securityGroupIds, String userData,
                                               String encodedUserData,
                                               String iamInstanceProfileArn,
                                               List<Tag> launchTemplateTags, List<Tag> instanceTags) {
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
        launchTemplate.setCreatedBy(AwsArnUtils.Arn.of("iam", "", accountId, "root").toString());
        launchTemplate.setRegion(region);
        launchTemplate.setImageId(imageId);
        launchTemplate.setInstanceType(instanceType);
        launchTemplate.setKeyName(keyName);
        launchTemplate.setUserData(userData);
        launchTemplate.setEncodedUserData(encodedUserData);
        launchTemplate.setIamInstanceProfileArn(iamInstanceProfileArn);
        if (securityGroupIds != null) {
            launchTemplate.setSecurityGroupIds(new ArrayList<>(securityGroupIds));
        }
        if (launchTemplateTags != null && !launchTemplateTags.isEmpty()) {
            launchTemplate.setTags(new ArrayList<>(launchTemplateTags));
            tags.put(launchTemplate.getLaunchTemplateId(), new ArrayList<>(launchTemplateTags));
        }
        if (instanceTags != null && !instanceTags.isEmpty()) {
            launchTemplate.setInstanceTags(new ArrayList<>(instanceTags));
        }
        launchTemplate.getVersions().put("1", dataFrom(launchTemplate));
        launchTemplates.put(key(region, launchTemplate.getLaunchTemplateId()), launchTemplate);
        return launchTemplate;
    }

    public LaunchTemplate createLaunchTemplateVersion(String region, String id, String name,
                                                      String sourceVersion,
                                                      String imageId, String instanceType, String keyName,
                                                      List<String> securityGroupIds, String userData,
                                                      String encodedUserData,
                                                      String iamInstanceProfileArn,
                                                      List<Tag> instanceTags) {
        ensureDefaultResources(region);
        LaunchTemplate launchTemplate = findLaunchTemplate(region, id, name);
        ensureLaunchTemplateVersions(launchTemplate);
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
            data.setEncodedUserData(encodedUserData);
        }
        if (iamInstanceProfileArn != null && !iamInstanceProfileArn.isBlank()) {
            data.setIamInstanceProfileArn(iamInstanceProfileArn);
        }
        if (securityGroupIds != null && !securityGroupIds.isEmpty()) {
            data.setSecurityGroupIds(securityGroupIds);
        }
        if (instanceTags != null && !instanceTags.isEmpty()) {
            data.setInstanceTags(instanceTags);
        }
        launchTemplate.getVersions().put(String.valueOf(latestVersion), data);
        applyData(launchTemplate, data);
        launchTemplates.put(key(region, launchTemplate.getLaunchTemplateId()), launchTemplate);
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
        ensureLaunchTemplateVersions(launchTemplate);
        if (defaultVersion != null && !defaultVersion.isBlank()) {
            String resolved = switch (defaultVersion) {
                case "$Latest" -> launchTemplate.getLatestVersionNumber();
                case "$Default" -> launchTemplate.getDefaultVersionNumber();
                default -> defaultVersion;
            };
            int requested = parseLaunchTemplateVersion(resolved);
            int latest = parseLaunchTemplateVersion(launchTemplate.getLatestVersionNumber());
            if (requested < 1 || requested > latest
                    || !launchTemplate.getVersions().containsKey(String.valueOf(requested))) {
                throw new AwsException("InvalidLaunchTemplateVersion.NotFound",
                        "The specified launch template version does not exist.", 400);
            }
            launchTemplate.setDefaultVersionNumber(String.valueOf(requested));
        }
        launchTemplates.put(key(region, launchTemplate.getLaunchTemplateId()), launchTemplate);
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

    public LaunchTemplateData resolveLaunchTemplateData(String region, String id, String name, String version) {
        ensureDefaultResources(region);
        LaunchTemplate launchTemplate = findLaunchTemplate(region, id, name);
        String resolvedVersion = resolveLaunchTemplateVersion(
                launchTemplate,
                version,
                launchTemplate.getDefaultVersionNumber());
        return new LaunchTemplateData(versionData(launchTemplate, resolvedVersion));
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

    private void ensureLaunchTemplateVersions(LaunchTemplate launchTemplate) {
        if (!launchTemplate.getVersions().isEmpty()) {
            return;
        }
        launchTemplate.getVersions().put(launchTemplate.getLatestVersionNumber(), dataFrom(launchTemplate));
        launchTemplates.put(key(launchTemplate.getRegion(), launchTemplate.getLaunchTemplateId()), launchTemplate);
    }

    private String resolveLaunchTemplateVersion(LaunchTemplate launchTemplate, String requestedVersion,
                                                String defaultWhenMissing) {
        ensureLaunchTemplateVersions(launchTemplate);
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
        data.setEncodedUserData(launchTemplate.getEncodedUserData());
        data.setIamInstanceProfileArn(launchTemplate.getIamInstanceProfileArn());
        data.setSecurityGroupIds(launchTemplate.getSecurityGroupIds());
        data.setInstanceTags(launchTemplate.getInstanceTags());
        return data;
    }

    private void applyData(LaunchTemplate launchTemplate, LaunchTemplateData data) {
        launchTemplate.setImageId(data.getImageId());
        launchTemplate.setInstanceType(data.getInstanceType());
        launchTemplate.setKeyName(data.getKeyName());
        launchTemplate.setUserData(data.getUserData());
        launchTemplate.setEncodedUserData(data.getEncodedUserData());
        launchTemplate.setIamInstanceProfileArn(data.getIamInstanceProfileArn());
        launchTemplate.setSecurityGroupIds(new ArrayList<>(data.getSecurityGroupIds()));
        launchTemplate.setInstanceTags(data.getInstanceTags());
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

    private boolean matchesImageIds(Image image, List<String> imageIds) {
        return imageIds == null || imageIds.isEmpty() || imageIds.contains(image.getImageId());
    }

    private boolean matchesImageOwners(Image image, List<String> owners) {
        return owners == null || owners.isEmpty()
                || owners.contains(image.getOwnerId())
                || (owners.contains("self") && accountId.equals(image.getOwnerId()));
    }

    private boolean matchesRegisteredImageFilters(Image image, Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
            if (!matchesRegisteredImageFilter(image, filter.getKey(), filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesRegisteredImageFilter(Image image, String name, List<String> values) {
        return switch (name) {
            case "architecture" -> matchesFilterValue(values, image.getArchitecture());
            case "block-device-mapping.snapshot-id" -> image.getBlockDeviceMappings().stream()
                    .map(BlockDeviceMapping::getEbs)
                    .filter(Objects::nonNull)
                    .map(EbsBlockDevice::getSnapshotId)
                    .anyMatch(snapshotId -> matchesFilterValue(values, snapshotId));
            case "description" -> matchesFilterValue(values, image.getDescription());
            case "hypervisor" -> matchesFilterValue(values, image.getHypervisor());
            case "image-id" -> matchesFilterValue(values, image.getImageId());
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

    private Snapshot snapshotFrom(String region, String snapshotId, Image image, BlockDeviceMapping mapping) {
        EbsBlockDevice ebs = mapping.getEbs();
        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotId(snapshotId);
        snapshot.setOwnerId(accountId);
        snapshot.setState("completed");
        snapshot.setDescription("Created by RegisterImage for " + image.getName());
        snapshot.setStartTime(Instant.now());
        snapshot.setVolumeSize(ebs.getVolumeSize());
        snapshot.setEncrypted(Boolean.TRUE.equals(ebs.getEncrypted()));
        snapshot.setRegion(region);
        return snapshot;
    }

    private boolean matchesSnapshotFilters(Snapshot snapshot, Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
            if (!matchesSnapshotFilter(snapshot, filter.getKey(), filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesSnapshotOwners(Snapshot snapshot, List<String> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return accountId.equals(snapshot.getOwnerId());
        }
        return ownerIds.contains(snapshot.getOwnerId())
                || ownerIds.contains("self") && accountId.equals(snapshot.getOwnerId());
    }

    private boolean matchesSnapshotFilter(Snapshot snapshot, String name, List<String> values) {
        return switch (name) {
            case "description" -> matchesFilterValue(values, snapshot.getDescription());
            case "owner-id" -> matchesFilterValue(values, snapshot.getOwnerId());
            case "progress" -> matchesFilterValue(values, snapshot.getProgress());
            case "snapshot-id" -> matchesFilterValue(values, snapshot.getSnapshotId());
            case "status" -> matchesFilterValue(values, snapshot.getState());
            case "volume-id" -> matchesFilterValue(values, snapshot.getVolumeId());
            case "volume-size" -> matchesFilterValue(values,
                    snapshot.getVolumeSize() != null ? String.valueOf(snapshot.getVolumeSize()) : null);
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
            synchronized (lockFor(key(region, resourceId))) {
                List<Tag> existing = effectiveTags(region, resourceId);
                for (Tag tag : tagList) {
                    existing.removeIf(t -> t.getKey().equals(tag.getKey()));
                    existing.add(tag);
                }
                tags.put(resourceId, existing);
                // Update resource objects
                updateResourceTags(region, resourceId, existing);
            }
        }
    }

    public void deleteTags(String region, List<String> resourceIds, List<Tag> tagList) {
        ensureDefaultResources(region);
        for (String resourceId : resourceIds) {
            synchronized (lockFor(key(region, resourceId))) {
                List<Tag> existing = effectiveTags(region, resourceId);
                if (!existing.isEmpty()) {
                    for (Tag tag : tagList) {
                        existing.removeIf(t -> t.getKey().equals(tag.getKey())
                                && (tag.getValue() == null || tag.getValue().equals(t.getValue())));
                    }
                    tags.put(resourceId, existing);
                    updateResourceTags(region, resourceId, existing);
                }
            }
        }
    }

    /**
     * One taggable model store, paired with the setter that writes tags onto its model. Kept as a
     * list rather than an if-chain so that adding a taggable EC2 store is a one-line registration
     * and cannot be half-done: {@code CreateTags} on a store missing from this list used to
     * update {@code ec2-tags.json} and silently leave the model's own tag list untouched, so
     * {@code DescribeVolumes} kept reporting the create-time tags forever.
     */
    private record TagTarget<T>(StorageBackend<String, T> store,
                                java.util.function.Function<T, List<Tag>> getter,
                                java.util.function.BiConsumer<T, List<Tag>> setter) {
        boolean apply(String storeKey, List<Tag> tagList) {
            T resource = store.get(storeKey).orElse(null);
            if (resource == null) {
                return false;
            }
            setter.accept(resource, new ArrayList<>(tagList));
            store.put(storeKey, resource);
            return true;
        }

        List<Tag> read(String storeKey) {
            T resource = store.get(storeKey).orElse(null);
            if (resource == null) {
                return null;
            }
            List<Tag> current = getter.apply(resource);
            return current == null ? List.of() : current;
        }

        /** Every key in this store, so a caller can enumerate taggable resources across stores. */
        java.util.Set<String> keys() {
            return store.keys();
        }
    }

    private List<TagTarget<?>> tagTargets() {
        return List.of(
                new TagTarget<>(instances, Instance::getTags, Instance::setTags),
                new TagTarget<>(vpcs, Vpc::getTags, Vpc::setTags),
                new TagTarget<>(subnets, Subnet::getTags, Subnet::setTags),
                new TagTarget<>(securityGroups, SecurityGroup::getTags, SecurityGroup::setTags),
                new TagTarget<>(securityGroupRules, SecurityGroupRule::getTags, SecurityGroupRule::setTags),
                new TagTarget<>(internetGateways, InternetGateway::getTags, InternetGateway::setTags),
                new TagTarget<>(routeTables, RouteTable::getTags, RouteTable::setTags),
                new TagTarget<>(keyPairs, KeyPair::getTags, KeyPair::setTags),
                new TagTarget<>(launchTemplates, LaunchTemplate::getTags, LaunchTemplate::setTags),
                new TagTarget<>(vpcEndpoints, VpcEndpoint::getTags, VpcEndpoint::setTags),
                new TagTarget<>(natGateways, NatGateway::getTags, NatGateway::setTags),
                new TagTarget<>(networkAcls, NetworkAcl::getTags, NetworkAcl::setTags),
                new TagTarget<>(addresses, Address::getTags, Address::setTags),
                new TagTarget<>(managedPrefixLists, ManagedPrefixList::getTags, ManagedPrefixList::setTags),
                new TagTarget<>(dhcpOptionsSets, DhcpOptions::getTags, DhcpOptions::setTags),
                new TagTarget<>(volumes, Volume::getTags, Volume::setTags),
                new TagTarget<>(snapshots, Snapshot::getTags, Snapshot::setTags),
                new TagTarget<>(registeredImages, Image::getTags, Image::setTags),
                new TagTarget<>(spotInstanceRequests, SpotInstanceRequest::getTags, SpotInstanceRequest::setTags),
                new TagTarget<>(customerGateways, CustomerGateway::getTags, CustomerGateway::setTags),
                new TagTarget<>(vpnGateways, VpnGateway::getTags, VpnGateway::setTags),
                new TagTarget<>(capacityReservations, CapacityReservation::getTags, CapacityReservation::setTags));
    }

    private void updateResourceTags(String region, String resourceId, List<Tag> tagList) {
        String storeKey = key(region, resourceId);
        for (TagTarget<?> target : tagTargets()) {
            if (target.apply(storeKey, tagList)) {
                return;
            }
        }
    }

    /**
     * The tags currently on a resource, from both places EC2 keeps them: the {@code ec2-tags.json}
     * side-store that {@code CreateTags} writes, and the resource model itself, which is where
     * tags supplied inline on a create ({@code TagSpecification}) land. They are unioned rather
     * than one being preferred, because either can hold tags the other has never seen — which is
     * why {@code CreateTags} on a volume used to drop the tags it was created with.
     *
     * <p>Public because it is also the whole answer for a resource type whose model lives in a
     * different service and so cannot be registered in {@link #tagTargets()} here (VPC Flow Logs,
     * owned by {@code FlowLogService}) — for those, the side-store is the only place tags are
     * ever kept, and this still returns exactly that.
     */
    public List<Tag> effectiveTags(String region, String resourceId) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Tag tag : tags.get(resourceId).orElse(List.of())) {
            merged.put(tag.getKey(), tag.getValue());
        }
        String storeKey = key(region, resourceId);
        for (TagTarget<?> target : tagTargets()) {
            List<Tag> modelTags = target.read(storeKey);
            if (modelTags != null) {
                modelTags.forEach(t -> merged.put(t.getKey(), t.getValue()));
                break;
            }
        }
        List<Tag> result = new ArrayList<>();
        merged.forEach((k, v) -> result.add(new Tag(k, v)));
        return result;
    }

    public List<Map<String, String>> describeTags(String region, Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        List<String> filterResourceIds   = filters != null ? filters.get("resource-id")   : null;
        List<String> filterResourceTypes = filters != null ? filters.get("resource-type") : null;
        List<String> filterKeys          = filters != null ? filters.get("key")            : null;
        List<String> filterValues        = filters != null ? filters.get("value")          : null;

        // Both places EC2 keeps tags have to be enumerated: the CreateTags side-store, and the
        // resource models, which are the only home for tags supplied inline on a create. A
        // volume created with TagSpecifications is absent from the side-store entirely, and used
        // to be invisible to DescribeTags for its whole life.
        java.util.Set<String> resourceIds = new java.util.LinkedHashSet<>(tags.keys());
        String regionPrefix = region + "::";
        for (TagTarget<?> target : tagTargets()) {
            for (String storeKey : target.keys()) {
                if (storeKey.startsWith(regionPrefix)) {
                    resourceIds.add(storeKey.substring(regionPrefix.length()));
                }
            }
        }

        List<Map<String, String>> result = new ArrayList<>();
        for (String resourceId : resourceIds) {
            String resourceType = inferResourceType(resourceId);

            if (filterResourceIds != null && !filterResourceIds.contains(resourceId)) {
                continue;
            }
            if (filterResourceTypes != null && !filterResourceTypes.contains(resourceType)) {
                continue;
            }
            for (Tag tag : effectiveTags(region, resourceId)) {
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
        return Ec2ResourceIds.resourceType(resourceId);
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

    // ─── VPN Gateways ──────────────────────────────────────────────────────────

    /** The Amazon-side BGP ASN AWS assigns when the request omits AmazonSideAsn. */
    private static final long DEFAULT_AMAZON_SIDE_ASN = 64512L;

    public VpnGateway createVpnGateway(String region, String type, String availabilityZone, String amazonSideAsn) {
        ensureDefaultResources(region);
        if (type == null || type.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter Type.", 400);
        }
        if (!"ipsec.1".equals(type)) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + type + ") for parameter Type is invalid. The only supported type is ipsec.1.", 400);
        }

        VpnGateway gateway = new VpnGateway();
        gateway.setVpnGatewayId("vgw-" + randomHex(8));
        gateway.setType(type);
        // AWS reports no availability zone unless the caller asked for one, and the Terraform
        // provider treats availability_zone as ForceNew — inventing a zone here made every plan
        // against an unchanged gateway propose destroy-and-recreate.
        gateway.setAvailabilityZone(availabilityZone != null && !availabilityZone.isBlank()
                ? availabilityZone : null);
        long asn;
        try {
            asn = (amazonSideAsn != null && !amazonSideAsn.isBlank())
                    ? Long.parseLong(amazonSideAsn) : DEFAULT_AMAZON_SIDE_ASN;
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + amazonSideAsn + ") for parameter AmazonSideAsn is invalid.", 400);
        }
        gateway.setAmazonSideAsn(asn);
        gateway.setOwnerId(accountId);
        gateway.setRegion(region);
        // AWS transitions pending → available asynchronously. Nothing here is slow, so the
        // gateway is available on the create response and on the first describe, the same
        // simplification the other EC2 gateway/attachment resources make.
        gateway.setState("available");
        vpnGateways.put(key(region, gateway.getVpnGatewayId()), gateway);
        return gateway;
    }

    public List<VpnGateway> describeVpnGateways(String region, List<String> vpnGatewayIds,
                                                Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        for (String vpnGatewayId : vpnGatewayIds) {
            getRequiredVpnGateway(region, vpnGatewayId);
        }
        return vpnGateways.scan(k -> true).stream()
                .filter(gateway -> gateway.getRegion().equals(region))
                .filter(gateway -> vpnGatewayIds.isEmpty() || vpnGatewayIds.contains(gateway.getVpnGatewayId()))
                .filter(gateway -> matchesFilters(gateway, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteVpnGateway(String region, String vpnGatewayId) {
        ensureDefaultResources(region);
        getRequiredVpnGateway(region, vpnGatewayId);
        vpnGateways.delete(key(region, vpnGatewayId));
    }

    public VpcAttachment attachVpnGateway(String region, String vpnGatewayId, String vpcId) {
        ensureDefaultResources(region);
        VpnGateway gateway = getRequiredVpnGateway(region, vpnGatewayId);
        getRequiredVpc(region, vpcId);

        VpcAttachment attachment = new VpcAttachment(vpcId, "attached");
        gateway.getVpcAttachments().removeIf(a -> a.getVpcId().equals(vpcId));
        gateway.getVpcAttachments().add(attachment);
        vpnGateways.put(key(region, vpnGatewayId), gateway);
        return attachment;
    }

    public void detachVpnGateway(String region, String vpnGatewayId, String vpcId) {
        ensureDefaultResources(region);
        VpnGateway gateway = getRequiredVpnGateway(region, vpnGatewayId);

        boolean removed = gateway.getVpcAttachments().removeIf(a -> a.getVpcId().equals(vpcId));
        if (!removed) {
            throw new AwsException("InvalidVpnGatewayAttachment.NotFound",
                    "The attachment with vpn gateway ID '" + vpnGatewayId + "' and vpc ID '" + vpcId
                            + "' does not exist", 400);
        }
        vpnGateways.put(key(region, vpnGatewayId), gateway);
    }

    private VpnGateway getRequiredVpnGateway(String region, String vpnGatewayId) {
        VpnGateway gateway = vpnGateways.get(key(region, vpnGatewayId)).orElse(null);
        if (gateway == null)
            throw new AwsException("InvalidVpnGatewayID.NotFound", "The vpn gateway ID '" + vpnGatewayId + "' does not exist", 400);

        return gateway;
    }

    // ─── Customer Gateways ─────────────────────────────────────────────────────

    /** The BGP ASN AWS assigns when the request omits both BgpAsn and BgpAsnExtended. */
    private static final String DEFAULT_CUSTOMER_GATEWAY_BGP_ASN = "65000";

    public CustomerGateway createCustomerGateway(String region, String type, String ipAddress,
                                                 String bgpAsn, String bgpAsnExtended,
                                                 String certificateArn, String deviceName) {
        ensureDefaultResources(region);
        if (type == null || type.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter Type.", 400);
        }
        if (!"ipsec.1".equals(type)) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + type + ") for parameter Type is invalid. The only supported type is ipsec.1.", 400);
        }

        CustomerGateway gateway = new CustomerGateway();
        gateway.setCustomerGatewayId("cgw-" + randomHex(17));
        gateway.setType(type);
        gateway.setIpAddress(ipAddress);
        gateway.setCertificateArn(certificateArn);
        gateway.setDeviceName(deviceName);
        gateway.setOwnerId(accountId);
        gateway.setRegion(region);
        if (bgpAsnExtended != null && !bgpAsnExtended.isBlank()) {
            gateway.setBgpAsnExtended(bgpAsnExtended);
            gateway.setBgpAsn(bgpAsn);
        } else {
            gateway.setBgpAsn(bgpAsn != null && !bgpAsn.isBlank() ? bgpAsn : DEFAULT_CUSTOMER_GATEWAY_BGP_ASN);
        }
        // AWS transitions pending → available asynchronously. Nothing here is slow, so the
        // gateway is available on the create response and on the first describe.
        gateway.setState("available");
        customerGateways.put(key(region, gateway.getCustomerGatewayId()), gateway);
        return gateway;
    }

    public List<CustomerGateway> describeCustomerGateways(String region, List<String> customerGatewayIds,
                                                          Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        for (String customerGatewayId : customerGatewayIds) {
            getRequiredCustomerGateway(region, customerGatewayId);
        }
        return customerGateways.scan(k -> true).stream()
                .filter(gateway -> gateway.getRegion().equals(region))
                .filter(gateway -> customerGatewayIds.isEmpty()
                        || customerGatewayIds.contains(gateway.getCustomerGatewayId()))
                .filter(gateway -> matchesFilters(gateway, filters, region))
                .collect(Collectors.toList());
    }

    public void deleteCustomerGateway(String region, String customerGatewayId) {
        ensureDefaultResources(region);
        getRequiredCustomerGateway(region, customerGatewayId);
        customerGateways.delete(key(region, customerGatewayId));
    }

    private CustomerGateway getRequiredCustomerGateway(String region, String customerGatewayId) {
        CustomerGateway gateway = customerGateways.get(key(region, customerGatewayId)).orElse(null);
        if (gateway == null) {
            throw new AwsException("InvalidCustomerGatewayID.NotFound",
                    "The customer gateway ID '" + customerGatewayId + "' does not exist", 400);
        }
        return gateway;
    }

    // ─── Instance Metadata Defaults ────────────────────────────────────────────

    /**
     * Region-level defaults new instances inherit unless they set their own metadata options
     * (lex00/floci#76, {@code aws_ec2_instance_metadata_defaults}). Only the fields the caller
     * actually supplied are changed - {@code null} means "leave this one as it is", matching real
     * {@code ModifyInstanceMetadataDefaults}, which lets a single call touch just
     * {@code http_tokens} without resetting the others back to "no preference".
     */
    public void modifyInstanceMetadataDefaults(String region, String httpTokens,
            Integer httpPutResponseHopLimit, String httpEndpoint, String instanceMetadataTags) {
        InstanceMetadataDefaults defaults = instanceMetadataDefaults.computeIfAbsent(region,
                r -> new InstanceMetadataDefaults());
        if (httpTokens != null) {
            defaults.setHttpTokens(httpTokens);
        }
        if (httpPutResponseHopLimit != null) {
            defaults.setHttpPutResponseHopLimit(httpPutResponseHopLimit);
        }
        if (httpEndpoint != null) {
            defaults.setHttpEndpoint(httpEndpoint);
        }
        if (instanceMetadataTags != null) {
            defaults.setInstanceMetadataTags(instanceMetadataTags);
        }
        defaults.setManagedBy("account");
    }

    /**
     * A region absent from {@link #instanceMetadataDefaults} has never had
     * {@code ModifyInstanceMetadataDefaults} called against it, so this returns AWS's own
     * "nothing configured" answer (every field {@code "no-preference"}/{@code -1}, {@code
     * managedBy="none"}) rather than throwing - {@code GetInstanceMetadataDefaults} on a fresh
     * account is a normal, successful call in real AWS.
     */
    public InstanceMetadataDefaults getInstanceMetadataDefaults(String region) {
        return instanceMetadataDefaults.getOrDefault(region, new InstanceMetadataDefaults());
    }

    // ─── Capacity Reservations ────────────────────────────────────────────────

    /**
     * Reserves EC2 instance capacity in a specific Availability Zone. Real AWS transitions a
     * Capacity Reservation through {@code payment-pending}/{@code assessing} before
     * {@code active}; nothing here is slow, so it is {@code active} on the create response and
     * on the first describe, the same simplification {@link #createCustomerGateway} makes.
     */
    public CapacityReservation createCapacityReservation(String region, String instanceType,
            String instancePlatform, String availabilityZone, Integer instanceCount, String tenancy,
            Boolean ebsOptimized, Boolean ephemeralStorage, String endDateType, java.time.Instant endDate,
            String instanceMatchCriteria, String outpostArn, String placementGroupArn) {
        ensureDefaultResources(region);
        if (instanceType == null || instanceType.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter InstanceType.", 400);
        }
        if (availabilityZone == null || availabilityZone.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter AvailabilityZone.", 400);
        }
        int count = instanceCount != null ? instanceCount : 1;
        CapacityReservation reservation = new CapacityReservation();
        reservation.setCapacityReservationId("cr-" + randomHex(17));
        reservation.setOwnerId(accountId);
        reservation.setRegion(region);
        reservation.setCapacityReservationArn(
                AwsArnUtils.Arn.of("ec2", region, accountId, "capacity-reservation/" + reservation.getCapacityReservationId()).toString());
        reservation.setAvailabilityZone(availabilityZone);
        reservation.setInstanceType(instanceType);
        reservation.setInstancePlatform(instancePlatform != null ? instancePlatform : "Linux/UNIX");
        reservation.setTenancy(tenancy != null ? tenancy : "default");
        reservation.setTotalInstanceCount(count);
        reservation.setAvailableInstanceCount(count);
        reservation.setEbsOptimized(Boolean.TRUE.equals(ebsOptimized));
        reservation.setEphemeralStorage(Boolean.TRUE.equals(ephemeralStorage));
        reservation.setState("active");
        reservation.setStartDate(Instant.now());
        reservation.setCreateDate(Instant.now());
        reservation.setEndDate(endDate);
        reservation.setEndDateType(endDateType != null ? endDateType : "unlimited");
        reservation.setInstanceMatchCriteria(instanceMatchCriteria != null ? instanceMatchCriteria : "open");
        reservation.setOutpostArn(outpostArn);
        reservation.setPlacementGroupArn(placementGroupArn);
        capacityReservations.put(key(region, reservation.getCapacityReservationId()), reservation);
        return reservation;
    }

    public List<CapacityReservation> describeCapacityReservations(String region, List<String> ids,
            Map<String, List<String>> filters) {
        ensureDefaultResources(region);
        for (String id : ids) {
            getRequiredCapacityReservation(region, id);
        }
        return capacityReservations.scan(k -> true).stream()
                .filter(r -> r.getRegion().equals(region))
                .filter(r -> ids.isEmpty() || ids.contains(r.getCapacityReservationId()))
                .filter(r -> matchesFilters(r, filters, region))
                .collect(Collectors.toList());
    }

    /**
     * Applies an in-place update. AWS forbids changing instance type, EBS optimization,
     * platform, instance store, Availability Zone or tenancy after creation — the caller
     * (the AWS provider's own update path) never asks for that, so nothing here re-checks it.
     */
    public CapacityReservation modifyCapacityReservation(String region, String capacityReservationId,
            Integer instanceCount, java.time.Instant endDate, String endDateType, String instanceMatchCriteria) {
        ensureDefaultResources(region);
        CapacityReservation reservation = getRequiredCapacityReservation(region, capacityReservationId);
        if (instanceCount != null) {
            int delta = instanceCount - reservation.getTotalInstanceCount();
            reservation.setTotalInstanceCount(instanceCount);
            reservation.setAvailableInstanceCount(Math.max(0, reservation.getAvailableInstanceCount() + delta));
        }
        if (endDateType != null) {
            reservation.setEndDateType(endDateType);
        }
        // AWS clears EndDate when EndDateType moves back to "unlimited"; ModifyCapacityReservation
        // has no way to explicitly clear EndDate otherwise (CancelCapacityReservation is the only
        // other transition), so mirror that here rather than leaving a stale date behind.
        if ("unlimited".equals(reservation.getEndDateType())) {
            reservation.setEndDate(null);
        } else if (endDate != null) {
            reservation.setEndDate(endDate);
        }
        if (instanceMatchCriteria != null) {
            reservation.setInstanceMatchCriteria(instanceMatchCriteria);
        }
        capacityReservations.put(key(region, capacityReservationId), reservation);
        return reservation;
    }

    public void cancelCapacityReservation(String region, String capacityReservationId) {
        ensureDefaultResources(region);
        CapacityReservation reservation = getRequiredCapacityReservation(region, capacityReservationId);
        reservation.setState("cancelled");
        reservation.setAvailableInstanceCount(0);
        capacityReservations.put(key(region, capacityReservationId), reservation);
    }

    private CapacityReservation getRequiredCapacityReservation(String region, String capacityReservationId) {
        CapacityReservation reservation = capacityReservations.get(key(region, capacityReservationId)).orElse(null);
        if (reservation == null) {
            throw new AwsException("InvalidCapacityReservationId.NotFound",
                    "The Capacity Reservation '" + capacityReservationId + "' does not exist.", 400);
        }
        return reservation;
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
        synchronized (lockFor(key(region, routeTableId))) {
            RouteTable current = getRequiredRouteTable(region, routeTableId);
            List<RouteTableAssociation> next = new ArrayList<>(current.getAssociations());
            next.add(assoc);
            current.setAssociations(next);
            routeTables.put(key(region, routeTableId), current);
        }
        return assoc;
    }

    public void disassociateRouteTable(String region, String associationId) {
        ensureDefaultResources(region);
        for (RouteTable rt : routeTables.scan(k -> true)) {
            if (rt.getRegion().equals(region)
                    && rt.getAssociations().stream()
                            .anyMatch(a -> a.getRouteTableAssociationId().equals(associationId))) {
                synchronized (lockFor(key(region, rt.getRouteTableId()))) {
                    RouteTable current = getRequiredRouteTable(region, rt.getRouteTableId());
                    List<RouteTableAssociation> next = new ArrayList<>(current.getAssociations());
                    next.removeIf(a -> a.getRouteTableAssociationId().equals(associationId));
                    current.setAssociations(next);
                    routeTables.put(key(region, current.getRouteTableId()), current);
                }
            }
        }
    }

    public void createRoute(String region, String routeTableId, String destinationCidrBlock, String gatewayId, String natGatewayId) {
        ensureDefaultResources(region);
        synchronized (lockFor(key(region, routeTableId))) {
            RouteTable current = getRequiredRouteTable(region, routeTableId);
            List<Route> next = new ArrayList<>(current.getRoutes());
            Route route = new Route(destinationCidrBlock, gatewayId, "CreateRoute");
            route.setNatGatewayId(natGatewayId);
            next.add(route);
            current.setRoutes(next);
            routeTables.put(key(region, routeTableId), current);
        }
    }

    public void replaceRoute(String region, String routeTableId, String destinationCidrBlock, String gatewayId, String natGatewayId) {
        if (destinationCidrBlock == null || destinationCidrBlock.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must include DestinationCidrBlock; routes are matched on their IPv4 destination.", 400);
        }
        // AWS takes exactly one target. Rejecting both-or-neither also keeps the targets this
        // emulator cannot model (transit gateway, network interface, peering connection, ...) from
        // silently clearing the route and reporting success.
        boolean hasGateway = gatewayId != null && !gatewayId.isBlank();
        boolean hasNatGateway = natGatewayId != null && !natGatewayId.isBlank();
        if (hasGateway == hasNatGateway) {
            throw new AwsException("InvalidParameterCombination",
                    "ReplaceRoute takes exactly one target, and only GatewayId or NatGatewayId is supported.", 400);
        }

        ensureDefaultResources(region);
        synchronized (lockFor(key(region, routeTableId))) {
            RouteTable current = getRequiredRouteTable(region, routeTableId);
            List<Route> next = new ArrayList<>(current.getRoutes());
            Route existing = next.stream()
                    .filter(r -> destinationCidrBlock.equals(r.getDestinationCidrBlock()))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("InvalidRoute.NotFound",
                            "The route identified by " + destinationCidrBlock + " does not exist", 400));

            // The target the request does not name is cleared rather than carried over from the
            // route being replaced.
            Route replacement = new Route(destinationCidrBlock, hasGateway ? gatewayId : null, existing.getOrigin());
            replacement.setNatGatewayId(hasNatGateway ? natGatewayId : null);
            next.set(next.indexOf(existing), replacement);
            current.setRoutes(next);
            routeTables.put(key(region, routeTableId), current);
        }
    }

    public void deleteRoute(String region, String routeTableId, String destinationCidrBlock) {
        ensureDefaultResources(region);
        synchronized (lockFor(key(region, routeTableId))) {
            RouteTable current = getRequiredRouteTable(region, routeTableId);
            List<Route> next = new ArrayList<>(current.getRoutes());
            next.removeIf(r -> r.getDestinationCidrBlock().equals(destinationCidrBlock));
            current.setRoutes(next);
            routeTables.put(key(region, routeTableId), current);
        }
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
        Address address = null;
        if (allocationId != null && !allocationId.isBlank()) {
            address = getRequiredAddress(region, allocationId);
        }

        NatGateway natGateway = new NatGateway();
        natGateway.setNatGatewayId("nat-" + randomHex(17));
        natGateway.setSubnetId(subnetId);
        natGateway.setVpcId(subnet.getVpcId());
        natGateway.setAllocationId(allocationId);
        natGateway.setConnectivityType(connectivityType != null && !connectivityType.isBlank() ? connectivityType : "public");
        natGateway.setAvailabilityMode("zonal");
        // AWS reports the gateway's ENI and addresses on every describe; the Terraform provider
        // reads allocation_id, private_ip, public_ip and network_interface_id out of the address
        // set rather than off the gateway itself.
        natGateway.setNetworkInterfaceId("eni-" + randomHex(17));
        natGateway.setPrivateIp(assignPrivateIp(region, subnetId));
        if (address != null) {
            natGateway.setPublicIp(address.getPublicIp());
            natGateway.setAssociationId("eipassoc-" + randomHex(17));
        }
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

    /**
     * AWS services that only ever expose a Gateway-type VPC endpoint (no
     * Interface type exists for them at all). Everything else defaults to
     * Interface when the caller does not name a type explicitly - matching
     * how most AWS services publish themselves.
     */
    private static final Set<String> GATEWAY_ONLY_ENDPOINT_SERVICES = Set.of("s3", "dynamodb");

    /**
     * DescribeVpcEndpointServices, synthesized rather than served from a
     * static catalog. Real AWS answers this from an account-wide table of
     * every published service; floci has no such table and building one
     * would only ever cover the services somebody remembered to add. What
     * every caller observed in practice actually needs is narrower: the
     * Terraform AWS provider's `aws_vpc_endpoint_service` data source (used
     * by, among others, terraform-aws-modules/terraform-aws-vpc's
     * vpc-endpoints submodule) resolves a short name like "s3" to a full
     * `com.amazonaws.<region>.<service>` name ITSELF before the API call
     * ever reaches here, then asks DescribeVpcEndpointServices to confirm
     * that exact name exists. So confirming is all this needs to do: for
     * every requested ServiceName (or "service-name" filter value), and
     * for names that are not a real, floci-known service, answer this the
     * emulator's affirmative rather than AWS's real per-account catalog:
     * synthesize a plausible detail so a caller providing any well-formed
     * service name is not blocked by an empty response. This mirrors how
     * DescribeInstanceTypes and other floci endpoints answer generically
     * rather than off a curated allowlist.
     *
     * The "service-type" filter, when present, is honored as the caller's
     * own statement of what type they want (Gateway vs Interface) rather
     * than second-guessed - a caller asking for the Interface type of a
     * service that also has a Gateway type on real AWS (S3 does) is
     * describing real, valid AWS behavior.
     */
    public List<Map<String, String>> describeVpcEndpointServices(String region, List<String> serviceNames,
                                                                   Map<String, List<String>> filters) {
        LinkedHashSet<String> names = new LinkedHashSet<>(serviceNames);
        if (filters != null && filters.containsKey("service-name")) {
            names.addAll(filters.get("service-name"));
        }

        List<String> requestedTypes = filters != null ? filters.get("service-type") : null;
        List<Map<String, String>> details = new ArrayList<>();
        for (String name : names) {
            String slug = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            String type = requestedTypes != null && !requestedTypes.isEmpty()
                    ? requestedTypes.get(0)
                    : GATEWAY_ONLY_ENDPOINT_SERVICES.contains(slug) ? "Gateway" : "Interface";
            String dnsName = slug + "." + region + ".amazonaws.com";

            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("serviceName", name);
            detail.put("serviceId", "vpce-svc-" + stableHex(name, 17));
            detail.put("serviceType", type);
            detail.put("owner", "amazon");
            detail.put("baseEndpointDnsName", dnsName);
            detail.put("privateDnsName", dnsName);
            detail.put("vpcEndpointPolicySupported", "true");
            detail.put("acceptanceRequired", "false");
            detail.put("managesVpcEndpoints", "false");
            detail.put("privateDnsNameVerificationState", "verified");
            details.add(detail);
        }
        return details;
    }

    /** A deterministic hex id, the same shape [.randomHex] produces but stable for a given input. */
    private static String stableHex(String seed, int len) {
        StringBuilder sb = new StringBuilder(Integer.toHexString(seed.hashCode()));
        while (sb.length() < len) {
            sb.append(Integer.toHexString((seed + sb).hashCode()));
        }
        return sb.substring(0, len);
    }

    public List<String> describeRegions() {
        return AwsRegions.ALL;
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
        if (instanceTypeNames.isEmpty()) {
            return instanceTypeCatalog.instanceTypes().stream()
                    .map(Ec2InstanceTypeCatalog.CatalogInstanceType::toResponseMap)
                    .collect(Collectors.toList());
        }
        return instanceTypeNames.stream()
                .distinct()
                .map(instanceTypeCatalog::find)
                .flatMap(Optional::stream)
                .map(Ec2InstanceTypeCatalog.CatalogInstanceType::toResponseMap)
                .collect(Collectors.toList());
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
                case "cidr-block", "cidrBlock", "cidr" -> matchesValue(values, subnet.getCidrBlock());
                default -> true;
            };
        }
        if (resource instanceof ManagedPrefixList prefixList) {
            return switch (filterName) {
                case "prefix-list-id" -> matchesValue(values, prefixList.getPrefixListId());
                case "prefix-list-name" -> matchesValue(values, prefixList.getPrefixListName());
                case "owner-id" -> matchesValue(values, prefixList.getOwnerId());
                default -> true;
            };
        }
        if (resource instanceof DhcpOptions dhcpOptions) {
            return switch (filterName) {
                case "dhcp-options-id" -> matchesValue(values, dhcpOptions.getDhcpOptionsId());
                case "owner-id" -> matchesValue(values, dhcpOptions.getOwnerId());
                case "key" -> dhcpOptions.getDhcpConfigurationSet().stream()
                        .anyMatch(c -> matchesValue(values, c.getKey()));
                case "value" -> dhcpOptions.getDhcpConfigurationSet().stream()
                        .flatMap(c -> c.getValues().stream())
                        .anyMatch(v -> matchesValue(values, v));
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
            // #103 follow-up: attachment.* was previously unhandled and fell through to the
            // `default -> true` catch-all below, so DescribeVolumes --filters
            // attachment.instance-id/attachment.device matched every volume in the region
            // instead of the one actually attached. Volumes[0] in the CLI output then depended
            // on Map iteration order across every volume ever created, not on the filter --
            // this reads as "nondeterministic" (sometimes the right volume, sometimes some
            // other instance's default-sized one) but is a plain missing-filter bug, not a race.
            return switch (filterName) {
                case "volume-id" -> matchesValue(values, vol.getVolumeId());
                case "status" -> matchesValue(values, vol.getState());
                case "volume-type" -> matchesValue(values, vol.getVolumeType());
                case "availability-zone" -> matchesValue(values, vol.getAvailabilityZone());
                case "encrypted" -> matchesValue(values, String.valueOf(vol.isEncrypted()));
                case "attachment.instance-id" -> vol.getAttachments().stream()
                        .anyMatch(a -> matchesValue(values, a.getInstanceId()));
                case "attachment.device" -> vol.getAttachments().stream()
                        .anyMatch(a -> matchesValue(values, a.getDevice()));
                case "attachment.status" -> vol.getAttachments().stream()
                        .anyMatch(a -> matchesValue(values, a.getState()));
                case "attachment.delete-on-termination" -> vol.getAttachments().stream()
                        .anyMatch(a -> matchesValue(values, String.valueOf(a.isDeleteOnTermination())));
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
                case "attachment.instance-id" -> ni.getAttachment() != null
                        && matchesValue(values, ni.getAttachment().getInstanceId());
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
        if (resource instanceof SpotInstanceRequest sir) {
            return switch (filterName) {
                case "spot-instance-request-id" -> matchesValue(values, sir.getSpotInstanceRequestId());
                case "state" -> matchesValue(values, sir.getState());
                case "instance-id" -> matchesValue(values, sir.getInstanceId());
                default -> true;
            };
        }
        if (resource instanceof CustomerGateway cgw) {
            return switch (filterName) {
                case "customer-gateway-id" -> matchesValue(values, cgw.getCustomerGatewayId());
                case "bgp-asn" -> cgw.getBgpAsn() != null && matchesValue(values, cgw.getBgpAsn());
                case "ip-address" -> cgw.getIpAddress() != null && matchesValue(values, cgw.getIpAddress());
                case "state" -> matchesValue(values, cgw.getState());
                case "type" -> matchesValue(values, cgw.getType());
                default -> true;
            };
        }
        if (resource instanceof VpnGateway vgw) {
            return switch (filterName) {
                case "vpn-gateway-id" -> matchesValue(values, vgw.getVpnGatewayId());
                case "state" -> matchesValue(values, vgw.getState());
                case "type" -> matchesValue(values, vgw.getType());
                case "availability-zone" -> matchesValue(values, vgw.getAvailabilityZone());
                case "amazon-side-asn" -> matchesValue(values, String.valueOf(vgw.getAmazonSideAsn()));
                case "attachment.vpc-id" -> vgw.getVpcAttachments().stream()
                        .anyMatch(a -> matchesValue(values, a.getVpcId()));
                case "attachment.state" -> vgw.getVpcAttachments().stream()
                        .anyMatch(a -> matchesValue(values, a.getState()));
                default -> true;
            };
        }
        if (resource instanceof CapacityReservation cr) {
            return switch (filterName) {
                case "capacity-reservation-id" -> matchesValue(values, cr.getCapacityReservationId());
                case "instance-type" -> matchesValue(values, cr.getInstanceType());
                case "availability-zone" -> matchesValue(values, cr.getAvailabilityZone());
                case "tenancy" -> matchesValue(values, cr.getTenancy());
                case "state" -> matchesValue(values, cr.getState());
                case "instance-platform" -> matchesValue(values, cr.getInstancePlatform());
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
        if (resource instanceof ManagedPrefixList prefixList) return prefixList.getTags();
        if (resource instanceof DhcpOptions dhcpOptions) return dhcpOptions.getTags();
        if (resource instanceof LaunchTemplate lt) return lt.getTags();
        if (resource instanceof VpcEndpoint endpoint) return endpoint.getTags();
        if (resource instanceof NatGateway natGateway) return natGateway.getTags();
        if (resource instanceof SpotInstanceRequest sir) return sir.getTags();
        if (resource instanceof CustomerGateway cgw) return cgw.getTags();
        if (resource instanceof VpnGateway vgw) return vgw.getTags();
        if (resource instanceof CapacityReservation cr) return cr.getTags();
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

    public VolumeAttachment attachVolume(String region, String volumeId, String instanceId, String device) {
        ensureDefaultResources(region);
        if (volumeId == null || volumeId.isEmpty()) {
            throw new AwsException("MissingParameter",
                    "The parameter VolumeId is missing", 400);
        }
        if (instanceId == null || instanceId.isEmpty()) {
            throw new AwsException("MissingParameter",
                    "The parameter InstanceId is missing", 400);
        }
        if (device == null || device.isEmpty()) {
            throw new AwsException("MissingParameter",
                    "The parameter Device is missing", 400);
        }
        Volume volume = getRequiredVolume(region, volumeId);
        Instance inst = getRequiredInstance(region, instanceId);
        if (!List.of("running", "stopped").contains(inst.getState().getName())) {
            throw new AwsException("IncorrectInstanceState",
                    "The instance '" + inst.getInstanceId() + "' is not in a state from which it can be attached", 400);
        }
        if (!inst.getPlacement().getAvailabilityZone().equals(volume.getAvailabilityZone())) {
            throw new AwsException(
                    "InvalidParameterValue",
                    "The volume '" + volume.getVolumeId() +
                            "' and instance '" + inst.getInstanceId() +
                            "' must be in the same Availability Zone", 400);
        }
        if (!"available".equals(volume.getState())) {
            throw new AwsException("VolumeInUse",
                    "Volume '" + volumeId + "' is already attached", 400);
        }

        VolumeAttachment attachment = new VolumeAttachment();
        attachment.setVolumeId(volumeId);
        attachment.setInstanceId(instanceId);
        attachment.setDevice(device);
        attachment.setState("attached");
        attachment.setAttachTime(Instant.now());
        attachment.setDeleteOnTermination(false); // Default for attached volumes

        volume.getAttachments().add(attachment);
        volume.setState("in-use");
        volumes.put(key(region, volumeId), volume);
        return attachment;
    }

    public VolumeAttachment detachVolume(String region, String volumeId, String instanceId, String device, boolean force) {
        if (volumeId == null || volumeId.isEmpty()) {
            throw new AwsException("MissingParameter", "The parameter VolumeId is missing", 400);
        }
        ensureDefaultResources(region);
        Volume volume = getRequiredVolume(region, volumeId);

        if ("available".equals(volume.getState()) || volume.getAttachments().isEmpty()) {
            throw new AwsException("InvalidVolume.NotAttached",
                    "Volume '" + volumeId + "' is not attached", 400);
        }
        VolumeAttachment target = volume.getAttachments().getFirst();
        if (instanceId != null && !target.getInstanceId().equals(instanceId)) {
            throw new AwsException("InvalidAttachment.NotFound",
                    "Volume '" + volumeId + "' is not attached to instance '" + instanceId + "'", 400);
        }
        if (device != null && !target.getDevice().equals(device)) {
            throw new AwsException("InvalidAttachment.NotFound",
                    "Volume '" + volumeId + "' is not attached with device '" + device + "'", 400);
        }
        Instance inst = getRequiredInstance(region, target.getInstanceId());
        if (!inst.getState().getName().equals("stopped") && target.getDevice().equals(inst.getRootDeviceName())) {
            throw new AwsException("OperationNotPermitted",
                    "The root volume of an instance cannot be detached while the instance is running", 400);
        }
        if (!force && target.getDevice().equals(inst.getRootDeviceName())) {
            throw new AwsException("InvalidParameterCombination",
                    "Device " + inst.getRootDeviceName() + " has the root partition on it. Detaching it will damage the " +
                            "filesystem/partition tables. To force detachment, use the force parameter", 400);
        }
        target.setState("detached");
        volume.getAttachments().clear();
        volume.setState("available");
        volumes.put(key(region, volumeId), volume);
        return target;
    }

    private Volume getRequiredVolume(String region, String volumeId) {
        return volumes.get(key(region, volumeId)).orElseThrow(() ->
                new AwsException("InvalidVolume.NotFound", "The volume '" + volumeId + "' does not exist", 400)
        );
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

        // Interface VPC endpoints own ENIs too, and AWS answers for them here — the Terraform
        // provider follows an endpoint's networkInterfaceIdSet straight into this call to build
        // its subnet_configuration, and fails the whole read if an id it was handed is unknown.
        for (NetworkInterface ni : endpointNetworkInterfaces(region)) {
            if (!networkInterfaceIds.isEmpty() && !networkInterfaceIds.contains(ni.getNetworkInterfaceId())) {
                continue;
            }
            foundIds.add(ni.getNetworkInterfaceId());
            if (!matchesFilters(ni, filters, region)) {
                continue;
            }
            result.add(ni);
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

    public List<SpotInstanceRequest> requestSpotInstances(String region, String spotPrice, Integer instanceCount,
                                                         String type, String productDescription, String imageId, String instanceType,
                                                         String keyName, String subnetId, List<String> securityGroupIds,
                                                         String userData, String iamInstanceProfileArn,
                                                         List<Tag> spotRequestTags, List<Tag> instanceTags) {
        ensureDefaultResources(region);

        int count = instanceCount != null ? instanceCount : 1;
        String finalType = type != null ? type : "one-time";

        List<SpotInstanceRequest> requests = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String spotRequestId = "sir-" + randomHex(8);

            Reservation reservation = runInstances(region, imageId, instanceType, 1, 1, keyName,
                    securityGroupIds, subnetId, null, instanceTags, userData, iamInstanceProfileArn);

            Instance launchedInstance = reservation.getInstances().get(0);

            LaunchSpecification spec = new LaunchSpecification();
            spec.setImageId(launchedInstance.getImageId());
            spec.setInstanceType(launchedInstance.getInstanceType());
            spec.setKeyName(launchedInstance.getKeyName());
            spec.setSubnetId(launchedInstance.getSubnetId());
            spec.setUserData(userData);
            spec.setIamInstanceProfileArn(iamInstanceProfileArn);

            if (launchedInstance.getSecurityGroups() != null) {
                spec.setSecurityGroups(new ArrayList<>(launchedInstance.getSecurityGroups()));
            }

            SpotInstanceRequest sir = new SpotInstanceRequest();
            sir.setSpotInstanceRequestId(spotRequestId);
            sir.setSpotPrice(spotPrice);
            sir.setType(finalType);
            sir.setState("active");
            sir.setStatusCode("fulfilled");
            sir.setStatusMessage("Your Spot Instance request is fulfilled.");
            sir.setStatusUpdateTime(Instant.now());
            sir.setInstanceId(launchedInstance.getInstanceId());
            sir.setCreateTime(Instant.now());
            sir.setLaunchSpecification(spec);
            sir.setRegion(region);
            if (productDescription != null && !productDescription.isBlank()) {
                sir.setProductDescription(productDescription);
            } else {
                sir.setProductDescription("Linux/UNIX");
            }

            if (spotRequestTags != null && !spotRequestTags.isEmpty()) {
                sir.setTags(new ArrayList<>(spotRequestTags));
                tags.put(spotRequestId, new ArrayList<>(spotRequestTags));
            }

            spotInstanceRequests.put(key(region, spotRequestId), sir);
            requests.add(sir);
        }

        return requests;
    }

    public List<SpotInstanceRequest> describeSpotInstanceRequests(String region, List<String> spotRequestIds, Map<String, List<String>> filters) {
        ensureDefaultResources(region);

        if (!spotRequestIds.isEmpty()) {
            for (String id : spotRequestIds) {
                if (spotInstanceRequests.get(key(region, id)).isEmpty()) {
                    throw new AwsException("InvalidSpotInstanceRequestID.NotFound",
                            "The spot instance request ID '" + id + "' does not exist", 400);
                }
            }
        }

        return spotInstanceRequests.scan(k -> true).stream()
                .filter(sir -> sir.getRegion().equals(region))
                .filter(sir -> spotRequestIds.isEmpty() || spotRequestIds.contains(sir.getSpotInstanceRequestId()))
                .filter(sir -> matchesFilters(sir, filters, region))
                .collect(Collectors.toList());
    }

    public List<SpotInstanceRequest> cancelSpotInstanceRequests(String region, List<String> spotRequestIds) {
        ensureDefaultResources(region);

        List<SpotInstanceRequest> result = new ArrayList<>();
        for (String id : spotRequestIds) {
            SpotInstanceRequest sir = spotInstanceRequests.get(key(region, id)).orElse(null);
            if (sir == null) {
                throw new AwsException("InvalidSpotInstanceRequestID.NotFound",
                        "The spot instance request ID '" + id + "' does not exist", 400);
            }

            sir.setState("cancelled");
            sir.setStatusCode("request-canceled-and-instance-running");
            sir.setStatusMessage("Spot Instance request canceled. Associated Spot Instance is still running.");
            sir.setStatusUpdateTime(Instant.now());
            spotInstanceRequests.put(key(region, id), sir);
            result.add(sir);
        }

        return result;
    }
}
