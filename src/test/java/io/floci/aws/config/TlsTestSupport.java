package io.floci.aws.config;

import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.testing.TlsTrust;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class TlsTestSupport {

    private TlsTestSupport() {}

    static SSLContext trustOnlyTheCa() throws Exception {
        return TlsTrust.trusting(FlociCertificateAuthority.loadOrCreate(Path.of(SelfSignedTlsProfile.TLS_DIR)).certificate());
    }

    // Writes the certificate of ca and then a private key to file, as a combined certificate-and-key file holds.
    static Path caWithPrivateKey(FlociCertificateAuthority ca, Path file) throws Exception {
        CertificateGenerator.GeneratedCertificate leaf = ca.issueServerCertificate("floci", List.of("floci"),
                KeyAlgorithm.RSA_2048, null);
        return Files.writeString(file, ca.caPem() + leaf.privateKeyPem());
    }

    static Set<String> dnsSans(X509Certificate certificate) throws Exception {
        Set<String> names = new TreeSet<>();
        for (List<?> entry : certificate.getSubjectAlternativeNames()) {
            if (Integer.valueOf(2).equals(entry.get(0))) {
                names.add(String.valueOf(entry.get(1)));
            }
        }
        return names;
    }

    /**
     * Connects by IP so the JDK has no peer host of its own and verifies the certificate against the SNI
     * name. The fixture leaf carries no {@code 127.0.0.1} SAN, so nothing else can match.
     */
    static X509Certificate handshake(int port, String serverName, boolean verifyIdentity) throws Exception {
        try (SSLSocket socket = (SSLSocket) trustOnlyTheCa().getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(serverName)));
            if (verifyIdentity) {
                params.setEndpointIdentificationAlgorithm("HTTPS");
            }
            socket.setSSLParameters(params);
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }
}
