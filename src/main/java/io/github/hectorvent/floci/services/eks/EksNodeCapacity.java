package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;

import java.util.List;

/** The one calculation shared by the Docker limit and the kubelet's node budget. */
final class EksNodeCapacity {
    private static final long MIB = 1024L * 1024L;

    private EksNodeCapacity() {}

    record Limits(long memoryBytes, int vcpus, long systemMemoryMib, long kubeMemoryMib,
                  long evictionMemoryMib, int systemCpuMilli, int kubeCpuMilli,
                  boolean reducedReservations, boolean kubeletArgsEnabled) {
        void addKubeletArgs(List<String> args) {
            if (!kubeletArgsEnabled) {
                return;
            }
            args.add("--kubelet-arg=system-reserved=cpu=" + systemCpuMilli + "m,memory="
                    + systemMemoryMib + "Mi");
            args.add("--kubelet-arg=kube-reserved=cpu=" + kubeCpuMilli + "m,memory="
                    + kubeMemoryMib + "Mi");
            args.add("--kubelet-arg=eviction-hard=memory.available<" + evictionMemoryMib
                    + "Mi,nodefs.available<10%,nodefs.inodesFree<5%");
        }
    }

    static Limits explicitCeilingWithoutHostInfo(int maxMemoryMib, int vcpus) {
        return new Limits((long) Math.max(0, maxMemoryMib) * MIB, vcpus,
                0, 0, 0, 0, 0, false, false);
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
        long hostLimitMib = Math.min(requestedMib, hostMib * 4 / 5);
        long memoryMib = hostLimitMib;
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
        int podCap = type.vcpu > 30 ? 250 : 110;
        int maxPods = interfaces > 0 && addresses > 0
                ? Math.min(podCap, interfaces * (addresses - 1) + 2) : podCap;
        long kubeMemoryMib = 11L * maxPods + 255;
        long systemMemoryMib = Math.max(128, memoryMib / 10);
        long evictionBufferMib = Math.min(100, memoryMib / 4);
        // Only an undersized host with no explicit ceiling falls back to the old unbounded mode.
        // An operator's ceiling must remain a hard bound, even if it leaves few resources for pods.
        if (maxMemoryMib == 0 && memoryMib < kubeMemoryMib + systemMemoryMib + evictionBufferMib + 256) {
            return null;
        }
        if (memoryMib <= 0) {
            return null;
        }
        boolean reducedReservations = maxMemoryMib > 0
                && memoryMib < kubeMemoryMib + systemMemoryMib + evictionBufferMib + 256;
        if (reducedReservations) {
            long reservableMib = Math.max(0, memoryMib - evictionBufferMib - 1);
            kubeMemoryMib = Math.min(kubeMemoryMib, reservableMib / 2);
            systemMemoryMib = Math.min(systemMemoryMib, reservableMib - kubeMemoryMib);
        }
        int kubeCpuMilli = cpuReservationMilli(vcpus);
        int systemCpuMilli = (hostCpus - vcpus) * 1000;
        long evictionMemoryMib = hostMib - memoryMib + evictionBufferMib;
        return new Limits(memoryMib * MIB, vcpus, systemMemoryMib, kubeMemoryMib,
                evictionMemoryMib, systemCpuMilli, kubeCpuMilli, reducedReservations, true);
    }

    private static int cpuReservationMilli(int vcpus) {
        int first = Math.min(vcpus, 1) * 60;
        int second = Math.min(Math.max(vcpus - 1, 0), 1) * 10;
        int third = Math.min(Math.max(vcpus - 2, 0), 2) * 5;
        int rest = Math.max(vcpus - 4, 0) * 25 / 10;
        return first + second + third + rest;
    }
}
