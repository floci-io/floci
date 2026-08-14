package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudhsmv2.CloudHsmV2Client;
import software.amazon.awssdk.services.cloudhsmv2.model.*;

import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;

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
        String dummyCert = generateDummyCertificate();
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
        String dummyCert = generateDummyCertificate();
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

    private String generateDummyCertificate() {
        try {
            KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA");
            keyPairGen.initialize(2048);
            KeyPair keyPair = keyPairGen.generateKeyPair();

            X500Name subject = new X500Name("CN=Dummy");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date notBefore = new Date(System.currentTimeMillis() - 86400000L);
            Date notAfter = new Date(System.currentTimeMillis() + 86400000L * 365);

            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    subject, serial, notBefore, notAfter, subject, keyPair.getPublic());

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .build(keyPair.getPrivate());

            X509Certificate cert = new JcaX509CertificateConverter()
                    .getCertificate(certBuilder.build(signer));

            StringWriter sw = new StringWriter();
            try (PemWriter pw = new PemWriter(sw)) {
                pw.writeObject(new PemObject("CERTIFICATE", cert.getEncoded()));
            }
            return sw.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate dummy certificate", e);
        }
    }
}
