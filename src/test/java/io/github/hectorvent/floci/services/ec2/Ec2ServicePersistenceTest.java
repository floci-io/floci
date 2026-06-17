package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for issue #1297 (persistent-restart case). EC2 networking and instance metadata
 * must be persisted via StorageFactory so that the VPC/subnet ids CloudFormation exports survive a
 * Floci restart. Before the fix Ec2Service used plain in-memory maps, so after a restart the
 * persisted CloudFormation exports/stack referenced VPC/subnet ids that EC2 had lost
 * (describe-subnets returned [] and ELBv2 failed with SubnetNotFound).
 *
 * <p>This builds an Ec2Service over PersistentStorage in a temp dir, creates a VPC/subnet, then
 * builds a SECOND Ec2Service over the SAME files (simulating a process restart) and asserts the
 * resources are still visible.
 */
class Ec2ServicePersistenceTest {

    private static final String REGION = "us-east-1";

    @Test
    void vpcAndSubnetSurviveRestart(@TempDir Path dir) {
        Ec2Service first = newService(dir);
        Vpc vpc = first.createVpc(REGION, "10.0.0.0/16", false);
        Subnet subnet = first.createSubnet(REGION, vpc.getVpcId(), "10.0.1.0/24", REGION + "a");

        // A fresh service over the same persistent files = a restart with the same data dir.
        Ec2Service restarted = newService(dir);

        List<Vpc> vpcs = restarted.describeVpcs(REGION, List.of(vpc.getVpcId()), Map.of());
        assertEquals(1, vpcs.size(), "VPC must survive restart");
        assertEquals("10.0.0.0/16", vpcs.get(0).getCidrBlock());

        List<Subnet> subnets = restarted.describeSubnets(REGION, List.of(subnet.getSubnetId()), Map.of());
        assertEquals(1, subnets.size(), "Subnet must survive restart");
        assertEquals(vpc.getVpcId(), subnets.get(0).getVpcId());
        assertEquals("10.0.1.0/24", subnets.get(0).getCidrBlock());
    }

    private Ec2Service newService(Path dir) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        return new Ec2Service(config, null, null, null,
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
                load(dir, "ec2-launch-templates.json", new TypeReference<Map<String, LaunchTemplate>>() {}),
                load(dir, "ec2-vpc-endpoints.json", new TypeReference<Map<String, VpcEndpoint>>() {}),
                load(dir, "ec2-nat-gateways.json", new TypeReference<Map<String, NatGateway>>() {}),
                load(dir, "ec2-tags.json", new TypeReference<Map<String, List<Tag>>>() {}));
    }

    private <V> StorageBackend<String, V> load(Path dir, String file, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> backend = new PersistentStorage<>(dir.resolve(file), type);
        backend.load();
        return backend;
    }
}
