package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EC2 DescribeKeyPairs IncludePublicKey")
class Ec2KeyPairPublicKeyTest {

    /** A throwaway ed25519 public key, imported under a comment that the description replaces. */
    private static final String ED25519_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIIj7azlDjVHAsSxgcpCRZ3oWnTm0nAFM64y9jd22ioI/";

    @Test
    @DisplayName("DescribeKeyPairs returns the public key only when IncludePublicKey is true")
    void describeKeyPairsReturnsThePublicKeyOnlyWhenAsked() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String name = TestFixtures.uniqueName("public-key");
            String keyPairId = ec2.createKeyPair(r -> r.keyName(name)).keyPairId();
            try {
                KeyPairInfo without = ec2.describeKeyPairs(r -> r.keyPairIds(keyPairId)).keyPairs().get(0);
                KeyPairInfo with = ec2.describeKeyPairs(r -> r.keyPairIds(keyPairId).includePublicKey(true))
                        .keyPairs().get(0);

                assertThat(without.publicKey()).isNull();
                assertThat(with.publicKey()).startsWith("ssh-rsa ").endsWith(" " + name);
            } finally {
                ec2.deleteKeyPair(r -> r.keyPairId(keyPairId));
            }
        }
    }

    @Test
    @DisplayName("An imported key is described with the key-pair name as its comment")
    void anImportedKeyIsDescribedWithTheKeyPairNameAsItsComment() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String name = TestFixtures.uniqueName("public-key");
            String keyPairId = ec2.importKeyPair(r -> r.keyName(name)
                    .publicKeyMaterial(SdkBytes.fromUtf8String(ED25519_KEY + " someone@example.com")))
                    .keyPairId();
            try {
                KeyPairInfo with = ec2.describeKeyPairs(r -> r.keyNames(name).includePublicKey(true))
                        .keyPairs().get(0);

                assertThat(with.publicKey()).isEqualTo(ED25519_KEY + " " + name);
            } finally {
                ec2.deleteKeyPair(r -> r.keyPairId(keyPairId));
            }
        }
    }
}
