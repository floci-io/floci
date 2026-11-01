package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.lambda.launcher.BootstrapProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The CA bundle handed to every container Floci launches: the JVM's public roots followed by
 * Floci's trust anchor, and the environment entries that make each runtime read it.
 */
class ContainerCaBundleTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void forgetBootstrapTlsDir() {
        // TlsConfigSource.resolvedTlsDir is static: a TLS-on bootstrap run by another test class
        // in this JVM would otherwise steer hostPath() at that test's directory.
        System.setProperty("floci.tls.enabled", "false");
        new TlsConfigSource();
        System.clearProperty("floci.tls.enabled");
    }

    @Test
    void writeProducesTheSystemRootsFollowedByTheAnchor() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);

        Path bundle = ContainerCaBundle.write(tlsDir, ca.certificatePath());

        assertEquals(tlsDir.resolve("floci-ca-bundle.pem"), bundle);
        List<X509Certificate> certificates = parseAll(Files.readAllBytes(bundle));
        assertTrue(certificates.size() > 50, "expected the JVM's public roots, got " + certificates.size());
        assertEquals(ca.certificate(), certificates.get(certificates.size() - 1), "the Floci CA closes the bundle");
        assertEquals(1, certificates.stream().filter(ca.certificate()::equals).count());
    }

    /**
     * The SSL context trusts every root the JVM trusts, so a public issuer, such as a real
     * Cognito pool's, still validates, and Floci's own anchor besides.
     */
    @Test
    void theSslContextTrustsEveryJvmRootAndTheAnchor() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        Path bundle = ContainerCaBundle.write(tlsDir, ca.certificatePath());

        List<X509Certificate> accepted = new ArrayList<>();
        for (TrustManager manager : ContainerCaBundle.trustManagers(bundle)) {
            if (manager instanceof X509TrustManager x509) {
                accepted.addAll(List.of(x509.getAcceptedIssuers()));
            }
        }
        TrustManagerFactory jvm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jvm.init((KeyStore) null);
        for (TrustManager manager : jvm.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                for (X509Certificate root : x509.getAcceptedIssuers()) {
                    assertTrue(accepted.contains(root), root.getSubjectX500Principal().getName());
                }
            }
        }
        assertTrue(accepted.contains(ca.certificate()), "Floci's anchor");
    }

    @Test
    void writeIsIdempotent() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);

        byte[] first = Files.readAllBytes(ContainerCaBundle.write(tlsDir, ca.certificatePath()));
        byte[] second = Files.readAllBytes(ContainerCaBundle.write(tlsDir, ca.certificatePath()));

        assertTrue(Arrays.equals(first, second));
        try (Stream<Path> files = Files.list(tlsDir)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")), "no temp file left behind");
        }
    }

    @Test
    void writeKeepsEveryCertificateOfAChainFileAndDropsPrivateKeys() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        CertificateGenerator.GeneratedCertificate leaf = ca.issueServerCertificate("floci", List.of("floci"),
                KeyAlgorithm.RSA_2048, null);
        Path chainFile = Files.writeString(tempDir.resolve("user-chain.pem"),
                leaf.certificatePem() + leaf.privateKeyPem() + ca.caPem());

        String bundle = Files.readString(ContainerCaBundle.write(tlsDir, chainFile));

        assertFalse(bundle.contains("PRIVATE KEY"), "a private key must never reach a container");
        List<X509Certificate> certificates = parseAll(bundle.getBytes());
        int size = certificates.size();
        assertEquals(ca.certificate(), certificates.get(size - 1));
        assertEquals(Pem.parseCertificate(leaf.certificatePem()), certificates.get(size - 2));
    }

    @Test
    void writeRefusesAnAnchorFileWithoutACertificate() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Path notACertificate = Files.writeString(tempDir.resolve("empty.pem"), "nothing to trust here\n");

        assertThrows(IllegalArgumentException.class, () -> ContainerCaBundle.write(tlsDir, notACertificate));
        assertFalse(Files.exists(tlsDir.resolve("floci-ca-bundle.pem")));
    }

    /**
     * A bundle with no certificate in it would make a trust store that trusts nothing, and every
     * HTTPS call through it would fail, public endpoints included. Floci's own clients fall back to
     * the JVM's roots instead.
     */
    @Test
    void sslContextIsEmptyForABundleWithNoCertificate() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Files.writeString(tlsDir.resolve(ContainerCaBundle.FILE_NAME), "");
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());

        assertEquals(Optional.empty(), ContainerCaBundle.sslContext(config));
        assertThrows(GeneralSecurityException.class,
                () -> ContainerCaBundle.sslContext(tlsDir.resolve(ContainerCaBundle.FILE_NAME)));
    }

    @Test
    void hostPathIsEmptyWhenTlsIsOff() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(false);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());

        assertEquals(Optional.empty(), ContainerCaBundle.hostPath(config));
    }

    @Test
    void hostPathIsEmptyWhenTheBootWriteDidNotHappen() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());

        assertEquals(Optional.empty(), ContainerCaBundle.hostPath(config));
    }

    @Test
    void hostPathFindsTheBundleUnderTheConfiguredPersistentPath() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        Path bundle = ContainerCaBundle.write(tlsDir, ca.certificatePath());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());

        assertEquals(Optional.of(bundle), ContainerCaBundle.hostPath(config));
    }

    @Test
    void hostPathFollowsTheDirectoryTheBootstrapUsed() throws Exception {
        // application.yml alone may name a persistent path the bootstrap never saw; the bundle
        // lives where TlsConfigSource wrote it, next to the CA.
        Path bootDir = tempDir.resolve("boot");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", bootDir.toString());
        try {
            new TlsConfigSource();
        } finally {
            System.clearProperty("floci.tls.enabled");
            System.clearProperty("floci.tls.self-signed");
            System.clearProperty("floci.storage.persistent-path");
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(tempDir.resolve("elsewhere").toString());

        assertEquals(Optional.of(bootDir.resolve("tls").resolve("floci-ca-bundle.pem")),
                ContainerCaBundle.hostPath(config));
    }

    @Test
    void hostPathFollowsTheDirectoryOfAUserCertificateBoot() throws Exception {
        Path bootDir = tempDir.resolve("boot");
        CertificateGenerator.GeneratedCertificate user = new CertificateGenerator()
                .generateSelfSignedCertificate("localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);
        Path userCert = Files.writeString(tempDir.resolve("user.crt"), user.certificatePem());
        Path userKey = Files.writeString(tempDir.resolve("user.key"), user.privateKeyPem());
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.cert-path", userCert.toString());
        System.setProperty("floci.tls.key-path", userKey.toString());
        System.setProperty("floci.storage.persistent-path", bootDir.toString());
        try {
            new TlsConfigSource();
        } finally {
            System.clearProperty("floci.tls.enabled");
            System.clearProperty("floci.tls.cert-path");
            System.clearProperty("floci.tls.key-path");
            System.clearProperty("floci.storage.persistent-path");
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(tempDir.resolve("elsewhere").toString());

        assertEquals(Optional.of(bootDir.resolve("tls").resolve("floci-ca-bundle.pem")),
                ContainerCaBundle.hostPath(config));
    }

    @Test
    void envPointsEveryRuntimeAtTheContainerPath() {
        assertEquals(List.of(
                "SSL_CERT_FILE=/etc/floci-ca-bundle.pem",
                "CURL_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "REQUESTS_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "NODE_EXTRA_CA_CERTS=/etc/floci-ca-bundle.pem",
                "AWS_CA_BUNDLE=/etc/floci-ca-bundle.pem"), ContainerCaBundle.env());
    }

    @Test
    void appendEnvKeepsTheCallerEntriesFirstAndUnchanged() {
        List<String> merged = ContainerCaBundle.appendEnv(List.of("FOO=bar", "SSL_CERT_FILE=/my/own.pem", "AWS_CA_BUNDLE"));

        assertEquals(List.of("FOO=bar", "SSL_CERT_FILE=/my/own.pem", "AWS_CA_BUNDLE"), merged.subList(0, 3));
        assertEquals(List.of(
                "CURL_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "REQUESTS_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "NODE_EXTRA_CA_CERTS=/etc/floci-ca-bundle.pem"), merged.subList(3, merged.size()));
    }

    @Test
    void appendEnvOfNothingIsTheTrustEnv() {
        assertEquals(ContainerCaBundle.env(), ContainerCaBundle.appendEnv(null));
        assertEquals(ContainerCaBundle.env(), ContainerCaBundle.appendEnv(List.of()));
    }

    // Options after Floci's win, so a trust store they name replaces it, as the JVM test below shows.
    @Test
    void appendJavaTrustEnvPutsFlocisOptionAheadOfTheContainersOwnWhichReplacesTheImages() {
        assertEquals(List.of("FOO=bar", "JAVA_TOOL_OPTIONS=" + ContainerCaBundle.JAVA_TRUST_OPTIONS + " -Xss2m"),
                ContainerCaBundle.appendJavaTrustEnv(List.of("FOO=bar", "JAVA_TOOL_OPTIONS=-Xss2m"),
                        List.of("JAVA_TOOL_OPTIONS=-Dimage=1")));
    }

    @Test
    void appendJavaTrustEnvPutsFlocisOptionAheadOfTheImagesLastWhenTheContainerSetsNone() {
        assertEquals(List.of("FOO=bar", "JAVA_TOOL_OPTIONS=" + ContainerCaBundle.JAVA_TRUST_OPTIONS + " -Dimage=2"),
                ContainerCaBundle.appendJavaTrustEnv(List.of("FOO=bar"),
                        List.of("PATH=/bin", "JAVA_TOOL_OPTIONS=-Dimage=1", "JAVA_TOOL_OPTIONS=-Dimage=2")));
    }

    @Test
    void appendJavaTrustEnvGivesFlocisOptionAloneWhenNothingElseIsSet() {
        List<String> alone = List.of("JAVA_TOOL_OPTIONS=" + ContainerCaBundle.JAVA_TRUST_OPTIONS);

        assertEquals(alone, ContainerCaBundle.appendJavaTrustEnv(List.of(), List.of("PATH=/bin")));
        assertEquals(alone, ContainerCaBundle.appendJavaTrustEnv(List.of("JAVA_TOOL_OPTIONS="), List.of()));
        // A bare key unsets the image's value, so none is kept.
        assertEquals(alone, ContainerCaBundle.appendJavaTrustEnv(List.of("JAVA_TOOL_OPTIONS"),
                List.of("JAVA_TOOL_OPTIONS=-Dimage=1")));
    }

    @Test
    void theJavaTrustStoreHoldsEveryCertificateOfTheBundleAsATrustedEntry() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        Path bundle = ContainerCaBundle.write(tlsDir, ca.certificatePath());

        Path trustStore = ContainerCaBundle.writeJavaTrustStore(bundle);

        assertEquals(tlsDir.resolve("floci-ca-bundle.p12"), trustStore);
        // Loaded as a JVM given only its path loads it: the default store type, and no password.
        assertEquals("-Djavax.net.ssl.trustStore=/etc/floci-ca-bundle.p12", ContainerCaBundle.JAVA_TRUST_OPTIONS);
        KeyStore loaded = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream in = Files.newInputStream(trustStore)) {
            loaded.load(in, null);
        }
        List<X509Certificate> held = new ArrayList<>();
        for (String alias : Collections.list(loaded.aliases())) {
            assertTrue(loaded.isCertificateEntry(alias), alias + " is a trusted certificate");
            held.add((X509Certificate) loaded.getCertificate(alias));
        }
        assertEquals(Set.copyOf(parseAll(Files.readAllBytes(bundle))), Set.copyOf(held));
        assertTrue(held.contains(ca.certificate()), "Floci's anchor");
        try (Stream<Path> files = Files.list(tlsDir)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")),
                    "no temp file left behind");
        }
    }

    @Test
    void javaTrustStoreHostPathIsEmptyUntilTheBootWritesIt() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        Path bundle = ContainerCaBundle.write(tlsDir, ca.certificatePath());

        assertEquals(Optional.empty(), ContainerCaBundle.javaTrustStoreHostPath(config));
        Path trustStore = ContainerCaBundle.writeJavaTrustStore(bundle);
        assertEquals(Optional.of(trustStore), ContainerCaBundle.javaTrustStoreHostPath(config));
        when(config.tls().enabled()).thenReturn(false);
        assertEquals(Optional.empty(), ContainerCaBundle.javaTrustStoreHostPath(config));
    }

    @Test
    void theShellLinePointsTheJvmsAtTheTrustStoreOnlyWhenItIsThere() throws Exception {
        Path trustStore = Files.writeString(tempDir.resolve("floci-ca-bundle.p12"), "trust store");
        String line = ContainerCaBundle.JAVA_TRUST_SHELL_LINE
                .replace(ContainerCaBundle.JAVA_TRUST_STORE_PATH, trustStore.toString());
        String options = ContainerCaBundle.JAVA_TRUST_OPTIONS
                .replace(ContainerCaBundle.JAVA_TRUST_STORE_PATH, trustStore.toString());

        assertEquals(options, javaToolOptionsAfter(line, null));
        // Options already there come after Floci's, so a trust store of their own wins.
        assertEquals(options + " -Djavax.net.ssl.trustStore=/own.p12",
                javaToolOptionsAfter(line, "-Djavax.net.ssl.trustStore=/own.p12"));
        Files.delete(trustStore);
        assertEquals("unset", javaToolOptionsAfter(line, null));
        assertEquals("-Xss2m", javaToolOptionsAfter(line, "-Xss2m"));
    }

    @Test
    void aJvmTrustsTheStoreByItsPathAloneAndAStoreAJobNamesReplacesItWhole() throws Exception {
        Path tlsDir = tempDir.resolve("tls");
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        Path bundle = ContainerCaBundle.write(tlsDir, ca.certificatePath());
        String line = ContainerCaBundle.JAVA_TRUST_SHELL_LINE.replace(ContainerCaBundle.JAVA_TRUST_STORE_PATH,
                ContainerCaBundle.writeJavaTrustStore(bundle).toString());
        int bundled = Set.copyOf(parseAll(Files.readAllBytes(bundle))).size();
        // A job's own store, under a password of its own, which it names by its path alone.
        Path own = tempDir.resolve("own.jks");
        KeyStore ownStore = KeyStore.getInstance("JKS");
        ownStore.load(null, null);
        ownStore.setCertificateEntry("own", ca.certificate());
        try (OutputStream out = Files.newOutputStream(own)) {
            ownStore.store(out, "s3cret".toCharArray());
        }

        assertEquals(bundled, anchorsTrustedAfter(line, null));
        assertEquals(1, anchorsTrustedAfter(line, "-Djavax.net.ssl.trustStore=" + own));
        // A password or a type a job gives for the JDK's own store still reads Floci's.
        assertEquals(bundled, anchorsTrustedAfter(line, "-Djavax.net.ssl.trustStorePassword=changeit"));
        assertEquals(bundled, anchorsTrustedAfter(line, "-Djavax.net.ssl.trustStoreType=JKS"));
    }

    /** Run in a JVM of its own: prints how many anchors that JVM trusts by default, as its options say. */
    public static final class TrustedAnchors {
        public static void main(String[] args) throws GeneralSecurityException {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            int anchors = 0;
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    anchors += x509.getAcceptedIssuers().length;
                }
            }
            System.out.println("trusted anchors " + anchors);
        }
    }

    // JAVA_TOOL_OPTIONS as a shell that ran line leaves it, starting from existing, or from none when null.
    private static String javaToolOptionsAfter(String line, String existing) throws Exception {
        return runAfter(line, existing, "printf %s \"${JAVA_TOOL_OPTIONS-unset}\"");
    }

    // How many anchors a JVM a shell starts after line trusts, JAVA_TOOL_OPTIONS starting as javaToolOptionsAfter's.
    private static int anchorsTrustedAfter(String line, String existing) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(TrustedAnchors.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        String output = runAfter(line, existing, "exec \"$@\"", java, "-cp", classes, TrustedAnchors.class.getName());
        Matcher anchors = Pattern.compile("trusted anchors (\\d+)").matcher(output);
        assertTrue(anchors.find(), output);
        return Integer.parseInt(anchors.group(1));
    }

    // What script, run by a shell after line with arguments as "$@", prints, JAVA_TOOL_OPTIONS starting from
    // existing, or from none when null.
    private static String runAfter(String line, String existing, String script, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", "-c", line + "\n" + script, "floci"));
        command.addAll(List.of(arguments));
        ProcessBuilder shell = new ProcessBuilder(command);
        shell.environment().remove("JAVA_TOOL_OPTIONS");
        if (existing != null) {
            shell.environment().put("JAVA_TOOL_OPTIONS", existing);
        }
        BootstrapProcess.Result ended = BootstrapProcess.run(shell);
        assertEquals(0, ended.exit(), ended.output());
        return ended.output();
    }

    private static List<X509Certificate> parseAll(byte[] pem) throws Exception {
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem))) {
            certificates.add((X509Certificate) certificate);
        }
        return certificates;
    }
}
