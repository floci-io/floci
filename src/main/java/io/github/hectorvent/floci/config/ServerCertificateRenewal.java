package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Issues the server leaf a Floci TLS listener presents, signed by Floci's CA, and says when the
 * next one is due. A leaf lasts a year but never past the CA, and is due a minute before it ends,
 * unless it already ends with the CA: that one cannot be extended without a new trust anchor.
 */
public final class ServerCertificateRenewal {

    private static final Duration VALIDITY = Duration.ofDays(365);
    private static final Duration RENEWAL_MARGIN = Duration.ofSeconds(60);

    private final FlociCertificateAuthority authority;
    private final CertificateGenerator generator;
    private final Clock clock;

    /** A listener's server context and the end of its leaf's validity. */
    public record Leaf(SSLContext context, Instant notAfter) {
    }

    public ServerCertificateRenewal(FlociCertificateAuthority authority, CertificateGenerator generator, Clock clock) {
        this.authority = authority;
        this.generator = generator;
        this.clock = clock;
    }

    /** A new leaf for {@code sans}. An expired CA signs nothing, so it fails rather than issue a dead leaf. */
    public Leaf issue(List<String> sans) throws GeneralSecurityException, IOException {
        Instant now = clock.instant();
        X509Certificate ca = authority.certificate();
        ca.checkValidity(Date.from(now));
        Instant notAfter = now.plus(VALIDITY);
        Instant caNotAfter = ca.getNotAfter().toInstant();
        if (caNotAfter.isBefore(notAfter)) {
            notAfter = caNotAfter;
        }
        CertificateGenerator.GeneratedCertificate generated = generator.generateIssuedCertificate(
                "localhost", sans, KeyAlgorithm.RSA_2048, null, authority.issuer(),
                CertificateGenerator.LeafUsage.SERVER, notAfter);
        SSLContext context = authority.serverSslContext(Pem.parseCertificate(generated.certificatePem()),
                Pem.parsePrivateKey(generated.privateKeyPem()));
        return new Leaf(context, generated.notAfter());
    }

    /** True with no leaf yet, and once {@code leaf} is due. */
    public boolean due(Leaf leaf) {
        if (leaf == null) {
            return true;
        }
        Instant renewAt = leaf.notAfter();
        if (renewAt.isBefore(authority.certificate().getNotAfter().toInstant())) {
            renewAt = renewAt.minus(RENEWAL_MARGIN);
        }
        return !renewAt.isAfter(clock.instant());
    }
}
