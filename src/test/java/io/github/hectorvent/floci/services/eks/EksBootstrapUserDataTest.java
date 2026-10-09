package io.github.hectorvent.floci.services.eks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksBootstrapUserDataTest {

    @Test
    void extractsClusterNameFromBootstrapScripts() {
        assertEquals("my-cluster",
                EksBootstrapUserData.extractClusterName("#!/bin/bash\n/etc/eks/bootstrap.sh my-cluster\n").orElse(null));
        assertEquals("test-cluster", EksBootstrapUserData.extractClusterName(
                "#!/bin/bash\n/etc/eks/bootstrap.sh --b64-cluster-ca dGVzdA== --apiserver-endpoint https://k8s.example.com test-cluster\n").orElse(null));
        assertEquals("my-prod-cluster", EksBootstrapUserData.extractClusterName(
                "#!/bin/bash\n/etc/eks/bootstrap.sh my-prod-cluster --kubelet-extra-args '--node-labels=workload=compute'\n").orElse(null));
    }

    @Test
    void extractsClusterNameFromNodeadmConfig() {
        String yaml = "---\napiVersion: node.eks.aws/v1alpha1\nkind: NodeConfig\nspec:\n  cluster:\n    name: nodeadm-cluster\n";
        assertEquals("nodeadm-cluster", EksBootstrapUserData.extractClusterName(yaml).orElse(null));
    }

    @Test
    void extractsClusterNameFromMimeMultipartNodeadmConfig() {
        String mime = "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"//\"\r\n\r\n"
                + "--//\r\nContent-Type: application/node.eks.aws\r\n\r\n---\r\n"
                + "apiVersion: node.eks.aws/v1alpha1\r\nkind: NodeConfig\r\nspec:\r\n  cluster:\r\n    name: mime-nodeadm-cluster\r\n--//--\r\n";
        assertEquals("mime-nodeadm-cluster", EksBootstrapUserData.extractClusterName(mime).orElse(null));
    }

    @Test
    void returnsEmptyForUnrelatedUserData() {
        assertTrue(EksBootstrapUserData.extractClusterName(null).isEmpty());
        assertTrue(EksBootstrapUserData.extractClusterName("").isEmpty());
        assertTrue(EksBootstrapUserData.extractClusterName("#!/bin/bash\necho hello world").isEmpty());
        assertTrue(EksBootstrapUserData.extractClusterName("export CLUSTER_NAME=\"my-cluster\"").isEmpty());
        assertTrue(EksBootstrapUserData.extractClusterName("--cluster-name my-cluster").isEmpty());
        String appYaml = "app:\n  version: 1.0\ncluster:\n  name: redis-cache-cluster\n  nodes: 3\n";
        assertTrue(EksBootstrapUserData.extractClusterName(appYaml).isEmpty());
    }
}
