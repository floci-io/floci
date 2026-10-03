package io.github.hectorvent.floci.services.kms.keytype;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import org.bouncycastle.asn1.ASN1BitString;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X962Parameters;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.WNafUtil;
import org.bouncycastle.math.ec.custom.gm.SM2P256V1Curve;
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve;
import org.bouncycastle.math.ec.custom.sec.SecP256R1Curve;
import org.bouncycastle.math.ec.custom.sec.SecP384R1Curve;
import org.bouncycastle.math.ec.custom.sec.SecP521R1Curve;
import org.bouncycastle.math.ec.endo.GLVTypeBEndomorphism;
import org.bouncycastle.math.ec.endo.GLVTypeBParameters;
import org.bouncycastle.math.ec.endo.ScalarSplitParameters;
import org.bouncycastle.util.encoders.Hex;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Map;

// Each curve is built from its own BouncyCastle class. A lookup through BouncyCastle's named curve
// tables would keep every curve those tables know in the native image.
final class BcEcKeys {

    private static final Map<String, NamedCurve> CURVES = Map.of(
            // G from SEC 2, the same point as NIST P-256
            "secp256r1", new NamedCurve(SECObjectIdentifiers.secp256r1, new SecP256R1Curve(),
                    "046B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"
                            + "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"),
            // G from SEC 2, the same point as NIST P-384
            "secp384r1", new NamedCurve(SECObjectIdentifiers.secp384r1, new SecP384R1Curve(),
                    "04AA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B9859F741E082542A385502F25DBF55296C3A545E3872760AB7"
                            + "3617DE4A96262C6F5D9E98BF9292DC29F8F41DBD289A147CE9DA3113B5F0B8C00A60B1CE1D7E819D7A431D7C90EA0E5F"),
            // G from SEC 2, the same point as NIST P-521
            "secp521r1", new NamedCurve(SECObjectIdentifiers.secp521r1, new SecP521R1Curve(),
                    "0400C6858E06B70404E9CD9E3ECB662395B4429C648139053FB521F828AF606B4D3DBAA14B5E77EFE7"
                            + "5928FE1DC127A2FFA8DE3348B3C1856A429BF97E7E31C2E5BD66"
                            + "011839296A789A3BC0045C8A5FB42C7D1BD998F54449579B446817AFBD17273E662C97EE72995EF4"
                            + "2640C550B9013FAD0761353C7086A272C24088BE94769FD16650"),
            // G from SEC 2
            "secp256k1", new NamedCurve(SECObjectIdentifiers.secp256k1, secp256k1Curve(),
                    "0479BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798"
                            + "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8"),
            // G from GB/T 32918.5
            "sm2p256v1", new NamedCurve(GMObjectIdentifiers.sm2p256v1, new SM2P256V1Curve(),
                    "0432C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7"
                            + "BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0"));

    private BcEcKeys() {
    }

