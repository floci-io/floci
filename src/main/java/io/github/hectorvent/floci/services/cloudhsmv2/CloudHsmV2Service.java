package io.github.hectorvent.floci.services.cloudhsmv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Certificates;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Cluster;
import io.github.hectorvent.floci.services.cloudhsmv2.model.ClusterState;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Hsm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
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
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import io.github.hectorvent.floci.services.cloudhsmv2.model.Backup;
import io.github.hectorvent.floci.services.cloudhsmv2.model.BackupRetentionPolicy;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import java.time.Instant;
import java.util.*;

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

    private final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final StorageBackend<String, Cluster> clusters;
    private final StorageBackend<String, Backup> backups;

    @Inject
    public CloudHsmV2Service(StorageFactory storageFactory) {
        this.clusters = storageFactory.create("cloudhsmv2", "cloudhsmv2-clusters.json",
                new TypeReference<Map<String, Cluster>>() {});
        this.backups = storageFactory.create("cloudhsmv2", "cloudhsmv2-backups.json",
                new TypeReference<Map<String, Backup>>() {});
    }

    CloudHsmV2Service(StorageBackend<String, Cluster> clusters, StorageBackend<String, Backup> backups) {
        this.clusters = clusters;
        this.backups = backups;
    }

    // ──────────────────────────── CreateCluster ────────────────────────────

    public Cluster createCluster(String hsmType, List<String> SubnetIds,
                                 String sourceBackupId, Map<String, String> tags,
                                 String mode, String networkType, BackupRetentionPolicy backupRetentionPolicy, String region) {
        if (SubnetIds == null || SubnetIds.isEmpty()) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "SubnetIds must contain at least one entry.", 400);
        }
        if (hsmType != null && !hsmType.matches("^hsm[1-9][a-z]?\\.medium$")) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "HsmType " + hsmType + " is not valid.", 400);
        }

        String clusterId = "cluster-" + generateShortId();

        Cluster cluster = new Cluster();
        cluster.setClusterId(clusterId);
        cluster.setState(ClusterState.UNINITIALIZED);
        cluster.setHsmType(hsmType != null ? hsmType : DEFAULT_HSM_TYPE);
        cluster.setVpcId("vpc-" + generateShortId());
        cluster.setSubnetIds(new ArrayList<>(SubnetIds));
        cluster.setSourceBackupId(sourceBackupId);
        cluster.setSecurityGroup("sg-" + generateShortId());
        cluster.setCreateTimestamp(Instant.now());
        cluster.setBackupPolicy(DEFAULT_BACKUP_POLICY);
        cluster.setTagList(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        cluster.setMode(mode != null ? mode : "FIPS");
        cluster.setNetworkType(networkType != null ? networkType : "IPV4");
        if (backupRetentionPolicy != null) {
            int val = Integer.parseInt(backupRetentionPolicy.getValue());
            if (val < 7 || val > 379) {
                throw new AwsException("CloudHsmInvalidRequestException", "BackupRetentionPolicy Value must be between 7 and 379.", 400);
            }
            cluster.setBackupRetentionPolicy(backupRetentionPolicy);
        } else {
            cluster.setBackupRetentionPolicy(new BackupRetentionPolicy("DAYS", "90"));
        }

        Certificates certs = new Certificates();
        certs.setClusterCsr(generateCsr(clusterId));

        try {
            KeyPair mfrKeyPair = generateKeyPair();
            X500Name mfrName = new X500Name("CN=HSM Manufacturer CA,O=AWS,C=US");
            certs.setManufacturerHardwareCertificate(generateCert(mfrName, mfrName, mfrKeyPair.getPublic(), mfrKeyPair.getPrivate()));

            KeyPair awsKeyPair = generateKeyPair();
            X500Name awsName = new X500Name("CN=AWS CloudHSM Hardware CA,O=AWS,C=US");
            certs.setAwsHardwareCertificate(generateCert(awsName, mfrName, awsKeyPair.getPublic(), mfrKeyPair.getPrivate()));

            KeyPair hsmKeyPair = generateKeyPair();
            X500Name hsmName = new X500Name("CN=HSM Instance " + clusterId + ",O=AWS,C=US");
            certs.setHsmCertificate(generateCert(hsmName, awsName, hsmKeyPair.getPublic(), awsKeyPair.getPrivate()));
        } catch (Exception e) {
            LOG.warnv("Failed to generate emulated hardware certs: {0}", e.getMessage());
        }

        cluster.setCertificates(certs);

        String storageKey = regionKey(region, clusterId);
        clusters.put(storageKey, cluster);

        createBackupInternal(clusterId, region);

        LOG.infov("Created CloudHSM v2 cluster {0} in region {1}", clusterId, region);
        return cluster;
    }

    // ──────────────────────────── DescribeClusters ────────────────────────────

    public Collection<Cluster> describeClusters(List<String> filterClusterIds,
                                                 List<String> filterStates, List<String> filterVpcIds, String region) {
        Collection<Cluster> all = clusters.scan(k -> k.startsWith(region + "::"));

        List<Cluster> filtered = new ArrayList<>();
        for (Cluster c : all) {
            boolean matchId = filterClusterIds == null || filterClusterIds.isEmpty()
                    || filterClusterIds.contains(c.getClusterId());
            boolean matchState = filterStates == null || filterStates.isEmpty()
                    || filterStates.contains(c.getState().wireValue());
            boolean matchVpc = filterVpcIds == null || filterVpcIds.isEmpty()
                    || filterVpcIds.contains(c.getVpcId());
            if (matchId && matchState && matchVpc) {
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

    public Hsm createHsm(String clusterId, String availabilityZone, String ipAddress, String region) {
        Cluster cluster = getCluster(clusterId, region);

        if (cluster.getState() != ClusterState.INITIALIZED
                && cluster.getState() != ClusterState.ACTIVE) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "Cannot create HSM in cluster " + clusterId + " with state " + cluster.getState().wireValue(), 400);
        }

        if (availabilityZone == null || availabilityZone.isBlank()) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "AvailabilityZone is required.", 400);
        }

        List<String> subnetIds = cluster.getSubnetIds();
        String regionPrefix = region != null ? region : "us-east-1";
        String subnetId = null;
        for (int i = 0; i < subnetIds.size(); i++) {
            if ((regionPrefix + (char) ('a' + i)).equals(availabilityZone)) {
                subnetId = subnetIds.get(i);
                break;
            }
        }
        if (subnetId == null) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "AvailabilityZone " + availabilityZone + " is not mapped to a subnet in this cluster.", 400);
        }

        Hsm hsm = new Hsm();
        hsm.setHsmId("hsm-" + generateShortId());
        hsm.setAvailabilityZone(availabilityZone);
        hsm.setClusterId(clusterId);
        hsm.setSubnetId(subnetId);
        hsm.setEniId("eni-" + generateShortId());
        hsm.setEniIp("10.0." + (SECURE_RANDOM.nextInt(254) + 1) + "." + (SECURE_RANDOM.nextInt(254) + 1));
        hsm.setIpAddress(ipAddress != null ? ipAddress : hsm.getEniIp());
        hsm.setState("ACTIVE");
        hsm.setCreatedAt(Instant.now());

        cluster.getHsms().add(hsm);

        if (cluster.isReadyForActive()) {
            cluster.setState(ClusterState.ACTIVE);
            cluster.setStateMessage("Cluster is active");
        }

        clusters.put(regionKey(region, clusterId), cluster);

        createBackupInternal(clusterId, region);

        LOG.infov("Created HSM {0} in cluster {1}", hsm.getHsmId(), clusterId);
        return hsm;
    }

    // ──────────────────────────── DeleteHsm ────────────────────────────

    public Hsm deleteHsm(String clusterId, String hsmId, String eniId, String eniIp, String region) {
        int count = 0;
        if (hsmId != null && !hsmId.isEmpty()) count++;
        if (eniId != null && !eniId.isEmpty()) count++;
        if (eniIp != null && !eniIp.isEmpty()) count++;

        if (count != 1) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "Exactly one of HsmId, EniId, or EniIp must be specified", 400);
        }

        Cluster cluster = getCluster(clusterId, region);

        Hsm target = null;
        for (Hsm h : cluster.getHsms()) {
            if ((hsmId != null && hsmId.equals(h.getHsmId())) ||
                (eniId != null && eniId.equals(h.getEniId())) ||
                (eniIp != null && eniIp.equals(h.getEniIp()))) {
                target = h;
                break;
            }
        }
        if (target == null) {
            String selector = hsmId != null ? hsmId : (eniId != null ? eniId : eniIp);
            throw new AwsException("CloudHsmResourceNotFoundException",
                    "HSM " + selector + " not found in cluster " + clusterId, 400);
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
        if (tags != null && !tags.isEmpty()) {
            cluster.getTagList().putAll(tags);
        }
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
                        "Cluster " + clusterId + " not found.", 400));
    }

    private String regionKey(String region, String clusterId) {
        return region + "::" + clusterId;
    }

    private String generateShortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        keyGen.initialize(2048, SECURE_RANDOM);
        return keyGen.generateKeyPair();
    }

    private String generateCert(X500Name subject, X500Name issuer, PublicKey pubKey, PrivateKey signerKey) throws Exception {
        BigInteger serial = new BigInteger(128, SECURE_RANDOM);
        Instant now = Instant.now();
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer, serial, Date.from(now), Date.from(now.plusSeconds(365L * 24 * 3600)), subject, pubKey);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(signerKey);
        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder);

        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
            pemWriter.writeObject(cert);
        }
        return sw.toString();
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

    // ──────────────────────────── Backups ────────────────────────────

    private void createBackupInternal(String clusterId, String region) {
        Cluster cluster = getCluster(clusterId, region);
        Backup backup = new Backup();
        backup.setBackupId("backup-" + generateShortId());
        backup.setBackupState("READY");
        backup.setClusterId(clusterId);
        backup.setCreateTimestamp(Instant.now());
        backup.setNeverExpires("False");
        backup.setMode(cluster.getMode());
        backups.put(regionKey(region, backup.getBackupId()), backup);
    }

    public Collection<Backup> describeBackups(List<String> filterBackupIds, List<String> filterClusterIds,
                                              List<String> filterStates, String region) {
        Collection<Backup> all = backups.scan(k -> k.startsWith(region + "::"));
        List<Backup> filtered = new ArrayList<>();
        for (Backup b : all) {
            boolean matchId = filterBackupIds == null || filterBackupIds.isEmpty() || filterBackupIds.contains(b.getBackupId());
            boolean matchCluster = filterClusterIds == null || filterClusterIds.isEmpty() || filterClusterIds.contains(b.getClusterId());
            boolean matchState = filterStates == null || filterStates.isEmpty() || filterStates.contains(b.getBackupState());
            if (matchId && matchCluster && matchState) {
                filtered.add(b);
            }
        }
        return filtered;
    }

    public Backup deleteBackup(String backupId, String region) {
        Backup backup = getBackup(backupId, region);
        backup.setBackupState("PENDING_DELETION");
        backup.setDeleteTimestamp(Instant.now());
        backups.put(regionKey(region, backupId), backup);
        return backup;
    }

    public Backup restoreBackup(String backupId, String region) {
        Backup backup = getBackup(backupId, region);
        if (!"PENDING_DELETION".equals(backup.getBackupState())) {
            throw new AwsException("CloudHsmInvalidRequestException", "Backup must be in PENDING_DELETION state", 400);
        }
        backup.setBackupState("READY");
        backup.setDeleteTimestamp(null);
        backups.put(regionKey(region, backupId), backup);
        return backup;
    }

    public Backup modifyBackupAttributes(String backupId, String neverExpires, String region) {
        Backup backup = getBackup(backupId, region);
        if (neverExpires != null) {
            backup.setNeverExpires(neverExpires);
        }
        backups.put(regionKey(region, backupId), backup);
        return backup;
    }

    public Backup copyBackupToRegion(String destinationRegion, String backupId, String sourceRegion) {
        // Source region emulation: we'll just clone the backup locally.
        Backup source = getBackup(backupId, sourceRegion != null ? sourceRegion : "us-east-1");
        Backup copy = new Backup();
        copy.setBackupId("backup-" + generateShortId());
        copy.setBackupState("READY");
        copy.setClusterId(source.getClusterId());
        copy.setCreateTimestamp(source.getCreateTimestamp());
        copy.setCopyTimestamp(Instant.now());
        copy.setSourceRegion(sourceRegion != null ? sourceRegion : "us-east-1");
        copy.setSourceBackup(backupId);
        copy.setSourceCluster(source.getClusterId());
        copy.setMode(source.getMode());
        copy.setNeverExpires(source.getNeverExpires());
        backups.put(regionKey(destinationRegion, copy.getBackupId()), copy);
        return copy;
    }

    // ──────────────────────────── Resource Policies ────────────────────────────

    public void putResourcePolicy(String resourceArn, String policy, String region) {
        String backupId = extractBackupId(resourceArn);
        Backup backup = getBackup(backupId, region);
        if (!"READY".equals(backup.getBackupState())) {
            throw new AwsException("CloudHsmInvalidRequestException", "Backup must be READY to apply a policy", 400);
        }
        backup.setResourcePolicy(policy);
        backups.put(regionKey(region, backupId), backup);
    }

    public String getResourcePolicy(String resourceArn, String region) {
        String backupId = extractBackupId(resourceArn);
        Backup backup = getBackup(backupId, region);
        return backup.getResourcePolicy();
    }

    public void deleteResourcePolicy(String resourceArn, String region) {
        String backupId = extractBackupId(resourceArn);
        Backup backup = getBackup(backupId, region);
        backup.setResourcePolicy(null);
        backups.put(regionKey(region, backupId), backup);
    }

    private String extractBackupId(String arn) {
        if (arn == null || !arn.contains("backup/")) {
            throw new AwsException("CloudHsmInvalidRequestException", "Invalid ResourceArn format", 400);
        }
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    // ──────────────────────────── ModifyCluster ────────────────────────────

    public Cluster modifyCluster(String clusterId, String hsmType, BackupRetentionPolicy backupRetentionPolicy, String region) {
        Cluster cluster = getCluster(clusterId, region);
        if (hsmType != null && !hsmType.matches("^hsm[1-9][a-z]?\\.medium$")) {
            throw new AwsException("CloudHsmInvalidRequestException", "HsmType " + hsmType + " is not valid.", 400);
        }
        if (hsmType != null) {
            cluster.setHsmType(hsmType);
        }
        if (backupRetentionPolicy != null) {
            int val = Integer.parseInt(backupRetentionPolicy.getValue());
            if (val < 7 || val > 379) {
                throw new AwsException("CloudHsmInvalidRequestException", "BackupRetentionPolicy Value must be between 7 and 379.", 400);
            }
            cluster.setBackupRetentionPolicy(backupRetentionPolicy);
        }
        clusters.put(regionKey(region, clusterId), cluster);
        return cluster;
    }

    private Backup getBackup(String backupId, String region) {
        if (backupId == null || backupId.isBlank()) {
            throw new AwsException("CloudHsmInvalidRequestException", "BackupId is required.", 400);
        }
        return backups.get(regionKey(region, backupId)).orElseThrow(() ->
                new AwsException("CloudHsmResourceNotFoundException", "Backup " + backupId + " not found.", 400));
    }

}