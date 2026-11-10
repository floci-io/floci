package io.floci.aws.testing;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.Pem;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;

import java.net.InetSocketAddress;
import java.util.List;

/** An HTTPS server on a free localhost port, so a test sees a client verify a certificate a given CA issued. */
public final class LocalHttpsServer {

    private LocalHttpsServer() {
    }

    /** Bound and serving a localhost certificate {@code ca} issued; not started, so the caller adds its contexts. */
    public static HttpsServer issuedBy(FlociCertificateAuthority ca) throws Exception {
        CertificateGenerator.GeneratedCertificate issued = ca.issueServerCertificate(
                "localhost", List.of("localhost"), KeyAlgorithm.RSA_2048, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ca.serverSslContext(
                Pem.parseCertificate(issued.certificatePem()), Pem.parsePrivateKey(issued.privateKeyPem()))));
        return server;
    }
}
