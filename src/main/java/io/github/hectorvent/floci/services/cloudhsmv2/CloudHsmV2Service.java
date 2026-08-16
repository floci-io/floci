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

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;

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
    private final Ec2Service ec2Service;

    @Inject
    public CloudHsmV2Service(StorageFactory storageFactory, Ec2Service ec2Service) {
        this.clusters = storageFactory.create("cloudhsmv2", "cloudhsmv2-clusters.json",
                new TypeReference<Map<String, Cluster>>() {});
        this.backups = storageFactory.create("cloudhsmv2", "cloudhsmv2-backups.json",
                new TypeReference<Map<String, Backup>>() {});
        this.ec2Service = ec2Service;
    }

    CloudHsmV2Service(StorageBackend<String, Cluster> clusters, StorageBackend<String, Backup> backups, Ec2Service ec2Service) {
        this.clusters = clusters;
        this.backups = backups;
        this.ec2Service = ec2Service;
    }

    // ──────────────────────────── CreateCluster ────────────────────────────

    public Cluster createCluster(String hsmType, List<String> SubnetIds,
                                 String sourceBackupId, Map<String, String> tags,
                                 String mode, String networkType, BackupRetentionPolicy backupRetentionPolicy, String region) {
        if (SubnetIds == null || SubnetIds.isEmpty()) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "SubnetIds must contain at least one entry.", 400);
        }
        if (hsmType == null) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "HsmType is required.", 400);
        }
        if (!hsmType.matches("^((p|)hsm[0-9][a-z.]*\\.[a-zA-Z]+)$")) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "HsmType " + hsmType + " is not valid.", 400);
        }
        if (mode != null && !mode.equals("FIPS") && !mode.equals("NON_FIPS")) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "Mode must be FIPS or NON_FIPS.", 400);
        }
        if (networkType != null && !networkType.equals("IPV4") && !networkType.equals("DUALSTACK")) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "NetworkType must be IPV4 or DUALSTACK.", 400);
        }

        Backup sourceBackup = null;
        if (sourceBackupId != null && !sourceBackupId.isEmpty()) {
            sourceBackup = getBackup(extractId(sourceBackupId), region);
            if (mode == null && sourceBackup.getMode() != null) {
                mode = sourceBackup.getMode();
            }
        }

        String clusterId = "cluster-" + generateShortId();

        Cluster cluster = new Cluster();
        cluster.setClusterId(clusterId);
        cluster.setState(ClusterState.UNINITIALIZED);
        cluster.setHsmType(hsmType);
        cluster.setVpcId("vpc-" + generateShortId());
        cluster.setSubnetIds(new ArrayList<>(SubnetIds));
        Map<String, String> subnetMapping = new LinkedHashMap<>();
        if (ec2Service != null) {
            List<Subnet> subnets = ec2Service.describeSubnets(region, SubnetIds, Collections.emptyMap());
            for (int i = 0; i < SubnetIds.size(); i++) {
                String subId = SubnetIds.get(i);
                final int index = i;
                String az = subnets.stream()
                        .filter(s -> s.getSubnetId().equals(subId))
                        .map(Subnet::getAvailabilityZone)
                        .findFirst()
                        .orElseGet(() -> region + (char) ('a' + index));
                subnetMapping.put(az, subId);
            }
        } else {
            for (int i = 0; i < SubnetIds.size(); i++) {
                subnetMapping.put(region + (char) ('a' + i), SubnetIds.get(i));
            }
        }
        cluster.setSubnetMapping(subnetMapping);
        cluster.setSourceBackupId(sourceBackupId);
        cluster.setSecurityGroup("sg-" + generateShortId());
        cluster.setCreateTimestamp(Instant.now());
        cluster.setBackupPolicy(DEFAULT_BACKUP_POLICY);
        cluster.setTagList(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        cluster.setMode(mode != null ? mode : "FIPS");
        cluster.setNetworkType(networkType != null ? networkType : "IPV4");
        if (backupRetentionPolicy != null) {
            String brpValue = backupRetentionPolicy.getValue();
            if (brpValue == null) {
                throw new AwsException("CloudHsmInvalidRequestException", "BackupRetentionPolicy Value is required.", 400);
            }
            int val;
            try {
                val = Integer.parseInt(brpValue);
            } catch (NumberFormatException e) {
                throw new AwsException("CloudHsmInvalidRequestException", "BackupRetentionPolicy Value must be an integer.", 400);
            }
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
        X509CertificateHolder signedCertHolder = parsePemCertificate(signedCert, "SignedCert");
        X509CertificateHolder trustAnchorHolder = parsePemCertificate(trustAnchor, "TrustAnchor");

        // Verify TrustAnchor is self-signed and SignedCert is issued by TrustAnchor
        try {
            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
            java.security.cert.X509Certificate trustAnchorCert = converter.getCertificate(trustAnchorHolder);
            java.security.cert.X509Certificate signedCertObj = converter.getCertificate(signedCertHolder);

            if (!trustAnchorHolder.getIssuer().equals(trustAnchorHolder.getSubject())) {
                throw new AwsException("CloudHsmInvalidRequestException",
                        "TrustAnchor must be a self-signed certificate.", 400);
            }
            trustAnchorCert.verify(trustAnchorCert.getPublicKey());

            if (!signedCertHolder.getIssuer().equals(trustAnchorHolder.getSubject())) {
                throw new AwsException("CloudHsmInvalidRequestException",
                        "SignedCert must be issued by the provided TrustAnchor.", 400);
            }
            signedCertObj.verify(trustAnchorCert.getPublicKey());
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "Certificate signature verification failed: " + e.getMessage(), 400);
        }

        // Verify SignedCert matches the cluster CSR
        String clusterCsr = cluster.getCertificates().getClusterCsr();
        PKCS10CertificationRequest csrHolder = parsePemCsr(clusterCsr);
        if (!signedCertHolder.getSubjectPublicKeyInfo().equals(csrHolder.getSubjectPublicKeyInfo())) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    "SignedCert public key does not match the cluster CSR public key.", 400);
        }

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

        String subnetId = cluster.getSubnetMapping().get(availabilityZone);
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
        hsm.setHsmType(cluster.getHsmType());

        if (ipAddress != null) {
            if (ipAddress.contains(":")) {
                hsm.setEniIpV6(ipAddress);
                hsm.setEniIp("10.0." + (SECURE_RANDOM.nextInt(254) + 1) + "." + (SECURE_RANDOM.nextInt(254) + 1));
            } else {
                hsm.setEniIp(ipAddress);
                if ("DUALSTACK".equals(cluster.getNetworkType())) {
                    hsm.setEniIpV6("2001:db8::" + Integer.toHexString(SECURE_RANDOM.nextInt(65535)));
                }
            }
            hsm.setIpAddress(ipAddress);
        } else {
            hsm.setEniIp("10.0." + (SECURE_RANDOM.nextInt(254) + 1) + "." + (SECURE_RANDOM.nextInt(254) + 1));
            if ("DUALSTACK".equals(cluster.getNetworkType())) {
                hsm.setEniIpV6("2001:db8::" + Integer.toHexString(SECURE_RANDOM.nextInt(65535)));
            }
            hsm.setIpAddress(hsm.getEniIp());
        }

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
        if (hsmId != null && !hsmId.isEmpty()) {
            count++;
        }
        if (eniId != null && !eniId.isEmpty()) {
            count++;
        }
        if (eniIp != null && !eniIp.isEmpty()) {
            count++;
        }

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
        LOG.infov("Deleted HSM {0} from cluster {1}", target.getHsmId(), clusterId);
        return target;
    }

    // ──────────────────────────── TagResource ────────────────────────────

    public void tagResource(String resourceId, Map<String, String> tags, String region) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new AwsException("CloudHsmInvalidRequestException", "ResourceId is required.", 400);
        }
        if (tags == null || tags.isEmpty() || tags.size() > 50) {
            throw new AwsException("CloudHsmInvalidRequestException", "TagList must have length between 1 and 50", 400);
        }
        if (resourceId.startsWith("backup-")) {
            Backup backup = getBackup(resourceId, region);
            if (tags != null && !tags.isEmpty()) {
                backup.getTagList().putAll(tags);
            }
            backups.put(regionKey(region, resourceId), backup);
        } else {
            Cluster cluster = getCluster(resourceId, region);
            if (tags != null && !tags.isEmpty()) {
                cluster.getTagList().putAll(tags);
            }
            clusters.put(regionKey(region, resourceId), cluster);
        }
    }

    public void untagResource(String resourceId, List<String> tagKeys, String region) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new AwsException("CloudHsmInvalidRequestException", "ResourceId is required.", 400);
        }
        if (tagKeys == null || tagKeys.isEmpty() || tagKeys.size() > 50) {
            throw new AwsException("CloudHsmInvalidRequestException", "TagKeyList must have length between 1 and 50", 400);
        }
        if (resourceId.startsWith("backup-")) {
            Backup backup = getBackup(resourceId, region);
            tagKeys.forEach(backup.getTagList()::remove);
            backups.put(regionKey(region, resourceId), backup);
        } else {
            Cluster cluster = getCluster(resourceId, region);
            tagKeys.forEach(cluster.getTagList()::remove);
            clusters.put(regionKey(region, resourceId), cluster);
        }
    }

    public Map<String, String> listTags(String resourceId, String region) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new AwsException("CloudHsmInvalidRequestException", "ResourceId is required.", 400);
        }
        if (resourceId.startsWith("backup-")) {
            return new LinkedHashMap<>(getBackup(resourceId, region).getTagList());
        } else {
            return new LinkedHashMap<>(getCluster(resourceId, region).getTagList());
        }
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
        String alphabet = "234567abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int length = 11 + SECURE_RANDOM.nextInt(6);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(SECURE_RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private String generateCsr(String clusterId) {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, SECURE_RANDOM);
            KeyPair keyPair = keyGen.generateKeyPair();

            X500Name subject = new X500Name("CN=" + clusterId + ",O=AWS CloudHSM,C=US");

            PKCS10CertificationRequestBuilder csrBuilder =
                    new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
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
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, SECURE_RANDOM);
        return keyGen.generateKeyPair();
    }

    private String generateCert(X500Name subject, X500Name issuer, PublicKey pubKey, PrivateKey signerKey) throws Exception {
        BigInteger serial = new BigInteger(128, SECURE_RANDOM);
        Instant now = Instant.now();
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer, serial, Date.from(now), Date.from(now.plusSeconds(365L * 24 * 3600)), subject, pubKey);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .build(signerKey);
        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(holder);

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

    private X509CertificateHolder parsePemCertificate(String pem, String fieldName) {
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
            return (X509CertificateHolder) obj;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("CloudHsmInvalidRequestException",
                    fieldName + " is malformed: " + e.getMessage(), 400);
        }
    }

    private PKCS10CertificationRequest parsePemCsr(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (obj instanceof PKCS10CertificationRequest) {
                return (PKCS10CertificationRequest) obj;
            }
            throw new RuntimeException("Not a valid CSR");
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse CSR", e);
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
        backup.setHsmType(cluster.getHsmType());
        backups.put(regionKey(region, backup.getBackupId()), backup);
    }

    public Collection<Backup> describeBackups(List<String> filterBackupIds, List<String> filterClusterIds,
                                              List<String> filterStates, List<String> filterSourceBackupIds,
                                              List<String> filterNeverExpires, Boolean shared, Boolean sortAscending, String region) {
        Collection<Backup> all = backups.scan(k -> k.startsWith(region + "::"));
        List<Backup> filtered = new ArrayList<>();
        for (Backup b : all) {
            boolean matchId = filterBackupIds == null || filterBackupIds.isEmpty() || filterBackupIds.contains(b.getBackupId());
            boolean matchCluster = filterClusterIds == null || filterClusterIds.isEmpty() || filterClusterIds.contains(b.getClusterId());
            boolean matchState = filterStates == null || filterStates.isEmpty() || filterStates.contains(b.getBackupState());
            boolean matchSourceBackup = filterSourceBackupIds == null || filterSourceBackupIds.isEmpty() || filterSourceBackupIds.contains(b.getSourceBackup());
            boolean matchNeverExpires = filterNeverExpires == null || filterNeverExpires.isEmpty() || 
                filterNeverExpires.stream().anyMatch(f -> f.equalsIgnoreCase(b.getNeverExpires() == null ? "False" : b.getNeverExpires()));

            if (matchId && matchCluster && matchState && matchSourceBackup && matchNeverExpires) {
                filtered.add(b);
            }
        }

        if (Boolean.TRUE.equals(sortAscending)) {
            filtered.sort(java.util.Comparator.comparing(Backup::getCreateTimestamp));
        } else {
            filtered.sort(java.util.Comparator.comparing(Backup::getCreateTimestamp).reversed());
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
        if (backup.getDeleteTimestamp() != null) {
            long daysSinceDeletion = java.time.Duration.between(backup.getDeleteTimestamp(), Instant.now()).toDays();
            if (daysSinceDeletion > 7) {
                throw new AwsException("CloudHsmInvalidRequestException", "Backup is past the 7-day deletion window", 400);
            }
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
        copy.setHsmType(source.getHsmType());
        copy.setNeverExpires(source.getNeverExpires());
        backups.put(regionKey(destinationRegion, copy.getBackupId()), copy);
        return copy;
    }

    // ──────────────────────────── Resource Policies ────────────────────────────

    public void putResourcePolicy(String resourceArn, String policy, String region) {
        String id = extractId(resourceArn);
        if (id.startsWith("backup-")) {
            Backup backup = getBackup(id, region);
            if (!"READY".equals(backup.getBackupState())) {
                throw new AwsException("CloudHsmInvalidRequestException", "Backup must be READY to apply a policy", 400);
            }
            backup.setResourcePolicy(policy);
            backups.put(regionKey(region, id), backup);
        } else {
            Cluster cluster = getCluster(id, region);
            cluster.setResourcePolicy(policy);
            clusters.put(regionKey(region, id), cluster);
        }
    }

    public String getResourcePolicy(String resourceArn, String region) {
        String id = extractId(resourceArn);
        if (id.startsWith("backup-")) {
            return getBackup(id, region).getResourcePolicy();
        } else {
            return getCluster(id, region).getResourcePolicy();
        }
    }

    public String deleteResourcePolicy(String resourceArn, String region) {
        String id = extractId(resourceArn);
        String oldPolicy = null;
        if (id.startsWith("backup-")) {
            Backup backup = getBackup(id, region);
            oldPolicy = backup.getResourcePolicy();
            backup.setResourcePolicy(null);
            backups.put(regionKey(region, id), backup);
        } else {
            Cluster cluster = getCluster(id, region);
            oldPolicy = cluster.getResourcePolicy();
            cluster.setResourcePolicy(null);
            clusters.put(regionKey(region, id), cluster);
        }
        return oldPolicy;
    }

    private String extractId(String arn) {
        if (arn == null) {
            throw new AwsException("CloudHsmInvalidRequestException", "Invalid ResourceArn format", 400);
        }
        if (arn.contains("backup/")) {
            return arn.substring(arn.lastIndexOf('/') + 1);
        } else if (arn.contains("cluster/")) {
            return arn.substring(arn.lastIndexOf('/') + 1);
        }
        if (arn.startsWith("cluster-") || arn.startsWith("backup-")) {
            return arn;
        }
        throw new AwsException("CloudHsmInvalidRequestException", "Invalid ResourceArn format", 400);
    }

    // ──────────────────────────── ModifyCluster ────────────────────────────

    public Cluster modifyCluster(String clusterId, String hsmType, BackupRetentionPolicy backupRetentionPolicy, String region) {
        Cluster cluster = getCluster(clusterId, region);
        if (hsmType != null && !hsmType.matches("^((p|)hsm[0-9][a-z.]*\\.[a-zA-Z]+)$")) {
            throw new AwsException("CloudHsmInvalidRequestException", "HsmType " + hsmType + " is not valid.", 400);
        }
        if (hsmType != null) {
            cluster.setHsmType(hsmType);
        }
        if (backupRetentionPolicy != null) {
            String valStr = backupRetentionPolicy.getValue();
            if (valStr == null) {
                throw new AwsException("CloudHsmInvalidRequestException", "BackupRetentionPolicy Value is missing.", 400);
            }
            try {
                int val = Integer.parseInt(valStr);
                if (val < 7 || val > 379) {
                    throw new AwsException("CloudHsmInvalidRequestException", "BackupRetentionPolicy Value must be between 7 and 379.", 400);
                }
            } catch (NumberFormatException e) {
                throw new AwsException("CloudHsmInvalidRequestException", "BackupRetentionPolicy Value must be a valid integer.", 400);
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
