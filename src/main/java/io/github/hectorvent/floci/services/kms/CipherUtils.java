package io.github.hectorvent.floci.services.kms;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;

public final class CipherUtils {
    /** Decodes a PKCS#8-encoded RSA private key. */
    public static PrivateKey generateRsaPrivateKey(byte[] encodedKey) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
    }

    /** Derives the public key from the modulus and public exponent of an RSA CRT private key. */
    public static PublicKey generateRsaPublicKey(RSAPrivateCrtKey privateKey) throws GeneralSecurityException {
        RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
        return KeyFactory.getInstance("RSA")
                .generatePublic(publicKeySpec);
    }

    public static byte[] encryptRsaOaep(PublicKey key, String digest, byte[] plaintext) throws GeneralSecurityException {
        return rsaOaepCipher(Cipher.ENCRYPT_MODE, key, digest)
                .doFinal(plaintext);
    }

    public static byte[] decryptRsaOaep(PrivateKey key, String digest, byte[] ciphertext) throws GeneralSecurityException {
        return rsaOaepCipher(Cipher.DECRYPT_MODE, key, digest)
                .doFinal(ciphertext);
    }

    /**
     * Unwraps key material encrypted with an {@code RSA_AES_KEY_WRAP_*} algorithm.
     *
     * <p>The input contains an RSA-OAEP-encrypted AES-256 wrapping key followed by key material
     * encrypted with that key using AES-KWP.
     *
     * @param wrappingKey RSA private key issued for import
     * @param digest digest used by RSA-OAEP
     * @param encryptedKeyMaterial encrypted key material payload
     * @return key material
     * @throws GeneralSecurityException if the wrapping key or key material cannot be unwrapped
     */
    public static byte[] unwrapRsaAes(PrivateKey wrappingKey, String digest, byte[] encryptedKeyMaterial) throws GeneralSecurityException {
        if (!(wrappingKey instanceof RSAPrivateKey rsaPrivateKey)) {
            throw new IllegalArgumentException("Wrapping key is not RSA private key.");
        }

        int wrappedAesKeyLength = (rsaPrivateKey.getModulus().bitLength() + 7) / 8;
        if (encryptedKeyMaterial.length <= wrappedAesKeyLength) {
            throw new IllegalArgumentException("Encrypted key material is too short.");
        }
        byte[] wrappedAesKey = Arrays.copyOfRange(encryptedKeyMaterial, 0, wrappedAesKeyLength);
        byte[] wrappedMaterial = Arrays.copyOfRange(encryptedKeyMaterial, wrappedAesKeyLength, encryptedKeyMaterial.length);

        SecretKey aesKey = (SecretKey) CipherUtils.rsaOaepUnwrapAesKey(rsaPrivateKey, digest, Cipher.SECRET_KEY, wrappedAesKey);
        byte[] aesKeyBytes = aesKey.getEncoded();
        if (aesKeyBytes == null || aesKeyBytes.length != 32) {
            throw new InvalidKeyException("RSA_AES_KEY_WRAP_* requires a 256-bit AES key.");
        }
        return CipherUtils.aesKwpDecrypt(aesKey, wrappedMaterial);
    }

    private static Key rsaOaepUnwrapAesKey(PrivateKey privateKey, String digest, int wrappedKeyType, byte[] wrappedKey) throws GeneralSecurityException {
        return rsaOaepCipher(Cipher.UNWRAP_MODE, privateKey, digest)
                .unwrap(wrappedKey, "AES", wrappedKeyType);
    }

    private static byte[] aesKwpDecrypt(SecretKey aesKey, byte[] wrappedMaterial) throws GeneralSecurityException {
        return aesKwpCipher(Cipher.DECRYPT_MODE, aesKey).doFinal(wrappedMaterial);
    }

    private static Cipher rsaOaepCipher(int mode, Key key, String digest) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        OAEPParameterSpec parameterSpec = new OAEPParameterSpec(digest, "MGF1",
                new MGF1ParameterSpec(digest), PSource.PSpecified.DEFAULT);

        cipher.init(mode, key, parameterSpec);
        return cipher;
    }

    private static Cipher aesKwpCipher(int mode, SecretKey aesKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/KWP/NoPadding");
        cipher.init(mode, aesKey);
        return cipher;
    }
}
