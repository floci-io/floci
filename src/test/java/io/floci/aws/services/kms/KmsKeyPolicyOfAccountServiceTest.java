package io.floci.aws.services.kms;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kms.model.KmsAlias;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KmsKeyPolicyOfAccountServiceTest {

    private static final String ACCOUNT = "111122223333";
    private static final String OTHER_ACCOUNT = "444455556666";
    private static final String REGION = "us-east-1";
    private static final String KEY_ID = "acc0a82a-f9ff-42a9-b9ee-8d53e5d69084";
    private static final String POLICY = "{\"Statement\":[]}";

    private AccountAwareStorageBackend<KmsKey> keys;
    private AccountAwareStorageBackend<KmsAlias> aliases;
    private KmsService service;

    @BeforeEach
    void setUp() {
        keys = AccountAwareStorageBackend.inMemory(ACCOUNT);
        aliases = AccountAwareStorageBackend.inMemory(ACCOUNT);
        service = new KmsService(keys, aliases, new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT));
        KmsKey key = new KmsKey();
        key.setKeyId(KEY_ID);
        key.setPolicy(POLICY);
        keys.put(REGION + "::" + KEY_ID, key);
        aliases.put(REGION + "::alias/shared", new KmsAlias("alias/shared", keyArn("alias/shared"), KEY_ID));
    }

    private static String keyArn(String resource) {
        return "arn:aws:kms:" + REGION + ":" + ACCOUNT + ":" + resource;
    }

    @Test
    void aKeyArnNamesTheKeyPolicyOfItsAccount() {
        assertEquals(Optional.of(POLICY), service.findKeyPolicyOfAccount(keyArn("key/" + KEY_ID)));
    }

    @Test
    void anAliasArnIsFollowedToItsKey() {
        assertEquals(Optional.of(POLICY), service.findKeyPolicyOfAccount(keyArn("alias/shared")));
    }

    @Test
    void anotherAccountsArnDoesNotReadTheKeyOfThisOne() {
        assertTrue(service.findKeyPolicyOfAccount(
                "arn:aws:kms:" + REGION + ":" + OTHER_ACCOUNT + ":key/" + KEY_ID).isEmpty());
    }

    @Test
    void aRegionThatHoldsNoSuchKeyFindsNothing() {
        assertTrue(service.findKeyPolicyOfAccount(
                "arn:aws:kms:eu-west-1:" + ACCOUNT + ":key/" + KEY_ID).isEmpty());
    }

    @Test
    void aMissingKeyAndADanglingAliasFindNothing() {
        aliases.put(REGION + "::alias/dangling",
                new KmsAlias("alias/dangling", keyArn("alias/dangling"), "00000000-0000-0000-0000-000000000000"));

        assertTrue(service.findKeyPolicyOfAccount(keyArn("key/00000000-0000-0000-0000-000000000000")).isEmpty());
        assertTrue(service.findKeyPolicyOfAccount(keyArn("alias/dangling")).isEmpty());
        assertTrue(service.findKeyPolicyOfAccount(keyArn("alias/unknown")).isEmpty());
    }

    @Test
    void aKeyWithoutAPolicyAndAnArnOfAnotherServiceFindNothing() {
        KmsKey bare = new KmsKey();
        bare.setKeyId("bare");
        keys.put(REGION + "::bare", bare);

        assertTrue(service.findKeyPolicyOfAccount(keyArn("key/bare")).isEmpty());
        assertTrue(service.findKeyPolicyOfAccount("arn:aws:s3:::" + KEY_ID).isEmpty());
        assertTrue(service.findKeyPolicyOfAccount(null).isEmpty());
    }

    @Test
    void storesThatAreNotAccountAwareFindNothing() {
        KmsService plain = new KmsService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT));

        assertTrue(plain.findKeyPolicyOfAccount(keyArn("key/" + KEY_ID)).isEmpty());
    }
}
