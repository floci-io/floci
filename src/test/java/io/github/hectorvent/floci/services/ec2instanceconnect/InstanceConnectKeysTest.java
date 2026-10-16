package io.github.hectorvent.floci.services.ec2instanceconnect;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceConnectKeysTest {

    private static final String REGION = "us-east-1";
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl test";

    @TempDir
    Path tlsDir;

    private FlociCertificateAuthority root;
    private MutableClock clock;
    private InstanceConnectKeys keys;

    @BeforeEach
    void setUp() {
        root = FlociCertificateAuthority.loadOrCreate(tlsDir);
        clock = new MutableClock(NOW);
        keys = new InstanceConnectKeys(() -> root, new CertificateGenerator(), clock);
    }

    @Test
    void chainIsSignerThenIntermediateThenRootWithTheAgentsExpectedSignerName() throws Exception {
        List<X509Certificate> chain = parseChain(keys.signerCertificateChain(REGION));

        assertEquals(3, chain.size());
        assertEquals("CN=managed-ssh-signer.us-east-1.amazonaws.com", chain.get(0).getSubjectX500Principal().getName());
        assertEquals("CN=" + InstanceConnectKeys.INTERMEDIATE_CN, chain.get(1).getSubjectX500Principal().getName());
        assertEquals(root.certificate(), chain.get(2));
        chain.get(0).verify(chain.get(1).getPublicKey());
        chain.get(1).verify(root.certificate().getPublicKey());
        assertTrue(chain.get(1).getBasicConstraints() >= 0, "the intermediate is a CA");
        assertEquals(-1, chain.get(0).getBasicConstraints(), "the signer is not");
        assertEquals("Floci_Local_CA", keys.trustAnchorFileStem());
    }

    @Test
    void eachNonRootCertificateHasAGoodStapleSignedByItsIssuer() throws Exception {
        List<X509Certificate> chain = parseChain(keys.signerCertificateChain(REGION));
        Map<String, String> staples = keys.ocspStaples(REGION);

        assertEquals(2, staples.size());
        for (int i = 0; i < 2; i++) {
            X509Certificate cert = chain.get(i);
            X509Certificate issuer = chain.get(i + 1);
            String staple = staples.get(InstanceConnectKeys.sha1Fingerprint(cert));
            BasicOCSPResp basic = (BasicOCSPResp) new OCSPResp(Base64.getDecoder().decode(staple)).getResponseObject();
            assertTrue(basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(issuer.getPublicKey())));
            SingleResp single = basic.getResponses()[0];
            assertNull(single.getCertStatus(), "null is OCSP 'good'");
            CertificateID expected = new CertificateID(new JcaDigestCalculatorProviderBuilder().build()
                    .get(CertificateID.HASH_SHA1), new JcaX509CertificateHolder(issuer), cert.getSerialNumber());
            assertEquals(expected, single.getCertID());
        }
    }

    @Test
    void activeKeysAreSignedWithRsaPssOverTheHeaderAndKeyLines() throws Exception {
        keys.add(new InstanceConnectKeys.ActiveKey("i-0123456789abcdef0", "admin", KEY,
                NOW.plus(InstanceConnectKeys.KEY_LIFETIME), "arn:aws:iam::000000000000:root", "req-1"));

        String document = keys.activeKeysDocument(REGION, "i-0123456789abcdef0", "admin").orElseThrow();
        String[] lines = document.split("\n", -1);
        assertEquals("#Timestamp=" + NOW.plusSeconds(60).getEpochSecond(), lines[0]);
        assertEquals("#Instance=i-0123456789abcdef0", lines[1]);
        assertEquals(KEY, lines[4]);
        assertEquals("", lines[6], "a blank line ends each entry");

        String signed = String.join("\n", List.of(lines).subList(0, 5)) + "\n";
        X509Certificate signer = parseChain(keys.signerCertificateChain(REGION)).getFirst();
        Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        verifier.initVerify(signer.getPublicKey());
        verifier.update(signed.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(lines[5])));
    }

    @Test
    void keysAreScopedToTheirUserAndExpireAfterSixtySeconds() {
        keys.add(new InstanceConnectKeys.ActiveKey("i-0123456789abcdef0", "admin", KEY,
                NOW.plus(InstanceConnectKeys.KEY_LIFETIME), "caller", "req-1"));

        assertTrue(keys.activeKeysDocument(REGION, "i-0123456789abcdef0", "root").isEmpty());
        assertTrue(keys.activeKeysDocument(REGION, "i-0000000000000000f", "admin").isEmpty());
        clock.now = NOW.plusSeconds(60);
        assertTrue(keys.activeKeysDocument(REGION, "i-0123456789abcdef0", "admin").isEmpty());
    }

    @Test
    void expiredKeysAreDroppedWhenTheNextKeyArrives() {
        keys.add(new InstanceConnectKeys.ActiveKey("i-0123456789abcdef0", "admin", KEY,
                NOW.plus(InstanceConnectKeys.KEY_LIFETIME), "caller", "req-1"));
        clock.now = NOW.plusSeconds(61);
        keys.add(new InstanceConnectKeys.ActiveKey("i-0000000000000000f", "admin", KEY,
                clock.now.plus(InstanceConnectKeys.KEY_LIFETIME), "caller", "req-2"));

        assertEquals(1, keys.trackedInstanceCount(), "the first instance's expired key is not kept");
    }

    private static List<X509Certificate> parseChain(String pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();
        factory.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))
                .forEach(cert -> chain.add((X509Certificate) cert));
        return chain;
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
