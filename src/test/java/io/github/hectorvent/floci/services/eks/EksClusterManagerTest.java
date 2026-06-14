package io.github.hectorvent.floci.services.eks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksClusterManagerTest {

    @Test
    void webhookKubeconfigEmbedsServerUrl() {
        String url = "http://host.docker.internal:4566/_floci/eks/token-webhook";
        String yaml = EksClusterManager.buildWebhookKubeconfig(url);

        assertTrue(yaml.contains("kind: Config"), "should be a kubeconfig");
        assertTrue(yaml.contains("server: " + url), "should point the webhook at Floci");
        assertTrue(yaml.contains("current-context: floci-token-webhook"),
                "should select the webhook context");
    }

    @Test
    void webhookKubeconfigUsesContainerNetworkAddress() {
        String url = "http://172.18.0.5:4566/_floci/eks/token-webhook";
        String yaml = EksClusterManager.buildWebhookKubeconfig(url);
        assertTrue(yaml.contains("server: " + url));
    }

    @Test
    void hostModeAlwaysReturnsHostReachableEndpoint() {
        assertEquals("https://localhost:6500",
                EksClusterManager.resolvePublicEndpoint(true, "host", "floci-eks-demo", 6500));
        assertEquals("https://localhost:6500",
                EksClusterManager.resolvePublicEndpoint(false, "host", "floci-eks-demo", 6500));
    }

    @Test
    void networkModeReturnsContainerDnsOnlyInContainer() {
        assertEquals("https://floci-eks-demo:6443",
                EksClusterManager.resolvePublicEndpoint(true, "network", "floci-eks-demo", 6500));
        // Native mode has no usable container DNS name — falls back to the host endpoint.
        assertEquals("https://localhost:6500",
                EksClusterManager.resolvePublicEndpoint(false, "network", "floci-eks-demo", 6500));
    }

    @Test
    void endpointModeIsCaseInsensitiveAndDefaultsToHost() {
        assertEquals("https://floci-eks-demo:6443",
                EksClusterManager.resolvePublicEndpoint(true, "NETWORK", "floci-eks-demo", 6500));
        // Unknown / unset modes behave as host.
        assertEquals("https://localhost:6500",
                EksClusterManager.resolvePublicEndpoint(true, "bogus", "floci-eks-demo", 6500));
    }
}
