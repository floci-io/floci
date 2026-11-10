package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.Pem;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.net.ssl.SSLContext;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The certificate every DSQL endpoint presents: one leaf, signed by Floci's root CA, naming
 * {@code *.dsql.<region>.<endpoint-suffix>} for every Region Floci knows, so a client verifying
 * the endpoint's name (sslmode=verify-full, as DSQL's own connectors do) only has to trust
 * Floci's CA, the one every launched container already trusts.
 */
@ApplicationScoped
public class DsqlTlsCertificates {

    private final FlociCertificateAuthority certificateAuthority;
    private final String endpointSuffix;
    private volatile SSLContext context;

    @Inject
    public DsqlTlsCertificates(FlociCertificateAuthority certificateAuthority, EmulatorConfig config) {
        this(certificateAuthority, config.services().dsql().endpointSuffix());
    }

    DsqlTlsCertificates(FlociCertificateAuthority certificateAuthority, String endpointSuffix) {
        this.certificateAuthority = certificateAuthority;
        this.endpointSuffix = endpointSuffix;
    }

    public SSLContext sslContext() {
        SSLContext current = context;
        if (current == null) {
            synchronized (this) {
                if (context == null) {
                    context = build();
                }
                current = context;
            }
        }
        return current;
    }

    private SSLContext build() {
        try {
            List<String> sans = new ArrayList<>();
            for (String region : new TreeSet<>(AwsRegions.KNOWN_IDS)) {
                sans.add("*.dsql." + region + "." + endpointSuffix);
            }
            CertificateGenerator.GeneratedCertificate generated = certificateAuthority.issueServerCertificate(
                    "dsql." + endpointSuffix, sans, KeyAlgorithm.RSA_2048, null);
            return certificateAuthority.serverSslContext(Pem.parseCertificate(generated.certificatePem()),
                    Pem.parsePrivateKey(generated.privateKeyPem()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue the DSQL endpoint certificate", e);
        }
    }
}
