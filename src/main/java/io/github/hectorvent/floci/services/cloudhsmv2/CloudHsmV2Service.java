package io.github.hectorvent.floci.services.cloudhsmv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Certificates;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Cluster;
import io.github.hectorvent.floci.services.cloudhsmv2.model.ClusterState;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Hsm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.openssl.PEMParser;
import org.jboss.logging.Logger;

import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CloudHSM v2 service implementation for the local emulator.
 *
 * <p>Provides cluster initialization and lifecycle management operations
 * compatible with the AWS CloudHSM v2 API. Clusters follow a strict lifecycle:
 * {@code CREATE_IN_PROGRESS → UNINITIALIZED → INITIALIZED → ACTIVE}.
 *
 * @see <a href="https://docs.aws.amazon.com/cloudhsm/latest/APIReference/Welcome.html">AWS CloudHSM v2 API Reference</a>
 */
@ApplicationScoped
public class CloudHsmV2Service {

    private static final Logger LOG = Logger.getLogger(CloudHsmV2Service.class);
    private static final String DEFAULT_HSM_TYPE = "hsm1.medium";
    private static final String DEFAULT_BACKUP_POLICY = "DEFAULT";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StorageBackend<String, Cluster> clusters;
    private final RegionResolver regionResolver;

    @Inject
    public CloudHsmV2Service(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.regionResolver = regionResolver;
        this.clusters = storageFactory.create("cloudhsmv2", "cloudhsmv2-clusters.json",
                new TypeReference<Map<String, Cluster>>() {});
    }

    CloudHsmV2Service(StorageBackend<String, Cluster> clusters, RegionResolver regionResolver) {
        this.clusters = clusters;
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── CreateCluster ────────────────────────────

    public Cluster createCluster(String hsmType, Map<String, String> subnetMapping,
                                 String sourceBackupId, Map<String, String> tags, String region) {
        if (subnetMapping == null || subnetMapping.isEmpty()) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "SubnetMapping must contain at least one entry.", 400);
        }

        String clusterId = "cluster-" + generateShortId();

        Cluster cluster = new Cluster();
        cluster.setClusterId(clusterId);
        cluster.setState(ClusterState.UNINITIALIZED);
        cluster.setHsmType(hsmType != null ? hsmType : DEFAULT_HSM_TYPE);
        cluster.setVpcId("vpc-" + generateShortId());
        cluster.setSubnetMapping(new LinkedHashMap<>(subnetMapping));
        cluster.setSourceBackupId(sourceBackupId);
        cluster.setSecurityGroup("sg-" + generateShortId());
        cluster.setCreateTimestamp(Instant.now());
        cluster.setBackupPolicy(DEFAULT_BACKUP_POLICY);
        cluster.setTagList(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());

        // Generate CSR for the cluster
        Certificates certs = new Certificates();
        certs.setClusterCsr(generateCsr(clusterId));
        // Emulate AWS hardware certs
        certs.setAwsHardwareCertificate(generateEmulatedHardwareCert("AWS CloudHSM Hardware CA"));
        certs.setManufacturerHardwareCertificate(generateEmulatedHardwareCert("HSM Manufacturer CA"));
        certs.setHsmCertificate(generateEmulatedHardwareCert("HSM Instance " + clusterId));
        cluster.setCertificates(certs);

        String storageKey = regionKey(region, clusterId);
        clusters.put(storageKey, cluster);