    static void generateKeyPair(KmsKey key, String curveName, SecureRandom random)
            throws GeneralSecurityException {
        NamedCurve curve = curve(curveName);
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(curve.domain(), random));
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        BigInteger privateScalar = ((ECPrivateKeyParameters) pair.getPrivate()).getD();
        ECPoint publicPoint = ((ECPublicKeyParameters) pair.getPublic()).getQ();
        DERBitString publicKey = new DERBitString(publicPoint.getEncoded(false));
        ECPrivateKey privateKey = new ECPrivateKey(curve.domain().getN().bitLength(), privateScalar, publicKey,
                curve.parameters());
        try {
            store(key, new PrivateKeyInfo(curve.algorithm(), privateKey).getEncoded(ASN1Encoding.DER),
                    new SubjectPublicKeyInfo(curve.algorithm(), publicKey).getEncoded(ASN1Encoding.DER));
        } catch (IOException e) {
            throw new GeneralSecurityException("Could not encode the " + curveName + " key pair", e);
        }
    }

    static ECPrivateKeyParameters privateKeyParameters(KmsKey key, String curveName) throws IOException {
        byte[] decoded = Base64.getDecoder().decode(key.getPrivateKeyEncoded());
        PrivateKeyInfo info = PrivateKeyInfo.getInstance(decoded);
        BigInteger privateScalar = ECPrivateKey.getInstance(info.parsePrivateKey()).getKey();
        return new ECPrivateKeyParameters(privateScalar, curve(curveName).domain());
    }

    static ECPublicKeyParameters publicKeyParameters(KmsKey key, String curveName) {
        byte[] decoded = Base64.getDecoder().decode(key.getPublicKeyEncoded());
        ECDomainParameters domain = curve(curveName).domain();
        byte[] point = SubjectPublicKeyInfo.getInstance(decoded).getPublicKeyData().getOctets();
        return new ECPublicKeyParameters(domain.getCurve().decodePoint(point), domain);
    }

    /**
     * Stores imported ECC key material: a PKCS#8 private key (RFC 5208) holding an RFC 5915
     * ECPrivateKey on the key spec's named curve. As on AWS, only the private key is taken from
     * the material and the public key is derived from it; an embedded public key must match.
     */
    static void importPrivateKey(KmsKey key, byte[] material, String curveName) {
        try {
            ImportedEcKey imported = parseImportedPrivateKey(material, curve(curveName), curveName);
            store(key, imported.privateKeyEncoded(), imported.publicKeyEncoded());
        } catch (InvalidKeySpecException e) {
            throw new AwsException("IncorrectKeyMaterialException",
                    "Key material for key spec " + key.getKeySpec() + " must be a PKCS#8-encoded "
                            + curveName + " private key: " + e.getMessage(), 400);
        }
    }

    private static ImportedEcKey parseImportedPrivateKey(byte[] material, NamedCurve curve, String curveName)
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
        if (!curve.oid().equals(algorithm.getParameters())) {
            throw new InvalidKeySpecException("the material must name the " + curveName + " curve");
        }
        if (innerParameters != null && !curve.oid().equals(innerParameters)) {
            throw new InvalidKeySpecException("the EC private key's own parameters must also name the "
                    + curveName + " curve");
        }

        ECDomainParameters domain = curve.domain();
        if (privateScalar.signum() <= 0 || privateScalar.compareTo(domain.getN()) >= 0) {
            throw new InvalidKeySpecException("the private key is out of range for " + curveName);
        }
        ECPoint publicPoint = domain.getG().multiply(privateScalar).normalize();
        requireMatchingEmbeddedPublicKey(embeddedPublicKey, publicPoint, domain.getCurve());

        try {
            SubjectPublicKeyInfo publicKeyInfo = new SubjectPublicKeyInfo(curve.algorithm(),
                    publicPoint.getEncoded(false));
            return new ImportedEcKey(privateKeyInfo.getEncoded(ASN1Encoding.DER),
                    publicKeyInfo.getEncoded(ASN1Encoding.DER));
        } catch (IOException e) {
            throw new InvalidKeySpecException("the key could not be re-encoded", e);
        }
    }

    private static void requireMatchingEmbeddedPublicKey(ASN1BitString embedded, ECPoint derived, ECCurve curve)
            throws InvalidKeySpecException {
        if (embedded == null) {
            return;
        }
        ECPoint embeddedPoint;
        try {
            embeddedPoint = curve.decodePoint(embedded.getOctets());
        } catch (RuntimeException e) {
            throw new InvalidKeySpecException("the embedded public key is not a point on the curve", e);
        }
        if (!embeddedPoint.equals(derived)) {
            throw new InvalidKeySpecException("the embedded public key does not match the private key");
        }
    }

    private static void store(KmsKey key, byte[] privateKeyEncoded, byte[] publicKeyEncoded) {
        Base64.Encoder encoder = Base64.getEncoder();
        key.setPrivateKeyEncoded(encoder.encodeToString(privateKeyEncoded));
        key.setPublicKeyEncoded(encoder.encodeToString(publicKeyEncoded));
    }

    private static NamedCurve curve(String curveName) {
        NamedCurve curve = CURVES.get(curveName);
        if (curve == null) {
            throw new IllegalArgumentException("Unsupported curve: " + curveName);
        }
        return curve;
    }

    // The GLV endomorphism BouncyCastle's CustomNamedCurves gives secp256k1. It speeds up verification.
    private static ECCurve secp256k1Curve() {
        GLVTypeBParameters glv = new GLVTypeBParameters(
                new BigInteger("7ae96a2b657c07106e64479eac3434e99cf0497512f58995c1396c28719501ee", 16),
                new BigInteger("5363ad4cc05c30e0a5261c028812645a122e22ea20816678df02967c1b23bd72", 16),
                new ScalarSplitParameters(
                        new BigInteger[]{
                                new BigInteger("3086d221a7d46bcde86c90e49284eb15", 16),
                                new BigInteger("-e4437ed6010e88286f547fa90abfe4c3", 16)},
                        new BigInteger[]{
                                new BigInteger("114ca50f7a8e2f3f657c1108d9d44cfd8", 16),
                                new BigInteger("3086d221a7d46bcde86c90e49284eb15", 16)},
                        new BigInteger("3086d221a7d46bcde86c90e49284eb153dab", 16),
                        new BigInteger("e4437ed6010e88286f547fa90abfe4c42212", 16),
                        272));
        ECCurve curve = new SecP256K1Curve();
        return curve.configure().setEndomorphism(new GLVTypeBEndomorphism(curve, glv)).create();
    }

    private record ImportedEcKey(byte[] privateKeyEncoded, byte[] publicKeyEncoded) {
    }

    private record NamedCurve(ASN1ObjectIdentifier oid, ECDomainParameters domain) {

        NamedCurve(ASN1ObjectIdentifier oid, ECCurve curve, String generator) {
            this(oid, domain(curve, generator));
        }

        private static ECDomainParameters domain(ECCurve curve, String generator) {
            ECPoint g = curve.decodePoint(Hex.decodeStrict(generator));
            WNafUtil.configureBasepoint(g);
            return new ECDomainParameters(curve, g, curve.getOrder(), curve.getCofactor());
        }

        X962Parameters parameters() {
            return new X962Parameters(oid);
        }

        AlgorithmIdentifier algorithm() {
            return new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, parameters());
        }
    }
}
