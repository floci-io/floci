package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfoBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS8EncryptedPrivateKeyInfoBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@ApplicationScoped
public class CertificateGenerator {

    private static final Logger LOG = Logger.getLogger(CertificateGenerator.class);
    private static final String ISSUER_DN = "CN=Amazon,OU=Server CA 1B,O=Amazon,C=US";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    void onStart(@Observes StartupEvent ev) {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            LOG.info("Registered BouncyCastle security provider");
        }
    }

    public record GeneratedCertificate(
        String certificatePem,
        String privateKeyPem,
        String serial,
        Instant notBefore,
        Instant notAfter,
        String subject,
        String issuer,
        String signatureAlgorithm
    ) {}

    public GeneratedCertificate generateCertificate(String domainName, List<String> sans, KeyAlgorithm keyAlgorithm) {
        try {
            KeyPair keyPair = generateKeyPair(keyAlgorithm);

            Instant now = Instant.now();
            Instant notBefore = now;
            Instant notAfter = now.plus(365, ChronoUnit.DAYS);

            BigInteger serial = new BigInteger(128, SECURE_RANDOM);
            String subjectDn = "CN=" + domainName;

            X500Name issuer = new X500Name(ISSUER_DN);
            X500Name subject = new X500Name(subjectDn);

            String signatureAlgorithm = keyAlgorithm.getAlgorithm().equals("EC")
                ? "SHA512withECDSA"
                : "SHA512WithRSA";

            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer,
                serial,
                Date.from(notBefore),
                Date.from(notAfter),
                subject,
                keyPair.getPublic()
            );

            // Add Subject Alternative Names
            List<GeneralName> sanList = new ArrayList<>();
            sanList.add(new GeneralName(GeneralName.dNSName, domainName));
            if (sans != null) {
                for (String san : sans) {
                    if (!san.equals(domainName)) {
                        sanList.add(new GeneralName(GeneralName.dNSName, san));
                    }
                }
            }
            GeneralNames generalNames = new GeneralNames(sanList.toArray(new GeneralName[0]));
            certBuilder.addExtension(Extension.subjectAlternativeName, false, generalNames);

            // Add Key Usage
            certBuilder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
            );

            // Add Basic Constraints (not a CA)
            certBuilder.addExtension(
                Extension.basicConstraints,
                true,
                new BasicConstraints(false)
            );

            // Self-signed certificate for local emulation - signed with subject's own private key
            // Real AWS ACM certificates are signed by Amazon's CA hierarchy
            ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.getPrivate());

            X509CertificateHolder certHolder = certBuilder.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(certHolder);

            String certPem = toPem(cert);
            String keyPem = toPem(keyPair.getPrivate());

            return new GeneratedCertificate(
                certPem,
                keyPem,
                serial.toString(),
                notBefore,
                notAfter,
                subjectDn,
                ISSUER_DN,
                signatureAlgorithm
            );

        } catch (Exception e) {
            LOG.error("Failed to generate certificate", e);
            throw new CertificateGenerationException("Certificate generation failed: " + e.getMessage(), e);
        }
    }

    private KeyPair generateKeyPair(KeyAlgorithm keyAlgorithm) throws Exception {
        KeyPairGenerator keyGen;
        if ("EC".equals(keyAlgorithm.getAlgorithm())) {
            keyGen = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            keyGen.initialize(new ECGenParameterSpec(keyAlgorithm.getCurveName()), SECURE_RANDOM);
        } else {
            keyGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
            keyGen.initialize(keyAlgorithm.getKeySize(), SECURE_RANDOM);
        }
        return keyGen.generateKeyPair();
    }

    private String toPem(Object obj) throws Exception {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
            pemWriter.writeObject(obj);
        }
        return sw.toString();
    }

    public String encryptPrivateKey(String privateKeyPem, String passphrase) {
        try {
            PrivateKey privateKey = parsePrivateKey(privateKeyPem);

            JcePKCSPBEOutputEncryptorBuilder encryptorBuilder = new JcePKCSPBEOutputEncryptorBuilder(
                PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC
            );
            encryptorBuilder.setProvider(BouncyCastleProvider.PROVIDER_NAME);

            PKCS8EncryptedPrivateKeyInfoBuilder pkcs8Builder = new JcaPKCS8EncryptedPrivateKeyInfoBuilder(privateKey);
            PKCS8EncryptedPrivateKeyInfo encryptedInfo = pkcs8Builder.build(
                encryptorBuilder.build(passphrase.toCharArray())
            );

            StringWriter sw = new StringWriter();
            try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
                pemWriter.writeObject(encryptedInfo);
            }
            return sw.toString();

        } catch (Exception e) {
            LOG.error("Failed to encrypt private key", e);
            throw new CertificateGenerationException("Private key encryption failed: " + e.getMessage(), e);
        }
    }

    public X509Certificate parseCertificate(String certPem) {
        try (PEMParser parser = new PEMParser(new StringReader(certPem))) {
            Object obj = parser.readObject();
            if (obj instanceof X509CertificateHolder holder) {
                return new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(holder);
            }
            throw new IllegalArgumentException("Invalid certificate PEM format");
        } catch (Exception e) {
            LOG.error("Failed to parse certificate", e);
            throw new CertificateGenerationException("Certificate parsing failed: " + e.getMessage(), e);
        }
    }

    public PrivateKey parsePrivateKey(String keyPem) {
        try (PEMParser parser = new PEMParser(new StringReader(keyPem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME);

            if (obj instanceof org.bouncycastle.openssl.PEMKeyPair pemKeyPair) {
                return converter.getKeyPair(pemKeyPair).getPrivate();
            } else if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo pkInfo) {
                return converter.getPrivateKey(pkInfo);
            }
            throw new IllegalArgumentException("Invalid private key PEM format");
        } catch (Exception e) {
            LOG.error("Failed to parse private key", e);
            throw new CertificateGenerationException("Private key parsing failed: " + e.getMessage(), e);
        }
    }

    public void validateCertificate(X509Certificate cert) {
        try {
            cert.checkValidity();
        } catch (Exception e) {
            throw new IllegalArgumentException("Certificate validation failed: " + e.getMessage(), e);
        }
    }

    public KeyAlgorithm detectKeyAlgorithm(PublicKey publicKey) {
        String algorithm = publicKey.getAlgorithm();
        if ("RSA".equals(algorithm)) {
            try {
                java.security.interfaces.RSAPublicKey rsaKey = (java.security.interfaces.RSAPublicKey) publicKey;
                int keySize = rsaKey.getModulus().bitLength();
                return switch (keySize) {
                    case 1024 -> KeyAlgorithm.RSA_1024;
                    case 3072 -> KeyAlgorithm.RSA_3072;
                    case 4096 -> KeyAlgorithm.RSA_4096;
                    default -> KeyAlgorithm.RSA_2048;
                };
            } catch (Exception e) {
                return KeyAlgorithm.RSA_2048;
            }
        } else if ("EC".equals(algorithm)) {
            try {
                java.security.interfaces.ECPublicKey ecKey = (java.security.interfaces.ECPublicKey) publicKey;
                int fieldSize = ecKey.getParams().getCurve().getField().getFieldSize();
                return switch (fieldSize) {
                    case 384 -> KeyAlgorithm.EC_secp384r1;
                    case 521 -> KeyAlgorithm.EC_secp521r1;
                    default -> KeyAlgorithm.EC_prime256v1;
                };
            } catch (Exception e) {
                return KeyAlgorithm.EC_prime256v1;
            }
        }
        return KeyAlgorithm.RSA_2048;
    }
}
