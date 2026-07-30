package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.SpotInstanceRequest;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for issue #1984: a public IP must be assigned only to instances
 * whose subnet opts in via MapPublicIpOnLaunch. Before the fix every instance got
 * PublicIpAddress 127.0.0.1 regardless of subnet, so a private-subnet instance
 * wrongly looked internet-facing. The launch-time flag (associatePublicIp) drives
 * whether Ec2ContainerManager exposes a public address once the container is up.
 */
class Ec2PublicIpOnLaunchTest {

    private static final String REGION = "us-east-1";
    private static final String AMI = "ami-0abcdef1234567890";

    @Test
    void publicSubnetInstanceGetsPublicIpFlag_privateDoesNot(@TempDir Path dir) {
        Ec2Service ec2 = newService(dir);
        Vpc vpc = ec2.createVpc(REGION, "10.0.0.0/16", false);

        Subnet publicSubnet = ec2.createSubnet(REGION, vpc.getVpcId(), "10.0.0.0/24", REGION + "a");
        ec2.modifySubnetAttribute(REGION, publicSubnet.getSubnetId(), "mapPublicIpOnLaunch", "true");

        Subnet privateSubnet = ec2.createSubnet(REGION, vpc.getVpcId(), "10.0.1.0/24", REGION + "a");
        // privateSubnet keeps MapPublicIpOnLaunch=false (the createSubnet default)

        Instance inPublic = runOne(ec2, publicSubnet.getSubnetId());
        Instance inPrivate = runOne(ec2, privateSubnet.getSubnetId());

        assertTrue(inPublic.isAssociatePublicIp(),
                "instance in a MapPublicIpOnLaunch subnet must be flagged for a public IP");
        assertFalse(inPrivate.isAssociatePublicIp(),
                "instance in a private subnet must NOT be flagged for a public IP");

        // The behavior #1984 is about, not just the flag: after the container
        // manager's gate runs, a private-subnet instance reports no public
        // address while a public-subnet one reports the host-reachable one.
        applyContainerManagerPublicIpGate(inPublic);
        applyContainerManagerPublicIpGate(inPrivate);
        assertEquals("127.0.0.1", inPublic.getPublicIpAddress(),
                "public-subnet instance must report the host-reachable public IP");
        assertNull(inPrivate.getPublicIpAddress(),
                "private-subnet instance must report no public IP");
        assertNull(inPrivate.getPublicDnsName(),
                "private-subnet instance must report no public DNS name");
    }

    @Test
    void launchTimeOverrideBeatsSubnetDefaultBothDirections(@TempDir Path dir) {
        Ec2Service ec2 = newService(dir);
        Vpc vpc = ec2.createVpc(REGION, "10.0.0.0/16", false);

        Subnet publicSubnet = ec2.createSubnet(REGION, vpc.getVpcId(), "10.0.0.0/24", REGION + "a");
        ec2.modifySubnetAttribute(REGION, publicSubnet.getSubnetId(), "mapPublicIpOnLaunch", "true");
        Subnet privateSubnet = ec2.createSubnet(REGION, vpc.getVpcId(), "10.0.1.0/24", REGION + "a");

        // AWS precedence: the launch-time AssociatePublicIpAddress override wins
        // over the subnet attribute in both directions.
        Instance forcedOn = runOne(ec2, privateSubnet.getSubnetId(), Boolean.TRUE);
        Instance forcedOff = runOne(ec2, publicSubnet.getSubnetId(), Boolean.FALSE);

        assertTrue(forcedOn.isAssociatePublicIp(),
                "launch-time true must force a public IP in a private subnet");
        assertFalse(forcedOff.isAssociatePublicIp(),
                "launch-time false must suppress the public IP in a public subnet");
    }

    /**
     * Mirrors the gate in Ec2ContainerManager (launch(), the
     * isAssociatePublicIp branch): public addresses are set only for flagged
     * instances. The manager is mocked out here to keep Docker away, so the
     * decision is replicated verbatim — if the branch there changes shape,
     * update this too.
     */
    private static void applyContainerManagerPublicIpGate(Instance instance) {
        if (instance.isAssociatePublicIp()) {
            instance.setPublicIpAddress("127.0.0.1");
            instance.setPublicDnsName("localhost");
        }
    }

    private Instance runOne(Ec2Service ec2, String subnetId) {
        return runOne(ec2, subnetId, null);
    }

    private Instance runOne(Ec2Service ec2, String subnetId, Boolean associatePublicIp) {
        Reservation r = ec2.runInstances(REGION, AMI, "t2.micro", 1, 1, null,
                List.of(), subnetId, null, List.of(), null, null, associatePublicIp);
        return r.getInstances().get(0);
    }

    private Ec2Service newService(Path dir) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        // mock() == true short-circuits the container launch in runInstances, so
        // no Docker is needed — the associatePublicIp flag is set before that.
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2Cfg = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2Cfg);
        when(ec2Cfg.mock()).thenReturn(true);
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        // A mock container manager makes launch() a no-op — the associatePublicIp
        // flag is set on the instance BEFORE launch, so no Docker is needed here.
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog,
                new Ec2InstanceTypeCatalog(),
                load(dir, "ec2-vpcs.json", new TypeReference<Map<String, Vpc>>() {}),
                load(dir, "ec2-subnets.json", new TypeReference<Map<String, Subnet>>() {}),
                load(dir, "ec2-security-groups.json", new TypeReference<Map<String, SecurityGroup>>() {}),
                load(dir, "ec2-security-group-rules.json", new TypeReference<Map<String, SecurityGroupRule>>() {}),
                load(dir, "ec2-internet-gateways.json", new TypeReference<Map<String, InternetGateway>>() {}),
                load(dir, "ec2-route-tables.json", new TypeReference<Map<String, RouteTable>>() {}),
                load(dir, "ec2-key-pairs.json", new TypeReference<Map<String, KeyPair>>() {}),
                load(dir, "ec2-addresses.json", new TypeReference<Map<String, Address>>() {}),
                load(dir, "ec2-instances.json", new TypeReference<Map<String, Instance>>() {}),
                load(dir, "ec2-volumes.json", new TypeReference<Map<String, Volume>>() {}),
                load(dir, "ec2-registered-images.json", new TypeReference<Map<String, Image>>() {}),
                load(dir, "ec2-snapshots.json", new TypeReference<Map<String, Snapshot>>() {}),
                load(dir, "ec2-launch-templates.json", new TypeReference<Map<String, LaunchTemplate>>() {}),
                load(dir, "ec2-vpc-endpoints.json", new TypeReference<Map<String, VpcEndpoint>>() {}),
                load(dir, "ec2-nat-gateways.json", new TypeReference<Map<String, NatGateway>>() {}),
                load(dir, "ec2-spot-instance-requests.json", new TypeReference<Map<String, SpotInstanceRequest>>() {}),
                load(dir, "ec2-network-acls.json", new TypeReference<Map<String, NetworkAcl>>() {}),
                load(dir, "ec2-tags.json", new TypeReference<Map<String, List<Tag>>>() {}));
    }

    private <V> StorageBackend<String, V> load(Path dir, String file, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> backend = new PersistentStorage<>(dir.resolve(file), type);
        backend.load();
        return backend;
    }
}
