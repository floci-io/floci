package io.github.hectorvent.floci.core.common;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.jboss.logging.Logger;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;

/**
 * Reading PEM certificate and private-key material, and checking that the two belong together.
 *
 * <p>These are stateless and have no connection to any one AWS service: {@code config} reads the
 * emulator's own TLS material with them, IoT and the RDS proxy read device and endpoint material,
 * ACM reads what a caller imports, and IAM reads what is uploaded as a server certificate. They
 * previously lived on {@code services.acm.CertificateGenerator}, which meant unrelated packages
 * depended on ACM to parse a file. Generation still belongs to that class; only the reading side
 * is shared.
 */
public final class Pem {

    private static final Logger LOG = Logger.getLogger(Pem.class);

    private Pem() {
    }

    /** The certificate a PEM block holds. */
    public static X509Certificate parseCertificate(String certPem) {
        try (PEMParser parser = new PEMParser(new StringReader(certPem))) {
            Object parsed = parser.readObject();
            if (parsed instanceof X509CertificateHolder holder) {
                return new JcaX509CertificateConverter().getCertificate(holder);
            }
            throw new IllegalArgumentException("Invalid certificate PEM format");
        } catch (Exception e) {
            LOG.error("Failed to parse certificate", e);
            throw new CertificateMaterialException("Certificate parsing failed: " + e.getMessage(), e);
        }
    }

    /** The private key a PEM block holds, whether it is a key pair or a bare PKCS#8 key. */
    public static PrivateKey parsePrivateKey(String keyPem) {
        try (PEMParser parser = new PEMParser(new StringReader(keyPem))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair pemKeyPair) {
                // Only the private half is needed, and a SEC1 key may carry no public half at all.
                return converter.getPrivateKey(pemKeyPair.getPrivateKeyInfo());
            } else if (parsed instanceof PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);
            }
            throw new IllegalArgumentException("Invalid private key PEM format");
        } catch (Exception e) {
            LOG.error("Failed to parse private key", e);
            throw new CertificateMaterialException("Private key parsing failed: " + e.getMessage(), e);
        }
    }

    /**
     * True when {@code privateKey} signs what {@code publicKey} verifies. Done by signing a probe
     * rather than comparing key fields, so it holds for RSA and EC alike without either being
     * special-cased beyond the signature algorithm.
     */
    public static boolean isPair(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        String algorithm = privateKey instanceof ECKey ? "SHA256withECDSA" : "SHA256withRSA";
        byte[] probe = "floci".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(privateKey);
        signer.update(probe);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(publicKey);
        verifier.update(probe);
        return verifier.verify(signature);
    }
}
