package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;
import software.amazon.awssdk.services.ec2.model.KeyType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EC2 ImportKeyPair validation and DescribeKeyPairs keyType, createTime and filters")
class Ec2KeyPairImportAndDescribeTest {

    /** A throwaway ed25519 public key. */
    private static final String ED25519_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII0fBPBUZHaEOBc2mySfmI5btu4mkvFfNRujmF7RH2fj";

    /** A throwaway ECDSA public key from ssh-keygen; EC2 imports RSA and ED25519 keys only. */
    private static final String ECDSA_KEY = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABB"
            + "BFaxjAHsoCE0FmjbKLGzgVRKfOKFmYj1/JpnSjsuLjy23mHIUPkLJ5thgE47igHp+KufC1V2KqJE4OP5w4EK9SU= throwaway";

    @Test
    @DisplayName("Created and imported key pairs are described with their key type and create time")
    void keyPairsAreDescribedWithTheirTypeAndCreateTime() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            String created = ec2.createKeyPair(r -> r.keyName(TestFixtures.uniqueName("typed-rsa"))).keyPairId();
            String imported = ec2.importKeyPair(r -> r.keyName(TestFixtures.uniqueName("typed-ed25519"))
                    .publicKeyMaterial(SdkBytes.fromUtf8String(ED25519_KEY + " someone@example.com"))).keyPairId();
            try {
                KeyPairInfo rsa = ec2.describeKeyPairs(r -> r.keyPairIds(created)).keyPairs().get(0);
                KeyPairInfo ed25519 = ec2.describeKeyPairs(r -> r.keyPairIds(imported)).keyPairs().get(0);

                assertThat(rsa.keyType()).isEqualTo(KeyType.RSA);
                assertThat(ed25519.keyType()).isEqualTo(KeyType.ED25519);
                // A minute either side: Floci may run in a container whose clock is not this one's.
                Instant after = Instant.now();
                assertThat(rsa.createTime()).isBetween(before.minusSeconds(60), after.plusSeconds(60));
                assertThat(ed25519.createTime()).isBetween(before.minusSeconds(60), after.plusSeconds(60));
            } finally {
                ec2.deleteKeyPair(r -> r.keyPairId(created));
                ec2.deleteKeyPair(r -> r.keyPairId(imported));
            }
        }
    }

    @Test
    @DisplayName("DescribeKeyPairs filters by key-name, key-type, fingerprint and tag")
    void describeKeyPairsHonoursItsFilters() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String prefix = TestFixtures.uniqueName("filtered");
            String rsaId = ec2.createKeyPair(r -> r.keyName(prefix + "-rsa")).keyPairId();
            String ed25519Id = ec2.importKeyPair(r -> r.keyName(prefix + "-ed25519")
                    .publicKeyMaterial(SdkBytes.fromUtf8String(ED25519_KEY))).keyPairId();
            try {
                ec2.createTags(r -> r.resources(ed25519Id).tags(t -> t.key("Team").value("blue")));
                Filter ours = Filter.builder().name("key-name").values(prefix + "-*").build();
                String fingerprint = ec2.describeKeyPairs(r -> r.keyPairIds(ed25519Id)).keyPairs().get(0).keyFingerprint();

                assertThat(ids(ec2, ours)).containsExactlyInAnyOrder(rsaId, ed25519Id);
                assertThat(ids(ec2, ours, Filter.builder().name("key-type").values("rsa").build()))
                        .containsExactly(rsaId);
                assertThat(ids(ec2, ours, Filter.builder().name("fingerprint").values(fingerprint).build()))
                        .containsExactly(ed25519Id);
                assertThat(ids(ec2, ours, Filter.builder().name("tag:Team").values("blue").build()))
                        .containsExactly(ed25519Id);
                assertThat(ids(ec2, ours, Filter.builder().name("tag-key").values("Team").build()))
                        .containsExactly(ed25519Id);
            } finally {
                ec2.deleteKeyPair(r -> r.keyPairId(rsaId));
                ec2.deleteKeyPair(r -> r.keyPairId(ed25519Id));
            }
        }
    }

    @Test
    @DisplayName("ImportKeyPair refuses an ECDSA key with InvalidKey.Format")
    void importKeyPairRefusesAnEcdsaKey() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            assertThatThrownBy(() -> ec2.importKeyPair(r -> r.keyName(TestFixtures.uniqueName("ecdsa"))
                    .publicKeyMaterial(SdkBytes.fromUtf8String(ECDSA_KEY))))
                    .isInstanceOfSatisfying(Ec2Exception.class, e -> {
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidKey.Format");
                        assertThat(e.awsErrorDetails().errorMessage())
                                .isEqualTo("Key is not in valid OpenSSH public key format");
                    });
        }
    }

    @Test
    @DisplayName("CreateKeyPair refuses a name longer than 255 characters")
    void createKeyPairRefusesAnOverlongName() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            assertThatThrownBy(() -> ec2.createKeyPair(r -> r.keyName("n".repeat(256))))
                    .isInstanceOfSatisfying(Ec2Exception.class, e ->
                            assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue"));
        }
    }

    private static List<String> ids(Ec2Client ec2, Filter... filters) {
        return ec2.describeKeyPairs(r -> r.filters(filters)).keyPairs().stream().map(KeyPairInfo::keyPairId).toList();
    }
}
