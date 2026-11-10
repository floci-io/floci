package io.floci.aws.config;

import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.testing.TlsTrust;

import javax.net.ssl.SSLContext;
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
}
