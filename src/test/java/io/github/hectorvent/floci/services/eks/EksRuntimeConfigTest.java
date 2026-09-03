package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.services.eks.model.EksClusterRuntimeConfig;
import io.github.hectorvent.floci.services.eks.model.KubernetesNetworkConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EksRuntimeConfigTest {

    @Test
    void resolvesOnlyTheSupportedCreateTags() {
        EksClusterRuntimeConfig runtime = EksRuntimeConfig.fromCreateTags(Map.of(
                ReservedTags.EKS_IMAGE_KEY, "registry.example.test/k3s:v1.34.0",
                ReservedTags.EKS_NODE_IPV4_ADDRESS_KEY, "172.20.0.20",
                ReservedTags.EKS_POD_IPV4_CIDR_KEY, "10.244.0.0/16"));

        assertEquals("registry.example.test/k3s:v1.34.0", runtime.getImage());
        assertEquals("172.20.0.20", runtime.getNodeIpv4Address());
        assertEquals("10.244.0.0/16", runtime.getPodIpv4Cidr());
    }

    @Test
    void preservesGlobalDefaultsWhenNoRuntimeTagsAreSet() {
        EksClusterRuntimeConfig runtime = EksRuntimeConfig.fromCreateTags(Map.of("team", "platform"));

        assertNull(runtime.getImage());
        assertNull(runtime.getNodeIpv4Address());
        assertNull(runtime.getPodIpv4Cidr());
    }

    @Test
    void rejectsReservedTagsOwnedByOtherServices() {
        AwsException exception = assertThrows(AwsException.class,
                () -> EksRuntimeConfig.fromCreateTags(Map.of(ReservedTags.OVERRIDE_ID_KEY, "not-eks")));

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void rejectsOverlappingServiceAndPodNetworks() {
        EksClusterRuntimeConfig runtime = new EksClusterRuntimeConfig(null, null, "10.100.1.0/24");

        AwsException exception = assertThrows(AwsException.class,
                () -> EksRuntimeConfig.validate(runtime, network("10.100.0.0/16")));

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void rejectsAStaticNodeAddressInsideTheKubernetesNetworks() {
        EksClusterRuntimeConfig runtime = new EksClusterRuntimeConfig(null, "10.244.0.20", "10.244.0.0/16");

        AwsException exception = assertThrows(AwsException.class,
                () -> EksRuntimeConfig.validate(runtime, network("10.100.0.0/16")));

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    private static KubernetesNetworkConfig network(String serviceIpv4Cidr) {
        KubernetesNetworkConfig network = new KubernetesNetworkConfig();
        network.setIpFamily("ipv4");
        network.setServiceIpv4Cidr(serviceIpv4Cidr);
        return network;
    }
}
