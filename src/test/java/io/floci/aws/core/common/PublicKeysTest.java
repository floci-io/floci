package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicKeysTest {

    @Test
    void rsaKeyIsRebuiltFromItsModulusAndExponent() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey key = (RSAPublicKey) generator.generateKeyPair().getPublic();

        RSAPublicKey rebuilt = PublicKeys.rsa(key.getModulus(), key.getPublicExponent());

        assertArrayEquals(key.getEncoded(), rebuilt.getEncoded());
    }

    @Test
    void ecKeyIsRebuiltFromItsPointOnTheNamedCurve() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey key = (ECPublicKey) generator.generateKeyPair().getPublic();

        ECPublicKey rebuilt = PublicKeys.ec("secp256r1", key.getW().getAffineX(), key.getW().getAffineY());

        assertArrayEquals(key.getEncoded(), rebuilt.getEncoded());
    }

    @Test
    void namedCurveDescribesTheCurve() throws Exception {
        ECParameterSpec p256 = PublicKeys.namedCurve("secp256r1");

        assertEquals(256, p256.getOrder().bitLength());
        assertThrows(GeneralSecurityException.class, () -> PublicKeys.namedCurve("not-a-curve"));
    }
}