        LOG.infov("Created CloudHSM v2 cluster {0} in region {1}", clusterId, region);
        return cluster;
    }

    // ──────────────────────────── DescribeClusters ────────────────────────────

    public Collection<Cluster> describeClusters(List<String> filterClusterIds,
                                                 List<String> filterStates, String region) {
        Collection<Cluster> all = clusters.scan(k -> k.startsWith(region + "::"));

        if ((filterClusterIds == null || filterClusterIds.isEmpty())
                && (filterStates == null || filterStates.isEmpty())) {
            return all;
        }

        List<Cluster> filtered = new ArrayList<>();
        for (Cluster c : all) {
            boolean matchId = filterClusterIds == null || filterClusterIds.isEmpty()
                    || filterClusterIds.contains(c.getClusterId());
            boolean matchState = filterStates == null || filterStates.isEmpty()
                    || filterStates.contains(c.getState().wireValue());
            if (matchId && matchState) {
                filtered.add(c);
            }
        }
        return filtered;
    }

    // ──────────────────────────── DeleteCluster ────────────────────────────

    public Cluster deleteCluster(String clusterId, String region) {
        Cluster cluster = getCluster(clusterId, region);

        if (!cluster.getHsms().isEmpty()) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "Cluster " + clusterId + " has active HSMs. Delete all HSMs before deleting the cluster.", 400);
        }

        cluster.setState(ClusterState.DELETE_IN_PROGRESS);
        clusters.delete(regionKey(region, clusterId));

        LOG.infov("Deleted CloudHSM v2 cluster {0}", clusterId);
        return cluster;
    }

    // ──────────────────────────── InitializeCluster ────────────────────────────

    public Cluster initializeCluster(String clusterId, String signedCert, String trustAnchor, String region) {
        Cluster cluster = getCluster(clusterId, region);

        if (cluster.getState() != ClusterState.UNINITIALIZED) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "Cluster " + clusterId + " is in state " + cluster.getState().wireValue()
                            + ". InitializeCluster requires UNINITIALIZED state.", 400);
        }

        validatePemCertificate(signedCert, "SignedCert");
        validatePemCertificate(trustAnchor, "TrustAnchor");

        // Parse and validate the certificates
        parsePemCertificate(signedCert, "SignedCert");
        parsePemCertificate(trustAnchor, "TrustAnchor");

        // Persist the cluster certificate
        Certificates certs = cluster.getCertificates();
        if (certs == null) {
            certs = new Certificates();
        }
        certs.setClusterCertificate(signedCert);
        cluster.setCertificates(certs);

        cluster.setState(ClusterState.INITIALIZED);
        cluster.setStateMessage("Cluster initialized successfully");

        // Auto-transition to ACTIVE if HSMs are present
        if (cluster.isReadyForActive()) {
            cluster.setState(ClusterState.ACTIVE);
            cluster.setStateMessage("Cluster is active");
        }

        clusters.put(regionKey(region, clusterId), cluster);
        LOG.infov("Initialized CloudHSM v2 cluster {0}, state={1}", clusterId, cluster.getState());
        return cluster;
    }

    // ──────────────────────────── CreateHsm ────────────────────────────

    public Hsm createHsm(String clusterId, String availabilityZone, String region) {
        Cluster cluster = getCluster(clusterId, region);

        if (cluster.getState() != ClusterState.INITIALIZED
                && cluster.getState() != ClusterState.ACTIVE
                && cluster.getState() != ClusterState.UNINITIALIZED) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "Cannot create HSM in cluster " + clusterId + " with state " + cluster.getState().wireValue(), 400);
        }

        if (availabilityZone == null || availabilityZone.isBlank()) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "AvailabilityZone is required.", 400);
        }

        Hsm hsm = new Hsm();
        hsm.setHsmId("hsm-" + generateShortId());
        hsm.setAvailabilityZone(availabilityZone);
        hsm.setClusterId(clusterId);
        hsm.setSubnetId(cluster.getSubnetMapping().getOrDefault(availabilityZone,
                "subnet-" + generateShortId()));
        hsm.setEniId("eni-" + generateShortId());
        hsm.setEniIp("10.0." + (SECURE_RANDOM.nextInt(254) + 1) + "." + (SECURE_RANDOM.nextInt(254) + 1));
        hsm.setState("ACTIVE");
        hsm.setCreatedAt(Instant.now());

        cluster.getHsms().add(hsm);

        // Auto-transition to ACTIVE if initialized and now has HSMs
        if (cluster.isReadyForActive()) {
            cluster.setState(ClusterState.ACTIVE);
            cluster.setStateMessage("Cluster is active");
        }

        clusters.put(regionKey(region, clusterId), cluster);
        LOG.infov("Created HSM {0} in cluster {1}", hsm.getHsmId(), clusterId);
        return hsm;
    }

    // ──────────────────────────── DeleteHsm ────────────────────────────

    public Hsm deleteHsm(String clusterId, String hsmId, String region) {
        Cluster cluster = getCluster(clusterId, region);

        Hsm target = null;
        for (Hsm h : cluster.getHsms()) {
            if (h.getHsmId().equals(hsmId)) {
                target = h;
                break;
            }
        }
        if (target == null) {
            throw new AwsException("CloudHsmResourceNotFoundException",
                    "HSM " + hsmId + " not found in cluster " + clusterId, 404);
        }

        cluster.getHsms().remove(target);

        // If cluster was ACTIVE but now has no HSMs, revert to INITIALIZED
        if (cluster.getState() == ClusterState.ACTIVE && cluster.getHsms().isEmpty()) {
            cluster.setState(ClusterState.INITIALIZED);
            cluster.setStateMessage("No active HSMs");
        }

        clusters.put(regionKey(region, clusterId), cluster);
        LOG.infov("Deleted HSM {0} from cluster {1}", hsmId, clusterId);
        return target;
    }

    // ──────────────────────────── TagResource ────────────────────────────

    public void tagResource(String resourceId, Map<String, String> tags, String region) {
        Cluster cluster = getCluster(resourceId, region);
        cluster.getTagList().putAll(tags);
        clusters.put(regionKey(region, resourceId), cluster);
    }

    public void untagResource(String resourceId, List<String> tagKeys, String region) {
        Cluster cluster = getCluster(resourceId, region);
        tagKeys.forEach(cluster.getTagList()::remove);
        clusters.put(regionKey(region, resourceId), cluster);
    }

    public Map<String, String> listTags(String resourceId, String region) {
        Cluster cluster = getCluster(resourceId, region);
        return new LinkedHashMap<>(cluster.getTagList());
    }

    // ──────────────────────────── Helpers ────────────────────────────

    Cluster getCluster(String clusterId, String region) {
        if (clusterId == null || clusterId.isBlank()) {
            throw new AwsException("CloudHsmInvalidRequestException", "ClusterId is required.", 400);
        }
        return clusters.get(regionKey(region, clusterId)).orElseThrow(() ->
                new AwsException("CloudHsmResourceNotFoundException",
                        "Cluster " + clusterId + " not found.", 404));
    }

    private String regionKey(String region, String clusterId) {
        return region + "::" + clusterId;
    }

    private String generateShortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 17);
    }

    private String generateCsr(String clusterId) {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
            keyGen.initialize(2048, SECURE_RANDOM);
            KeyPair keyPair = keyGen.generateKeyPair();

            X500Name subject = new X500Name("CN=" + clusterId + ",O=AWS CloudHSM,C=US");

            PKCS10CertificationRequestBuilder csrBuilder =
                    new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(keyPair.getPrivate());

            PKCS10CertificationRequest csr = csrBuilder.build(signer);

            StringWriter sw = new StringWriter();
            try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
                pemWriter.writeObject(csr);
            }
            return sw.toString();
        } catch (Exception e) {
            LOG.warnv("Failed to generate CSR for cluster {0}: {1}", clusterId, e.getMessage());
            return "-----BEGIN CERTIFICATE REQUEST-----\nemulated-csr-" + clusterId + "\n-----END CERTIFICATE REQUEST-----\n";
        }
    }

    private String generateEmulatedHardwareCert(String cn) {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
            keyGen.initialize(2048, SECURE_RANDOM);
            KeyPair keyPair = keyGen.generateKeyPair();

            X500Name issuer = new X500Name("CN=" + cn + ",O=AWS,C=US");
            java.math.BigInteger serial = new java.math.BigInteger(128, SECURE_RANDOM);
            Instant now = Instant.now();

            org.bouncycastle.cert.X509v3CertificateBuilder certBuilder =
                    new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                            issuer, serial,
                            java.util.Date.from(now),
                            java.util.Date.from(now.plusSeconds(365L * 24 * 3600)),
                            issuer, keyPair.getPublic());

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(keyPair.getPrivate());

            X509CertificateHolder holder = certBuilder.build(signer);
            java.security.cert.X509Certificate cert =
                    new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                            .getCertificate(holder);

            StringWriter sw = new StringWriter();
            try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
                pemWriter.writeObject(cert);
            }
            return sw.toString();
        } catch (Exception e) {
            LOG.warnv("Failed to generate emulated hardware cert for {0}: {1}", cn, e.getMessage());
            return "-----BEGIN CERTIFICATE-----\nemulated-" + cn + "\n-----END CERTIFICATE-----\n";
        }
    }

    private void validatePemCertificate(String pem, String fieldName) {
        if (pem == null || pem.isBlank()) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    fieldName + " is required.", 400);
        }
        if (!pem.contains("-----BEGIN CERTIFICATE-----")) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    fieldName + " must be a valid PEM-encoded certificate.", 400);
        }
        if (!pem.contains("-----END CERTIFICATE-----")) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    fieldName + " is malformed: missing PEM end marker.", 400);
        }
    }

    private void parsePemCertificate(String pem, String fieldName) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (obj == null) {
                throw new AwsException("CloudHsmInvalidRequestException",
                        fieldName + " could not be parsed as a valid certificate.", 400);
            }
            if (!(obj instanceof X509CertificateHolder)) {
                throw new AwsException("CloudHsmInvalidRequestException",
                        fieldName + " is not a valid X.509 certificate.", 400);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    fieldName + " is malformed: " + e.getMessage(), 400);
        }
    }
}
