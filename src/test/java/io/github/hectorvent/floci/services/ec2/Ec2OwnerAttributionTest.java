package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.TransitGateway;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for issue #3775. EC2 attributed every resource to the configured default
 * account, no matter which account the caller resolved to, so {@code OwnerId} disagreed with
 * what STS reported for the same credentials and {@code DescribeImages --owners <caller>}
 * matched nothing. Each resource must carry the calling account as its owner (and in its ARN),
 * and the {@code self} owner alias must resolve to that same account.
 *
 * <p>Hermetic: a real {@link Ec2Service} over in-memory storage with a stubbed request context
 * whose account the test switches between calls.
 */
class Ec2OwnerAttributionTest {

    private static final String REGION = "us-east-1";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String CALLER = "444444444444";
    private static final String OTHER_CALLER = "111111111111";

    private final AtomicReference<String> caller = new AtomicReference<>(CALLER);

    @Test
    void registeredImageIsOwnedByTheCallingAccount() {
        Ec2Service service = newService();

        Image image = service.registerImage(REGION, "owner-test", null, "x86_64", "/dev/xvda", List.of());

        assertEquals(CALLER, image.getOwnerId());
        assertTrue(describesImage(service, image, List.of(CALLER)),
                "filtering on the account the caller actually uses must match its own image");
        assertTrue(describesImage(service, image, List.of("self")),
                "the self alias must resolve to the calling account");
        assertFalse(describesImage(service, image, List.of(DEFAULT_ACCOUNT)),
                "the default account must not be reported as owner of another account's image");
    }

    @Test
    void ownerFollowsTheCallerWhenTheAccountChanges() {
        Ec2Service service = newService();
        Image first = service.registerImage(REGION, "first", null, "x86_64", "/dev/xvda", List.of());

        caller.set(OTHER_CALLER);
        Image second = service.registerImage(REGION, "second", null, "x86_64", "/dev/xvda", List.of());

        assertEquals(CALLER, first.getOwnerId());
        assertEquals(OTHER_CALLER, second.getOwnerId());
        // The owner is resolved per request, not frozen at construction time.
        assertTrue(describesImage(service, second, List.of("self")));
        assertFalse(describesImage(service, first, List.of("self")),
                "self must not match an image the current caller does not own");
    }

    @Test
    void snapshotsAreOwnedByTheCallingAccountAndDescribeDefaultsToThem() {
        Ec2Service service = newService();
        service.registerImage(REGION, "with-snapshot", null, "x86_64", "/dev/xvda",
                List.of(blockDeviceMapping("snap-3775", 8)));

        List<Snapshot> own = service.describeSnapshots(REGION, List.of(), List.of(), Map.of());
        assertEquals(1, own.size(), "with no owner filter DescribeSnapshots reports the caller's own snapshots");
        assertEquals(CALLER, own.getFirst().getOwnerId());

        assertEquals(1, service.describeSnapshots(REGION, List.of(), List.of("self"), Map.of()).size());
        assertTrue(service.describeSnapshots(REGION, List.of(), List.of(DEFAULT_ACCOUNT), Map.of()).isEmpty(),
                "the default account owns none of the caller's snapshots");
    }

    @Test
    void networkingResourcesAreOwnedByTheCallingAccount() {
        Ec2Service service = newService();

        Vpc vpc = service.createVpc(REGION, "10.0.0.0/16", false);
        Subnet subnet = service.createSubnet(REGION, vpc.getVpcId(), "10.0.1.0/24", REGION + "a");
        SecurityGroup sg = service.createSecurityGroup(REGION, "owner-sg", "t", vpc.getVpcId());
        InternetGateway igw = service.createInternetGateway(REGION);
        RouteTable routeTable = service.createRouteTable(REGION, vpc.getVpcId());
        ManagedPrefixList prefixList = service.createManagedPrefixList(REGION, "owner-pl", "IPv4", 5,
                List.of(new PrefixListEntry("10.0.0.0/8", "corporate")), List.of());

        assertEquals(CALLER, vpc.getOwnerId());
        assertEquals(CALLER, subnet.getOwnerId());
        assertTrue(subnet.getSubnetArn().contains(":" + CALLER + ":"), subnet.getSubnetArn());
        assertEquals(CALLER, sg.getOwnerId());
        assertEquals(CALLER, igw.getOwnerId());
        assertEquals(CALLER, routeTable.getOwnerId());
        assertEquals(CALLER, prefixList.getOwnerId());
        assertTrue(prefixList.getPrefixListArn().contains(":" + CALLER + ":"), prefixList.getPrefixListArn());
    }

