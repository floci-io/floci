package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudhsmv2.CloudHsmV2Client;
import software.amazon.awssdk.services.cloudhsmv2.model.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CloudHsmV2Test {

    private final CloudHsmV2Client client = TestFixtures.cloudHsmV2Client();

    @Test
    public void testFullLifecycle() {
        // 1. Create Cluster
        CreateClusterResponse createClusterResponse = client.createCluster(r -> r
                .hsmType("hsm1.medium")
                .subnetIds("subnet-1", "subnet-2")
                .mode(ClusterMode.FIPS)
                .networkType(NetworkType.IPV4)
                .backupRetentionPolicy(b -> b.type("DAYS").value("30"))
        );
        
        Cluster cluster = createClusterResponse.cluster();
        assertThat(cluster.clusterId()).startsWith("cluster-");
        assertThat(cluster.hsmType()).isEqualTo("hsm1.medium");
        assertThat(cluster.stateAsString()).isEqualTo("UNINITIALIZED");
        assertThat(cluster.certificates().clusterCsr()).isNotBlank();
        
        String clusterId = cluster.clusterId();

        // 2. Initialize Cluster
        // Since we are mocking, we can just use the generated CSR or dummy certificates
        String dummyCert = "-----BEGIN CERTIFICATE-----\nMIIC\n-----END CERTIFICATE-----\n";
        InitializeClusterResponse initResp = client.initializeCluster(r -> r
                .clusterId(clusterId)
                .signedCert(dummyCert)
                .trustAnchor(dummyCert)
        );
        
        assertThat(initResp.stateAsString()).isEqualTo("INITIALIZED");
        
        // 3. Create HSM
        CreateHsmResponse hsmResp = client.createHsm(r -> r
                .clusterId(clusterId)
                .availabilityZone("us-east-1a")
                .ipAddress("10.0.1.5")
        );
        
        Hsm hsm = hsmResp.hsm();
        assertThat(hsm.hsmId()).startsWith("hsm-");
        assertThat(hsm.stateAsString()).isEqualTo("ACTIVE");
        
        String hsmId = hsm.hsmId();

        // 4. Describe Clusters
        DescribeClustersResponse descClusters = client.describeClusters(r -> r
                .filters(java.util.Map.of("clusterIds", List.of(clusterId)))
        );
        assertThat(descClusters.clusters()).hasSize(1);
        assertThat(descClusters.clusters().get(0).stateAsString()).isEqualTo("ACTIVE");

        // 5. Describe Backups (auto-created on CreateHsm/CreateCluster)
        DescribeBackupsResponse descBackups = client.describeBackups(r -> r
                .filters(java.util.Map.of("clusterIds", List.of(clusterId)))
        );
        assertThat(descBackups.backups()).isNotEmpty();
        String backupId = descBackups.backups().get(0).backupId();

        // 6. Delete HSM
        DeleteHsmResponse delHsm = client.deleteHsm(r -> r
                .clusterId(clusterId)
                .hsmId(hsmId)
        );
        assertThat(delHsm.hsmId()).isEqualTo(hsmId);

        // 7. Delete Cluster
        DeleteClusterResponse delCluster = client.deleteCluster(r -> r
                .clusterId(clusterId)
        );
        assertThat(delCluster.cluster().stateAsString()).isEqualTo("DELETE_IN_PROGRESS");
        
        // 8. Delete Backup
        DeleteBackupResponse delBackup = client.deleteBackup(r -> r
                .backupId(backupId)
        );
        assertThat(delBackup.backup().backupStateAsString()).isEqualTo("PENDING_DELETION");
    }

    @Test
    public void testDeleteHsmSelectors() {
        CreateClusterResponse createClusterResponse = client.createCluster(r -> r
                .hsmType("hsm1.medium")
                .subnetIds("subnet-1", "subnet-2")
        );
        String clusterId = createClusterResponse.cluster().clusterId();
        String dummyCert = "-----BEGIN CERTIFICATE-----\nMIIC\n-----END CERTIFICATE-----\n";
        client.initializeCluster(r -> r.clusterId(clusterId).signedCert(dummyCert).trustAnchor(dummyCert));
        
        Hsm hsm = client.createHsm(r -> r.clusterId(clusterId).availabilityZone("us-east-1b")).hsm();
        
        // Test exactly one selector rule
        assertThatThrownBy(() -> client.deleteHsm(r -> r
                .clusterId(clusterId)
                .hsmId(hsm.hsmId())
                .eniId(hsm.eniId())
        )).hasMessageContaining("Exactly one of HsmId, EniId, or EniIp must be specified");
        
        // Test successful delete with EniId
        DeleteHsmResponse delResp = client.deleteHsm(r -> r
                .clusterId(clusterId)
                .eniId(hsm.eniId())
        );
        assertThat(delResp.hsmId()).isEqualTo(hsm.hsmId());
    }
}
