package com.floci.test;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.util.io.pem.PemReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateKeyPairResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.KeyFormat;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;
import software.amazon.awssdk.services.ec2.model.KeyType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EC2 CreateKeyPair KeyType and KeyFormat")
class Ec2CreateKeyPairTest {

    @Test
    @DisplayName("KeyType ed25519 returns an OpenSSH ED25519 private key fingerprinted with SHA-256")
    void ed25519ReturnsAnOpenSshPrivateKey() throws Exception {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String name = TestFixtures.uniqueName("ed25519-key");
            CreateKeyPairResponse created = ec2.createKeyPair(r -> r.keyName(name).keyType(KeyType.ED25519));
            try {
                assertThat(created.keyMaterial()).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n");
                Ed25519PrivateKeyParameters key;
                try (PemReader reader = new PemReader(new StringReader(created.keyMaterial()))) {
                    key = (Ed25519PrivateKeyParameters) OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(
                            reader.readPemObject().getContent());
                }
                byte[] blob = OpenSSHPublicKeyUtil.encodePublicKey(key.generatePublicKey());

                assertThat(created.keyFingerprint())
                        .isEqualTo(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob)));
                assertThat(describedPublicKey(ec2, created.keyPairId(), KeyType.ED25519))
                        .isEqualTo("ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) + " " + name);
            } finally {
                ec2.deleteKeyPair(r -> r.keyPairId(created.keyPairId()));
            }
        }
    }

    @Test
    @DisplayName("KeyFormat ppk returns a PuTTY version 2 key file of the key, named in its comment")
    void ppkReturnsAPuttyKeyFile() throws Exception {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            for (KeyType keyType : List.of(KeyType.RSA, KeyType.ED25519)) {
                String name = TestFixtures.uniqueName("ppk-key");
                CreateKeyPairResponse created = ec2.createKeyPair(r -> r.keyName(name).keyType(keyType)
                        .keyFormat(KeyFormat.PPK));
                try {
                    String algorithm = keyType == KeyType.RSA ? "ssh-rsa" : "ssh-ed25519";
                    List<String> lines = created.keyMaterial().lines().toList();
                    assertThat(lines.subList(0, 3)).containsExactly("PuTTY-User-Key-File-2: " + algorithm,
                            "Encryption: none", "Comment: " + name);

                    int publicLines = Integer.parseInt(lines.get(3).substring("Public-Lines: ".length()));
                    byte[] publicBlob = Base64.getDecoder().decode(String.join("", lines.subList(4, 4 + publicLines)));
                    int privateHeader = 4 + publicLines;
                    int privateLines = Integer.parseInt(lines.get(privateHeader).substring("Private-Lines: ".length()));
                    byte[] privateBlob = Base64.getDecoder().decode(
                            String.join("", lines.subList(privateHeader + 1, privateHeader + 1 + privateLines)));
                    String mac = lines.get(privateHeader + 1 + privateLines).substring("Private-MAC: ".length());

                    assertThat(mac).isEqualTo(puttyMac(algorithm, name, publicBlob, privateBlob));
                    assertThat(describedPublicKey(ec2, created.keyPairId(), keyType))
                            .isEqualTo(algorithm + " " + Base64.getEncoder().encodeToString(publicBlob) + " " + name);
                    if (keyType == KeyType.ED25519) {
                        assertThat(created.keyFingerprint()).isEqualTo(Base64.getEncoder().encodeToString(
                                MessageDigest.getInstance("SHA-256").digest(publicBlob)));
                    } else {
                        assertThat(created.keyFingerprint()).matches("([0-9a-f]{2}:){19}[0-9a-f]{2}");
                    }
                } finally {
                    ec2.deleteKeyPair(r -> r.keyPairId(created.keyPairId()));
                }
            }
        }
    }

    @Test
    @DisplayName("A KeyType or KeyFormat outside its enum is InvalidParameterValue")
    void unknownTypeOrFormatIsRefused() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String name = TestFixtures.uniqueName("bad-key");

            assertThatThrownBy(() -> ec2.createKeyPair(r -> r.keyName(name).keyType("ecdsa")))
                    .isInstanceOfSatisfying(Ec2Exception.class, e -> {
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");
                        assertThat(e.awsErrorDetails().errorMessage()).isEqualTo("1 validation error detected: "
                                + "Value 'ecdsa' at 'keyType' failed to satisfy constraint: "
                                + "Member must satisfy enum value set: [rsa, ed25519]");
                    });
            assertThatThrownBy(() -> ec2.createKeyPair(r -> r.keyName(name).keyFormat("der")))
                    .isInstanceOfSatisfying(Ec2Exception.class, e -> {
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");
                        assertThat(e.awsErrorDetails().errorMessage()).isEqualTo("1 validation error detected: "
                                + "Value 'der' at 'keyFormat' failed to satisfy constraint: "
                                + "Member must satisfy enum value set: [ppk, pem]");
                    });
            assertThatThrownBy(() -> ec2.describeKeyPairs(r -> r.keyNames(name)))
                    .isInstanceOfSatisfying(Ec2Exception.class, e ->
                            assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidKeyPair.NotFound"));
        }
    }

    @Test
    @DisplayName("DryRun is answered before the key type is checked, and creates nothing")
    void dryRunIsAnsweredFirst() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String name = TestFixtures.uniqueName("dry-run-key");

            assertThatThrownBy(() -> ec2.createKeyPair(r -> r.keyName(name).keyType("ecdsa").dryRun(true)))
                    .isInstanceOfSatisfying(Ec2Exception.class, e -> {
                        assertThat(e.statusCode()).isEqualTo(412);
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("DryRunOperation");
                    });
            assertThatThrownBy(() -> ec2.describeKeyPairs(r -> r.keyNames(name)))
                    .isInstanceOfSatisfying(Ec2Exception.class, e ->
                            assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidKeyPair.NotFound"));
        }
    }

    /**
     * The described public key, without the line break AWS ends it with and Floci does not, once the
     * key pair is seen described with the type it was created with.
     */
    private static String describedPublicKey(Ec2Client ec2, String keyPairId, KeyType keyType) {
        KeyPairInfo described = ec2.describeKeyPairs(r -> r.keyPairIds(keyPairId).includePublicKey(true))
                .keyPairs().get(0);
        assertThat(described.keyType()).isEqualTo(keyType);
        return described.publicKey().strip();
    }

    /** PuTTY's version 2 MAC of an unencrypted key file, as its sshpubk.c computes it. */
    private static String puttyMac(String algorithm, String comment, byte[] publicBlob, byte[] privateBlob)
            throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (byte[] field : List.of(algorithm.getBytes(StandardCharsets.UTF_8), "none".getBytes(StandardCharsets.UTF_8),
                comment.getBytes(StandardCharsets.UTF_8), publicBlob, privateBlob)) {
            data.writeBytes(ByteBuffer.allocate(4).putInt(field.length).array());
            data.writeBytes(field);
        }
        byte[] key = MessageDigest.getInstance("SHA-1")
                .digest("putty-private-key-file-mac-key".getBytes(StandardCharsets.US_ASCII));
        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(key, "HmacSHA1"));
        return HexFormat.of().formatHex(hmac.doFinal(data.toByteArray()));
    }
}
