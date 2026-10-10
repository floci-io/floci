package io.github.hectorvent.floci.services.cognito.verification;

import io.github.hectorvent.floci.services.kms.KmsService;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Encrypts the secrets Cognito hands to a custom sender Lambda trigger the way Cognito does: as an
 * AWS Encryption SDK message under the pool's KMS key, with the encryption context
 * {@code {"userpool-id": <pool id>}}, so a function decrypts it with the Encryption SDK's KMS
 * keyring or master key provider exactly as it would on AWS.
 *
 * <p>The message uses the SDK's default suite for a non-committing client, the one Cognito's
 * ciphertexts carry: format version 1, algorithm suite {@code 0x0378} (AES-256-GCM under a key
 * derived with HKDF-SHA384, signed with ECDSA P-384), one {@code aws-kms} encrypted data key from
 * {@code GenerateDataKey}, and the secret in a single final frame.
 */
public final class CustomSenderCodeEncryptor {

    public static final String USER_POOL_ID_CONTEXT_KEY = "userpool-id";

    private static final short ALGORITHM_SUITE_ID = 0x0378;
    private static final int MESSAGE_ID_LENGTH = 16;
    private static final int DATA_KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int FRAME_LENGTH = 4096;
    private static final int P384_FIELD_LENGTH = 48;
    // The Encryption SDK writes every P-384 signature at this length; DER encodings vary by a byte.
    private static final int SIGNATURE_LENGTH = 103;
    private static final int MAX_SIGNING_ATTEMPTS = 64;
    private static final String PUBLIC_KEY_CONTEXT_KEY = "aws-crypto-public-key";
    private static final byte[] KMS_PROVIDER_ID = "aws-kms".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINAL_FRAME_CONTENT = "AWSKMSEncryptionClient Final Frame"
            .getBytes(StandardCharsets.UTF_8);

    private final KmsService kms;
    private final SecureRandom random = new SecureRandom();

    public CustomSenderCodeEncryptor(KmsService kms) {
        this.kms = kms;
    }

    /** Returns the base64 Encryption SDK message that carries {@code secret}, as the event's {@code code}. */
    public String encrypt(String kmsKeyId, String userPoolId, String secret, String region) {
        try {
            KeyPair signingKey = signingKeyPair();
            Map<String, String> encryptionContext = new TreeMap<>();
            encryptionContext.put(USER_POOL_ID_CONTEXT_KEY, userPoolId);
            encryptionContext.put(PUBLIC_KEY_CONTEXT_KEY,
                    Base64.getEncoder().encodeToString(compressedPoint((ECPublicKey) signingKey.getPublic())));

            Map<String, Object> dataKey = kms.generateDataKey(kmsKeyId, "AES_256", null, encryptionContext, region);
            byte[] plaintextDataKey = (byte[]) dataKey.get("Plaintext");
            byte[] messageId = new byte[MESSAGE_ID_LENGTH];
            random.nextBytes(messageId);
            SecretKeySpec cipherKey = deriveKey(plaintextDataKey, messageId);
            Arrays.fill(plaintextDataKey, (byte) 0);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream message = new DataOutputStream(bytes);
            message.writeByte(1);
            message.writeByte(0x80);
            message.writeShort(ALGORITHM_SUITE_ID);
            message.write(messageId);
            byte[] serializedContext = serialize(encryptionContext);
            message.writeShort(serializedContext.length);
            message.write(serializedContext);
            message.writeShort(1);
            writeWithLength(message, KMS_PROVIDER_ID);
            writeWithLength(message, ((String) dataKey.get("KeyId")).getBytes(StandardCharsets.UTF_8));
            writeWithLength(message, (byte[]) dataKey.get("CiphertextBlob"));
            message.writeByte(2);
            message.writeInt(0);
            message.writeByte(IV_LENGTH);
            message.writeInt(FRAME_LENGTH);
            byte[] headerIv = new byte[IV_LENGTH];
            byte[] headerTag = seal(cipherKey, headerIv, bytes.toByteArray(), new byte[0]);
            message.write(headerIv);
            message.write(headerTag);

            byte[] content = secret.getBytes(StandardCharsets.UTF_8);
            int sequenceNumber = 1;
            byte[] frameIv = ByteBuffer.allocate(IV_LENGTH).putInt(IV_LENGTH - Integer.BYTES, sequenceNumber).array();
            byte[] frameAad = ByteBuffer.allocate(MESSAGE_ID_LENGTH + FINAL_FRAME_CONTENT.length + Integer.BYTES + Long.BYTES)
                    .put(messageId)
                    .put(FINAL_FRAME_CONTENT)
                    .putInt(sequenceNumber)
                    .putLong(content.length)
                    .array();
            message.writeInt(0xFFFFFFFF);
            message.writeInt(sequenceNumber);
            message.write(frameIv);
            message.writeInt(content.length);
            message.write(seal(cipherKey, frameIv, frameAad, content));

            byte[] signature = sign(signingKey.getPrivate(), bytes.toByteArray());
            message.writeShort(signature.length);
            message.write(signature);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt a custom sender code", e);
        }
    }

    private KeyPair signingKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"), random);
        return generator.generateKeyPair();
    }

    private static byte[] compressedPoint(ECPublicKey publicKey) {
        byte[] point = new byte[1 + P384_FIELD_LENGTH];
        point[0] = (byte) (publicKey.getW().getAffineY().testBit(0) ? 0x03 : 0x02);
        byte[] x = unsigned(publicKey.getW().getAffineX());
        System.arraycopy(x, 0, point, point.length - x.length, x.length);
        return point;
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes[0] == 0 && bytes.length > 1 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    /** HKDF-SHA384 with no salt and the suite ID and message ID as info, per format version 1. */
    private static SecretKeySpec deriveKey(byte[] dataKey, byte[] messageId) throws GeneralSecurityException {
        Mac extract = Mac.getInstance("HmacSHA384");
        extract.init(new SecretKeySpec(new byte[extract.getMacLength()], "HmacSHA384"));
        byte[] pseudoRandomKey = extract.doFinal(dataKey);
        Mac expand = Mac.getInstance("HmacSHA384");
        expand.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA384"));
        expand.update(ByteBuffer.allocate(Short.BYTES).putShort(ALGORITHM_SUITE_ID).array());
        expand.update(messageId);
        expand.update((byte) 1);
        return new SecretKeySpec(Arrays.copyOf(expand.doFinal(), DATA_KEY_LENGTH), "AES");
    }

    /** AES-GCM; returns the ciphertext followed by the tag. */
    private static byte[] seal(SecretKeySpec key, byte[] iv, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    private static byte[] sign(PrivateKey key, byte[] data) throws GeneralSecurityException {
        byte[] signature = null;
        for (int attempt = 0; attempt < MAX_SIGNING_ATTEMPTS
                && (signature == null || signature.length != SIGNATURE_LENGTH); attempt++) {
            Signature signer = Signature.getInstance("SHA384withECDSA");
            signer.initSign(key);
            signer.update(data);
            signature = signer.sign();
        }
        return signature;
    }

    /** The key-value pairs in ascending key order, each length-prefixed, after their count. */
    private static byte[] serialize(Map<String, String> sortedContext) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(sortedContext.size());
        for (Map.Entry<String, String> entry : sortedContext.entrySet()) {
            writeWithLength(out, entry.getKey().getBytes(StandardCharsets.UTF_8));
            writeWithLength(out, entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static void writeWithLength(DataOutputStream out, byte[] value) throws IOException {
        out.writeShort(value.length);
        out.write(value);
    }
}
