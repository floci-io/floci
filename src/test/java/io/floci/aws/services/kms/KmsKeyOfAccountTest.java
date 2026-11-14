package io.floci.aws.services.kms;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.floci.aws.services.kms.model.KmsAlias;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Another service reads a key its resource records, and the key's policy, in the account that resource is in, whoever
 * is calling: a shared snapshot's key is the owner's.
 */
class KmsKeyOfAccountTest {

    private static final String OWNER = "111122223333";
    private static final String CALLER = "444455556666";
    private static final String REGION = "us-east-1";

    private KmsService owners;
    private KmsService callers;
    private KmsKey key;

    @BeforeEach
    void setUp() {
        AccountAwareStorageBackend<KmsKey> keys = new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, OWNER);
        AccountAwareStorageBackend<KmsAlias> aliases = new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null,
                OWNER);
        owners = new KmsService(keys, aliases, new InMemoryStorage<>(), new RegionResolver(REGION, OWNER));
        callers = new KmsService(keys, aliases, new InMemoryStorage<>(), new RegionResolver(REGION, CALLER));
        key = owners.createKey("snapshot key", REGION);
        owners.createAlias("alias/snapshots", key.getKeyId(), REGION);
    }

    @Test
    void aKeyIsFoundByItsIdAliasOrArnInTheAccountItIsIn() {
        String arn = key.getArn();
        assertEquals(Optional.of(arn), callers.findKeyArnOfAccount(key.getKeyId(), OWNER, REGION));
        assertEquals(Optional.of(arn), callers.findKeyArnOfAccount("alias/snapshots", OWNER, REGION));
        assertEquals(Optional.of(arn), callers.findKeyArnOfAccount(arn, CALLER, REGION));
        assertEquals(Optional.of(arn), callers.findKeyArnOfAccount(
                "arn:aws:kms:" + REGION + ":" + OWNER + ":alias/snapshots", CALLER, REGION));
        // Not the caller's key, nor one in another Region, nor AWS's own key, which no account holds.
        assertEquals(Optional.empty(), callers.findKeyArnOfAccount(key.getKeyId(), CALLER, REGION));
        assertEquals(Optional.empty(), callers.findKeyArnOfAccount(key.getKeyId(), OWNER, "eu-west-1"));
        assertEquals(Optional.empty(), callers.findKeyArnOfAccount("AWS_OWNED_KMS_KEY", OWNER, REGION));
        assertEquals(Optional.empty(), callers.findKeyArnOfAccount("alias/none", OWNER, REGION));
    }

    @Test
    void aKeysPolicyIsReadInTheAccountOfItsArn() {
        assertThat(callers.findKeyPolicy(key.getArn()).orElseThrow(), containsString(OWNER));
        assertEquals(Optional.empty(), callers.findKeyPolicy(key.getArn().replace(OWNER, CALLER)));
        assertEquals(Optional.empty(), callers.findKeyPolicy("arn:aws:kms:" + REGION + ":" + OWNER + ":alias/snapshots"));
        assertEquals(Optional.empty(), callers.findKeyPolicy("arn:aws:sqs:" + REGION + ":" + OWNER + ":queue"));
    }

    /**
     * A key's policy is its resource policy, owned by the account in its ARN even when that account holds no such key,
     * so another account's key is judged as cross-account; an alias or a key left open has none.
     */
    @Test
    void theProviderGivesAKeysPolicyAndOwnerAndNothingForAnAliasOrAnOpenKey() {
        KmsResourcePolicyProvider provider = new KmsResourcePolicyProvider(callers);
        List<ResourcePolicy> policies = provider.getResourcePolicies("redshift-serverless", key.getArn());
        assertEquals(1, policies.size());
        assertEquals(OWNER, policies.getFirst().ownerAccountId());
        assertThat(policies.getFirst().policyDocument(), containsString("kms:*"));
        String missing = "arn:aws:kms:" + REGION + ":777788889999:key/" + key.getKeyId();
        assertEquals(List.of(new ResourcePolicy(null, "777788889999")), provider.getResourcePolicies("kms", missing));
        assertTrue(provider.getResourcePolicies("kms", "arn:aws:kms:" + REGION + ":" + OWNER + ":alias/snapshots")
                .isEmpty());
        assertTrue(provider.getResourcePolicies("kms", "arn:aws:kms:" + REGION + ":" + OWNER + ":key/*").isEmpty());
        assertTrue(provider.getResourcePolicies("kms", "*").isEmpty());
    }

    @Test
    void theStoredResourceArnsFindAKeyInTheCallersAccountOrAnother() {
        KmsStoredResourceArns stored = new KmsStoredResourceArns(owners, new RegionResolver(REGION, OWNER));
        assertEquals("kms", stored.namespace());
        assertEquals(Optional.of(key.getArn()), stored.find("key", List.of(key.getKeyId()), REGION));
        assertEquals(Optional.of(key.getArn()), stored.findInAccount("key", List.of("alias/snapshots"), OWNER, REGION));
        assertEquals(Optional.empty(), stored.findInAccount("key", List.of(key.getKeyId()), CALLER, REGION));
        assertEquals(Optional.empty(), stored.find("alias", List.of("alias/snapshots"), REGION));
        assertFalse(stored.grantsItsAccountThroughServices(key.getArn()));
    }

    /** A key KMS would refuse a decrypt with, disabled or pending deletion, is none a forwarded request finds. */
    @Test
    void theStoredResourceArnsFindNoKeyKmsWouldNotUse() {
        KmsStoredResourceArns stored = new KmsStoredResourceArns(owners, new RegionResolver(REGION, OWNER));
        owners.disableKey(key.getKeyId(), REGION);
        assertEquals(Optional.empty(), stored.find("key", List.of(key.getKeyId()), REGION));
        owners.enableKey(key.getKeyId(), REGION);
        assertEquals(Optional.of(key.getArn()), stored.find("key", List.of(key.getKeyId()), REGION));
        owners.scheduleKeyDeletion(key.getKeyId(), 7, REGION);
        assertEquals(Optional.empty(), stored.find("key", List.of(key.getKeyId()), REGION));
        // Its lookup still finds it: only a request KMS would serve with it is refused.
        assertEquals(Optional.of(key.getArn()), owners.findKeyArnOfAccount(key.getKeyId(), OWNER, REGION));
    }

    /**
     * The reserved {@code alias/aws/redshift}, as a default copy grant records it, names AWS's managed key of the
     * account, which is there before anything read KMS, and whose policy lets the account use it through Redshift.
     */
    @Test
    void aReservedAliasNamesTheAccountsAwsManagedKeyWhichGrantsItsAccountThroughServices() {
        AccountAwareStorageBackend<KmsKey> keys = new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, OWNER);
        AccountAwareStorageBackend<KmsAlias> aliases = new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null,
                OWNER);
        KmsService fresh = new KmsService(keys, aliases, new InMemoryStorage<>(), new RegionResolver(REGION, OWNER));
        KmsStoredResourceArns stored = new KmsStoredResourceArns(fresh, new RegionResolver(REGION, OWNER));
        String managed = stored.find("key", List.of("arn:aws:kms:" + REGION + ":" + OWNER + ":alias/aws/redshift"),
                REGION).orElseThrow();
        assertTrue(fresh.isAwsManagedKey(managed));
        assertTrue(stored.grantsItsAccountThroughServices(managed));
        // Another account's reserved alias is not seeded for it, so it names no key here.
        assertEquals(Optional.empty(), fresh.findKeyArnOfAccount("alias/aws/redshift", CALLER, REGION));
    }
}
