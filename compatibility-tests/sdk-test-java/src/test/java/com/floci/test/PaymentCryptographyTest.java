package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.paymentcryptography.PaymentCryptographyClient;
import software.amazon.awssdk.services.paymentcryptography.model.Alias;
import software.amazon.awssdk.services.paymentcryptography.model.Key;
import software.amazon.awssdk.services.paymentcryptography.model.KeyAlgorithm;
import software.amazon.awssdk.services.paymentcryptography.model.KeyAttributes;
import software.amazon.awssdk.services.paymentcryptography.model.KeyCheckValueAlgorithm;
import software.amazon.awssdk.services.paymentcryptography.model.KeyClass;
import software.amazon.awssdk.services.paymentcryptography.model.KeyState;
import software.amazon.awssdk.services.paymentcryptography.model.KeyUsage;
import software.amazon.awssdk.services.paymentcryptography.model.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Payment Cryptography control plane through the AWS SDK.
 *
 * <p>The real service is an awsJson 1.0 service whose operations are targeted by
 * {@code PaymentCryptographyControlPlane.<Operation>}, so a real client is the only way to
 * prove that the wire format, the request shape and the answer shape all agree.
 */
@DisplayName("Payment Cryptography")
class PaymentCryptographyTest {

    private static PaymentCryptographyClient client;
    private static final List<String> keyArns = new ArrayList<>();
    private static final List<String> aliasNames = new ArrayList<>();

    @BeforeAll
    static void setup() {
        client = TestFixtures.paymentCryptographyClient();
    }

    @AfterAll
    static void cleanup() {
        if (client == null) {
            return;
        }
        for (String alias : aliasNames) {
            try {
                client.deleteAlias(a -> a.aliasName(alias));
            } catch (Exception ignored) {
                // The key may already be gone, and real AWS refuses to delete an alias of one.
            }
        }
        for (String arn : keyArns) {
            try {
                client.deleteKey(k -> k.keyIdentifier(arn));
            } catch (Exception ignored) {
                // Real AWS wants a key disabled before it is deleted, and wants it gone by now.
            }
        }
        client.close();
    }

    private static Key createAesKey() {
        Key key = client.createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(KeyAlgorithm.AES_256)
                        .keyClass(KeyClass.SYMMETRIC_KEY)
                        .keyUsage(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY)
                        .keyModesOfUse(m -> m.encrypt(true).decrypt(true)))
                .exportable(true))
                .key();
        keyArns.add(key.keyArn());
        return key;
    }

    /** The key identifier of an ARN: the part after the last slash. */
    private static String keyIdOf(String arn) {
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    @Test
    void aKeyIsCreatedAndReadBackWithItsModesOfUse() {
        Key created = createAesKey();

        assertThat(created.keyArn()).isNotBlank();
        assertThat(created.keyState()).isEqualTo(KeyState.CREATE_COMPLETE);
        assertThat(created.keyCheckValueAlgorithm()).isEqualTo(KeyCheckValueAlgorithm.CMAC);
        assertThat(created.keyCheckValue()).isNotBlank();
        assertThat(created.enabled()).isTrue();
        assertThat(created.exportable()).isTrue();

        KeyAttributes attributes = created.keyAttributes();
        assertThat(attributes.keyAlgorithm()).isEqualTo(KeyAlgorithm.AES_256);
        assertThat(attributes.keyClass()).isEqualTo(KeyClass.SYMMETRIC_KEY);
        assertThat(attributes.keyUsage()).isEqualTo(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY);
        // The modes of use are a nested structure, and the answer must carry the ones asked for.
        assertThat(attributes.keyModesOfUse()).isNotNull();
        assertThat(attributes.keyModesOfUse().encrypt()).isTrue();
        assertThat(attributes.keyModesOfUse().decrypt()).isTrue();

        Key read = client.getKey(k -> k.keyIdentifier(created.keyArn())).key();
        assertThat(read.keyArn()).isEqualTo(created.keyArn());
        assertThat(read.keyAttributes().keyAlgorithm()).isEqualTo(KeyAlgorithm.AES_256);
        assertThat(read.keyAttributes().keyModesOfUse().encrypt()).isTrue();
        assertThat(read.keyAttributes().keyModesOfUse().decrypt()).isTrue();
        assertThat(read.keyCheckValue()).isEqualTo(created.keyCheckValue());
    }

    @Test
    void theKeysOfTheRegionAreListed() {
        Key key = createAesKey();

        assertThat(client.listKeys(r -> r.maxResults(100)).keys())
                .anySatisfy(listed -> assertThat(listed.keyArn()).isEqualTo(key.keyArn()));
    }

    @Test
    void theAliasOfAKeyIsCreatedAndReadBack() {
        Key key = createAesKey();
        String alias = "alias/compat/" + keyIdOf(key.keyArn());
        aliasNames.add(alias);

        client.createAlias(a -> a.aliasName(alias).keyArn(key.keyArn()));

        Alias read = client.getAlias(a -> a.aliasName(alias)).alias();
        assertThat(read.aliasName()).isEqualTo(alias);
        assertThat(read.keyArn()).isEqualTo(key.keyArn());
        assertThat(client.listAliases(r -> r.maxResults(100)).aliases())
                .anySatisfy(listed -> assertThat(listed.aliasName()).isEqualTo(alias));
    }

    @Test
    void aKeyIsTaggedAndTheTagsAreListed() {
        Key key = createAesKey();

        client.tagResource(t -> t
                .resourceArn(key.keyArn())
                .tags(Tag.builder().key("owner").value("compat").build()));

        Map<String, String> tags = client.listTagsForResource(t -> t.resourceArn(key.keyArn())).tags()
                .stream()
                .collect(Collectors.toMap(Tag::key, Tag::value, (a, b) -> a));
        assertThat(tags).containsEntry("owner", "compat");
    }

    /**
     * A region the creating client did not sign for. It has to differ from the
     * region of the suite, or the key would be in that region too and the key
     * would be found.
     */
    private static Region anotherRegion() {
        Region region = TestFixtures.region();
        if (region.id().startsWith("cn-")) {
            return region.id().equals("cn-northwest-1")
                    ? Region.of("cn-north-1") : Region.of("cn-northwest-1");
        }
        return Region.US_WEST_2.equals(region) ? Region.US_EAST_1 : Region.US_WEST_2;
    }

    @Test
    void aKeyOfOneRegionIsNotFoundInAnother() {
        Key key = createAesKey();
        PaymentCryptographyClient elsewhere = TestFixtures.paymentCryptographyClient(anotherRegion());
        try {
            assertThatThrownBy(() -> elsewhere.getKey(k -> k.keyIdentifier(keyIdOf(key.keyArn()))))
                    .isInstanceOf(AwsServiceException.class)
                    .satisfies(thrown -> assertThat(((AwsServiceException) thrown).awsErrorDetails()
                            .errorCode()).isEqualTo("ResourceNotFoundException"));
        } finally {
            elsewhere.close();
        }
    }
}
