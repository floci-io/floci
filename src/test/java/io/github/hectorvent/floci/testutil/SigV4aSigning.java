package io.github.hectorvent.floci.testutil;

import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.auth.SigV4aSignature;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.util.encoders.Hex;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Signs a SigV4A string to sign the way a client does: a DER-encoded ECDSA P-256 signature, hex
 * encoded, by the key pair derived from the secret. Shared by the test signers of the services
 * that take SigV4A.
 */
public final class SigV4aSigning {

    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256r1");
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
            CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());

    private SigV4aSigning() {
    }

    public static String sign(String accessKeyId, String secretKey, String stringToSign) {
        BigInteger privateScalar = SigV4aSignature.derivePrivateScalar(secretKey, accessKeyId);
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, new ECPrivateKeyParameters(privateScalar, DOMAIN));
        BigInteger[] rs = signer.generateSignature(Digests.sha256(stringToSign.getBytes(StandardCharsets.UTF_8)));
        ASN1EncodableVector sequence = new ASN1EncodableVector();
        sequence.add(new ASN1Integer(rs[0]));
        sequence.add(new ASN1Integer(rs[1]));
        try {
            return Hex.toHexString(new DERSequence(sequence).getEncoded());
        } catch (IOException e) {
            throw new IllegalStateException("could not encode the signature", e);
        }
    }
}
