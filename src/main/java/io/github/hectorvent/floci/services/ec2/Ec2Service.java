package io.github.hectorvent.floci.services.ec2;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
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
import io.github.hectorvent.floci.services.ec2.model.PrefixListId;
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
import io.github.hectorvent.floci.services.ec2.model.TransitGateway;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayOptions;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayRoute;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayRouteTable;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayRouteTablePropagation;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayVpcAttachment;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayVpcAttachmentOptions;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcCidrBlockAssociation;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
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
    /** The accounts behind the two non-self owner aliases DescribeImages accepts. */
    private static final String AMAZON_OWNER_ID = "137112412989";
    private static final String AWS_MARKETPLACE_OWNER_ID = "679593333241";
    // The ASN AWS assigns when CreateTransitGateway omits Options.AmazonSideAsn.
    private static final long DEFAULT_AMAZON_SIDE_ASN = 64512L;
    private static final Pattern TRANSIT_GATEWAY_ID_PATTERN = Pattern.compile("^tgw-[0-9a-f]{8}([0-9a-f]{9})?$");
    private static final Pattern TRANSIT_GATEWAY_ROUTE_TABLE_ID_PATTERN =
            Pattern.compile("^tgw-rtb-[0-9a-f]{8}([0-9a-f]{9})?$");
    private static final Pattern TRANSIT_GATEWAY_ATTACHMENT_ID_PATTERN =
            Pattern.compile("^tgw-attach-[0-9a-f]{8}([0-9a-f]{9})?$");

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
    private final StorageBackend<String, TransitGateway> transitGateways;
    private final StorageBackend<String, TransitGatewayRouteTable> transitGatewayRouteTables;
    private final StorageBackend<String, TransitGatewayVpcAttachment> transitGatewayVpcAttachments;
    private final StorageBackend<String, TransitGatewayRouteTablePropagation> transitGatewayPropagations;
    private final StorageBackend<String, TransitGatewayRoute> transitGatewayRoutes;
    // resourceId → List<Tag>
    private final StorageBackend<String, List<Tag>> tags;
    private final Set<String> seededRegions = ConcurrentHashMap.newKeySet();
    // subnetId → counter for IP assignment (runtime-only, not persisted)
    private final Map<String, AtomicInteger> subnetIpCounters = new ConcurrentHashMap<>();

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
                storageFactory.create("ec2", "ec2-tags.json", new TypeReference<Map<String, List<Tag>>>() {}),
                storageFactory.create("ec2", "ec2-transit-gateways.json", new TypeReference<Map<String, TransitGateway>>() {}),
                storageFactory.create("ec2", "ec2-transit-gateway-route-tables.json",
                        new TypeReference<Map<String, TransitGatewayRouteTable>>() {}),
                storageFactory.create("ec2", "ec2-transit-gateway-vpc-attachments.json",
                        new TypeReference<Map<String, TransitGatewayVpcAttachment>>() {}),
                storageFactory.create("ec2", "ec2-transit-gateway-propagations.json",
                        new TypeReference<Map<String, TransitGatewayRouteTablePropagation>>() {}),
                storageFactory.create("ec2", "ec2-transit-gateway-routes.json",
                        new TypeReference<Map<String, TransitGatewayRoute>>() {}));
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
               StorageBackend<String, List<Tag>> tags) {
        this(config, containerManager, portForwardManager, amiImageResolver, imageCatalog, instanceTypeCatalog,
                vpcs, subnets, securityGroups, securityGroupRules, internetGateways, routeTables, keyPairs,
                addresses, instances, volumes, registeredImages, snapshots, launchTemplates, vpcEndpoints,
                natGateways, spotInstanceRequests, networkAcls, managedPrefixLists, tags,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    // Package-private for hermetic tests, transit-gateway-aware. The shorter overload above keeps its
    // arity so existing fixtures still resolve it (#2103 and #2106 both broke CI by moving it).
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
               StorageBackend<String, List<Tag>> tags,
               StorageBackend<String, TransitGateway> transitGateways,
               StorageBackend<String, TransitGatewayRouteTable> transitGatewayRouteTables,
               StorageBackend<String, TransitGatewayVpcAttachment> transitGatewayVpcAttachments,
               StorageBackend<String, TransitGatewayRouteTablePropagation> transitGatewayPropagations,
               StorageBackend<String, TransitGatewayRoute> transitGatewayRoutes) {
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
        this.tags = tags;
        this.transitGateways = transitGateways;
        this.transitGatewayRouteTables = transitGatewayRouteTables;
        this.transitGatewayVpcAttachments = transitGatewayVpcAttachments;
        this.transitGatewayPropagations = transitGatewayPropagations;
        this.transitGatewayRoutes = transitGatewayRoutes;
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

        // Default VPC
        String vpcId = defaultVpcId(region);
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
        String[] subnetIds = {
                defaultSubnetId(region, azSuffixes[0]),
                defaultSubnetId(region, azSuffixes[1]),
                defaultSubnetId(region, azSuffixes[2])};
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

        createDefaultSecurityGroup(region, vpcId, defaultSecurityGroupId(region));

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
        NetworkAclEntry entry = new NetworkAclEntry();
        entry.setRuleNumber(ruleNumber);
        entry.setProtocol(protocol);
        entry.setRuleAction(action);
        entry.setEgress(egress);
        entry.setCidrBlock(cidr);
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
                                      String ruleAction, boolean egress, String cidrBlock, Integer from, Integer to,
                                      boolean replace) {
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
            NetworkAclEntry entry = naclEntry(ruleNumber, protocol, ruleAction, egress, cidrBlock);
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

    // Default resource ids must be derived per region (lex00/floci#21): every region seeds its own
    // default VPC/subnets/security group independently, but a literal id like "vpc-default"
    // is identical text in every region's response even though storage itself is already
    // correctly keyed by region. RdsService derives the same ids from these methods so its
    // default DB subnet group resolves against the right region's default VPC.
    public static String defaultVpcId(String region) {
        return "vpc-default-" + region;
    }

    public static String defaultSubnetId(String region, String azSuffix) {
        return "subnet-default-" + region + "-" + azSuffix;
    }

    public static String defaultSecurityGroupId(String region) {
        return "sg-default-" + region;
    }

    // Resolves the default VPC/security-group id actually on file for a region, falling back to
    // the pre-lex00/floci#21 unscoped literal ("vpc-default"/"sg-default") when storage was persisted before
    // ids were made region-scoped. Seeding (ensureDefaultResources) always assigns the new
    // region-scoped id going forward; these resolvers only cover lookups against what may already
    // be on disk. Without this, a region seeded under the old scheme would silently lose its
    // default VPC/security group to every caller that resolves them by computed id.
    public String resolveDefaultVpcId(String region) {
        String scoped = defaultVpcId(region);
        if (vpcs.get(key(region, scoped)).isPresent()) {
            return scoped;
        }
        String legacy = "vpc-default";
        return vpcs.get(key(region, legacy)).isPresent() ? legacy : scoped;
    }

    public String resolveDefaultSecurityGroupId(String region) {
        String scoped = defaultSecurityGroupId(region);
        if (securityGroups.get(key(region, scoped)).isPresent()) {
            return scoped;
        }
        String legacy = "sg-default";
        return securityGroups.get(key(region, legacy)).isPresent() ? legacy : scoped;
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

    // ─── Transit Gateways ──────────────────────────────────────────────────────

    /**
     * Creates a transit gateway. Option defaults, and the fact that the default route table is
     * minted during creation rather than afterwards, were taken from a live AWS account rather
     * than the documentation.
     *
     * <p>AWS returns the gateway as {@code pending} and reaches {@code available} about 50
     * seconds later. Nothing here is slow, so the settled state is what the caller sees, the
     * same compression {@code createManagedPrefixList} applies.
     */
    public TransitGateway createTransitGateway(String region, String description,
                                               TransitGatewayOptions requested, List<Tag> gatewayTags) {
        // Held here too, so the rule needs no exceptions: every write to a gateway, route table,
        // attachment, propagation or route happens under this one lock.
        synchronized (attachmentTopologyLock(region)) {
        String transitGatewayId = "tgw-" + randomHex(17);
        TransitGateway gateway = new TransitGateway();
        gateway.setTransitGatewayId(transitGatewayId);
        gateway.setTransitGatewayArn(AwsArnUtils.Arn
                .of("ec2", region, accountId, "transit-gateway/" + transitGatewayId).toString());
        gateway.setState("available");
        gateway.setOwnerId(accountId);
        gateway.setDescription(description);
        gateway.setCreationTime(ISO_FMT.format(Instant.now()));
        gateway.setRegion(region);
        gateway.setOptions(resolveTransitGatewayOptions(requested));

        TransitGatewayOptions options = gateway.getOptions();
        if ("enable".equals(options.getDefaultRouteTableAssociation())
                || "enable".equals(options.getDefaultRouteTablePropagation())) {
            TransitGatewayRouteTable defaultRouteTable = createDefaultTransitGatewayRouteTable(region, gateway);
            if ("enable".equals(options.getDefaultRouteTableAssociation())) {
                options.setAssociationDefaultRouteTableId(defaultRouteTable.getTransitGatewayRouteTableId());
            }
            if ("enable".equals(options.getDefaultRouteTablePropagation())) {
                options.setPropagationDefaultRouteTableId(defaultRouteTable.getTransitGatewayRouteTableId());
            }
        }

        if (gatewayTags != null && !gatewayTags.isEmpty()) {
            gateway.setTags(new ArrayList<>(gatewayTags));
            tags.put(transitGatewayId, new ArrayList<>(gatewayTags));
        }
        transitGateways.put(key(region, transitGatewayId), gateway);
        return gateway;
        }
    }

    private TransitGatewayOptions resolveTransitGatewayOptions(TransitGatewayOptions requested) {
        TransitGatewayOptions options = new TransitGatewayOptions();
        options.setAmazonSideAsn(DEFAULT_AMAZON_SIDE_ASN);
        options.setAutoAcceptSharedAttachments("disable");
        options.setDefaultRouteTableAssociation("enable");
        options.setDefaultRouteTablePropagation("enable");
        options.setVpnEcmpSupport("enable");
        options.setDnsSupport("enable");
        options.setSecurityGroupReferencingSupport("disable");
        options.setMulticastSupport("disable");
        if (requested == null) {
            return options;
        }
        if (requested.getAmazonSideAsn() != null) {
            options.setAmazonSideAsn(requested.getAmazonSideAsn());
        }
        if (requested.getAutoAcceptSharedAttachments() != null) {
            options.setAutoAcceptSharedAttachments(requested.getAutoAcceptSharedAttachments());
        }
        if (requested.getDefaultRouteTableAssociation() != null) {
            options.setDefaultRouteTableAssociation(requested.getDefaultRouteTableAssociation());
        }
        if (requested.getDefaultRouteTablePropagation() != null) {
            options.setDefaultRouteTablePropagation(requested.getDefaultRouteTablePropagation());
        }
        if (requested.getVpnEcmpSupport() != null) {
            options.setVpnEcmpSupport(requested.getVpnEcmpSupport());
        }
        if (requested.getDnsSupport() != null) {
            options.setDnsSupport(requested.getDnsSupport());
        }
        if (requested.getSecurityGroupReferencingSupport() != null) {
            options.setSecurityGroupReferencingSupport(requested.getSecurityGroupReferencingSupport());
        }
        if (requested.getMulticastSupport() != null) {
            options.setMulticastSupport(requested.getMulticastSupport());
        }
        if (requested.getTransitGatewayCidrBlocks() != null) {
            options.setTransitGatewayCidrBlocks(new ArrayList<>(requested.getTransitGatewayCidrBlocks()));
        }
        return options;
    }

    private TransitGatewayRouteTable createDefaultTransitGatewayRouteTable(String region, TransitGateway gateway) {
        TransitGatewayRouteTable routeTable = new TransitGatewayRouteTable();
        String routeTableId = "tgw-rtb-" + randomHex(17);
        routeTable.setTransitGatewayRouteTableId(routeTableId);
        routeTable.setTransitGatewayId(gateway.getTransitGatewayId());
        routeTable.setState("available");
        routeTable.setDefaultAssociationRouteTable("enable".equals(gateway.getOptions().getDefaultRouteTableAssociation()));
        routeTable.setDefaultPropagationRouteTable("enable".equals(gateway.getOptions().getDefaultRouteTablePropagation()));
        routeTable.setCreationTime(ISO_FMT.format(Instant.now()));
        routeTable.setRegion(region);
        transitGatewayRouteTables.put(key(region, routeTableId), routeTable);
        return routeTable;
    }

    public List<TransitGateway> describeTransitGateways(String region, List<String> transitGatewayIds,
                                                        Map<String, List<String>> filters) {
        transitGatewayIds.forEach(Ec2Service::requireWellFormedTransitGatewayId);
        List<TransitGateway> all = transitGateways.scan(k -> true).stream()
                .filter(gateway -> region.equals(gateway.getRegion()))
                .collect(Collectors.toList());

        for (String transitGatewayId : transitGatewayIds) {
            if (all.stream().noneMatch(gateway -> gateway.getTransitGatewayId().equals(transitGatewayId))) {
                throw new AwsException("InvalidTransitGatewayID.NotFound",
                        "Transit Gateway " + transitGatewayId + " was deleted or does not exist.", 400);
            }
        }
        return all.stream()
                .filter(gateway -> transitGatewayIds.isEmpty()
                        || transitGatewayIds.contains(gateway.getTransitGatewayId()))
                .filter(gateway -> matchesFilters(gateway, filters, region))
                .collect(Collectors.toList());
    }

    public TransitGateway modifyTransitGateway(String region, String transitGatewayId, String description,
                                               TransitGatewayOptions changes, List<String> addCidrBlocks,
                                               List<String> removeCidrBlocks) {
        // Outermost first, as the deletes take it: this writes route tables through
        // markDefaultRouteTable, and a delete running between the read and that write would be
        // undone by it.
        synchronized (attachmentTopologyLock(region)) {
        synchronized (lockFor(key(region, transitGatewayId))) {
            TransitGateway gateway = getRequiredTransitGateway(region, transitGatewayId);
            requireCoherentDefaultRouteTableChange(region, gateway, changes);
            if (description != null) {
                gateway.setDescription(description);
            }
            applyTransitGatewayOptionChanges(gateway.getOptions(), changes);
            applyDefaultRouteTableChanges(region, gateway, changes);
            if (removeCidrBlocks != null && !removeCidrBlocks.isEmpty()) {
                gateway.getOptions().getTransitGatewayCidrBlocks().removeIf(removeCidrBlocks::contains);
            }
            if (addCidrBlocks != null) {
                for (String cidr : addCidrBlocks) {
                    if (!gateway.getOptions().getTransitGatewayCidrBlocks().contains(cidr)) {
                        gateway.getOptions().getTransitGatewayCidrBlocks().add(cidr);
                    }
                }
            }
            transitGateways.put(key(region, transitGatewayId), gateway);
            return gateway;
        }
        }
    }

    /**
     * Carries a default route table flag change through to the id it governs and to the table
     * itself. Verified against a live account: disabling drops the id from the options entirely
     * rather than blanking it, and clears that table's own default marker while leaving the table
     * in place — the other default, if still enabled, keeps both its id and its marker.
     */
    private void applyDefaultRouteTableChanges(String region, TransitGateway gateway,
                                               TransitGatewayOptions changes) {
        if (changes == null) {
            return;
        }
        TransitGatewayOptions options = gateway.getOptions();
        // The id on the options already carries any id the request supplied, so enabling without
        // one keeps the table the gateway already names rather than dropping it.
        if (changes.getDefaultRouteTableAssociation() != null) {
            if ("enable".equals(changes.getDefaultRouteTableAssociation())) {
                markDefaultRouteTable(region, options.getAssociationDefaultRouteTableId(), true, true);
            } else {
                String previous = options.getAssociationDefaultRouteTableId();
                options.setAssociationDefaultRouteTableId(null);
                markDefaultRouteTable(region, previous, true, false);
            }
        } else if (changes.getAssociationDefaultRouteTableId() != null) {
            markDefaultRouteTable(region, changes.getAssociationDefaultRouteTableId(), true, true);
        }
        if (changes.getDefaultRouteTablePropagation() != null) {
            if ("enable".equals(changes.getDefaultRouteTablePropagation())) {
                markDefaultRouteTable(region, options.getPropagationDefaultRouteTableId(), false, true);
            } else {
                String previous = options.getPropagationDefaultRouteTableId();
                options.setPropagationDefaultRouteTableId(null);
                markDefaultRouteTable(region, previous, false, false);
            }
        } else if (changes.getPropagationDefaultRouteTableId() != null) {
            markDefaultRouteTable(region, changes.getPropagationDefaultRouteTableId(), false, true);
        }
    }

    private void markDefaultRouteTable(String region, String routeTableId, boolean association, boolean isDefault) {
        if (routeTableId == null) {
            return;
        }
        transitGatewayRouteTables.get(key(region, routeTableId)).ifPresent(routeTable -> {
            if (association) {
                routeTable.setDefaultAssociationRouteTable(isDefault);
            } else {
                routeTable.setDefaultPropagationRouteTable(isDefault);
            }
            transitGatewayRouteTables.put(key(region, routeTableId), routeTable);
        });
    }

    /**
     * A default route table flag and its id have to move together, which is what stops the two
     * from diverging: AWS will not enable association or propagation without being told which
     * existing route table to use, and will not accept an id alongside a disable. Verified against
     * a live account, including that an unknown table is reported as
     * {@code InvalidRouteTableID.NotFound} rather than a transit-gateway-specific code.
     */
    private void requireCoherentDefaultRouteTableChange(String region, TransitGateway gateway,
                                                        TransitGatewayOptions changes) {
        if (changes == null) {
            return;
        }
        TransitGatewayOptions current = gateway.getOptions();
        requireFlagAndRouteTableAgree(changes.getDefaultRouteTableAssociation(),
                changes.getAssociationDefaultRouteTableId(),
                current.getDefaultRouteTableAssociation(), current.getAssociationDefaultRouteTableId(),
                "DefaultRouteTableAssociation", "AssociationDefaultRouteTableId");
        requireFlagAndRouteTableAgree(changes.getDefaultRouteTablePropagation(),
                changes.getPropagationDefaultRouteTableId(),
                current.getDefaultRouteTablePropagation(), current.getPropagationDefaultRouteTableId(),
                "DefaultRouteTablePropagation", "PropagationDefaultRouteTableId");
        requireRouteTableOfGateway(region, gateway, changes.getAssociationDefaultRouteTableId());
        requireRouteTableOfGateway(region, gateway, changes.getPropagationDefaultRouteTableId());
    }

    /**
     * The flag and its route table id are judged against the gateway as it stands, not against the
     * request alone — which is why an id may arrive on its own. Verified against a live account:
     *
     * <ul>
     *   <li>an id on its own is accepted while the option is enabled, and rejected while it is
     *       disabled, with the message quoting the stored flag rather than the request</li>
     *   <li>{@code enable} on its own is accepted when the gateway already names a table, and
     *       rejected when it does not</li>
     *   <li>{@code disable} may not carry an id at all</li>
     * </ul>
     *
     * <p>This runs before the table is looked up, matching AWS: a disabled option paired with an
     * id that does not exist reports the combination rather than the missing table.
     */
    private void requireFlagAndRouteTableAgree(String flag, String routeTableId,
                                               String currentFlag, String currentRouteTableId,
                                               String flagName, String routeTableIdName) {
        String effectiveFlag = flag != null ? flag : currentFlag;
        if (!"enable".equals(effectiveFlag)) {
            if (routeTableId != null) {
                throw new AwsException("InvalidParameterCombination",
                        "disable " + flagName + " conflicts with " + routeTableIdName + " " + routeTableId, 400);
            }
            return;
        }
        if (flag != null && routeTableId == null && currentRouteTableId == null) {
            throw new AwsException("InvalidParameterCombination",
                    "enable " + flagName + " conflicts with " + routeTableIdName + " null", 400);
        }
    }

    /**
     * A default route table has to belong to the gateway naming it. AWS reports a table owned by
     * another gateway under the same {@code InvalidRouteTableID.NotFound} code as one that exists
     * nowhere, but qualifies the message with the gateway; both wordings are reproduced here.
     */
    private void requireRouteTableOfGateway(String region, TransitGateway gateway, String routeTableId) {
        if (routeTableId == null) {
            return;
        }
        TransitGatewayRouteTable routeTable = transitGatewayRouteTables.get(key(region, routeTableId)).orElse(null);
        if (routeTable == null) {
            throw new AwsException("InvalidRouteTableID.NotFound",
                    "Transit Gateway Route Table " + routeTableId + " was deleted or does not exist.", 400);
        }
        if (!gateway.getTransitGatewayId().equals(routeTable.getTransitGatewayId())) {
            throw new AwsException("InvalidRouteTableID.NotFound",
                    "Transit Gateway Route Table " + routeTableId + " was deleted or does not exist in Transit Gateway "
                            + gateway.getTransitGatewayId() + ".", 400);
        }
    }

    private void applyTransitGatewayOptionChanges(TransitGatewayOptions options, TransitGatewayOptions changes) {
        if (changes == null) {
            return;
        }
        if (changes.getAmazonSideAsn() != null) {
            options.setAmazonSideAsn(changes.getAmazonSideAsn());
        }
        if (changes.getAutoAcceptSharedAttachments() != null) {
            options.setAutoAcceptSharedAttachments(changes.getAutoAcceptSharedAttachments());
        }
        if (changes.getDefaultRouteTableAssociation() != null) {
            options.setDefaultRouteTableAssociation(changes.getDefaultRouteTableAssociation());
        }
        if (changes.getAssociationDefaultRouteTableId() != null) {
            options.setAssociationDefaultRouteTableId(changes.getAssociationDefaultRouteTableId());
        }
        if (changes.getDefaultRouteTablePropagation() != null) {
            options.setDefaultRouteTablePropagation(changes.getDefaultRouteTablePropagation());
        }
        if (changes.getPropagationDefaultRouteTableId() != null) {
            options.setPropagationDefaultRouteTableId(changes.getPropagationDefaultRouteTableId());
        }
        if (changes.getVpnEcmpSupport() != null) {
            options.setVpnEcmpSupport(changes.getVpnEcmpSupport());
        }
        if (changes.getDnsSupport() != null) {
            options.setDnsSupport(changes.getDnsSupport());
        }
        if (changes.getSecurityGroupReferencingSupport() != null) {
            options.setSecurityGroupReferencingSupport(changes.getSecurityGroupReferencingSupport());
        }
        if (changes.getMulticastSupport() != null) {
            options.setMulticastSupport(changes.getMulticastSupport());
        }
    }

    /**
     * Deletes a transit gateway and the default route table created with it, which is what the
     * live API does — the route table disappears alongside the gateway rather than outliving it.
     *
     * <p>AWS reports {@code deleting} here and settles on {@code deleted} about a minute later;
     * the returned object carries the settled state for the same reason creation does.
     */
    public TransitGateway deleteTransitGateway(String region, String transitGatewayId) {
        // Outermost first: the attachment check below and a concurrent attachment create have to
        // agree on who goes first.
        synchronized (attachmentTopologyLock(region)) {
        synchronized (lockFor(key(region, transitGatewayId))) {
            TransitGateway gateway = getRequiredTransitGateway(region, transitGatewayId);
            // AWS refuses while anything is still attached, naming the attachments in the message.
            List<String> attached = transitGatewayVpcAttachments.scan(k -> true).stream()
                    .filter(attachment -> region.equals(attachment.getRegion()))
                    .filter(attachment -> transitGatewayId.equals(attachment.getTransitGatewayId()))
                    .map(TransitGatewayVpcAttachment::getTransitGatewayAttachmentId)
                    .toList();
            if (!attached.isEmpty()) {
                throw new AwsException("IncorrectState", transitGatewayId
                        + " has non-deleted VPC Attachments: " + String.join(", ", attached) + ".", 400);
            }
            transitGatewayRouteTables.scan(k -> true).stream()
                    .filter(routeTable -> region.equals(routeTable.getRegion()))
                    .filter(routeTable -> transitGatewayId.equals(routeTable.getTransitGatewayId()))
                    .toList()
                    .forEach(routeTable -> {
                        // What the table owned goes with it, or the propagations and routes
                        // outlive the table they belong to and nothing can reach them again.
                        String routeTableId = routeTable.getTransitGatewayRouteTableId();
                        propagationsOf(region, routeTableId).forEach(propagation -> transitGatewayPropagations
                                .delete(propagationKey(region, routeTableId,
                                        propagation.getTransitGatewayAttachmentId())));
                        routesOf(region, routeTableId).forEach(route -> transitGatewayRoutes
                                .delete(routeKey(region, routeTableId, route.getDestinationCidrBlock())));
                        transitGatewayRouteTables.delete(key(region, routeTableId));
                        tags.delete(routeTableId);
                    });
            transitGateways.delete(key(region, transitGatewayId));
            tags.delete(transitGatewayId);
            gateway.setState("deleted");
            return gateway;
        }
        }
    }

    private TransitGateway getRequiredTransitGateway(String region, String transitGatewayId) {
        if (transitGatewayId == null || transitGatewayId.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter TransitGatewayId.", 400);
        }
        return describeTransitGateways(region, List.of(transitGatewayId), Map.of()).stream()
                .findFirst()
                .orElseThrow(() -> new AwsException("InvalidTransitGatewayID.NotFound",
                        "Transit Gateway " + transitGatewayId + " was deleted or does not exist.", 400));
    }

    private static void requireWellFormedTransitGatewayId(String transitGatewayId) {
        if (!TRANSIT_GATEWAY_ID_PATTERN.matcher(transitGatewayId).matches()) {
            throw new AwsException("InvalidTransitGatewayID.Malformed",
                    "Invalid Transit Gateway id " + transitGatewayId + ".", 400);
        }
    }

    // ─── Transit Gateway VPC Attachments ───────────────────────────────────────

    /**
     * Attaches a VPC to a transit gateway. The option defaults are the attachment's own and not
     * the gateway's: verified against a live account, {@code securityGroupReferencingSupport} is
     * enabled here where it is disabled on the gateway that owns the attachment.
     *
     * <p>An attachment is associated with the gateway's default route table only when the gateway
     * asks for that, so a gateway created with {@code DefaultRouteTableAssociation} disabled
     * produces an attachment carrying no association at all.
     */
    public TransitGatewayVpcAttachment createTransitGatewayVpcAttachment(
            String region, String transitGatewayId, String vpcId, List<String> subnetIds,
            TransitGatewayVpcAttachmentOptions requested, List<Tag> attachmentTags) {
        // Everything an attachment depends on is resolved and written under one lock: the gateway
        // it hangs off, the VPC and subnets it names, and the uniqueness rule. Resolving any of
        // them outside it lets a concurrent delete land in between, leaving an attachment that
        // names a gateway, VPC or subnet which no longer exists.
        synchronized (attachmentTopologyLock(region)) {
            TransitGateway gateway = getRequiredTransitGateway(region, transitGatewayId);
            getRequiredVpc(region, vpcId);
            // SubnetIds is required on the request, and modify refuses to leave an attachment
            // without any, so creation must not be a way to produce what modify forbids.
            if (subnetIds == null || subnetIds.isEmpty()) {
                throw new AwsException("MissingParameter",
                        "The request must contain the parameter SubnetIds.", 400);
            }
            requireAttachableSubnets(region, vpcId, subnetIds, List.of());
            boolean alreadyAttached = transitGatewayVpcAttachments.scan(k -> true).stream()
                    .filter(existing -> region.equals(existing.getRegion()))
                    .filter(existing -> transitGatewayId.equals(existing.getTransitGatewayId()))
                    .anyMatch(existing -> vpcId.equals(existing.getVpcId()));
            if (alreadyAttached) {
                throw new AwsException("DuplicateTransitGatewayAttachment",
                        transitGatewayId + " has non-deleted Transit Gateway Attachments with same VPC ID.", 400);
            }
            return storeNewAttachment(region, gateway, vpcId, subnetIds, requested, attachmentTags);
        }
    }

    private TransitGatewayVpcAttachment storeNewAttachment(
            String region, TransitGateway gateway, String vpcId, List<String> subnetIds,
            TransitGatewayVpcAttachmentOptions requested, List<Tag> attachmentTags) {
        TransitGatewayVpcAttachment attachment = new TransitGatewayVpcAttachment();
        String attachmentId = "tgw-attach-" + randomHex(17);
        attachment.setTransitGatewayAttachmentId(attachmentId);
        attachment.setTransitGatewayId(gateway.getTransitGatewayId());
        attachment.setVpcId(vpcId);
        attachment.setVpcOwnerId(accountId);
        attachment.setTransitGatewayOwnerId(gateway.getOwnerId());
        // AWS reports pending and settles on available; nothing is slow locally, the same
        // compression createTransitGateway applies.
        attachment.setState("available");
        attachment.setSubnetIds(new ArrayList<>(subnetIds));
        attachment.setCreationTime(ISO_FMT.format(Instant.now()));
        attachment.setRegion(region);
        attachment.setOptions(resolveAttachmentOptions(requested));
        // Both halves together: an association state without a table would be a shape AWS never
        // serves, and the gateway's own validation keeps the pair in step.
        if ("enable".equals(gateway.getOptions().getDefaultRouteTableAssociation())
                && gateway.getOptions().getAssociationDefaultRouteTableId() != null) {
            attachment.setAssociationRouteTableId(gateway.getOptions().getAssociationDefaultRouteTableId());
            attachment.setAssociationState("associated");
        }
        if (attachmentTags != null && !attachmentTags.isEmpty()) {
            attachment.setTags(new ArrayList<>(attachmentTags));
            tags.put(attachmentId, new ArrayList<>(attachmentTags));
        }
        transitGatewayVpcAttachments.put(key(region, attachmentId), attachment);
        return attachment;
    }

    /**
     * One region-wide monitor for every operation that creates an attachment, removes one, or
     * removes something an attachment depends on. Held outermost wherever a gateway lock is also
     * taken, so the two never interleave in opposite orders. Per-resource striped locks cannot
     * serve here: the dependency spans a gateway, a VPC and its subnets, which stripe separately.
     */
    private Object attachmentTopologyLock(String region) {
        return lockFor(key(region, "transit-gateway-attachments"));
    }

    private TransitGatewayVpcAttachmentOptions resolveAttachmentOptions(
            TransitGatewayVpcAttachmentOptions requested) {
        TransitGatewayVpcAttachmentOptions options = new TransitGatewayVpcAttachmentOptions();
        options.setDnsSupport("enable");
        options.setSecurityGroupReferencingSupport("enable");
        options.setIpv6Support("disable");
        options.setApplianceModeSupport("disable");
        applyAttachmentOptionChanges(options, requested);
        return options;
    }

    /**
     * Every subnet has to exist in the VPC being attached, and no two may share an availability
     * zone. A subnet belonging to another VPC is reported missing rather than mismatched, which is
     * what the live API does.
     */
    private void requireAttachableSubnets(String region, String vpcId, List<String> subnetIds,
                                          List<String> alreadyAttached) {
        List<String> zones = new ArrayList<>();
        for (String subnetId : alreadyAttached) {
            subnets.get(key(region, subnetId)).ifPresent(subnet -> zones.add(subnet.getAvailabilityZone()));
        }
        for (String subnetId : subnetIds) {
            Subnet subnet = subnets.get(key(region, subnetId)).orElse(null);
            if (subnet == null || !vpcId.equals(subnet.getVpcId())) {
                throw new AwsException("InvalidSubnetID.NotFound",
                        "Subnet " + subnetId + " was deleted or does not exist.", 400);
            }
            if (zones.contains(subnet.getAvailabilityZone())) {
                throw new AwsException("DuplicateSubnetsInSameZone", "Duplicate Subnets for same AZ", 400);
            }
            zones.add(subnet.getAvailabilityZone());
        }
    }

    public List<TransitGatewayVpcAttachment> describeTransitGatewayVpcAttachments(
            String region, List<String> attachmentIds, Map<String, List<String>> filters) {
        attachmentIds.forEach(Ec2Service::requireWellFormedAttachmentId);
        List<TransitGatewayVpcAttachment> all = transitGatewayVpcAttachments.scan(k -> true).stream()
                .filter(attachment -> region.equals(attachment.getRegion()))
                .collect(Collectors.toList());
        for (String attachmentId : attachmentIds) {
            if (all.stream().noneMatch(a -> a.getTransitGatewayAttachmentId().equals(attachmentId))) {
                throw new AwsException("InvalidTransitGatewayAttachmentID.NotFound",
                        "Transit Gateway Attachment " + attachmentId + " was deleted or does not exist.", 400);
            }
        }
        return all.stream()
                .filter(a -> attachmentIds.isEmpty() || attachmentIds.contains(a.getTransitGatewayAttachmentId()))
                .filter(a -> matchesFilters(a, filters, region))
                .collect(Collectors.toList());
    }

    public TransitGatewayVpcAttachment modifyTransitGatewayVpcAttachment(
            String region, String attachmentId, List<String> addSubnetIds, List<String> removeSubnetIds,
            TransitGatewayVpcAttachmentOptions changes) {
        synchronized (attachmentTopologyLock(region)) {
            TransitGatewayVpcAttachment attachment = getRequiredVpcAttachment(region, attachmentId);
            List<String> subnetIds = new ArrayList<>(attachment.getSubnetIds());
            if (removeSubnetIds != null) {
                for (String subnetId : removeSubnetIds) {
                    if (!subnetIds.remove(subnetId)) {
                        throw new AwsException("InvalidSubnetID.NotFound",
                                subnetId + " is not attached but supplied in RemoveSubnets", 400);
                    }
                }
            }
            if (addSubnetIds != null && !addSubnetIds.isEmpty()) {
                requireAttachableSubnets(region, attachment.getVpcId(), addSubnetIds, subnetIds);
                subnetIds.addAll(addSubnetIds);
            }
            // Removals are applied first, so an attachment cannot be left with nothing to attach
            // through even when the same request adds subnets back.
            if (subnetIds.isEmpty()) {
                throw new AwsException("InsufficientSubnetsException", "Insufficient Subnets", 400);
            }
            attachment.setSubnetIds(subnetIds);
            applyAttachmentOptionChanges(attachment.getOptions(), changes);
            transitGatewayVpcAttachments.put(key(region, attachmentId), attachment);
            return attachment;
        }
    }

    private void applyAttachmentOptionChanges(TransitGatewayVpcAttachmentOptions options,
                                              TransitGatewayVpcAttachmentOptions changes) {
        if (changes == null) {
            return;
        }
        if (changes.getDnsSupport() != null) {
            options.setDnsSupport(changes.getDnsSupport());
        }
        if (changes.getSecurityGroupReferencingSupport() != null) {
            options.setSecurityGroupReferencingSupport(changes.getSecurityGroupReferencingSupport());
        }
        if (changes.getIpv6Support() != null) {
            options.setIpv6Support(changes.getIpv6Support());
        }
        if (changes.getApplianceModeSupport() != null) {
            options.setApplianceModeSupport(changes.getApplianceModeSupport());
        }
    }

    /**
     * Deletes a VPC attachment and settles what pointed at it. Verified on a live account: the
     * attachment's propagations disappear, while a static route that named it survives as a
     * blackhole rather than being removed — the destination is still configured, it simply has
     * nowhere to go now.
     */
    public TransitGatewayVpcAttachment deleteTransitGatewayVpcAttachment(String region, String attachmentId) {
        synchronized (attachmentTopologyLock(region)) {
            TransitGatewayVpcAttachment attachment = getRequiredVpcAttachment(region, attachmentId);
            transitGatewayPropagations.scan(k -> true).stream()
                    .filter(propagation -> region.equals(propagation.getRegion()))
                    .filter(propagation -> attachmentId.equals(propagation.getTransitGatewayAttachmentId()))
                    .toList()
                    .forEach(propagation -> transitGatewayPropagations.delete(propagationKey(region,
                            propagation.getTransitGatewayRouteTableId(), attachmentId)));
            transitGatewayRoutes.scan(k -> true).stream()
                    .filter(route -> region.equals(route.getRegion()))
                    .filter(route -> attachmentId.equals(route.getTransitGatewayAttachmentId()))
                    .toList()
                    .forEach(route -> {
                        route.setState("blackhole");
                        route.setTransitGatewayAttachmentId(null);
                        route.setResourceId(null);
                        route.setResourceType(null);
                        transitGatewayRoutes.put(routeKey(region, route.getTransitGatewayRouteTableId(),
                                route.getDestinationCidrBlock()), route);
                    });
            transitGatewayVpcAttachments.delete(key(region, attachmentId));
            tags.delete(attachmentId);
            attachment.setState("deleted");
            return attachment;
        }
    }

    /**
     * Verified live: an id of the wrong shape is rejected before any lookup, and the message does
     * not echo it back, unlike the transit gateway's equivalent.
     */
    private static void requireWellFormedAttachmentId(String attachmentId) {
        if (!TRANSIT_GATEWAY_ATTACHMENT_ID_PATTERN.matcher(attachmentId).matches()) {
            throw new AwsException("InvalidTransitGatewayAttachmentID.Malformed",
                    "Invalid Transit Gateway Attachment id.", 400);
        }
    }

    // ─── Transit Gateway Route Tables, Associations, Propagations and Routes ───

    /**
     * An attachment reached through a route table has to hang off the same gateway. Verified on a
     * live account: associating, propagating or routing to an attachment of another gateway is
     * refused as though the attachment did not exist, rather than with a mismatch of its own.
     */
    private TransitGatewayVpcAttachment requireAttachmentOfSameGateway(
            String region, TransitGatewayRouteTable routeTable, String attachmentId) {
        TransitGatewayVpcAttachment attachment = getRequiredVpcAttachment(region, attachmentId);
        if (!routeTable.getTransitGatewayId().equals(attachment.getTransitGatewayId())) {
            throw new AwsException("InvalidTransitGatewayAttachmentID.NotFound",
                    "Transit Gateway Attachment " + attachmentId + " was deleted or does not exist.", 400);
        }
        return attachment;
    }

    public TransitGatewayRouteTable createTransitGatewayRouteTable(
            String region, String transitGatewayId, List<Tag> routeTableTags) {
        synchronized (attachmentTopologyLock(region)) {
            TransitGateway gateway = getRequiredTransitGateway(region, transitGatewayId);
            TransitGatewayRouteTable routeTable = new TransitGatewayRouteTable();
            String routeTableId = "tgw-rtb-" + randomHex(17);
            routeTable.setTransitGatewayRouteTableId(routeTableId);
            routeTable.setTransitGatewayId(gateway.getTransitGatewayId());
            // AWS reports pending and settles on available; nothing is slow locally.
            routeTable.setState("available");
            // A table asked for by name is never either default; only the one the gateway mints is.
            routeTable.setDefaultAssociationRouteTable(false);
            routeTable.setDefaultPropagationRouteTable(false);
            routeTable.setCreationTime(ISO_FMT.format(Instant.now()));
            routeTable.setRegion(region);
            if (routeTableTags != null && !routeTableTags.isEmpty()) {
                routeTable.setTags(new ArrayList<>(routeTableTags));
                tags.put(routeTableId, new ArrayList<>(routeTableTags));
            }
            transitGatewayRouteTables.put(key(region, routeTableId), routeTable);
            return routeTable;
        }
    }

    public List<TransitGatewayRouteTable> describeTransitGatewayRouteTables(
            String region, List<String> routeTableIds, Map<String, List<String>> filters) {
        routeTableIds.forEach(Ec2Service::requireWellFormedRouteTableId);
        List<TransitGatewayRouteTable> all = transitGatewayRouteTables.scan(k -> true).stream()
                .filter(routeTable -> region.equals(routeTable.getRegion()))
                .collect(Collectors.toList());
        for (String routeTableId : routeTableIds) {
            if (all.stream().noneMatch(rt -> rt.getTransitGatewayRouteTableId().equals(routeTableId))) {
                throw new AwsException("InvalidRouteTableID.NotFound",
                        "Transit Gateway Route Table " + routeTableId + " was deleted or does not exist.", 400);
            }
        }
        return all.stream()
                .filter(rt -> routeTableIds.isEmpty() || routeTableIds.contains(rt.getTransitGatewayRouteTableId()))
                .filter(rt -> matchesFilters(rt, filters, region))
                .collect(Collectors.toList());
    }

    /**
     * Verified live: a route table will not go while it is a gateway's default association table,
     * nor while attachments are associated with it, and the two refusals carry different messages
     * under one {@code IncorrectState} code.
     */
    public TransitGatewayRouteTable deleteTransitGatewayRouteTable(String region, String routeTableId) {
        synchronized (attachmentTopologyLock(region)) {
            TransitGatewayRouteTable routeTable = getRequiredTransitGatewayRouteTable(region, routeTableId);
            TransitGateway gateway = transitGatewayRouteTables.get(key(region, routeTableId))
                    .map(TransitGatewayRouteTable::getTransitGatewayId)
                    .flatMap(id -> transitGateways.get(key(region, id)))
                    .orElse(null);
            if (gateway != null
                    && (routeTableId.equals(gateway.getOptions().getAssociationDefaultRouteTableId())
                        || routeTableId.equals(gateway.getOptions().getPropagationDefaultRouteTableId()))) {
                throw new AwsException("IncorrectState", routeTableId
                        + " is set as default association route table for " + gateway.getTransitGatewayId(), 400);
            }
            if (!associationsOf(region, routeTableId).isEmpty()) {
                throw new AwsException("IncorrectState", routeTableId + " has associated attachments", 400);
            }
            propagationsOf(region, routeTableId).forEach(propagation -> transitGatewayPropagations
                    .delete(propagationKey(region, routeTableId, propagation.getTransitGatewayAttachmentId())));
            routesOf(region, routeTableId).forEach(route -> transitGatewayRoutes
                    .delete(routeKey(region, routeTableId, route.getDestinationCidrBlock())));
            transitGatewayRouteTables.delete(key(region, routeTableId));
            tags.delete(routeTableId);
            routeTable.setState("deleted");
            return routeTable;
        }
    }

    /** An attachment associates with exactly one route table, so a second attempt is refused. */
    public TransitGatewayVpcAttachment associateTransitGatewayRouteTable(
            String region, String routeTableId, String attachmentId) {
        synchronized (attachmentTopologyLock(region)) {
            TransitGatewayRouteTable routeTable = getRequiredTransitGatewayRouteTable(region, routeTableId);
            TransitGatewayVpcAttachment attachment =
                    requireAttachmentOfSameGateway(region, routeTable, attachmentId);
            if (attachment.getAssociationRouteTableId() != null) {
                throw new AwsException("Resource.AlreadyAssociated", "Transit Gateway Attachment "
                        + attachmentId + " is already associated to a route table.", 400);
            }
            attachment.setAssociationRouteTableId(routeTableId);
            attachment.setAssociationState("associated");
            transitGatewayVpcAttachments.put(key(region, attachmentId), attachment);
            return attachment;
        }
    }

    public TransitGatewayVpcAttachment disassociateTransitGatewayRouteTable(
            String region, String routeTableId, String attachmentId) {
        synchronized (attachmentTopologyLock(region)) {
            TransitGatewayRouteTable routeTable = getRequiredTransitGatewayRouteTable(region, routeTableId);
            TransitGatewayVpcAttachment attachment =
                    requireAttachmentOfSameGateway(region, routeTable, attachmentId);
            if (!routeTableId.equals(attachment.getAssociationRouteTableId())) {
                throw new AwsException("InvalidparameterValue", "Transit Gateway Attachment "
                        + attachmentId + " is not associated with route table " + routeTableId + ".", 400);
            }
            attachment.setAssociationRouteTableId(null);
            attachment.setAssociationState(null);
            transitGatewayVpcAttachments.put(key(region, attachmentId), attachment);
            return attachment;
        }
    }

    /** The attachments associated with a route table, which is where an association is recorded. */
    public List<TransitGatewayVpcAttachment> associationsOf(String region, String routeTableId) {
        return transitGatewayVpcAttachments.scan(k -> true).stream()
                .filter(attachment -> region.equals(attachment.getRegion()))
                .filter(attachment -> routeTableId.equals(attachment.getAssociationRouteTableId()))
                .collect(Collectors.toList());
    }

    public TransitGatewayRouteTablePropagation enableTransitGatewayRouteTablePropagation(
            String region, String routeTableId, String attachmentId) {
        synchronized (attachmentTopologyLock(region)) {
            TransitGatewayRouteTable routeTable = getRequiredTransitGatewayRouteTable(region, routeTableId);
            TransitGatewayVpcAttachment attachment =
                    requireAttachmentOfSameGateway(region, routeTable, attachmentId);
            if (transitGatewayPropagations.get(propagationKey(region, routeTableId, attachmentId)).isPresent()) {
                throw new AwsException("TransitGatewayRouteTablePropagation.Duplicate", "Propagation "
                        + attachmentId + " already exists in Transit Gateway Route Table " + routeTableId + ".", 400);
            }
            TransitGatewayRouteTablePropagation propagation = new TransitGatewayRouteTablePropagation();
            propagation.setTransitGatewayRouteTableId(routeTableId);
            propagation.setTransitGatewayAttachmentId(attachmentId);
            propagation.setResourceId(attachment.getVpcId());
            propagation.setResourceType("vpc");
            // Verified live: propagation reports the settled state at once, where association
            // reports associating first.
            propagation.setState("enabled");
            propagation.setRegion(region);
            transitGatewayPropagations.put(propagationKey(region, routeTableId, attachmentId), propagation);
            return propagation;
        }
    }

    public TransitGatewayRouteTablePropagation disableTransitGatewayRouteTablePropagation(
            String region, String routeTableId, String attachmentId) {
        synchronized (attachmentTopologyLock(region)) {
            TransitGatewayRouteTable routeTable = getRequiredTransitGatewayRouteTable(region, routeTableId);
            requireAttachmentOfSameGateway(region, routeTable, attachmentId);
            TransitGatewayRouteTablePropagation propagation = transitGatewayPropagations
                    .get(propagationKey(region, routeTableId, attachmentId))
                    .orElseThrow(() -> new AwsException("InvalidparameterValue", "Propagation "
                            + attachmentId + " does not exist in Transit Gateway Route Table "
                            + routeTableId + ".", 400));
            transitGatewayPropagations.delete(propagationKey(region, routeTableId, attachmentId));
            propagation.setState("disabled");
            return propagation;
        }
    }

    public List<TransitGatewayRouteTablePropagation> propagationsOf(String region, String routeTableId) {
        return transitGatewayPropagations.scan(k -> true).stream()
                .filter(propagation -> region.equals(propagation.getRegion()))
                .filter(propagation -> routeTableId.equals(propagation.getTransitGatewayRouteTableId()))
                .collect(Collectors.toList());
    }

    public TransitGatewayRoute createTransitGatewayRoute(String region, String routeTableId,
                                                         String destinationCidrBlock, String attachmentId,
                                                         boolean blackhole) {
        synchronized (attachmentTopologyLock(region)) {
            getRequiredTransitGatewayRouteTable(region, routeTableId);
            if (destinationCidrBlock == null || destinationCidrBlock.isBlank()) {
                throw new AwsException("MissingParameter",
                        "The request must contain the parameter DestinationCidrBlock.", 400);
            }
            if (transitGatewayRoutes.get(routeKey(region, routeTableId, destinationCidrBlock)).isPresent()) {
                throw new AwsException("RouteAlreadyExists", "Route " + destinationCidrBlock
                        + " already exists in Transit Gateway Route Table " + routeTableId + ".", 400);
            }
            TransitGatewayRoute route = new TransitGatewayRoute();
            route.setTransitGatewayRouteTableId(routeTableId);
            route.setDestinationCidrBlock(destinationCidrBlock);
            // A blackhole is a state of a static route rather than a type, and carries no
            // attachment even when one was named.
            route.setType("static");
            route.setState(blackhole ? "blackhole" : "active");
            route.setRegion(region);
            if (!blackhole) {
                TransitGatewayVpcAttachment attachment = requireAttachmentOfSameGateway(region,
                        getRequiredTransitGatewayRouteTable(region, routeTableId), attachmentId);
                route.setTransitGatewayAttachmentId(attachmentId);
                route.setResourceId(attachment.getVpcId());
                route.setResourceType("vpc");
            }
            transitGatewayRoutes.put(routeKey(region, routeTableId, destinationCidrBlock), route);
            return route;
        }
    }

    /**
     * Replaces a route's target, and writes the route when it is not there. Verified on a live
     * account: replacing a destination the table has never held creates it rather than reporting
     * it missing, so this is an upsert and not an update.
     */
    public TransitGatewayRoute replaceTransitGatewayRoute(String region, String routeTableId,
                                                          String destinationCidrBlock, String attachmentId,
                                                          boolean blackhole) {
        synchronized (attachmentTopologyLock(region)) {
            TransitGatewayRouteTable routeTable = getRequiredTransitGatewayRouteTable(region, routeTableId);
            if (destinationCidrBlock == null || destinationCidrBlock.isBlank()) {
                throw new AwsException("MissingParameter",
                        "The request must contain the parameter DestinationCidrBlock.", 400);
            }
            TransitGatewayRoute route = transitGatewayRoutes
                    .get(routeKey(region, routeTableId, destinationCidrBlock))
                    .orElseGet(TransitGatewayRoute::new);
            route.setTransitGatewayRouteTableId(routeTableId);
            route.setDestinationCidrBlock(destinationCidrBlock);
            route.setType("static");
            route.setRegion(region);
            // The target moves as one: a blackhole keeps no attachment, and pointing the route at
            // an attachment again restores all three fields together.
            if (blackhole) {
                route.setState("blackhole");
                route.setTransitGatewayAttachmentId(null);
                route.setResourceId(null);
                route.setResourceType(null);
            } else {
                TransitGatewayVpcAttachment attachment =
                        requireAttachmentOfSameGateway(region, routeTable, attachmentId);
                route.setState("active");
                route.setTransitGatewayAttachmentId(attachmentId);
                route.setResourceId(attachment.getVpcId());
                route.setResourceType("vpc");
            }
            transitGatewayRoutes.put(routeKey(region, routeTableId, destinationCidrBlock), route);
            return route;
        }
    }

    public TransitGatewayRoute deleteTransitGatewayRoute(String region, String routeTableId,
                                                         String destinationCidrBlock) {
        synchronized (attachmentTopologyLock(region)) {
            getRequiredTransitGatewayRouteTable(region, routeTableId);
            TransitGatewayRoute route = transitGatewayRoutes
                    .get(routeKey(region, routeTableId, destinationCidrBlock))
                    .orElseThrow(() -> new AwsException("InvalidRoute.NotFound", "The route "
                            + destinationCidrBlock + " does not exist in Transit Gateway Route Table "
                            + routeTableId + ".", 400));
            transitGatewayRoutes.delete(routeKey(region, routeTableId, destinationCidrBlock));
            route.setState("deleted");
            route.setTransitGatewayAttachmentId(null);
            route.setResourceId(null);
            route.setResourceType(null);
            return route;
        }
    }

    /**
     * The static routes written into a table, plus the propagated ones, which are a view of the
     * enabled propagations joined to the attached VPC's CIDRs rather than records of their own.
     * Deriving them keeps a VPC's CIDR changes from leaving a stale route behind.
     */
    public List<TransitGatewayRoute> searchTransitGatewayRoutes(String region, String routeTableId,
                                                                Map<String, List<String>> filters) {
        getRequiredTransitGatewayRouteTable(region, routeTableId);
        List<TransitGatewayRoute> routes = new ArrayList<>(routesOf(region, routeTableId));
        for (TransitGatewayRouteTablePropagation propagation : propagationsOf(region, routeTableId)) {
            TransitGatewayVpcAttachment attachment = transitGatewayVpcAttachments
                    .get(key(region, propagation.getTransitGatewayAttachmentId())).orElse(null);
            if (attachment == null) {
                continue;
            }
            Vpc vpc = vpcs.get(key(region, attachment.getVpcId())).orElse(null);
            if (vpc == null) {
                continue;
            }
            for (String cidr : vpcCidrBlocks(vpc)) {
                TransitGatewayRoute route = new TransitGatewayRoute();
                route.setTransitGatewayRouteTableId(routeTableId);
                route.setDestinationCidrBlock(cidr);
                route.setTransitGatewayAttachmentId(attachment.getTransitGatewayAttachmentId());
                route.setResourceId(attachment.getVpcId());
                route.setResourceType("vpc");
                route.setType("propagated");
                route.setState("active");
                route.setRegion(region);
                routes.add(route);
            }
        }
        return applyRouteFilters(routes, filters);
    }

    /**
     * The route search filters, as the live API applies them. The three CIDR relationship filters
     * differ in the value they take, which is not something the reference spells out:
     * {@code supernet-of-match} and {@code subnet-of-match} take a CIDR and match inclusively in
     * either direction, while {@code longest-prefix-match} takes a bare address and returns the one
     * most specific route covering it. Handing either the other's value form returns nothing on
     * AWS, so the same holds here.
     *
     * <p>A filter name the API does not know is rejected rather than ignored: accepting it would
     * answer a question that was never asked.
     */
    private List<TransitGatewayRoute> applyRouteFilters(List<TransitGatewayRoute> routes,
                                                        Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return routes;
        }
        List<TransitGatewayRoute> matched = new ArrayList<>(routes);
        for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
            String name = filter.getKey();
            List<String> values = filter.getValue();
            switch (name) {
                case "type" -> matched.removeIf(route -> !matchesValue(values, route.getType()));
                case "state" -> matched.removeIf(route -> !matchesValue(values, route.getState()));
                case "route-search.exact-match" ->
                        matched.removeIf(route -> !matchesValue(values, route.getDestinationCidrBlock()));
                case "attachment.transit-gateway-attachment-id" ->
                        matched.removeIf(route -> !matchesValue(values, route.getTransitGatewayAttachmentId()));
                case "attachment.resource-id" ->
                        matched.removeIf(route -> !matchesValue(values, route.getResourceId()));
                case "attachment.resource-type" ->
                        matched.removeIf(route -> !matchesValue(values, route.getResourceType()));
                case "route-search.supernet-of-match" -> matched.removeIf(route -> values.stream()
                        .noneMatch(value -> cidrContains(route.getDestinationCidrBlock(), value)));
                case "route-search.subnet-of-match" -> matched.removeIf(route -> values.stream()
                        .noneMatch(value -> cidrContains(value, route.getDestinationCidrBlock())));
                case "route-search.longest-prefix-match" -> {
                    List<TransitGatewayRoute> longest = new ArrayList<>();
                    for (String address : values) {
                        matched.stream()
                                .filter(route -> cidrContainsAddress(route.getDestinationCidrBlock(), address))
                                .max(Comparator.comparingInt(route ->
                                        prefixLengthOf(route.getDestinationCidrBlock())))
                                .ifPresent(longest::add);
                    }
                    matched.retainAll(longest);
                }
                default -> throw new AwsException("InvalidParameterValue",
                        "Value (" + name + ") for parameter Filter is invalid. ", 400);
            }
        }
        return matched;
    }

    /** Whether {@code outer} covers {@code inner}, both CIDRs, an equal pair counting as covered. */
    private boolean cidrContains(String outer, String inner) {
        int[] outerRange = cidrRange(outer);
        int[] innerRange = cidrRange(inner);
        if (outerRange == null || innerRange == null) {
            return false;
        }
        return outerRange[1] <= innerRange[1]
                && (innerRange[0] & maskOf(outerRange[1])) == outerRange[0];
    }

    /** Whether a CIDR covers a bare address, which is the form longest-prefix-match takes. */
    private boolean cidrContainsAddress(String cidr, String address) {
        if (address == null || address.contains("/")) {
            return false;
        }
        int[] range = cidrRange(cidr);
        Integer packed = packIpv4(address);
        if (range == null || packed == null) {
            return false;
        }
        return (packed & maskOf(range[1])) == range[0];
    }

    private int prefixLengthOf(String cidr) {
        int[] range = cidrRange(cidr);
        return range == null ? -1 : range[1];
    }

    /** The network address and prefix length of an IPv4 CIDR, or null when it is neither. */
    private int[] cidrRange(String cidr) {
        if (cidr == null) {
            return null;
        }
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            return null;
        }
        Integer packed = packIpv4(cidr.substring(0, slash));
        if (packed == null) {
            return null;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (prefix < 0 || prefix > 32) {
            return null;
        }
        return new int[] {packed & maskOf(prefix), prefix};
    }

    private int maskOf(int prefixLength) {
        return prefixLength == 0 ? 0 : (int) (-1L << (32 - prefixLength));
    }

    private Integer packIpv4(String address) {
        String[] octets = address.split("\\.");
        if (octets.length != 4) {
            return null;
        }
        int packed = 0;
        for (String octet : octets) {
            int value;
            try {
                value = Integer.parseInt(octet);
            } catch (NumberFormatException e) {
                return null;
            }
            if (value < 0 || value > 255) {
                return null;
            }
            packed = (packed << 8) | value;
        }
        return packed;
    }

    private List<String> vpcCidrBlocks(Vpc vpc) {
        List<String> blocks = new ArrayList<>();
        if (vpc.getCidrBlock() != null) {
            blocks.add(vpc.getCidrBlock());
        }
        vpc.getCidrBlockAssociationSet().stream()
                .map(VpcCidrBlockAssociation::getCidrBlock)
                .filter(cidr -> cidr != null && !blocks.contains(cidr))
                .forEach(blocks::add);
        return blocks;
    }

    private List<TransitGatewayRoute> routesOf(String region, String routeTableId) {
        return transitGatewayRoutes.scan(k -> true).stream()
                .filter(route -> region.equals(route.getRegion()))
                .filter(route -> routeTableId.equals(route.getTransitGatewayRouteTableId()))
                .collect(Collectors.toList());
    }

    private String propagationKey(String region, String routeTableId, String attachmentId) {
        return key(region, routeTableId + "::" + attachmentId);
    }

    private String routeKey(String region, String routeTableId, String destinationCidrBlock) {
        return key(region, routeTableId + "::" + destinationCidrBlock);
    }

    private TransitGatewayRouteTable getRequiredTransitGatewayRouteTable(String region, String routeTableId) {
        if (routeTableId == null || routeTableId.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter TransitGatewayRouteTableId.", 400);
        }
        requireWellFormedRouteTableId(routeTableId);
        return transitGatewayRouteTables.get(key(region, routeTableId))
                .filter(routeTable -> region.equals(routeTable.getRegion()))
                .orElseThrow(() -> new AwsException("InvalidRouteTableID.NotFound",
                        "Transit Gateway Route Table " + routeTableId + " was deleted or does not exist.", 400));
    }

    /**
     * Verified live, including the casing: the not-found code spells it {@code InvalidRouteTableID}
     * and the malformed one {@code InvalidRouteTableId}.
     */
    private static void requireWellFormedRouteTableId(String routeTableId) {
        if (!TRANSIT_GATEWAY_ROUTE_TABLE_ID_PATTERN.matcher(routeTableId).matches()) {
            throw new AwsException("InvalidRouteTableId.Malformed",
                    "Invalid Transit Gateway Route Table id " + routeTableId + ".", 400);
        }
    }

    private TransitGatewayVpcAttachment getRequiredVpcAttachment(String region, String attachmentId) {
        if (attachmentId == null || attachmentId.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter TransitGatewayAttachmentId.", 400);
        }
        requireWellFormedAttachmentId(attachmentId);
        return transitGatewayVpcAttachments.get(key(region, attachmentId))
                .orElseThrow(() -> new AwsException("InvalidTransitGatewayAttachmentID.NotFound",
                        "Transit Gateway Attachment " + attachmentId + " was deleted or does not exist.", 400));
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

        String vpcId = subnet != null ? subnet.getVpcId() : resolveDefaultVpcId(region);
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
            SecurityGroup defaultSg = securityGroups.get(key(region, resolveDefaultSecurityGroupId(region))).orElse(null);
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
        for (int i = 0; i < count; i++) {
            String instanceId = "i-" + randomHex(17);
            String privateIp = assignPrivateIp(region, finalSubnetId);

            Instance inst = new Instance();
            inst.setInstanceId(instanceId);
            inst.setImageId(imageId);
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

            // Root EBS volume
            String rootVolId = "vol-" + randomHex(17);
            inst.setRootVolumeId(rootVolId);
            Volume rootVol = new Volume();
            rootVol.setVolumeId(rootVolId);
            rootVol.setAvailabilityZone(az);
            rootVol.setVolumeType(DEFAULT_ROOT_VOLUME_TYPE);
            rootVol.setSize(DEFAULT_ROOT_VOLUME_SIZE_GIB);
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
        synchronized (attachmentTopologyLock(region)) {
            requireNoTransitGatewayAttachment(region,
                    attachment -> vpcId.equals(attachment.getVpcId()),
                    "The vpc '" + vpcId + "' has dependencies and cannot be deleted.");
            vpcs.delete(key(region, vpcId));
        }
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
                                         List<String> securityGroupIds, Boolean privateDnsEnabled, List<Tag> endpointTags) {
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
        boolean isInterface = "Interface".equalsIgnoreCase(endpoint.getVpcEndpointType());
        endpoint.setPrivateDnsEnabled(privateDnsEnabled != null ? privateDnsEnabled : isInterface);
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
                ni.setPrivateIpAddress(endpointPrivateIp(subnet, endpoint.getVpcEndpointId()));
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

    /** Stable host address near the top of the subnet range, clear of the instance counter (starts at 10). */
    private static String endpointPrivateIp(Subnet subnet, String endpointId) {
        String cidr = subnet.getCidrBlock();
        String baseIp = cidr != null ? cidr.split("/")[0] : "172.31.0.0";
        String[] parts = baseIp.split("\\.");
        int host = 200 + Math.floorMod(endpointId.hashCode(), 50);
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
        synchronized (attachmentTopologyLock(region)) {
            requireNoTransitGatewayAttachment(region,
                    attachment -> attachment.getSubnetIds().contains(subnetId),
                    "The subnet '" + subnetId + "' has dependencies and cannot be deleted.");
            subnets.delete(key(region, subnetId));
        }
    }

    /**
     * A VPC or subnet carrying a transit gateway attachment cannot be deleted out from under it.
     * Verified on a live account: both report {@code DependencyViolation} with the same wording,
     * rather than leaving the attachment pointing at something that no longer exists.
     */
    private void requireNoTransitGatewayAttachment(
            String region, java.util.function.Predicate<TransitGatewayVpcAttachment> dependsOnIt, String message) {
        boolean attached = transitGatewayVpcAttachments.scan(k -> true).stream()
                .filter(attachment -> region.equals(attachment.getRegion()))
                .anyMatch(dependsOnIt);
        if (attached) {
            throw new AwsException("DependencyViolation", message, 400);
        }
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
            vpcId = resolveDefaultVpcId(region);
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
        ensureDefaultResources(region);
        List<SecurityGroupRule> rules = new ArrayList<>();
        synchronized (lockFor(key(region, groupId))) {
            SecurityGroup sg = getRequiredSecurityGroup(region, groupId);
            requireKnownPrefixLists(region, permissions);
            List<IpPermission> next = new ArrayList<>(sg.getIpPermissions());
            for (IpPermission perm : permissions) {
                resolveGroupReferences(region, sg.getVpcId(), perm);
                next.add(perm);
                rules.addAll(createRules(region, groupId, perm, false));
            }
            sg.setIpPermissions(next);
            securityGroups.put(key(region, groupId), sg);
        }
        reconcilePublishedPortsForGroup(region, groupId);
        return rules;
    }

    public List<SecurityGroupRule> authorizeSecurityGroupEgress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        List<SecurityGroupRule> rules = new ArrayList<>();
        synchronized (lockFor(key(region, groupId))) {
            SecurityGroup sg = getRequiredSecurityGroup(region, groupId);
            requireKnownPrefixLists(region, permissions);
            List<IpPermission> next = new ArrayList<>(sg.getIpPermissionsEgress());
            for (IpPermission perm : permissions) {
                resolveGroupReferences(region, sg.getVpcId(), perm);
                next.add(perm);
                rules.addAll(createRules(region, groupId, perm, true));
            }
            sg.setIpPermissionsEgress(next);
            securityGroups.put(key(region, groupId), sg);
        }
        return rules;
    }

    /**
     * Flattens one permission into the {@link SecurityGroupRule} entries DescribeSecurityGroupRules
     * serves. AWS gives every rule exactly one source, so a permission carrying several sources fans
     * out into one rule each.
     *
     * <p>Deliberately does not validate the prefix lists it reads: authorize resolves every list a
     * request names before the first write. Re-checking per permission would reopen the
     * partial-write window, since a list can be deleted between the two checks — the group lock and
     * the prefix list lock are different monitors. The other callers pass a CIDR-only default egress
     * permission.
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
            for (PrefixListId prefixList : perm.getPrefixListIds()) {
                SecurityGroupRule rule = newRule(groupId, perm, egress);
                rule.setPrefixListId(prefixList.getPrefixListId());
                rule.setDescription(prefixList.getDescription());
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

    /**
     * Resolves every prefix list a request names before anything is written. AWS rejects the whole
     * call, so a permission carrying a valid CIDR alongside an unknown list must persist neither.
     */
    private void requireKnownPrefixLists(String region, List<IpPermission> permissions) {
        for (IpPermission perm : permissions) {
            if (perm.getPrefixListIds() == null) {
                continue;
            }
            for (PrefixListId prefixList : perm.getPrefixListIds()) {
                getRequiredManagedPrefixList(region, prefixList.getPrefixListId());
            }
        }
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
        ensureDefaultResources(region);
        synchronized (lockFor(key(region, groupId))) {
            SecurityGroup sg = getRequiredSecurityGroup(region, groupId);
            // Authorize stores a group reference by id, so a revoke naming it by name alone has to
            // resolve the same way before the sources can be compared.
            for (IpPermission perm : permissions) {
                resolveGroupReferences(region, sg.getVpcId(), perm);
            }
            List<IpPermission> next = revokeSources(new ArrayList<>(sg.getIpPermissions()), permissions);
            sg.setIpPermissions(next);
            securityGroups.put(key(region, groupId), sg);
        }
        reconcilePublishedPortsForGroup(region, groupId);
    }

    public void revokeSecurityGroupEgress(String region, String groupId, List<IpPermission> permissions) {
        ensureDefaultResources(region);
        synchronized (lockFor(key(region, groupId))) {
            SecurityGroup sg = getRequiredSecurityGroup(region, groupId);
            for (IpPermission perm : permissions) {
                resolveGroupReferences(region, sg.getVpcId(), perm);
            }
            List<IpPermission> next = revokeSources(new ArrayList<>(sg.getIpPermissionsEgress()), permissions);
            sg.setIpPermissionsEgress(next);
            securityGroups.put(key(region, groupId), sg);
        }
    }

    /**
     * Deletes a rule by its {@code sgr-} id, which is what a standalone
     * {@code AWS::EC2::SecurityGroupIngress} or {@code Egress} resource holds as its physical id.
     * Returns false when the rule is already gone, so a stack delete stays idempotent.
     */
    public boolean deleteSecurityGroupRule(String region, String securityGroupRuleId) {
        ensureDefaultResources(region);
        SecurityGroupRule rule = securityGroupRules.get(key(region, securityGroupRuleId)).orElse(null);
        if (rule == null) {
            return false;
        }
        securityGroupRules.delete(key(region, securityGroupRuleId));
        String groupId = rule.getGroupId();
        if (groupId == null) {
            return true;
        }
        synchronized (lockFor(key(region, groupId))) {
            SecurityGroup sg = securityGroups.get(key(region, groupId)).orElse(null);
            if (sg == null) {
                return true;
            }
            List<IpPermission> next = new ArrayList<>(rule.isEgress()
                    ? sg.getIpPermissionsEgress() : sg.getIpPermissions());
            if (removeRecordedPermission(next, rule)) {
                if (rule.isEgress()) {
                    sg.setIpPermissionsEgress(next);
                } else {
                    sg.setIpPermissions(next);
                }
                securityGroups.put(key(region, groupId), sg);
            }
        }
        if (!rule.isEgress()) {
            reconcilePublishedPortsForGroup(region, groupId);
        }
        return true;
    }

    /**
     * Drops exactly the peer a rule record names, its ipv4 cidr, ipv6 cidr or referenced group, and
     * the whole permission only once nothing is left in it. Matching on protocol and ports alone
     * would take out an unrelated rule that happens to share them.
     */
    private boolean removeRecordedPermission(List<IpPermission> perms, SecurityGroupRule rule) {
        String referencedGroupId = rule.getReferencedGroupInfo() == null
                ? null : rule.getReferencedGroupInfo().getGroupId();
        for (IpPermission perm : perms) {
            if (!Objects.equals(perm.getIpProtocol(), rule.getIpProtocol())
                    || !Objects.equals(perm.getFromPort(), rule.getFromPort())
                    || !Objects.equals(perm.getToPort(), rule.getToPort())) {
                continue;
            }
            if (rule.getCidrIpv6() != null) {
                Ipv6Range match6 = perm.getIpv6Ranges().stream()
                        .filter(r -> rule.getCidrIpv6().equals(r.getCidrIpv6()))
                        .findFirst().orElse(null);
                if (match6 == null) {
                    continue;
                }
                perm.getIpv6Ranges().remove(match6);
                dropIfEmpty(perms, perm);
                return true;
            }
            if (referencedGroupId != null) {
                UserIdGroupPair matchPair = perm.getUserIdGroupPairs().stream()
                        .filter(pair -> referencedGroupId.equals(pair.getGroupId()))
                        .findFirst().orElse(null);
                if (matchPair == null) {
                    continue;
                }
                perm.getUserIdGroupPairs().remove(matchPair);
                dropIfEmpty(perms, perm);
                return true;
            }
            if (rule.getCidrIpv4() != null) {
                IpRange match = perm.getIpRanges().stream()
                        .filter(r -> rule.getCidrIpv4().equals(r.getCidrIp()))
                        .findFirst().orElse(null);
                if (match == null) {
                    continue;
                }
                perm.getIpRanges().remove(match);
                dropIfEmpty(perms, perm);
                return true;
            }
            // A record naming no peer matches only a permission that names none either.
            if (perm.getIpRanges().isEmpty() && perm.getIpv6Ranges().isEmpty()
                    && perm.getUserIdGroupPairs().isEmpty()) {
                perms.remove(perm);
                return true;
            }
        }
        return false;
    }

    private static void dropIfEmpty(List<IpPermission> perms, IpPermission perm) {
        if (perm.getIpRanges().isEmpty() && perm.getIpv6Ranges().isEmpty()
                && perm.getUserIdGroupPairs().isEmpty()) {
            perms.remove(perm);
        }
    }

    private SecurityGroup getRequiredSecurityGroup(String region, String groupId) {
        SecurityGroup sg = securityGroups.get(key(region, groupId)).orElse(null);
        if (sg == null)
            throw new AwsException("InvalidGroup.NotFound", "The security group '" + groupId + "' does not exist", 400);

        return sg;
    }

    /**
     * Revocation is scoped to the sources it names, as on AWS: revoking one source leaves other
     * permissions sharing the same protocol and ports in place, and a permission that names
     * several sources loses only those revoked. A request naming no source at all still removes
     * the whole matching permission, which is how a bare protocol/port revoke behaves.
     *
     * <p>Returns the permissions that remain.
     */
    private List<IpPermission> revokeSources(List<IpPermission> existing, List<IpPermission> toRemove) {
        List<IpPermission> remaining = new ArrayList<>();
        for (IpPermission perm : existing) {
            boolean dropWholePermission = false;
            boolean hadSources = hasSources(perm);
            for (IpPermission removal : toRemove) {
                if (!sameProtocolAndPorts(perm, removal)) {
                    continue;
                }
                if (!hasSources(removal)) {
                    dropWholePermission = true;
                    break;
                }
                // authorize stores the caller's IpPermission object, so a revoke can name the very
                // instance held on the group. Snapshot the values before mutating either list.
                List<String> cidrs = removal.getIpRanges().stream().map(IpRange::getCidrIp).toList();
                List<String> cidrsV6 = removal.getIpv6Ranges().stream().map(Ipv6Range::getCidrIpv6).toList();
                List<String> lists = removal.getPrefixListIds().stream()
                        .map(PrefixListId::getPrefixListId).toList();
                List<String> groups = removal.getUserIdGroupPairs().stream()
                        .map(UserIdGroupPair::getGroupId).toList();
                perm.getIpRanges().removeIf(e -> cidrs.contains(e.getCidrIp()));
                perm.getIpv6Ranges().removeIf(e -> cidrsV6.contains(e.getCidrIpv6()));
                perm.getPrefixListIds().removeIf(e -> lists.contains(e.getPrefixListId()));
                perm.getUserIdGroupPairs().removeIf(e -> groups.contains(e.getGroupId()));
            }
            // A permission that had sources and has lost them all is gone; one that never had any
            // survives unless a sourceless revoke named it.
            if (!dropWholePermission && (!hadSources || hasSources(perm))) {
                remaining.add(perm);
            }
        }
        return remaining;
    }

    private boolean sameProtocolAndPorts(IpPermission a, IpPermission b) {
        return Objects.equals(a.getIpProtocol(), b.getIpProtocol())
                && Objects.equals(a.getFromPort(), b.getFromPort())
                && Objects.equals(a.getToPort(), b.getToPort());
    }

    private boolean hasSources(IpPermission perm) {
        return !perm.getIpRanges().isEmpty() || !perm.getIpv6Ranges().isEmpty()
                || !perm.getPrefixListIds().isEmpty() || !perm.getUserIdGroupPairs().isEmpty();
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
        if (config.services().ec2().mock()) {
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

    /**
     * {@code Owner.N} takes the aliases {@code self}, {@code amazon} and {@code aws-marketplace}
     * beside bare account ids, while {@code imageOwnerId} is always an account id. Only
     * {@code self} was translated, so an alias matched nothing unless an image happened to be
     * owned by the literal string.
     */
    private boolean matchesImageOwners(Image image, List<String> owners) {
        if (owners == null || owners.isEmpty()) {
            return true;
        }
        String ownerId = image.getOwnerId();
        return owners.contains(ownerId)
                || (owners.contains("self") && accountId.equals(ownerId))
                || (owners.contains("amazon") && AMAZON_OWNER_ID.equals(ownerId))
                || (owners.contains("aws-marketplace") && AWS_MARKETPLACE_OWNER_ID.equals(ownerId));
    }

    /**
     * Whether an image satisfies a DescribeImages filter set. Exposed so a synthesized lookup image
     * can be checked against the request that produced it before being returned.
     */
    public boolean imageMatchesFilters(Image image, Map<String, List<String>> filters) {
        return matchesRegisteredImageFilters(image, filters);
    }

    /**
     * Whether an image satisfies a DescribeImages owner scope. Exposed alongside
     * {@link #imageMatchesFilters} so a synthesized lookup image faces the whole request that
     * produced it, since {@code Owner.N} is carried outside the filter set.
     */
    public boolean imageMatchesOwners(Image image, List<String> owners) {
        return matchesImageOwners(image, owners);
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

    /**
     * AWS filter values take two wildcards, {@code *} for any run of characters and {@code ?} for
     * exactly one. Only {@code *} was honoured here, so a {@code ?} was matched literally and a
     * pattern like {@code ubuntu-?} found nothing, while {@link #wildcardToRegex} a few hundred
     * lines down already treated both.
     */
    private boolean wildcardMatches(String pattern, String value) {
        if (pattern == null) {
            return false;
        }
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return pattern.equals(value);
        }
        String regex = pattern.chars()
                .mapToObj(ch -> switch (ch) {
                    case '*' -> ".*";
                    case '?' -> ".";
                    default -> java.util.regex.Pattern.quote(String.valueOf((char) ch));
                })
                .collect(Collectors.joining());
        return value.matches(regex);
    }

    // ─── Tags ──────────────────────────────────────────────────────────────────

    public void createTags(String region, List<String> resourceIds, List<Tag> tagList) {
        ensureDefaultResources(region);
        for (String resourceId : resourceIds) {
            withTopologyLockIfNeeded(region, resourceId, () -> {
                synchronized (lockFor(key(region, resourceId))) {
                    List<Tag> existing = new ArrayList<>(tags.get(resourceId).orElse(List.of()));
                    for (Tag tag : tagList) {
                        existing.removeIf(t -> t.getKey().equals(tag.getKey()));
                        existing.add(tag);
                    }
                    tags.put(resourceId, existing);
                    // Update resource objects
                    updateResourceTags(region, resourceId, existing);
                }
            });
        }
    }

    /**
     * Runs a tag change with the topology lock already held when the resource is one a transit
     * gateway delete can remove. Order matters as much as the lock does: the deletes take the
     * topology lock and then a striped resource lock, so tagging has to take them the same way
     * round or the two deadlock.
     */
    private void withTopologyLockIfNeeded(String region, String resourceId, Runnable change) {
        if (resourceId != null && resourceId.startsWith("tgw-")) {
            synchronized (attachmentTopologyLock(region)) {
                change.run();
            }
            return;
        }
        change.run();
    }

    public void deleteTags(String region, List<String> resourceIds, List<Tag> tagList) {
        ensureDefaultResources(region);
        for (String resourceId : resourceIds) {
            withTopologyLockIfNeeded(region, resourceId, () -> {
            synchronized (lockFor(key(region, resourceId))) {
                List<Tag> stored = tags.get(resourceId).orElse(null);
                if (stored != null) {
                    List<Tag> existing = new ArrayList<>(stored);
                    for (Tag tag : tagList) {
                        existing.removeIf(t -> t.getKey().equals(tag.getKey())
                                && (tag.getValue() == null || tag.getValue().equals(t.getValue())));
                    }
                    tags.put(resourceId, existing);
                    updateResourceTags(region, resourceId, existing);
                }
            }
            });
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
        SecurityGroupRule sgRule = securityGroupRules.get(storeKey).orElse(null);
        if (sgRule != null) { sgRule.setTags(new ArrayList<>(tagList)); securityGroupRules.put(storeKey, sgRule); return; }
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
        if (natGateway != null) { natGateway.setTags(new ArrayList<>(tagList)); natGateways.put(storeKey, natGateway); return; }
        NetworkAcl networkAcl = networkAcls.get(storeKey).orElse(null);
        if (networkAcl != null) { networkAcl.setTags(new ArrayList<>(tagList)); networkAcls.put(storeKey, networkAcl); return; }
        Address address = addresses.get(storeKey).orElse(null);
        if (address != null) { address.setTags(new ArrayList<>(tagList)); addresses.put(storeKey, address); return; }
        ManagedPrefixList prefixList = managedPrefixLists.get(storeKey).orElse(null);
        if (prefixList != null) {
            prefixList.setTags(new ArrayList<>(tagList));
            managedPrefixLists.put(storeKey, prefixList);
            return;
        }
        // Reached with the topology lock already held for tgw- resources, so the read-modify-write
        // below cannot put back something a concurrent delete has just removed.
        {
            TransitGateway gateway = transitGateways.get(storeKey).orElse(null);
            if (gateway != null) {
                gateway.setTags(new ArrayList<>(tagList));
                transitGateways.put(storeKey, gateway);
                return;
            }
            TransitGatewayRouteTable routeTable = transitGatewayRouteTables.get(storeKey).orElse(null);
            if (routeTable != null) {
                routeTable.setTags(new ArrayList<>(tagList));
                transitGatewayRouteTables.put(storeKey, routeTable);
                return;
            }
            TransitGatewayVpcAttachment attachment = transitGatewayVpcAttachments.get(storeKey).orElse(null);
            if (attachment != null) {
                attachment.setTags(new ArrayList<>(tagList));
                transitGatewayVpcAttachments.put(storeKey, attachment);
            }
        }
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
        if (resourceId.startsWith("i-")) {
            return "instance";
        }
        if (resourceId.startsWith("vpc-")) {
            return "vpc";
        }
        if (resourceId.startsWith("subnet-")) {
            return "subnet";
        }
        if (resourceId.startsWith("sgr-")) {
            return "security-group-rule";
        }
        if (resourceId.startsWith("sg-")) {
            return "security-group";
        }
        if (resourceId.startsWith("igw-")) {
            return "internet-gateway";
        }
        if (resourceId.startsWith("rtb-")) {
            return "route-table";
        }
        if (resourceId.startsWith("key-")) {
            return "key-pair";
        }
        if (resourceId.startsWith("eipalloc-")) {
            return "elastic-ip";
        }
        if (resourceId.startsWith("lt-")) {
            return "launch-template";
        }
        if (resourceId.startsWith("vpce-")) {
            return "vpc-endpoint";
        }
        if (resourceId.startsWith("nat-")) {
            return "natgateway";
        }
        if (resourceId.startsWith("pl-")) {
            return "prefix-list";
        }
        // Both checked before the gateway prefix, which they start with.
        if (resourceId.startsWith("tgw-attach-")) {
            return "transit-gateway-attachment";
        }
        if (resourceId.startsWith("tgw-rtb-")) {
            return "transit-gateway-route-table";
        }
        if (resourceId.startsWith("tgw-")) {
            return "transit-gateway";
        }
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
        return AwsRegions.ALL;
    }

    public Map<String, String> describeAccountAttributes(String region) {
        ensureDefaultResources(region);
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("supported-platforms", "VPC");
        attrs.put("default-vpc", resolveDefaultVpcId(region));
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
        if (resource instanceof TransitGatewayVpcAttachment attachment) {
            return switch (filterName) {
                case "transit-gateway-attachment-id" -> matchesValue(values, attachment.getTransitGatewayAttachmentId());
                case "transit-gateway-id" -> matchesValue(values, attachment.getTransitGatewayId());
                case "vpc-id" -> matchesValue(values, attachment.getVpcId());
                case "vpc-owner-id" -> matchesValue(values, attachment.getVpcOwnerId());
                case "state" -> matchesValue(values, attachment.getState());
                case "resource-id" -> matchesValue(values, attachment.getVpcId());
                case "resource-type" -> matchesValue(values, "vpc");
                default -> true;
            };
        }
        if (resource instanceof TransitGateway gateway) {
            return switch (filterName) {
                case "transit-gateway-id" -> matchesValue(values, gateway.getTransitGatewayId());
                case "state" -> matchesValue(values, gateway.getState());
                case "owner-id" -> matchesValue(values, gateway.getOwnerId());
                case "options.amazon-side-asn" ->
                        matchesValue(values, String.valueOf(gateway.getOptions().getAmazonSideAsn()));
                case "options.association-default-route-table-id" ->
                        matchesValue(values, gateway.getOptions().getAssociationDefaultRouteTableId());
                case "options.propagation-default-route-table-id" ->
                        matchesValue(values, gateway.getOptions().getPropagationDefaultRouteTableId());
                case "options.dns-support" -> matchesValue(values, gateway.getOptions().getDnsSupport());
                case "options.vpn-ecmp-support" -> matchesValue(values, gateway.getOptions().getVpnEcmpSupport());
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
        if (resource instanceof LaunchTemplate lt) return lt.getTags();
        if (resource instanceof VpcEndpoint endpoint) return endpoint.getTags();
        if (resource instanceof NatGateway natGateway) return natGateway.getTags();
        if (resource instanceof SpotInstanceRequest sir) return sir.getTags();
        if (resource instanceof TransitGateway gateway) return gateway.getTags();
        if (resource instanceof TransitGatewayRouteTable routeTable) return routeTable.getTags();
        if (resource instanceof TransitGatewayVpcAttachment attachment) return attachment.getTags();
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
