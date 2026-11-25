package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Ec2InstanceNetworkInterfaceSnapshotTest {

    @Test
    void aSnapshotKeepsIpv4PrefixesAndIpv6MetadataWithoutSharingMutableLists() {
        InstanceNetworkInterface original = new InstanceNetworkInterface();
        original.setNetworkInterfaceId("eni-held");
        original.setIpv4Prefixes(new ArrayList<>(List.of("10.0.1.0/28")));
        original.setIpv6Addresses(new ArrayList<>(List.of("2001:db8:1::1")));
        original.setSubnetIpv6CidrBlocks(new ArrayList<>(List.of("2001:db8:1::/64")));
        original.setVpcIpv6CidrBlocks(new ArrayList<>(List.of("2001:db8:1::/56")));

        InstanceNetworkInterface snapshot = original.copy();
        assertEquals("eni-held", snapshot.getNetworkInterfaceId());
        assertEquals(List.of("10.0.1.0/28"), snapshot.getIpv4Prefixes());
        assertEquals(List.of("2001:db8:1::1"), snapshot.getIpv6Addresses());
        assertEquals(List.of("2001:db8:1::/64"), snapshot.getSubnetIpv6CidrBlocks());
        assertEquals(List.of("2001:db8:1::/56"), snapshot.getVpcIpv6CidrBlocks());

        snapshot.getIpv4Prefixes().clear();
        snapshot.getIpv6Addresses().clear();
        snapshot.getSubnetIpv6CidrBlocks().clear();
        original.getVpcIpv6CidrBlocks().clear();
        assertEquals(List.of("10.0.1.0/28"), original.getIpv4Prefixes());
        assertEquals(List.of("2001:db8:1::1"), original.getIpv6Addresses());
        assertEquals(List.of("2001:db8:1::/64"), original.getSubnetIpv6CidrBlocks());
        assertEquals(List.of("2001:db8:1::/56"), snapshot.getVpcIpv6CidrBlocks());
    }
}
