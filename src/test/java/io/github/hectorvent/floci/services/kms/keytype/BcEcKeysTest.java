package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.services.kms.model.KmsKey;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BcEcKeysTest {

    @ParameterizedTest
    @ValueSource(strings = {"secp256r1", "secp384r1", "secp521r1", "secp256k1", "sm2p256v1"})
    void generatedKeyMatchesBouncyCastleNamedCurve(String curveName) throws Exception {
        KmsKey key = new KmsKey();
        BcEcKeys.generateKeyPair(key, curveName, new SecureRandom());
        byte[] privateEncoded = Base64.getDecoder().decode(key.getPrivateKeyEncoded());
        byte[] publicEncoded = Base64.getDecoder().decode(key.getPublicKeyEncoded());

        KeyFactorySpi.EC factory = new KeyFactorySpi.EC();
        BCECPrivateKey privateKey = (BCECPrivateKey) factory.generatePrivate(PrivateKeyInfo.getInstance(privateEncoded));
        BCECPublicKey publicKey = (BCECPublicKey) factory.generatePublic(SubjectPublicKeyInfo.getInstance(publicEncoded));
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(curveName);
        assertEquals(spec.getG().multiply(privateKey.getD()).normalize(), publicKey.getQ());
        assertArrayEquals(privateKey.getEncoded(), privateEncoded);
        assertArrayEquals(publicKey.getEncoded(), publicEncoded);

        ECPrivateKeyParameters privateParameters = BcEcKeys.privateKeyParameters(key, curveName);
        ECPublicKeyParameters publicParameters = BcEcKeys.publicKeyParameters(key, curveName);
        assertEquals(privateKey.getD(), privateParameters.getD());
        assertEquals(publicKey.getQ(), publicParameters.getQ());
        assertEquals(spec.getCurve(), privateParameters.getParameters().getCurve());
        assertEquals(spec.getG(), privateParameters.getParameters().getG());
        assertEquals(spec.getN(), privateParameters.getParameters().getN());
        assertEquals(spec.getH(), privateParameters.getParameters().getH());
    }
}