    @Test
    void transitGatewayCapacityReservationAndEniAreOwnedByTheCallingAccount() {
        Ec2Service service = newService();

        TransitGateway gateway = service.createTransitGateway(REGION, "owner-tgw", null, List.of());
        CapacityReservation reservation = service.createCapacityReservation(REGION, "t3.micro",
                "Linux/UNIX", REGION + "a", null, 1, null, null, null, null, null, null, null, null);
        Vpc vpc = service.createVpc(REGION, "10.1.0.0/16", false);
        Subnet subnet = service.createSubnet(REGION, vpc.getVpcId(), "10.1.1.0/24", REGION + "a");
        NetworkInterface eni = service.createNetworkInterface(REGION, subnet.getSubnetId(), "owner-eni",
                null, List.of(), List.of(), List.of());

        assertEquals(CALLER, gateway.getOwnerId());
        assertTrue(gateway.getTransitGatewayArn().contains(":" + CALLER + ":"),
                gateway.getTransitGatewayArn());
        assertEquals(CALLER, reservation.getOwnerId());
        assertTrue(reservation.getCapacityReservationArn().contains(":" + CALLER + ":"),
                reservation.getCapacityReservationArn());
        assertEquals(CALLER, eni.getOwnerId());
    }

    @Test
    void launchTemplateCreatedByNamesTheCallingAccount() {
        Ec2Service service = newService();

        LaunchTemplate template = service.createLaunchTemplate(REGION, "owner-lt",
                new LaunchTemplateData(), List.of());

        // createdBy is an IAM ARN, so a frozen owner would hand back the default account's root.
        assertEquals("arn:aws:iam::" + CALLER + ":root", template.getCreatedBy());
    }

    @Test
    void withoutARequestAccountResourcesFallBackToTheDefaultAccount() {
        Ec2Service service = newService();
        caller.set(null);

        Image image = service.registerImage(REGION, "startup-seeded", null, "x86_64", "/dev/xvda", List.of());

        assertEquals(DEFAULT_ACCOUNT, image.getOwnerId());
    }

    private static boolean describesImage(Ec2Service service, Image image, List<String> owners) {
        return service.describeImages(REGION, List.of(), owners, Map.of()).stream()
                .anyMatch(img -> image.getImageId().equals(img.getImageId()));
    }

    private static BlockDeviceMapping blockDeviceMapping(String snapshotId, int volumeSize) {
        EbsBlockDevice ebs = new EbsBlockDevice();
        ebs.setSnapshotId(snapshotId);
        ebs.setVolumeSize(volumeSize);
        BlockDeviceMapping mapping = new BlockDeviceMapping();
        mapping.setDeviceName("/dev/sda1");
        mapping.setEbs(ebs);
        return mapping;
    }

    @SuppressWarnings("unchecked")
    private Ec2Service newService() {
        RequestContext requestContext = mock(RequestContext.class);
        when(requestContext.getAccountId()).thenAnswer(invocation -> caller.get());
        Instance<RequestContext> contextInstance = mock(Instance.class);
        when(contextInstance.get()).thenReturn(requestContext);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(DEFAULT_ACCOUNT);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);

        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        return new Ec2Service(config, null, mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog, new Ec2InstanceTypeCatalog(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                contextInstance);
    }
}
