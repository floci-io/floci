package io.github.hectorvent.floci.testing;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

/** A client TLS context that trusts one CA and nothing else, so a test sees real verification. */
public final class TlsTrust {

    private TlsTrust() {
    }

    public static SSLContext trusting(X509Certificate ca) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci", ca);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }
}
