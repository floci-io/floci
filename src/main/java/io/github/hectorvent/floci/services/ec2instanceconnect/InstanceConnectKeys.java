package io.github.hectorvent.floci.services.ec2instanceconnect;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.jcajce.JcaBasicOCSPRespBuilder;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The instance side of EC2 Instance Connect: the keys {@code SendSSHPublicKey} pushed, and the
 * signing material the unmodified AWS agent ({@code aws-ec2-instance-connect-config}) verifies
 * them with, served through IMDS under {@code managed-ssh-keys/}.
 *
 * <p>The agent's contract, which this class exists to satisfy:
 * <ul>
 *   <li>{@code signer-cert/} is a PEM chain: the signer first, then each issuer. The signer's CN
 *       must be {@code managed-ssh-signer.<region>.<domain>}, and the last certificate's CN must
 *       name a trusted file {@code /etc/ssl/certs/<CN with spaces as underscores>.pem}.</li>
 *   <li>Every certificate below that trusted one needs an OCSP staple at
 *       {@code signer-ocsp/<upper-case SHA-1 fingerprint>} (base64 DER), signed by its issuer,
 *       reporting {@code good}.</li>
 *   <li>{@code active-keys/<user>/} holds one entry per key: {@code #Timestamp=} (expiry, epoch
 *       seconds), {@code #Instance=}, {@code #Caller=} and {@code #Request=} lines, the key line,
 *       then a base64 RSA-PSS (SHA-256, MGF1-SHA-256, salt 32) signature over those lines, and a
 *       blank line.</li>
 * </ul>
 *
 * <p>The chain is signer, a Floci intermediate, and Floci's local CA, which Floci installs into
 * each guest's trust store. On AWS the chain ends at a public root the AMI already trusts.
 * Signing material lives in memory: a restart issues a new intermediate and signers under the
 * same persistent root, so guests keep verifying.
 */
@ApplicationScoped
public class InstanceConnectKeys implements Resettable {

    /** AWS keeps a pushed key for 60 seconds. */
    public static final Duration KEY_LIFETIME = Duration.ofSeconds(60);
    static final String DOMAIN = "amazonaws.com";
    static final String INTERMEDIATE_CN = "Floci EC2 Instance Connect Intermediate CA";
    private static final Duration INTERMEDIATE_VALIDITY = Duration.ofDays(365);
    private static final Duration SIGNER_VALIDITY = Duration.ofDays(30);
    /** Material is reissued once it is this close to expiry, so a verification never races it. */
    private static final Duration RENEW_BEFORE = Duration.ofDays(1);
    private static final Duration STAPLE_VALIDITY = Duration.ofDays(7);

    public record ActiveKey(String instanceId, String osUser, String publicKey, Instant expiresAt,
                            String caller, String requestId) {}

    private record Issued(X509Certificate certificate, PrivateKey key) {}

    private final Supplier<FlociCertificateAuthority> rootCa;
    private final CertificateGenerator generator;
    private final Clock clock;
    private final Map<String, List<ActiveKey>> keysByInstance = new ConcurrentHashMap<>();
    private final Map<String, Issued> signers = new ConcurrentHashMap<>();
    private Issued intermediate;

    @Inject
    public InstanceConnectKeys(Instance<FlociCertificateAuthority> rootCa,
                               CertificateGenerator generator) {
        this(rootCa::get, generator, Clock.systemUTC());
    }

    InstanceConnectKeys(Supplier<FlociCertificateAuthority> rootCa, CertificateGenerator generator, Clock clock) {
        this.rootCa = rootCa;
        this.generator = generator;
        this.clock = clock;
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    public void add(ActiveKey key) {
        // Expired keys go here rather than on terminate: a key lives 60 seconds, so this bounds the
        // map to instances that received one within the last minute.
        keysByInstance.entrySet().removeIf(entry -> live(entry.getValue()).isEmpty());
        keysByInstance.compute(key.instanceId(), (id, existing) -> {
            List<ActiveKey> live = live(existing);
            live.add(key);
            return live;
        });
    }

    /** The signed authorized-keys document for {@code osUser}, or empty when no key is live. */
    public Optional<String> activeKeysDocument(String region, String instanceId, String osUser) {
        List<ActiveKey> forUser = live(keysByInstance.get(instanceId)).stream()
                .filter(key -> key.osUser().equals(osUser))
                .toList();
        if (forUser.isEmpty()) {
            return Optional.empty();
        }
        PrivateKey signingKey = signer(region).key();
        StringBuilder document = new StringBuilder();
        for (ActiveKey key : forUser) {
            String signed = "#Timestamp=" + key.expiresAt().getEpochSecond() + "\n"
                    + "#Instance=" + key.instanceId() + "\n"
                    + "#Caller=" + key.caller() + "\n"
                    + "#Request=" + key.requestId() + "\n"
                    + key.publicKey().trim() + "\n";
            document.append(signed).append(sign(signingKey, signed)).append("\n\n");
        }
        return Optional.of(document.toString());
    }

    int trackedInstanceCount() {
        return keysByInstance.size();
    }

    private List<ActiveKey> live(List<ActiveKey> keys) {
        Instant now = clock.instant();
        List<ActiveKey> live = new ArrayList<>();
        if (keys != null) {
            keys.stream().filter(key -> key.expiresAt().isAfter(now)).forEach(live::add);
        }
        return live;
    }

    private static String sign(PrivateKey key, String data) {
        try {
            Signature signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            signature.initSign(key);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign an EC2 Instance Connect key", e);
        }
    }

    // ── Signing material ──────────────────────────────────────────────────────

    /**
     * The signer certificate, then the intermediate, then the root: what {@code signer-cert/} serves.
     * Synchronized with {@link #signer} and {@link #intermediate} so a rotation between the two reads
     * cannot mix generations.
     */
    public synchronized String signerCertificateChain(String region) {
        try {
            return generator.toPem(signer(region).certificate())
                    + generator.toPem(intermediate().certificate())
                    + rootCa.get().caPem();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode the EC2 Instance Connect signer chain", e);
        }
    }

    /** OCSP staples by upper-case SHA-1 fingerprint, each base64 DER: the signer's and the intermediate's. */
    public synchronized Map<String, String> ocspStaples(String region) {
        Issued signer = signer(region);
        Issued intermediate = intermediate();
        FlociCertificateAuthority root = rootCa.get();
        Map<String, String> staples = new LinkedHashMap<>();
        staples.put(sha1Fingerprint(signer.certificate()),
                staple(signer.certificate(), intermediate.certificate(), intermediate.key()));
        staples.put(sha1Fingerprint(intermediate.certificate()),
                staple(intermediate.certificate(), root.certificate(), root.key()));
        return staples;
    }

    /** The root guests must trust, and the file name the agent looks it up by. */
    public String trustAnchorPem() {
        return rootCa.get().caPem();
    }

    public String trustAnchorFileStem() {
        String dn = rootCa.get().certificate().getSubjectX500Principal().getName();
        String cn = dn.replaceFirst("(?s).*CN=([^,]+).*", "$1");
        return cn.trim().replaceAll(" +", "_");
    }

    public static String signerCommonName(String region) {
        return "managed-ssh-signer." + region + "." + DOMAIN;
    }

    private synchronized Issued intermediate() {
        if (intermediate == null || expiresSoon(intermediate.certificate())) {
            FlociCertificateAuthority root = rootCa.get();
            intermediate = issue(INTERMEDIATE_CN, true,
                    X500Name.getInstance(root.certificate().getSubjectX500Principal().getEncoded()),
                    root.certificate(), root.key(), INTERMEDIATE_VALIDITY);
            signers.clear();
        }
        return intermediate;
    }

    private synchronized Issued signer(String region) {
        Issued issuer = intermediate();
        Issued current = signers.get(region);
        if (current == null || expiresSoon(current.certificate())) {
            current = issue(signerCommonName(region), false,
                    X500Name.getInstance(issuer.certificate().getSubjectX500Principal().getEncoded()),
                    issuer.certificate(), issuer.key(), SIGNER_VALIDITY);
            signers.put(region, current);
        }
        return current;
    }

    private boolean expiresSoon(X509Certificate certificate) {
        return certificate.getNotAfter().toInstant().isBefore(clock.instant().plus(RENEW_BEFORE));
    }

    private Issued issue(String commonName, boolean asCa, X500Name issuerDn, X509Certificate issuerCert,
                         PrivateKey issuerKey, Duration validity) {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair pair = keyGen.generateKeyPair();
            Instant now = clock.instant();
            X509Certificate cert = generator.signCertificate(new X500Name("CN=" + commonName), pair.getPublic(),
                    issuerDn, issuerKey, List.of(), asCa, null, now.minus(5, ChronoUnit.MINUTES),
                    now.plus(validity), issuerCert.getPublicKey());
            return new Issued(cert, pair.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue " + commonName, e);
        }
    }

    private String staple(X509Certificate certificate, X509Certificate issuer, PrivateKey issuerKey) {
        try {
            DigestCalculatorProvider digests = new JcaDigestCalculatorProviderBuilder().build();
            CertificateID id = new CertificateID(digests.get(CertificateID.HASH_SHA1),
                    new JcaX509CertificateHolder(issuer), certificate.getSerialNumber());
            BasicOCSPRespBuilder builder = new JcaBasicOCSPRespBuilder(issuer.getPublicKey(),
                    digests.get(CertificateID.HASH_SHA1));
            Instant now = clock.instant();
            builder.addResponse(id, CertificateStatus.GOOD, Date.from(now.minus(5, ChronoUnit.MINUTES)),
                    Date.from(now.plus(STAPLE_VALIDITY)), null);
            String algorithm = issuerKey instanceof ECKey ? "SHA256withECDSA" : "SHA256withRSA";
            BasicOCSPResp basic = builder.build(new JcaContentSignerBuilder(algorithm).build(issuerKey),
                    new X509CertificateHolder[0], Date.from(now));
            OCSPResp response = new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basic);
            return Base64.getEncoder().encodeToString(response.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to staple " + certificate.getSubjectX500Principal(), e);
        }
    }

    static String sha1Fingerprint(X509Certificate certificate) {
        try {
            return HexFormat.of().withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint " + certificate.getSubjectX500Principal(), e);
        }
    }

    @Override
    public synchronized void clear() {
        keysByInstance.clear();
        signers.clear();
        intermediate = null;
    }
}
