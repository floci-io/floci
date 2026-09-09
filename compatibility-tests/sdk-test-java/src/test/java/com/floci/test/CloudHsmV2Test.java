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
        DescribeClustersResponse initDescribe = client.describeClusters(r -> r
                .filters(java.util.Map.of("clusterIds", List.of(clusterId))));
        String csr = initDescribe.clusters().get(0).certificates().clusterCsr();
        String[] certs;
        try { certs = generateCerts(csr); } catch (Exception e) { throw new RuntimeException(e); }
        String signedCert = certs[0];
        String trustAnchor = certs[1];

        InitializeClusterResponse initResp = client.initializeCluster(r -> r
                .clusterId(clusterId)
                .signedCert(signedCert)
                .trustAnchor(trustAnchor)
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
        DescribeClustersResponse initDescribe = client.describeClusters(r -> r
                .filters(java.util.Map.of("clusterIds", List.of(clusterId))));
        String csr = initDescribe.clusters().get(0).certificates().clusterCsr();
        String[] certs;
        try { certs = generateCerts(csr); } catch (Exception e) { throw new RuntimeException(e); }
        String signedCert = certs[0];
        String trustAnchor = certs[1];

        client.initializeCluster(r -> r.clusterId(clusterId).signedCert(signedCert).trustAnchor(trustAnchor));

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

    private String[] generateCerts(String csrPem) throws Exception {
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA", "BC");
        keyGen.initialize(2048, new java.security.SecureRandom());
        java.security.KeyPair caKeyPair = keyGen.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name caName = new org.bouncycastle.asn1.x500.X500Name("CN=Floci Test CA");
        long now = System.currentTimeMillis();
        java.util.Date startDate = new java.util.Date(now);
        java.util.Date endDate = new java.util.Date(now + 365L * 24 * 3600 * 1000);
        java.math.BigInteger serial = java.math.BigInteger.valueOf(now);
        org.bouncycastle.cert.X509v3CertificateBuilder caBuilder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                caName, serial, startDate, endDate, caName, caKeyPair.getPublic());
        org.bouncycastle.operator.ContentSigner caSigner = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider("BC").build(caKeyPair.getPrivate());
        org.bouncycastle.cert.X509CertificateHolder caHolder = caBuilder.build(caSigner);
        java.io.StringWriter caSw = new java.io.StringWriter();
        try (org.bouncycastle.openssl.jcajce.JcaPEMWriter pw = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(caSw)) {
            pw.writeObject(caHolder);
        }
        String trustAnchor = caSw.toString();
        org.bouncycastle.pkcs.PKCS10CertificationRequest csr;
        try (org.bouncycastle.openssl.PEMParser parser = new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(csrPem))) {
            csr = (org.bouncycastle.pkcs.PKCS10CertificationRequest) parser.readObject();
        }
        java.security.PublicKey csrPublicKey = new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().setProvider("BC").getPublicKey(csr.getSubjectPublicKeyInfo());
        org.bouncycastle.cert.X509v3CertificateBuilder certBuilder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                caName, serial.add(java.math.BigInteger.ONE), startDate, endDate, csr.getSubject(), csrPublicKey);
        org.bouncycastle.cert.X509CertificateHolder certHolder = certBuilder.build(caSigner);
        java.io.StringWriter certSw = new java.io.StringWriter();
        try (org.bouncycastle.openssl.jcajce.JcaPEMWriter pw = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(certSw)) {
            pw.writeObject(certHolder);
        }
        String signedCert = certSw.toString();
        return new String[]{signedCert, trustAnchor};
    }
}
