package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;
import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog.CatalogNetworkCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksNodeCapacityTest {
    private static final long MIB = 1024L * 1024L;
    private final Ec2InstanceTypeCatalog catalog = new Ec2InstanceTypeCatalog();

    @Test
    void reservationsFollowTheAppliedLimitAndTheInstancePodDensity() {
        CatalogInstanceType type = catalog.find("m5.large").orElseThrow();
        long hostMib = 16384;
        EksNodeCapacity.Limits limits = EksNodeCapacity.calculate(type, hostMib * MIB, 8, 0, 0);
        long limitMib = limits.memoryBytes() / MIB;
        int maxPods = type.networkCards.getFirst().maximumNetworkInterfaces
                * (type.ipv4AddressesPerInterface - 1) + 2;

        assertEquals(type.memoryMib + Math.max(128, type.memoryMib / 10), limitMib);
        assertEquals(type.vcpu, limits.vcpus());
        assertEquals(255 + 11L * maxPods, limits.kubeMemoryMib());
        assertEquals(Math.max(128, limitMib / 10), limits.systemMemoryMib());
        assertEquals(hostMib - limitMib + 100, limits.evictionMemoryMib());
        assertEquals((8 - limits.vcpus()) * 1000, limits.systemCpuMilli());
        assertEquals(70, limits.kubeCpuMilli());

        List<String> args = new ArrayList<>();
        limits.addKubeletArgs(args);
        assertTrue(args.contains("--kubelet-arg=system-reserved=cpu=" + limits.systemCpuMilli()
                + "m,memory=" + limits.systemMemoryMib() + "Mi"));
        assertTrue(args.contains("--kubelet-arg=kube-reserved=cpu=" + limits.kubeCpuMilli()
                + "m,memory=" + limits.kubeMemoryMib() + "Mi"));
        assertTrue(args.contains("--kubelet-arg=eviction-hard=memory.available<"
                + limits.evictionMemoryMib() + "Mi,nodefs.available<10%,nodefs.inodesFree<5%"));
    }

    @Test
    void hostAndUserCeilingsBoundTheContainerAndReservations() {
        CatalogInstanceType type = catalog.find("m5.large").orElseThrow();
        EksNodeCapacity.Limits limits = EksNodeCapacity.calculate(type, 8192 * MIB, 4, 4096, 1);
        assertEquals(4096 * MIB, limits.memoryBytes());
        assertEquals(1, limits.vcpus());
        assertEquals(60, limits.kubeCpuMilli());
        assertEquals(3000, limits.systemCpuMilli());
        assertEquals(8192 - 4096 + 100, limits.evictionMemoryMib());
    }

    @Test
    void largeManagedNodeUsesThe250PodCapForMemoryReservation() {
        CatalogInstanceType type = new CatalogInstanceType();
        type.vcpu = 32;
        type.memoryMib = 131072;
        type.ipv4AddressesPerInterface = 50;
        CatalogNetworkCard card = new CatalogNetworkCard();
        card.networkCardIndex = 0;
        card.maximumNetworkInterfaces = 15;
        type.networkCards = List.of(card);

        EksNodeCapacity.Limits limits = EksNodeCapacity.calculate(type, 262144 * MIB, 64, 0, 0);
        assertEquals(255 + 11 * 250, limits.kubeMemoryMib());
    }

    @Test
    void tooSmallHostOrMissingHostInformationFallsBackToUnbounded() {
        CatalogInstanceType type = catalog.find("m5.large").orElseThrow();
        assertNull(EksNodeCapacity.calculate(type, 1024 * MIB, 2, 0, 0));
        assertNull(EksNodeCapacity.calculate(type, 0, 0, 0, 0));
        EksNodeCapacity.Limits capped = EksNodeCapacity.calculate(type, 8192 * MIB, 2, 512, 0);
        assertNotNull(capped);
        assertEquals(512 * MIB, capped.memoryBytes());
        assertTrue(capped.kubeMemoryMib() + capped.systemMemoryMib()
                + 100 < capped.memoryBytes() / MIB);
    }
}
