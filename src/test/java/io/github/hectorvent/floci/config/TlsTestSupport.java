package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.testing.TlsTrust;

import javax.net.ssl.SSLContext;
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
