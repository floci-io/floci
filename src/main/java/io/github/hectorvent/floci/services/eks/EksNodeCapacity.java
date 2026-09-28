package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;

import java.util.List;

/** The one calculation shared by the Docker limit and the kubelet's node budget. */
final class EksNodeCapacity {
    private static final long MIB = 1024L * 1024L;

    private EksNodeCapacity() {}

    record Limits(long memoryBytes, int vcpus, long systemMemoryMib, long kubeMemoryMib,
                  long evictionMemoryMib, int systemCpuMilli, int kubeCpuMilli) {
        void addKubeletArgs(List<String> args) {
            args.add("--kubelet-arg=system-reserved=cpu=" + systemCpuMilli + "m,memory="
                    + systemMemoryMib + "Mi");
            args.add("--kubelet-arg=kube-reserved=cpu=" + kubeCpuMilli + "m,memory="
                    + kubeMemoryMib + "Mi");
            args.add("--kubelet-arg=eviction-hard=memory.available<" + evictionMemoryMib
                    + "Mi,nodefs.available<10%,nodefs.inodesFree<5%");
        }
    }

    /**
     * Docker's host totals are also what kubelet sees in this nested setup. Its eviction threshold
     * includes the host-to-cgroup gap; otherwise the cgroup can OOM before kubelet sees pressure.
     * The EKS AMI kube reservation is 11 MiB per pod plus 255 MiB, and its CPU tiers are 6%,
     * 1%, 0.5%, then 0.25% of successive cores. The additional system memory is Floci's
     * container headroom, not an EKS AMI default.
     */
    static Limits calculate(CatalogInstanceType type, long hostMemoryBytes, int hostCpus,
                            int maxMemoryMib, int maxVcpus) {
        if (hostMemoryBytes <= 0 || hostCpus <= 0 || maxMemoryMib < 0 || maxVcpus < 0) {
            return null;
        }
        long hostMib = hostMemoryBytes / MIB;
        long requestedMib = type.memoryMib + Math.max(128L, type.memoryMib / 10L);
        long memoryMib = Math.min(requestedMib, hostMib * 4 / 5);
        if (maxMemoryMib > 0) {
            memoryMib = Math.min(memoryMib, maxMemoryMib);
        }
        int vcpus = Math.min(type.vcpu, hostCpus);
        if (maxVcpus > 0) {
            vcpus = Math.min(vcpus, maxVcpus);
        }
        if (vcpus <= 0) {
            return null;
        }

        int interfaces = type.networkCards.stream()
                .filter(card -> card.networkCardIndex != null && card.networkCardIndex == 0
                        && card.maximumNetworkInterfaces != null)
                .mapToInt(card -> card.maximumNetworkInterfaces)
                .findFirst().orElse(0);
        int addresses = type.ipv4AddressesPerInterface == null ? 0 : type.ipv4AddressesPerInterface;
        int maxPods = interfaces > 0 && addresses > 0
                ? Math.min(110, interfaces * (addresses - 1) + 2) : 110;
        long kubeMemoryMib = 11L * maxPods + 255;
        long systemMemoryMib = Math.max(128, memoryMib / 10);
        long evictionBufferMib = 100;
        // A tiny host or a restrictive user cap cannot run k3s with this reservation. The caller
        // leaves the container unbounded so creating a cluster remains possible on such hosts.
        if (memoryMib < kubeMemoryMib + systemMemoryMib + evictionBufferMib + 256) {
            return null;
        }
        int kubeCpuMilli = cpuReservationMilli(vcpus);
        int systemCpuMilli = (hostCpus - vcpus) * 1000;
        long evictionMemoryMib = hostMib - memoryMib + evictionBufferMib;
        return new Limits(memoryMib * MIB, vcpus, systemMemoryMib, kubeMemoryMib,
                evictionMemoryMib, systemCpuMilli, kubeCpuMilli);
    }

    private static int cpuReservationMilli(int vcpus) {
        int first = Math.min(vcpus, 1) * 60;
        int second = Math.min(Math.max(vcpus - 1, 0), 1) * 10;
        int third = Math.min(Math.max(vcpus - 2, 0), 2) * 5;
        int rest = Math.max(vcpus - 4, 0) * 25 / 10;
        return first + second + third + rest;
    }
}
