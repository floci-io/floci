package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hostnames {@link TlsCertificateManager} learned at runtime must survive every boot path of
 * {@link TlsConfigSource}: a reused certificate, a regeneration after a configuration change,
 * a reissue of an expired leaf, and a certificate whose metadata write did not complete.
 */
class TlsConfigSourceLearnedHostnamesTest {

    private static final String LEARNED = "api.example.localhost.floci.io";
    private static final List<String> DEFAULTS = List.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost",
            "localhost.floci.io", "*.localhost.floci.io", "*.execute-api.localhost.floci.io",
            "*.execute-api.localhost.localstack.cloud", "host.docker.internal");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path tlsDir;
    private Path metadataFile;

    @BeforeAll
    static void bouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeEach
    void tlsOn() {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
        tlsDir = tempDir.resolve("tls");
        metadataFile = tlsDir.resolve("floci-server.metadata.json");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty("floci.hostname");
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.storage.persistent-path");
    }

    @Test
    void learnedHostnamesAreKeptWhenBootRegeneratesTheCertificate() throws Exception {
        System.setProperty("floci.hostname", "host1");
        new TlsConfigSource();
        recordAsLearned(LEARNED);

        System.setProperty("floci.hostname", "host2");
        new TlsConfigSource();

        List<String> sans = servedSans();
        assertTrue(sans.contains("host2"), sans.toString());
        assertFalse(sans.contains("host1"), sans.toString());
        assertTrue(sans.contains(LEARNED), "learned name must survive: " + sans);
        CertificateMetadata after = readMetadata();
        assertEquals(List.of(LEARNED), after.getLearnedHostnames());
        assertFalse(after.getHostnames().contains(LEARNED), "learned names stay out of the configured list");
    }

    @Test
    void aLearnedHostnameDoesNotCountAsAConfigurationChange() throws Exception {
        new TlsConfigSource();
        recordAsLearned(LEARNED);
        String certBefore = Files.readString(tlsDir.resolve("floci-server.crt"));

        new TlsConfigSource();

        assertEquals(certBefore, Files.readString(tlsDir.resolve("floci-server.crt")),
                "same configuration: the certificate is reused, not regenerated");
    }

    @Test
    void learnedHostnamesAreKeptWhenAnExpiredLeafIsReissued() throws Exception {
        Files.createDirectories(tlsDir);
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        CertificateGenerator gen = new CertificateGenerator();
        List<String> sans = new ArrayList<>(DEFAULTS);
        sans.add(LEARNED);
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        X509Certificate expired = gen.signCertificate(new X500Name("CN=localhost"), keyPair.getPublic(),
                X500Name.getInstance(ca.certificate().getSubjectX500Principal().getEncoded()), ca.key(), sans, false,
                CertificateGenerator.LeafUsage.SERVER, -1);
        Files.writeString(tlsDir.resolve("floci-server.crt"), gen.toPem(expired));
        Files.writeString(tlsDir.resolve("floci-server.key"), gen.toPem(keyPair.getPrivate()));
        CertificateMetadata metadata = CertificateMetadata.create(DEFAULTS, "dev");
        metadata.setLearnedHostnames(List.of(LEARNED));
        Files.writeString(metadataFile, MAPPER.writeValueAsString(metadata));

        new TlsConfigSource();

        X509Certificate leaf = parseCertificate(tlsDir.resolve("floci-server.crt"));
        leaf.checkValidity();
        assertNotEquals(expired.getSerialNumber(), leaf.getSerialNumber(), "the expired leaf was reissued");
        List<String> reissued = servedSans();
        assertTrue(reissued.contains(LEARNED), "learned name must survive: " + reissued);
        assertTrue(reissued.containsAll(DEFAULTS), reissued.toString());
    }

    @Test
    void aSanTheMetadataDoesNotListIsKeptAsLearnedWhenBootRegenerates() throws Exception {
        System.setProperty("floci.hostname", "host1");
        new TlsConfigSource();
        CertificateMetadata bootMetadata = readMetadata();

        // A runtime reissue whose certificate rename succeeded and whose metadata rename did not:
        // the served leaf carries the learned name, the metadata knows nothing about it.
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        CertificateGenerator gen = new CertificateGenerator();
        X509Certificate served = parseCertificate(tlsDir.resolve("floci-server.crt"));
        KeyPair keyPair = new KeyPair(served.getPublicKey(),
                gen.parsePrivateKey(Files.readString(tlsDir.resolve("floci-server.key"))));
        List<String> sans = new ArrayList<>(bootMetadata.getHostnames());
        sans.add(LEARNED);
        Files.writeString(tlsDir.resolve("floci-server.crt"),
                ca.issueServerCertificate("localhost", sans, KeyAlgorithm.RSA_2048, keyPair).certificatePem());

        System.setProperty("floci.hostname", "host2");
        new TlsConfigSource();

        List<String> regenerated = servedSans();
        assertTrue(regenerated.contains(LEARNED), "served name must survive: " + regenerated);
        assertTrue(regenerated.contains("host2"), regenerated.toString());
        assertFalse(regenerated.contains("host1"), "a previously configured name is not mistaken for a learned one");
        assertEquals(List.of(LEARNED), readMetadata().getLearnedHostnames());
    }

    private void recordAsLearned(String name) throws Exception {
        CertificateMetadata metadata = readMetadata();
        metadata.setLearnedHostnames(List.of(name));
        Files.writeString(metadataFile, MAPPER.writeValueAsString(metadata));
    }

    private CertificateMetadata readMetadata() throws Exception {
        return MAPPER.readValue(metadataFile.toFile(), CertificateMetadata.class);
    }

    private List<String> servedSans() throws Exception {
        List<String> sans = new ArrayList<>();
        for (List<?> entry : parseCertificate(tlsDir.resolve("floci-server.crt")).getSubjectAlternativeNames()) {
            sans.add(String.valueOf(entry.get(1)));
        }
        return sans;
    }

    private static X509Certificate parseCertificate(Path certFile) throws Exception {
        return new CertificateGenerator().parseCertificate(Files.readString(certFile));
    }
}
