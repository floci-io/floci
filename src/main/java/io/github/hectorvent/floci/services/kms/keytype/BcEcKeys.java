package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import org.bouncycastle.asn1.ASN1BitString;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyPairGeneratorSpi;
import org.bouncycastle.jcajce.provider.asymmetric.util.ECUtil;
import org.bouncycastle.jcajce.provider.util.AsymmetricKeyInfoConverter;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

// The BouncyCastle SPI classes are allocated directly. JCA lookup cannot find them in the native image.
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

    /**
     * Stores imported ECC key material: a PKCS#8 private key (RFC 5208) holding an RFC 5915
     * ECPrivateKey on the key spec's named curve. As on AWS, only the private key is taken from
     * the material and the public key is derived from it; an embedded public key must match.
     */
    static void importPrivateKey(KmsKey key, byte[] material, String curveName) {
        try {
            ImportedEcKey imported = parseImportedPrivateKey(material, curveName);
            Base64.Encoder encoder = Base64.getEncoder();
            key.setPrivateKeyEncoded(encoder.encodeToString(imported.privateKeyEncoded()));
            key.setPublicKeyEncoded(encoder.encodeToString(imported.publicKeyEncoded()));
        } catch (InvalidKeySpecException e) {
            throw new AwsException("IncorrectKeyMaterialException",
                    "Key material for key spec " + key.getKeySpec() + " must be a PKCS#8-encoded "
                            + curveName + " private key: " + e.getMessage(), 400);
        }
    }

    private static ImportedEcKey parseImportedPrivateKey(byte[] material, String curveName)
            throws InvalidKeySpecException {
        // BouncyCastle decodes the ECPrivateKey fields lazily, so a malformed field only fails when it
        // is read: read them all here, where a failure maps to IncorrectKeyMaterialException.
        PrivateKeyInfo privateKeyInfo;
        BigInteger privateScalar;
        ASN1Object innerParameters;
        ASN1BitString embeddedPublicKey;
        try {
            privateKeyInfo = PrivateKeyInfo.getInstance(material);
            ECPrivateKey ecPrivateKey = ECPrivateKey.getInstance(privateKeyInfo.parsePrivateKey());
            privateScalar = ecPrivateKey.getKey();
            innerParameters = ecPrivateKey.getParametersObject();
            embeddedPublicKey = ecPrivateKey.getPublicKey();
        } catch (IOException | RuntimeException e) {
            throw new InvalidKeySpecException("the material is not a PKCS#8 EC private key", e);
        }

        AlgorithmIdentifier algorithm = privateKeyInfo.getPrivateKeyAlgorithm();
        if (!X9ObjectIdentifiers.id_ecPublicKey.equals(algorithm.getAlgorithm())) {
            throw new InvalidKeySpecException("the material is not an EC private key");
        }
        ASN1ObjectIdentifier curveOid = ECUtil.getNamedCurveOid(curveName);
        if (!curveOid.equals(algorithm.getParameters())) {
            throw new InvalidKeySpecException("the material must name the " + curveName + " curve");
        }
        if (innerParameters != null && !curveOid.equals(innerParameters)) {
            throw new InvalidKeySpecException("the EC private key's own parameters must also name the "
                    + curveName + " curve");
        }

        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(curveName);
        if (privateScalar.signum() <= 0 || privateScalar.compareTo(spec.getN()) >= 0) {
            throw new InvalidKeySpecException("the private key is out of range for " + curveName);
        }
        ECPoint publicPoint = spec.getG().multiply(privateScalar).normalize();
        requireMatchingEmbeddedPublicKey(embeddedPublicKey, publicPoint, spec);

        try {
            SubjectPublicKeyInfo publicKeyInfo = new SubjectPublicKeyInfo(
                    new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, curveOid),
                    publicPoint.getEncoded(false));
            return new ImportedEcKey(privateKeyInfo.getEncoded(ASN1Encoding.DER),
                    publicKeyInfo.getEncoded(ASN1Encoding.DER));
        } catch (IOException e) {
            throw new InvalidKeySpecException("the key could not be re-encoded", e);
        }
    }

    private static void requireMatchingEmbeddedPublicKey(ASN1BitString embedded, ECPoint derived,
                                                         ECNamedCurveParameterSpec spec)
            throws InvalidKeySpecException {
        if (embedded == null) {
            return;
        }
        ECPoint embeddedPoint;
        try {
            embeddedPoint = spec.getCurve().decodePoint(embedded.getOctets());
        } catch (RuntimeException e) {
            throw new InvalidKeySpecException("the embedded public key is not a point on the curve", e);
        }
        if (!embeddedPoint.equals(derived)) {
            throw new InvalidKeySpecException("the embedded public key does not match the private key");
        }
    }

    private record ImportedEcKey(byte[] privateKeyEncoded, byte[] publicKeyEncoded) {
    }

    private static ECDomainParameters domainParameters(String curveName) {
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(curveName);
        return new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
    }
}
