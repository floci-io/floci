package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;

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
    void publicSubnetInstanceGetsPublicIpFlag_privateDoesNot() {
        Ec2Service ec2 = newService();
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
    void launchTimeOverrideBeatsSubnetDefaultBothDirections() {
        Ec2Service ec2 = newService();
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

    private Ec2Service newService() {
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
                new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
