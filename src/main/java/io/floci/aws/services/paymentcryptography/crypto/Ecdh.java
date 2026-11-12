package io.floci.aws.services.paymentcryptography.crypto;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * The elliptic curve key agreement the key exchange derives its shared secret with.
 *
 * <p>The keys are the ones the service holds, which are the JDK's key objects. They are
 * turned into the library's own parameters through their encodings, which carry the curve
 * by name, so the two parties' keys are read on the same curve rather than on two curves
 * that happen to look alike.
 */
public final class Ecdh {

    private Ecdh() {
    }

    /**
     * The secret the two parties agree.
     *
     * @param privateKey the service's own private key
     * @param counterpartyKey the other party's public key
     * @return the shared secret, the x coordinate of the agreed point
     * @throws GeneralSecurityException when the two keys are not on the same curve
     */
    public static byte[] agree(ECPrivateKey privateKey, ECPublicKey counterpartyKey)
            throws GeneralSecurityException {
        try {
            ECPrivateKeyParameters own = (ECPrivateKeyParameters) PrivateKeyFactory
                    .createKey(PrivateKeyInfo.getInstance(privateKey.getEncoded()));
            ECPublicKeyParameters other = (ECPublicKeyParameters) PublicKeyFactory
                    .createKey(SubjectPublicKeyInfo.getInstance(counterpartyKey.getEncoded()));
            ECDHBasicAgreement agreement = new ECDHBasicAgreement();
            agreement.init(own);
            // The agreed point's x coordinate, in as many bytes as the field is wide.
            BigInteger x = agreement.calculateAgreement(other);
            int size = agreement.getFieldSize();
            byte[] raw = x.toByteArray();
            byte[] secret = new byte[size];
            int from = Math.max(0, raw.length - size);
            System.arraycopy(raw, from, secret, size - (raw.length - from), raw.length - from);
            return secret;
        } catch (IOException | ClassCastException | IllegalArgumentException
                | IllegalStateException e) {
            // The library raises IllegalStateException when the two keys are on different
            // curves, which is a refusal and not a crash.
            throw new GeneralSecurityException("the key agreement failed: " + e.getMessage(), e);
        }
    }
}
