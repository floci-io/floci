package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.services.kms.model.KmsKey;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyPairGeneratorSpi;
import org.bouncycastle.jcajce.provider.util.AsymmetricKeyInfoConverter;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * EC keys on curves the JDK lacks, through BouncyCastle SPI classes allocated directly. JCA's
 * ClassLoader.loadClass cannot find BC SPI classes in GraalVM native image unless they are
 * allocated directly in code (GraalVM escape analysis eliminates unused allocations, keeping
 * them out of the native image type registry).
 */
final class BcEcKeys {

    private BcEcKeys() {
    }

    static KeyPair generateKeyPair(String curveName) throws InvalidAlgorithmParameterException {
        KeyPairGenerator generator = new KeyPairGeneratorSpi.EC();
        generator.initialize(new ECGenParameterSpec(curveName));
        return generator.generateKeyPair();
    }

    static ECPrivateKeyParameters privateKeyParameters(KmsKey key, String curveName) throws IOException {
        AsymmetricKeyInfoConverter converter = new KeyFactorySpi.EC();
        byte[] decoded = Base64.getDecoder().decode(key.getPrivateKeyEncoded());
        BCECPrivateKey privateKey = (BCECPrivateKey) converter.generatePrivate(PrivateKeyInfo.getInstance(decoded));
        return new ECPrivateKeyParameters(privateKey.getD(), domainParameters(curveName));
    }

    static ECPublicKeyParameters publicKeyParameters(KmsKey key, String curveName) throws IOException {
        AsymmetricKeyInfoConverter converter = new KeyFactorySpi.EC();
        byte[] decoded = Base64.getDecoder().decode(key.getPublicKeyEncoded());
        BCECPublicKey publicKey = (BCECPublicKey) converter.generatePublic(SubjectPublicKeyInfo.getInstance(decoded));
        return new ECPublicKeyParameters(publicKey.getQ(), domainParameters(curveName));
    }

    private static ECDomainParameters domainParameters(String curveName) {
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(curveName);
        return new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
    }
}
